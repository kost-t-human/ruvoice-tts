package ru.kost.ruvoice.text

/**
 * Что сказать вместо куска текста с TtsSpan. Приложение само размечает, что это: телефон
 * (PhoneNumberUtils.createTtsSpan — звонилка, контакты), время и дата (часы, календарь), деньги,
 * величина, «по цифрам», «как написано». Нормализатору тогда не надо угадывать по виду.
 *
 * [type] и ключи [a] — короткие имена без префикса android.type./android.arg. (сервис переводит
 * Bundle спана по константам TtsSpan): "number", "integer_part", "unit", "hours" и т. д.; падеж, род и
 * число — "case" = nominative|genitive|dative|accusative|instrumental|locative, "gender" =
 * male|female|neutral, "multiplicity" = single|plural, "animacy" = animate. Возвращает текст для
 * нормализатора или уже слова; null — спан не разобран, остаётся исходный текст.
 */
object SpanSay {
    fun say(type: String, a: Map<String, Any?>): String? {
        fun s(k: String) = a[k]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        fun i(k: String) = s(k)?.toIntOrNull()
        return when (type) {
            "text" -> s("text")
            "cardinal" -> s("number")
            "ordinal" -> s("number")?.let { ordinal(it, a) }
            "decimal" -> decimal(s("integer_part"), s("fractional_part"))
            "fraction" -> fraction(s("integer_part"), s("numerator"), s("denominator"))
            "measure" -> measure(a)
            "time" -> i("hours")?.let { time(it, i("minutes")) }
            "date" -> date(i("weekday"), i("day"), i("month"), i("year"))
            "telephone" -> s("number_parts")?.let { telephone(s("country_code"), it, s("extension")) }
            "electronic" -> electronic(a)
            "money" -> money(a)
            "digits" -> s("digits")?.let(::spell)
            "verbatim" -> s("verbatim")?.let(::spell)
            else -> null
        }?.takeIf { it.isNotBlank() }
    }

    private val digitWords = arrayOf("ноль", "один", "два", "три", "четыре", "пять", "шесть", "семь", "восемь", "девять")

    /** По знаку: цифры словами, буквы по имени, прочее — по имени знака; пробел — пауза. */
    fun spell(v: String): String = v.mapNotNull { c ->
        when {
            c.isWhitespace() -> ","
            c in '0'..'9' -> digitWords[c - '0']
            c.isLetter() -> Abbrev.letterName(c) ?: c.toString()
            else -> SymbolNames.of(c)
        }
    }.joinToString(" ").replace(" ,", ",").trim(',', ' ')

    private fun ordinal(n: String, a: Map<String, Any?>): String {
        val v = n.toLongOrNull()?.takeIf { it > 0 } ?: return n
        val plural = a["multiplicity"] == "plural"
        val animate = a["animacy"] == "animate"
        val g = a["gender"]
        val suffix = if (plural) when (a["case"]) {
            "genitive", "locative" -> "х"; "dative" -> "ым"; "instrumental" -> "ыми"
            "accusative" -> if (animate) "х" else "ые"; else -> "ые"
        } else if (g == "female") when (a["case"]) {
            "accusative" -> "ю"; null, "nominative" -> "я"; else -> "ой"
        } else when (a["case"]) {
            "genitive" -> "го"; "dative" -> "му"; "instrumental", "locative" -> "м"
            "accusative" -> if (g == "neutral") "е" else if (animate) "го" else "й"
            else -> if (g == "neutral") "е" else "й"
        }
        return Normalizer.ordinal(v, suffix)
    }

    private fun decimal(int: String?, frac: String?): String? = when {
        int == null && frac == null -> null
        frac == null -> int
        else -> (int ?: "0") + "," + frac
    }

    private fun fraction(int: String?, num: String?, den: String?): String? {
        val f = if (num != null && den != null) "$num/$den" else null
        val n = int?.toLongOrNull()
        return when {
            f == null -> int
            n == null || n == 0L -> f
            // «1 1/2» нормализатор читает «одна одна вторая»
            else -> Normalizer.cardinal(n, feminine = true) + " " + Normalizer.plural(n, Triple("целая", "целых", "целых")) + " и " + f
        }
    }

    /** Число из аргументов величины или денег: целое, десятичное или дробь. */
    private fun amount(a: Map<String, Any?>): String? {
        fun s(k: String) = a[k]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        return s("number") ?: decimal(s("integer_part"), s("fractional_part"))?.takeIf { s("numerator") == null }
            ?: fraction(s("integer_part"), s("numerator"), s("denominator"))
    }

