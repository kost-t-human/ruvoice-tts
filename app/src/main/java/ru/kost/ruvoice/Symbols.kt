package ru.kost.ruvoice

import org.json.JSONObject

/** Символы входа модели: общее у русской `v5_5_ru` и языковых паков (Pack). */
class Symbols(val symbols: String, val symbolToId: Map<Char, Int>, val sos: Char, val eos: Char, val alphabet: Set<Char>) {
    /** Символы, допустимые во входе модели: без трёх служебных в начале строки — ровно как
     * фильтр `symbols[3:]` в prepare_text_input пакета, со всеми его причудами (у cis-пака
     * так выпадают `!` и `'`). */
    val allowed: String get() = symbols.substring(3)

    /** sos + текст + eos; символы вне таблицы пропускаются. */
    fun sequence(text: String): LongArray {
        val ids = ArrayList<Long>(text.length + 2)
        ids += symbolToId.getValue(sos).toLong()
        for (c in text) symbolToId[c]?.let { ids += it.toLong() }
        ids += symbolToId.getValue(eos).toLong()
        return ids.toLongArray()
    }

    companion object {
        /** Из объекта с ключами symbols, symbol_to_id, sos, eos, alphabet (silero_ru.json и pack.json). */
        fun fromJson(o: JSONObject): Symbols {
            val s2i = o.getJSONObject("symbol_to_id").let { j -> j.keys().asSequence().associate { it[0] to j.getInt(it) } }
            val sos = o.getString("sos")[0]; val eos = o.getString("eos")[0]
            require(sos in s2i && eos in s2i) { "sos/eos нет в symbol_to_id" }
            return Symbols(o.getString("symbols"), s2i, sos, eos, o.getString("alphabet").toSet())
        }
    }
}
