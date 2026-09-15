package ru.kost.ruvoice

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Грузит первый установленный пак из filesDir/packs (92 МБ в assets тестов не кладём); без паков — пропуск. */
@RunWith(AndroidJUnit4::class)
class PackModelsTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun loadsPackAndSynthesizes() {
        val pack = Packs.installed(ctx.filesDir).firstOrNull()
        assumeTrue("нет установленных паков", pack != null)
        val (lang, l) = pack!!.languages.entries.first()
        val m = SileroModels(ctx)
        m.ensureLoaded(); assertTrue(m.isLoaded)
        m.ensurePack(pack)
        assertFalse("русская тройка должна выгрузиться", m.isLoaded)
        assertEquals(pack.id, m.loadedPackId)
        val seq = pack.sym.sequence("а, а.")
        val n = seq.size
        val t = System.currentTimeMillis()
        val audio = m.synthesizePack(seq, l.speakers.values.first(), 48000, FloatArray(n) { 1f }, FloatArray(n) { 1f }, emptyMap()).audio
        android.util.Log.i("RuVoiceTest", "pack=${pack.id} lang=$lang synth=${System.currentTimeMillis() - t}ms len=${audio.size / 48}ms")
        assertTrue(audio.size > 4800)
        assertTrue(audio.all { it > -1.5f && it < 1.5f })
        m.release(); assertNull(m.loadedPackId)
    }
}