    // Единицы — как их называет TtsSpan.MeasureBuilder.setUnit (английское имя, «kilometer per hour»)
    // или уже сокращением; ответ — сокращение, которое склоняет нормализатор (units()).
    private val unitAbbr = mapOf(
        "meter" to "м", "kilometer" to "км", "centimeter" to "см", "millimeter" to "мм",
        "gram" to "г", "kilogram" to "кг", "milligram" to "мг", "ton" to "т", "tonne" to "т",
        "liter" to "л", "litre" to "л", "milliliter" to "мл",
        "second" to "сек", "minute" to "мин", "hour" to "ч",
        "percent" to "%", "celsius" to "°C", "fahrenheit" to "°F", "degree celsius" to "°C", "degree fahrenheit" to "°F",
        "kilobyte" to "КБ", "megabyte" to "МБ", "gigabyte" to "ГБ", "terabyte" to "ТБ",
        "megabit per second" to "Мбит/с", "watt" to "Вт", "kilowatt" to "кВт", "volt" to "В", "ampere" to "А",
        "hertz" to "Гц", "kilohertz" to "кГц", "megahertz" to "МГц", "gigahertz" to "ГГц",
        "calorie" to "кал", "kilocalorie" to "ккал", "square meter" to "м²", "square kilometer" to "км²",
        "kilometer per hour" to "км/ч", "meter per second" to "м/с",
    )
    // Единицы без сокращения в нормализаторе — словами: (1, 2–4, 5+).
    private val unitWords = mapOf(
        "inch" to Triple("дюйм", "дюйма", "дюймов"), "foot" to Triple("фут", "фута", "футов"),
        "mile" to Triple("миля", "мили", "миль"), "yard" to Triple("ярд", "ярда", "ярдов"),
        "pound" to Triple("фунт", "фунта", "фунтов"), "ounce" to Triple("унция", "унции", "унций"),
        "day" to Triple("день", "дня", "дней"), "week" to Triple("неделя", "недели", "недель"),
        "month" to Triple("месяц", "месяца", "месяцев"), "year" to Triple("год", "года", "лет"),
        "millisecond" to Triple("миллисекунда", "миллисекунды", "миллисекунд"),
        "byte" to Triple("байт", "байта", "байт"), "bit" to Triple("бит", "бита", "бит"),
        "step" to Triple("шаг", "шага", "шагов"),
    )
    private val femaleUnits = setOf("mile", "ounce", "week", "millisecond")

    private fun unitKey(u: String) = u.lowercase().replace('-', ' ').replace('_', ' ').trim().removeSuffix("s")
        .let { if (it.endsWith("inche")) "inch" else if (it == "feet") "foot" else it }

    private fun measure(a: Map<String, Any?>): String? {
        val num = amount(a) ?: return null
        val unit = a["unit"]?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: return num
        val k = unitKey(unit)
        unitAbbr[k]?.let { return "$num $it" }
        val w = unitWords[k] ?: return "$num $unit"
        val n = num.toLongOrNull() ?: return "$num ${w.second}" // дробное — «2,5 мили»: род. п. ед. ч.
        return Normalizer.cardinal(n, feminine = k in femaleUnits) + " " + Normalizer.plural(n, w)
    }

    private fun time(h: Int, m: Int?): String {
        val hh = Normalizer.cardinal(h.toLong()) + " " + Normalizer.plural(h.toLong(), Triple("час", "часа", "часов"))
        if (m == null || m == 0) return hh
        return hh + " " + Normalizer.cardinal(m.toLong(), feminine = true) + " " + Normalizer.plural(m.toLong(), Triple("минута", "минуты", "минут"))
    }

    private val weekdays = arrayOf("воскресенье", "понедельник", "вторник", "среда", "четверг", "пятница", "суббота")
    private val monthsGen = arrayOf("января", "февраля", "марта", "апреля", "мая", "июня", "июля", "августа", "сентября", "октября", "ноября", "декабря")
    private val monthsNom = arrayOf("январь", "февраль", "март", "апрель", "май", "июнь", "июль", "август", "сентябрь", "октябрь", "ноябрь", "декабрь")

    /** [weekday] 1 — воскресенье (TtsSpan.WEEKDAY_SUNDAY), [month] 0 — январь (TtsSpan.MONTH_JANUARY). */
    private fun date(weekday: Int?, day: Int?, month: Int?, year: Int?): String? {
        val parts = ArrayList<String>()
        weekday?.takeIf { it in 1..7 }?.let { parts += weekdays[it - 1] }
        val mo = month?.takeIf { it in 0..11 }
        val dm = when {
            day != null && day in 1..31 && mo != null -> Normalizer.ordinal(day.toLong(), "е") + " " + monthsGen[mo]
            day != null && day in 1..31 -> Normalizer.ordinal(day.toLong(), "е") + " число"
            mo != null -> monthsNom[mo]
            else -> null
        }
        val y = year?.takeIf { it > 0 }?.let { Normalizer.ordinal(it.toLong(), "го") + " года" }
        val rest = listOfNotNull(dm, y).joinToString(" ")
        if (rest.isNotEmpty()) parts += rest
        return parts.joinToString(", ").takeIf { it.isNotEmpty() }
    }

