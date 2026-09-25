package ru.kost.ruvoice

import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import ru.kost.ruvoice.text.Rules
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * «Английский другим движком» на живом телефоне: движки видны (queries в манифесте), свой пакет в списке
 * не значится, чужой движок отдаёт звук, сервис заявляет английский и читает смешанный текст до конца.
 * Без другого движка с английским (эмулятор без Google TTS) тесты пропускаются, а не падают.
 * Настройки меняет на время теста и возвращает.
 */
@RunWith(AndroidJUnit4::class)
class EnglishProxyTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefs = Prefs(ctx)
    private var savedOff = emptySet<String>(); private var savedEngine = ""

    @Before fun save() { savedOff = prefs.rulesOff; savedEngine = prefs.enEngine; EnglishProxy.forget() }
    @After fun restore() { prefs.rulesOff = savedOff; prefs.enEngine = savedEngine }

    private fun engine(): EnglishProxy.Engine? = EnglishProxy.suggest(ctx, "")

    @Test fun enginesExcludeSelf() {
        assertTrue(EnglishProxy.engines(ctx).none { it.pkg == ctx.packageName })
    }

    @Test fun chosenIsStrict() {
        assertNull(EnglishProxy.chosen(ctx, ""))
        assertNull(EnglishProxy.chosen(ctx, "com.example.not.installed"))
        val e = engine(); assumeTrue("нет другого движка", e != null)
        assertEquals(e, EnglishProxy.chosen(ctx, e!!.pkg))
    }

    @Test fun proxySynthesizesEnglish() {
        val e = engine(); assumeTrue("нет другого движка", e != null)
        val p = EnglishProxy.create(ctx)
        try {
            val voices = p.voices(e!!.pkg)
            assumeTrue("у движка нет английского", !voices.isNullOrEmpty())
            Log.i("RuVoiceTest", "голоса ${e.pkg}: ${voices!!.joinToString { "${it.name}${if (it.offline) "" else " (сеть)"}" }}")
            val t0 = System.currentTimeMillis()
            val a = p.synth("Hello, this is a test.", e.pkg, "", 1f, 1f, EnglishProxy.BOOKS) { false }
            Log.i("RuVoiceTest", "английский ${System.currentTimeMillis() - t0} мс, ${a?.pcm?.size} сэмплов, ${a?.sampleRate} Гц")
            assertNotNull(a)
            assertTrue(a!!.sampleRate > 0)
            assertTrue("звук короче 0,3 с", a.pcm.size > a.sampleRate * 3 / 10)
        } finally { p.release() }
    }

    @Test fun serviceReadsMixedTextAndClaimsEnglish() {
        val e = engine(); assumeTrue("нет другого движка", e != null)
        prefs.enEngine = e!!.pkg
        prefs.rulesOff = Rules(prefs.rulesOff).with("en_proxy_books", true).off
        val ready = CountDownLatch(1); var status = -1
        val tts = TextToSpeech(ctx, { s -> status = s; ready.countDown() }, ctx.packageName)
        try {
            assertTrue("engine bind timeout", ready.await(60, TimeUnit.SECONDS))
            assertEquals(TextToSpeech.SUCCESS, status)
            assertTrue("английский не заявлен", tts.setLanguage(Locale.US) >= TextToSpeech.LANG_AVAILABLE)
            assertTrue(tts.setLanguage(Locale("ru", "RU")) >= TextToSpeech.LANG_AVAILABLE)
            val done = CountDownLatch(1); var errors = 0
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) { done.countDown() }
                @Deprecated("") override fun onError(id: String?) { errors++; done.countDown() }
                override fun onError(id: String?, code: Int) { errors++; done.countDown() }
            })
            val f = File(ctx.cacheDir, "mixed.wav")
            assertEquals(TextToSpeech.SUCCESS, tts.synthesizeToFile("Он сказал: I don't know what you mean, и ушёл.", Bundle(), f, "mixed"))
            assertTrue("synth timeout", done.await(180, TimeUnit.SECONDS))
            assertEquals(0, errors)
            assertTrue("звук слишком короткий", f.length() > 48000)
            Log.i("RuVoiceTest", "смешанный текст: ${f.length()} байт; журнал: ${SileroTtsService.journal().lastOrNull()}")
        } finally { tts.shutdown() }
    }
}
