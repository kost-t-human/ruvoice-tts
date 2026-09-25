package ru.kost.ruvoice.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavTest {
    private fun wav(rate: Int, channels: Int, samples: ShortArray, dataSize: Int = samples.size * 2, extra: ByteArray = ByteArray(0)): ByteArray {
        val b = ByteBuffer.allocate(44 + extra.size + samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + samples.size * 2).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(channels.toShort()).putInt(rate)
            .putInt(rate * channels * 2).putShort((channels * 2).toShort()).putShort(16)
        b.put(extra)
        b.put("data".toByteArray()).putInt(dataSize)
        for (s in samples) b.putShort(s)
        return b.array()
    }

    @Test fun mono16() {
        val a = Wav.parse(wav(24000, 1, shortArrayOf(1, -2, 3000)))!!
        assertEquals(24000, a.sampleRate)
        assertArrayEquals(shortArrayOf(1, -2, 3000), a.pcm)
    }

    @Test fun stereoAveraged() {
        assertArrayEquals(shortArrayOf(15, -10), Wav.parse(wav(22050, 2, shortArrayOf(10, 20, -10, -10)))!!.pcm)
    }

    @Test fun streamedHeaderWithZeroDataSize() {
        assertArrayEquals(shortArrayOf(5, 6, 7), Wav.parse(wav(16000, 1, shortArrayOf(5, 6, 7), dataSize = 0))!!.pcm)
    }

    @Test fun skipsUnknownChunk() {
        val list = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).put("LIST".toByteArray()).putInt(4).putInt(0).array()
        assertArrayEquals(shortArrayOf(9), Wav.parse(wav(24000, 1, shortArrayOf(9), extra = list))!!.pcm)
    }

    @Test fun notWav() {
        assertNull(Wav.parse("hello world, not a wav".toByteArray()))
        assertNull(Wav.parse(ByteArray(0)))
    }
}
