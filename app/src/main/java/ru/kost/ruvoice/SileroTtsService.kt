package ru.kost.ruvoice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.ServiceInfo
import android.os.PowerManager
import android.os.Build
import android.media.AudioFormat
import android.os.Handler
import android.os.Looper
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.Voice
import android.text.Spanned
import android.text.style.TtsSpan
import android.util.Log
import ru.kost.ruvoice.audio.Pcm
import ru.kost.ruvoice.audio.Tempo
import ru.kost.ruvoice.text.*
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future

object Pipeline {
    // Маркер паузы {pause:N} (N — мс) на границе предложений режет текст сегмента на куски
    // с тишиной между ними (см. plan ниже); внутри предложения остаётся в тексте, и Marks
    // делает из него запятую заданной длины — фраза синтезируется целиком, с интонацией.
    private val pauseMarker = Marks.pauseRe
    private val sentenceTail = Regex("[.!?…]$|\\{pause:\\d+\\}$")
    private fun isBoundary(txt: String, m: MatchResult): Boolean {
        val before = txt.substring(0, m.range.first).trimEnd()
        val after = txt.substring(m.range.last + 1).trimStart()
        return before.isEmpty() || sentenceTail.containsMatchIn(before) || after.isEmpty() || pauseMarker.matchAt(after, 0) != null
    }
    // Прямая речь: после trim — тире/дефис с пробелом или открывающая кавычка.
    private val speechStart = Regex("^([—–-]\\s|[«\"“„])")
    // Тире + строчная буква в начале — авторский хвост, который Splitter отрезал по «!»/«?»
    // («— Привет! — сказал он.»), а не новая реплика.
    private val authorStart = Regex("^[—–-]\\s+\\p{Ll}")
    // Реплика с авторской вставкой: «— Пойдём, — сказал он, — нам пора.» режется по « — » на
    // куски, чётные — речь, нечётные — автор. Перед таким тире всегда стоит знак («, —», «! —»,
    // «. —»), перед тире внутри самой реплики («— Нам пора — уже поздно.») — нет, по нему не режем.
    private val speechDash = Regex("(?<=[,.!?…»\"“”])\\s[—–-]\\s")
    // Авторская вставка внутри предложения (« — » + строчная): читалка отдаёт по предложению, и
    // «Как дела, — спросил он.» приходит без начала реплики — вставка и есть признак речи.
    // Экспериментально: «Он вышел, — и дверь хлопнула.» тоже попадёт в речь.
    private val authorInsert = Regex("(?<=[,.!?…»\"“”])\\s[—–-]\\s\\p{Ll}")
    private val closingQuote = Regex("[»\"”]$")
    /** cont — предложение идёт следом за незакрытой репликой (или перед авторским хвостом), это
     * её продолжение без тире/кавычки в начале. */
    private fun speechPieces(s: String, rules: Rules, cont: Boolean): List<Pair<String, Boolean>> {
        val t0 = s.trim()
        // маркер {prosody} перед репликой (из SSML) — не часть текста, тире ищем за ним
        val prefix = Marks.prosodyRe.matchAt(t0, 0)?.value ?: ""
        val t = t0.substring(prefix.length)
        if (!rules.on("speech") || authorStart.containsMatchIn(t)) return listOf(t0 to false)
        if (!speechStart.containsMatchIn(t) && !cont && !authorInsert.containsMatchIn(t)) return listOf(t0 to false)
        return speechDash.split(t).mapIndexed { i, p -> (if (i == 0) prefix + p else p) to (i % 2 == 0) }
    }

    /** Маркер {prosody:R:P} действует до следующего; после разбиения на предложения его надо
     * повторить в начале каждого следующего сегмента, пока не встретится сброс. */
    private fun carryProsody(segments: List<Segment>): List<Segment> {
        var state: String? = null
        return segments.map { seg ->
            val text = if (state != null && seg.text.isNotBlank() && Marks.prosodyRe.matchAt(seg.text, 0) == null) state + seg.text else seg.text
            Marks.prosodyRe.findAll(seg.text).lastOrNull()?.let { state = it.value.takeIf { v -> v != "{prosody}" } }
            if (text === seg.text) seg else seg.copy(text = text)
        }
    }

    // Запрос из одной буквы — так TalkBack шлёт букву под курсором, эхо ввода, клавишу экранной
    // клавиатуры: строчную как есть, заглавную по умолчанию как «прописная буква Б.»
    // (talkback: CompositorUtils.prependCapital, template_capital_letter в values-ru). Читаем имя
    // буквы. Внутри текста одиночную букву не трогаем — там «в», «с», «к» предлоги, «б», «ж» частицы.
    // Jieshuo шлёт заглавную как «Ц Заглавная» (logcat 25.09.2026); «заглавная» принимаем и перед буквой.
    private val loneLetter = Regex("""^\s*((?:(?:прописная|заглавная)(?:\s+буква)?\s+)?)(\p{L})(\s*,?\s+(?:заглавная|прописная)(?:\s+буква)?)?\s*[.)]?\s*$""", RegexOption.IGNORE_CASE)

