package ru.kost.ruvoice.text

import ru.kost.ruvoice.SileroData

object SentenceType {
    private val quoteOpen = setOf('"', '«', '“', '„')
    private val quoteClose = setOf('"', '»', '”', '’')
    private val wordRe = Regex("[а-яёa-z]+", RegexOption.IGNORE_CASE)
    private val sentSplit = Regex("(?<=[.!?])\\s+")
    private val wsRe = Regex("\\s+")
    // \b в java.util.regex зависит от JDK (unicode-aware ≤18, ASCII-only 19+) — не полагаемся
    // на него для кириллицы, используем явные lookaround по классу букв.
    private val altRe = Regex("(?<![а-яё])или(?![а-яё])")

    private fun stripOuterQuotes(s0: String): String {
        var s = s0.trim()
        while (s.isNotEmpty() && s[0] in quoteOpen) s = s.substring(1).trim()
        while (s.isNotEmpty() && s.last() in quoteClose) s = s.dropLast(1).trim()
        return s
    }

    private fun normalize(s: String): String =
        stripOuterQuotes(s).replace("+", "").replace('ё', 'е').replace('Ё', 'Е').replace(wsRe, " ").trim()

    private fun hasWhPrefix(text: String, d: SileroData): Boolean {
        val content = mutableListOf<String>()
        for (w in wordRe.findAll(text.lowercase()).map { it.value }.take(8)) {
            if (w in d.leadingFillers) continue
            content += w
            if (content.size >= 4) break
        }
        return content.any { it in d.whForms }
    }

    fun classifySentence(s0: String, d: SileroData): String {
        val s = s0.trim()
        if (s.isEmpty()) return "st"
        val clean = stripOuterQuotes(s)
        if (clean.isEmpty()) return "st"
        var tail = clean
        if (tail.endsWith("?!") || tail.endsWith("?..") || tail.endsWith("?...")) tail = tail.trimEnd('.', '!')
        if (tail.endsWith("?")) {
            val q = normalize(tail)
            if (d.tagRe.containsMatchIn(q)) return "tag_q"
            if (hasWhPrefix(q, d)) return "wh_q"
            if (altRe.containsMatchIn(q.lowercase())) return "alternative_q"
            return "general_q"
        }
        if (clean.endsWith("!")) return "exclam"
        return "st"
    }

    /**
     * Как `classify_sentence` из `multi_acc_v3_package.py` (не `classify_text`): классификация
     * всего текста целиком, без разбиения на предложения. `golden.json` (`type`) сформирован
     * вызовом `mod.classify_sentence(text)` в `tools/export_silero.py:make_golden`, поэтому
     * это единственно верное поведение — вызывающая сторона (Splitter/Pipeline) уже передаёт
     * сюда одно предложение за раз.
     */
    fun classify(text: String, d: SileroData, rules: Rules = Rules()): String =
        if (rules.on("intonation")) classifySentence(text, d) else "st"

    /** type_ids по символам входа модели (sos + prepared + eos), как build_type_ids_inference. */
    fun typeIds(prepared: String, typeStr: String, seqLen: Int, d: SileroData): LongArray {
        val types = typeStr.split("|")
        val sents = if (prepared.trim().isEmpty()) listOf("") else sentSplit.split(prepared.trim())
        val perChar = mutableListOf<Int>()
        for ((i, s) in sents.withIndex()) {
            val tid = d.type2id[types.getOrElse(i) { types.last() }] ?: 0
            repeat(s.length) { perChar += tid }
            if (i < sents.size - 1) perChar += tid
        }
        val out = LongArray(seqLen)
        if (perChar.isEmpty()) return out
        val default = (d.type2id[types[0]] ?: 0).toLong()
        out[0] = default
        var idx = 1
        for (tid in perChar) if (idx < seqLen) out[idx++] = tid.toLong()
        while (idx < seqLen) out[idx++] = default
        return out
    }
}
