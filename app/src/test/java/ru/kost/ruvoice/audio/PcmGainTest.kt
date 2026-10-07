package ru.kost.ruvoice.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Громкость голоса: тише — ровно в g раз, громче — без среза пиков и без изгиба формы волны. */
class PcmGainTest {
    private val sr = 24000

    @Test fun quieterIsLinear() {
        val a = floatArrayOf(0.5f, -0.9f, 1f)
        Pcm.gain(a, 0.5f, sr)
        assertEquals(0.25f, a[0], 1e-6f); assertEquals(-0.45f, a[1], 1e-6f); assertEquals(0.5f, a[2], 1e-6f)
    }

    @Test fun oneIsNoopUnderCeiling() {
        val a = floatArrayOf(0.3f, -0.9f)
        Pcm.gain(a, 1f, sr)
        assertEquals(0.3f, a[0]); assertEquals(-0.9f, a[1])
    }

    /** ×1, но модель дала пик 1,0 (крик): ограничитель срабатывает и тут, иначе срез в toPcm16. */
    @Test fun oneLimitsPeakAboveCeiling() {
        val a = FloatArray(sr) { if (it == sr / 2) -1f else (0.2 * Math.sin(it * 0.05)).toFloat() }
        Pcm.gain(a, 1f, sr)
        assertTrue(a.all { Math.abs(it) <= 0.97f })
        assertEquals((0.2 * Math.sin(100 * 0.05)).toFloat(), a[100], 1e-6f)   // вдали от пика звук не тронут
    }

    /** Тихий звук при ×3 усиливается ровно в 3 раза: ограничителю нечего делать. */
    @Test fun louderIsLinearWithHeadroom() {
        val a = FloatArray(sr) { (0.2 * Math.sin(it * 0.05)).toFloat() }
        val src = a.copyOf()
        Pcm.gain(a, 3f, sr)
        for (i in a.indices) assertEquals(src[i] * 3f, a[i], 1e-5f)
    }

    /** Громкая синусоида при ×3: пики под потолком, огибающая без скачков, форма волны не гнётся
     * (в середине — та же синусоида, только тише). */
    @Test fun louderStaysBelowFullScaleSmoothly() {
        val a = FloatArray(sr) { (0.8 * Math.sin(it * 0.05)).toFloat() }
        val src = a.copyOf()
        Pcm.gain(a, 3f, sr)
        for (x in a) assertTrue("пик $x", Math.abs(x) < 1f)
        // в середине отношение выход/вход постоянно — это усиление, а не искажение
        val ratios = (sr / 4 until 3 * sr / 4).filter { Math.abs(src[it]) > 0.1f }.map { a[it] / src[it] }
        val r = ratios.average().toFloat()
        for (x in ratios) assertEquals(r, x, 1e-3f)
        assertTrue("стало громче: $r", r > 1.1f)
    }

    /** Одиночный пик посреди тихого звука: приглушается только окрестность, дальше — полное усиление. */
    @Test fun limiterIsLocal() {
        val a = FloatArray(sr) { 0.1f }
        a[sr / 2] = 0.9f
        Pcm.gain(a, 3f, sr)
        assertTrue(Math.abs(a[sr / 2]) < 1f)
        assertEquals(0.3f, a[0], 1e-5f); assertEquals(0.3f, a[sr - 1], 1e-5f)
    }

    @Test fun englishGainCapped() {
        assertEquals(Pcm.MAX_GAIN, Pcm.englishGain(2f, 2f, 2f), 0f)
        assertEquals(1.5f, Pcm.englishGain(1.5f, 1f, 1f), 1e-6f)
    }
}
