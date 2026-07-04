package ru.kost.ruvoice

/**
 * Чистый разбор/форматирование строк user_stress.txt и user_replace.txt для табличных
 * редакторов настроек (см. Prefs.userDict / Replacements.parse — тот же формат файлов,
 * здесь только для UI: список записей и диалоги редактирования).
 */
object DictLines {
    private const val VOWELS = "аеёиоуыэюя"

    /** «слово вариант» → (слово, вариант); пустые, #-строки и строки без второго слова — null. */
    fun parseStress(line: String): Pair<String, String>? {
        val t = line.trim()
        if (t.isEmpty() || t.startsWith("#")) return null
        val parts = t.split(Regex("\\s+"))
        if (parts.size < 2) return null
        return parts[0] to parts[1]
    }

    /** Строка для сохранения: слово с «+» перед гласной по индексу символа, обе части lowercase. */
    fun formatStress(word: String, vowelIndex: Int): String {
        val w = word.lowercase()
        val variant = w.substring(0, vowelIndex) + "+" + w.substring(vowelIndex)
        return "$w $variant"
    }

    /** Позиции (индексы символов) гласных букв в слове — по ним строятся чипы диалога. */
    fun vowelPositions(word: String): List<Int> = word.indices.filter { word[it].lowercaseChar() in VOWELS }

    /** «твор+ог» → «творо́г»: «+» убирается, следующая за ним гласная получает
     * комбинируемое ударение U+0301. Без «+» слово возвращается как есть. */
    fun accentDisplay(variant: String): String {
        val i = variant.indexOf('+')
        if (i < 0 || i + 1 >= variant.length) return variant.replace("+", "")
        return variant.substring(0, i) + variant[i + 1] + '́' + variant.substring(i + 2)
    }

    /** «[~]ключ = замена» → (ключ без «~», замена, regex?); пустые, #-строки и строки без «=» — null. */
    fun parseReplace(line: String): Triple<String, String, Boolean>? {
        val t = line.trim()
        if (t.isEmpty() || t.startsWith("#")) return null
        val i = t.indexOf('=')
        if (i < 0) return null
        var key = t.substring(0, i).trim()
        val value = t.substring(i + 1).trim()
        val isRegex = key.startsWith("~")
        if (isRegex) key = key.removePrefix("~")
        if (key.isEmpty()) return null
        return Triple(key, value, isRegex)
    }

    /** Строка для сохранения: «~ключ = замена» при regex, иначе «ключ = замена». */
    fun formatReplace(key: String, value: String, isRegex: Boolean): String {
        val k = if (isRegex) "~$key" else key
        return "$k = $value"
    }
}
