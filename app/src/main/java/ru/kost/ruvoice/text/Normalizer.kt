package ru.kost.ruvoice.text

object Normalizer {
    private val ones = arrayOf("", "один", "два", "три", "четыре", "пять", "шесть", "семь", "восемь", "девять",
        "десять", "одиннадцать", "двенадцать", "тринадцать", "четырнадцать", "пятнадцать", "шестнадцать",
        "семнадцать", "восемнадцать", "девятнадцать")
    private val onesF = arrayOf("", "одна", "две")
    private val tens = arrayOf("", "", "двадцать", "тридцать", "сорок", "пятьдесят", "шестьдесят", "семьдесят",
        "восемьдесят", "девяносто")
    private val hundreds = arrayOf("", "сто", "двести", "триста", "четыреста", "пятьсот", "шестьсот", "семьсот",
        "восемьсот", "девятьсот")
    // (ед., 2-4, 5+), род
    private val scales = listOf(
        Triple("миллиард", "миллиарда", "миллиардов") to false,
        Triple("миллион", "миллиона", "миллионов") to false,
        Triple("тысяча", "тысячи", "тысяч") to true,
    )

    fun plural(n: Long, forms: Triple<String, String, String>): String {
        val r10 = n % 10; val r100 = n % 100
        return when {
            r100 in 11..19 -> forms.third
            r10 == 1L -> forms.first
            r10 in 2..4 -> forms.second
            else -> forms.third
        }
    }

    private fun below1000(n: Int, feminine: Boolean): String {
        val parts = mutableListOf<String>()
        if (n / 100 > 0) parts += hundreds[n / 100]
        val rest = n % 100
        if (rest in 1..19) parts += if (feminine && rest <= 2) onesF[rest] else ones[rest]
        else if (rest >= 20) {
            parts += tens[rest / 10]
            val u = rest % 10
            if (u > 0) parts += if (feminine && u <= 2) onesF[u] else ones[u]
        }
        return parts.joinToString(" ")
    }

    fun cardinal(n: Long, feminine: Boolean = false): String {
        if (n == 0L) return "ноль"
        if (n < 0) return "минус " + cardinal(-n, feminine)
        val parts = mutableListOf<String>()
        var rest = n
        var scale = 1_000_000_000L
        for ((forms, fem) in scales) {
            val q = (rest / scale).toInt()
            if (q > 0) { parts += below1000(q, fem); parts += plural(q.toLong(), forms) }
            rest %= scale
            scale /= 1000
        }
        if (rest > 0) parts += below1000(rest.toInt(), feminine)
        return parts.joinToString(" ")
    }

    private val ordinalStems = mapOf(1 to "перв", 2 to "втор", 3 to "трет", 4 to "четвёрт", 5 to "пят", 6 to "шест",
        7 to "седьм", 8 to "восьм", 9 to "девят", 10 to "десят", 11 to "одиннадцат", 12 to "двенадцат",
        13 to "тринадцат", 14 to "четырнадцат", 15 to "пятнадцат", 16 to "шестнадцат", 17 to "семнадцат",
        18 to "восемнадцат", 19 to "девятнадцат", 20 to "двадцат", 30 to "тридцат", 40 to "сороков",
        50 to "пятидесят", 60 to "шестидесят", 70 to "семидесят", 80 to "восьмидесят", 90 to "девяност",
        100 to "сот")
    private val stressedEnding = setOf("втор", "шест", "седьм", "восьм", "сороков")
    // суффикс после дефиса → окончание (обычное, для основ на ударное -ой, для «трет»)
    private val endings = mapOf(
        "й" to Triple("ый", "ой", "ий"), "го" to Triple("ого", "ого", "ьего"), "му" to Triple("ому", "ому", "ьему"),
        "м" to Triple("ом", "ом", "ьем"), "х" to Triple("ых", "ых", "ьих"), "е" to Triple("ое", "ое", "ье"),
        "я" to Triple("ая", "ая", "ья"), "ю" to Triple("ую", "ую", "ью"))
    // Круглые тысячи целиком — своя основа порядкового, а не «две тысячи» + окончание у нуля
    // (review t17 п.5, Normalizer.kt:80): «2000-й» → «двухтысячный», не просто cardinal-фолбэк.
    private val roundThousandStems = mapOf(1000L to "тысячн", 2000L to "двухтысячн", 3000L to "трёхтысячн",
        4000L to "четырёхтысячн", 5000L to "пятитысячн", 6000L to "шеститысячн", 7000L to "семитысячн",
        8000L to "восьмитысячн", 9000L to "девятитысячн")

    /** «2024-м» → «две тысячи двадцать четвёртом»: порядковым делаем только последнее слово. */
    fun ordinal(n: Long, suffix: String): String {
        val e = endings[suffix] ?: return cardinal(n)
        // Круглая тысяча с суффиксом «-е» — это не «двухтысячное» (ед. ч., ср. р.), а десятилетие
        // «двухтысячные» (мн. ч., task 18 п.8): у круглых тысяч «-е» иного смысла не бывает.
        roundThousandStems[n]?.let { stem ->
            return stem + if (suffix == "е") "ые" else if (stem in stressedEnding) e.second else e.first
        }
        val last = when {
            n % 100 == 0L && n % 1000 != 0L -> (n % 1000).toInt()
            n % 100 in 1..19 -> (n % 100).toInt()
            n % 10 != 0L -> (n % 10).toInt()
            else -> (n % 100).toInt()
        }
        val stem = ordinalStems[last] ?: return cardinal(n)
        val head = n - last
        val ending = when {
            stem == "трет" -> e.third
            stem in stressedEnding -> e.second
            else -> e.first
        }
        // в порядковых «тысяча девятьсот…», без «одна»
        val prefix = if (head > 0) cardinal(head).removePrefix("одна ") + " " else ""
        return prefix + stem + ending
    }

