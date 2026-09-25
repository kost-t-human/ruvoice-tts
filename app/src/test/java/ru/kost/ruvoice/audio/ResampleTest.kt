package ru.kost.ruvoice.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ResampleTest {
    @Test fun sameRateUntouched() {
        val p = shortArrayOf(1, 2, 3)
        assertSame(p, Pcm.resample(p, 24000, 24000))
    }

    @Test fun upsampleDoublesAndInterpolates() {
        assertArrayEquals(shortArrayOf(0, 50, 100, 150, 200, 200), Pcm.resample(shortArrayOf(0, 100, 200), 24000, 48000))
    }

    @Test fun downsampleHalves() {
        assertArrayEquals(shortArrayOf(0, 200), Pcm.resample(shortArrayOf(0, 100, 200, 300), 48000, 24000))
    }

    @Test fun oddRateLength() {
        assertEquals(48000, Pcm.resample(ShortArray(22050), 22050, 48000).size)
    }
}
