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

    /** Те же правила с [key] во включённом или выключенном состоянии. */
    fun with(key: String, on: Boolean): Rules =
        if (on(key) == on) this else Rules(if (key in off) off - key else off + key, maxLen, focus)

    /** Правила для запроса экранного чтеца (TalkBack и др.) — секция «Чтение с экрана» поверх общих:
     * служебные символы словами, без голоса прямой речи и без тишины перед фразой. Паузы между
     * предложениями (sr_pauses_off), выгрузку модели (sr_keep_loaded), темп и высоту чтеца решает сервис. */
    fun screenReader(): Rules {
        var r = this
        if (on("sr_symbols")) r = r.with("symbol_names", true)
        if (on("sr_quote_off")) r = r.with("speech", false)
        if (on("sr_lead_in_off")) r = r.with("lead_in", false)
        return r
    }

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
        val DEFAULT_OFF = setOf("symbol_names", "fast_start", "drop_links", "drop_emails", "en_proxy_books", "en_proxy_sr")

        /** Порядок списка = порядок на экране. Вверху «Чтение с экрана» (только запросы экранного чтеца),
         * за ней «Разное» — для настроек без своего раздела. */
        val KEYS = listOf(
            "sr_symbols", "sr_quote_off", "sr_lead_in_off", "sr_pauses_off", "sr_keep_loaded",
            "symbol_names", "emoji", "letter_name", "lead_in", "fast_start", "drop_links", "drop_emails", "read_links",
            "phones", "codes", "numbers", "arith", "cases", "roman", "roman_name", "dates", "day_month", "years", "times", "units",
            "degrees", "currency", "fractions", "spoons", "gen_suffix", "sections", "thousands", "footnotes",
            "abbrev", "spell_cyr", "spell_lat", "letter_digit", "latin", "homoglyphs",
            "en_proxy_books", "en_proxy_sr",
            "dehyphen", "soft_break", "punct", "ssml",
            "gram", "first_pl", "homo", "accentor", "yo", "hard_e", "prefix_space", "intonation", "focus", "exclaim", "question",
            "pause_semicolon", "pause_parens", "fast_cores",
        )
        /** Ключ, с которого начинается новая секция → её заголовок (rules_section_<имя> в strings.xml). */
        val SECTIONS = mapOf("sr_symbols" to "talkback", "symbol_names" to "misc", "phones" to "numbers", "abbrev" to "abbrev", "en_proxy_books" to "english", "dehyphen" to "split",
            "gram" to "stress", "pause_semicolon" to "audio")
    }
}