    // 0. Пунктуация (task 18 п.3): первый проход в prepare(), до чисел — приводим «шумную»
    // пунктуацию к одному варианту. Повторные «!»/«?» — к первому знаку, многоточие в любом
    // виде («...», ". . .») — к «…», дефис/минус в пробелах — к тире, несколько тире подряд —
    // к одному.
    private val multiExclQuestRe = Regex("[!?]{2,}")
    private val ellipsisRe = Regex("""\.(?: ?\.){2,}""")
    private val spacedDashRe = Regex("""(?<=[ ])[-−](?=[ ])""")
    private val multiDashRe = Regex("[–—]{2,}")
    fun punctuation(text: String): String {
        var s = multiExclQuestRe.replace(text) { it.value.first().toString() }
        s = ellipsisRe.replace(s, "…")
        s = spacedDashRe.replace(s, "–")
        s = multiDashRe.replace(s, "–")
        return s
    }

    private val numberRe = Regex("""(№|§)?(?<!\d)(-?)(\d+)(?:[.,](\d+))?(?:-(й|го|му|м|х|е|я|ю)(?![а-яё]))?(%)?""")

    // «в 1917 году» → порядковое: год → -й, года → -го, году → -м
    private val yearRe = Regex("""(?<![\d-])(\d{3,4})(\s+)(год|года|году)(?![а-яё])""", RegexOption.IGNORE_CASE)
    private val yearSuffix = mapOf("год" to "й", "года" to "го", "году" to "м")

    // 1. Разряды тысяч, разделённые НЕразрывным/узким/тонким пробелом (НЕ обычным — review t17 п.3,
    // Normalizer.kt:99: «1 200» с обычным пробелом должно остаться двумя числами).
    private val thousandsRe = Regex("""(\d)[   ](\d{3})(?!\d)""")
    private fun glueThousands(text: String): String {
        var s = text
        while (true) {
            val r = thousandsRe.replace(s) { m -> m.groupValues[1] + m.groupValues[2] }
            if (r == s) return r
            s = r
        }
    }

    // 1b. Обычный пробел (в отличие от NBSP/узкого/тонкого) склеивает разряды тысяч не всегда —
    // только когда группа круглая («000») или сразу следует ещё одна группа из трёх цифр
    // («1 200 000»), иначе это, вероятнее всего, два разных числа подряд (review t17 п.2).
    // ponytail: эвристика по локальному контексту, не полный грамматический разбор — «1 200 рублей»
    // (одна некруглая группа без продолжения) так и читается как «один двести», не «тысяча двести».
    private val thousandsSpaceRe = Regex("""(\d) (\d{3})(?!\d)""")
    private val moreGroupAheadRe = Regex("""^ \d{3}(?!\d)""")
    private fun glueThousandsSpace(text: String): String {
        var s = text
        while (true) {
            var changed = false
            val r = thousandsSpaceRe.replace(s) { m ->
                val group = m.groupValues[2]
                val tail = s.substring(m.range.last + 1)
                if (group == "000" || moreGroupAheadRe.containsMatchIn(tail)) {
                    changed = true
                    m.groupValues[1] + group
                } else m.value
            }
            if (!changed) return r
            s = r
        }
    }

    // 2. Сноски вида «[1]» — вырезаются, двойной пробел на их месте схлопывается.
    private val footnoteRe = Regex("""\[\d+\]""")
    private fun removeFootnotes(text: String) = footnoteRe.replace(text, "").replace(Regex(" {2,}"), " ")

    // 3. Римские цифры: «xx век» → «20-м веке» → (после numberRe) «двадцатом веке».
    // Слово из латинских «римских» букв конвертируем, только если рядом есть слово-триггер
    // (глава/часть/том… перед или век/столетие… после), ИЛИ токен в исходном тексте целиком
    // заглавный (XIV, MIX) — иначе это случайное латинское слово («mix», «civil») (review t17
    // п.2, Normalizer.kt:114-141). IGNORE_CASE — регистр слов-триггеров и самого токена теперь
    // доступен: prepare() больше не лоуэркейсит текст до numbers() (review t17 round2 п.1).
    private val romanStrictRe = Regex("""^m{0,4}(?:cm|cd|d?c{0,3})(?:xc|xl|l?x{0,3})(?:ix|iv|v?i{0,3})$""")
    private val romanRe = Regex(
        """(?:(?<![\p{L}])(глава|часть|том|книга|раздел|акт)\s+)?(?<![\p{L}\d])([mdclxvi]+)(?![\p{L}\d])""" +
            """(?:\s+(век|века|веке|веков|столетие|столетия|столетии)(?![а-яё]))?""",
        RegexOption.IGNORE_CASE
    )
    private val romanValues = mapOf('i' to 1, 'v' to 5, 'x' to 10, 'l' to 50, 'c' to 100, 'd' to 500, 'm' to 1000)
    private val romanAfterSuffix = mapOf("век" to "й", "века" to "го", "веке" to "м", "веков" to "х",
        "столетие" to "е", "столетия" to "го", "столетии" to "м")
    private val romanBeforeSuffix = mapOf("глава" to "я", "часть" to "я", "книга" to "я",
        "том" to "й", "раздел" to "й", "акт" to "й")

