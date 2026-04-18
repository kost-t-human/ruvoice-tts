package ru.kost.ruvoice.text

import java.util.regex.PatternSyntaxException

/**
 * Пользовательский словарь замен: алиасы, ударение с оглядкой на соседние слова, пропуск мусора.
 * Строка «ключ = замена»; пустая замена удаляет ключ из текста.
 * Ключ, начинающийся с «~», — Java-regex (без автоматических границ слова), его замена
 * подставляется как есть (работают $1, $2); битый regex молча пропускается.
 * Замена «{skip}» равносильна пустой. Маркер «{pause:N}» в замене превращается в паузу
 * N мс — его уже разбирает Pipeline.plan, здесь это просто часть подставляемого текста.
 */
class Replacements private constructor(private val rules: List<Pair<Regex, String>>) {
    fun apply(text: String): String {
        var result = text
        for ((re, repl) in rules) result = re.replace(result, repl)
        // после удаления ключей могли остаться двойные или краевые пробелы
        return result.replace(Regex(" {2,}"), " ").trim()
    }

    companion object {
        fun parse(lines: List<String>): Replacements {
            val pairs = lines.mapNotNull { line ->
                val t = line.trim()
                if (t.isEmpty() || t.startsWith("#")) return@mapNotNull null
                val i = t.indexOf('=')
                if (i < 0) return@mapNotNull null
                val key = t.substring(0, i).trim()
                var value = t.substring(i + 1).trim()
                if (value.equals("{skip}", ignoreCase = true)) value = ""
                key.takeIf { it.isNotEmpty() }?.let { it to value }
            }.sortedByDescending { it.first.length }
            val rules = pairs.mapNotNull { (key, value) ->
                if (key.startsWith("~")) {
                    // regex-правило: замена не экранируется, чтобы работали обратные ссылки
                    val re = try { Regex(key.removePrefix("~"), RegexOption.IGNORE_CASE) }
                        catch (e: PatternSyntaxException) { return@mapNotNull null }
                    re to value
                } else {
                    val isWord = key.first().isLetterOrDigit() && key.last().isLetterOrDigit()
                    val escaped = Regex.escape(key)
                    val pattern = if (isWord) "(?<![\\p{L}\\d])$escaped(?![\\p{L}\\d])" else escaped
                    Regex(pattern, RegexOption.IGNORE_CASE) to Regex.escapeReplacement(value)
                }
            }
            return Replacements(rules)
        }
    }
}
