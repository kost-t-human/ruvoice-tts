package ru.kost.ruvoice.text

/**
 * Пользовательский словарь замен: алиасы, ударение с оглядкой на соседние слова, пропуск мусора.
 * Строка «ключ = замена»; пустая замена удаляет ключ из текста.
 */
class Replacements private constructor(private val rules: List<Pair<Regex, String>>) {
    fun apply(text: String): String {
        var result = text
        for ((re, repl) in rules) result = re.replace(result, Regex.escapeReplacement(repl))
        // после удаления ключей могли остаться двойные пробелы
        return result.replace(Regex(" {2,}"), " ")
    }

    companion object {
        fun parse(lines: List<String>): Replacements {
            val pairs = lines.mapNotNull { line ->
                val t = line.trim()
                if (t.isEmpty() || t.startsWith("#")) return@mapNotNull null
                val i = t.indexOf('=')
                if (i < 0) return@mapNotNull null
                val key = t.substring(0, i).trim()
                val value = t.substring(i + 1).trim()
                key.takeIf { it.isNotEmpty() }?.let { it to value }
            }.sortedByDescending { it.first.length }
            val rules = pairs.map { (key, value) ->
                val isWord = key.first().isLetterOrDigit() && key.last().isLetterOrDigit()
                val escaped = Regex.escape(key)
                val pattern = if (isWord) "(?<![\\p{L}\\d])$escaped(?![\\p{L}\\d])" else escaped
                Regex(pattern, RegexOption.IGNORE_CASE) to value
            }
            return Replacements(rules)
        }
    }
}