    private fun romanToInt(s: String): Int {
        var result = 0
        for (i in s.indices) {
            val v = romanValues.getValue(s[i])
            val next = s.getOrNull(i + 1)?.let { romanValues[it] } ?: 0
            result += if (v < next) -v else v
        }
        return result
    }

    private fun romanNumerals(text: String) = romanRe.replace(text) { m ->
        val (before, token, after) = m.destructured
        val lower = token.lowercase()
        if (!romanStrictRe.matches(lower)) return@replace m.value
        // Без триггера считаем числом только заглавный токен без «m» (review t17 round2 п.1,
        // тест «MIX стилей» → «микс стилей»): бытовые слова с «m» в начале («mix», «mid») тоже
        // валидны по строгой грамматике (M+IX=1009), а реальные capslock-числа без триггера на
        // тысячи почти не бывают — годы такого вида читает отдельное правило дат/годов.
        val eligibleAlone = token.all { it.isUpperCase() } && 'm' !in lower
        if (before.isEmpty() && after.isEmpty() && !eligibleAlone) return@replace m.value
        val value = romanToInt(lower)
        val suffix = if (after.isNotEmpty()) romanAfterSuffix[after.lowercase()] else romanBeforeSuffix[before.lowercase()]
        val sb = StringBuilder()
        if (before.isNotEmpty()) sb.append(before).append(' ')
        if (suffix != null) sb.append(value).append('-').append(suffix) else sb.append(value)
        if (after.isNotEmpty()) sb.append(' ').append(after)
        sb.toString()
    }

    // 4. Даты «дд.мм.гггг» и «дд.мм»: день и год — порядковый суффикс для numberRe,
    // месяц — сразу словом. Без года цифры трогаем, только если хотя бы одна часть двузначная,
    // иначе это десятичная дробь («3.5»).
    private val monthGenitive = arrayOf("", "января", "февраля", "марта", "апреля", "мая", "июня", "июля",
        "августа", "сентября", "октября", "ноября", "декабря")
    private val dateWithYearRe = Regex("""(?<!\d)(\d{1,2})[./](\d{1,2})[./](\d{4})(?!\d)""")
    private val dateNoYearRe = Regex("""(?<!\d)(\d{1,2})\.(\d{1,2})(?![\d.])""")

    private fun dates(text: String): String {
        val withYear = dateWithYearRe.replace(text) { m ->
            val (d, mo, y) = m.destructured
            val day = d.toInt(); val month = mo.toInt()
            if (day !in 1..31 || month !in 1..12) return@replace m.value
            "$day-го ${monthGenitive[month]} $y-го года"
        }
        return dateNoYearRe.replace(withYear) { m ->
            val (d, mo) = m.destructured
            if (d.length < 2 && mo.length < 2) return@replace m.value
            val day = d.toInt(); val month = mo.toInt()
            if (day !in 1..31 || month !in 1..12) return@replace m.value
            "$day-го ${monthGenitive[month]}"
        }
    }

    // 5. Время «чч:мм[:сс]»: читаем словами, только если рядом есть триггер — предлог перед
    // (в/к/до/с/около/после/на), слово после (утра/дня/вечера/ночи) или сам формат чч:мм:сс.
    // Без триггера «3:16» — это скорее «глава:стих» («Иоанна 3:16»), а не время: оставляем
    // числа по отдельности, их потом читает numberRe (review t17 п.1, Normalizer.kt:176).
    private val timeRe = Regex(
        """(?:(?<![\p{L}])(в|к|до|с|около|после|на)\s+)?(?<!\d)(\d{1,2}):(\d{2})(?::(\d{2}))?(?!\d)""" +
            """(?:\s+(утра|дня|вечера|ночи))?""",
        RegexOption.IGNORE_CASE
    )
    private fun times(text: String) = timeRe.replace(text) { m ->
        val (prep, h, mi, sec, after) = m.destructured
        val hour = h.toInt(); val minute = mi.toInt()
        if (hour !in 0..23 || minute !in 0..59) return@replace m.value
        val second = sec.toIntOrNull()
        if (sec.isNotEmpty() && (second == null || second !in 0..59)) return@replace m.value
        val hasTrigger = prep.isNotEmpty() || after.isNotEmpty() || sec.isNotEmpty()
        val sb = StringBuilder()
        if (prep.isNotEmpty()) sb.append(prep).append(' ')
        if (hasTrigger) {
            sb.append(cardinal(hour.toLong())).append(' ').append(plural(hour.toLong(), Triple("час", "часа", "часов")))
            if (minute > 0) {
                sb.append(' ').append(cardinal(minute.toLong(), feminine = true)).append(' ')
                    .append(plural(minute.toLong(), Triple("минута", "минуты", "минут")))
            }
            if (second != null && second > 0) {
                sb.append(' ').append(cardinal(second.toLong(), feminine = true)).append(' ')
                    .append(plural(second.toLong(), Triple("секунда", "секунды", "секунд")))
            }
        } else {
            sb.append(h).append(' ').append(mi)
        }
        if (after.isNotEmpty()) sb.append(' ').append(after)
        sb.toString()
    }

