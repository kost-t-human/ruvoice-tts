package ru.kost.ruvoice.text

import android.content.Context
import android.util.Log

/**
 * Эмодзи по имени (assets/emoji_ru.tsv — Unicode CLDR, tools/emoji_ru.py): «Привет 😀» → «Привет, широко
 * улыбается». Иначе фильтр символов модели выкидывает эмодзи молча, и в мессенджере теряется смысл. Имя
 * отделяется запятыми от соседних слов, повтор подряд («😂😂😂») читается один раз, U+FE0F не учитывается,
 * тон кожи в имя не входит. Незнакомое (новее таблицы) остаётся фильтру.
 */
class Emoji(lines: Sequence<String>) {
    private val names = HashMap<String, String>()
    private var maxLen = 1

    init {
        for (l in lines) {
            if (l.startsWith("#")) continue
            val tab = l.indexOf('\t')
            if (tab <= 0) continue
            names[l.substring(0, tab)] = l.substring(tab + 1)
            maxLen = maxOf(maxLen, tab)
        }
    }

    val size get() = names.size

    /** Самое длинное эмодзи с позиции [i]: (длина, имя) или null. */
    private fun match(s: String, i: Int): Pair<Int, String>? {
        val c = s[i]
        val maybe = c.code >= 0x2300 || ((c in '0'..'9' || c == '#' || c == '*') && s.getOrNull(i + 1) == KEYCAP)
        if (!maybe) return null
        for (len in minOf(maxLen, s.length - i) downTo 1) names[s.substring(i, i + len)]?.let { return len to it }
        return null
    }

    fun apply(text: String): String {
        val s = text.replace(VS16, "")
        if (s.none { it.code >= 0x2300 || it == KEYCAP }) return text
        val sb = StringBuilder(s.length + 16)
        var i = 0
        var last: String? = null
        while (i < s.length) {
            val hit = match(s, i)
            if (hit == null) {
                if (!s[i].isWhitespace()) last = null
                sb.append(s[i]); i++
                continue
            }
            i += hit.first
            if (hit.second == last) continue
            val end = sb.trimEnd().length
            if (end > 0 && (sb[end - 1].isLetterOrDigit() || sb[end - 1] in CLOSING)) { sb.setLength(end); sb.append(", ") }
            else if (sb.isNotEmpty() && !sb.last().isWhitespace()) sb.append(' ')
            sb.append(hit.second)
            last = hit.second
            var j = i
            while (j < s.length && s[j] == ' ') j++
            if (j < s.length && (s[j].isLetterOrDigit() || s[j] in OPENING || match(s, j) != null)) { sb.append(", "); i = j }
        }
        return sb.toString().replace(trailingComma, "")
    }

    companion object {
        private const val VS16 = "\uFE0F"
        private const val KEYCAP = '\u20E3'
        private const val CLOSING = "»)\"”"
        private const val OPENING = "«(\"“"
        private val trailingComma = Regex(""",\s*$""")

        /** Общий на процесс, ставит SileroModels.data(); null — правило ничего не делает (JVM-тесты без ассета). */
        @Volatile var shared: Emoji? = null

        fun open(context: Context): Emoji {
            val t = System.nanoTime()
            return context.assets.open("emoji_ru.tsv").bufferedReader().useLines { Emoji(it) }
                .also { Log.i("RuVoice", "emoji_ru.tsv: ${it.size} эмодзи, ${(System.nanoTime() - t) / 1_000_000} мс") }
        }
    }
}
