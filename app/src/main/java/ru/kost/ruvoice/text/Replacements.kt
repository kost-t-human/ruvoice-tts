package ru.kost.ruvoice.text

import java.util.regex.PatternSyntaxException

/**
 * Пользовательский словарь замен: алиасы, ударение с оглядкой на соседние слова, пропуск мусора.
 * Строка «ключ = замена»; пустая замена удаляет ключ из текста.
 * Ключ, начинающийся с «~», — Java-regex (без автоматических границ слова), его замена
 * подставляется как есть (работают $1, $2); битый regex молча пропускается. Регистр
 * игнорируется, внутри regex это отключается через «(?-i)».
 * Замена «{skip}» равносильна пустой. Маркер «{pause:N}» в замене превращается в паузу
 * N мс — его уже разбирает Pipeline.plan, здесь это просто часть подставляемого текста.
 */
class Replacements private constructor(private val rules: List<Pair<Regex, String>>) {
    fun apply(text: String): String {
        var result = text
        for ((re, repl) in rules) {
            // regex-правило может ссылаться на несуществующую группу ($2 при одной группе) или
            // содержать одинокий $ — это всплывает только при подстановке, а не при компиляции
            // regex; такое правило просто пропускаем, остальные не должны падать из-за него.
            result = try { re.replace(result, repl) }
                catch (e: IndexOutOfBoundsException) { result }
                catch (e: IllegalArgumentException) { result }
        }
        // после удаления ключей могли остаться двойные или краевые пробелы
        return result.replace(Regex(" {2,}"), " ").trim()
    }

    companion object {
        fun parse(lines: List<String>): Replacements {
            val pairs = lines.mapNotNull { line ->
                val (key, v) = split(line) ?: return@mapNotNull null
                key to if (v.equals("{skip}", ignoreCase = true)) "" else v
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

        /** «ключ = замена» → (ключ с «~», если есть; замена). Для regex-ключа разделитель —
         * первое « = » с пробелами, чтобы «=» внутри (?<=…) не рвал строку; если такого нет —
         * первый «=». Пустые, #-строки и строки без разделителя — null. */
        fun split(line: String): Pair<String, String>? {
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#")) return null
            val sep = if (t.startsWith("~") && t.contains(" = ")) " = " else "="
            val i = t.indexOf(sep)
            if (i < 0) return null
            val key = t.substring(0, i).trim()
            return key.takeIf { it.isNotEmpty() }?.let { it to t.substring(i + sep.length).trim() }
        }

        /** Почему regex-ключ не скомпилируется; null — всё в порядке. Для диалога редактирования. */
        fun patternError(pattern: String): String? =
            try { Regex(pattern, RegexOption.IGNORE_CASE); null } catch (e: PatternSyntaxException) { e.description }

        /** Почему замена упадёт на подстановке ($N больше числа групп, одинокий «$», «\» в конце);
         * null — всё в порядке или сам regex битый (это уже сказал patternError).
         * Именованные группы ${name} не проверяются. */
        fun replacementError(pattern: String, value: String): String? {
            val groups = try { Regex(pattern).toPattern().matcher("").groupCount() }
                catch (e: PatternSyntaxException) { return null }
            var i = 0
            while (i < value.length) {
                when (value[i]) {
                    '\\' -> { if (i + 1 >= value.length) return "«\\» в конце: нечего экранировать"; i++ }
                    '$' -> {
                        val c = value.getOrNull(i + 1)
                        if (c == null || !(c.isDigit() || c == '{')) return "одинокий «$»: перед ним нужен «\\»"
                        if (c.isDigit() && c.digitToInt() > groups) return "группы \$$c нет: в ключе групп — $groups"
                    }
                }
                i++
            }
            return null
        }
    }
}
