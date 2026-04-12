package ru.kost.ruvoice.audio

import org.junit.Assert.*
import org.junit.Test

class PausesTest {
    // durs [3,4,6], сумма 13 -> 1300/13 = 100 сэмплов на фрейм
    private fun audio() = FloatArray(1300) { (it + 1).toFloat() } // ненулевые, чтобы отличать от вставленной тишины
    private val durs = floatArrayOf(3f, 4f, 6f)

    @Test fun insertsSilenceAtFrameOffset() {
        val out = Pauses.insert(audio(), durs, intArrayOf(1), 50)
        assertEquals(1350, out.size)
        for (i in 700 until 750) assertEquals(0f, out[i], 0f)
        assertTrue(out[699] != 0f)
        assertTrue(out[750] != 0f)
    }

    @Test fun twoInsertionsFromEndBackwards() {
        val out = Pauses.insert(audio(), durs, intArrayOf(0, 1), 50)
        assertEquals(1400, out.size)
        for (i in 300 until 350) assertEquals(0f, out[i], 0f)
        for (i in 750 until 800) assertEquals(0f, out[i], 0f)
        // не-нулевые сэмплы должны идти в исходном порядке
        val nonZero = out.filter { it != 0f }
        assertArrayEquals(audio(), nonZero.toFloatArray(), 0f)
    }

    @Test fun noOpWhenPauseZeroOrNoIndices() {
        val a = audio()
        assertSame(a, Pauses.insert(a, durs, intArrayOf(1), 0))
        assertSame(a, Pauses.insert(a, durs, IntArray(0), 50))
    }
}