    // 6. Годы с «г.»/«гг.»: сначала диапазоны и «-х гг.», потом одиночные «г.» с предлогом/без.
    private val yearGRangeWithPrepRe =
        Regex("""(в|во)\s+(\d{4})\s*[-–—]\s*(\d{4})\s*(?:гг\.|годах)""", RegexOption.IGNORE_CASE)
    private val yearGRangeBareRe = Regex("""(?<![а-яё\d])(\d{4})\s*[-–—]\s*(\d{4})\s*гг\.""", RegexOption.IGNORE_CASE)
    private val yearGXWithPrepRe = Regex("""(в|во)\s+(\d{3,4})\s*-?\s*х\s*гг\.""", RegexOption.IGNORE_CASE)
    private val yearGXBareRe = Regex("""(?<![а-яё\d-])(\d{3,4})\s*-?\s*х\s*гг\.""", RegexOption.IGNORE_CASE)
    private val yearGLeftoverRe = Regex("""гг\.""", RegexOption.IGNORE_CASE)
    private val yearGPrepVRe = Regex("""(в|во)\s+(\d{3,4})\s*г\.""", RegexOption.IGNORE_CASE)
    private val yearGPrepOtherRe =
        Regex("""(с начала|с конца|с|до|после|от|около)\s+(\d{3,4})\s*г\.""", RegexOption.IGNORE_CASE)
    private val yearGBareRe = Regex("""(?<![а-яё\d])(\d{3,4})\s*г\.""", RegexOption.IGNORE_CASE)

    private fun yearsWithG(text: String): String {
        var s = text
        s = yearGRangeWithPrepRe.replace(s) { m ->
            val (prep, y1, y2) = m.destructured
            "$prep $y1-м – $y2-м годах"
        }
        s = yearGRangeBareRe.replace(s) { m ->
            val (y1, y2) = m.destructured
            "$y1-й – $y2-й годы"
        }
        s = yearGXWithPrepRe.replace(s) { m ->
            val (prep, y) = m.destructured
            "$prep $y-х годах"
        }
        s = yearGXBareRe.replace(s) { m -> "${m.groupValues[1]}-х годов" }
        s = yearGLeftoverRe.replace(s, "годы")
        s = yearGPrepVRe.replace(s) { m ->
            val (prep, y) = m.destructured
            "$prep $y-м году"
        }
        s = yearGPrepOtherRe.replace(s) { m ->
            val (prep, y) = m.destructured
            "$prep $y-го года"
        }
        s = yearGBareRe.replace(s) { m -> "${m.groupValues[1]}-й год" }
        return s
    }

    // 6b. «г.» перед словом с заглавной буквы (task 18 п.7) — это «город», а не год: годовые
    // варианты «г.» уже разобраны выше (нужен цифровой год перед точкой), здесь остаётся только
    // «г. Москва» и подобное.
    private val cityAbbrevRe = Regex("""(?<![\p{L}\d])г\.(?=\s*[А-ЯЁ])""")
    private fun cityAbbrev(text: String) = cityAbbrevRe.replace(text, "город")

    // 7. Родительный падеж количественного через дефис: «5-ти» → «пяти», «2-ух» → «двух».
    private val genitiveUnits = mapOf(1 to "одного", 2 to "двух", 3 to "трёх", 4 to "четырёх", 5 to "пяти",
        6 to "шести", 7 to "семи", 8 to "восьми", 9 to "девяти", 10 to "десяти", 11 to "одиннадцати",
        12 to "двенадцати", 13 to "тринадцати", 14 to "четырнадцати", 15 to "пятнадцати", 16 to "шестнадцати",
        17 to "семнадцати", 18 to "восемнадцати", 19 to "девятнадцати")
    private val genitiveTens = mapOf(20 to "двадцати", 30 to "тридцати", 40 to "сорока", 50 to "пятидесяти",
        60 to "шестидесяти", 70 to "семидесяти", 80 to "восьмидесяти", 90 to "девяноста")
    private val cardinalGenSuffixRe = Regex("""(\d+)-(ти|и|ух|ех|ёх)(?![а-яё])""", RegexOption.IGNORE_CASE)

    private fun genitiveCardinal(n: Int): String = when {
        n in 1..19 -> genitiveUnits.getValue(n)
        n in 20..99 && n % 10 == 0 -> genitiveTens.getValue(n)
        n in 20..99 -> genitiveTens.getValue(n / 10 * 10) + " " + genitiveUnits.getValue(n % 10)
        else -> cardinal(n.toLong())
    }

    private fun cardinalGenitiveSuffix(text: String) = cardinalGenSuffixRe.replace(text) { m ->
        val n = m.groupValues[1].toIntOrNull() ?: return@replace m.value
        genitiveCardinal(n)
    }

