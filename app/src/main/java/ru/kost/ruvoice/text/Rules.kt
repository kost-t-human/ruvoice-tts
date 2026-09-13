package ru.kost.ruvoice.text

/**
 * Переключатели правил обработки текста (вкладка «Правила»). Всё включено по умолчанию,
 * off — ключи выключенных правил; maxLen — предел длины куска для синтеза (Splitter.sentences).
 * Ключи и их порядок в UI — KEYS; подписи к ним лежат в strings.xml как rule_<key> / rule_<key>_hint.
 */
class Rules(val off: Set<String> = emptySet(), val maxLen: Int = MAX_LEN_DEFAULT, val focus: Int = FOCUS_DEFAULT) {
    fun on(key: String) = key !in off
    /** Сила логического ударения `*слово*` для focus_mask модели; 0 — правило выключено. */
    val focusLevel get() = if (on("focus")) focus.coerceIn(FOCUS_MIN, FOCUS_MAX) else 0

    companion object {
        const val MAX_LEN_DEFAULT = 400
        const val MAX_LEN_MIN = 100
        const val MAX_LEN_MAX = 900
        // focus_embedding модели — 4 строки (0..3), как intensity в apply_tts
        const val FOCUS_DEFAULT = 3
        const val FOCUS_MIN = 1
        const val FOCUS_MAX = 3

        /** Ключ → секция UI. Порядок списка = порядок на экране. */
        val KEYS = listOf(
            "numbers", "cases", "roman", "roman_name", "dates", "day_month", "years", "times", "units",
            "degrees", "currency", "fractions", "spoons", "gen_suffix", "sections", "thousands", "footnotes",
            "abbrev", "spell_cyr", "spell_lat", "latin", "homoglyphs",
            "dehyphen", "soft_break", "punct", "ssml",
            "homo", "accentor", "intonation", "focus",
            "pause_semicolon",
        )
        /** Индексы KEYS, с которых начинается новая секция, и её заголовок (см. strings.xml). */
        val SECTIONS = mapOf(0 to "numbers", 17 to "abbrev", 22 to "split", 26 to "stress", 30 to "audio")
    }
}
