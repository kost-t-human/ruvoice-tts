package ru.kost.ruvoice.text

/**
 * Переключатели правил обработки текста (вкладка «Правила»). Всё включено по умолчанию, кроме
 * DEFAULT_OFF; off — ключи, переключённые относительно умолчания (для DEFAULT_OFF — включённые);
 * maxLen — предел длины куска для синтеза (Splitter.sentences).
 * Ключи и их порядок в UI — KEYS; подписи к ним лежат в strings.xml как rule_<key> / rule_<key>_hint.
 */
class Rules(val off: Set<String> = emptySet(), val maxLen: Int = MAX_LEN_DEFAULT, val focus: Int = FOCUS_DEFAULT) {
    fun on(key: String) = (key in off) == (key in DEFAULT_OFF)
    /** Сила логического ударения `*слово*` для focus_mask модели; 0 — правило выключено. */
    val focusLevel get() = if (on("focus")) focus.coerceIn(FOCUS_MIN, FOCUS_MAX) else 0

    companion object {
        const val MAX_LEN_DEFAULT = 400
        const val MAX_LEN_MIN = 100
        const val MAX_LEN_MAX = 900
        /** Длина первого куска запроса при fast_start, ~5 с звука. */
        const val FAST_START_LEN = 100
        // focus_embedding модели — 4 строки (0..3), как intensity в apply_tts
        const val FOCUS_DEFAULT = 3
        const val FOCUS_MIN = 1
        const val FOCUS_MAX = 3

        /** Правила, выключенные по умолчанию. */
        val DEFAULT_OFF = setOf("fast_start", "drop_links", "drop_emails")

        /** Порядок списка = порядок на экране. Секция «Разное» вверху — для настроек без своего раздела. */
        val KEYS = listOf(
            "letter_name", "lead_in", "fast_start", "drop_links", "drop_emails", "read_links",
            "phones", "numbers", "arith", "cases", "roman", "roman_name", "dates", "day_month", "years", "times", "units",
            "degrees", "currency", "fractions", "spoons", "gen_suffix", "sections", "thousands", "footnotes",
            "abbrev", "spell_cyr", "spell_lat", "letter_digit", "latin", "homoglyphs",
            "dehyphen", "soft_break", "punct", "ssml",
            "gram", "first_pl", "homo", "accentor", "yo", "hard_e", "prefix_space", "intonation", "focus", "exclaim", "question",
            "pause_semicolon", "pause_parens", "fast_cores",
        )
        /** Ключ, с которого начинается новая секция → её заголовок (rules_section_<имя> в strings.xml). */
        val SECTIONS = mapOf("letter_name" to "misc", "phones" to "numbers", "abbrev" to "abbrev", "dehyphen" to "split",
            "gram" to "stress", "pause_semicolon" to "audio")
    }
}
