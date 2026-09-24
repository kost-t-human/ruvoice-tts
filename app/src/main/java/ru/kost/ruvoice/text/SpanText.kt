package ru.kost.ruvoice.text

/**
 * Подстановка текста из TtsSpan. TalkBack в режиме пунктуации «Все»/«Большинство» не вписывает
 * названия знаков в текст, а вешает на каждый знак TtsSpan.TYPE_TEXT с ARG_TEXT = «запятая»
 * (talkback: SpeechControllerImpl.makeSpeakablePunctuation), так же — свёрнутые повторы
 * («15 дефисов»). Разбор спанов — в SileroTtsService (android.text), здесь только замена.
 */
object SpanText {
    /** [spans] — (начало, конец, текст): куски [text] заменяются текстом в пробелах, чтобы «а,б»
     * не склеилось в «азапятаяб». Пересекающиеся и выходящие за текст спаны пропускаются. */
    fun substitute(text: String, spans: List<Triple<Int, Int, String>>): String {
        if (spans.isEmpty()) return text
        val sb = StringBuilder()
        var at = 0
        for ((start, end, say) in spans.sortedBy { it.first }) {
            if (start < at || end > text.length || start >= end) continue
            sb.append(text, at, start)
            if (sb.isNotEmpty() && !sb.last().isWhitespace()) sb.append(' ')
            sb.append(say.trim())
            if (end < text.length && !text[end].isWhitespace()) sb.append(' ')
            at = end
        }
        return sb.append(text, at, text.length).toString()
    }
}