    // 8. Единицы измерения: число оставляем цифрами для numberRe (кроме мин/сек — там род
    // важен для согласования, поэтому число сразу произносим словом в женском роде).
    private class UnitForms(val forms: Triple<String, String, String>, val suffix: String = "", val feminine: Boolean = false)
    private val unitTable = linkedMapOf(
        "км/ч" to UnitForms(Triple("километр", "километра", "километров"), suffix = " в час"),
        "м/с" to UnitForms(Triple("метр", "метра", "метров"), suffix = " в секунду"),
        "кг" to UnitForms(Triple("килограмм", "килограмма", "килограммов")),
        "км" to UnitForms(Triple("километр", "километра", "километров")),
        "см" to UnitForms(Triple("сантиметр", "сантиметра", "сантиметров")),
        "мм" to UnitForms(Triple("миллиметр", "миллиметра", "миллиметров")),
        "мл" to UnitForms(Triple("миллилитр", "миллилитра", "миллилитров")),
        "мин" to UnitForms(Triple("минута", "минуты", "минут"), feminine = true),
        "сек" to UnitForms(Triple("секунда", "секунды", "секунд"), feminine = true),
        "ч" to UnitForms(Triple("час", "часа", "часов")),
        "л" to UnitForms(Triple("литр", "литра", "литров")),
        "г" to UnitForms(Triple("грамм", "грамма", "граммов")),
        "м" to UnitForms(Triple("метр", "метра", "метров")),
    )
    private val unitAltPattern = unitTable.keys.joinToString("|") { Regex.escape(it) }
    // ponytail: «г.»/«кг.» в конце предложения не читаются словом — редкий случай, не покрыт тестами.
    private val unitRe = Regex("""(\d+(?:[.,]\d+)?)\s*($unitAltPattern)(?![\p{L}\d/.])""", RegexOption.IGNORE_CASE)

    private fun units(text: String) = unitRe.replace(text) { m ->
        val (numStr, unitKey) = m.destructured
        val u = unitTable.getValue(unitKey.lowercase())
        val hasFrac = numStr.contains(',') || numStr.contains('.')
        if (u.feminine && !hasFrac) {
            val n = numStr.toLongOrNull() ?: return@replace m.value
            cardinal(n, feminine = true) + " " + plural(n, u.forms) + u.suffix
        } else {
            val intPart = numStr.substringBefore(',').substringBefore('.')
            val n = intPart.toLongOrNull() ?: return@replace m.value
            val form = if (hasFrac) u.forms.second else plural(n, u.forms)
            numStr + " " + form + u.suffix
        }
    }

    // 8b. Градусы (task 18 п.4): число оставляем цифрами для numberRe ниже, сразу дописываем
    // слово. «°C»/«° C»/«°С» (кириллическая «С» тоже) — Цельсия, «°F» — по Фаренгейту,
    // одиночный «°» — просто «градус(а/ов)» по plural().
    private val degreeForms = Triple("градус", "градуса", "градусов")
    private val degreeRe = Regex("""(-?\d+)\s*°\s*(c|f|с)?(?![\p{L}])""", RegexOption.IGNORE_CASE)
    private fun degrees(text: String) = degreeRe.replace(text) { m ->
        val (numStr, unit) = m.destructured
        val n = numStr.removePrefix("-").toLongOrNull() ?: return@replace m.value
        val suffix = when {
            unit.isEmpty() -> ""
            unit.equals("f", ignoreCase = true) -> " по Фаренгейту"
            else -> " Цельсия"
        }
        "$numStr ${plural(n, degreeForms)}$suffix"
    }

    // 7b. Число с родительным суффиксом (-ти/-х/-ми…) сразу перед единицей измерения: сначала
    // само число, потом plural() единицы — количественная форма, а не форма предлога
    // (review t17 п.4, Normalizer.kt:228-284: «5-ти км» иначе уходит в cardinalGenitiveSuffix
    // раньше unit-регэкспа, и единица остаётся нераскрытой).
    // ponytail: единица в количественной форме («пяти километров»), а не в падеже предлога
    // («пяти километрах») — для полного склонения единиц нужны падежи, здесь не покрыто.
    private val cardinalGenSuffixUnitRe =
        Regex("""(\d+)-(ти|и|ух|ех|ёх|х|ми)\s*($unitAltPattern)(?![\p{L}\d/.])""", RegexOption.IGNORE_CASE)
    private fun cardinalGenitiveSuffixUnit(text: String) = cardinalGenSuffixUnitRe.replace(text) { m ->
        val n = m.groupValues[1].toIntOrNull() ?: return@replace m.value
        val u = unitTable.getValue(m.groupValues[3].lowercase())
        genitiveCardinal(n) + " " + plural(n.toLong(), u.forms) + u.suffix
    }

    // 9. Составные номера разделов «1.2.3» — читаются по частям через «точка».
    // Ровно два числа через точку («1.2») остаются датой/дробью — их не трогаем.
    private val sectionRe = Regex("""(?<![\d.])(\d+)(\.\d+){2,}(?![\d.])""")
    private fun sectionNumbers(text: String) = sectionRe.replace(text) { m ->
        m.value.split('.').joinToString(" точка ") { cardinal(it.toLong()) }
    }

