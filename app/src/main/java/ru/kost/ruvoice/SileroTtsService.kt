package ru.kost.ruvoice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.ServiceInfo
import android.os.PowerManager
import android.os.Build
import android.media.AudioFormat
import android.content.Intent
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import android.os.Looper
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.Voice
import android.text.Spanned
import android.text.TextUtils
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
     * «Книге с ударениями» они не нужны. englishJoinMs — пауза на стыке русского и английского, где
     * стоит знак (запятая, двоеточие, кавычка); без знака — короткая English.JOIN_MS (English.joins). */
    fun plan(text: CharSequence, d: SileroData, sentencePauseMs: Int, paragraphPauseMs: Int,
             replacements: Replacements = Replacements.parse(emptyList()), rules: Rules = Rules(), englishWords: Int = 0,
             englishJoinMs: Int = English.JOIN_PUNCT_MS): List<Segment> {
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
                // эхо ввода/удаления: «Удаление заглавная Р», «Р удалено» — буква с именем, иначе «ррр»;
                // у экранного чтеца всегда, у книг и приложений — тумблером letter_echo_all
                ?: (if (rules.on(Rules.LETTER_ECHO) || rules.on("letter_echo_all")) LetterEcho.rewrite(t) else null)
                // одиночный знак (клавиша «#», знак под курсором) — по имени, иначе фильтр оставит тишину
                ?: t.trim().singleOrNull()?.let { SymbolNames.of(it) } ?: t
        }
        // Последний абзац запроса намеренно без паузы абзаца — свою паузу до следующей
        // реплики читалка/пользователь и так делают между вызовами.
        val segments = if (rules.on("ssml") && Ssml.isSsml(src)) Ssml.parse(src) else
            Splitter.paragraphs(src, rules).mapIndexed { i, p -> Segment(p, paragraph = true) }.let { if (it.isEmpty()) it else it.dropLast(1) + it.last().copy(paragraph = false) }
        // длина куска для модели: у чтеца предел куска меряется ею (Splitter.sentences), у быстрого старта — всегда
        val measure = { s: String -> Normalizer.prepare(Marks.parse(s, 0).text, d.allowed, rules).length }
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
                val sents = Splitter.sentences(piece, rules, measure.takeIf { rules.on(Rules.SCREEN_READER) })
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
                        // английский кусок посреди реплики — свой сегмент с паузой на стыке (два движка одну фразу
                        // общей интонацией не свяжут — пауза делает стык паузой, а не обрывом); обрывки из одних
                        // знаков («», ») русскому движку читать нечего, их знак решает, какой будет пауза
                        val langs = if (englishWords <= 0) listOf(English.Piece(part.first, false, 0))
                            else English.joins(English.split(part.first, englishWords), englishJoinMs)
                        for ((li, lp) in langs.withIndex()) {
                            val lastLang = li == langs.size - 1
                            out += Segment(lp.text, breakMs = if (!lastLang || !lastPart) lp.joinMs else breakMs,
                                paragraph = lastPart && lastLang && last && seg.paragraph, speech = part.second, en = lp.en)
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
        if (rules.on("fast_start")) fastStart(out, measure)
        return carryProsody(out)
    }

    /** Первый сегмент запроса короче остальных: звук уходит читалке только после счёта всего
     * сегмента, и max_len символов на слабом телефоне — секунды тишины перед началом. Хвост
     * считается, пока играет голова. Режется как limit, по запятой, но один раз.
     * Длина — текста для модели, после нормализации: ссылка, эмодзи, число раздуваются в 2–3 раза
     * («t.me/c/123…» — «ти точка ме слэш си слэш один два три…»). Без раздувания разрез тот же, что по исходному. */
    private fun fastStart(out: ArrayList<Segment>, measure: (String) -> Int) {
        val i = out.indexOfFirst { it.text.isNotBlank() }
        if (i < 0) return
        val seg = out[i]; val t = seg.text
        // короткую фразу чтеца без цифр, латиницы и значков не нормализуем лишний раз — раздуваться в ней нечему
        if (t.length <= Rules.FAST_START_LEN && (!Splitter.mayInflate(t) || measure(t) <= Rules.FAST_START_LEN)) return
        val c = Splitter.cutMeasured(t, Rules.FAST_START_LEN, measure)
        if (c + 1 >= t.length) return   // запрос короче порога без запятой и пробела — резать негде
        out[i] = seg.copy(text = t.substring(c + 1).trim())
        out.add(i, Segment(Splitter.head(t, c), speech = seg.speech, en = seg.en))
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
    /** Номер запроса: сегмент, посчитанный заранее для прошлого запроса, видит, что его запрос уже не текущий, — даже
     * когда новый запрос снова сбросил [stopped]. Иначе он занимал бы synthPool (английский — до срока ожидания
     * движка) и новая фраза чтеца стояла бы за ним в тишине. */
    @Volatile private var generation = 0
    /** Куски, поставленные читалками в очередь (uid, текст), которые фреймворк ещё не отдал в onSynthesizeText. */
    private val queued = ArrayDeque<Pair<Int, CharSequence>>() // со спанами: заготовка читает их, как сам запрос
    /** Начало следующего куска, посчитанное заранее: сегменты в synthPool, [key] — текст и настройки, с которыми считали. */
    private class Prefetch(val uid: Int, val key: String, val texts: List<String>) {
        val futures = ArrayList<Future<SegOut?>>()
        val ms = java.util.concurrent.atomic.AtomicLong()
        @Volatile var dead = false
    }
    private var prefetch: Prefetch? = null // под замком queued
    /** Ставит запрос, отдавший последний сегмент (uid читалки и как заготовить её следующий кусок). Binder зовёт его,
     * когда кусок этой читалки пришёл позже. */
    @Volatile private var prefetcher: Pair<Int, () -> Unit>? = null
    /** uid приложения, чей запрос сейчас в onSynthesizeText: onStop фреймворк зовёт для текущего запроса. */
    @Volatile private var currentUid = -1
    private var foreground = false
    // Аудиовыход телефона уходит в standby через ~3 с тишины, а после пробуждения HAL плавно
    // поднимает громкость — первое слово фразы выходит тихим. Если с прошлого звука прошло
    // больше LEAD_GAP_MS, начинаем с LEAD_IN_MS тишины, чтобы подъём пришёлся на неё (правило lead_in).
    // ponytail: пороги под AOSP standby 3 с; сделать настройкой, если на другом телефоне не совпадёт.
    private var lastAudioAt = 0L
    /** Когда запрос отдал первый звук (0 — ещё не отдал): в журнале «первый звук через», задержка до речи. */
    private var firstAudioAt = 0L
    // Выгрузка на отдельном потоке: release() и synthesize() делят монитор models, поэтому
    // release() просто дождётся текущего forward, а не заблокирует main на его время.
    private val unload = Runnable {
        Thread { models.release(); english.release(); note("модели выгружены по простою") }.start()
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
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(if (srHold()) R.string.fg_screen_reader else R.string.fg_reading))
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

    /** Экранный чтец включён и правило sr_keep_loaded: движок держим наготове — модель в памяти целиком
     * (SileroModels.resident), сервис на переднем плане, чтобы One UI и другие оболочки не выгружали процесс:
     * после выгрузки каждая фраза ждала бы запуска и загрузки модели. */
    private fun srHold() = prefs.rules().on("sr_keep_loaded") && ScreenReaders.anyActive(this)

    override fun onCreate() {
        super.onCreate()
        note("сервис запущен")
        phrases.dir = java.io.File(cacheDir, "phrases")
        // процесс перезапущен при работающем чтеце — сразу на передний план (из фона система может не дать)
        if (srHold()) handler.post { enterForeground() }
        // режим бэкбона — до любой загрузки: фраза чтеца может прийти раньше прогрева, и прогрев перегружал бы бэкбон
        models.resident = srHold()
        Thread { runCatching { warmUp() }.onFailure { Log.e(SileroModels.TAG, "прогрев", it) } }.start()
        // английский для чтеца: подключаемся к движку сейчас, а не на первой фразе с латиницей
        Thread { runCatching { if (prefs.rules().on("en_proxy_sr")) EnglishProxy.chosen(this, prefs.enEngine)?.let {
            english.warm(it.pkg, prefs.enVoice) } } }.start()
        // 0.14.18 по ошибке ушёл с отладочной записью всего звука в files/tee/*.pcm — вычищаем
        Thread { runCatching { getExternalFilesDir(null)?.let { java.io.File(it, "tee").deleteRecursively() } } }.start()
    }

    private fun warmUp() {
        // Первая фраза после запуска процесса ждёт данные с Normalizer (~1,3 с на A32), модель с акцентором (~1,6 с) и
        // словари замен (~0,7 с). Модель — здесь сразу (нативная загрузка, GC не мешает), словари — вторым потоком,
        // данные готовит поток самой фразы. Больше потоков на Java-разборе на A32 выходило медленнее: упирается в GC.
        val dicts = Thread { runCatching { prefs.userDict(); prefs.replacements() }.onFailure { Log.e(SileroModels.TAG, "прогрев", it) } }.apply { start() }
        // греем тройку голоса из настроек, а не штатную: иначе первый запрос перегружает 90 МБ; пак — по имени, без data
        val name = currentName() ?: return   // lite без пака: голосов нет
        models.ensureLoaded(packOf(name))
        dicts.join()
        // фраза уже пришла — она сама и прогреет, а прогревочный forward держал бы модель, пока она ждёт
        if (generation == 0) currentSpeaker()?.let { v -> synchronized(models) {
            val seq = v.sym.sequence("прив+ет.")
            models.synthesize(seq, v.id, prefs.sampleRate, FloatArray(seq.size) { 1f }, FloatArray(seq.size) { 1f }, LongArray(seq.size), LongArray(seq.size), emptyMap(), v.types)
        } }
        scheduleUnload()
    }

    /** Пак голоса по имени — как Speaker.resolve, без SileroData: null — штатная модель. */
    private fun packOf(name: String): Pack? {
        val i = name.indexOf('/')
        if (i >= 0) return packs().firstOrNull { it.id == name.substring(0, i) }
        return if (Speaker.builtin) null else packs().firstOrNull { it.id == Speaker.RU_PACK }
    }

    /** Снятие foreground после простоя — отдельно от выгрузки моделей: та может быть выключена
     * в настройках, а уведомление «идёт чтение» висеть вечно не должно. */
    private val fgOff: Runnable = Runnable {
        // При погасшем экране снимать нельзя: поднять обратно система уже не даст, а после паузы
        // чтение продолжат в том же фоне. Ждём, пока на телефон посмотрят.
        // При работающем экранном чтеце не снимаем вовсе (srHold): выгруженный процесс — секунды тишины на каждой фразе.
        if (screenOff() || srHold()) handler.postDelayed(fgOff, FG_IDLE_MS) else leaveForeground()
    }

    private fun scheduleUnload() {
        handler.removeCallbacks(unload)
        handler.removeCallbacks(fgOff)
        handler.postDelayed(fgOff, FG_IDLE_MS)
        // «Не выгружать, пока работает экранный чтец» важнее общей выгрузки по простою: первая фраза
        // TalkBack после паузы иначе ждёт загрузки модели
        if (srHold()) { models.resident = true; return }
        models.resident = false
        if (prefs.idleOn) handler.postDelayed(unload, prefs.idleMinutes.coerceAtLeast(1) * 60_000L)
    }

    override fun onDestroy() { handler.removeCallbacks(unload); handler.removeCallbacks(fgOff); leaveForeground(); stopped = true; synthPool.shutdownNow(); models.release(); english.release(); super.onDestroy() }

    // Binder-loadLanguage у TextToSpeechService отдаёт клиенту именно этот ответ (onLoadLanguage
    // в очереди, его результат выбрасывается), поэтому «голосов нет» (lite без пака) — здесь.
    // Английский — если включён хоть один тумблер «Английский другим движком» и выбранный движок стоит:
    // читалка или TalkBack (LocaleSpan) может прямо попросить английский. Русский текст такого запроса
    // всё равно читает Silero (English.split), другому движку уходит только латиница.
    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int =
        if (lang == "eng") (if (englishReady()) TextToSpeech.LANG_AVAILABLE else TextToSpeech.LANG_NOT_SUPPORTED)
        else if (lang != "rus") TextToSpeech.LANG_NOT_SUPPORTED
        else if (!Speaker.hasVoices(packs())) TextToSpeech.LANG_MISSING_DATA
        else TextToSpeech.LANG_COUNTRY_AVAILABLE

    private fun englishReady(): Boolean = prefs.rules().let { it.on("en_proxy_books") || it.on("en_proxy_sr") } &&
        EnglishProxy.chosen(this, prefs.enEngine) != null
    override fun onGetLanguage(): Array<String> = arrayOf("rus", "RUS", "")
    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int {
        val r = onIsLanguageAvailable(lang, country, variant)
        // TextToSpeechService.onCreate() зовёт этот метод синхронно на главном потоке — грузить
        // модели прямо тут нельзя, это надолго заблокирует главный поток. Прогрев (onCreate выше)
        // и так грузит их отдельным потоком, поэтому с главного потока просто отвечаем по языку.
        // Загружен любой голос — не трогаем: запрос сам загрузит свой (request.voiceName), а голос из настроек здесь
        // выгнал бы голос чтеца, и следующая фраза снова грузила бы модель — секунды тишины на каждой.
        if (r == TextToSpeech.LANG_COUNTRY_AVAILABLE && Looper.myLooper() != Looper.getMainLooper() && !models.loaded) {
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

    // Вопросы о голосах — по одним именам (SileroModels.speakers), без models.data: система задаёт их при подключении
    // читалки и шлёт первую фразу после ответа, а полный разбор данных на A32 — ~10 с.
    private fun voiceNames() = Speaker.names(SileroModels.speakers(this), packs())
    private fun known(name: String?) = Speaker.exists(name, SileroModels.speakers(this), packs())
    /** Имя [currentSpeaker] без SileroData. */
    private fun currentName(): String? = prefs.voice.takeIf { known(it) } ?: Speaker.DEFAULT.takeIf { known(it) } ?: voiceNames().firstOrNull()

    override fun onGetVoices(): List<Voice> = voiceNames().map {
        Voice(Speaker.ttsName(it), Locale("ru", "RU"), Voice.QUALITY_HIGH, Voice.LATENCY_NORMAL, false, emptySet())
    } + (if (englishReady()) listOf(Voice(EN_VOICE, Locale.US, Voice.QUALITY_NORMAL, Voice.LATENCY_HIGH, false, emptySet())) else emptyList())
    override fun onIsValidVoiceName(name: String?): Int =
        if (name == EN_VOICE) (if (englishReady()) TextToSpeech.SUCCESS else TextToSpeech.ERROR)
        else if (known(Speaker.fromTtsName(name, voiceNames()))) TextToSpeech.SUCCESS else TextToSpeech.ERROR
    override fun onLoadVoice(name: String?): Int = onIsValidVoiceName(name)
    override fun onGetDefaultVoiceNameFor(lang: String?, country: String?, variant: String?): String =
        if (lang == "eng" && englishReady()) EN_VOICE else Speaker.ttsName(currentName() ?: Speaker.DEFAULT)

    override fun onStop() { stopped = true; synchronized(queued) { forgetApp(currentUid) } }

    // Очередь фреймворка отдаёт следующий кусок только после возврата текущего запроса — за ~0,5 с до конца его звука.
    // Читалка (AlReaderX и др.) ставит следующий кусок через QUEUE_ADD раньше, и через binder мы видим его сразу:
    // запоминаем, чтобы запрос, досчитавший свои сегменты, заранее посчитал начало следующего (правило prefetch).
    // speak — первый метод скрытого ITextToSpeechService; не разобрали — работаем как без заготовки.
    override fun onBind(intent: Intent?): IBinder? {
        val inner = super.onBind(intent) ?: return null
        return object : Binder() {
            // свой процесс (проба голоса) берёт настоящий stub; у пустого Binder дескриптор null,
            // и queryLocalInterface на Android 6 падает в mDescriptor.equals
            override fun queryLocalInterface(descriptor: String): IInterface? = inner.queryLocalInterface(descriptor)
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                val text = if (code == FIRST_CALL_TRANSACTION) speakText(data) else null
                val mode = if (text != null) data.readInt() else 0
                data.setDataPosition(0)
                val uid = getCallingUid()
                // после transact: QUEUE_FLUSH внутри него зовёт onStop, а тот чистит очередь
                return inner.transact(code, data, reply, flags).also { if (text != null) queuedSpeak(uid, text, mode) }
            }
        }
    }

    private fun queuedSpeak(uid: Int, text: CharSequence, mode: Int) {
        synchronized(queued) {
            // QUEUE_FLUSH сбрасывает только очередь этого приложения (stopForApp): фраза TalkBack книгу не трогает
            if (mode == TextToSpeech.QUEUE_FLUSH) forgetApp(uid)
            queued.addLast(uid to text)
            while (queued.size > QUEUED_MAX) queued.removeFirst()
        }
        prefetcher?.let { (u, f) -> if (u == uid) f() }
    }

    /** Под замком queued: приложение сбросило очередь — его куски и заготовка больше не придут. */
    private fun forgetApp(uid: Int) {
        queued.removeAll { it.first == uid }
        if (prefetch?.uid == uid) dropPrefetch()
    }

    /** Под замком queued. */
    private fun dropPrefetch() {
        prefetch?.let { p -> p.dead = true; p.futures.forEach { it.cancel(false) } }
        prefetch = null
    }

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
        val gen = ++generation
        fun gone() = stopped || gen != generation
        currentUid = request.callerUid
        if (prefetcher?.first == request.callerUid) prefetcher = null
        val rawText = request.charSequenceText?.toString().orEmpty()
        synchronized(queued) { repeat(queued.indexOfFirst { it.second.toString() == rawText } + 1) { queued.removeFirst() } }
        handler.removeCallbacks(unload)
        handler.removeCallbacks(fgOff)
        // Поднимаем сразу: при погасшем экране система запрещает старт foreground-сервиса из фона
        // (Background started FGS: Disallowed), а нужен он именно тогда — там процесс уводят
        // в Restricted и синтез перестаёт успевать за воспроизведением.
        handler.post { enterForeground() }
        val t0 = System.currentTimeMillis()
        firstAudioAt = 0L
        try {
            // Голос запроса — по именам; models.data и модель — после кэша фраз: готовая фраза звучит сразу, и после
            // перезапуска процесса тоже, пока разбираются данные (~10 с на A32)
            val asked = Speaker.fromTtsName(request.voiceName, voiceNames()).takeIf { known(it) }
            val wantedName = asked ?: currentName() ?: run {
                Log.e(SileroModels.TAG, "голосов нет: сборка без модели и без пака"); callback.error(TextToSpeech.ERROR_NOT_INSTALLED_YET); return
            }
            val caller = ScreenReaders.caller(this, request.callerUid)
            // читалка сменила голос на привязанный к профилю — профиль включается до чтения настроек ниже.
            // «Прослушать» в самом приложении не в счёт: голос там меняют на вкладке «Голос» (VoiceFragment).
            // Пакеты обычных читалок с Android 11 нам не видны (caller null) — тогда читалка по uid, иначе все они
            // делили бы один «прошлый голос» и две читалки с разными голосами дёргали бы профиль на каждой фразе
            if (asked != null && request.callerUid != android.os.Process.myUid()) {
                val who = caller?.pkg ?: "uid ${request.callerUid}"
                Profiles(this).followVoice(who, asked)?.let { note("профиль «${it.name}»: $who выбрала голос $asked") }
            }
            val sr = prefs.sampleRate
            // Экранный чтец (TalkBack и др.) — свои правила, темп и высота поверх общих (секция «Чтение с экрана»)
            val screenReader = caller != null && ScreenReaders.isScreenReader(prefs, caller)
            caller?.let { prefs.rememberCaller(it) }
            // TalkBack шлёт темп до ×6 (и умножает на системный) — для чтеца потолок ×6, книгам ×3 как было
            val rate = if (screenReader) (request.speechRate / 100f * prefs.srRate).coerceIn(0.5f, SR_MAX_RATE)
                else (request.speechRate / 100f * prefs.rate).coerceIn(0.5f, 3f)
            val pitch = (request.pitch / 100f * (if (screenReader) prefs.srPitch else prefs.pitch)).coerceIn(0.5f, 2f)
            // громкость читалки (KEY_PARAM_VOLUME) применяет сам плеер Android; наша — поверх, в звуке модели.
            // У чтеца своя громкость — множитель поверх общей: крутят общую, слышно и в TalkBack
            val volume = (prefs.volume * (if (screenReader) prefs.srVolume else 1f)).coerceIn(0.25f, Pcm.MAX_GAIN)
            // настройки слушают слово «как модель», без пользовательского словаря
            val noDict = request.params?.getString("ruvoice.nodict") == "1"
            val baseRules = prefs.rules()
            val rules = if (screenReader) baseRules.screenReader() else baseRules
            val noPauses = screenReader && baseRules.on("sr_pauses_off")
            // TtsSpan (пунктуация TalkBack «Все») — текстом; смещения rangeStart считаются по нему же
            val spoken = spokenText(request.charSequenceText)
            val reqText = spoken.text
            // правило verbose_log: книжный запрос — в журнал как пришёл (невидимые знаки и ударения кодами), с голосом и темпом:
            // видно, что прислала читалка. Фразы экранного чтеца не пишем — там переписка
            if (!screenReader && rules.on("verbose_log")) note("от ${caller?.pkg ?: "?"}: голос $wantedName, темп %.2f, высота %.2f: «%s»".format(rate, pitch, visible(reqText)))
            val auditNames = prefs.auditNames && !noDict
            var written = 0L // сэмплов отдано читалке — точка отсчёта markerInFrames
            var audioMs = 0L // длительность отданного звука — для журнала
            // Чистое время синтеза: в него не входит ожидание плеера (audioAvailable блокирует, пока
            // непроигранного больше 500 мс), поэтому RTF = синтез / звук показывает, успевает ли телефон.
            val synthMs = java.util.concurrent.atomic.AtomicLong()
            val perf = Perf.snap()
            if (callback.start(sr, AudioFormat.ENCODING_PCM_16BIT, 1) != TextToSpeech.SUCCESS) { stopped = true; return }
            val continuous = System.currentTimeMillis() - lastAudioAt <= LEAD_GAP_MS
            if (rules.on("lead_in") && !continuous) { val sil = Pcm.silence(sr, LEAD_IN_MS); if (!write(callback, sil)) return; written += sil.size; firstAudioAt = 0L }
            // Правило pause_min: пауза между предложениями — не меньше заданной. Тишину, которую модель уже оставила
            // в конце куска, засчитываем и добавляем только остаток; тишину в начале куска после паузы срезаем до
            // LEAD_KEEP_MS — иначе длина паузы гуляла бы вместе с краями звука модели. Реплику с тире не трогаем:
            // пауза перед ней намеренная (dashPauseMs). При нулевой паузе предложения правило ничего не меняет.
            val pauseMin = rules.on("pause_min") && !screenReader && prefs.sentencePauseMs > 0
            val keep = sr * LEAD_KEEP_MS / 1000
            fun dash(t: String) = t.trimStart().firstOrNull()?.let { it in "–—-" } == true
            var cutNext = pauseMin && continuous && !dash(reqText) // срезать тишину в начале следующего звука
            var owed = 0  // сэмплов паузы, ещё не отданных перед следующим звуком
            var trail = 0 // тишина в конце последнего отданного звука, сэмплов
            fun leadCut(pcm: ShortArray) = if (cutNext) (Pcm.silentEdges(pcm).first - keep).coerceAtLeast(0) else 0
            // Короткая фраза уже звучала с теми же голосом, темпом и настройками — отдаём готовый звук.
            // Сбор имён («Проверка») и прослушивание без словаря идут мимо кэша.
            val cacheKey = if (reqText.length <= PhraseCache.MAX_TEXT && !noDict && !auditNames)
                listOf(BuildConfig.VERSION_CODE, prefs.stamp(), prefs.dictStamp(), wantedName, packs().joinToString(",") { "${it.id}:${java.io.File(it.dir, "pack.json").lastModified()}" },
                    sr, rate, pitch, screenReader, reqText).joinToString("\u0001") else null
            // фразы чтеца — ещё и с диска (правило sr_phrase_disk): переживают перезапуск процесса
            val diskCache = screenReader && baseRules.on("sr_phrase_disk")
            if (screenReader && !diskCache) phrases.clearDisk()
            cacheKey?.let { phrases.get(it, diskCache) }?.let { hit ->
                // в кэше начало без среза: срез зависит от того, шло ли чтение подряд
                val cut = leadCut(hit.pcm)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    for (k in hit.ranges.indices step 3) callback.rangeStart((written + (hit.ranges[k] - cut).coerceAtLeast(0)).toInt(), hit.ranges[k + 1], hit.ranges[k + 2])
                if (!write(callback, if (cut > 0) hit.pcm.copyOfRange(cut, hit.pcm.size) else hit.pcm)) return
                callback.done()
                note("запрос ${request.charSequenceText.length} симв. из кэша, ${System.currentTimeMillis() - t0} мс, звук ${hit.pcm.size * 1000L / sr} мс" +
                    (caller?.let { ", от ${it.pkg}" + if (screenReader) " (экранный чтец)" else "" } ?: ""))
                return
            }
            val d = models.data
            val wanted = Speaker.resolve(wantedName, d, packs()) ?: run { callback.error(TextToSpeech.ERROR_NOT_INSTALLED_YET); return }
            val voice = load(wanted)
            val speakerId = voice.id
            val sym = voice.sym
            models.threads = if (rules.on("fast_cores")) SileroModels.fastCores else Runtime.getRuntime().availableProcessors()
            val stress = Stress(d, models, if (noDict) emptyMap() else prefs.userDict(), rules)
            // вкладка «Проверка»: имена — по исходному тексту сегмента
            val audit = prefs.audit; val known = Audit.known(d, if (noDict) emptyMap() else prefs.userDict())
            val replacements = prefs.replacements()
            // Голос/темп/питч прямой речи — читаем один раз на запрос, как replacements.
            val quoteSpeakerId = Speaker.resolve(prefs.quoteVoice, d, packs())?.takeIf { it.pack?.id == voice.pack?.id }?.id
            val quoteRate = prefs.quoteRate
            val quotePitch = prefs.quotePitch
            // Английские куски — другому движку, отдельно для экранного чтеца и для книг (en_proxy_sr / en_proxy_books),
            // только выбранному пользователем; нет его — читаем по-русски, как раньше
            val enOn = baseRules.on(if (screenReader) "en_proxy_sr" else "en_proxy_books")
            val enEngine = if (enOn) EnglishProxy.chosen(this, prefs.enEngine) else null
            if (enOn && enEngine == null) noteOnce("английский: движок «${prefs.enEngine.ifEmpty { "не выбран" }}» не найден, читаю по-русски")
            val enVoice = prefs.enVoice
            val enRate = prefs.enRate.coerceIn(0.5f, 2f)
            val enVolume = prefs.enVolume.coerceIn(0.5f, 2f)
            val enLimits = if (screenReader) EnglishProxy.screenReader(prefs.enSrTimeoutMs.coerceIn(EnglishProxy.SR_TIMEOUT_MIN, EnglishProxy.SR_TIMEOUT_MAX))
                else EnglishProxy.BOOKS
            // читалка попросила английский (setLanguage, голос EN_VOICE) — вся латиница другому движку, с первого слова
            val askedEnglish = request.language == "eng" || request.voiceName == EN_VOICE
            val enWords = if (enEngine == null) 0 else if (askedEnglish) English.MIN_WORDS
                else (if (screenReader) prefs.enMinWordsSr else prefs.enMinWords).coerceIn(English.MIN_WORDS, English.MAX_WORDS)
            fun planOf(t: String) = Pipeline.plan(t, d, if (noPauses) 0 else prefs.sentencePauseMs, if (noPauses) 0 else prefs.paragraphPauseMs, replacements, rules, enWords,
                maxOf(prefs.commaPauseMs, English.JOIN_MS))
            val segments = planOf(reqText)
            // заготовка годится, если считана с тем же голосом, высотой и настройками; темп и громкость — при отдаче
            fun keyOf(t: String) = listOf(prefs.stamp(), prefs.dictStamp(), wantedName, packs().joinToString(",") { "${it.id}:${java.io.File(it.dir, "pack.json").lastModified()}" },
                sr, pitch, askedEnglish, screenReader, t).joinToString("\u0001")
            // запрос экранного чтеца заготовку книги не трогает и ключ не считает: файлы словарей — лишние мс на жест
            val pre = if (screenReader) null else keyOf(reqText).let { reqKey -> synchronized(queued) {
                prefetch?.takeIf { it.key == reqKey && !it.dead }.also { if (it == null) dropPrefetch() else prefetch = null }
            } }
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
            val srcWords = nonSpace.findAll(srcText).map { Triple(Marks.key(it.value), spoken.orig(it.range.first), spoken.orig(it.range.last) + 1) }.toList()
            val matcher = Marks.Matcher(srcWords.map { it.first })
            val recorder = cacheKey?.let { PhraseCache.Recorder() }
            var enCount = 0
            // английский кусок не дошёл до движка (не ответил, минута после сбоя) и прочитан по-русски — такую фразу
            // не кэшируем: иначе она и после того, как движок ожил, звучала бы по-русски, с диска — и после перезапуска
            var enFallback = false
            // Английский кусок чужим движком: готовый PCM на нашей частоте и слова с долей позиции для подсветки.
            // Громкость подтягиваем к Silero (у Google голос заметно громче), поверх — поправки пользователя.
            // null — не вышло; тогда сегмент читает Silero, латиница транслитерируется, как без правила.
            fun proxySegment(seg: Segment, engine: EnglishProxy.Engine): SegOut.Proxied? {
                val text = Marks.parse(seg.text, 0).text.trim()
                if (text.isEmpty()) return null
                val segRate = rate * enRate * (if (seg.speech) quoteRate else 1f)
                val segPitch = pitch * (if (seg.speech) quotePitch else 1f)
                val a = english.synth(text, engine.pkg, enVoice, segRate, segPitch, enLimits) { gone() } ?: return null
                val audio = Pcm.toFloat(Pcm.resample(a.pcm, a.sampleRate, sr))
                Pcm.gain(audio, Pcm.englishGain(Pcm.matchGain(sileroLevel, Pcm.voicedRms(audio, sr)), enVolume, volume), sr)
                Pcm.fadeEdges(audio, sr, 5)
                enCount++
                return SegOut.Proxied(Pcm.toPcm16(audio), nonSpace.findAll(text).map { Marks.key(it.value) to it.range.first.toDouble() / text.length }.toList())
            }
            // Звук сегмента и токены для подсветки; null — нечего читать или синтез упал.
            fun synthSegment(seg: Segment, dead: () -> Boolean = { gone() }, acc: java.util.concurrent.atomic.AtomicLong = synthMs): SegOut.Model? {
                if (dead()) return null
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
                        val (accented, endDot) = stress.modelEnd(stress.apply(prepared, marks.text))
                        if (auditNames) audit.names(seg.text, accented, known)
                        val spoken = stress.stretchShort(accented)
                        val forModel = stress.forModel(spoken)
                        if (!screenReader && rules.on("verbose_log")) note("в модель: «${visible(forModel)}»")
                        val seq = sym.sequence(forModel)
                        // интонация вопросов/восклицаний и логическое ударение есть только у v5_5_ru
                        val typeIds = if (voice.types) SentenceType.typeIds(prepared, SentenceType.classify(marks.text, d, rules), seq.size, d) else LongArray(seq.size)
                        val curSpeakerId = if (seg.speech) quoteSpeakerId ?: speakerId else speakerId
                        val curPitch = pitch * (if (seg.speech) quotePitch else 1f)
                        val al = Marks.align(marks.words, spoken, seq.size, sym)
                        for (i in al.pitches.indices) al.pitches[i] *= curPitch
                        // seq = sos + spoken + eos, индексы совпадают с durs напрямую.
                        // seq[1] — первый символ сегмента: тире перед репликой («— Привет»), пауза там — тишина до слов.
                        // Тире после знака («, —», «! —») — авторская ремарка, паузу уже дал сам знак.
                        val symbDurs = (2 until seq.size).mapNotNull { i ->
                            val fr = pauseFrames[seq[i].toInt()] ?: return@mapNotNull null
                            if (spoken[i - 1] in "–—" && spoken.substring(0, i - 1).trimEnd().lastOrNull()?.let { it in Marks.PUNCT } == true) null else i.toLong() to fr
                        }.toMap() + (if (endDot) mapOf((seq.size - 2).toLong() to 1L) else emptyMap()) + al.symbDurs
                        SegOut.Model(models.synthesize(seq, curSpeakerId, sr, al.rates, al.pitches, typeIds, al.focus, symbDurs, voice.types), Marks.tokens(accented, sym))
                    } catch (e: Throwable) {
                        // Throwable, не Exception: OOM на длинном forward не должен убивать сервис.
                        Log.e(SileroModels.TAG, "синтез не удался: «${seg.text.take(60)}»", e); null
                    }
                }
                } finally { acc.addAndGet(System.currentTimeMillis() - tSeg) }
            }
            // Сегмент N+1 считается, пока звук сегмента N уходит плееру: audioAvailable блокирует,
            // пока непроигранного звука больше 500 мс (SynthesisPlaybackQueueItem), и без опережения
            // перед каждым куском была бы пауза в его время счёта.
            fun unit(seg: Segment): SegOut? {
                if (gone()) return null
                if (seg.en && enEngine != null) {
                    val t = System.currentTimeMillis()
                    val p = try { proxySegment(seg, enEngine) } finally { synthMs.addAndGet(System.currentTimeMillis() - t) }
                    if (p != null || gone()) return p
                    enFallback = true
                }
                return synthSegment(seg)
            }
            // сегмент из заготовки, если она посчитана для этого же сегмента
            fun ready(i: Int) = pre?.takeIf { i < it.futures.size && it.texts[i] == segments[i].text }?.futures?.get(i)
            // Свои сегменты все в работе — заранее считаем начало следующего куска той же читалки, пока звучат наши.
            // Английские сегменты не заготавливаем: чужой движок ждёт своей очереди.
            val prefetchNext = { synchronized(queued) {
                val t = queued.firstOrNull { it.first == request.callerUid }?.second
                if (prefetch == null && t != null) {
                    val text = spokenText(t).text
                    val segs = planOf(text)
                    val p = Prefetch(request.callerUid, keyOf(text), segs.map { it.text })
                    for (s in segs.take(PREFETCH_SEGS)) {
                        if (s.en && enEngine != null) break
                        p.futures += synthPool.submit(Callable<SegOut?> { synthSegment(s, { p.dead }, p.ms) })
                    }
                    prefetch = p
                    note("заготовка ${text.length} симв.: ${p.futures.size} из ${segs.size} сегм.")
                }
            } }
            // Звук куска читалке: сначала недоданная пауза (минус тишина, что осталась в начале звука), потом звук без лишней
            // тишины в начале; подсветку ([words]: сэмпл от начала звука, начало и конец слова) сдвигаем на срезанное.
            // Первый звук запроса в кэш фраз — без среза: срез зависит от того, шло ли чтение подряд.
            var firstAudio = true
            fun emit(raw: ShortArray, words: List<Triple<Int, Int, Int>>): Boolean {
                val cut = leadCut(raw)
                if (pauseMin) {
                    val pad = owed - (Pcm.silentEdges(raw).first - cut)
                    if (pad > 0) { val sil = ShortArray(pad); recorder?.audio(sil); if (!write(callback, sil)) return false; written += sil.size }
                    owed = 0
                }
                val pcm = if (cut > 0) raw.copyOfRange(cut, raw.size) else raw
                val recCut = if (firstAudio) 0 else cut
                for ((at, s, e) in words) {
                    recorder?.range((at - recCut).coerceAtLeast(0), s, e)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) callback.rangeStart((written + (at - cut).coerceAtLeast(0)).toInt(), s, e)
                }
                recorder?.audio(if (firstAudio) raw else pcm)
                if (!write(callback, pcm)) return false
                written += pcm.size
                audioMs += pcm.size * 1000L / sr
                trail = if (pauseMin) Pcm.silentEdges(pcm).second else 0
                cutNext = false; firstAudio = false
                return true
            }
            val canPrefetch = !screenReader && !noDict && !auditNames && rules.on("prefetch")
            var next: Future<SegOut?>? = null
            // выход посреди запроса (чтец перебил) — заранее поставленный сегмент не нужен
            try { for ((si, seg) in segments.withIndex()) {
                if (stopped) { next?.cancel(false); break }
                val res = (next ?: ready(si) ?: synthPool.submit(Callable { unit(seg) })).get()
                next = if (si + 1 < segments.size) ready(si + 1) ?: synthPool.submit(Callable { unit(segments[si + 1]) })
                    else { if (canPrefetch) { prefetcher = request.callerUid to prefetchNext; prefetchNext() }; null }
                if (res is SegOut.Proxied) {
                    val words = res.words.mapNotNull { (key, at) ->
                        matcher.next(key).takeIf { it >= 0 }?.let { j -> Triple(Math.round(at * res.pcm.size).toInt(), srcWords[j].second, srcWords[j].third) }
                    }
                    if (!emit(res.pcm, words)) return
                }
                if (res is SegOut.Model) {
                    val out = res.synth; val tokens = res.tokens
                    val audio = out.audio
                    // средняя громкость Silero — мерка для английского от другого движка
                    Pcm.voicedRms(audio, sr).takeIf { it > 0f }?.let { sileroLevel = if (sileroLevel == 0f) it else sileroLevel * 0.8f + it * 0.2f }
                    Pcm.gain(audio, volume, sr)
                    Pcm.fadeEdges(audio, sr, 5)
                    val segRate = rate * (if (seg.speech) quoteRate else 1f)
                    val pcm = Tempo.stretch(Pcm.toPcm16(audio), sr, segRate)
                    // Границы слов: сумма durs до первого символа слова × сэмплов на кадр, после
                    // Sonic — в пропорции длин. Слово, которого нет в запросе (число, сокращение,
                    // латиница), подсветку не двигает.
                    val perFrame = pcm.size.toDouble() / out.durs.sum()
                    val cum = DoubleArray(out.durs.size + 1)
                    for (i in out.durs.indices) cum[i + 1] = cum[i] + out.durs[i]
                    val words = tokens.mapNotNull { t ->
                        matcher.next(t.key).takeIf { it >= 0 }?.let { j -> Triple(Math.round(cum[t.seqStart] * perFrame).toInt(), srcWords[j].second, srcWords[j].third) }
                    }
                    val (lead, tail) = Pcm.silentEdges(pcm)
                    Log.d(SileroModels.TAG, "unit ${audio.size * 1000L / sr} мс, тишина в начале ${lead * 1000L / sr} мс, в конце ${tail * 1000L / sr} мс")
                    if (!emit(pcm, words)) return
                }
                if (seg.breakMs > 0) {
                    val n = sr * seg.breakMs / 1000
                    if (pauseMin) { owed += (n - trail).coerceAtLeast(0); trail = 0; cutNext = !dash(segments.getOrNull(si + 1)?.text.orEmpty()) }
                    else { val sil = ShortArray(n); recorder?.audio(sil); if (!write(callback, sil)) return; written += sil.size }
                }
            } } finally { next?.cancel(false); pre?.futures?.forEach { it.cancel(false) } }
            // остаток паузы после последнего куска: тишину в начале следующего запроса срежет он сам
            if (owed > 0) { val sil = ShortArray(owed); recorder?.audio(sil); if (!write(callback, sil)) return; written += sil.size }
            callback.done()
            pre?.let { synthMs.addAndGet(it.ms.get()) }
            // в кэш — только доведённое до конца: оборванный TalkBack-ом звук был бы неполным
            if (recorder != null && cacheKey != null && !stopped && !enFallback && audioMs > 0) phrases.put(cacheKey, recorder.entry(), diskCache)
            audit.flush()
            val ms = System.currentTimeMillis() - t0
            note("запрос ${request.charSequenceText.length} симв., ${segments.size} сегм., $ms мс" +
                (if (firstAudioAt > 0) ", первый звук через ${firstAudioAt - t0} мс" else "") + ", звук $audioMs мс, синтез ${synthMs.get()} мс" +
                (if (audioMs > 0) ", RTF %.2f".format(synthMs.get().toDouble() / audioMs) else "") +
                // синтез не успевает — чем занят телефон (Perf); с «Подробным журналом» — всегда
                (if (rules.on("verbose_log") || audioMs > 0 && synthMs.get() >= Perf.SLOW_RTF * audioMs) Perf.describe(this, perf) else "") +
                (if (pre != null) ", из заготовки ${pre.futures.size} сегм." else "") +
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
        // до первой порции: audioAvailable блокирует, пока в плеере больше 500 мс, и после цикла был бы почти конец фразы
        if (firstAudioAt == 0L) firstAudioAt = System.currentTimeMillis()
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

    /** Готовый сегмент: звук модели Silero с токенами для подсветки или английский кусок от другого
     * движка — звук на нашей частоте и слова (ключ, доля длины текста до слова). */
    private sealed class SegOut {
        class Model(val synth: SileroModels.Synth, val tokens: List<Marks.Token>) : SegOut()
        class Proxied(val pcm: ShortArray, val words: List<Pair<String, Double>>) : SegOut()
    }

    /** Одно и то же в журнал — не чаще раза в минуту: TalkBack шлёт запрос на каждый жест. */
    private var lastOnce = ""; private var lastOnceAt = 0L
    private fun noteOnce(line: String) {
        val now = System.currentTimeMillis()
        if (line == lastOnce && now - lastOnceAt < 60_000L) return
        lastOnce = line; lastOnceAt = now; note(line)
    }

    companion object {
        /** Текст из speak скрытого ITextToSpeechService, со спанами; null — не разобрали. */
        internal fun speakText(data: Parcel): CharSequence? = runCatching {
            data.enforceInterface("android.speech.tts.ITextToSpeechService")
            data.readStrongBinder()
            if (data.readInt() != 0) TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(data) else null
        }.getOrNull()

        const val LEAD_GAP_MS = 2500L
        const val LEAD_IN_MS = 300
        /** Сколько тишины оставить в начале куска после паузы (pause_min): запас перед взрывным согласным. */
        private const val LEAD_KEEP_MS = 20
        /** Сколько первых сегментов следующего куска считать заранее: дальше успевает конвейер самого запроса. */
        private const val PREFETCH_SEGS = 2
        /** Moon+ ставит в очередь всю страницу разом, строкой на предложение: предел — только от утечки, если
         * куски так и не дойдут до onSynthesizeText. */
        private const val QUEUED_MAX = 1000
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
        /** Голос «английский» для читалок, которые выбирают голос по языку: русский текст такого запроса
         * всё равно читает Silero, латиницу — движок для английского. */
        const val EN_VOICE = "english-en"
        private val nonSpace = Regex("\\S+")
        /** Текст для журнала: не длиннее JOURNAL_TEXT, знаки вне букв, цифр и обычной пунктуации — кодом («\\u00AD»),
         * иначе мягкий перенос или комбинируемое ударение в отчёте не разглядеть. */
        fun visible(t: String): String {
            val s = if (t.length > JOURNAL_TEXT) t.take(JOURNAL_TEXT) + "…" else t
            return buildString { for (c in s) if (c.isLetterOrDigit() || c == ' ' || c in ".,!?;:()«»\"'-–—…+*{}") append(c) else append("\\u%04X".format(c.code)) }
        }
        private const val JOURNAL_TEXT = 300
        /** Громкость речи Silero (Pcm.voicedRms, скользящее среднее) — к ней подтягиваем английский. */
        @Volatile var sileroLevel = 0f
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
