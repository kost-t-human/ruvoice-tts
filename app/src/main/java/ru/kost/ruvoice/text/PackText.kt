package ru.kost.ruvoice.text

import ru.kost.ruvoice.Pack

/**
 * Текст → вход модели пака, как prepare_text_input в пакете Silero: lowercase, тире, транслитерация
 * из таблицы пака (грузинский, армянский, латиница), фильтр по символам модели. Числа, латиница
 * без таблицы и сокращения не читаются — модель их не знает, обход через словарь замен.
 */
object PackText {
    private val ws = Regex("\\s+")

    fun prepare(text: String, pack: Pack, lang: String, rules: Rules = Rules()): String {
        var s = Normalizer.punctuation(text, rules).lowercase().replace('—', '–').replace('‑', '-')
        pack.translit[lang]?.let { table ->
            // как convert_to_orig: таблица применяется, только если в тексте есть буквы вне алфавита модели
            if (s.any { it.isLetter() && it !in pack.sym.alphabet }) s = translit(s, table)
        }
        val allowed = pack.sym.allowed
        val sb = StringBuilder(s.length)
        for (c in s) if (c in allowed) sb.append(c)
        return ws.replace(sb, " ").trim()
    }

    /** Самый длинный ключ первым: в узбекской таблице есть «sh», «o'», а не только буквы. */
    private fun translit(s: String, table: Map<String, String>): String {
        val keys = table.keys.sortedByDescending { it.length }
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val k = keys.firstOrNull { s.startsWith(it, i) }
            if (k != null) { sb.append(table.getValue(k)); i += k.length } else { sb.append(s[i]); i++ }
        }
        return sb.toString()
    }
}