    // 10. Дроби через слэш (task 18 п.5): если знаменатель 2..20 и числитель меньше него —
    // говорим словами («шесть десятых»): числитель — количественное женского рода, знаменатель —
    // порядковое женского рода (a==1 — ед. ч. им. п. «одна вторая», a>1 — мн. ч. род. п.
    // «две третьих»). Иначе — как раньше, «a дробь b» («25/3»).
    private val fractionSlashRe = Regex("""(?<![\d/])(\d+)/(\d+)(?![\d/])""")
    private fun fractionsSlash(text: String) = fractionSlashRe.replace(text) { m ->
        val (a, b) = m.destructured
        val an = a.toIntOrNull(); val bn = b.toIntOrNull()
        if (an != null && bn != null && bn in 2..20 && an in 1 until bn) {
            val denomSuffix = if (an == 1) "я" else "х"
            cardinal(an.toLong(), feminine = true) + " " + ordinal(bn.toLong(), denomSuffix)
        } else {
            cardinal(a.toLong()) + " дробь " + cardinal(b.toLong())
        }
    }

    // 10b. Валюты (task 18 п.6): число остаётся цифрами для numberRe ниже, сразу дописываем
    // слово. «€»/евро не склоняется, доллар/рубль/копейка — по plural(), а не всегда одна форма.
    private val dollarForms = Triple("доллар", "доллара", "долларов")
    private val rubleForms = Triple("рубль", "рубля", "рублей")
    private val kopeckForms = Triple("копейка", "копейки", "копеек")
    private val moneyNumRe = """\d+(?:[.,]\d+)?"""
    private val dollarPrefixRe = Regex("""\$($moneyNumRe)""")
    private val dollarSuffixRe = Regex("""($moneyNumRe)\s?\$""")
    private val euroPrefixRe = Regex("""€($moneyNumRe)""")
    private val euroSuffixRe = Regex("""($moneyNumRe)\s?€""")
    private val rubleSuffixRe = Regex("""($moneyNumRe)\s?(?:₽|руб\.|р\.)""", RegexOption.IGNORE_CASE)
    private val kopeckSuffixRe = Regex("""($moneyNumRe)\s?коп\.""", RegexOption.IGNORE_CASE)

    private fun currencyWord(numStr: String, forms: Triple<String, String, String>): String {
        val hasFrac = numStr.contains(',') || numStr.contains('.')
        if (hasFrac) return forms.second
        val n = numStr.toLongOrNull() ?: return forms.third
        return plural(n, forms)
    }

    private fun currency(text: String): String {
        var s = text
        s = dollarPrefixRe.replace(s) { m -> "${m.groupValues[1]} ${currencyWord(m.groupValues[1], dollarForms)}" }
        s = dollarSuffixRe.replace(s) { m -> "${m.groupValues[1]} ${currencyWord(m.groupValues[1], dollarForms)}" }
        s = euroPrefixRe.replace(s) { m -> "${m.groupValues[1]} евро" }
        s = euroSuffixRe.replace(s) { m -> "${m.groupValues[1]} евро" }
        s = rubleSuffixRe.replace(s) { m -> "${m.groupValues[1]} ${currencyWord(m.groupValues[1], rubleForms)}" }
        s = kopeckSuffixRe.replace(s) { m -> "${m.groupValues[1]} ${currencyWord(m.groupValues[1], kopeckForms)}" }
        return s
    }

    // 11. Сокращения. Предложные формы («на стр.» → «на странице») — раньше общего списка.
    // «тыс./млн/млрд» после числа — со склонением через plural(), «тыс.» ещё и с родом (жен.).
    private val abbrevPrepRe =
        Regex("""(?<![\p{L}\d])(на|в|во|о|об|при)\s+(стр|гл|табл|рис)\.""", RegexOption.IGNORE_CASE)
    private val abbrevPrepWord = mapOf("стр" to "странице", "гл" to "главе", "табл" to "таблице", "рис" to "рисунке")
    private fun abbrevPrep(text: String) = abbrevPrepRe.replace(text) { m ->
        val (prep, abbr) = m.destructured
        "$prep ${abbrevPrepWord.getValue(abbr.lowercase())}"
    }

    private val scaleAbbrevRe = Regex("""(\d+)\s*(тыс|млн|млрд)\.?(?![\p{L}])""", RegexOption.IGNORE_CASE)
    private val scaleAbbrevForms = mapOf(
        "тыс" to Triple("тысяча", "тысячи", "тысяч"),
        "млн" to Triple("миллион", "миллиона", "миллионов"),
        "млрд" to Triple("миллиард", "миллиарда", "миллиардов"),
    )
    private fun scaleAbbrev(text: String) = scaleAbbrevRe.replace(text) { m ->
        val (numStr, abbrRaw) = m.destructured
        val abbr = abbrRaw.lowercase()
        val n = numStr.toLongOrNull() ?: return@replace m.value
        val forms = scaleAbbrevForms.getValue(abbr)
        if (abbr == "тыс") cardinal(n, feminine = true) + " " + plural(n, forms)
        else numStr + " " + plural(n, forms)
    }

