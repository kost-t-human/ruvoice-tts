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
import ru.kost.ruvoice.audio.Pauses
import ru.kost.ruvoice.audio.Pcm
import ru.kost.ruvoice.audio.Tempo
import ru.kost.ruvoice.text.*
import java.util.Locale

object Pipeline {
    // Маркер паузы: {pause:N}, N — мс, режет текст сегмента на куски (см. plan ниже).
    private val pauseMarker = Regex("\\{pause:(\\d+)\\}")

    fun plan(text: CharSequence, d: SileroData, sentencePauseMs: Int, paragraphPauseMs: Int,
             replacements: Replacements = Replacements.parse(emptyList())): List<Segment> {
        val src = text.toString()
        // Последний абзац запроса намеренно без паузы абзаца — свою паузу до следующей
        // реплики читалка/пользователь и так делают между вызовами.
        val segments = if (Ssml.isSsml(src)) Ssml.parse(src) else
            Splitter.paragraphs(src).mapIndexed { i, p -> Segment(p, paragraph = true) }.let { if (it.isEmpty()) it else it.dropLast(1) + it.last().copy(paragraph = false) }
        val out = ArrayList<Segment>()
        for (seg in segments) {
            // Замены — до разбиения на предложения; маркер {pause:N} (пришедший из замены или
            // стоявший прямо в тексте) режет результат на куски, между которыми — пауза N мс.
            val txt = replacements.apply(seg.text)
            val pieces = pauseMarker.split(txt)
            val pauses = pauseMarker.findAll(txt).map { m -> m.groupValues[1].toLongOrNull()?.coerceIn(0, 10000)?.toInt() ?: 10000 }.toList()
            for ((pi, piece) in pieces.withIndex()) {
                val lastPiece = pi == pieces.size - 1
                val sents = Splitter.sentences(piece)
                for ((i, s) in sents.withIndex()) {
                    val lastInPiece = i == sents.size - 1
                    val last = lastInPiece && lastPiece
                    val breakMs = if (lastInPiece && !lastPiece) pauses[pi]
                        else sentencePauseMs + (if (last) seg.breakMs else 0) + (if (last && seg.paragraph) paragraphPauseMs else 0)
                    out += Segment(s, seg.rate, seg.pitch, breakMs = breakMs, paragraph = last && seg.paragraph)
                }
                if (sents.isEmpty()) {
                    if (!lastPiece) {
                        // кусок пуст — маркер в начале текста или два маркера подряд; пауза N уходит
                        // в предыдущий добавленный сегмент, а если его ещё нет — заводим пустой
                        val n = pauses[pi]
                        if (out.isNotEmpty()) out[out.size - 1] = out.last().copy(breakMs = out.last().breakMs + n)
                        else out += Segment("", seg.rate, seg.pitch, breakMs = n)
                    } else if (seg.breakMs > 0) out += seg
                }
            }
        }
        return out
    }
}

class SileroTtsService : TextToSpeechService() {
    // by lazy: TextToSpeechService.onCreate() зовёт onLoadLanguage раньше тела нашего onCreate.
    private val models: SileroModels by lazy { SileroModels(this) }
    private val prefs: Prefs by lazy { Prefs(this) }
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var stopped = false
    // ponytail: выгрузка на отдельном потоке — release() и synthesize() делят монитор models,
    // поэтому release() просто дождётся текущего forward, а не заблокирует main на его время.
    private val unload = Runnable {
        Thread { models.release(); Log.i(SileroModels.TAG, "модели выгружены по простою") }.start()
    }

    override fun onCreate() {
        super.onCreate()
        Thread { runCatching { warmUp() }.onFailure { Log.e(SileroModels.TAG, "прогрев", it) } }.start()
    }

    private fun warmUp() {
        synchronized(models) {
            models.ensureLoaded()
            val seq = models.data.sequence("прив+ет.")
            models.synthesize(seq, 0, prefs.sampleRate, FloatArray(seq.size) { 1f }, FloatArray(seq.size) { 1f }, LongArray(seq.size))
        }
        scheduleUnload()
    }

    private fun scheduleUnload() {
        handler.removeCallbacks(unload)
        handler.postDelayed(unload, prefs.idleMinutes.coerceAtLeast(1) * 60_000L)
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
            val rate = (request.speechRate / 100f).coerceIn(0.5f, 3f)
            val pitch = (request.pitch / 100f).coerceIn(0.5f, 2f)
            val stress = Stress(d, models, prefs.userDict())
            val replacements = prefs.replacements()
            val segments = Pipeline.plan(request.charSequenceText, d, prefs.sentencePauseMs, prefs.paragraphPauseMs, replacements)
            if (callback.start(sr, AudioFormat.ENCODING_PCM_16BIT, 1) != TextToSpeech.SUCCESS) { stopped = true; return }
            for (seg in segments) {
                if (stopped) break
                // Замены Pipeline.plan уже применил к seg.text; тип предложения классифицируется
                // по этому же тексту — так и надо.
                val prepared = Normalizer.prepare(seg.text, d.allowed)
                if (prepared.any { it != '+' && it in d.alphabet }) {
                    // Монитор models — тот же, что у SileroModels.release()/ensureLoaded() (оба @Synchronized
                    // на this), поэтому выгрузка по простою не может destroy() модуль посреди forward.
                    val audio = synchronized(models) {
                        try {
                            models.ensureLoaded()
                            val accented = stress.apply(prepared)
                            val seq = d.sequence(accented)
                            val typeIds = SentenceType.typeIds(prepared, SentenceType.classify(seg.text, d), seq.size, d)
                            val synth = models.synthesize(seq, speakerId, sr, FloatArray(seq.size) { seg.rate }, FloatArray(seq.size) { pitch * seg.pitch }, typeIds)
                            if (prefs.commaPauseMs > 0) {
                                // seq = sos + accented + eos, индексы совпадают с durs напрямую.
                                val commaIds = listOfNotNull(d.symbolToId[','], d.symbolToId[';'], d.symbolToId[':']).toHashSet()
                                val pauseIdx = seq.indices.filter { seq[it].toInt() in commaIds }.toIntArray()
                                Pauses.insert(synth.audio, synth.durs, pauseIdx, sr * prefs.commaPauseMs / 1000)
                            } else synth.audio
                        } catch (e: Throwable) {
                            // Throwable, не Exception: OOM на длинном forward не должен убивать сервис.
                            Log.e(SileroModels.TAG, "синтез не удался: «${seg.text.take(60)}»", e); null
                        }
                    }
                    if (audio != null) {
                        Pcm.fadeEdges(audio, sr, 5)
                        val pcm = Tempo.stretch(Pcm.toPcm16(audio), sr, rate)
                        if (!write(callback, pcm)) return
                        Log.d(SileroModels.TAG, "unit ${audio.size * 1000L / sr} мс")
                    }
                }
                if (seg.breakMs > 0) if (!write(callback, Pcm.silence(sr, seg.breakMs))) return
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
        return true
    }
}
