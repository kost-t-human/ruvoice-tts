package ru.kost.ruvoice

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import ru.kost.ruvoice.audio.Wav
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Английские куски (правила en_proxy_books/en_proxy_sr, English.split) читает другой движок синтеза,
 * установленный на телефоне, — Google, Samsung, RHVoice. Мы для него обычный клиент TextToSpeech:
 * synthesizeToFile в cacheDir, WAV читаем и отдаём дальше своим путём (частота, громкость, подсветка слов).
 *
 * Текст этих кусков уходит тому движку — только тому, который пользователь выбрал или подтвердил в
 * диалоге при включении ([chosen]); удалили его — читаем по-русски, на другой молча не переходим.
 * Голос по умолчанию — офлайн, если у движка такой есть ([pickOffline]): иначе Google без скачанного
 * голоса отправил бы текст в сеть. Сам RuVoice в сеть не ходит.
 *
 * Клиент сервиса один на процесс ([shared]) и живёт до выгрузки моделей по простою (release). Настройки
 * спрашивают голоса своим клиентом ([create]), чтобы не ждать, пока сервис дочитает. Запросы по одному:
 * чужой движок всё равно читает очередью.
 */
class EnglishProxy private constructor(private val context: Context) {
    data class Engine(val pkg: String, val label: String)
    /** Английский голос движка; network — движку нужен интернет, installed — голос скачан. */
    data class VoiceInfo(val name: String, val locale: Locale, val network: Boolean, val installed: Boolean) {
        val offline get() = !network && installed
    }

    /** Сколько ждать движок: [init] — подключения клиента, [base] + [perChar] × длина — звука. */
    class Timeouts(val init: Long, val base: Long, val perChar: Long)

    private var tts: TextToSpeech? = null
    private var ttsPkg: String? = null
    private var ttsLang: Locale? = null
    /** Голос, выставленный клиенту; "" — по умолчанию (офлайн, если есть). */
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

    /** Клиент к движку [pkg] с английским языком; null — движок не поднялся за [initMs] или английского у него нет. */
    private fun client(pkg: String, initMs: Long): TextToSpeech? {
        tts?.let { if (ttsPkg == pkg) return it }
        shutdown()
        val init = CountDownLatch(1)
        var status = TextToSpeech.ERROR
        // onInit приходит на главном потоке; мы на потоке синтеза, его не держим
        val t = TextToSpeech(context, { status = it; init.countDown() }, pkg)
        if (!init.await(initMs, TimeUnit.MILLISECONDS) || status != TextToSpeech.SUCCESS) {
            SileroTtsService.note("английский: движок $pkg не запустился за $initMs мс")
            runCatching { t.shutdown() }; return null
        }
        val lang = listOf(Locale.US, Locale.UK, Locale.ENGLISH).firstOrNull { runCatching { t.setLanguage(it) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED) >= TextToSpeech.LANG_AVAILABLE }
        if (lang == null) {
            SileroTtsService.note("английский: у движка $pkg нет английского голоса")
            runCatching { t.shutdown() }; return null
        }
        t.setOnUtteranceProgressListener(listener)
        tts = t; ttsPkg = pkg; ttsLang = lang; appliedVoice = null
        return t
    }

    private fun englishVoices(t: TextToSpeech): List<Pair<Voice, VoiceInfo>> =
        runCatching { t.voices }.getOrNull().orEmpty()
            .filter { it.locale.language == "en" || it.locale.language == "eng" }
            .map { it to VoiceInfo(it.name, it.locale, it.isNetworkConnectionRequired,
                TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty()) }

    /** Голос из настроек; не нашёлся (удалили, сменили движок) — как «по умолчанию»: голос движка, если
     * он офлайн, иначе офлайн-голос того же языка ([pickOffline]); офлайн нет — голос движка и запись в журнал. */
    private fun applyVoice(t: TextToSpeech, voice: String) {
        if (voice == appliedVoice) return
        val all = englishVoices(t)
        val chosen = if (voice.isEmpty()) null else all.firstOrNull { it.first.name == voice }?.first
        if (chosen != null) t.setVoice(chosen) else {
            ttsLang?.let { t.setLanguage(it) }
            val cur = runCatching { t.voice }.getOrNull()
            val curInfo = all.firstOrNull { it.first.name == cur?.name }?.second
            if (curInfo == null || !curInfo.offline) {
                val off = pickOffline(all.map { it.second }, ttsLang)
                val v = off?.let { o -> all.first { it.second == o }.first }
                if (v != null) t.setVoice(v)
                else SileroTtsService.note("английский: у движка $ttsPkg нет скачанного офлайн-голоса — текст может уходить в интернет")
            }
        }
        appliedVoice = voice
    }