    private val abbrevSimple = listOf(
        Regex("""(?<![\p{L}\d])и\s+т\.\s*д\.(?![\p{L}])""", RegexOption.IGNORE_CASE) to "и так далее",
        Regex("""(?<![\p{L}\d])и\s+т\.\s*п\.(?![\p{L}])""", RegexOption.IGNORE_CASE) to "и тому подобное",
        Regex("""(?<![\p{L}\d])в\s+т\.\s*ч\.(?![\p{L}])""", RegexOption.IGNORE_CASE) to "в том числе",
        Regex("""(?<![\p{L}\d])т\.\s*е\.(?![\p{L}])""", RegexOption.IGNORE_CASE) to "то есть",
        Regex("""(?<![\p{L}\d])т\.\s*к\.(?![\p{L}])""", RegexOption.IGNORE_CASE) to "так как",
        Regex("""(?<![\p{L}\d])т\.\s*н\.(?![\p{L}])""", RegexOption.IGNORE_CASE) to "так называемый",
        Regex("""(?<![\p{L}\d])пп\.(?![\p{L}])""", RegexOption.IGNORE_CASE) to "подпункт",
        Regex("""(?<![\p{L}\d])п\.(?=\s*\d)""", RegexOption.IGNORE_CASE) to "пункт",
        Regex("""(?<![\p{L}\d])стр\.(?![\p{L}])""", RegexOption.IGNORE_CASE) to "страница",
        Regex("""(?<![\p{L}\d])рис\.(?![\p{L}])""", RegexOption.IGNORE_CASE) to "рисунок",
        Regex("""(?<![\p{L}\d])табл\.(?![\p{L}])""", RegexOption.IGNORE_CASE) to "таблица",
        Regex("""(?<![\p{L}\d])гл\.(?![\p{L}])""", RegexOption.IGNORE_CASE) to "глава",
        Regex("""(?<![\p{L}\d])ср\.(?![\p{L}])""", RegexOption.IGNORE_CASE) to "сравни",
        Regex("""(?<![\p{L}\d])св\.(?![\p{L}])""", RegexOption.IGNORE_CASE) to "святой",
        Regex("""(?<![\p{L}\d])см\.(?![\p{L}])""", RegexOption.IGNORE_CASE) to "смотри",
        Regex("""(?<![\p{L}\d])др\.(?![\p{L}])""", RegexOption.IGNORE_CASE) to "другие",
        Regex("""(?<![\p{L}\d])пр\.(?![\p{L}])""", RegexOption.IGNORE_CASE) to "прочее",
        Regex("""(?<![\p{L}\d])напр\.(?![\p{L}])""", RegexOption.IGNORE_CASE) to "например",
        Regex("""(?<![\p{L}\d])проф\.(?![\p{L}])""", RegexOption.IGNORE_CASE) to "профессор",
        Regex("""(?<![\p{L}\d])акад\.(?![\p{L}])""", RegexOption.IGNORE_CASE) to "академик",
        Regex("""(?<![\p{L}\d])им\.(?![\p{L}])""", RegexOption.IGNORE_CASE) to "имени",
        Regex("""(?<![\p{L}\d])ул\.(?![\p{L}])""", RegexOption.IGNORE_CASE) to "улица",
        // Фолбэк для «руб.» без числа сразу перед ним (например, после «тыс.» — «5 тыс. руб.»
        // число уже стало словами выше в scaleAbbrev). Число рядом с «руб.» ловит currency()
        // раньше — со склонением по plural() (task 18 п.6), здесь до него не доходит.
        Regex("""(?<![\p{L}\d])руб\.(?![\p{L}])""", RegexOption.IGNORE_CASE) to "рублей",
        Regex("""(?<![\p{L}\d])тыс\.(?![\p{L}])""", RegexOption.IGNORE_CASE) to "тысяч",
        Regex("""(?<![\p{L}\d])млн\.?(?![\p{L}])""", RegexOption.IGNORE_CASE) to "миллионов",
        Regex("""(?<![\p{L}\d])млрд\.?(?![\p{L}])""", RegexOption.IGNORE_CASE) to "миллиардов",
    )

    private fun abbreviations(text: String): String {
        var s = abbrevPrep(text)
        s = scaleAbbrev(s)
        for ((re, rep) in abbrevSimple) s = re.replace(s, rep)
        return s
    }

    fun numbers(text: String): String {
        // U+2212 (настоящий знак «минус», не дефис) — приводим к «-», чтобы его читал
        // тот же numberRe, что уже умеет «-5» (task 18 п.4).
        var s = text.replace('−', '-')
        s = glueThousands(s)
        s = glueThousandsSpace(s)
        s = removeFootnotes(s)
        // Градусы — до римских цифр: одиночная «C» после «°» иначе читается как римское 100
        // (eligibleAlone в romanNumerals не заглядывает влево, task 18 п.4).
        s = degrees(s)
        s = romanNumerals(s)
        s = dates(s)
        s = times(s)
        s = yearsWithG(s)
        s = cityAbbrev(s)
        s = cardinalGenitiveSuffixUnit(s)
        s = cardinalGenitiveSuffix(s)
        s = units(s)
        s = sectionNumbers(s)
        s = fractionsSlash(s)
        s = currency(s)
        s = abbreviations(s)
        s = yearRe.replace(s) { m ->
            m.groupValues[1] + "-" + yearSuffix.getValue(m.groupValues[3].lowercase()) + m.groupValues[2] + m.groupValues[3]
        }
        return numberRe.replace(s) { m ->
            val (mark, minus, intPart, frac, suffix, percent) = m.destructured
            val sb = StringBuilder()
            when (mark) { "№" -> sb.append("номер "); "§" -> sb.append("параграф ") }
            if (minus.isNotEmpty()) sb.append("минус ")
            if (intPart.length > 12) {
                sb.append(intPart.map { cardinal((it - '0').toLong()) }.joinToString(" "))
                return@replace sb.toString()
            }
            val n = intPart.toLongOrNull() ?: return@replace m.value
            if (frac.isNotEmpty()) {
                val fracN = frac.toLongOrNull() ?: return@replace m.value
                val denom = when (frac.length) { 1 -> Triple("десятая", "десятых", "десятых"); 2 -> Triple("сотая", "сотых", "сотых"); else -> Triple("тысячная", "тысячных", "тысячных") }
                sb.append(cardinal(n, feminine = true)).append(if (n % 10 == 1L && n % 100 != 11L) " целая " else " целых ")
                sb.append(cardinal(fracN, feminine = true)).append(' ').append(plural(fracN, denom))
            } else if (suffix.isNotEmpty()) {
                sb.append(ordinal(n, suffix.lowercase()))
            } else {
                sb.append(cardinal(n))
            }
            if (percent.isNotEmpty()) sb.append(' ').append(plural(n, Triple("процент", "процента", "процентов")))
            sb.toString()
        }
    }

