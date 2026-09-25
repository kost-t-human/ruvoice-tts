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

class LoudnessTest {
    @Test fun pausesDoNotLowerLevel() {
        val sr = 1000
        val tone = FloatArray(200) { if (it % 2 == 0) 0.5f else -0.5f }
        val withPause = tone + FloatArray(800)
        assertEquals(Pcm.voicedRms(tone, sr), Pcm.voicedRms(withPause, sr), 1e-4f)
        assertEquals(0.5f, Pcm.voicedRms(tone, sr), 1e-4f)
    }

    @Test fun silenceIsZero() = assertEquals(0f, Pcm.voicedRms(FloatArray(1000), 1000))

    @Test fun gainClamped() {
        assertEquals(2f, Pcm.matchGain(0.4f, 0.1f))
        assertEquals(0.5f, Pcm.matchGain(0.1f, 0.4f))
        assertEquals(1.25f, Pcm.matchGain(0.25f, 0.2f), 1e-6f)
        assertEquals(1f, Pcm.matchGain(0f, 0.2f))
    }
}