    /** Английские голоса движка [pkg] для выбора в настройках; null — движок не поднялся. Не с главного
     * потока: клиент подключается до пяти секунд. */
    @Synchronized fun voices(pkg: String): List<VoiceInfo>? {
        val t = client(pkg, BOOKS.init) ?: return null
        return englishVoices(t).map { it.second }
            .sortedWith(compareBy({ !it.offline }, { it.network }, { it.locale.toString() }, { it.name }))
    }

    /** Звук [text] движком [pkg] голосом [voice] ("" — по умолчанию); темп и высота — как у
     * TextToSpeech.setSpeechRate/setPitch (1 — обычные). null — не вышло (движок пропал, ошибка, не
     * уложился в [limits]) или [stopped] оборвал ожидание. */
    @Synchronized fun synth(text: String, pkg: String, voice: String, rate: Float, pitch: Float, limits: Timeouts, stopped: () -> Boolean): Wav.Audio? {
        val t = client(pkg, limits.init) ?: return null
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
            val limit = limits.base + text.length * limits.perChar
            val deadline = SystemClock.elapsedRealtime() + limit
            while (!done.await(20, TimeUnit.MILLISECONDS)) {
                if (stopped()) { runCatching { t.stop() }; return null }
                if (SystemClock.elapsedRealtime() > deadline) {
                    SileroTtsService.note("английский: движок $pkg не ответил за $limit мс, читаю по-русски")
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
        /** Книги ждут движок подольше: лучше английский с задержкой, чем транслитерация. */
        val BOOKS = Timeouts(init = 5_000, base = 10_000, perChar = 100)
        /** Экранный чтец ждать не может: не успел движок за [ms] (настройка «Сколько ждать движок», Prefs.enSrTimeoutMs)
         * — фраза по-русски, следующая — снова ему. Столько же — на подключение клиента. */
        fun screenReader(ms: Int) = Timeouts(init = ms.toLong(), base = ms.toLong(), perChar = 20)
        const val SR_TIMEOUT_DEFAULT = 2_500
        const val SR_TIMEOUT_MIN = 500
        const val SR_TIMEOUT_MAX = 10_000

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

        /** Офлайн-голос вместо сетевого по умолчанию: того же языка и страны, что выставлен клиенту, иначе
         * американский, иначе любой английский; null — офлайн-голосов нет. */
        fun pickOffline(voices: List<VoiceInfo>, lang: Locale?): VoiceInfo? {
            val off = voices.filter { it.offline }.sortedBy { it.name }
            return off.firstOrNull { lang != null && it.locale.language == lang.language && it.locale.country == lang.country }
                ?: off.firstOrNull { it.locale.country == "US" } ?: off.firstOrNull()
        }

        /** Движок, который предлагаем в диалоге включения, пока пользователь ничего не выбрал. */
        const val GOOGLE = "com.google.android.tts"
        private const val ENGINES_TTL_MS = 30_000L

        @Volatile private var instance: EnglishProxy? = null
        /** Клиент сервиса синтеза. */
        fun shared(context: Context): EnglishProxy =
            instance ?: synchronized(this) { instance ?: EnglishProxy(context.applicationContext).also { instance = it } }
        /** Отдельный клиент (настройки): после дела — release(). */
        fun create(context: Context): EnglishProxy = EnglishProxy(context.applicationContext)

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

        /** Движок, которому пользователь разрешил отдавать текст, если он всё ещё установлен; иначе null —
         * английский читается по-русски. На другой движок молча не переходим: текст ушёл бы без согласия. */
        fun chosen(context: Context, pkg: String): Engine? =
            if (pkg.isEmpty()) null else engines(context).firstOrNull { it.pkg == pkg }

        /** Что предложить в диалоге включения: выбранный, иначе Google, иначе первый установленный. */
        fun suggest(context: Context, pkg: String): Engine? {
            val all = engines(context)
            return all.firstOrNull { it.pkg == pkg } ?: all.firstOrNull { it.pkg == GOOGLE } ?: all.firstOrNull()
        }
    }
}