    /** englishWords > 0 — выделять английские куски от стольких слов в отдельные сегменты (Segment.en,
     * правила en_proxy_*): решает сервис, только когда есть движок для английского. 0 — не выделять,
     * «Книге с ударениями» они не нужны. */
    fun plan(text: CharSequence, d: SileroData, sentencePauseMs: Int, paragraphPauseMs: Int,
             replacements: Replacements = Replacements.parse(emptyList()), rules: Rules = Rules(), englishWords: Int = 0): List<Segment> {
        val src = text.toString().let { t ->
            if (!rules.on("letter_name")) t else loneLetter.matchEntire(t)?.let { m ->
                m.groupValues[2][0].let { c -> if (m.groupValues[1].isEmpty() && m.groupValues[3].isEmpty()) Abbrev.loneLetterName(c) else Abbrev.letterName(c) }
                    ?.let { name ->
                        val pre = m.groupValues[1].lowercase()
                        val post = m.groupValues[3].lowercase().trim(' ', ',', '\t', '\n')
                        // «Заглавная А» — имя буквы вперёд, «+а, заглавная»: так звучит Jieshuo, и буква не теряется в начале;
                        // запятая — пауза между буквой и словом
                        if (pre.startsWith("заглавная")) "$name, ${pre.trim()}" else pre + name + if (post.isEmpty()) "" else ", $post"
                    } }
                // эхо ввода/удаления: «Удаление заглавная Р», «Р удалено» — буква с именем, иначе «ррр»
                ?: LetterEcho.rewrite(t)
                // одиночный знак (клавиша «#», знак под курсором) — по имени, иначе фильтр оставит тишину
                ?: t.trim().singleOrNull()?.let { SymbolNames.of(it) } ?: t
        }
        // Последний абзац запроса намеренно без паузы абзаца — свою паузу до следующей
        // реплики читалка/пользователь и так делают между вызовами.
        val segments = if (rules.on("ssml") && Ssml.isSsml(src)) Ssml.parse(src) else
            Splitter.paragraphs(src, rules).mapIndexed { i, p -> Segment(p, paragraph = true) }.let { if (it.isEmpty()) it else it.dropLast(1) + it.last().copy(paragraph = false) }
        val out = ArrayList<Segment>()
        for (seg in segments) {
            // Реплика в абзаце тянется через предложения, пока её не закроет авторский хвост или кавычка.
            var openReply = false
            // Замены — до разбиения на предложения; маркер {pause:N} (пришедший из замены или
            // стоявший прямо в тексте) режет результат на куски, между которыми — пауза N мс.
            val txt = replacements.apply(seg.text)
            val pieces = ArrayList<String>(); val pauses = ArrayList<Int>()
            var from = 0
            for (m in pauseMarker.findAll(txt)) {
                if (!isBoundary(txt, m)) continue
                pieces += txt.substring(from, m.range.first); from = m.range.last + 1
                pauses += m.groupValues[1].toLongOrNull()?.coerceIn(0, 10000)?.toInt() ?: 10000
            }
            pieces += txt.substring(from)
            for ((pi, piece) in pieces.withIndex()) {
                val lastPiece = pi == pieces.size - 1
                val sents = Splitter.sentences(piece, rules)
                for ((i, s) in sents.withIndex()) {
                    val lastInPiece = i == sents.size - 1
                    // {pause:N} на конце абзаца — маркер про паузу предложения (ruling, task 26/п.11
                    // финального фикса), но если следом только пустой хвост (маркер и правда в конце
                    // текста абзаца, не разрывает его на два), пауза абзаца и флаг paragraph должны
                    // сохраниться, а не потеряться вместе с обычной веткой ниже.
                    val markerEndsParagraph =
                        lastInPiece && !lastPiece && pi + 1 == pieces.size - 1 && pieces[pi + 1].isBlank()
                    val last = (lastInPiece && lastPiece) || markerEndsParagraph
                    val breakMs = if (lastInPiece && !lastPiece)
                        pauses[pi] + (if (markerEndsParagraph && seg.paragraph) paragraphPauseMs else 0)
                        else sentencePauseMs + (if (last) seg.breakMs else 0) + (if (last && seg.paragraph) paragraphPauseMs else 0)
                    val parts = speechPieces(s, rules, openReply || sents.getOrNull(i + 1)?.let { authorStart.containsMatchIn(it.trim()) } == true)
                    openReply = parts.last().second && !closingQuote.containsMatchIn(parts.last().first.trimEnd())
                    for ((k, part) in parts.withIndex()) {
                        val lastPart = k == parts.size - 1
                        // английский кусок посреди реплики — свой сегмент; обрывки из одних знаков («», »)
                        // русскому движку читать нечего, их пауза уходит соседу
                        val langs = if (englishWords <= 0) listOf(part.first to false) else English.split(part.first, englishWords).let { l ->
                            if (l.size == 1) l else l.filter { it.second || !English.isNoise(it.first) }.map { it.first.trim() to it.second } }
                        for ((li, lp) in langs.withIndex()) {
                            val lastLang = lastPart && li == langs.size - 1
                            out += Segment(lp.first, breakMs = if (lastLang) breakMs else 0,
                                paragraph = lastLang && last && seg.paragraph, speech = part.second, en = lp.second)
                        }
                        if (langs.isEmpty() && lastPart && breakMs > 0) out += Segment("", breakMs = breakMs, paragraph = last && seg.paragraph)
                    }
                }
                if (sents.isEmpty()) {
                    if (!lastPiece) {
                        // кусок пуст — маркер в начале текста или два маркера подряд; пауза N уходит
                        // в предыдущий добавленный сегмент, а если его ещё нет — заводим пустой
                        val n = pauses[pi]
                        if (out.isNotEmpty()) out[out.size - 1] = out.last().copy(breakMs = out.last().breakMs + n)
                        else out += Segment("", breakMs = n)
                    } else if (seg.breakMs > 0) out += seg.copy(text = "")
                }
            }
        }
        if (rules.on("fast_start")) fastStart(out)
        return carryProsody(out)
    }

