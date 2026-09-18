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
        val audio = m.synthesize(seq, 4, 48000, FloatArray(n) { 1f }, FloatArray(n) { 1f }, LongArray(n), LongArray(n), emptyMap()).audio
        val synthMs = System.currentTimeMillis() - t1
        val t2 = System.currentTimeMillis()
        m.synthesize(seq, 4, 48000, FloatArray(n) { 1f }, FloatArray(n) { 1f }, LongArray(n), LongArray(n), emptyMap())
        val warmMs = System.currentTimeMillis() - t2
        android.util.Log.i("RuVoiceTest", "load=${loadMs}ms synth=${synthMs}ms warm=${warmMs}ms len=${audio.size / 48}ms rtf=${warmMs * 48f / audio.size}")
        // эталон с десктопа (tools/export_silero.py); backbone.pte в XNNPACK на ARM даёт до 6e-3 разницы
        val ref = testCtx.assets.open("golden_audio.f32").readBytes().let { b ->
            FloatArray(b.size / 4).also { java.nio.ByteBuffer.wrap(b).order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(it) } }
        assertEquals(ref.size, audio.size)
        var d = 0f; for (i in ref.indices) d = maxOf(d, Math.abs(ref[i] - audio[i]))
        assertTrue("maxdiff $d", d < 0.02f)
        m.release(); assertFalse(m.isLoaded)
    }

    @Test fun goldenStressMatches() {
        val m = SileroModels(ctx); m.ensureLoaded()
        val golden = JSONArray(testCtx.assets.open("golden.json").bufferedReader().readText())
        val stress = Stress(m.data, m, rules = ru.kost.ruvoice.text.Rules(off = setOf("gram"))) // эталон — чистый Silero Stress
        val bad = ArrayList<String>()
        stress.apply(golden.getJSONObject(0).getString("prepared")) // прогрев
        var total = 0L; var chars = 0; var slowest = ""; var slowestMs = 0L
        for (i in 0 until golden.length()) {
            val o = golden.getJSONObject(i)
            val t = System.nanoTime()
            val got = stress.apply(o.getString("prepared"))
            val ms = (System.nanoTime() - t) / 1_000_000
            total += ms; chars += o.getString("prepared").length
            if (ms > slowestMs) { slowestMs = ms; slowest = o.getString("prepared").take(40) }
            if (got != o.getString("accented")) bad += "${o.getString("prepared")}\n  ожидалось: ${o.getString("accented")}\n  получено:  $got"
        }
        android.util.Log.i("RuVoiceTest", "stress: ${golden.length()} фраз, $chars симв., ${total} мс, самая долгая ${slowestMs} мс «$slowest»")
        assertTrue(bad.joinToString("\n"), bad.isEmpty())
    }

    /** Пак cis_ru: zip заранее `adb push dist/ruvoice-pack-cis_ru.zip /data/local/tmp/` (без файла кейс пропускается).
     * Ставит пак в filesDir приложения, грузит его тройку, синтезирует golden-фразу, возвращается к штатной. */
    @Test fun packSynthesizesAndSwitchesBack() {
        val zip = java.io.File("/data/local/tmp/ruvoice-pack-cis_ru.zip")
        org.junit.Assume.assumeTrue("нет $zip", zip.isFile)
        val pack = zip.inputStream().use { Packs.install(it, ctx.filesDir) }
        val golden = org.json.JSONObject(testCtx.assets.open("golden_pack.json").bufferedReader().readText())
        val m = SileroModels(ctx)
        val t0 = System.currentTimeMillis(); m.ensureLoaded(pack); val loadMs = System.currentTimeMillis() - t0
        assertEquals("cis_ru", m.loadedPack)
        val seq = pack.sym.sequence(golden.getString("text"))
        val ids = golden.getJSONArray("ids").let { a -> LongArray(a.length()) { a.getLong(it) } }
        assertArrayEquals(ids, seq)
        val n = seq.size
        val t1 = System.currentTimeMillis()
        val out = m.synthesize(seq, pack.speakers.getValue(golden.getString("speaker")), 48000, FloatArray(n) { 1f }, FloatArray(n) { 1f }, LongArray(n), LongArray(n), emptyMap(), types = false)
        android.util.Log.i("RuVoiceTest", "pack load=${loadMs}ms synth=${System.currentTimeMillis() - t1}ms len=${out.audio.size / 48}ms")
        assertTrue(out.audio.size > 48000 / 2)   // «привет, мир» — больше полсекунды
        assertEquals(n, out.durs.size)
        assertTrue(out.audio.any { Math.abs(it) > 0.05f })
        // обратно на штатную: акцентор/BERT не перегружаются, тройка — штатная, эталон сходится
        m.ensureLoaded()
        assertNull(m.loadedPack)
        val seqRu = m.data.sequence("прив+ет, м+ир.")
        val ru = m.synthesize(seqRu, 4, 48000, FloatArray(seqRu.size) { 1f }, FloatArray(seqRu.size) { 1f }, LongArray(seqRu.size), LongArray(seqRu.size), emptyMap()).audio
        val ref = testCtx.assets.open("golden_audio.f32").readBytes().let { b ->
            FloatArray(b.size / 4).also { java.nio.ByteBuffer.wrap(b).order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(it) } }
        assertEquals(ref.size, ru.size)
        m.release()
        Packs.delete(ctx.filesDir, "cis_ru")
    }
}
