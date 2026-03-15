package ru.kost.ruvoice

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import ru.kost.ruvoice.text.SentenceType
import ru.kost.ruvoice.text.Stress
import org.json.JSONArray

@RunWith(AndroidJUnit4::class)
class SileroModelsTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val testCtx = InstrumentationRegistry.getInstrumentation().context

    @Test fun loadsAndSynthesizes() {
        val m = SileroModels(ctx)
        val t0 = System.currentTimeMillis(); m.ensureLoaded(); val loadMs = System.currentTimeMillis() - t0
        val seq = m.data.sequence("прив+ет, м+ир.")
        val n = seq.size
        val t1 = System.currentTimeMillis()
        val audio = m.synthesize(seq, 4, 48000, FloatArray(n) { 1f }, FloatArray(n) { 1f }, LongArray(n))
        val synthMs = System.currentTimeMillis() - t1
        android.util.Log.i("RuVoiceTest", "load=${loadMs}ms synth=${synthMs}ms len=${audio.size / 48}ms rtf=${audio.size / 48f / synthMs}")
        assertTrue(audio.size > 48000 / 2)
        assertTrue(audio.all { it > -1.5f && it < 1.5f })
        m.release(); assertFalse(m.isLoaded)
    }

    @Test fun goldenStressMatches() {
        val m = SileroModels(ctx); m.ensureLoaded()
        val golden = JSONArray(testCtx.assets.open("golden.json").bufferedReader().readText())
        val stress = Stress(m.data, m)
        val bad = ArrayList<String>()
        for (i in 0 until golden.length()) {
            val o = golden.getJSONObject(i)
            val got = stress.apply(o.getString("prepared"))
            if (got != o.getString("accented")) bad += "${o.getString("prepared")}\n  ожидалось: ${o.getString("accented")}\n  получено:  $got"
        }
        assertTrue(bad.joinToString("\n"), bad.isEmpty())
    }
}
