package ru.kost.ruvoice

import android.media.AudioFormat
import android.os.Handler
import android.os.Looper
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.Voice
import android.util.Log
import ru.kost.ruvoice.audio.Pcm
import ru.kost.ruvoice.audio.Tempo
import ru.kost.ruvoice.text.*
import java.util.Locale

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
    private fun speechPieces(s: String, rules: Rules): List<Pair<String, Boolean>> {
        val t0 = s.trim()
        // маркер {prosody} перед репликой (из SSML) — не часть текста, тире ищем за ним
        val prefix = Marks.prosodyRe.matchAt(t0, 0)?.value ?: ""
        val t = t0.substring(prefix.length)
        if (!rules.on("speech") || !speechStart.containsMatchIn(t) || authorStart.containsMatchIn(t)) return listOf(t0 to false)
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
    private val loneLetter = Regex("""^\s*((?:прописная буква\s+)?)(\p{L})\s*[.)]?\s*$""", RegexOption.IGNORE_CASE)

    fun plan(text: CharSequence, d: SileroData, sentencePauseMs: Int, paragraphPauseMs: Int,
             replacements: Replacements = Replacements.parse(emptyList()), rules: Rules = Rules()): List<Segment> {
        val src = text.toString().let { t ->
            if (!rules.on("letter_name")) t else loneLetter.matchEntire(t)?.let { m ->
                Abbrev.letterName(m.groupValues[2][0])?.let { m.groupValues[1].lowercase() + it } } ?: t
        }
        // Последний абзац запроса намеренно без паузы абзаца — свою паузу до следующей
        // реплики читалка/пользователь и так делают между вызовами.
        val segments = if (rules.on("ssml") && Ssml.isSsml(src)) Ssml.parse(src) else
            Splitter.paragraphs(src, rules).mapIndexed { i, p -> Segment(p, paragraph = true) }.let { if (it.isEmpty()) it else it.dropLast(1) + it.last().copy(paragraph = false) }
        val out = ArrayList<Segment>()
        for (seg in segments) {
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
                    val parts = speechPieces(s, rules)
                    for ((k, part) in parts.withIndex()) {
                        val lastPart = k == parts.size - 1
                        out += Segment(part.first, breakMs = if (lastPart) breakMs else 0,
                            paragraph = lastPart && last && seg.paragraph, speech = part.second)
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
        return carryProsody(out)
    }
}

class SileroTtsService : TextToSpeechService() {
    // by lazy: TextToSpeechService.onCreate() зовёт onLoadLanguage раньше тела нашего onCreate.
    private val models: SileroModels by lazy { SileroModels(this) }
    private val prefs: Prefs by lazy { Prefs(this) }
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var stopped = false
    // Аудиовыход телефона уходит в standby через ~3 с тишины, а после пробуждения HAL плавно
    // поднимает громкость — первое слово фразы выходит тихим. Если с прошлого звука прошло
    // больше LEAD_GAP_MS, начинаем с LEAD_IN_MS тишины, чтобы подъём пришёлся на неё.
    // ponytail: пороги под AOSP standby 3 с; сделать настройкой, если на другом телефоне не совпадёт.
    private var lastAudioAt = 0L
    // Выгрузка на отдельном потоке: release() и synthesize() делят монитор models, поэтому
    // release() просто дождётся текущего forward, а не заблокирует main на его время.
    private val unload = Runnable {
        Thread { models.release(); Log.i(SileroModels.TAG, "модели выгружены по простою") }.start()
    }

    override fun onCreate() {
        super.onCreate()
        Thread { runCatching { warmUp() }.onFailure { Log.e(SileroModels.TAG, "прогрев", it) } }.start()
    }

    private fun warmUp() {
        // словари разбираются раз на процесс; большие списки — секунда на телефоне, лучше
        // потратить её сейчас, чем на первой фразе
        prefs.userDict(); prefs.replacements()
        synchronized(models) {
            models.ensureLoaded()
            val seq = models.data.sequence("прив+ет.")
            models.synthesize(seq, 0, prefs.sampleRate, FloatArray(seq.size) { 1f }, FloatArray(seq.size) { 1f }, LongArray(seq.size), LongArray(seq.size), emptyMap())
        }
        scheduleUnload()
    }

    private fun scheduleUnload() {
        handler.removeCallbacks(unload)
        if (prefs.idleOn) handler.postDelayed(unload, prefs.idleMinutes.coerceAtLeast(1) * 60_000L)
    }

    override fun onDestroy() { handler.removeCallbacks(unload); stopped = true; models.release(); super.onDestroy() }

    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int =
        if (lang == "rus") TextToSpeech.LANG_COUNTRY_AVAILABLE else TextToSpeech.LANG_NOT_SUPPORTED
    override fun onGetLanguage(): Array<String> = arrayOf("rus", "RUS", "")
    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int {
        val r = onIsLanguageAvailable(lang, country, variant)
        // TextToSpeechService.onCreate() зовёт этот метод синхронно на главном потоке — грузить
        // модели прямо тут нельзя, это надолго заблокирует главный поток. Прогрев (onCreate выше)
        // и так грузит их отдельным потоком, поэтому с главного потока просто отвечаем по языку.
        if (r == TextToSpeech.LANG_COUNTRY_AVAILABLE && Looper.myLooper() != Looper.getMainLooper()) {
            runCatching { models.ensureLoaded() }.onFailure {
                Log.e(SileroModels.TAG, "загрузка моделей", it); return TextToSpeech.LANG_NOT_SUPPORTED
            }
            scheduleUnload()
        }
        return r
    }

    private fun voiceName(speaker: String) = "ru-ru-$speaker"
    override fun onGetVoices(): List<Voice> = models.data.speakers.keys.sorted().map {
        Voice(voiceName(it), Locale("ru", "RU"), Voice.QUALITY_HIGH, Voice.LATENCY_NORMAL, false, emptySet())
    }
    override fun onIsValidVoiceName(name: String?): Int =
        if (name != null && name.removePrefix("ru-ru-") in models.data.speakers) TextToSpeech.SUCCESS else TextToSpeech.ERROR
    override fun onLoadVoice(name: String?): Int = onIsValidVoiceName(name)
    override fun onGetDefaultVoiceNameFor(lang: String?, country: String?, variant: String?): String = voiceName(prefs.voice)

    override fun onStop() { stopped = true }

    override fun onSynthesizeText(request: SynthesisRequest, callback: SynthesisCallback) {
        stopped = false
        handler.removeCallbacks(unload)
        val t0 = System.currentTimeMillis()
        try {
            models.ensureLoaded()
            val d = models.data
            val sr = prefs.sampleRate
            val speaker = request.voiceName?.removePrefix("ru-ru-")?.takeIf { it in d.speakers }
                ?: prefs.voice.takeIf { it in d.speakers }
                ?: "xenia".takeIf { it in d.speakers }
                ?: d.speakers.keys.first()
            val speakerId = d.speakers.getValue(speaker)
            val rate = (request.speechRate / 100f * prefs.rate).coerceIn(0.5f, 3f)
            val pitch = (request.pitch / 100f * prefs.pitch).coerceIn(0.5f, 2f)
            // настройки слушают слово «как модель», без пользовательского словаря
            val noDict = request.params?.getString("ruvoice.nodict") == "1"
            val rules = prefs.rules()
            val stress = Stress(d, models, if (noDict) emptyMap() else prefs.userDict(), rules)
            val replacements = prefs.replacements()
            // Голос/темп/питч прямой речи — читаем один раз на запрос, как replacements.
            val quoteSpeakerId = prefs.quoteVoice.takeIf { it in d.speakers }?.let { d.speakers.getValue(it) }
            val quoteRate = prefs.quoteRate
            val quotePitch = prefs.quotePitch
            val segments = Pipeline.plan(request.charSequenceText, d, prefs.sentencePauseMs, prefs.paragraphPauseMs, replacements, rules)
            // Пауза после запятой — явная длительность самой запятой в кадрах модели (Marks.frames).
            val commaIds = if (prefs.commaPauseMs <= 0) emptySet() else listOfNotNull(d.symbolToId[','],
                *(if (rules.on("pause_semicolon")) arrayOf(d.symbolToId[';'], d.symbolToId[':']) else emptyArray())).toHashSet()
            val commaFrames = Marks.frames(prefs.commaPauseMs)
            // Слова запроса для подсветки читаемого слова (rangeStart): ключ и смещения в тексте.
            // SSML-теги и маркеры заменяются пробелами той же длины, чтобы смещения не поехали.
            val srcText = request.charSequenceText.toString().let { if (rules.on("ssml") && Ssml.isSsml(it)) Ssml.blankTags(it) else it }
                .let { Marks.blank(it) }
            val srcWords = Regex("\\S+").findAll(srcText).map { Triple(Marks.key(it.value), it.range.first, it.range.last + 1) }.toList()
            val matcher = Marks.Matcher(srcWords.map { it.first })
            var written = 0L // сэмплов отдано читалке — точка отсчёта markerInFrames
            if (callback.start(sr, AudioFormat.ENCODING_PCM_16BIT, 1) != TextToSpeech.SUCCESS) { stopped = true; return }
            if (System.currentTimeMillis() - lastAudioAt > LEAD_GAP_MS) { val sil = Pcm.silence(sr, LEAD_IN_MS); if (!write(callback, sil)) return; written += sil.size }
            for (seg in segments) {
                if (stopped) break
                // Замены Pipeline.plan уже применил к seg.text; тип предложения классифицируется
                // по этому же тексту — так и надо.
                val marks = Marks.parse(seg.text, rules.focusLevel)
                val prepared = Normalizer.prepare(marks.text, d.allowed, rules)
                if (prepared.any { it != '+' && it in d.alphabet }) {
                    // Монитор models — тот же, что у SileroModels.release()/ensureLoaded() (оба @Synchronized
                    // на this), поэтому выгрузка по простою не может destroy() модуль посреди forward.
                    val synth = synchronized(models) {
                        try {
                            models.ensureLoaded()
                            val accented = stress.apply(prepared)
                            val seq = d.sequence(accented)
                            val typeIds = SentenceType.typeIds(prepared, SentenceType.classify(marks.text, d, rules), seq.size, d)
                            val curSpeakerId = if (seg.speech) quoteSpeakerId ?: speakerId else speakerId
                            val curPitch = pitch * (if (seg.speech) quotePitch else 1f)
                            val al = Marks.align(marks.words, accented, seq.size, d)
                            for (i in al.pitches.indices) al.pitches[i] *= curPitch
                            // seq = sos + accented + eos, индексы совпадают с durs напрямую.
                            val symbDurs = seq.indices.filter { seq[it].toInt() in commaIds }.associate { it.toLong() to commaFrames } + al.symbDurs
                            Pair(models.synthesize(seq, curSpeakerId, sr, al.rates, al.pitches, typeIds, al.focus, symbDurs), Marks.tokens(accented, d))
                        } catch (e: Throwable) {
                            // Throwable, не Exception: OOM на длинном forward не должен убивать сервис.
                            Log.e(SileroModels.TAG, "синтез не удался: «${seg.text.take(60)}»", e); null
                        }
                    }
                    if (synth != null) {
                        val (out, tokens) = synth
                        val audio = out.audio
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
                            if (j >= 0) callback.rangeStart((written + Math.round(cum[t.seqStart] * perFrame)).toInt(), srcWords[j].second, srcWords[j].third)
                        }
                        if (!write(callback, pcm)) return
                        written += pcm.size
                        Log.d(SileroModels.TAG, "unit ${audio.size * 1000L / sr} мс")
                    }
                }
                if (seg.breakMs > 0) { val sil = Pcm.silence(sr, seg.breakMs); if (!write(callback, sil)) return; written += sil.size }
            }
            callback.done()
            Log.i(SileroModels.TAG, "запрос ${request.charSequenceText.length} симв., ${segments.size} сегм., ${System.currentTimeMillis() - t0} мс")
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

    companion object {
        const val LEAD_GAP_MS = 2500L
        const val LEAD_IN_MS = 300
    }
}
