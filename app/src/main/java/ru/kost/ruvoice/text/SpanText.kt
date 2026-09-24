package ru.kost.ruvoice.text

/**
 * Подстановка текста из TtsSpan. TalkBack в режиме пунктуации «Все»/«Большинство» не вписывает
 * названия знаков в текст, а вешает на каждый знак TtsSpan.TYPE_TEXT с ARG_TEXT = «запятая»
 * (talkback: SpeechControllerImpl.makeSpeakablePunctuation), так же — свёрнутые повторы
 * («15 дефисов»). Разбор спанов — в SileroTtsService (android.text), здесь только замена.
 */
object SpanText {
    /** Текст после подстановки и карта: индекс в нём → индекс в исходном тексте (размер — длина + 1).
     * Подсветка слов (rangeStart) сообщается в смещениях исходного текста — того, что прислал клиент. */
    class Mapped(val text: String, private val orig: IntArray) {
        /** Смещение в исходном тексте для смещения [i] в подставленном. */
        fun orig(i: Int) = orig[i.coerceIn(0, orig.size - 1)]
    }

    /** [spans] — (начало, конец, текст): куски [text] заменяются текстом в пробелах, чтобы «а,б»
     * не склеилось в «азапятаяб». Пересекающиеся и выходящие за текст спаны пропускаются. */
    fun substitute(text: String, spans: List<Triple<Int, Int, String>>): String = mapped(text, spans).text

    fun mapped(text: String, spans: List<Triple<Int, Int, String>>): Mapped {
        val sb = StringBuilder()
        val orig = ArrayList<Int>(text.length + 1)
        var at = 0
        fun keep(from: Int, to: Int) { for (i in from until to) { sb.append(text[i]); orig += i } }
        fun put(s: String, o: Int) { for (c in s) { sb.append(c); orig += o } }
        for ((start, end, say) in spans.sortedBy { it.first }) {
            if (start < at || end > text.length || start >= end) continue
            keep(at, start)
            if (sb.isNotEmpty() && !sb.last().isWhitespace()) put(" ", start)
            // слово-название целиком указывает на заменённый знак
            put(say.trim(), start)
            if (end < text.length && !text[end].isWhitespace()) put(" ", end)
            at = end
        }
        keep(at, text.length)
        orig += text.length
        return Mapped(sb.toString(), orig.toIntArray())
    }
}