    /** Первый сегмент запроса короче остальных: звук уходит читалке только после счёта всего
     * сегмента, и max_len символов на слабом телефоне — секунды тишины перед началом. Хвост
     * считается, пока играет голова. Режется как limit, по запятой, но один раз. */
    private fun fastStart(out: ArrayList<Segment>) {
        val i = out.indexOfFirst { it.text.isNotBlank() }
        if (i < 0 || out[i].text.length <= Rules.FAST_START_LEN) return
        val seg = out[i]; val c = Splitter.cut(seg.text, Rules.FAST_START_LEN)
        out[i] = seg.copy(text = seg.text.substring(c + 1).trim())
        out.add(i, Segment(seg.text.substring(0, c + 1).trim().trimEnd(','), speech = seg.speech, en = seg.en))
    }

    /** Текст сегмента → слова для модели с ударениями (до Stress.forModel), тем же путём, что synthSegment. */
    fun accent(text: String, d: SileroData, stress: Stress, allowed: String, rules: Rules): String {
        var t = if (rules.on("exclaim")) Marks.exclaim(text) else text
        if (rules.on("question") && SentenceType.classify(t, d, rules) == "general_q") t = Marks.question(t)
        val marks = Marks.parse(t, rules.focusLevel)
        return stress.apply(Normalizer.prepare(marks.text, allowed, rules), marks.text)
    }
}

class SileroTtsService : TextToSpeechService() {
    // by lazy: TextToSpeechService.onCreate() зовёт onLoadLanguage раньше тела нашего onCreate.
    private val models: SileroModels by lazy { SileroModels.shared(this) }
    private val prefs: Prefs by lazy { Prefs(this) }
    private val handler = Handler(Looper.getMainLooper())
    private val synthPool = Executors.newSingleThreadExecutor()
    @Volatile private var stopped = false
    private var foreground = false
    // Аудиовыход телефона уходит в standby через ~3 с тишины, а после пробуждения HAL плавно
    // поднимает громкость — первое слово фразы выходит тихим. Если с прошлого звука прошло
    // больше LEAD_GAP_MS, начинаем с LEAD_IN_MS тишины, чтобы подъём пришёлся на неё (правило lead_in).
    // ponytail: пороги под AOSP standby 3 с; сделать настройкой, если на другом телефоне не совпадёт.
    private var lastAudioAt = 0L
    // Выгрузка на отдельном потоке: release() и synthesize() делят монитор models, поэтому
    // release() просто дождётся текущего forward, а не заблокирует main на его время.
    private val unload = Runnable {
        Thread { models.release(); english.release(); Log.i(SileroModels.TAG, "модели выгружены по простою") }.start()
    }
    private val english: EnglishProxy by lazy { EnglishProxy.shared(this) }

    /** Пока идёт чтение, сервис — foreground. Без этого при погасшем экране процесс
     * уезжает в sched group Restricted (cpuset может не включать быстрые ядра), forward замедляется в разы
     * и паузы между предложениями растут. Снимается вместе с выгрузкой моделей по простою. */
    private fun screenOff() = !getSystemService(PowerManager::class.java).isInteractive