    private val digraphs = listOf(
        "sch" to "ш", "tch" to "ч", "sh" to "ш", "ch" to "ч", "th" to "з", "ph" to "ф", "wh" to "в", "qu" to "кв",
        "ck" to "к", "oo" to "у", "ee" to "и", "ea" to "и", "ou" to "ау", "ay" to "эй", "ey" to "эй", "ai" to "эй",
        "oa" to "оу", "ie" to "и", "kn" to "н", "wr" to "р", "gh" to "", "ng" to "нг", "ew" to "ью",
    )
    private val singles = mapOf('a' to "а", 'b' to "б", 'c' to "к", 'd' to "д", 'e' to "е", 'f' to "ф", 'g' to "г",
        'h' to "х", 'i' to "и", 'j' to "дж", 'k' to "к", 'l' to "л", 'm' to "м", 'n' to "н", 'o' to "о", 'p' to "п",
        'q' to "к", 'r' to "р", 's' to "с", 't' to "т", 'u' to "а", 'v' to "в", 'w' to "в", 'x' to "кс", 'y' to "й",
        'z' to "з")
    // ponytail: транслитерация по таблице, не G2P; «iphone» → «айфон» через частные правила ниже
    private val wordFixes = mapOf("iphone" to "айфон", "google" to "гугл", "the" to "зэ", "new" to "нью",
        "york" to "йорк", "queen" to "квин", "photo" to "фото", "charlie" to "чарли", "sherlock" to "шерлок",
        "windows" to "виндовс", "john" to "джон")
    private val latinWordRe = Regex("[a-z]+")

    // 12. Омоглифы: латинская буква внутри преимущественно кириллического слова — опечатка
    // раскладки («прoблема» с латинской «o»), а не английское слово — возвращаем в кириллицу.
    private val homoglyphMap = mapOf('a' to 'а', 'c' to 'с', 'e' to 'е', 'o' to 'о', 'p' to 'р', 'x' to 'х', 'y' to 'у')
    private val mixedWordRe = Regex("""\p{L}+""")
    private fun fixHomoglyphs(text: String) = mixedWordRe.replace(text) { m ->
        val w = m.value
        val cyr = w.count { it in 'а'..'я' || it == 'ё' }
        val lat = w.count { it in 'a'..'z' }
        if (cyr > 0 && lat > 0 && cyr > lat) w.map { homoglyphMap[it] ?: it }.joinToString("") else w
    }

    fun latin(text: String): String = latinWordRe.replace(fixHomoglyphs(text.lowercase())) { m ->
        wordFixes[m.value] ?: run {
            val w = m.value
            val sb = StringBuilder()
            var i = 0
            while (i < w.length) {
                val d = digraphs.firstOrNull { w.startsWith(it.first, i) }
                if (d != null) { sb.append(d.second); i += d.first.length }
                else { sb.append(singles[w[i]] ?: ""); i++ }
            }
            // немое e на конце
            if (w.length > 2 && w.endsWith("e") && !w.endsWith("ee")) sb.setLength(sb.length - 1)
            sb.toString()
        }
    }

    // JVM \s матчит только ASCII-пробелы; Python \s матчит любой Unicode-пробел (NBSP U+00A0 и т.п.).
    // Сначала приводим все такие пробелы к обычному, иначе они просто выпадают на фильтрации и слова слипаются.
    private val wsClass = Regex("[\\s\\p{Zs}\\u0085\\u2028\\u2029\\u001C-\\u001F]")

    fun symbols(text: String, allowed: String): String {
        val normalized = text.replace('—', '–').replace('‑', '-').replace(wsClass, " ")
        val sb = StringBuilder(normalized.length)
        for (c in normalized) if (c in allowed) sb.append(c)
        return sb.toString().replace(Regex("\\s+"), " ").trim()
    }

    // Регистр НЕ приводим к нижнему здесь: numbers() должен видеть исходный регистр — иначе
    // римские цифры в CAPS-токене («Людовик XIV») теряют признак «весь токен заглавный» ещё
    // до romanNumerals(). latin() лоуэркейсит сам, так что дальше по пайплайну (символы/фильтр)
    // всё как раньше (review t17 round2 п.1).
    fun prepare(text: String, allowed: String): String =
        symbols(latin(numbers(punctuation(text))), allowed)
}
