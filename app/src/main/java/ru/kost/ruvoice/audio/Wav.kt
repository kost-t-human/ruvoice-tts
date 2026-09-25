package ru.kost.ruvoice.audio

/** WAV, который пишет чужой движок в synthesizeToFile (EnglishProxy): PCM 8/16 бит или float 32, моно или стерео. */
object Wav {
    class Audio(val pcm: ShortArray, val sampleRate: Int)

    /** null — не WAV или формат, который мы не разбираем. Размер data 0 или больше файла (движок писал
     * потоком и не вернулся поправить заголовок) — читаем до конца файла. */
    fun parse(b: ByteArray): Audio? {
        if (b.size < 12 || String(b, 0, 4, Charsets.US_ASCII) != "RIFF" || String(b, 8, 4, Charsets.US_ASCII) != "WAVE") return null
        var p = 12
        var format = 1; var channels = 1; var rate = 0; var bits = 16
        while (p + 8 <= b.size) {
            val id = String(b, p, 4, Charsets.US_ASCII)
            val size = le32(b, p + 4)
            val body = p + 8
            if (id == "fmt " && body + 16 <= b.size) {
                format = le16(b, body); channels = le16(b, body + 2).coerceAtLeast(1); rate = le32(b, body + 4); bits = le16(b, body + 14)
                // WAVE_FORMAT_EXTENSIBLE: настоящий формат — первые два байта SubFormat
                if (format == 0xFFFE && size >= 26 && body + 26 <= b.size) format = le16(b, body + 24)
            } else if (id == "data") {
                if (rate <= 0) return null
                val n = if (size <= 0 || body.toLong() + size > b.size) b.size - body else size
                return decode(b, body, n, format, channels, bits)?.let { Audio(it, rate) }
            }
            if (size < 0) return null
            p = body + size + (size and 1)
        }
        return null
    }

    private fun decode(b: ByteArray, off: Int, len: Int, format: Int, channels: Int, bits: Int): ShortArray? {
        val bytes = bits / 8
        if (bytes <= 0) return null
        val frames = len / (bytes * channels)
        fun sample(i: Int): Int {
            val o = off + i * bytes
            return when {
                format == 1 && bits == 16 -> le16(b, o).toShort().toInt()
                format == 1 && bits == 8 -> ((b[o].toInt() and 0xFF) - 128) shl 8
                format == 3 && bits == 32 -> Math.round(java.lang.Float.intBitsToFloat(le32(b, o)).coerceIn(-1f, 1f) * 32767f)
                else -> Int.MIN_VALUE
            }
        }
        if (sample(0).let { frames > 0 && it == Int.MIN_VALUE }) return null
        return ShortArray(frames) { f ->
            var s = 0
            for (c in 0 until channels) s += sample(f * channels + c)
            (s / channels).toShort()
        }
    }

    private fun le16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    private fun le32(b: ByteArray, o: Int) = le16(b, o) or (le16(b, o + 2) shl 16)
}
