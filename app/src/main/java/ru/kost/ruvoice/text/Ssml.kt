package ru.kost.ruvoice.text

data class Segment(val text: String, val rate: Float = 1f, val pitch: Float = 1f, val breakMs: Int = 0, val paragraph: Boolean = false)

object Ssml {
    private val strength = mapOf("x-weak" to 25, "weak" to 75, "medium" to 150, "strong" to 300, "x-strong" to 1000)
    private val rateWords = mapOf("x-slow" to 0.5f, "slow" to 0.8f, "medium" to 1f, "fast" to 1.2f, "x-fast" to 1.5f)
    private val pitchWords = mapOf("x-low" to 0.6f, "low" to 0.8f, "medium" to 1f, "high" to 1.2f, "x-high" to 1.4f)
    private val tagRe = Regex("<(/?)([a-zA-Z]+)([^>]*?)(/?)>")
    private val attrRe = Regex("([a-zA-Z]+)\\s*=\\s*\"([^\"]*)\"")
    private val entityRe = Regex("&(#x[0-9a-fA-F]+|#[0-9]+|amp|lt|gt|quot|apos);")

    fun isSsml(text: CharSequence) = text.trimStart().startsWith("<speak")

    private fun attrs(s: String) = attrRe.findAll(s).associate { it.groupValues[1] to it.groupValues[2] }

    private fun decode(s: String): String = entityRe.replace(s) { m ->
        when (val e = m.groupValues[1]) {
            "amp" -> "&"
            "lt" -> "<"
            "gt" -> ">"
            "quot" -> "\""
            "apos" -> "'"
            else -> when {
                e.startsWith("#x") -> e.substring(2).toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: m.value
                e.startsWith("#") -> e.substring(1).toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value
                else -> m.value
            }
        }
    }

    fun rateValue(v: String): Float = rateWords[v] ?: if (v.endsWith("%")) v.dropLast(1).toFloat() / 100f else v.toFloatOrNull() ?: 1f
    fun pitchValue(v: String): Float = pitchWords[v] ?: when {
        v.endsWith("%") && (v.startsWith("+") || v.startsWith("-")) -> 1f + v.dropLast(1).toFloat() / 100f
        v.endsWith("%") -> v.dropLast(1).toFloat() / 100f
        else -> v.toFloatOrNull() ?: 1f
    }

    fun parse(text: String): List<Segment> {
        val out = ArrayList<Segment>()
        val buf = StringBuilder()
        var rate = 1f; var pitch = 1f
        val stack = ArrayList<Pair<Float, Float>>()
        fun flush(breakMs: Int = 0, paragraph: Boolean = false) {
            if (buf.isNotEmpty() || breakMs > 0 || paragraph) {
                if (buf.isEmpty() && out.isNotEmpty()) {
                    val last = out.removeAt(out.size - 1)
                    out += last.copy(breakMs = last.breakMs + breakMs, paragraph = last.paragraph || paragraph)
                } else out += Segment(buf.toString(), rate, pitch, breakMs, paragraph)
                buf.setLength(0)
            }
        }
        var pos = 0
        for (m in tagRe.findAll(text)) {
            buf.append(decode(text.substring(pos, m.range.first))); pos = m.range.last + 1
            val closing = m.groupValues[1] == "/"; val name = m.groupValues[2].lowercase(); val a = attrs(m.groupValues[3])
            when (name) {
                "break" -> flush(breakMs = a["time"]?.let { t -> if (t.endsWith("ms")) t.dropLast(2).trim().toInt() else if (t.endsWith("s")) (t.dropLast(1).trim().toFloat() * 1000).toInt() else t.toInt() } ?: strength[a["strength"] ?: "medium"] ?: 150)
                "prosody" -> if (closing) { if (stack.isNotEmpty()) { flush(); val (r, p) = stack.removeAt(stack.size - 1); rate = r; pitch = p } }
                            else { flush(); stack += rate to pitch; a["rate"]?.let { rate = rateValue(it) }; a["pitch"]?.let { pitch = pitchValue(it) } }
                "s" -> if (closing) flush()
                "p" -> if (closing) flush(paragraph = true)
                else -> {}
            }
        }
        buf.append(decode(text.substring(pos, text.length)))
        flush()
        return out.filter { it.text.isNotBlank() || it.breakMs > 0 }
    }
}
