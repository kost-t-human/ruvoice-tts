package ru.kost.ruvoice

import org.json.JSONObject

class SileroData(json: String) {
    val symbols: String
    val symbolToId: Map<Char, Int>
    val sos: Char
    val eos: Char
    val alphabet: Set<Char>
    val speakers: Map<String, Int>
    val exceptions: Map<String, IntArray>
    val homodict: Map<String, List<String>>
    /** Фразы Silero Stress: слово → [(фраза, вариант с «+»)], порядок важен — длинные фразы раньше. */
    val phrases: Map<String, List<Pair<String, String>>>
    /** Грамматические омографы (AOT): форма → {g: род. ед., p: им./вин. мн., n: сущ., v: глагол} с «+». */
    val gram: Map<String, Map<String, String>>
    val bertVocab: Map<String, Int>
    val bertCls: Int
    val bertSep: Int
    val bertPad: Int
    val bertUnk: Int
    val bertHomoStart: Int
    val bertHomoEnd: Int
    val type2id: Map<String, Int>
    val whForms: Set<String>
    val leadingFillers: Set<String>
    val tagRe: Regex

    init {
        // Локальные, не сохраняются полями — разобранное дерево (2.5 МБ) не остаётся в памяти.
        val o = JSONObject(json)
        symbols = o.getString("symbols")
        symbolToId = o.getJSONObject("symbol_to_id").let { j -> j.keys().asSequence().associate { it[0] to j.getInt(it) } }
        sos = o.getString("sos")[0]
        eos = o.getString("eos")[0]
        alphabet = o.getString("alphabet").toSet()
        speakers = o.getJSONObject("speakers").let { j -> j.keys().asSequence().associateWith { j.getInt(it) } }
        exceptions = o.getJSONObject("exceptions").let { j ->
            j.keys().asSequence().associateWith { k -> val a = j.getJSONArray(k); intArrayOf(a.getInt(0), a.getInt(1)) }
        }
        homodict = o.getJSONObject("homodict").let { j ->
            j.keys().asSequence().associateWith { k -> val a = j.getJSONArray(k); List(a.length()) { a.getString(it) } }
        }
        phrases = o.getJSONObject("phrases").let { j ->
            j.keys().asSequence().associateWith { k -> val a = j.getJSONArray(k); List(a.length()) { a.getJSONArray(it).let { p -> p.getString(0) to p.getString(1) } } }
        }
        gram = o.getJSONObject("gram").let { j ->
            j.keys().asSequence().associateWith { k -> val e = j.getJSONObject(k); e.keys().asSequence().associateWith { e.getString(it) } }
        }
        val bert = o.getJSONObject("bert")
        bertVocab = bert.getJSONObject("vocab").let { j -> j.keys().asSequence().associateWith { j.getInt(it) } }
        bertCls = bert.getInt("cls"); bertSep = bert.getInt("sep"); bertPad = bert.getInt("pad")
        bertUnk = bert.getInt("unk"); bertHomoStart = bert.getInt("homo_start"); bertHomoEnd = bert.getInt("homo_end")
        type2id = o.getJSONObject("type2id").let { j -> j.keys().asSequence().associateWith { j.getInt(it) } }
        whForms = o.getJSONArray("wh_forms").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
        leadingFillers = o.getJSONArray("leading_fillers").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
        tagRe = o.getJSONArray("tag_patterns").let { a ->
            Regex((0 until a.length()).joinToString("|") { "(?:${a.getString(it)})" }, RegexOption.IGNORE_CASE)
        }
    }

    /** Символы, допустимые во входе модели: без служебных `_~|`. */
    val allowed: String get() = symbols.substring(3)

    fun sequence(accented: String): LongArray {
        val ids = ArrayList<Long>(accented.length + 2)
        ids += symbolToId.getValue(sos).toLong()
        for (c in accented) symbolToId[c]?.let { ids += it.toLong() }
        ids += symbolToId.getValue(eos).toLong()
        return ids.toLongArray()
    }
}
