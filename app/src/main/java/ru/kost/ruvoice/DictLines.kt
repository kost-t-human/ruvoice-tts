package ru.kost.ruvoice

import ru.kost.ruvoice.text.Replacements

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

    /** Выбор ударения в чипе: (номер слога, всего слогов, ударная буква) — чтобы «моло́ко» и «молоко́»
     * различались не только значком ударения: номер слога TalkBack скажет любым голосом. */
    fun syllable(word: String, pos: Int): Triple<Int, Int, Char> {
        val all = vowelPositions(word)
        return Triple(all.indexOf(pos) + 1, all.size, word[pos].lowercaseChar())
    }

    /** Границы русского слова (буквы и «+») вокруг позиции курсора в свободном тексте замены;
     * null, если курсор не касается слова. Курсор на границе прилипает к слову слева. */
    fun wordRangeAt(text: String, cursor: Int): IntRange? {
        fun isW(i: Int) = i in text.indices && (text[i].lowercaseChar() in 'а'..'я' || text[i] == 'ё' || text[i] == '+')
        var start = cursor.coerceIn(0, text.length)
        if (!isW(start) && !isW(start - 1)) return null
        while (isW(start - 1)) start--
        var end = start
        while (isW(end)) end++
        return start until end
    }

    /** Слово из [range] получает «+» перед гласной по индексу [vowelPos] внутри слова без «+»;
     * прежний «+» в этом слове убирается, null — просто снять ударение. */
    fun setWordStress(text: String, range: IntRange, vowelPos: Int?): String {
        val bare = text.substring(range).replace("+", "")
        val word = if (vowelPos == null) bare else bare.substring(0, vowelPos) + "+" + bare.substring(vowelPos)
        return text.replaceRange(range, word)
    }

    /** «твор+ог» → «творо́г»: «+» убирается, следующая за ним гласная получает
     * комбинируемое ударение U+0301. Без «+» слово возвращается как есть. Лишние «+» после
     * первого (не должны появляться в норме) молча вырезаются, а не превращаются в текст. */
    fun accentDisplay(variant: String): String {
        val i = variant.indexOf('+')
        if (i < 0 || i + 1 >= variant.length) return variant.replace("+", "")
        return variant.substring(0, i) + variant[i + 1] + '́' + variant.substring(i + 2).replace("+", "")
    }

    /** «[~]ключ = замена» → (ключ без «~», замена, regex?); пустые, #-строки и строки без «=» — null. */
    fun parseReplace(line: String): Triple<String, String, Boolean>? {
        val (rawKey, value) = Replacements.split(line) ?: return null
        val isRegex = rawKey.startsWith("~")
        val key = rawKey.removePrefix("~")
        if (key.isEmpty()) return null
        return Triple(key, value, isRegex)
    }

    /** Строка для сохранения: «~ключ = замена» при regex, иначе «ключ = замена». */
    fun formatReplace(key: String, value: String, isRegex: Boolean): String {
        val k = if (isRegex) "~$key" else key
        return "$k = $value"
    }
}
