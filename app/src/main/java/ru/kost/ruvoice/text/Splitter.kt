package ru.kost.ruvoice.text

object Splitter {
    private val sentenceEnd = Regex("(?<=[.!?…])\\s+")

    fun paragraphs(text: String): List<String> = text.split(Regex("\\n+")).map { it.trim() }.filter { it.isNotEmpty() }

    fun sentences(text: String, maxLen: Int = 400): List<String> =
        sentenceEnd.split(text.trim()).filter { it.isNotBlank() }.flatMap { limit(it.trim(), maxLen) }

    private fun limit(s: String, maxLen: Int): List<String> {
        if (s.length <= maxLen) return listOf(s)
        val cut = s.lastIndexOf(',', maxLen).takeIf { it > 0 } ?: s.lastIndexOf(' ', maxLen).takeIf { it > 0 } ?: maxLen
        val head = s.substring(0, cut + 1).trim().trimEnd(',')
        return listOf(head) + limit(s.substring(cut + 1).trim(), maxLen)
    }
}
