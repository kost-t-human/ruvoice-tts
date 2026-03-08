package ru.kost.ruvoice

import org.json.JSONObject

class SileroData(json: String) {
    private val o = JSONObject(json)
    val symbols: String = o.getString("symbols")
    val symbolToId: Map<Char, Int> = o.getJSONObject("symbol_to_id").let { j -> j.keys().asSequence().associate { it[0] to j.getInt(it) } }
    val sos: Char = o.getString("sos")[0]
    val eos: Char = o.getString("eos")[0]
    val alphabet: Set<Char> = o.getString("alphabet").toSet()
    val speakers: Map<String, Int> = o.getJSONObject("speakers").let { j -> j.keys().asSequence().associateWith { j.getInt(it) } }
    val exceptions: Map<String, IntArray> = o.getJSONObject("exceptions").let { j ->
        j.keys().asSequence().associateWith { k -> val a = j.getJSONArray(k); intArrayOf(a.getInt(0), a.getInt(1)) }
    }
    val homodict: Map<String, List<String>> = o.getJSONObject("homodict").let { j ->
        j.keys().asSequence().associateWith { k -> val a = j.getJSONArray(k); List(a.length()) { a.getString(it) } }
    }
    private val bert = o.getJSONObject("bert")
    val bertVocab: Map<String, Int> = bert.getJSONObject("vocab").let { j -> j.keys().asSequence().associateWith { j.getInt(it) } }
    val bertCls = bert.getInt("cls"); val bertSep = bert.getInt("sep"); val bertPad = bert.getInt("pad")
    val bertUnk = bert.getInt("unk"); val bertHomoStart = bert.getInt("homo_start"); val bertHomoEnd = bert.getInt("homo_end")
    val type2id: Map<String, Int> = o.getJSONObject("type2id").let { j -> j.keys().asSequence().associateWith { j.getInt(it) } }
    val whForms: Set<String> = o.getJSONArray("wh_forms").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
    val leadingFillers: Set<String> = o.getJSONArray("leading_fillers").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
    val tagRe: Regex = o.getJSONArray("tag_patterns").let { a ->
        Regex((0 until a.length()).joinToString("|") { "(?:${a.getString(it)})" }, RegexOption.IGNORE_CASE)
    }
    /** Символы, допустимые во входе модели: без служебных `_~|`. */
    val allowed: String get() = symbols.substring(3)
}
