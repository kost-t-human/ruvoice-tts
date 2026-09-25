package ru.kost.ruvoice

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import ru.kost.ruvoice.audio.Wav
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Английские куски (правило en_proxy, English.split) читает другой движок синтеза, установленный на
 * телефоне, — Google, Samsung, RHVoice. Мы для него обычный клиент TextToSpeech: synthesizeToFile в
 * cacheDir, WAV читаем и отдаём дальше своим путём (частота, громкость, подсветка слов).
 *
 * Текст этих кусков уходит тому движку. Если у него не скачан офлайн-голос, он может отправить текст
 * в сеть — об этом предупреждает диалог при включении правила. Сам RuVoice в сеть не ходит.
 *
 * Клиент один на процесс и живёт до выгрузки моделей по простою (release). Запросы по одному: чужой
 * движок всё равно читает очередью.
 */
class EnglishProxy private constructor(private val context: Context) {
    data class Engine(val pkg: String, val label: String)
    /** Английский голос движка; network — движку нужен интернет, installed — голос скачан. */
    data class VoiceInfo(val name: String, val locale: Locale, val network: Boolean, val installed: Boolean)

    private var tts: TextToSpeech? = null
    private var ttsPkg: String? = null
    private var ttsLang: Locale? = null
    /** Голос, выставленный клиенту; "" — по умолчанию движка. */
    private var appliedVoice: String? = null
    @Volatile private var pending: String? = null
    @Volatile private var ok = false
    @Volatile private var latch: CountDownLatch? = null
    private var counter = 0

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(id: String?) {}
        override fun onDone(id: String?) { if (id == pending) { ok = true; latch?.countDown() } }
        @Deprecated("Deprecated in Java")
        override fun onError(id: String?) { if (id == pending) latch?.countDown() }
        override fun onError(id: String?, code: Int) { if (id == pending) latch?.countDown() }
        override fun onStop(id: String?, interrupted: Boolean) { if (id == pending) latch?.countDown() }
    }

    /** Клиент к движку [pkg] с английским языком; null — движок не поднялся или английского у него нет. */
    private fun client(pkg: String): TextToSpeech? {
        tts?.let { if (ttsPkg == pkg) return it }
        shutdown()
        val init = CountDownLatch(1)
        var status = TextToSpeech.ERROR
        // onInit приходит на главном потоке; мы на потоке синтеза, его не держим
        val t = TextToSpeech(context, { status = it; init.countDown() }, pkg)
        if (!init.await(INIT_TIMEOUT_S, TimeUnit.SECONDS) || status != TextToSpeech.SUCCESS) {
            SileroTtsService.note("английский: движок $pkg не запустился")
            runCatching { t.shutdown() }; return null
        }
        val lang = listOf(Locale.US, Locale.UK, Locale.ENGLISH).firstOrNull { runCatching { t.setLanguage(it) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED) >= TextToSpeech.LANG_AVAILABLE }
        if (lang == null) {
            SileroTtsService.note("английский: у движка $pkg нет английского голоса")
            runCatching { t.shutdown() }; return null
        }
        t.setOnUtteranceProgressListener(listener)
        tts = t; ttsPkg = pkg; ttsLang = lang; appliedVoice = ""
        return t
    }

    /** Голос из настроек; не нашёлся (удалили, сменили движок) — голос движка по умолчанию. */
    private fun applyVoice(t: TextToSpeech, voice: String) {
        if (voice == appliedVoice) return
        val v = if (voice.isEmpty()) null else runCatching { t.voices }.getOrNull()?.firstOrNull { it.name == voice }
        if (v != null) t.setVoice(v) else ttsLang?.let { t.setLanguage(it) }
        appliedVoice = voice
    }

    /** Английские голоса движка [pkg] для выбора в настройках; null — движок не поднялся. Зовётся не
     * с главного потока: первый раз клиент подключается до пяти секунд. */
    @Synchronized fun voices(pkg: String): List<VoiceInfo>? {
        val t = client(pkg) ?: return null
        return runCatching { t.voices }.getOrNull().orEmpty()
            .filter { it.locale.language == "en" || it.locale.language == "eng" }
            .map { VoiceInfo(it.name, it.locale, it.isNetworkConnectionRequired,
                TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty()) }
            .sortedWith(compareBy({ it.network }, { !it.installed }, { it.locale.toString() }, { it.name }))
    }

    /** Звук [text] движком [pkg] голосом [voice] ("" — по умолчанию движка); темп и высота — как у TextToSpeech.setSpeechRate/setPitch (1 — обычные).
     * null — не вышло (движок пропал, ошибка, таймаут) или [stopped] оборвал ожидание. */
    @Synchronized fun synth(text: String, pkg: String, voice: String, rate: Float, pitch: Float, stopped: () -> Boolean): Wav.Audio? {
        val t = client(pkg) ?: return null
        runCatching { applyVoice(t, voice) }
        val dir = File(context.cacheDir, "en").apply { mkdirs() }
        val f = File(dir, "u${counter++ % 4}.wav")
        try {
            f.delete()
            t.setSpeechRate(rate)
            t.setPitch(pitch)
            val id = "ruvoice-${SystemClock.elapsedRealtimeNanos()}"
            val done = CountDownLatch(1)
            ok = false; latch = done; pending = id
            if (t.synthesizeToFile(text, null, f, id) != TextToSpeech.SUCCESS) { shutdown(); return null }
            val deadline = SystemClock.elapsedRealtime() + SYNTH_TIMEOUT_MS + text.length * 100L
            while (!done.await(50, TimeUnit.MILLISECONDS)) {
                if (stopped()) { runCatching { t.stop() }; return null }
                if (SystemClock.elapsedRealtime() > deadline) {
                    SileroTtsService.note("английский: движок $pkg не ответил вовремя")
                    shutdown(); return null
                }
            }
            if (!ok) { SileroTtsService.note("английский: движок $pkg вернул ошибку"); return null }
            return Wav.parse(f.readBytes()).also { if (it == null) SileroTtsService.note("английский: не разобрал звук движка $pkg") }
        } catch (e: Exception) {
            Log.e(SileroModels.TAG, "английский движок", e); shutdown(); return null
        } finally {
            pending = null; latch = null
            f.delete()
        }
    }

    @Synchronized fun release() = shutdown()

    private fun shutdown() {
        runCatching { tts?.shutdown() }
        tts = null; ttsPkg = null; ttsLang = null; appliedVoice = null
    }

    companion object {
        // голоса Google и многих других: en-us-x-iol-local, en-gb-x-rjs-network
        private val voiceNameRe = Regex("([a-z]{2,3})-([a-z]{2,3})-x-([a-z0-9]+)(?:-(?:local|network))?")

        /** Имя голоса для человека и TalkBack: «Английский (США), голос iol». Язык — из [locale], иначе из
         * имени; имя не по шаблону остаётся как есть («Английский (США), голос Alex»). */
        fun voiceTitle(name: String, locale: Locale?, display: Locale, word: String): String {
            val m = voiceNameRe.matchEntire(name.lowercase())
            val loc = locale ?: m?.let { Locale(it.groupValues[1], it.groupValues[2].uppercase()) }
            val id = m?.groupValues?.get(3) ?: name
            val lang = loc?.getDisplayName(display)?.takeIf { it.isNotBlank() }?.replaceFirstChar { it.uppercase() }
            return if (lang == null) id else "$lang, $word $id"
        }

        /** Движок по умолчанию, если в настройках не выбран другой: есть почти на всех телефонах. */
        const val GOOGLE = "com.google.android.tts"
        private const val INIT_TIMEOUT_S = 5L
        private const val SYNTH_TIMEOUT_MS = 10_000L
        private const val ENGINES_TTL_MS = 30_000L

        @Volatile private var instance: EnglishProxy? = null
        fun shared(context: Context): EnglishProxy =
            instance ?: synchronized(this) { instance ?: EnglishProxy(context.applicationContext).also { instance = it } }

        private var enginesAt = 0L
        private var enginesCache: List<Engine> = emptyList()

        /** Установленные движки синтеза, кроме нас самих (иначе запрос ушёл бы по кругу). Android 11+
         * видит их через <queries> в манифесте. Кэш на 30 с: TalkBack шлёт запрос на каждый жест. */
        fun engines(context: Context): List<Engine> = synchronized(this) {
            val now = SystemClock.elapsedRealtime()
            if (enginesAt != 0L && now - enginesAt < ENGINES_TTL_MS) return enginesCache
            val pm = context.packageManager
            enginesCache = runCatching {
                pm.queryIntentServices(Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0)
                    .mapNotNull { it.serviceInfo }
                    .filter { it.packageName != context.packageName }
                    .map { Engine(it.packageName, it.loadLabel(pm).toString()) }
                    .distinctBy { it.pkg }
            }.getOrDefault(emptyList())
            enginesAt = now
            enginesCache
        }

        /** Сбросить кэш списка — после выбора движка в настройках. */
        fun forget() = synchronized(this) { enginesAt = 0L }

        /** Движок для английского: выбранный в настройках, если он ещё стоит, иначе Google, иначе первый попавшийся. */
        fun resolve(context: Context, chosen: String): Engine? {
            val all = engines(context)
            return all.firstOrNull { it.pkg == chosen } ?: all.firstOrNull { it.pkg == GOOGLE } ?: all.firstOrNull()
        }
    }
}
