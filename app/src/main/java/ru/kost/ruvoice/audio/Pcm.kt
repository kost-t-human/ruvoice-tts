package ru.kost.ruvoice.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Единственное место, где float становится short. Насыщение, не приведение типа: заворот знака и есть щелчок. */
object Pcm {
    fun toPcm16(samples: FloatArray): ShortArray = ShortArray(samples.size) { i ->
        val v = Math.round(samples[i] * 32767f)
        v.coerceIn(-32767, 32767).toShort()
    }

    /** Громкость голоса: множитель поверх того, что даёт модель. Выше 1 пики не срезаются в квадрат
     * (это хрип), а мягко прижимаются к потолку: до 0,8 — как есть, выше — плавный изгиб к 1. */
    fun gain(samples: FloatArray, g: Float) {
        if (g == 1f) return
        for (i in samples.indices) {
            val v = samples[i] * g
            val a = Math.abs(v)
            samples[i] = if (a <= KNEE) v else Math.signum(v) * (KNEE + (1 - KNEE) * Math.tanh(((a - KNEE) / (1 - KNEE)).toDouble()).toFloat())
        }
    }
    private const val KNEE = 0.8f

    fun fadeEdges(samples: FloatArray, sampleRate: Int, ms: Int = 5) {
        val n = minOf(sampleRate * ms / 1000, samples.size / 2)
        if (n <= 0) return
        for (i in 0 until n) {
            val g = i.toFloat() / n
            samples[i] *= g
            samples[samples.size - 1 - i] *= g
        }
    }

    /** Частота чужого движка (EnglishProxy, обычно 22050 или 24000) → наша. Линейная интерполяция:
     * речь почти вся ниже 8 кГц, на слух разницы с полифазным фильтром нет. */
    fun resample(pcm: ShortArray, from: Int, to: Int): ShortArray {
        if (from == to || pcm.isEmpty() || from <= 0 || to <= 0) return pcm
        val n = (pcm.size.toLong() * to / from).toInt().coerceAtLeast(1)
        val step = from.toDouble() / to
        return ShortArray(n) { i ->
            val x = i * step
            val a = x.toInt().coerceAtMost(pcm.size - 1)
            val b = (a + 1).coerceAtMost(pcm.size - 1)
            val t = x - a
            Math.round(pcm[a] * (1 - t) + pcm[b] * t).toInt().toShort()
        }
    }

    fun toFloat(pcm: ShortArray): FloatArray = FloatArray(pcm.size) { pcm[it] / 32767f }

    fun silence(sampleRate: Int, ms: Int): ShortArray = ShortArray(maxOf(0, sampleRate * ms / 1000))

    fun toBytes(pcm: ShortArray): ByteArray {
        val buf = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        buf.asShortBuffer().put(pcm)
        return buf.array()
    }
}
