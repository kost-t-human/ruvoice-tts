package ru.kost.ruvoice

import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Сервис через платформенный API, как его видит читалка: bind → synthesizeToFile на трёх скоростях. */
@RunWith(AndroidJUnit4::class)
class ServiceSmokeTest {
    private val text = "Поздним вечером старый смотритель запер тяжёлые ворота. На двери висел замок, а на холме стоял замок! " +
        "В 1917 году было 3 события.\nТы придёшь завтра, правда? Пойдём в кино или останемся дома?"

    @Test fun synthesizesToFileAtThreeRates() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val ready = CountDownLatch(1); var status = -1
        val tts = TextToSpeech(ctx, { s -> status = s; ready.countDown() }, "ru.kost.ruvoice")
        assertTrue("engine bind timeout", ready.await(60, TimeUnit.SECONDS))
        assertEquals(TextToSpeech.SUCCESS, status)
        assertEquals(TextToSpeech.LANG_COUNTRY_AVAILABLE, tts.setLanguage(Locale("ru", "RU")))
        var errors = 0
        for (rate in floatArrayOf(1f, 1.5f, 2f)) {
            val done = CountDownLatch(1)
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) { done.countDown() }
                @Deprecated("") override fun onError(id: String?) { errors++; done.countDown() }
                override fun onError(id: String?, code: Int) { errors++; done.countDown() }
            })
            tts.setSpeechRate(rate)
            val f = File(ctx.cacheDir, "out_$rate.wav")
            val t0 = System.currentTimeMillis()
            assertEquals(TextToSpeech.SUCCESS, tts.synthesizeToFile(text, Bundle(), f, "u$rate"))
            assertTrue("synth timeout at $rate", done.await(180, TimeUnit.SECONDS))
            Log.i("RuVoiceTest", "rate=$rate synth=${System.currentTimeMillis() - t0}ms bytes=${f.length()} path=${f.path}")
            assertTrue("wav too small at $rate", f.length() > 48000)
        }
        tts.shutdown()
        assertEquals(0, errors)
    }
}
