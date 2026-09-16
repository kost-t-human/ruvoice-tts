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

/** Вкладка «Проверка» через живой сервис: имена после чтения абзаца попадают в список. */
@RunWith(AndroidJUnit4::class)
class AuditServiceTest {
    private val text = "Хагрид кивнул. — Пойдём, — сказал Хагрид Гарри и повёл его в Косой переулок. " +
        "Стива Облонский проснулся в своём кабинете, а Кингсбридж спал. Она читала Бальмонта и Заболоцкого."

    @Test fun collectsNames() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = Prefs(ctx)
        val oldNames = prefs.auditNames
        prefs.auditNames = true
        prefs.audit.clear(Audit.Kind.NAMES)
        try {
            val ready = CountDownLatch(1); var status = -1
            val tts = TextToSpeech(ctx, { s -> status = s; ready.countDown() }, "ru.kost.ruvoice")
            assertTrue(ready.await(60, TimeUnit.SECONDS)); assertEquals(TextToSpeech.SUCCESS, status)
            tts.setLanguage(Locale("ru", "RU"))
            val done = CountDownLatch(1)
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) { done.countDown() }
                @Deprecated("") override fun onError(id: String?) { done.countDown() }
                override fun onError(id: String?, code: Int) { done.countDown() }
            })
            assertEquals(TextToSpeech.SUCCESS, tts.synthesizeToFile(text, Bundle(), File(ctx.cacheDir, "audit.wav"), "audit"))
            assertTrue(done.await(180, TimeUnit.SECONDS))
            tts.shutdown()
            val names = prefs.audit.entries(Audit.Kind.NAMES)
            Log.i("RuVoiceTest", "имена: " + names.joinToString { "${it.variant}×${it.count}" })
            assertTrue(names.any { it.word == "хагрид" }) // первое «Хагрид» — начало предложения, считается второе
            assertTrue(names.any { it.word == "кингсбридж" })
            assertTrue(names.none { it.word == "пойдём" || it.word == "она" })
        } finally { prefs.auditNames = oldNames }
    }

    /** Списки «Проверки» вместе со скрытыми уезжают в экспорт настроек и возвращаются импортом. */
    @Test fun auditListsSurviveExportImport() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = Prefs(ctx)
        val backup = prefs.exportJson()
        try {
            prefs.audit.clear(Audit.Kind.NAMES); prefs.audit.clear(Audit.Kind.NAMES, hidden = true)
            prefs.audit.add(Audit.Kind.NAMES, "хагрид", "хагр+ид", "сказал Хагрид")
            prefs.audit.add(Audit.Kind.NAMES, "гарри", "г+арри", "сказал Гарри"); prefs.audit.hide(Audit.Kind.NAMES, "гарри", true)
            val json = prefs.exportJson()
            prefs.audit.clear(Audit.Kind.NAMES); prefs.audit.clear(Audit.Kind.NAMES, hidden = true)
            assertTrue(prefs.audit.entries(Audit.Kind.NAMES).isEmpty())
            prefs.importJson(json)
            assertEquals(listOf("хагрид"), prefs.audit.entries(Audit.Kind.NAMES).map { it.word })
            assertEquals(listOf("гарри"), prefs.audit.entries(Audit.Kind.NAMES, hidden = true).map { it.word })
        } finally { prefs.importJson(backup) }
    }
}
