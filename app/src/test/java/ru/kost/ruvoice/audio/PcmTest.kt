package ru.kost.ruvoice.audio

import org.junit.Assert.*
import org.junit.Test

class PcmTest {
    @Test fun saturatesInsteadOfWrapping() {
        val out = Pcm.toPcm16(floatArrayOf(0f, 0.5f, 1.03f, -1.05f, 1f, -1f))
        assertEquals(0, out[0].toInt())
        assertEquals(16384, out[1].toInt())
        assertEquals(32767, out[2].toInt())
        assertEquals(-32767, out[3].toInt())
        assertEquals(32767, out[4].toInt())
        assertEquals(-32767, out[5].toInt())
    }

    @Test fun noSignFlipOnOvershoot() {
        // плавный подъём за единицу: соседние сэмплы не меняют знак
        val src = FloatArray(200) { 0.9f + it * 0.001f }  // до 1.099
        val out = Pcm.toPcm16(src)
        for (i in 1 until out.size) assertTrue(out[i] >= 0 && out[i - 1] >= 0)
    }

    @Test fun fadeZeroesEdges() {
        val s = FloatArray(48000) { 0.8f }
        Pcm.fadeEdges(s, 48000, 5)
        assertEquals(0f, s[0], 1e-6f)
        assertEquals(0f, s[s.size - 1], 1e-6f)
        assertEquals(0.8f, s[24000], 1e-6f)
        assertTrue(s[120] > s[60])
    }

    @Test fun silenceLength() {
        assertEquals(4800, Pcm.silence(48000, 100).size)
        assertEquals(0, Pcm.silence(48000, 0).size)
    }

    @Test fun bytesLittleEndian() {
        val b = Pcm.toBytes(shortArrayOf(0x1234, -1))
        assertArrayEquals(byteArrayOf(0x34, 0x12, -1, -1), b)
    }
}
