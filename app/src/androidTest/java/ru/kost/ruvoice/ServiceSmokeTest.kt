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

    /** Бенч потоков forward: `-e threads N` (по умолчанию как выставит сервис по правилу fast_cores);
     * прогон 0 с загрузкой моделей, дальше тест сам ставит N (сервис зовёт setNumThreads только при
     * смене значения, так что N держится), стена и процессорное время процесса — в лог RuVoiceTest. */
    @Test fun threadsBench() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val threads = InstrumentationRegistry.getArguments().getString("threads")?.toInt()
        val ready = CountDownLatch(1)
        val tts = TextToSpeech(ctx, { ready.countDown() }, "ru.kost.ruvoice")
        assertTrue("engine bind timeout", ready.await(60, TimeUnit.SECONDS))
        tts.setLanguage(Locale("ru", "RU"))
        for (run in 0..2) {
            if (run == 1 && threads != null) org.pytorch.LitePyTorchAndroid.setNumThreads(threads)
            val done = CountDownLatch(1)
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) { done.countDown() }
                @Deprecated("") override fun onError(id: String?) { done.countDown() }
                override fun onError(id: String?, code: Int) { done.countDown() }
            })
            val cpu0 = android.os.Process.getElapsedCpuTime(); val t0 = System.currentTimeMillis()
            tts.synthesizeToFile(text, Bundle(), File(ctx.cacheDir, "bench.wav"), "b$run")
            assertTrue("synth timeout", done.await(180, TimeUnit.SECONDS))
            Log.i("RuVoiceTest", "threads=${threads ?: "default"} run=$run wall=${System.currentTimeMillis() - t0}ms cpu=${android.os.Process.getElapsedCpuTime() - cpu0}ms")
        }
        tts.shutdown()
    }

    /** Демо новых входов модели (фокус, пауза в предложении, prosody, робот, подсветка слов) на трёх темпах — файлы для прослушивания. */
    @Test fun demoMarksAtThreeRates() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val plain = "Он *не* пришёл, хотя обещал. Ждал пять{pause:600}минут и ушёл. " +
            "{prosody:80:90}Не оборачивайся, он рядом. {prosody}Всё в порядке, в 21:30 вернёмся. {prosody:100:0}Я робот, и это мой голос."
        // SSML парами «как есть / с тегом», чтобы разница была слышна рядом
        val ssml = "<speak>Раз, два, три.<break time=\"700ms\"/>Раз, два, <emphasis>три</emphasis>.<break time=\"1s\"/>" +
            "Медленно и обычно.<break time=\"700ms\"/><prosody rate=\"x-slow\">Медленно и</prosody> обычно.<break time=\"1s\"/>" +
            "<prosody rate=\"x-fast\">Быстро и</prosody> обычно.<break time=\"1s\"/>" +
            "Голос робота.<break time=\"700ms\"/><prosody pitch=\"robot\">Голос робота.</prosody><break time=\"1s\"/>" +
            "<prosody pitch=\"x-high\">Высоко</prosody>, <prosody pitch=\"x-low\">низко</prosody>, обычно.</speak>"
        val ready = CountDownLatch(1); var status = -1
        val tts = TextToSpeech(ctx, { s -> status = s; ready.countDown() }, "ru.kost.ruvoice")
        assertTrue("engine bind timeout", ready.await(60, TimeUnit.SECONDS))
        assertEquals(TextToSpeech.SUCCESS, status)
        tts.setLanguage(Locale("ru", "RU"))
        var errors = 0; val ranges = ArrayList<String>()
        for (rate in floatArrayOf(1f, 1.5f, 2f)) for ((name, text) in listOf("plain" to plain, "ssml" to ssml)) {
            val done = CountDownLatch(1)
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) { done.countDown() }
                @Deprecated("") override fun onError(id: String?) { errors++; done.countDown() }
                override fun onError(id: String?, code: Int) { errors++; done.countDown() }
                override fun onRangeStart(id: String?, a: Int, b: Int, c: Int) { ranges += "$id $a $b $c" }
            })
            tts.setSpeechRate(rate)
            val f = File(ctx.cacheDir, "demo_${name}_$rate.wav")
            assertEquals(TextToSpeech.SUCCESS, tts.synthesizeToFile(text, Bundle(), f, "$name$rate"))
            assertTrue("synth timeout $name $rate", done.await(180, TimeUnit.SECONDS))
            Log.i("RuVoiceTest", "$name rate=$rate bytes=${f.length()} path=${f.path}")
        }
        for (r in ranges) Log.i("RuVoiceTest", "range $r")
        tts.shutdown()
        assertEquals(0, errors)
        assertTrue("нет rangeStart", ranges.isNotEmpty())
    }
}
