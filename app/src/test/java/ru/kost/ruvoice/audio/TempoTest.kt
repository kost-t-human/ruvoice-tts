package ru.kost.ruvoice.audio

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class TempoTest {
    private fun tone(sr: Int, seconds: Float): ShortArray =
        ShortArray((sr * seconds).toInt()) { (sin(2 * PI * 220 * it / sr) * 30000).toInt().toShort() }

    @Test fun rateOneIsIdentity() {
        val src = tone(48000, 0.5f)
        assertSame(src, Tempo.stretch(src, 48000, 1f))
    }

    @Test fun lengthFollowsRate() {
        val src = tone(48000, 2f)
        for (rate in floatArrayOf(1.5f, 2f, 0.8f)) {
            val out = Tempo.stretch(src, 48000, rate)
            val expected = src.size / rate
            assertTrue("rate $rate: ${out.size} vs $expected", abs(out.size - expected) < expected * 0.05)
        }
    }

    @Test fun noDiscontinuitiesOnTone() {
        // чистый тон после растяжения не должен давать скачков между соседними сэмплами больше четверти шкалы
        val out = Tempo.stretch(tone(48000, 1f), 48000, 1.5f)
        for (i in 1 until out.size) assertTrue(abs(out[i] - out[i - 1]) < 8192)
    }
}
