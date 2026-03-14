package ru.kost.ruvoice.text

import ru.kost.ruvoice.SileroData

class BertTokenizer(private val d: SileroData) {
    private val neverSplit = setOf("[HOMO]", "[/HOMO]")
    private val maxChars = 100

    fun encode(text: String): LongArray {
        val tokens = ArrayList<String>()
        tokens += "[CLS]"
        for (t in basic(text)) tokens += wordpiece(t)
        tokens += "[SEP]"
        return LongArray(tokens.size) { (d.bertVocab[tokens[it]] ?: d.bertUnk).toLong() }
    }

    private fun isControl(c: Char) = c != '\t' && c != '\n' && c != '\r' && Character.getType(c).let {
        it == Character.CONTROL.toInt() || it == Character.FORMAT.toInt() || it == Character.PRIVATE_USE.toInt() ||
            it == Character.SURROGATE.toInt() || it == Character.UNASSIGNED.toInt()
    }
    private fun isWhitespace(c: Char) = c == ' ' || c == '\t' || c == '\n' || c == '\r' || Character.getType(c) == Character.SPACE_SEPARATOR.toInt()
    private fun isPunct(c: Char): Boolean {
        val cp = c.code
        if (cp in 33..47 || cp in 58..64 || cp in 91..96 || cp in 123..126) return true
        val t = Character.getType(c)
        return t == Character.CONNECTOR_PUNCTUATION.toInt() || t == Character.DASH_PUNCTUATION.toInt() ||
            t == Character.START_PUNCTUATION.toInt() || t == Character.END_PUNCTUATION.toInt() ||
            t == Character.INITIAL_QUOTE_PUNCTUATION.toInt() || t == Character.FINAL_QUOTE_PUNCTUATION.toInt() ||
            t == Character.OTHER_PUNCTUATION.toInt()
    }

    private fun basic(text: String): List<String> {
        val cleaned = StringBuilder()
        for (c in text) {
            if (c.code == 0 || c.code == 0xFFFD || isControl(c)) continue
            cleaned.append(if (isWhitespace(c)) ' ' else c)
        }
        val out = ArrayList<String>()
        for (tok in cleaned.toString().trim().split(Regex("\\s+")).filter { it.isNotEmpty() }) {
            if (tok in neverSplit) { out += tok; continue }
            var cur = StringBuilder()
            for (c in tok) {
                if (isPunct(c)) {
                    if (cur.isNotEmpty()) { out += cur.toString(); cur = StringBuilder() }
                    out += c.toString()
                } else cur.append(c)
            }
            if (cur.isNotEmpty()) out += cur.toString()
        }
        return out
    }

    private fun wordpiece(token: String): List<String> {
        if (token.length > maxChars) return listOf("[UNK]")
        val sub = ArrayList<String>()
        var start = 0
        while (start < token.length) {
            var end = token.length
            var found: String? = null
            while (start < end) {
                val s = (if (start > 0) "##" else "") + token.substring(start, end)
                if (s in d.bertVocab) { found = s; break }
                end--
            }
            if (found == null) return listOf("[UNK]")
            sub += found
            start = end
        }
        return sub
    }
}