    /** Группы номера через запятую, каждая числом с ведущими нулями — как правило «Телефоны». */
    private fun telephone(cc: String?, parts: String, ext: String?): String? {
        val groups = parts.split(Regex("""\D+""")).filter { it.isNotEmpty() }.flatMap(::cut)
        if (groups.isEmpty()) return null
        val sb = StringBuilder()
        cc?.filter { it.isDigit() }?.takeIf { it.isNotEmpty() }?.let { sb.append("плюс ").append(Normalizer.phoneGroup(it)).append(", ") }
        sb.append(groups.joinToString(", ", transform = Normalizer::phoneGroup))
        ext?.filter { it.isDigit() }?.takeIf { it.isNotEmpty() }?.let { sb.append(", добавочный ").append(Normalizer.phoneGroup(it)) }
        return sb.toString()
    }

    /** Длинная группа — привычными кусками: 3-2-2 с конца, остаток спереди по три. */
    private fun cut(g: String): List<String> {
        if (g.length <= 4) return listOf(g)
        val tail = if (g.length >= 7) listOf(g.substring(g.length - 4, g.length - 2), g.substring(g.length - 2)) else emptyList()
        val head = g.substring(0, g.length - tail.size * 2)
        val r = head.length % 3
        return listOfNotNull(head.take(r).takeIf { r > 0 }) + head.drop(r).chunked(3) + tail
    }

    private fun electronic(a: Map<String, Any?>): String? {
        fun s(k: String) = a[k]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        val domain = s("domain") ?: return null
        val user = s("username")
        if (s("protocol") == null && s("path") == null && s("port") == null && user != null) return "$user@$domain"
        val sb = StringBuilder()
        s("protocol")?.let { sb.append(it).append("://") }
        user?.let { sb.append(it); s("password")?.let { p -> sb.append(':').append(p) }; sb.append('@') }
        sb.append(domain)
        s("port")?.let { sb.append(':').append(it) }
        s("path")?.let { sb.append('/').append(it.removePrefix("/")) }
        s("query_string")?.let { sb.append('?').append(it) }
        s("fragment_id")?.let { sb.append('#').append(it) }
        return sb.toString()
    }

    private class Currency(val major: Triple<String, String, String>, val female: Boolean,
                           val minor: Triple<String, String, String>?, val minorFemale: Boolean = false)
    private val currencies = mapOf(
        "RUB" to Currency(Triple("рубль", "рубля", "рублей"), false, Triple("копейка", "копейки", "копеек"), true),
        "BYN" to Currency(Triple("белорусский рубль", "белорусских рубля", "белорусских рублей"), false, Triple("копейка", "копейки", "копеек"), true),
        "UAH" to Currency(Triple("гривна", "гривны", "гривен"), true, Triple("копейка", "копейки", "копеек"), true),
        "USD" to Currency(Triple("доллар", "доллара", "долларов"), false, Triple("цент", "цента", "центов")),
        "EUR" to Currency(Triple("евро", "евро", "евро"), false, Triple("цент", "цента", "центов")),
        "GBP" to Currency(Triple("фунт", "фунта", "фунтов"), false, Triple("пенс", "пенса", "пенсов")),
        "CNY" to Currency(Triple("юань", "юаня", "юаней"), false, Triple("фэнь", "фэня", "фэней")),
        "JPY" to Currency(Triple("иена", "иены", "иен"), true, null),
        "KZT" to Currency(Triple("тенге", "тенге", "тенге"), false, Triple("тиын", "тиына", "тиынов")),
        "TRY" to Currency(Triple("лира", "лиры", "лир"), true, Triple("куруш", "куруша", "курушей")),
        "CHF" to Currency(Triple("франк", "франка", "франков"), false, Triple("сантим", "сантима", "сантимов")),
    )

    private fun money(a: Map<String, Any?>): String? {
        fun s(k: String) = a[k]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        val code = s("currency")?.uppercase()
        val c = currencies[code]
        val int = s("integer_part") ?: s("number")
        val frac = s("fractional_part")
        if (c == null) return listOfNotNull(decimal(int, frac) ?: return null, code).joinToString(" ")
        val n = int?.toLongOrNull() ?: return listOfNotNull(decimal(int, frac), c.major.second).joinToString(" ")
        val sb = StringBuilder(Normalizer.cardinal(n, c.female) + " " + Normalizer.plural(n, c.major))
        // «1,5» у денег — «1 рубль 50 копеек»: дробная часть — сотые
        val m = frac?.padEnd(2, '0')?.take(2)?.toLongOrNull()
        if (m != null && m > 0) {
            if (c.minor != null) sb.append(' ').append(Normalizer.cardinal(m, c.minorFemale)).append(' ').append(Normalizer.plural(m, c.minor))
            else return decimal(int, frac) + " " + c.major.second
        }
        return sb.toString()
    }
}
