package ru.kost.ruvoice.text

object Splitter {
    // Не разбивать после одной буквы с точкой — инициалы («Л. Н. Толстой»), сокращения («т. е.», «г.»).
    private val sentenceEnd = Regex("(?<=[.!?…])(?<!\\s[а-яёa-z]\\.)(?<!^[а-яёa-z]\\.)\\s+", RegexOption.IGNORE_CASE)

    // Многобуквенные сокращения, после которых тоже не разрываем предложение (13).
    private val abbrevStopWords = setOf("оз", "гр", "гг", "вв", "стр", "св", "см", "др", "пр", "рис", "табл", "ср",
        "им", "ул", "корп", "кв", "проф", "акад", "чл", "мл", "ст", "изд", "тыс", "руб", "коп", "напр", "англ",
        "нем", "фр", "лат", "греч", "букв", "прим", "перев", "ок", "мин", "макс", "обл")
    private val abbrevWordAtEndRe = Regex("""(?:^|\s)([а-яёa-z]+)\.$""", RegexOption.IGNORE_CASE)

    private fun endsWithAbbrev(piece: String) =
        abbrevWordAtEndRe.find(piece)?.groupValues?.get(1)?.lowercase() in abbrevStopWords

    fun paragraphs(text: String): List<String> = text.split(Regex("\\n+")).map { it.trim() }.filter { it.isNotEmpty() }

    fun sentences(text: String, maxLen: Int = 400): List<String> {
        val merged = mutableListOf<String>()
        for (piece in sentenceEnd.split(text.trim())) {
            if (merged.isNotEmpty() && endsWithAbbrev(merged.last())) merged[merged.size - 1] += " $piece"
            else merged += piece
        }
        return merged.filter { it.isNotBlank() }.flatMap { limit(it.trim(), maxLen) }
    }

    private fun limit(s: String, maxLen: Int): List<String> {
        if (s.length <= maxLen) return listOf(s)
        val cut = s.lastIndexOf(',', maxLen).takeIf { it > 0 } ?: s.lastIndexOf(' ', maxLen).takeIf { it > 0 } ?: maxLen
        val head = s.substring(0, cut + 1).trim().trimEnd(',')
        return listOf(head) + limit(s.substring(cut + 1).trim(), maxLen)
    }
}
