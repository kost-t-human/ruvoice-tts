package ru.kost.ruvoice.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Единственное место, где float становится short. Насыщение, не приведение типа: заворот знака и есть щелчок. */
object Pcm {
    fun toPcm16(samples: FloatArray): ShortArray = ShortArray(samples.size) { i ->
        val v = Math.round(samples[i] * 32767f)
        v.coerceIn(-32767, 32767).toShort()
    }

    fun fadeEdges(samples: FloatArray, sampleRate: Int, ms: Int = 5) {
        val n = minOf(sampleRate * ms / 1000, samples.size / 2)
        if (n <= 0) return
        for (i in 0 until n) {
            val g = i.toFloat() / n
            samples[i] *= g
            samples[samples.size - 1 - i] *= g
        }
    }

    fun silence(sampleRate: Int, ms: Int): ShortArray = ShortArray(maxOf(0, sampleRate * ms / 1000))

    fun toBytes(pcm: ShortArray): ByteArray {
        val buf = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        buf.asShortBuffer().put(pcm)
        return buf.array()
    }
}
