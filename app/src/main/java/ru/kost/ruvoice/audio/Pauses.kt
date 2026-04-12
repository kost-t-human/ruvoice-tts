package ru.kost.ruvoice.audio

/** Вставка микро-пауз внутри уже синтезированного аудио по длительностям символов от модели. */
object Pauses {
    /**
     * Вставляет [pauseSamples] нулевых сэмплов сразу после конца каждого символа из [symbolIdx]
     * (индексы по [durs], включая sos/eos). Конец символа idx = округлённая сумма durs[0..idx] в
     * сэмплах, один фрейм = audio.size / durs.sum(). Вставки идут с конца, чтобы более ранние
     * смещения оставались верными.
     */
    fun insert(audio: FloatArray, durs: FloatArray, symbolIdx: IntArray, pauseSamples: Int): FloatArray {
        if (pauseSamples <= 0 || symbolIdx.isEmpty()) return audio
        val samplesPerFrame = audio.size / durs.sum()
        var result = audio
        for (idx in symbolIdx.sortedDescending()) {
            var cum = 0f
            for (i in 0..idx) cum += durs[i]
            val offset = Math.round(cum * samplesPerFrame).coerceIn(0, result.size)
            result = result.copyOfRange(0, offset) + FloatArray(pauseSamples) + result.copyOfRange(offset, result.size)
        }
        return result
    }
}
