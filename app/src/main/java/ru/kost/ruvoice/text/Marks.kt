package ru.kost.ruvoice.text

import ru.kost.ruvoice.Symbols

/**
 * Пометки слов, которые модель умеет поверх текста: логическое ударение `*слово*` (focus_mask),
 * темп/высота с этой точки `{prosody:R:P}` (R, P — проценты; `{prosody}` — сброс к 100:100)
 * и пауза внутри предложения `{pause:N}` (запятая длительностью N мс через symb_durs).
 * Маркеры снимаются до нормализации, а после ударений раскладываются по символам входа модели
 * сопоставлением слов (см. [Matcher]): слово, которое нормализация переписала (число,
 * сокращение), получает пометку слова, на котором остановилось сопоставление.
 */
object Marks {
    data class Mark(val rate: Float = 1f, val pitch: Float = 1f, val focus: Int = 0, val pauseMs: Int = 0)
    val NONE = Mark()

    val prosodyRe = Regex("\\{prosody(?::(\\d+):(\\d+))?\\}")
    val pauseRe = Regex("\\{pause:(\\d+)\\}")
    // *слово* или *несколько слов*, но не «***» и не одинокая звёздочка
    private val focusRe = Regex("\\*([^*\\s](?:[^*]*?[^*\\s])?)\\*")
    private val tokenRe = Regex("\\S+")
    private val nonKey = Regex("[^\\p{L}\\d]")
    const val PUNCT = ",.;:!?…–—-"

    /** Кадр модели — 12.5 мс; первые 10 кадров символа модель оставляет естественными, остальное тишина (fx_pauses). */
    fun frames(pauseMs: Int): Long = 10L + Math.round(pauseMs / 12.5)

    /** Маркеры → пробелы той же длины: слова остаются на своих смещениях в исходном тексте. */
    fun blank(text: String): String = Regex("\\{(?:pause:\\d+|prosody(?::\\d+:\\d+)?)\\}").replace(text) { " ".repeat(it.value.length) }

    /** Ключ слова для сопоставления: буквы и цифры, без «+», регистра и «ё». */
    fun key(token: String) = nonKey.replace(token, "").lowercase().replace('ё', 'е')

    class Parsed(val text: String, val words: List<Pair<String, Mark>>)

    // Короткое восклицание («Эй, вы!», «Бам!») модель читает ровно и коротко; фокус на последнем
    // слове это чинит (выбрано на слух, 16.09.2026). До двух слов, конец «!» (не «?!»).
    private val shortExclamRe = Regex("""^[^\p{L}]*\p{L}[\p{L}+-]*(?:[^\p{L}?]*\s\p{L}[\p{L}+-]*)?[^\p{L}?*]*!\W*$""")
    private val lastWordRe = Regex("""\p{L}[\p{L}+-]*(?=[^\p{L}]*$)""")

    /** «Эй, вы!» → «Эй, *вы*!»: логическое ударение на последнем слове короткого восклицания. */
    fun exclaim(text: String): String =
        if (shortExclamRe.matches(text)) lastWordRe.replace(text) { "*${it.value}*" } else text

    private val wordRe = Regex("""\p{L}[\p{L}+-]*""")
    /** «Вы барон Гордеев?», «— Барон Андрей Николаевич Гордеев?» → «… *Гордеев*?»: в общем вопросе модель ставит подъём на
     * первое-второе слово и роняет хвост; без глагола это звучит утверждением (по F0 эталонной модели, 20.09.2026), фокус на
     * последнем слове переносит подъём туда. С глаголом («Ты придёшь завтра?») подъём и так на нём — не трогаем. Глагол — как
     * в Stress.verbLike, но слово с заглавной не в начале — имя («Андрей»), не глагол. Тип предложения проверяет вызывающий. */
    fun question(text: String, morph: Morph? = Normalizer.morph): String {
        if ('*' in text) return text
        val words = wordRe.findAll(text).map { it.value }.toList()
        if (words.isEmpty() || words.withIndex().any { (i, w) -> (i == 0 || w[0].isLowerCase()) && verbLike(w.lowercase(), morph) }) return text
        return lastWordRe.replace(text) { "*${it.value}*" }
    }

