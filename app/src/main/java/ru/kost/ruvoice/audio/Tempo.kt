package ru.kost.ruvoice.audio

import sonic.Sonic

/** Темп поверх готового PCM16. Вход уже насыщен в Pcm.toPcm16, внутри Sonic переполниться нечему. */
object Tempo {
    fun stretch(pcm: ShortArray, sampleRate: Int, rate: Float): ShortArray {
        if (rate == 1f || pcm.isEmpty()) return pcm
        val sonic = Sonic(sampleRate, 1)
        sonic.speed = rate
        sonic.pitch = 1f
        sonic.rate = 1f
        sonic.volume = 1f
        sonic.chordPitch = false
        sonic.quality = 0
        sonic.writeShortToStream(pcm, pcm.size)
        sonic.flushStream()
        val out = ShortArray(sonic.samplesAvailable())
        val n = sonic.readShortFromStream(out, out.size)
        return if (n == out.size) out else out.copyOf(n)
    }
}