    private fun enterForeground() {
        if (foreground) return
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(CHANNEL) == null)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.app_name), NotificationManager.IMPORTANCE_MIN))
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.fg_reading))
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(true)
            .setOngoing(true)
            .build()
        // Android 12+ запрещает старт foreground-сервиса из фона: если читалка сама не на виду, ловим
        // исключение и читаем как раньше.
        foreground = runCatching {
            ServiceCompat.startForeground(this, FG_ID, n,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0)
            true
        }.onFailure { note("startForeground не дали: ${it.javaClass.simpleName}") }.getOrDefault(false)
        note(if (foreground) "foreground включён" else "foreground не включён")
    }

    private fun leaveForeground() {
        if (!foreground) return
        foreground = false
        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
    }

    override fun onCreate() {
        super.onCreate()
        Thread { runCatching { warmUp() }.onFailure { Log.e(SileroModels.TAG, "прогрев", it) } }.start()
        // 0.14.18 по ошибке ушёл с отладочной записью всего звука в files/tee/*.pcm — вычищаем
        Thread { runCatching { getExternalFilesDir(null)?.let { java.io.File(it, "tee").deleteRecursively() } } }.start()
    }

    private fun warmUp() {
        // словари разбираются раз на процесс; большие списки — секунда на телефоне, лучше
        // потратить её сейчас, чем на первой фразе
        prefs.userDict(); prefs.replacements()
        // греем тройку голоса из настроек, а не штатную: иначе первый запрос перегружает 90 МБ
        val v = currentSpeaker() ?: return   // lite без пака: голосов нет
        synchronized(models) {
            models.ensureLoaded(v.pack)
            val seq = v.sym.sequence("прив+ет.")
            models.synthesize(seq, v.id, prefs.sampleRate, FloatArray(seq.size) { 1f }, FloatArray(seq.size) { 1f }, LongArray(seq.size), LongArray(seq.size), emptyMap(), v.types)
        }
        scheduleUnload()
    }

    /** Снятие foreground после простоя — отдельно от выгрузки моделей: та может быть выключена
     * в настройках, а уведомление «идёт чтение» висеть вечно не должно. */
    private val fgOff: Runnable = Runnable {
        // При погасшем экране снимать нельзя: поднять обратно система уже не даст, а после паузы
        // чтение продолжат в том же фоне. Ждём, пока на телефон посмотрят.
        if (screenOff()) handler.postDelayed(fgOff, FG_IDLE_MS) else leaveForeground()
    }

    private fun scheduleUnload() {
        handler.removeCallbacks(unload)
        handler.removeCallbacks(fgOff)
        handler.postDelayed(fgOff, FG_IDLE_MS)
        // «Не выгружать, пока работает экранный чтец» важнее общей выгрузки по простою: первая фраза
        // TalkBack после паузы иначе ждёт загрузки модели
        if (prefs.rules().on("sr_keep_loaded") && ScreenReaders.anyActive(this)) return
        if (prefs.idleOn) handler.postDelayed(unload, prefs.idleMinutes.coerceAtLeast(1) * 60_000L)
    }

    override fun onDestroy() { handler.removeCallbacks(unload); handler.removeCallbacks(fgOff); leaveForeground(); stopped = true; synthPool.shutdownNow(); models.release(); english.release(); super.onDestroy() }

    // Binder-loadLanguage у TextToSpeechService отдаёт клиенту именно этот ответ (onLoadLanguage
    // в очереди, его результат выбрасывается), поэтому «голосов нет» (lite без пака) — здесь.
    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int =
        if (lang != "rus") TextToSpeech.LANG_NOT_SUPPORTED
        else if (!Speaker.hasVoices(packs())) TextToSpeech.LANG_MISSING_DATA
        else TextToSpeech.LANG_COUNTRY_AVAILABLE
    override fun onGetLanguage(): Array<String> = arrayOf("rus", "RUS", "")
    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int {
        val r = onIsLanguageAvailable(lang, country, variant)
        // TextToSpeechService.onCreate() зовёт этот метод синхронно на главном потоке — грузить
        // модели прямо тут нельзя, это надолго заблокирует главный поток. Прогрев (onCreate выше)
        // и так грузит их отдельным потоком, поэтому с главного потока просто отвечаем по языку.
        if (r == TextToSpeech.LANG_COUNTRY_AVAILABLE && Looper.myLooper() != Looper.getMainLooper()) {
            val s = currentSpeaker() ?: return TextToSpeech.LANG_MISSING_DATA   // lite без пака: читалка предложит установить данные
            runCatching { models.ensureLoaded(s.pack) }.onFailure {
                Log.e(SileroModels.TAG, "загрузка моделей", it); return TextToSpeech.LANG_NOT_SUPPORTED
            }
            scheduleUnload()
        }
        return r
    }

    private fun packs() = Packs.installed(filesDir)
    /** Голос из настроек; голос удалённого пака — штатный по умолчанию; null — голосов нет (lite без пака). */
    private fun currentSpeaker(): Speaker? = Speaker.resolve(prefs.voice, models.data, packs()) ?: Speaker.default(models.data, packs())

    /** Тройка моделей голоса; пак, который не грузится (битый файл, чужой рантайм) — читаем штатным
     * голосом этот запрос, в prefs ничего не меняем. */
    private fun load(s: Speaker): Speaker {
        try { models.ensureLoaded(s.pack); return s } catch (e: Exception) {
            if (s.pack == null) throw e
            Log.e(SileroModels.TAG, "пак ${s.pack.id} не загрузился, читаю штатным голосом", e)
            // lite: без встроенной модели читать нечем
            return Speaker.default(models.data, packs())?.also { models.ensureLoaded(it.pack) } ?: throw e
        }
    }

    override fun onGetVoices(): List<Voice> = Speaker.names(models.data, packs()).map {
        Voice(Speaker.ttsName(it), Locale("ru", "RU"), Voice.QUALITY_HIGH, Voice.LATENCY_NORMAL, false, emptySet())
    }
    override fun onIsValidVoiceName(name: String?): Int =
        if (Speaker.resolve(Speaker.fromTtsName(name, models.data, packs()), models.data, packs()) != null) TextToSpeech.SUCCESS else TextToSpeech.ERROR
    override fun onLoadVoice(name: String?): Int = onIsValidVoiceName(name)
    override fun onGetDefaultVoiceNameFor(lang: String?, country: String?, variant: String?): String = Speaker.ttsName(currentSpeaker()?.name ?: Speaker.DEFAULT)

    override fun onStop() { stopped = true }

    /** Текст запроса с подставленными TtsSpan (SpanSay): TYPE_TEXT пунктуации TalkBack, телефоны,
     * время, даты, деньги, «по цифрам» и прочие размеченные приложением куски. */
    private fun spokenText(cs: CharSequence?): SpanText.Mapped {
        val t = cs?.toString().orEmpty()
        if (cs !is Spanned) return SpanText.mapped(t, emptyList())
        val spans = cs.getSpans(0, cs.length, TtsSpan::class.java).mapNotNull { sp ->
            val type = spanTypes[sp.type] ?: return@mapNotNull null
            val b = sp.args
            val args = spanArgs.entries.associate { (k, v) -> v to b.get(k)?.let { x -> spanValues[x.toString()] ?: x } }
            runCatching { SpanSay.say(type, args) }.getOrNull()?.let { Triple(cs.getSpanStart(sp), cs.getSpanEnd(sp), it) }
        }
        return SpanText.mapped(t, spans)
    }

    override fun onSynthesizeText(request: SynthesisRequest, callback: SynthesisCallback) {
        stopped = false
        handler.removeCallbacks(unload)
        handler.removeCallbacks(fgOff)
        // Поднимаем сразу: при погасшем экране система запрещает старт foreground-сервиса из фона
        // (Background started FGS: Disallowed), а нужен он именно тогда — там процесс уводят
        // в Restricted и синтез перестаёт успевать за воспроизведением.
        handler.post { enterForeground() }
        val t0 = System.currentTimeMillis()
        try {
            val d = models.data
            val sr = prefs.sampleRate
            val voice = load(Speaker.resolve(Speaker.fromTtsName(request.voiceName, d, packs()), d, packs()) ?: currentSpeaker() ?: run {
                Log.e(SileroModels.TAG, "голосов нет: сборка без модели и без пака"); callback.error(TextToSpeech.ERROR_NOT_INSTALLED_YET); return
            })
            val speakerId = voice.id
            val sym = voice.sym
            // Экранный чтец (TalkBack и др.) — свои правила, темп и высота поверх общих (секция «Чтение с экрана»)
            val caller = ScreenReaders.caller(this, request.callerUid)
            val screenReader = caller != null && ScreenReaders.isScreenReader(prefs, caller)
            caller?.let { prefs.rememberCaller(it) }
            // TalkBack шлёт темп до ×6 (и умножает на системный) — для чтеца потолок ×6, книгам ×3 как было
            val rate = if (screenReader) (request.speechRate / 100f * prefs.srRate).coerceIn(0.5f, SR_MAX_RATE)
                else (request.speechRate / 100f * prefs.rate).coerceIn(0.5f, 3f)
            val pitch = (request.pitch / 100f * (if (screenReader) prefs.srPitch else prefs.pitch)).coerceIn(0.5f, 2f)
            // громкость читалки (KEY_PARAM_VOLUME) применяет сам плеер Android; наша — поверх, в звуке модели
            val volume = (if (screenReader) prefs.srVolume else prefs.volume).coerceIn(0.5f, 2f)
            // настройки слушают слово «как модель», без пользовательского словаря
            val noDict = request.params?.getString("ruvoice.nodict") == "1"
            val baseRules = prefs.rules()
            val rules = if (screenReader) baseRules.screenReader() else baseRules
            val noPauses = screenReader && baseRules.on("sr_pauses_off")
            models.threads = if (rules.on("fast_cores")) SileroModels.fastCores else Runtime.getRuntime().availableProcessors()
            val stress = Stress(d, models, if (noDict) emptyMap() else prefs.userDict(), rules)
            // вкладка «Проверка»: имена — по исходному тексту сегмента
            val audit = prefs.audit; val auditNames = prefs.auditNames && !noDict; val known = Audit.known(d, if (noDict) emptyMap() else prefs.userDict())
            val replacements = prefs.replacements()
            // Голос/темп/питч прямой речи — читаем один раз на запрос, как replacements.
            val quoteSpeakerId = Speaker.resolve(prefs.quoteVoice, d, packs())?.takeIf { it.pack?.id == voice.pack?.id }?.id
            val quoteRate = prefs.quoteRate
            val quotePitch = prefs.quotePitch
            // TtsSpan (пунктуация TalkBack «Все») — текстом; смещения rangeStart считаются по нему же
            val spoken = spokenText(request.charSequenceText)
            val reqText = spoken.text
            // Английские куски — другому движку, отдельно для экранного чтеца и для книг (en_proxy_sr / en_proxy_books);
            // нет движка — читаем по-русски, как раньше
            val enEngine = if (baseRules.on(if (screenReader) "en_proxy_sr" else "en_proxy_books")) EnglishProxy.resolve(this, prefs.enEngine) else null
            val enVoice = prefs.enVoice
            val enWords = if (enEngine == null) 0 else prefs.enMinWords.coerceIn(English.MIN_WORDS, English.MAX_WORDS)
            val segments = Pipeline.plan(reqText, d, if (noPauses) 0 else prefs.sentencePauseMs, if (noPauses) 0 else prefs.paragraphPauseMs, replacements, rules, enWords)
            // Паузы после запятой и на тире — явная длительность самого знака в кадрах модели (Marks.frames);
            // ноль — как решит модель. Дефис/минус в пробелах Normalizer.punctuation уже свёл к «–».
            val pauseFrames = HashMap<Int, Long>()
            fun pause(ms: Int, vararg chars: Char) { if (ms > 0) for (c in chars) sym.symbolToId[c]?.let { pauseFrames[it] = Marks.frames(ms) } }
            pause(prefs.commaPauseMs, ',', *(if (rules.on("pause_semicolon")) charArrayOf(';', ':') else charArrayOf()))
            pause(prefs.dashPauseMs, '–', '—')
            // Слова запроса для подсветки читаемого слова (rangeStart): ключ и смещения в тексте.
            // SSML-теги и маркеры заменяются пробелами той же длины, чтобы смещения не поехали.
            val srcText = reqText.let { if (rules.on("ssml") && Ssml.isSsml(it)) Ssml.blankTags(it) else it }
                .let { Marks.blank(it) }
            // смещения — в тексте клиента: подставленное из TtsSpan слово указывает на свой знак
            val srcWords = Regex("\\S+").findAll(srcText).map { Triple(Marks.key(it.value), spoken.orig(it.range.first), spoken.orig(it.range.last) + 1) }.toList()
            val matcher = Marks.Matcher(srcWords.map { it.first })
            var written = 0L // сэмплов отдано читалке — точка отсчёта markerInFrames
            var audioMs = 0L // длительность отданного звука — для журнала
            // Чистое время синтеза: в него не входит ожидание плеера (audioAvailable блокирует, пока
            // непроигранного больше 500 мс), поэтому RTF = синтез / звук показывает, успевает ли телефон.
            val synthMs = java.util.concurrent.atomic.AtomicLong()
            if (callback.start(sr, AudioFormat.ENCODING_PCM_16BIT, 1) != TextToSpeech.SUCCESS) { stopped = true; return }
            if (rules.on("lead_in") && System.currentTimeMillis() - lastAudioAt > LEAD_GAP_MS) { val sil = Pcm.silence(sr, LEAD_IN_MS); if (!write(callback, sil)) return; written += sil.size }
            // Короткая фраза уже звучала с теми же голосом, темпом и настройками — отдаём готовый звук.
            // Сбор имён («Проверка») и прослушивание без словаря идут мимо кэша.
            val cacheKey = if (reqText.length <= PhraseCache.MAX_TEXT && !noDict && !auditNames)
                listOf(prefs.stamp(), System.identityHashCode(prefs.userDict()), System.identityHashCode(replacements),
                    voice.name, sr, rate, pitch, screenReader, reqText).joinToString("\u0001") else null
            cacheKey?.let { phrases.get(it) }?.let { hit ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    for (k in hit.ranges.indices step 3) callback.rangeStart((written + hit.ranges[k]).toInt(), hit.ranges[k + 1], hit.ranges[k + 2])
                if (!write(callback, hit.pcm)) return
                callback.done()
                note("запрос ${request.charSequenceText.length} симв. из кэша, ${System.currentTimeMillis() - t0} мс, звук ${hit.pcm.size * 1000L / sr} мс" +
                    (caller?.let { ", от ${it.pkg}" + if (screenReader) " (экранный чтец)" else "" } ?: ""))
                return
            }
            val recorder = cacheKey?.let { PhraseCache.Recorder() }
            var enCount = 0
            // Английский кусок чужим движком: готовый PCM на нашей частоте и слова с долей позиции для подсветки.
            // null — не вышло; тогда сегмент читает Silero, латиница транслитерируется, как без правила.
            fun proxySegment(seg: Segment, engine: EnglishProxy.Engine): Proxied? {
                val text = Marks.parse(seg.text, 0).text.trim()
                if (text.isEmpty()) return null
                val segRate = rate * (if (seg.speech) quoteRate else 1f)
                val segPitch = pitch * (if (seg.speech) quotePitch else 1f)
                val a = english.synth(text, engine.pkg, enVoice, segRate, segPitch) { stopped } ?: return null
                val audio = Pcm.toFloat(Pcm.resample(a.pcm, a.sampleRate, sr))
                Pcm.gain(audio, volume)
                Pcm.fadeEdges(audio, sr, 5)
                enCount++
                return Proxied(Pcm.toPcm16(audio), Regex("\\S+").findAll(text).map { Marks.key(it.value) to it.range.first.toDouble() / text.length }.toList())
            }
            // Звук сегмента и токены для подсветки; null — нечего читать или синтез упал.
            fun synthSegment(seg: Segment): Pair<SileroModels.Synth, List<Marks.Token>>? {
                if (stopped) return null
                val tSeg = System.currentTimeMillis()
                try {
                // Замены Pipeline.plan уже применил к seg.text; тип предложения классифицируется
                // по этому же тексту — так и надо.
                var text = if (rules.on("exclaim")) Marks.exclaim(seg.text) else seg.text
                if (rules.on("question") && SentenceType.classify(text, d, rules) == "general_q") text = Marks.question(text)
                val marks = Marks.parse(text, rules.focusLevel)
                val prepared = Normalizer.prepare(marks.text, sym.allowed, rules)
                if (prepared.none { it != '+' && it in sym.alphabet }) return null
                // Монитор models — тот же, что у SileroModels.release()/ensureLoaded() (оба @Synchronized
                // на this), поэтому выгрузка по простою не может destroy() модуль посреди forward.
                return synchronized(models) {
                    try {
                        models.ensureLoaded(voice.pack)
                        val accented = stress.apply(prepared, marks.text)
                        if (auditNames) audit.names(seg.text, accented, known)
                        val seq = sym.sequence(stress.forModel(accented))
                        // интонация вопросов/восклицаний и логическое ударение есть только у v5_5_ru
                        val typeIds = if (voice.types) SentenceType.typeIds(prepared, SentenceType.classify(marks.text, d, rules), seq.size, d) else LongArray(seq.size)
                        val curSpeakerId = if (seg.speech) quoteSpeakerId ?: speakerId else speakerId
                        val curPitch = pitch * (if (seg.speech) quotePitch else 1f)
                        val al = Marks.align(marks.words, accented, seq.size, sym)
                        for (i in al.pitches.indices) al.pitches[i] *= curPitch
                        // seq = sos + accented + eos, индексы совпадают с durs напрямую.
                        // seq[1] — первый символ сегмента: тире перед репликой («— Привет»), пауза там — тишина до слов.
                        // Тире после знака («, —», «! —») — авторская ремарка, паузу уже дал сам знак.
                        val symbDurs = (2 until seq.size).mapNotNull { i ->
                            val fr = pauseFrames[seq[i].toInt()] ?: return@mapNotNull null
                            if (accented[i - 1] in "–—" && accented.substring(0, i - 1).trimEnd().lastOrNull()?.let { it in Marks.PUNCT } == true) null else i.toLong() to fr
                        }.toMap() + al.symbDurs
                        Pair(models.synthesize(seq, curSpeakerId, sr, al.rates, al.pitches, typeIds, al.focus, symbDurs, voice.types), Marks.tokens(accented, sym))
                    } catch (e: Throwable) {
                        // Throwable, не Exception: OOM на длинном forward не должен убивать сервис.
                        Log.e(SileroModels.TAG, "синтез не удался: «${seg.text.take(60)}»", e); null
                    }
                }
                } finally { synthMs.addAndGet(System.currentTimeMillis() - tSeg) }
            }
            // Сегмент N+1 считается, пока звук сегмента N уходит плееру: audioAvailable блокирует,
            // пока непроигранного звука больше 500 мс (SynthesisPlaybackQueueItem), и без опережения
            // перед каждым куском была бы пауза в его время счёта.
            fun unit(seg: Segment): Any? {
                if (seg.en && enEngine != null && !stopped) {
                    val t = System.currentTimeMillis()
                    val p = try { proxySegment(seg, enEngine) } finally { synthMs.addAndGet(System.currentTimeMillis() - t) }
                    if (p != null || stopped) return p
                }
                return synthSegment(seg)
            }
            var next: Future<Any?>? = null
            for ((si, seg) in segments.withIndex()) {
                if (stopped) { next?.cancel(false); break }
                val res = (next ?: synthPool.submit(Callable { unit(seg) })).get()
                next = if (si + 1 < segments.size) synthPool.submit(Callable { unit(segments[si + 1]) }) else null
                if (res is Proxied) {
                    for ((key, at) in res.words) {
                        val j = matcher.next(key)
                        if (j < 0) continue
                        val pos = Math.round(at * res.pcm.size).toInt()
                        recorder?.range(pos, srcWords[j].second, srcWords[j].third)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) callback.rangeStart((written + pos).toInt(), srcWords[j].second, srcWords[j].third)
                    }
                    recorder?.audio(res.pcm)
                    if (!write(callback, res.pcm)) return
                    written += res.pcm.size
                    audioMs += res.pcm.size * 1000L / sr
                }
                @Suppress("UNCHECKED_CAST")
                val synth = res as? Pair<SileroModels.Synth, List<Marks.Token>>
                if (synth != null) {
                    val (out, tokens) = synth
                    val audio = out.audio
                    Pcm.gain(audio, volume)
                    Pcm.fadeEdges(audio, sr, 5)
                    val segRate = rate * (if (seg.speech) quoteRate else 1f)
                    val pcm = Tempo.stretch(Pcm.toPcm16(audio), sr, segRate)
                    // Границы слов: сумма durs до первого символа слова × сэмплов на кадр, после
                    // Sonic — в пропорции длин. Слово, которого нет в запросе (число, сокращение,
                    // латиница), подсветку не двигает.
                    val perFrame = pcm.size.toDouble() / out.durs.sum()
                    val cum = DoubleArray(out.durs.size + 1)
                    for (i in out.durs.indices) cum[i + 1] = cum[i] + out.durs[i]
                    for (t in tokens) {
                        val j = matcher.next(t.key)
                        if (j < 0) continue
                        val at = Math.round(cum[t.seqStart] * perFrame).toInt()
                        recorder?.range(at, srcWords[j].second, srcWords[j].third)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) callback.rangeStart((written + at).toInt(), srcWords[j].second, srcWords[j].third)
                    }
                    recorder?.audio(pcm)
                    if (!write(callback, pcm)) return
                    written += pcm.size
                    audioMs += pcm.size * 1000L / sr
                    Log.d(SileroModels.TAG, "unit ${audio.size * 1000L / sr} мс")
                }
                if (seg.breakMs > 0) { val sil = Pcm.silence(sr, seg.breakMs); recorder?.audio(sil); if (!write(callback, sil)) return; written += sil.size }
            }
            callback.done()
            // в кэш — только доведённое до конца: оборванный TalkBack-ом звук был бы неполным
            if (recorder != null && cacheKey != null && !stopped && audioMs > 0) phrases.put(cacheKey, recorder.entry())
            audit.flush()
            val ms = System.currentTimeMillis() - t0
            note("запрос ${request.charSequenceText.length} симв., ${segments.size} сегм., $ms мс, звук $audioMs мс, синтез ${synthMs.get()} мс" +
                (if (audioMs > 0) ", RTF %.2f".format(synthMs.get().toDouble() / audioMs) else "") +
                (if (!foreground) ", без foreground" else "") +
                (if (enCount > 0) ", по-английски $enCount через ${enEngine?.pkg}" else "") +
                (caller?.let { ", от ${it.pkg}" + if (screenReader) " (экранный чтец)" else "" } ?: ""))
        } catch (e: Exception) {
            Log.e(SileroModels.TAG, "onSynthesizeText", e)
            callback.error()
        } finally {
            scheduleUnload()
        }
    }

    /** false — клиент ушёл (framework вернул не SUCCESS), дальше синтезировать незачем. */
    private fun write(callback: SynthesisCallback, pcm: ShortArray): Boolean {
        val bytes = Pcm.toBytes(pcm)
        val max = callback.maxBufferSize
        var off = 0
        while (off < bytes.size && !stopped) {
            val n = minOf(max, bytes.size - off)
            if (callback.audioAvailable(bytes, off, n) != TextToSpeech.SUCCESS) { stopped = true; return false }
            off += n
        }
        lastAudioAt = System.currentTimeMillis()
        return true
    }

    /** Английский кусок от чужого движка: звук на нашей частоте и слова (ключ, доля длины текста до слова). */
    private class Proxied(val pcm: ShortArray, val words: List<Pair<String, Double>>)

    companion object {
        const val LEAD_GAP_MS = 2500L
        const val LEAD_IN_MS = 300
        /** Потолок темпа для экранного чтеца: TalkBack шлёт до ×6. Книгам — ×3, как раньше. */
        // TtsSpan → короткие имена SpanSay (без префиксов android.type./android.arg.)
        private val spanTypes = mapOf(TtsSpan.TYPE_TEXT to "text", TtsSpan.TYPE_CARDINAL to "cardinal",
            TtsSpan.TYPE_ORDINAL to "ordinal", TtsSpan.TYPE_DECIMAL to "decimal", TtsSpan.TYPE_FRACTION to "fraction",
            TtsSpan.TYPE_MEASURE to "measure", TtsSpan.TYPE_TIME to "time", TtsSpan.TYPE_DATE to "date",
            TtsSpan.TYPE_TELEPHONE to "telephone", TtsSpan.TYPE_ELECTRONIC to "electronic", TtsSpan.TYPE_MONEY to "money",
            TtsSpan.TYPE_DIGITS to "digits", TtsSpan.TYPE_VERBATIM to "verbatim")
        private val spanArgs = mapOf(TtsSpan.ARG_TEXT to "text", TtsSpan.ARG_NUMBER to "number",
            TtsSpan.ARG_INTEGER_PART to "integer_part", TtsSpan.ARG_FRACTIONAL_PART to "fractional_part",
            TtsSpan.ARG_NUMERATOR to "numerator", TtsSpan.ARG_DENOMINATOR to "denominator", TtsSpan.ARG_UNIT to "unit",
            TtsSpan.ARG_HOURS to "hours", TtsSpan.ARG_MINUTES to "minutes", TtsSpan.ARG_WEEKDAY to "weekday",
            TtsSpan.ARG_DAY to "day", TtsSpan.ARG_MONTH to "month", TtsSpan.ARG_YEAR to "year",
            TtsSpan.ARG_COUNTRY_CODE to "country_code", TtsSpan.ARG_NUMBER_PARTS to "number_parts", TtsSpan.ARG_EXTENSION to "extension",
            TtsSpan.ARG_PROTOCOL to "protocol", TtsSpan.ARG_USERNAME to "username", TtsSpan.ARG_PASSWORD to "password",
            TtsSpan.ARG_DOMAIN to "domain", TtsSpan.ARG_PORT to "port", TtsSpan.ARG_PATH to "path",
            TtsSpan.ARG_QUERY_STRING to "query_string", TtsSpan.ARG_FRAGMENT_ID to "fragment_id",
            TtsSpan.ARG_CURRENCY to "currency", TtsSpan.ARG_QUANTITY to "quantity", TtsSpan.ARG_DIGITS to "digits",
            TtsSpan.ARG_VERBATIM to "verbatim", TtsSpan.ARG_GENDER to "gender", TtsSpan.ARG_ANIMACY to "animacy",
            TtsSpan.ARG_MULTIPLICITY to "multiplicity", TtsSpan.ARG_CASE to "case")
        private val spanValues = mapOf(TtsSpan.GENDER_MALE to "male", TtsSpan.GENDER_FEMALE to "female",
            TtsSpan.GENDER_NEUTRAL to "neutral", TtsSpan.ANIMACY_ANIMATE to "animate", TtsSpan.ANIMACY_INANIMATE to "inanimate",
            TtsSpan.MULTIPLICITY_SINGLE to "single", TtsSpan.MULTIPLICITY_DUAL to "plural", TtsSpan.MULTIPLICITY_PLURAL to "plural",
            TtsSpan.CASE_NOMINATIVE to "nominative", TtsSpan.CASE_GENITIVE to "genitive", TtsSpan.CASE_DATIVE to "dative",
            TtsSpan.CASE_ACCUSATIVE to "accusative", TtsSpan.CASE_INSTRUMENTAL to "instrumental",
            TtsSpan.CASE_LOCATIVE to "locative", TtsSpan.CASE_ABLATIVE to "genitive", TtsSpan.CASE_VOCATIVE to "nominative")
        const val SR_MAX_RATE = 6f
        /** Кэш коротких фраз — на процесс, переживает пересоздание сервиса. */
        val phrases = PhraseCache()
        private const val CHANNEL = "synth"
        /** Простой, после которого снимаем foreground: больше паузы между главами, меньше времени
         * висения уведомления после того, как читалку закрыли. */
        private const val FG_IDLE_MS = 60_000L
        private const val FG_ID = 1
        /** Последние запросы — в «Решение проблем» кнопкой «Скопировать отчёт». Сервис и настройки
         * в одном процессе, файл не нужен. */
        private val journal = ArrayDeque<String>()
        private val stamp = SimpleDateFormat("HH:mm:ss", Locale.US)
        fun note(line: String) = synchronized(journal) {
            Log.i(SileroModels.TAG, line)
            journal.addLast("${stamp.format(System.currentTimeMillis())} $line")
            while (journal.size > 200) journal.removeFirst()
        }
        fun journal(): List<String> = synchronized(journal) { journal.toList() }
    }
}
