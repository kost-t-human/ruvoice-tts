package ru.kost.ruvoice.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Громкость голоса: тише — ровно в g раз, громче — без среза пиков в квадрат. */
class PcmGainTest {
    @Test fun quieterIsLinear() {
        val a = floatArrayOf(0.5f, -0.9f, 1f)
        Pcm.gain(a, 0.5f)
        assertEquals(0.25f, a[0], 1e-6f); assertEquals(-0.45f, a[1], 1e-6f); assertEquals(0.5f, a[2], 1e-6f)
    }

    @Test fun louderStaysBelowFullScaleAndMonotonic() {
        val src = FloatArray(201) { (it - 100) / 100f }
        val a = src.copyOf()
        Pcm.gain(a, 2f)
        for (i in a.indices) assertTrue("пик ${a[i]}", Math.abs(a[i]) < 1f)
        for (i in 1 until a.size) assertTrue("порядок сохранён", a[i] >= a[i - 1])
        assertEquals(0.6f, a[130], 1e-6f)   // 0,3 × 2 — ниже изгиба, как есть
    }

    @Test fun oneIsNoop() {
        val a = floatArrayOf(0.3f, -1f)
        Pcm.gain(a, 1f)
        assertEquals(0.3f, a[0]); assertEquals(-1f, a[1])
    }
}