    /** Снимает маркеры; words — ключ слова и его пометка, в порядке текста. [focus] — сила `*слова*`, 0 — не выделять. */
    fun parse(text: String, focus: Int = 3): Parsed {
        val focused = BooleanArray(text.length)
        val star = BooleanArray(text.length)
        for (m in focusRe.findAll(text)) {
            star[m.range.first] = true; star[m.range.last] = true
            for (i in m.range.first + 1 until m.range.last) focused[i] = true
        }
        val sb = StringBuilder(text.length)
        val marks = ArrayList<Mark>(text.length)
        val pauseAt = HashMap<Int, Int>() // индекс знака в sb → мс
        var rate = 1f; var pitch = 1f
        var i = 0
        while (i < text.length) {
            val pm = if (text[i] == '{') prosodyRe.matchAt(text, i) else null
            val bm = if (text[i] == '{' && pm == null) pauseRe.matchAt(text, i) else null
            if (pm != null) {
                rate = pm.groupValues[1].toFloatOrNull()?.div(100f) ?: 1f
                pitch = pm.groupValues[2].toFloatOrNull()?.div(100f) ?: 1f
                i = pm.range.last + 1
            } else if (bm != null) {
                // пауза живёт на знаке перед ней; нет знака — ставим запятую
                while (sb.isNotEmpty() && sb.last().isWhitespace()) { sb.setLength(sb.length - 1); marks.removeAt(marks.size - 1) }
                i = bm.range.last + 1
                if (sb.isNotEmpty()) {
                    if (sb.last() !in PUNCT) { sb.append(','); marks += Mark(rate, pitch) }
                    pauseAt[sb.length - 1] = (pauseAt[sb.length - 1] ?: 0) + (bm.groupValues[1].toIntOrNull() ?: 0).coerceIn(0, 10000)
                    if (i < text.length && !text[i].isWhitespace()) { sb.append(' '); marks += Mark(rate, pitch) }
                }
            } else {
                if (!star[i]) { sb.append(text[i]); marks += Mark(rate, pitch, if (focused[i]) focus else 0) }
                i++
            }
        }
        val words = tokenRe.findAll(sb).map { t ->
            val pause = pauseAt.entries.filter { it.key in t.range }.sumOf { it.value }
            key(t.value) to marks[t.range.first].copy(pauseMs = pause)
        }.toList()
        return Parsed(sb.toString(), words)
    }

    /** Слово строки ударений и диапазон его символов во входе модели (seq = sos + text + eos);
     * punctSeq — индекс в seq последнего знака препинания слова, -1 если слово им не кончается. */
    class Token(val key: String, val seqStart: Int, val seqEnd: Int, val punctSeq: Int)

    fun tokens(accented: String, sym: Symbols): List<Token> {
        // индекс символа accented → индекс в seq; символы не из алфавита модели sequence() выкидывает
        val seqIdx = IntArray(accented.length + 1)
        var idx = 1
        for ((k, c) in accented.withIndex()) { seqIdx[k] = idx; if (c in sym.symbolToId) idx++ }
        seqIdx[accented.length] = idx
        return tokenRe.findAll(accented).map {
            val last = it.range.last
            Token(key(it.value), seqIdx[it.range.first], seqIdx[last + 1], if (accented[last] in PUNCT && accented[last] in sym.symbolToId) seqIdx[last] else -1)
        }.toList()
    }

    /**
     * Последовательное сопоставление слов: [next] ищет ключ вперёд от текущей позиции в
     * окне [window] слов (нормализация могла раздуть слово в несколько), найденное сдвигает позицию.
     */
    class Matcher(private val keys: List<String>, private val window: Int = 8) {
        var pos = 0
        fun next(key: String): Int {
            if (key.isEmpty()) return -1
            for (j in pos until minOf(pos + window, keys.size)) if (keys[j] == key) { pos = j + 1; return j }
            return -1
        }
    }

    class Aligned(val rates: FloatArray, val pitches: FloatArray, val focus: LongArray, val symbDurs: Map<Long, Long>)

    /** Раскладывает пометки [words] по символам [accented]; пробелы наследуют пометку слова слева. */
    fun align(words: List<Pair<String, Mark>>, accented: String, seqLen: Int, sym: Symbols): Aligned {
        val rates = FloatArray(seqLen) { 1f }; val pitches = FloatArray(seqLen) { 1f }; val focus = LongArray(seqLen)
        val symbDurs = HashMap<Long, Long>()
        if (words.isEmpty()) return Aligned(rates, pitches, focus, symbDurs)
        val m = Matcher(words.map { it.first })
        val used = BooleanArray(words.size) // пауза слова берётся один раз, даже если слово раздулось в несколько
        var prevEnd = 1
        var pending = 0 // пауза, для которой ещё не нашлось знака — на ближайший знак дальше
        for (t in tokens(accented, sym)) {
            val j = m.next(t.key)
            val k = if (j >= 0) j else minOf(m.pos, words.size - 1)
            val mark = words[k].second
            // ponytail: пробел перед словом получает пометку предыдущего слова, модели всё равно
            for (i in prevEnd until minOf(t.seqEnd, seqLen)) { rates[i] = mark.rate; pitches[i] = mark.pitch; focus[i] = mark.focus.toLong() }
            prevEnd = t.seqEnd
            if (!used[k]) { used[k] = true; pending += mark.pauseMs }
            if (pending > 0 && t.punctSeq >= 0) { symbDurs[t.punctSeq.toLong()] = frames(pending); pending = 0 }
        }
        return Aligned(rates, pitches, focus, symbDurs)
    }
}
