package ru.kost.ruvoice.text

object Normalizer {
    /** Таблица морфологии AOT (assets/morph.bin); null — как раньше, по окончаниям и спискам. Ставит SileroModels.data(). */
    @Volatile var morph: Morph? = null

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
        100 to "сот", 200 to "двухсот", 300 to "трёхсот", 400 to "четырёхсот", 500 to "пятисот", 600 to "шестисот",
        700 to "семисот", 800 to "восьмисот", 900 to "девятисот")
    private val stressedEnding = setOf("втор", "шест", "седьм", "восьм", "сороков")
    // суффикс после дефиса → окончание (обычное, для основ на ударное -ой, для «трет»)
    private val endings = mapOf(
        "й" to Triple("ый", "ой", "ий"), "го" to Triple("ого", "ого", "ьего"), "му" to Triple("ому", "ому", "ьему"),
        "м" to Triple("ом", "ом", "ьем"), "х" to Triple("ых", "ых", "ьих"), "е" to Triple("ое", "ое", "ье"),
        "я" to Triple("ая", "ая", "ья"), "ю" to Triple("ую", "ую", "ью"),
        // им. п. мн. ч. (task 28 п.6): «XX вв.» → «20-ые века» → «двадцатые»; «-е» даёт «двадцатое».
        "ые" to Triple("ые", "ые", "ьи"),
        // Полные и «длинные» суффиксы (корпус ru-normalizr): «1-ый», «5-ой», «1-ую», «21-ом», «1-ым».
        "ый" to Triple("ый", "ой", "ий"), "ий" to Triple("ый", "ой", "ий"), "ой" to Triple("ой", "ой", "ьей"),
        "ей" to Triple("ой", "ой", "ьей"), "ую" to Triple("ую", "ую", "ью"), "ью" to Triple("ую", "ую", "ью"),
        "ом" to Triple("ом", "ом", "ьем"), "ем" to Triple("ом", "ом", "ьем"), "ым" to Triple("ым", "ым", "ьим"),
        "им" to Triple("ым", "ым", "ьим"), "ого" to Triple("ого", "ого", "ьего"), "его" to Triple("ого", "ого", "ьего"),
        "ому" to Triple("ому", "ому", "ьему"), "ему" to Triple("ому", "ому", "ьему"), "ых" to Triple("ых", "ых", "ьих"),
        "их" to Triple("ых", "ых", "ьих"), "ая" to Triple("ая", "ая", "ья"), "ья" to Triple("ая", "ая", "ья"),
        "ое" to Triple("ое", "ое", "ье"), "ье" to Triple("ое", "ое", "ье"), "ыми" to Triple("ыми", "ыми", "ьими"))
    // Альтернация суффиксов для numberRe и numTailExclude: длинные раньше коротких.
    val ordSuffixAlt: String = endings.keys.sortedByDescending { it.length }.joinToString("|")
    // Круглые тысячи целиком — своя основа порядкового, а не «две тысячи» + окончание у нуля
    // (review t17 п.5, Normalizer.kt:80): «2000-й» → «двухтысячный», не просто cardinal-фолбэк.
    private val roundThousandStems = mapOf(1000L to "тысячн", 2000L to "двухтысячн", 3000L to "трёхтысячн",
        4000L to "четырёхтысячн", 5000L to "пятитысячн", 6000L to "шеститысячн", 7000L to "семитысячн",
        8000L to "восьмитысячн", 9000L to "девятитысячн", 10000L to "десятитысячн", 100000L to "стотысячн")

    /**
     * «2024-м» → «две тысячи двадцать четвёртом»: порядковым делаем только последнее слово.
     * [pluralDecade] — круглая тысяча с суффиксом «-е» неоднозначна: «1000-е место» (обычное
     * порядковое, ед. ч.) и «в 2000-е» / «2000-е годы» (десятилетие, мн. ч.) пишутся одинаково,
     * различает их только то, что стоит после числа в тексте (review round 1 п.4) — это решает
     * вызывающий код по контексту, сюда приходит уже готовым флагом.
     */
    fun ordinal(n: Long, suffix: String, pluralDecade: Boolean = false): String {
        // «1960-е гг.», «в лихие 90-е» — десятилетие (круглое число), мн. ч.: «шестидесятые», не «шестидесятое».
        val e = (if (suffix == "е" && pluralDecade && n % 10 == 0L) endings["ые"] else endings[suffix]) ?: return cardinal(n)
        roundThousandStems[n]?.let { stem ->
            return stem + if (stem in stressedEnding) e.second else e.first
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
    fun punctuation(text: String, rules: Rules = Rules()): String {
        if (!rules.on("punct")) return text
        var s = multiExclQuestRe.replace(text) { it.value.first().toString() }
        s = ellipsisRe.replace(s, "…")
        s = spacedDashRe.replace(s, "–")
        s = multiDashRe.replace(s, "–")
        return s
    }

    // «Минус» перед числом — только если дефис не приклеен к букве/цифре слева (review final-fix
    // п.1): иначе диапазоны через дефис («10-15», «2-3») и составные слова («Ту-154») ошибочно
    // читаются как «минус» — дефис между двумя числами остаётся дефисом, каждое число читается
    // само по себе (numTailExclude в cases() этот же дефис не блокирует нарочно, см. ниже).
    // Суффикс порядкового — через дефис или вплотную («5я группа», «3го номера», «21ом году»);
    // голое «м» без дефиса не берём («5м» — метры, их читает units()). Процент — и через пробел («20 %»).
    private val numberRe =
        Regex("""(№|§)?(?<!\d)((?<![\p{L}\d])-)?(\d+)(?:[.,](\d+))?(?:(?:-|(?!м(?![а-яё])))($ordSuffixAlt)(?![а-яё\d]))?( ?%)?""")

    // Признак десятилетия для круглой тысячи с «-е» (task 18 review round 1 п.4): годовое слово
    // сразу после, либо число в конце строки/перед пунктуацией — «2000-е годы», «в 2000-е»;
    // если дальше идёт обычное слово («1000-е место») — это порядковое, не десятилетие.
    private val decadeYearWordRe = Regex("""^\s*(?:годы|года|годов|годах|гг\.)(?![а-яёА-ЯЁ])""", RegexOption.IGNORE_CASE)
    // Суффикс «-й» двусмыслен: «1-й номер» (м. р.) и «в 3-й дивизии» (ж. р., косвенный) — решает
    // окончание слова следом; «-е»: «2-е место» (ср. р.) и «2-е ножницы» (мн. ч.). Корпус ru-normalizr.
    private val femTailRe = Regex("""^\s+(?!год)\p{L}{2,}(?:ой|ей|ии|ы|и|е)(?![а-яё])""")
    private val pluralTailRe = Regex("""^\s+\p{L}{2,}[ыиа](?![а-яё])""")
    // То же по таблице морфологии, когда она есть и слово следом — существительное: «-й» → «-ей» у женского
    // рода («3-й дивизии»), «-е» → «-ые», если у слова есть только мн. ч. («2-е ножницы», «2000-е годы»).
    // null — слова нет в таблице или род общий, тогда решают регулярки выше.
    private val tailWordRe = Regex("""^\s+(\p{L}{2,})(?![а-яё])""")
    private fun tailNoun(tail: String): Int? {
        val word = tailWordRe.find(tail)?.groupValues?.get(1) ?: return null
        return morph?.tags(word)?.takeIf { Morph.isNoun(it) }
    }
    private fun ordFem(tail: String): Boolean? {
        val t = tailNoun(tail) ?: return null
        return when (Morph.gender(t)) { Gender.F -> true; Gender.M, Gender.N -> false; null -> null }
    }
    private fun ordPlural(tail: String): Boolean? {
        val t = tailNoun(tail) ?: return null
        return when {
            Case.NOM in Morph.nounCases(t, plural = false) -> false
            Case.NOM in Morph.nounCases(t, plural = true) -> true
            else -> null
        }
    }
    private fun isDecadeTail(tail: String): Boolean {
        if (decadeYearWordRe.containsMatchIn(tail)) return true
        val t = tail.trimStart(' ', '\t')
        return t.isEmpty() || !t[0].isLetter()
    }

    // «в 1917 году» → порядковое: год → -й, года → -го, году → -м
    // Одно-двузначное — только перед «году» («в 21 году»): «2 года» — срок, не год.
    private val yearRe = Regex("""(?<![\d-])(\d{3,4}|\d{1,2}(?=\s+году))(\s+)(год|года|году)(?![а-яё])""", RegexOption.IGNORE_CASE)
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
    // только когда группа круглая («000»), сразу следует ещё одна группа из трёх цифр
    // («1 200 000») или за группой идут деньги/единица измерения («1 200 рублей», «2 500 км»),
    // иначе это, вероятнее всего, два разных числа подряд («глава 1 200 читателей», review t17 п.2).
    private val thousandsSpaceRe = Regex("""(\d) (\d{3})(?!\d)""")
    private val moreGroupAheadRe = Regex("""^ \d{3}(?!\d)""")
    private fun glueThousandsSpace(text: String): String {
        var s = text
        while (true) {
            var changed = false
            val r = thousandsSpaceRe.replace(s) { m ->
                val group = m.groupValues[2]
                val tail = s.substring(m.range.last + 1)
                if (group == "000" || moreGroupAheadRe.containsMatchIn(tail) || moneyOrUnitAheadRe.containsMatchIn(tail)) {
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
    // Кириллические х/с/м/і визуально совпадают с латинскими римскими буквами (review final-fix
    // п.2) — «ХХ» иначе доходит до Abbrev как обычный кириллический токен («ха х+а»). Токен может
    // быть смешанным («ХIV» — кириллическая Х + латинские IV), поэтому в класс входят обе группы.
    // Предлог перед римским числом задаёт падеж, когда существительное сокращено («в XV в.»,
    // «к IV кв.») или его нет; при полном слове падеж берём по его окончанию («XV веке», «главах»).
    private val romanPrepCase = listOf("в", "во", "о", "об", "при", "начале", "середине", "конце", "рубеже", "половине", "протяжении").associateWith { Case.PRE } +
        listOf("с", "со", "от", "до", "из", "после", "около", "для", "начала", "середины", "конца", "рубежа", "половины", "течение").associateWith { Case.GEN } +
        mapOf("к" to Case.DAT, "ко" to Case.DAT, "между" to Case.INS, "перед" to Case.INS)
    private val romanPrepAlt = romanPrepCase.keys.joinToString("|")
    // Существительные-триггеры: основа → род (m/f/n); формы ловим по основе + окончанию.
    private val romanNounGender = mapOf("глав" to 'f', "част" to 'f', "книг" to 'f', "том" to 'm', "раздел" to 'm', "акт" to 'm',
        "век" to 'm', "столети" to 'n', "тысячелети" to 'n', "квартал" to 'm')
    private val romanNounBeforeAlt = """(?:глав(?:а|ы|е|у|ой|ах|ам|ами)?|част(?:ь|и|ью|ей|ях|ям|ями)|том(?:а|ы|у|е|ом|ов|ах|ам|ами)?|книг(?:а|и|е|у|ой|ах|ам|ами)?|раздел(?:а|ы|у|е|ом|ов|ах|ам|ами)?|акт(?:а|ы|у|е|ом|ов|ах|ам|ами)?)"""
    private val romanNounAfterAlt = """(?:в\.|вв\.|кв\.|век(?:а|е|у|ом|ов|ах|ам|ами)?|(?:столети|тысячелети)(?:е|я|и|ю|ем|й|ях|ям|ями)|квартал(?:а|е|у|ом|ов|ах|ам|ами)?)"""
    private val romanTok = """([mdclxviхсмі]+)"""
    // Кириллические х/с/м/і визуально совпадают с латинскими римскими буквами (review final-fix
    // п.2) — «ХХ» иначе доходит до Abbrev как обычный кириллический токен («ха х+а»). Токен может
    // быть смешанным («ХIV» — кириллическая Х + латинские IV), поэтому в класс входят обе группы.
    private val romanRe = Regex(
        """(?:(?<![\p{L}])($romanPrepAlt)\s+)?(?:(?<![\p{L}])($romanNounBeforeAlt)\s+)?(?<![\p{L}\d])$romanTok(?![\p{L}\d])""" +
            """(?:\s*(и|по|до|[-–—]|,)\s*$romanTok(?![\p{L}\d])(?:\s*(?:и|,)\s*$romanTok(?![\p{L}\d]))?)?(?:\s+($romanNounAfterAlt)(?![а-яё]))?""",
        RegexOption.IGNORE_CASE
    )
    private val romanValues = mapOf('i' to 1, 'v' to 5, 'x' to 10, 'l' to 50, 'c' to 100, 'd' to 500, 'm' to 1000)
    private val romanCyrMap = mapOf('х' to 'x', 'с' to 'c', 'м' to 'm', 'і' to 'i')
    private fun latinizeRoman(s: String) = s.map { romanCyrMap[it] ?: it }.joinToString("")
    // Суффикс порядкового для numberRe по падежу и роду.
    private val ordSuffixByCase = mapOf(
        'm' to arrayOf("й", "го", "му", "й", "ым", "м"), 'f' to arrayOf("я", "ой", "ой", "ю", "ой", "ой"),
        'n' to arrayOf("е", "го", "му", "е", "ым", "м"))
    private val centurySg = arrayOf("век", "века", "веку", "век", "веком", "веке")
    private val centuryPl = arrayOf("века", "веков", "векам", "века", "веками", "веках")
    private val quarterSg = arrayOf("квартал", "квартала", "кварталу", "квартал", "кварталом", "квартале")
    // Падеж по окончанию существительного-триггера; plural — рядом два числа, тогда «века»/«главы» —
    // им. п. мн. ч., а не род. п. ед. ч. null — форма не разобрана (берём предлог/именительный).
    private fun romanNounCase(noun: String, plural: Boolean): Case? {
        val stem = romanNounGender.keys.firstOrNull { noun.startsWith(it) } ?: return null
        val g = romanNounGender.getValue(stem)
        return when (val end = noun.removePrefix(stem)) {
            "", "ь" -> if (g == 'f' && end.isEmpty()) Case.GEN else Case.NOM
            "а" -> if (g == 'f') Case.NOM else if (plural) Case.NOM else Case.GEN
            "ы" -> if (plural) Case.NOM else Case.GEN
            "я" -> if (plural) Case.NOM else Case.GEN
            "и" -> if (g == 'n') Case.PRE else if (plural) Case.NOM else Case.GEN
            "у", "ю" -> if (g == 'f') Case.ACC else Case.DAT
            "е" -> if (g == 'n') Case.NOM else if (g == 'f') Case.PRE else Case.PRE
            "ой", "ью", "ом", "ем", "ами", "ями" -> Case.INS
            "ов", "ей", "й" -> Case.GEN
            "ам", "ям" -> Case.DAT
            "ах", "ях" -> Case.PRE
            else -> null
        }
    }

    private fun romanToInt(s: String): Int {
        var result = 0
        for (i in s.indices) {
            val v = romanValues.getValue(s[i])
            val next = s.getOrNull(i + 1)?.let { romanValues[it] } ?: 0
            result += if (v < next) -v else v
        }
        return result
    }

    // Валидный римский токен: строгая грамматика; кириллический — только капсом, иначе предлог «с»
    // после «глава/книга» становится «сотая» (scoped re-review final-fix п.1).
    private fun romanValue(token: String): Int? {
        val lower = token.lowercase()
        val normalized = latinizeRoman(lower)
        if (!romanStrictRe.matches(normalized) || normalized.isEmpty()) return null
        if (normalized != lower && !token.all { it.isUpperCase() }) return null
        return romanToInt(normalized)
    }

    // Одиночный токен без триггеров: числом считаем только заглавный без «m» (review t17 round2
    // п.1, тест «MIX стилей» → «микс стилей»): бытовые слова с «m» в начале («mix», «mid») тоже
    // валидны по строгой грамматике (M+IX=1009), а реальные capslock-числа без триггера на
    // тысячи почти не бывают — годы такого вида читает отдельное правило дат/годов. Токен
    // короче 3 букв тоже не считаем числом (review final-fix п.6): «I love you», «XL», «CD»,
    // «C++» — бытовые сокращения; «Пётр I» ловит romanAfterName(). Двухбуквенные из одних
    // I/V/X («II», «XX») — всё же числа: «Николай II» иначе уходит в Abbrev как «ай +ай».
    private fun romanAlone(token: String): String {
        val value = romanValue(token) ?: return token
        val eligible = (token.length >= 3 || (token.length == 2 && token.all { it in "IVX" })) && token.all { it.isUpperCase() } && 'm' !in latinizeRoman(token.lowercase())
        return if (eligible) value.toString() else token
    }

    private fun romanNumerals(text: String) = romanRe.replace(text) { m ->
        val (prep, before, tok1, conn, tok2, tok3, after) = m.destructured
        val v1 = romanValue(tok1) ?: return@replace m.value
        val v2 = if (tok2.isEmpty()) null else romanValue(tok2)
        val v3 = if (tok3.isEmpty()) null else romanValue(tok3)
        if (before.isEmpty() && after.isEmpty()) {
            // Без триггеров — каждый токен сам по себе (старое поведение), остальной текст как есть.
            val repl = mutableListOf(3 to romanAlone(tok1))
            if (tok2.isNotEmpty()) repl += 5 to romanAlone(tok2)
            if (tok3.isNotEmpty()) repl += 6 to romanAlone(tok3)
            return@replace m.withGroupReplaced(*repl.toTypedArray())
        }
        if (tok2.isNotEmpty() && v2 == null || tok3.isNotEmpty() && v3 == null) return@replace m.value
        val noun = (if (after.isNotEmpty()) after else before).lowercase()
        val stem = romanNounGender.keys.firstOrNull { noun.startsWith(it) }
        val gender = if (noun == "кв.") 'm' else if (noun == "в." || noun == "вв.") 'm' else romanNounGender[stem] ?: 'm'
        val plural = v2 != null && conn.lowercase() !in setOf("по", "до")
        val prepCase = romanPrepCase[prep.lowercase()]
        // Одно число + «вв.» — старое чтение «двадцатые века» (task 28 п.6).
        if (v2 == null && after.lowercase() == "вв." && before.isEmpty()) return@replace "${if (prep.isEmpty()) "" else "$prep "}$v1-ые века"
        val nounCase = if (noun.endsWith(".")) null else romanNounCase(noun, plural)
        val range = v2 != null && conn.lowercase() in setOf("по", "до")
        // «с XVI по XVIII век»: существительное согласуется со вторым числом, первое — по предлогу.
        val case1 = if (range) prepCase ?: Case.GEN else nounCase ?: prepCase ?: Case.NOM
        val case2 = when (conn.lowercase()) { "по" -> nounCase ?: Case.ACC; "до" -> Case.GEN; else -> case1 }
        val suf = ordSuffixByCase.getValue(gender)
        val sb = StringBuilder()
        if (prep.isNotEmpty()) sb.append(prep).append(' ')
        if (before.isNotEmpty()) sb.append(before).append(' ')
        sb.append(v1).append('-').append(suf[case1.ordinal])
        if (v2 != null) sb.append(if (conn == ",") "" else " ").append(conn).append(' ').append(v2).append('-').append(suf[case2.ordinal])
        // Третье число («IV, V и VI») — в том же падеже, что второе; союз между ними берём из текста.
        if (v3 != null) sb.append(m.value.substring(m.groups[5]!!.range.last + 1 - m.range.first, m.groups[6]!!.range.first - m.range.first))
            .append(v3).append('-').append(suf[case2.ordinal])
        if (after.isNotEmpty()) {
            val a = after.lowercase()
            val nounCaseIdx = (if (v2 != null) case2 else case1).ordinal
            val word = when (a) {
                "в." -> centurySg[nounCaseIdx]
                "вв." -> (if (plural) centuryPl else centurySg)[nounCaseIdx]
                "кв." -> quarterSg[nounCaseIdx]
                else -> after
            }
            sb.append(' ').append(word)
        }
        sb.toString()
    }

    // 3b. Римское число после имени с заглавной (task 28 п.2): «Пётр I», «Екатерина II» →
    // порядковое через суффикс для numberRe. Падеж — по окончанию имени, род — по списку женских
    // имён монархов (корпус ru-normalizr): «Людовика XVI» — мужское в родительном («шестнадцатого»),
    // а не женское в именительном. Проход ДО romanNumerals(), иначе тот уже прочитает «XIV»/«II»
    // количественным (eligibleAlone); слова-триггеры («Глава I», «Россия XX века») отдаём старому пути.
    // Только [IVX] — «Размер XL», «Диск CD» остаются бытовыми сокращениями, короли за XX не бывают.
    // Латинское слово следом («Название I Am Legend») — это английская фраза, не номер.
    private val romanAfterNameRe = Regex(
        """(?<![\p{L}])([А-ЯЁ][а-яё]+)\s+([IVX]+)(?:-(?:$ordSuffixAlt))?(?![\p{L}\d])(?!\s+[A-Za-z])(?!\s*(?:и|[-–—,])\s*[IVX]+(?![\p{L}\d]))""")
    // Предлог/союз с заглавной в начале предложения — не имя («За XV и XVI века», «Между XV и XVI»).
    private val notNames = setOf("за", "на", "но", "из", "под", "над", "от", "до", "по", "для", "без", "через", "около",
        "после", "вокруг", "среди", "между", "перед", "при", "про", "обо", "или", "как", "что", "это", "все", "уже", "ещё",
        "еще", "лишь", "даже", "если", "когда", "где", "там", "тут", "так", "ведь", "только", "потом", "затем", "зато")
    private val romanAfterWordRe = Regex("""^\s+(\p{L}+\.?)""")
    private val romanNounBeforeRe = Regex("^$romanNounBeforeAlt$")
    private val femaleStems = setOf("екатерин", "елизавет", "анн", "мари", "виктори", "изабелл", "матильд", "иоанн", "хуан",
        "маргарит", "кристин", "христин", "ульрик", "беатрикс", "вильгельмин", "юлиан", "луиз", "елен", "ирин", "феодор",
        "зо", "клеопатр", "береник", "арсино", "джейн", "софь", "софи", "ядвиг", "констанци", "бланк", "джованн", "тамар",
        "русудан", "мод", "элеонор", "филипп", "урак", "санч", "петронил", "агнесс", "ольг", "александр")
    // «Елизаветы 3-й» — «-й» у женского имени в косвенном падеже: «третьей» (корпус ru-normalizr).
    private val arabicAfterFemaleNameRe = Regex("""(?<![\p{L}])([А-ЯЁ][а-яё]+)\s+(\d+)-й(?![а-яё\d])""")
    private fun romanAfterName(text: String): String {
        val s = arabicAfterFemaleNameRe.replace(text) { m ->
            val (name, num) = m.destructured
            val (female, case) = nameGenderCase(name)
            if (female) "$name $num-${ordSuffixByCase.getValue('f')[case.ordinal]}" else m.value
        }
        return romanAfterNameRe.replace(s) { m ->
            val (name, token) = m.destructured
            val lower = token.lowercase()
            if (!romanStrictRe.matches(lower) || romanNounBeforeRe.matches(name.lowercase()) || name.lowercase() in notNames) return@replace m.value
            // Явный суффикс после числа («VII-го», «II-я») — берём его как есть.
            val explicit = m.value.substringAfter(token, "").removePrefix("-")
            if (explicit.isNotEmpty()) return@replace "$name ${romanToInt(lower)}-$explicit"
            // Следом «века»/«в.»/«вв.» («Европа XIX в.») — отдать romanNumerals(), там это век.
            val next = romanAfterWordRe.find(s.substring(m.range.last + 1))?.groupValues?.get(1)?.lowercase()
            if (next != null && Regex("^$romanNounAfterAlt$", RegexOption.IGNORE_CASE).matches(next)) return@replace m.value
            val (female, case) = nameGenderCase(name)
            "$name ${romanToInt(lower)}-${ordSuffixByCase.getValue(if (female) 'f' else 'm')[case.ordinal]}"
        }
    }

    // Пол и падеж имени по окончанию и списку женских имён монархов.
    private fun nameGenderCase(name: String): Pair<Boolean, Case> {
        val n = name.lowercase()
        val stem = when {
            n.endsWith("ой") || n.endsWith("ей") -> n.dropLast(2)
            n.last() in "аяуюеиы" -> n.dropLast(1)
            else -> n
        }
        val female = stem in femaleStems || (stem.length > 3 && stem.dropLast(1) in femaleStems)
        val case = when {
            n.endsWith("ой") || n.endsWith("ей") -> Case.INS
            n.endsWith("ом") || n.endsWith("ем") -> Case.INS
            n.last() in "ая" -> if (female) Case.NOM else Case.GEN
            n.last() == 'ы' -> Case.GEN
            n.last() in "ую" -> if (female) Case.ACC else Case.DAT
            n.last() in "еи" -> Case.PRE
            else -> Case.NOM
        }
        return female to case
    }

    // 4. Даты «дд.мм.гггг» и «дд.мм»: день и год — порядковый суффикс для numberRe,
    // месяц — сразу словом. Без года цифры трогаем, только если хотя бы одна часть двузначная,
    // иначе это десятичная дробь («3.5»).
    private val monthGenitive = arrayOf("", "января", "февраля", "марта", "апреля", "мая", "июня", "июля",
        "августа", "сентября", "октября", "ноября", "декабря")
    private val monthGenAlt = monthGenitive.drop(1).joinToString("|")
    // Хвост «г.»/«г»/«года» после полной даты съедаем: «15.03.2021 г.» иначе даёт «…года г.».
    private val dateWithYearRe = Regex("""(?<!\d)(\d{1,2})[./](\d{1,2})[./](\d{4})(?!\d)(\s*(?:г\.|г(?![а-яё])|года(?![а-яё])))?""")
    private val sentenceEndAheadRe = Regex("""^\s*(?:$|[А-ЯЁ«"(])""")
    // Лукбехайнд захватывает и точку (review final-fix п.3): «2.10.3» — составной номер раздела
    // (sectionRe ниже), а не «10.3» день.месяц внутри него — без этого фикса regex стартовал бы
    // прямо с «10», не видя, что перед ним уже идёт «2.».
    // После латинского слова или знака операции («iOS 18.4», «9 × 11.01») — дробь, не дата.
    private val dateNoYearRe = Regex("""(?<![\d.])(?<![A-Za-z×*+=÷^−] ?)(\d{1,2})\.(\d{1,2})(?![\d.])""")

    private fun dates(text: String): String {
        val withYear = dateWithYearRe.replace(text) { m ->
            val (d, mo, y, gTail) = m.destructured
            val day = d.toInt(); val month = mo.toInt()
            if (day !in 1..31 || month !in 1..12) return@replace m.value
            val rest = text.substring(m.range.last + 1)
            if (gTail.isEmpty() && dateTailUnitRe.containsMatchIn(rest)) return@replace m.value
            // «г.» перед заглавной/концом текста — ещё и точка предложения, её сохраняем.
            val dot = if (gTail.trim() == "г." && sentenceEndAheadRe.containsMatchIn(rest)) "." else ""
            "$day-го ${monthGenitive[month]} $y-го года$dot"
        }
        return dateNoYearRe.replace(withYear) { m ->
            val (d, mo) = m.destructured
            if (d.length < 2 && mo.length < 2) return@replace m.value
            val day = d.toInt(); val month = mo.toInt()
            if (day !in 1..31 || month !in 1..12) return@replace m.value
            // «N.N» перед единицей измерения — дробь («12.5 км/ч»), не дата без года (review
            // final-fix п.8): unitAltPattern определён ниже по файлу, но здесь это функция, не
            // property-инициализатор — к моменту вызова объект полностью сконструирован.
            if (dateTailUnitRe.containsMatchIn(withYear.substring(m.range.last + 1))) return@replace m.value
            "$day-го ${monthGenitive[month]}"
        }
    }

    // 5. Время «чч:мм[:сс]»: читаем словами, только если рядом есть триггер — предлог перед
    // (в/к/до/с/около/после/на), слово после (утра/дня/вечера/ночи) или сам формат чч:мм:сс.
    // Без триггера «3:16» — это скорее «глава:стих» («Иоанна 3:16»), а не время: оставляем
    // числа по отдельности, их потом читает numberRe (review t17 п.1, Normalizer.kt:176).
    private val timeRe = Regex(
        """(?:(?<![\p{L}])(в|к|до|с|около|после|на)\s+)?(?<!\d)(\d{1,2}):(\d{2})(?::(\d{2}))?(?!\d)""" +
            """(?:\s+(утра|утром|дня|вечера|ночи))?""",
        RegexOption.IGNORE_CASE
    )
    // Падеж по предлогу (task 28 п.1): «с/до/около/после» — родительный, «к» — дательный,
    // «в/на» — винительный = именительный. Формы (ед., 2-4, 5+) на падеж: [час, минута, секунда],
    // в косвенных падежах 2-4 и 5+ совпадают — plural() даёт ед. при n%10==1 && n%100!=11.
    private val timePrepCase = mapOf("с" to Case.GEN, "до" to Case.GEN, "около" to Case.GEN, "после" to Case.GEN, "к" to Case.DAT)
    private val timeNouns = mapOf(
        Case.NOM to listOf(Triple("час", "часа", "часов"), Triple("минута", "минуты", "минут"), Triple("секунда", "секунды", "секунд")),
        Case.GEN to listOf(Triple("часа", "часов", "часов"), Triple("минуты", "минут", "минут"), Triple("секунды", "секунд", "секунд")),
        Case.DAT to listOf(Triple("часу", "часам", "часам"), Triple("минуте", "минутам", "минутам"), Triple("секунде", "секундам", "секундам")),
    )
    private fun timeUnit(n: Int, case: Case, idx: Int) =
        Declension.cardinal(n.toLong(), case, feminine = idx > 0) + " " + plural(n.toLong(), timeNouns.getValue(case)[idx])

    // «в 8.00 утром» — точка вместо двоеточия, время только при слове-триггере следом.
    private val timeDotRe = Regex("""(?<![\d.,])(\d{1,2})\.([0-5]\d)(?=\s+(?:утра|утром|дня|вечера|ночи)(?![а-яё]))""")
    private fun times(text: String) = timeRe.replace(timeDotRe.replace(text, "$1:$2")) { m ->
        val (prep, h, mi, sec, after) = m.destructured
        val hour = h.toInt(); val minute = mi.toInt()
        if (hour !in 0..23 || minute !in 0..59) return@replace m.value
        val second = sec.toIntOrNull()
        if (sec.isNotEmpty() && (second == null || second !in 0..59)) return@replace m.value
        val hasTrigger = prep.isNotEmpty() || after.isNotEmpty() || sec.isNotEmpty()
        val case = timePrepCase[prep.lowercase()] ?: Case.NOM
        val sb = StringBuilder()
        if (prep.isNotEmpty()) sb.append(prep).append(' ')
        if (hasTrigger) {
            sb.append(timeUnit(hour, case, 0))
            if (minute > 0) sb.append(' ').append(timeUnit(minute, case, 1))
            if (second != null && second > 0) sb.append(' ').append(timeUnit(second, case, 2))
        } else {
            sb.append(h).append(' ').append(mi)
        }
        if (after.isNotEmpty()) sb.append(' ').append(after)
        sb.toString()
    }

    // 6. Годы с «г.»/«гг.»: сначала диапазоны и «-х гг.», потом одиночные «г.» с предлогом/без.
    // Граммы против годов. Сильные признаки граммов: слово-продукт в Р.п. следом («500 г муки»),
    // слово веса/тары перед («весом в 300 г», «упаковка 250 г»), знак «=» следом, число больше 2100.
    // Сильные признаки года: предлог года перед числом, «н. э.» следом, точка после «г» при 4 цифрах.
    private val gramsWordAhead = """(?!\s*(?:муки|сахара|масла|воды|мяса|фарша|соли|творога|сыра|теста|крупы|риса|картофеля|продукта|вещества|сметаны|сливок|молока|крахмала|орехов|ягод|овощей|фруктов|рыбы|хлеба|соды|дрожжей|порошка|смеси|раствора|золота|серебра|металла|белка|жира|углеводов|клетчатки|грибов|мёда|меда|сала|мяса|печенья|шоколада|кофе|чая|специй|перца|лука|моркови|капусты|зелени)(?![а-яё]))(?!\s*=)"""
    private val weightWordBehind = """(?<!(?:весом|массой|нетто|упаковка|пачка|банка|порция|доза|навеска|вес|масса)\s{1,3})"""
    // Двузначные тоже («44-45 гг.»), корпус ru-normalizr; перед «н. э.» — и без «гг.» («12500-9500 до н. э.»).
    // «В 300-200 г.» без продукта следом — тоже годы: диапазон граммов пишут без точки.
    private val eraAhead = """(?=\s+(?:до\s+)?(?:н\.\s*э(?:\.|(?![а-яё]))|нашей\s+эры))"""
    private val yearGRangeWithPrepRe =
        Regex("""$weightWordBehind(?<![\p{L}])(в|во)\s+(\d{2,5})\s*[-–—]\s*(\d{2,5})\s*(?:гг\.|годах|г\.$eraAhead|$eraAhead|г\.$gramsWordAhead)""", RegexOption.IGNORE_CASE)
    // «в 1943 и 1951 гг.» → «в …-м и …-м годах»; «с 1991 г. по 2000 г.» → «с …-го по …-й год»,
    // «с 1920 до 1933 г.» → «с …-го до …-го года» (корпус ru-normalizr).
    private val yearPairVRe = Regex("""(?<![\p{L}])(в|во)\s+(\d{4})\s+и\s+(\d{4})\s*гг\.""", RegexOption.IGNORE_CASE)
    private val yearRangeSRe = Regex(
        """(?<![\p{L}])(с|со)\s+(\d{3,5})(?:\s*г\.?(?![а-яё]))?\s+(по|до)\s+(\d{3,5})(?:\s*(?:гг?\.|г(?![а-яё])|года?(?![а-яё])))?""", RegexOption.IGNORE_CASE)
    private val eraAheadRe = Regex("""^\s*(?:до\s+)?н\.\s*э(?:\.|(?![а-яё]))""", RegexOption.IGNORE_CASE)
    // «между 1990 и 2000 гг.» — творительный.
    private val yearPairMezhduRe = Regex("""(?<![\p{L}])между\s+(\d{4})\s+и\s+(\d{4})\s*гг\.""", RegexOption.IGNORE_CASE)
    private val yearGRangeBareRe = Regex("""$weightWordBehind(?<![а-яё\d])(\d{2,5})\s*[-–—]\s*(\d{2,5})\s*(?:гг\.|г\.$eraAhead|$eraAhead|г\.$gramsWordAhead)""", RegexOption.IGNORE_CASE)
    private val yearGXWithPrepRe = Regex("""(?<![\p{L}])(в|во)\s+(\d{2,4})\s*-?\s*х\s*гг\.""", RegexOption.IGNORE_CASE)
    private val yearGXBareRe = Regex("""(?<![а-яё\d-])(\d{2,4})\s*-?\s*х\s*гг\.""", RegexOption.IGNORE_CASE)
    private val yearGLeftoverRe = Regex("""гг\.""", RegexOption.IGNORE_CASE)
    // 1–2 цифры — год только перед «н. э.» («в 79 г. н. э.»).
    private val yearDigits = """(\d{3,4}|\d{1,2}(?=\s*г\.?\s*(?:до\s+)?н\.\s*э))"""
    // После «в»/«к» точка у «г» не обязательна («в 862 г был призван Рюрик»); после «до/около/с» —
    // обязательна: «до 500 г» без контекста — граммы (принятое ограничение).
    private val yearGOptDot = """\s*г(?:\.|(?![а-яё.]))"""
    private val yearGPrepVRe = Regex("""$weightWordBehind(?<![\p{L}])(в|во)\s+$yearDigits$yearGOptDot$gramsWordAhead""", RegexOption.IGNORE_CASE)
    private val yearGPrepKRe = Regex("""(?<![\p{L}])(к|ко)\s+$yearDigits$yearGOptDot$gramsWordAhead""", RegexOption.IGNORE_CASE)
    private val monthsAlt = """январ[ьяе]|феврал[ьяе]|март[ае]?|апрел[ьяе]|ма[йяе]|июн[ьяе]|июл[ьяе]|август[ае]?|сентябр[ьяе]|октябр[ьяе]|ноябр[ьяе]|декабр[ьяе]"""
    private val yearGPrepOtherRe = Regex(
        """(?<![\p{L}])(с начала|с конца|с|до|после|от|около|(?:в\s+)?(?:конце|начале|середине|течение|конца|начала|середины|половине|половины|рубеже)|лет[оа]м?|осен[ьи]ю?|весн[аы]|весной|зим[аыо]й?|(?:в\s+)?(?:$monthsAlt))\s+$yearDigits\s*г\.$gramsWordAhead""",
        RegexOption.IGNORE_CASE)
    // «г.» без предлога — год только при четырёх цифрах (task 28 п.4): «500 г. муки» — граммы,
    // доходит до units(); трёхзначный год — с предлогом («в 988 г.», yearGPrepVRe) или перед «до н. э.».
    private val yearGBareRe =
        Regex("""$weightWordBehind(?<![а-яё\d])(\d{4}|\d{1,3}(?=\s*г\.?\s*(?:до\s+)?н\.\s*э\.))$yearGOptDot$gramsWordAhead""", RegexOption.IGNORE_CASE)
    // Точка после «г.» — ещё и точка предложения, если дальше заглавная или конец текста.
    private fun gDot(m: MatchResult, s: String) = if (m.value.endsWith(".") && sentenceEndAheadRe.containsMatchIn(s.substring(m.range.last + 1))) "." else ""
    // «55 г.р.» — год рождения; «около 50 н. э.», «500 до н. э.» — год и без «г.»: с предлогом
    // родительный, иначе именительный. Диапазоны («300-200 до н. э.») не трогаем.
    private val yearBirthRe = Regex("""(?<![а-яё\d])(\d{1,4})\s*г\.\s*р\.""", RegexOption.IGNORE_CASE)
    private val eraBareRe = Regex(
        """(?:(?<![\p{L}])(около|ок\.|в|во|до|с|от|после|к)\s*)?(?<![\d\-–—]\s?)(?<![\d\-–—])(\d{1,5})\s+(?=(?:до\s+)?н\.\s*э(?:\.|(?![а-яё])))""",
        RegexOption.IGNORE_CASE
    )
    // «41м году», «21ом году» — порядковый без дефиса перед годом: дописываем дефис для numberRe.
    private val bareOrdinalYearRe = Regex("""(?<![\p{L}\d])(\d+)(м|ом|ым)(?=\s+год[ау]?(?![а-яё]))""", RegexOption.IGNORE_CASE)

    // «г»/«гг» без точки после четырёх цифр («2050 г», «1941–1945 гг») — та же аббревиатура,
    // точку просто дописываем и дальше работают правила выше. Только четыре цифры: «500 г муки» — граммы.
    private val yearGNoDotRe = Regex("""(?<![а-яё\d])(\d{4}\s*гг)(?![а-яё.])""", RegexOption.IGNORE_CASE)
    // «20 июня 2050 г.» — полная дата: день порядковый (падеж по предлогу, см. dayMonth), год в родительном.
    private val dayMonthYearGRe =
        Regex("""(?<![\d-])(\d{1,2})(\s+)($monthGenAlt)(\s+)(\d{4})$yearGOptDot""", RegexOption.IGNORE_CASE)

    private val yearInParensRe = Regex("""(?<=[а-яё] ?)\((1[1-9]\d{2}|20\d{2})\)""", RegexOption.IGNORE_CASE)
    private fun yearsWithG(text: String): String {
        // Одинокий год в скобках после слова — «Аустерлицем (1805)», «Джокер (2019)»: порядковое.
        var s = yearInParensRe.replace(text) { m -> "(${m.groupValues[1]}-й)" }
        s = bareOrdinalYearRe.replace(s) { m -> m.groupValues[1] + "-" + m.groupValues[2] }
        s = yearBirthRe.replace(s) { m -> m.groupValues[1] + "-го года рождения" }
        s = yearPairVRe.replace(s) { m -> "${m.groupValues[1]} ${m.groupValues[2]}-м и ${m.groupValues[3]}-м годах" }
        s = yearRangeSRe.replace(s) { m ->
            val (prep, y1, mid, y2) = m.destructured
            // пять цифр — год только перед «н. э.» («с 12500 по 9500 до н. э.»)
            if ((y1.length == 5 || y2.length == 5) && !eraAheadRe.containsMatchIn(s.substring(m.range.last + 1))) return@replace m.value
            if (mid.equals("по", true)) "$prep $y1-го по $y2-й год" else "$prep $y1-го до $y2-го года"
        }
        s = eraBareRe.replace(s) { m ->
            val (prep, y) = m.destructured
            if (prep.isEmpty()) "$y-й год " else "${if (prep.equals("ок.", true)) "около" else prep} $y-го года "
        }
        s = yearGNoDotRe.replace(s) { m -> m.groupValues[1] + "." }
        s = yearPairMezhduRe.replace(s) { m -> "между ${m.groupValues[1]}-ым и ${m.groupValues[2]}-ым годами" }
        s = dayMonthYearGRe.replace(s) { m ->
            val (d, sp1, month, sp2, y) = m.destructured
            if (d.toInt() !in 1..31) return@replace m.value
            "$d-${daySuffix(s.substring(0, m.range.first), withYear = true)}$sp1$month$sp2$y-го года" + gDot(m, s)
        }
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
            "$prep $y-м году" + gDot(m, s)
        }
        s = yearGPrepKRe.replace(s) { m ->
            val (prep, y) = m.destructured
            "$prep $y-му году" + gDot(m, s)
        }
        s = yearGPrepOtherRe.replace(s) { m ->
            val (prep, y) = m.destructured
            "$prep $y-го года" + gDot(m, s)
        }
        s = yearGBareRe.replace(s) { m ->
            val y = m.groupValues[1]
            if (y.length == 4 && y.toInt() > 2100) m.value else "$y-й год" + gDot(m, s)
        }
        return s
    }

    // 6a. День + месяц словом («20 июня», «к 1 сентября») — порядковое среднего рода, падеж по
    // предлогу перед числом: «к» — дательный, «с/до/после/от» — родительный, иначе именительный.
    // Запускается после cases(): там числа перед месяцем нарочно не трогаются (numTailExclude).
    private val dayPrepRe = Regex("""(?<![а-яё])(к|ко|с|со|до|после|от|начиная\s+с|перед)\s+$""", RegexOption.IGNORE_CASE)
    // Дата с годом без предлога («15 марта 2021 года») — родительный: так читается обстоятельство времени.
    private fun daySuffix(before: String, withYear: Boolean = false) = when (dayPrepRe.find(before)?.groupValues?.get(1)?.lowercase()) {
        null -> if (withYear) "го" else "е"
        "к", "ко" -> "му"
        "перед" -> "ым"
        else -> "го"
    }
    private val dayMonthRe = Regex("""(?<![\d-])(\d{1,2})(\s+)($monthGenAlt)(?![а-яё])(?=(\s+\d{4}(?![\d-]))?)""", RegexOption.IGNORE_CASE)
    private fun dayMonth(text: String) = dayMonthRe.replace(text) { m ->
        val (d, sp, month, year) = m.destructured
        if (d.toInt() !in 1..31) return@replace m.value
        "$d-${daySuffix(text.substring(0, m.range.first), year.isNotEmpty())}$sp$month"
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

    // 7b. Голый суффикс «-х» у 2/3/4 (review final-fix п.7): «2-х», «3-х», «4-х» — тот же
    // родительный, что и «-ти/-ух/-ёх» выше, просто другая буква сокращения. Только один разряд
    // (?<!\d перед числом) — «12-х», «90-х», «2000-х» это десятилетия/окончание, не трогаем
    // (numberRe ниже сам читает «-х» как порядковый суффикс для них).
    private val cardinalGenSuffixBareHRe = Regex("""(?<!\d)([234])-х(?![\p{L}\d])""", RegexOption.IGNORE_CASE)
    private fun cardinalGenitiveSuffixBareH(text: String) = cardinalGenSuffixBareHRe.replace(text) { m ->
        genitiveUnits.getValue(m.groupValues[1].toInt())
    }

    // 7d. Сложные прилагательные с числом (корпус ru-normalizr): «5-летний» → «пятилетний»,
    // «2х тонный» → «двухтонный», «30%-ная» → «тридцатипроцентная», «1000-летний» → «тысячелетний».
    // Основа числа — родительный без пробелов (1 → «одно», 90 → «девяносто», 100 → «сто»).
    private val compoundStems = listOf("летн", "тонн", "комнатн", "кратн", "часов", "местн", "процентн", "этажн", "дневн",
        "месячн", "недельн", "минутн", "секундн", "километров", "метров", "миллиметров", "сантиметров", "градусн", "балльн",
        "бальн", "звёздн", "звездн", "дюймов", "литров", "килограммов", "граммов", "мегапиксельн", "ядерн", "цилиндров",
        "струнн", "колёсн", "колесн", "полосн", "разов", "тысячн", "миллионн", "миллиардн", "сотенн", "рублёв", "рублев",
        "долларов", "копеечн", "голов", "ступенчат", "зарядн", "фазн", "канальн", "актн", "томн", "серийн", "значн", "битн",
        "байтов", "тактн", "годичн", "суточн", "палубн", "мачтов", "моторн", "сильн", "струйн", "слойн", "угольн", "гранн",
        "сторонн", "этапн", "частн", "страничн", "мильн", "футов", "пудов", "гектарн", "ваттн", "вольтов", "лошадин", "местечков")
    // Через пробел (без дефиса) — только там, где число почти не бывает самостоятельным («5 тонный»);
    // «5 летних дней», «2 местных жителя» — обычные словосочетания, их не склеиваем.
    private val compoundSpaceStems = setOf("тонн", "этажн", "кратн", "процентн", "ступенчат")
    private val adjEndAlt = """(?:ый|ая|ое|ые|ого|ому|ым|ом|ой|ую|ых|ыми|ий|яя|ее|ие|его|ему|им|ем|ей|юю|их|ими)"""
    private val compoundHyphenRe = Regex(
        """(?<![\p{L}\d])(\d+)(?:-?(?:ти|ух|ех|ёх|х|и))?\s*[-–]\s*(${compoundStems.joinToString("|")})($adjEndAlt)(?![а-яё])""",
        RegexOption.IGNORE_CASE
    )
    private val compoundSpaceRe = Regex(
        """(?<![\p{L}\d])(\d+)(?:-?(?:ти|ух|ех|ёх|х|и))?\s*(${compoundSpaceStems.joinToString("|")})($adjEndAlt)(?![а-яё])""",
        RegexOption.IGNORE_CASE
    )
    // С падежным суффиксом («2х местный», «5-ти местный») пробел не мешает — число уже не самостоятельное.
    private val compoundSuffixSpaceRe = Regex(
        """(?<![\p{L}\d])(\d+)-?(?:ти|ух|ех|ёх|х|и)\s+(${compoundStems.joinToString("|")})($adjEndAlt)(?![а-яё])""",
        RegexOption.IGNORE_CASE
    )
    private val compoundPercentRe = Regex("""(?<![\p{L}\d])(\d+)\s*%-?(н[а-яё]{1,3})(?![а-яё])""", RegexOption.IGNORE_CASE)

    private fun compoundBelow1000(n: Int): String {
        val sb = StringBuilder()
        val h = n / 100; val rest = n % 100
        if (h == 1) sb.append("сто") else if (h > 1) sb.append(Declension.cardinal(h * 100L, Case.GEN))
        when {
            rest == 0 -> {}
            rest == 1 -> sb.append("одно")
            rest == 90 -> sb.append("девяносто")
            rest < 20 || rest % 10 == 0 -> sb.append(Declension.cardinal(rest.toLong(), Case.GEN))
            else -> sb.append(if (rest / 10 == 9) "девяносто" else Declension.cardinal(rest / 10 * 10L, Case.GEN)).append(compoundBelow1000(rest % 10))
        }
        return sb.toString()
    }
    private fun compoundStem(n: Long): String {
        if (n == 0L) return "нуле"
        val sb = StringBuilder()
        val millions = n / 1_000_000; val thousands = n / 1000 % 1000; val rest = (n % 1000).toInt()
        if (millions > 0) sb.append(if (millions == 1L) "" else compoundBelow1000(millions.toInt())).append("миллионо")
        if (thousands > 0) sb.append(if (thousands == 1L) "" else compoundBelow1000(thousands.toInt())).append("тысяче")
        if (rest > 0) sb.append(compoundBelow1000(rest))
        return sb.toString()
    }
    private fun compounds(text: String): String {
        var s = compoundPercentRe.replace(text) { m ->
            val n = m.groupValues[1].toLongOrNull() ?: return@replace m.value
            compoundStem(n) + "процент" + m.groupValues[2]
        }
        for (re in listOf(compoundHyphenRe, compoundSuffixSpaceRe, compoundSpaceRe)) s = re.replace(s) { m ->
            val n = m.groupValues[1].toLongOrNull() ?: return@replace m.value
            compoundStem(n) + m.groupValues[2].lowercase() + m.groupValues[3]
        }
        return s
    }

    // 7e. Арифметика (корпус ru-normalizr): знаки между числами — словами. «-» как «минус» только
    // в выражении со знаком «=», иначе это диапазон («10-15»).
    private val arithOps = mapOf("+" to "плюс", "×" to "умножить на", "*" to "умножить на", "÷" to "разделить на",
        "=" to "равно", "^" to "в степени", "−" to "минус")
    private val arithRe = Regex("""(?<=[\d)])\s*([+×*÷^−])\s*(?=[-−]?[\d(])""")
    // «=» словом только рядом с числом: «x = y» остаётся как есть.
    private val equalsRe = Regex("""(?<=\d)\s*=\s*(?=[-−]?[\p{L}\d(])|(?<=[\p{L}\d)])\s*=\s*(?=[-−]?[\d(])""")
    private val minusBeforeEqualsRe = Regex("""(?<=\d)\s*[-–]\s*(?=\d+(?:[.,]\d+)?\s*=)""")
    private fun arithmetic(text: String): String {
        var s = minusBeforeEqualsRe.replace(text, " минус ")
        s = arithRe.replace(s) { m -> " ${arithOps.getValue(m.groupValues[1])} " }
        return equalsRe.replace(s, " равно ")
    }

    // 8. Единицы измерения: число оставляем цифрами для numberRe (кроме мин/сек — там род
    // важен для согласования, поэтому число сразу произносим словом в женском роде).
    // prefix (task 28 п.6): «квадратный/кубический» перед существительным, формы (1, 2-4/дробь, 5+).
    private class UnitForms(val forms: Triple<String, String, String>, val suffix: String = "", val feminine: Boolean = false,
                            val prefix: Triple<String, String, String>? = null)
    private val squarePrefix = Triple("квадратный", "квадратных", "квадратных")
    private val cubicPrefix = Triple("кубический", "кубических", "кубических")
    // Формы (1, 2-4, 5+) по лемме: «-ь/-й» — мягкое склонение, «-а/-я» — женское, иначе «-а/-ов».
    // Ампер/ватт/вольт/герц/ом в род. мн. — без окончания («пять ампер»), как принято у физиков.
    private val zeroGenPl = listOf("ампер", "ватт", "вольт", "герц", "ом", "бит", "рентген")
    private fun forms(lemma: String, genPl: String? = null): Triple<String, String, String> {
        val stem = lemma.dropLast(1)
        return when {
            lemma.endsWith("ия") -> Triple(lemma, stem + "и", stem + "й")
            lemma.endsWith("я") -> Triple(lemma, stem + "и", stem + "ь")
            lemma.endsWith("а") -> Triple(lemma, stem + (if (stem.last() in "гкхжшчщ") "и" else "ы"), genPl ?: stem)
            lemma.endsWith("ь") -> Triple(lemma, stem + "я", stem + "ей")
            lemma.endsWith("й") -> Triple(lemma, stem + "я", stem + "ев")
            else -> Triple(lemma, lemma + "а", genPl ?: if (zeroGenPl.any { lemma.endsWith(it) }) lemma else lemma + "ов")
        }
    }
    private fun u(lemma: String, suffix: String = "", genPl: String? = null) =
        UnitForms(forms(lemma, genPl), suffix, feminine = lemma.endsWith("а") || lemma.endsWith("я"))
    private fun sq(lemma: String) = UnitForms(forms(lemma), prefix = squarePrefix)
    private fun cu(lemma: String) = UnitForms(forms(lemma), prefix = cubicPrefix)
    // Ключи регистрозависимы там, где это различает единицы (мПа/МПа, Mb/MB, т/Т); для остальных
    // есть строчный фолбэк в unitOf(). Порядок не важен — альтернация строится от длинных к коротким.
    private val unitTable: Map<String, UnitForms> = buildMap {
        // длина/площадь/объём
        for (k in listOf("км²", "км2", "км^2")) put(k, sq("километр"))
        for (k in listOf("м²", "м2", "м^2", "кв.м", "кв. м")) put(k, sq("метр"))
        for (k in listOf("м³", "м3", "м^3", "куб.м", "куб. м")) put(k, cu("метр"))
        for (k in listOf("см²", "см2", "см^2")) put(k, sq("сантиметр"))
        for (k in listOf("см³", "см3", "см^3")) put(k, cu("сантиметр"))
        for (k in listOf("мм²", "мм2", "мм^2")) put(k, sq("миллиметр"))
        for (k in listOf("мм³", "мм3", "мм^3")) put(k, cu("миллиметр"))
        put("км/ч", u("километр", " в час")); put("м/с", u("метр", " в секунду"))
        put("км", u("километр")); put("km", u("километр")); put("м", u("метр")); put("дм", u("дециметр"))
        put("см", u("сантиметр")); put("cm", u("сантиметр")); put("мм", u("миллиметр")); put("mm", u("миллиметр"))
        put("мкм", u("микрометр")); put("нм", u("нанометр")); put("га", u("гектар")); put("ft", u("фут")); put("yd", u("ярд"))
        put("л", u("литр")); put("мл", u("миллилитр")); put("ml", u("миллилитр")); put("мл/мин", u("миллилитр", " в минуту"))
        // масса
        put("кг", u("килограмм")); put("kg", u("килограмм")); put("г", u("грамм")); put("гр", u("грамм")); put("мг", u("миллиграмм"))
        put("г/л", u("грамм", " на литр")); for (k in listOf("г/м²", "г/м2", "г/м^2")) put(k, u("грамм", " на квадратный метр"))
        put("mg", u("миллиграмм")); put("мкг", u("микрограмм")); put("нг", u("нанограмм")); put("Мг", u("мегаграмм")); put("т", u("тонна"))
        put("мг/кг", u("миллиграмм", " на килограмм")); put("мг/мл", u("миллиграмм", " на миллилитр"))
        put("мкг/мл", u("микрограмм", " на миллилитр")); put("мг/л", u("миллиграмм", " на литр")); put("мг/дл", u("миллиграмм", " на децилитр"))
        put("oz", u("унция")); put("lb", u("фунт"))
        // время
        put("ч", u("час")); put("мин", u("минута")); put("сек", u("секунда")); put("мс", u("миллисекунда"))
        put("мкс", u("микросекунда")); put("нс", u("наносекунда")); put("сут", UnitForms(Triple("сутки", "суток", "суток")))
        put("нед", u("неделя")); put("дн", UnitForms(Triple("день", "дня", "дней"))); put("мес", UnitForms(Triple("месяц", "месяца", "месяцев")))
        // счёт
        put("шт", u("штука")); put("чел", UnitForms(Triple("человек", "человека", "человек"))); put("экз", u("экземпляр"))
        put("ед", u("единица")); put("МЕ", UnitForms(Triple("международная единица", "международные единицы", "международных единиц"), feminine = true))
        put("IU", UnitForms(Triple("международная единица", "международные единицы", "международных единиц"), feminine = true))
        put("п.п.", UnitForms(Triple("процентный пункт", "процентных пункта", "процентных пунктов")))
        put("б.п.", UnitForms(Triple("базисный пункт", "базисных пункта", "базисных пунктов")))
        put("л.с.", UnitForms(Triple("лошадиная сила", "лошадиные силы", "лошадиных сил"), feminine = true))
        put("л. с.", UnitForms(Triple("лошадиная сила", "лошадиные силы", "лошадиных сил"), feminine = true))
        put("об/мин", u("оборот", " в минуту")); put("rpm", u("оборот", " в минуту")); put("fps", u("кадр", " в секунду"))
        put("mph", u("миля", " в час")); put("уз", UnitForms(Triple("узел", "узла", "узлов"))); put("рад", u("радиан"))
        put("моль", u("моль")); put("mol", u("моль")); put("ммоль", u("миллимоль")); put("mmol", u("миллимоль"))
        put("ммоль/л", u("миллимоль", " на литр")); put("атм", u("атмосфера")); put("бар", u("бар"))
        put("ккал", u("килокалория")); put("кал", u("калория"))
        // электричество и физика (SI)
        put("В", u("вольт")); put("V", u("вольт")); put("кВ", u("киловольт")); put("kV", u("киловольт")); put("мВ", u("милливольт"))
        put("mV", u("милливольт")); put("МВ", u("мегавольт")); put("MV", u("мегавольт"))
        put("А", u("ампер")); put("мА", u("миллиампер")); put("mA", u("миллиампер")); put("кА", u("килоампер")); put("мкА", u("микроампер"))
        put("мАч", u("миллиампер-час")); put("mAh", u("миллиампер-час")); put("Ач", u("ампер-час")); put("Ah", u("ампер-час"))
        put("Вт", u("ватт")); put("W", u("ватт")); put("кВт", u("киловатт")); put("kW", u("киловатт")); put("мВт", u("милливатт"))
        put("mW", u("милливатт")); put("МВт", u("мегаватт")); put("MW", u("мегаватт")); put("ГВт", u("гигаватт")); put("GW", u("гигаватт"))
        for (k in listOf("кВт·ч", "кВт⋅ч", "кВт-ч", "кВтч", "кВт*ч", "kWh")) put(k, u("киловатт-час"))
        put("Вт·ч", u("ватт-час")); put("Wh", u("ватт-час")); put("МВт·ч", u("мегаватт-час")); put("MWh", u("мегаватт-час"))
        put("Гц", u("герц")); put("Hz", u("герц")); put("кГц", u("килогерц")); put("kHz", u("килогерц")); put("МГц", u("мегагерц"))
        put("MHz", u("мегагерц")); put("мГц", u("миллигерц")); put("ГГц", u("гигагерц")); put("GHz", u("гигагерц"))
        put("Дж", u("джоуль")); put("кДж", u("килоджоуль")); put("МДж", u("мегаджоуль"))
        put("Па", u("паскаль")); put("Pa", u("паскаль")); put("кПа", u("килопаскаль")); put("kPa", u("килопаскаль"))
        put("МПа", u("мегапаскаль")); put("MPa", u("мегапаскаль")); put("мПа", u("миллипаскаль")); put("ГПа", u("гигапаскаль"))
        put("Н", u("ньютон")); put("N", u("ньютон")); put("кН", u("килоньютон")); put("kN", u("килоньютон")); put("МН", u("меганьютон"))
        put("мН", u("миллиньютон")); put("Ом", u("ом")); put("кОм", u("килоом")); put("МОм", u("мегаом")); put("Ω", u("ом"))
        put("Ф", u("фарад")); put("мФ", u("миллифарад")); put("мкФ", u("микрофарад")); put("нФ", u("нанофарад")); put("пФ", u("пикофарад"))
        put("Кл", u("кулон")); put("Тл", u("тесла")); put("Вб", u("вебер")); put("лм", u("люмен")); put("лк", u("люкс"))
        put("Бк", u("беккерель")); put("Гр", u("грей")); put("Зв", u("зиверт")); put("мЗв", u("миллизиверт")); put("мкЗв", u("микрозиверт"))
        // информация
        put("байт", u("байт")); put("бит", u("бит")); put("Кбит", u("килобит")); put("кбит", u("килобит")); put("Мбит", u("мегабит"))
        put("Гбит", u("гигабит")); put("Тбит", u("терабит"))
        for (k in listOf("КБ", "кБ", "Кб", "кб", "kB", "KB", "Кбайт", "кбайт")) put(k, u("килобайт"))
        for (k in listOf("МБ", "мб", "MB", "Мбайт", "мбайт")) put(k, u("мегабайт"))
        for (k in listOf("ГБ", "гб", "GB", "Гбайт", "гбайт")) put(k, u("гигабайт"))
        for (k in listOf("ТБ", "тб", "TB", "Тбайт", "тбайт")) put(k, u("терабайт"))
        put("kb", u("килобит")); put("Mb", u("мегабит")); put("Gb", u("гигабит")); put("Tb", u("терабит"))
        for (k in listOf("кбит/с", "Кбит/с", "kbps", "Kbps", "кб/с")) put(k, u("килобит", " в секунду"))
        for (k in listOf("Мбит/с", "мбит/с", "Mbps", "Mb/s")) put(k, u("мегабит", " в секунду"))
        for (k in listOf("Гбит/с", "Gbps", "Gb/s")) put(k, u("гигабит", " в секунду"))
        put("байт/с", u("байт", " в секунду")); put("бит/с", u("бит", " в секунду"))
        for (k in listOf("КБ/с", "кБ/с", "kBps", "KBps", "kB/s", "KB/s")) put(k, u("килобайт", " в секунду"))
        for (k in listOf("МБ/с", "MBps", "MB/s")) put(k, u("мегабайт", " в секунду"))
        for (k in listOf("ГБ/с", "GBps", "GB/s")) put(k, u("гигабайт", " в секунду"))
        // валюты, которых нет в currency()
        put("в.", u("век"))
        put("₺", u("лира")); put("₩", u("вона")); put("₸", UnitForms(Triple("тенге", "тенге", "тенге")))
        put("₪", u("шекель")); put("₴", u("гривна", genPl = "гривен")); put("₹", u("рупия")); put("£", u("фунт")); put("¥", u("иена"))
        put("¢", u("цент"))
    }
    // «М»/«мкМ» с заглавной — молярность, не метры: не трогаем (корпус ru-normalizr).
    private fun unitOf(key: String): UnitForms? = if (key == "М" || key == "мкМ") null else unitTable[key] ?: unitTable[key.lowercase()]
    private val unitAltPattern = unitTable.keys.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }
    // Единица сразу после «N.N» (review final-fix п.8) — используется в dates() выше, чтобы не
    // принять такую пару за дату без года: «12.5 км/ч» — дробь, читает fractionsSlash()/numberRe.
    private val dateTailUnitRe = Regex("""^\s*(?:$unitAltPattern)(?![\p{L}\d/.])""", RegexOption.IGNORE_CASE)
    // Деньги/единица сразу после группы цифр — для glueThousandsSpace() выше (функция, к вызову
    // объект уже сконструирован, порядок инициализации не мешает).
    private val moneyOrUnitAheadRe = Regex(
        """^ ?(?:[₽$€]|руб\.?|рубл\p{L}*|коп\.?|долл\p{L}*|евро|тыс\.?|млн|млрд|$unitAltPattern)(?![\p{L}\d])""",
        RegexOption.IGNORE_CASE
    )
    // Точка после единицы (review final-fix п.5): перед строчной буквой — часть сокращения («ч.
    // дня»), съедаем; перед заглавной/в конце предложения — точка предложения, оставляем, иначе
    // «см.» без раскрытой единицы попадало в abbrevSimple («смотри»), а Splitter не резал по ней.
    // (?-i: ) вокруг [а-яё] — сам unitRe регистронезависим (IGNORE_CASE), а здесь важен именно
    // регистр следующей буквы (строчная/заглавная), иначе IGNORE_CASE сворачивает его до нуля.
    // Предлог-триггер перед числом (task 28 п.5): и число, и единица — в его падеже («около трёх
    // километров», «к пяти километрам»), иначе cases() склонит только число. «с» не в списке —
    // бывает и родительным, и творительным; «в/на» — винительный = именительный, тоже не нужны.
    private val unitTriggerCase = listOf("около", "более", "менее", "больше", "меньше", "свыше", "до", "от", "из",
        "после", "порядка", "не более", "не менее").associateWith { Case.GEN } +
        mapOf("к" to Case.DAT, "ко" to Case.DAT, "при" to Case.PRE, "о" to Case.PRE, "об" to Case.PRE, "в" to Case.PRE, "во" to Case.PRE)
    // «в 100 г продукта» — предложный, но «весом в 300 г», «в 3 ч дня» — нет: винительный = именительный.
    private val unitInAccBehindRe = Regex("""(?:весом|массой|нетто|упаковка|пачка|банка|порция|доза|объёмом|длиной|высотой|шириной|глубиной|ростом|ценой|стоимостью|мощностью|скоростью|радиусом|диаметром|весит|стоит|размером|толщиной|весу)\s+$""", RegexOption.IGNORE_CASE)
    private val timeUnitKeys = setOf("ч", "мин", "сек", "мс", "мкс", "нс")
    // Винительный перед женской единицей виден только у «1»: «стоит 1 ₺» → «одну лиру», «на 1 мс» → «одну миллисекунду».
    private val unitAccBehindRe = Regex(
        """(?<![\p{L}])(?:на|за|через|про|спустя|сто(?:ит|ил[аио]?|ят)|вес(?:ит|ил[аио]?|ят)|ждал[аио]?|ждать|занял[аио]?|занимает|""" +
        """длит(?:ся|ься)|длил(?:ся|ась|ось)|каждую|всю|целую|ещё|еще)\s+$""", RegexOption.IGNORE_CASE)
    private val unitTriggerAlt = unitTriggerCase.keys.joinToString("|") { it.replace(" ", "\\s+") }
    private val unitRe = Regex(
        """(?:(?<![\p{L}])($unitTriggerAlt)\s+)?(\d+(?:[.,]\d+)?)(?:\s*[-–]\s*(?=\p{L}{2})|\s*)($unitAltPattern)(?![\p{L}\d/])(?!\.\d)(?:\.(?=\s*(?-i:[а-яё,;:])))?""",
        RegexOption.IGNORE_CASE
    )
    // Диапазон с единицей: «между 2 и 5 км» — оба числа и единица в творительном, «с/от 2 до 5 км»
    // — в родительном. До units(): иначе тот прочитает «5 км»/«до 5 км» сам, а cases() потом
    // не увидит второго числа и первое оставит именительным («с два до пяти километров»).
    private val unitRangeRe = Regex(
        """(?<![\p{L}])(между|с|от)\s+(\d+)\s+(и|до)\s+(\d+)\s*($unitAltPattern)(?![\p{L}\d/])""", RegexOption.IGNORE_CASE
    )
    // «до 30 – 40 см» — оба числа диапазона в падеже предлога (корпус ru-normalizr).
    private val unitDashRangeRe = Regex(
        """(?<![\p{L}])(до|от|около|более|менее|свыше|порядка|с)\s+(\d+)\s*([-–—])\s*(\d+)\s*($unitAltPattern)(?![\p{L}\d/])""", RegexOption.IGNORE_CASE
    )
    private fun unitDashRange(text: String) = unitDashRangeRe.replace(text) { m ->
        val (prep, a, dash, b, unitKey) = m.destructured
        val u = unitOf(unitKey) ?: return@replace m.value
        val n1 = a.toLongOrNull() ?: return@replace m.value
        val n2 = b.toLongOrNull() ?: return@replace m.value
        "$prep ${Declension.cardinal(n1, Case.GEN, u.feminine)} $dash ${Declension.cardinal(n2, Case.GEN, u.feminine)} ${unitWord(u, n2, Case.GEN)}"
    }
    private fun unitRange(text: String) = unitRangeRe.replace(text) { m ->
        val (prep, a, mid, b, unitKey) = m.destructured
        val case = when (prep.lowercase() to mid.lowercase()) { "между" to "и" -> Case.INS; "с" to "до", "от" to "до" -> Case.GEN; else -> return@replace m.value }
        val u = unitOf(unitKey) ?: return@replace m.value
        val n1 = a.toLongOrNull() ?: return@replace m.value
        val n2 = b.toLongOrNull() ?: return@replace m.value
        "$prep ${Declension.cardinal(n1, case, u.feminine)} $mid ${Declension.cardinal(n2, case, u.feminine)} ${unitWord(u, n2, case)}"
    }

    // Единица в падеже. И.п. и Р.п. — из forms; Д./Т./П. — по основе: у мужских она равна И.п.
    // ед. («километр»), у женских — без «-а» («минут»); окончания [м. ед., м. мн., ж. ед., ж. мн.].
    // «человек» во мн. ч. косвенных — супплетивная основа «люд-», единственная неправильная в таблице.
    private val obliqueEndings = mapOf(
        Case.DAT to listOf("у", "ам", "е", "ам"), Case.INS to listOf("ом", "ами", "ой", "ами"), Case.PRE to listOf("е", "ах", "е", "ах"))
    private val adjObliqueEndings = mapOf(Case.DAT to ("ому" to "ым"), Case.INS to ("ым" to "ыми"), Case.PRE to ("ом" to "ых"))
    private val irregularPlural = mapOf("человек" to mapOf(Case.DAT to "людям", Case.INS to "людьми", Case.PRE to "людях"))
    private fun unitWord(u: UnitForms, n: Long, case: Case): String {
        val one = n % 10 == 1L && n % 100 != 11L
        val noun = when (case) {
            Case.ACC -> if (one && u.feminine) u.forms.first.dropLast(1) + (if (u.forms.first.endsWith("я")) "ю" else "у") else plural(n, u.forms)
            Case.NOM -> plural(n, u.forms)
            Case.GEN -> if (one) u.forms.second else u.forms.third
            else -> irregularPlural[u.forms.first]?.takeIf { !one }?.getValue(case) ?: run {
                val stem = if (u.feminine) u.forms.first.dropLast(1) else u.forms.first
                stem + obliqueEndings.getValue(case)[(if (one) 0 else 1) + (if (u.feminine) 2 else 0)]
            }
        }
        val pre = u.prefix?.let { p ->
            when (case) {
                Case.NOM, Case.ACC -> plural(n, p)
                Case.GEN -> if (one) p.first.dropLast(2) + "ого" else p.third
                // «кубическ» + «ым» → «кубическим»: после «к» пишется «и».
                else -> adjObliqueEndings.getValue(case).let { (sg, pl) -> (p.first.dropLast(2) + if (one) sg else pl).replace("кы", "ки") }
            } + " "
        }.orEmpty()
        return pre + noun + u.suffix
    }

    // 7c. Ложки «ч. л.»/«ст. л.» (task 28 п.3) — до units(), иначе «2 ч.» уходит в часы, и до
    // fractionsSlash(): дробь (запятая/точка/слэш) остаётся цифрами для них, ложка — в род. ед.
    // Точка после «л» съедается, кроме как перед заглавной (точка предложения, как в unitRe).
    private val spoonForms = mapOf(
        "ч" to Triple("чайная ложка", "чайные ложки", "чайных ложек"),
        "ст" to Triple("столовая ложка", "столовые ложки", "столовых ложек"),
    )
    private val spoonGen = mapOf("ч" to "чайной ложки", "ст" to "столовой ложки")
    private val spoonRe = Regex(
        """((?:\d+/)?\d+(?:[.,]\d+)?)\s*(ч|ст)\.?\s*л(?![\p{L}\d])(?:\.(?!\s*(?-i:[А-ЯЁ])))?""",
        RegexOption.IGNORE_CASE
    )
    private fun spoons(text: String) = spoonRe.replace(text) { m ->
        val (numStr, kind) = m.destructured
        val n = numStr.toLongOrNull()
        if (n == null) numStr + " " + spoonGen.getValue(kind.lowercase())
        else cardinal(n, feminine = true) + " " + plural(n, spoonForms.getValue(kind.lowercase()))
    }

    // «2 мл / мин», «4 кВт · ч» — лишние пробелы вокруг разделителя; «90 км ч», «5 квт ч», «12 об мин» —
    // разделитель потерян вовсе (корпус ru-normalizr): подставляем ключ таблицы, если такой есть.
    // Справа «с» не берём — это предлог («3 м с ветром»), а не секунда.
    private val unitSepSpaceRe = Regex("""(?<=\d ?)(\p{L}{1,5})(?: ?([/·]) ?| )(\p{L}{1,4})(?![\p{L}])""")
    private fun units(text: String) = unitRe.replace(unitSepSpaceRe.replace(text) { m ->
        val (left, sep, right) = m.destructured
        if (sep.isNotEmpty()) return@replace if (unitOf(left + sep + right) != null) left + sep + right else m.value
        if (right.equals("с", true) || right == "c") return@replace m.value
        unitTable.keys.firstOrNull { it.equals("$left/$right", true) || it.equals("$left·$right", true) } ?: m.value
    }) { m ->
        val (trigger, numStr, unitKey) = m.destructured
        val u = unitOf(unitKey) ?: return@replace m.value
        val hasFrac = numStr.contains(',') || numStr.contains('.')
        val head = if (trigger.isEmpty()) "" else "$trigger "
        val inAcc = trigger.lowercase() in setOf("в", "во") &&
            (unitKey in timeUnitKeys || unitInAccBehindRe.containsMatchIn(text.substring(0, m.range.first)))
        if (trigger.isNotEmpty() && !hasFrac && !inAcc) {
            val n = numStr.toLongOrNull() ?: return@replace m.value
            val case = unitTriggerCase.getValue(trigger.lowercase().replace(Regex("\\s+"), " "))
            head + Declension.cardinal(n, case, u.feminine) + " " + unitWord(u, n, case)
        } else if (u.forms.first == "сутки" && !hasFrac) {
            // «сутки» — только мн. ч.: «одни сутки», «двое суток», дальше обычный счёт.
            val n = numStr.toLongOrNull() ?: return@replace m.value
            val one = n % 10 == 1L && n % 100 != 11L
            val few = mapOf(2L to "двое", 3L to "трое", 4L to "четверо")
            (if (one) "одни" else few[n] ?: cardinal(n)) + " " + (if (one) "сутки" else "суток")
        } else if (u.feminine && !hasFrac) {
            val n = numStr.toLongOrNull() ?: return@replace m.value
            val acc = n % 10 == 1L && n % 100 != 11L &&
                (inAcc || trigger.isEmpty() && unitAccBehindRe.containsMatchIn(text.substring(0, m.range.first)))
            if (acc) head + Declension.cardinal(n, Case.ACC, feminine = true) + " " + unitWord(u, n, Case.ACC)
            else head + cardinal(n, feminine = true) + " " + plural(n, u.forms) + u.suffix
        } else {
            val intPart = numStr.substringBefore(',').substringBefore('.')
            val n = intPart.toLongOrNull() ?: return@replace m.value
            val pre = u.prefix?.let { (if (hasFrac) it.second else plural(n, it)) + " " } ?: ""
            val form = if (hasFrac) u.forms.second else plural(n, u.forms)
            head + numStr + " " + pre + form + u.suffix
        }
    }

    // 8b. Градусы (task 18 п.4): число оставляем цифрами для numberRe ниже, сразу дописываем
    // слово. «°C»/«° C»/«°С» (кириллическая «С» тоже) — Цельсия, «°F» — по Фаренгейту,
    // одиночный «°» — просто «градус(а/ов)» по plural().
    private val degreeForms = Triple("градус", "градуса", "градусов")
    private val degreeRe = Regex("""(-?\d+)\s*°\s*(c|f|с)?(?![\p{L}])""", RegexOption.IGNORE_CASE)
    // Предлог перед градусами задаёт падеж слова «градус»: «при -20°C» → «при минус двадцати градусах»
    // (число потом склонит cases() по тому же предлогу).
    private val degreePrepRe = Regex("""(?<![\p{L}])(при|около|до|от|свыше|ниже|выше|к)\s+$""", RegexOption.IGNORE_CASE)
    private val degreeOblique = mapOf(Case.GEN to ("градуса" to "градусов"), Case.DAT to ("градусу" to "градусам"), Case.PRE to ("градусе" to "градусах"))
    private fun degrees(text: String) = degreeRe.replace(text) { m ->
        val (numStr, unit) = m.destructured
        val n = numStr.removePrefix("-").toLongOrNull() ?: return@replace m.value
        val prep = degreePrepRe.find(text.substring(0, m.range.first))?.groupValues?.get(1)?.lowercase()
        val case = when (prep) { null -> null; "при" -> Case.PRE; "к" -> Case.DAT; else -> Case.GEN }
        val noun = degreeOblique[case]?.let { if (n % 10 == 1L && n % 100 != 11L) it.first else it.second } ?: plural(n, degreeForms)
        val suffix = when {
            unit.isEmpty() -> ""
            unit.equals("f", ignoreCase = true) -> " по Фаренгейту"
            else -> " Цельсия"
        }
        "$numStr $noun$suffix"
    }

    // 7b. Число с суффиксом косвенного падежа (-ти/-х/-ми…) сразу перед единицей измерения
    // (review t17 п.4, Normalizer.kt:228-284: «5-ти км» иначе уходит в cardinalGenitiveSuffix
    // раньше unit-регэкспа, и единица остаётся нераскрытой). Суффикс сам по себе падежа не
    // задаёт («пяти» — Р./Д./П.), его берём по предлогу: «в/на/при/о» — предложный («в пяти
    // километрах»), «к» — дательный, без предлога — родительный («пяти километров»).
    private val cardinalGenSuffixUnitRe = Regex(
        """(?:(?<![\p{L}])(в|во|на|при|о|об|к|ко)\s+)?(\d+)-(ти|и|ух|ех|ёх|х|ми)\s*($unitAltPattern)(?![\p{L}\d/.])""",
        RegexOption.IGNORE_CASE
    )
    private fun cardinalGenitiveSuffixUnit(text: String) = cardinalGenSuffixUnitRe.replace(text) { m ->
        val (prep, numStr, _, unitKey) = m.destructured
        val n = numStr.toLongOrNull() ?: return@replace m.value
        val u = unitOf(unitKey) ?: return@replace m.value
        val case = when (prep.lowercase()) { "" -> Case.GEN; "к", "ко" -> Case.DAT; else -> Case.PRE }
        (if (prep.isEmpty()) "" else "$prep ") + Declension.cardinal(n, case, u.feminine) + " " + unitWord(u, n, case)
    }

    // 9. Составные номера разделов «1.2.3» — читаются по частям через «точка».
    // Ровно два числа через точку («1.2») остаются датой/дробью — их не трогаем.
    // Хвост допускает одиночную точку без цифры за ней (review final-fix п.3): «пункт 2.10.3.» в
    // конце предложения — номер целиком, а не отказ от матча из-за точки-конца-предложения.
    private val sectionRe = Regex("""(?<![\d.])(\d+)(\.\d+){2,}(?!\.?\d)""")
    // «GPT-4.5», «Ту-154.2», «модель-3.5» — номер версии после слова с дефисом: «четыре точка пять».
    private val hyphenDecimalRe = Regex("""(?<=\p{L}{2}-)(\d+)\.(\d+)(?![.,]?\d)""")
    // Часть номера с ведущим нулём («2.03.1») — по цифрам: «ноль три».
    private fun sectionPart(p: String) = if (p.length > 1 && p.startsWith("0")) p.map { cardinal((it - '0').toLong()) }.joinToString(" ") else cardinal(p.toLong())
    private fun sectionNumbers(text: String): String {
        val s = hyphenDecimalRe.replace(text) { m -> "${m.groupValues[1]} точка ${m.groupValues[2]}" }
        return sectionRe.replace(s) { m ->
            (if (m.range.first > 0 && s[m.range.first - 1].isLetter()) " " else "") + m.value.split('.').joinToString(" точка ") { sectionPart(it) }
        }
    }

    // 10. Дроби через слэш (task 18 п.5): если знаменатель 2..20 и числитель меньше него —
    // говорим словами («шесть десятых»): числитель — количественное женского рода, знаменатель —
    // порядковое женского рода (a==1 — ед. ч. им. п. «одна вторая», a>1 — мн. ч. род. п.
    // «две третьих»). Числитель больше знаменателя — тоже словами, если знаменатель 2 или круглый
    // («7/2» — «семь вторых», «100/100» — «сто сотых»); «24/7» и «25/3» — как раньше, «a дробь b».
    private val fractionSlashRe = Regex("""(?<![\d/])(\d+)/(\d+)(?![\d/])""")
    // Триггер падежа перед дробью («без 100/100», «равна 5/7») — оба в этом падеже: «пяти седьмым».
    // by lazy: genTriggerAlt объявлен ниже по файлу.
    private val fracCaseBehind by lazy {
        listOf(genTriggerAlt to Case.GEN, datTriggerAlt to Case.DAT).map { (alt, case) ->
            Regex("""(?<![\p{L}-])$alt(?![\p{L}])(?:\s+$amplifierRe(?![\p{L}]))*\s+$""", RegexOption.IGNORE_CASE) to case
        }
    }
    private fun fractionsSlash(text: String) = fractionSlashRe.replace(text) { m ->
        val (a, b) = m.destructured
        val an = a.toIntOrNull(); val bn = b.toIntOrNull()
        if (an != null && bn != null && an >= 1 && (bn in 2..20 && an < bn || bn == 2 || bn == 10 || bn == 100 || bn == 1000 || bn == 10000)) {
            val before = text.substring(0, m.range.first)
            fracCaseBehind.firstOrNull { it.first.containsMatchIn(before) }?.let { (_, case) ->
                return@replace Declension.cardinal(an.toLong(), case, feminine = true) + " " +
                    // «одной тысяче сотых»: круглая тысяча — существительное, знаменатель при ней остаётся в Р.п.
                    ordinal(bn.toLong(), if (an % 10 == 1 && an % 100 != 11) "ой" else if (case == Case.DAT && an % 1000 != 0) "ым" else "х")
            }
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
    private val dollarPrefixRe = Regex("""\$\s?($moneyNumRe)""")
    private val dollarSuffixRe = Regex("""($moneyNumRe)\s?(?:\$|долл\.)""", RegexOption.IGNORE_CASE)
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

    private val scaleAbbrevRe = Regex("""(\d+(?:[.,]\d+)?)\s*(тыс|млн|млрд)\.?(?![\p{L}])""", RegexOption.IGNORE_CASE)
    private val scaleAbbrevForms = mapOf(
        "тыс" to Triple("тысяча", "тысячи", "тысяч"),
        "млн" to Triple("миллион", "миллиона", "миллионов"),
        "млрд" to Triple("миллиард", "миллиарда", "миллиардов"),
    )
    private fun scaleAbbrev(text: String) = scaleAbbrevRe.replace(text) { m ->
        val (numStr, abbrRaw) = m.destructured
        val abbr = abbrRaw.lowercase()
        val forms = scaleAbbrevForms.getValue(abbr)
        // «2,5 млн» — дробь согласуется как 2-4: «миллиона».
        if (numStr.contains(',') || numStr.contains('.')) return@replace numStr + " " + forms.second
        val n = numStr.toLongOrNull() ?: return@replace m.value
        if (abbr == "тыс") cardinal(n, feminine = true) + " " + plural(n, forms)
        else numStr + " " + plural(n, forms)
    }
    // Фолбэки без числа рядом («5 тыс. руб.» — число уже словами) — после scaleAbbrev.
    private val scaleFallback = listOf(
        Regex("""(?<![\p{L}\d])руб\.(?![\p{L}])""", RegexOption.IGNORE_CASE) to "рублей",
        Regex("""(?<![\p{L}\d])долл\.(?![\p{L}])""", RegexOption.IGNORE_CASE) to "долларов",
        Regex("""(?<![\p{L}\d])тыс\.(?![\p{L}])""", RegexOption.IGNORE_CASE) to "тысяч",
        Regex("""(?<![\p{L}\d])млн\.?(?![\p{L}])""", RegexOption.IGNORE_CASE) to "миллионов",
        Regex("""(?<![\p{L}\d])млрд\.?(?![\p{L}])""", RegexOption.IGNORE_CASE) to "миллиардов",
    )
    // Порядок: после cases() — «от 60 до 80 тыс.» сначала склоняет оба числа, потом «тысяч».
    private fun scales(text: String): String {
        var s = scaleAbbrev(text)
        for ((re, rp) in scaleFallback) s = re.replace(s, rp)
        return s
    }

    // «им.» — «имени» только рядом с учреждением или перед именем собственным (следом слово с заглавной
    // и за ним ещё одно с заглавной/знак/конец): иначе «все деньги им. Они откроют» — местоимение.
    private val nameOfPlaces = """(?:школ|завод|институт|университет|театр|библиотек|музе|парк|улиц|площад|фабрик|академи|больниц|клиник|стадион|фонд|преми|училищ|лице|гимнази|колледж|центр|дворц|дворец|проспект|кинотеатр|аэропорт|станци|общеж|дом|сквер|бульвар|набережн)\p{L}{0,12}\s{1,3}"""
    // ({0,12}, {1,3}: lookbehind на Android (ICU) должен быть ограничен по длине, JVM это прощает)
    private val abbrevSimple = listOf(
        Regex("""(?<=$nameOfPlaces)им\.(?![\p{L}])""", RegexOption.IGNORE_CASE) to "имени",
        Regex("""(?<![\p{L}\d])им\.(?=\s+[А-ЯЁ][\p{L}.]*(?:\s+[А-ЯЁ]|\s*[,.;:!?)]|\s*$))""") to "имени",
        // Пары «см. + что»: винительный («смотри главу», «смотри таблицу»).
        Regex("""(?<![\p{L}\d])см\.\s*гл\.(?![\p{L}])""", RegexOption.IGNORE_CASE) to "смотри главу",
        Regex("""(?<![\p{L}\d])см\.\s*табл\.(?![\p{L}])""", RegexOption.IGNORE_CASE) to "смотри таблицу",
        Regex("""(?<![\p{L}\d])см\.\s*стр\.(?![\p{L}])""", RegexOption.IGNORE_CASE) to "смотри страницу",
        // Только после числа/«млн» и строчными: «Л. Н. Толстой» — инициалы.
        Regex("""(?<=(?:\d|млн|млрд|тыс)\.? {1,2})л\.\s*н\.(?![\p{L}])""") to "лет назад",
        Regex("""(?<![\p{L}\d])Т\.\s*(?=\d)""") to "том ",
        Regex("""(?<![\p{L}\d])ст\.\s*(?=\d)""", RegexOption.IGNORE_CASE) to "статья ",
        Regex("""(?<![\p{L}\d])с\.\s*(?=\d)""") to "страница ",
        Regex("""(?<![\p{L}\d])Mr\.(?![\p{L}])""") to "мистер",
        Regex("""(?<![\p{L}\d])Mrs\.(?![\p{L}])""") to "миссис",
        Regex("""(?<![\p{L}\d])Dr\.(?=\s+[A-ZА-ЯЁ])""") to "доктор",
        Regex("""(?<![\p{L}\d])Vol\.\s*(?=\d)""") to "том ",
        Regex("""(?<![\p{L}\d])No\.\s*(?=\d)""") to "номер ",
        Regex("""(?<![\p{L}\d])и\s+т\.\s*д\.(?![\p{L}])""", RegexOption.IGNORE_CASE) to "и так далее",
        Regex("""(?<![\p{L}\d])и\s+т\.\s*п\.(?![\p{L}])""", RegexOption.IGNORE_CASE) to "и тому подобное",
        Regex("""(?<![\p{L}\d])в\s+т\.\s*ч\.(?![\p{L}])""", RegexOption.IGNORE_CASE) to "в том числе",
        Regex("""(?<![\p{L}\d])т\.\s*е\.(?![\p{L}])""", RegexOption.IGNORE_CASE) to "то есть",
        Regex("""(?<![\p{L}\d])т\.\s*к\.(?![\p{L}])""", RegexOption.IGNORE_CASE) to "так как",
        Regex("""(?<![\p{L}\d])т\.\s*н\.(?![\p{L}])""", RegexOption.IGNORE_CASE) to "так называемый",
        Regex("""(?<![\p{L}\d])пп\.(?![\p{L}])""", RegexOption.IGNORE_CASE) to "подпункт",
        // Пробел — часть замены (review final-fix п.12): «п.5» без пробела иначе даёт «пункт5»,
        // сам regex съедает исходный пробел, если он был, чтобы не задвоить его.
        Regex("""(?<![\p{L}\d])п\.\s*(?=\d)""", RegexOption.IGNORE_CASE) to "пункт ",
        // 10. «от лат. homo» — после «от/с/из» форма одна: «от латинского»
        Regex("""(?<![\p{L}\d])(от|с|из)\s+лат\.(?![\p{L}])""", RegexOption.IGNORE_CASE) to "$1 латинского",
        Regex("""(?<![\p{L}\d])(от|с|из)\s+греч\.(?![\p{L}])""", RegexOption.IGNORE_CASE) to "$1 греческого",
        Regex("""(?<![\p{L}\d])стр\.(?![\p{L}])""", RegexOption.IGNORE_CASE) to "страница",
        Regex("""(?<![\p{L}\d])рис\.(?![\p{L}])""", RegexOption.IGNORE_CASE) to "рисунок",
        Regex("""(?<![\p{L}\d])табл\.(?![\p{L}])""", RegexOption.IGNORE_CASE) to "таблица",
        Regex("""(?<![\p{L}\d])гл\.(?![\p{L}])""", RegexOption.IGNORE_CASE) to "глава",
        Regex("""(?<![\p{L}\d])ср\.(?![\p{L}])""", RegexOption.IGNORE_CASE) to "сравни",
        Regex("""(?<![\p{L}\d])св\.(?![\p{L}])""", RegexOption.IGNORE_CASE) to "святой",  // без имени следом (см. saintRe)
        Regex("""(?<![\p{L}\d])см\.(?![\p{L}])""", RegexOption.IGNORE_CASE) to "смотри",
        Regex("""(?<![\p{L}\d])др\.(?![\p{L}])""", RegexOption.IGNORE_CASE) to "другие",
        Regex("""(?<![\p{L}\d])пр\.(?![\p{L}])""", RegexOption.IGNORE_CASE) to "прочее",
        Regex("""(?<![\p{L}\d])напр\.(?![\p{L}])""", RegexOption.IGNORE_CASE) to "например",
        Regex("""(?<![\p{L}\d])проф\.(?![\p{L}])""", RegexOption.IGNORE_CASE) to "профессор",
        Regex("""(?<![\p{L}\d])акад\.(?![\p{L}])""", RegexOption.IGNORE_CASE) to "академик",
        Regex("""(?<![\p{L}\d])ул\.(?![\p{L}])""", RegexOption.IGNORE_CASE) to "улица",
        // task 28 п.6
        Regex("""(?<![\p{L}\d])г-н(?![\p{L}])""", RegexOption.IGNORE_CASE) to "господин",
        Regex("""(?<![\p{L}\d])г-жа(?![\p{L}])""", RegexOption.IGNORE_CASE) to "госпожа",
        Regex("""(?<![\p{L}\d])н\.\s*э\.?(?![\p{L}])""", RegexOption.IGNORE_CASE) to "нашей эры",
        Regex("""(?<![\p{L}\d])т\.\s*о\.(?![\p{L}])""", RegexOption.IGNORE_CASE) to "таким образом",
        Regex("""(?<![\p{L}\d])кол-во(?![\p{L}])""", RegexOption.IGNORE_CASE) to "количество",
        Regex("""(?<![\p{L}\d])ж/д(?![\p{L}])""", RegexOption.IGNORE_CASE) to "железнодорожный",
        Regex("""(?<![\p{L}\d])б/у(?![\p{L}])""", RegexOption.IGNORE_CASE) to "бывший в употреблении",
        // «ок.»/«кв.» только перед числом, пробел — часть замены (как у «п.» выше)
        Regex("""(?<![\p{L}\d])ок\.\s*(?=\d)""", RegexOption.IGNORE_CASE) to "около ",
        Regex("""(?<![\p{L}\d])кв\.\s*(?=\d)""", RegexOption.IGNORE_CASE) to "квартира ",
        Regex("""(?<![\p{L}\d])тел\.(?![\p{L}])""", RegexOption.IGNORE_CASE) to "телефон",
        Regex("""(?<![\p{L}\d])макс\.(?![\p{L}])""", RegexOption.IGNORE_CASE) to "максимум",
    )

    // «Св. Георгия» → «Святого Георгия», «Св. Анны» → «Святой Анны»: род и падеж по имени (nameGenderCase).
    private val saintRe = Regex("""(?<![\p{L}\d])(св\.)\s+([А-ЯЁ][а-яё]+)""", RegexOption.IGNORE_CASE)
    private val saintForms = mapOf(
        'm' to arrayOf("Святой", "Святого", "Святому", "Святого", "Святым", "Святом"),
        'f' to arrayOf("Святая", "Святой", "Святой", "Святую", "Святой", "Святой"))
    // «см. рис. 2 и табл. 3, стр. 4» — всё перечисление после «см.» в винительном: «таблицу три, страницу четыре».
    private val seeListRe = Regex("""(?<![\p{L}])((?:смотри|см\.)\s+)([^!?;\n]*?)(?=[!?;\n]|\.\s+[А-ЯЁ]|\.?\s*$)""", RegexOption.IGNORE_CASE)
    private val seeAccRe = Regex("""(?<![\p{L}\d])(табл|стр|гл)\.(?![\p{L}])""", RegexOption.IGNORE_CASE)
    private val seeAccWords = mapOf("табл" to "таблицу", "стр" to "страницу", "гл" to "главу")
    private fun abbreviations(text: String): String {
        var s = seeListRe.replace(abbrevPrep(text)) { m ->
            m.groupValues[1] + seeAccRe.replace(m.groupValues[2]) { seeAccWords.getValue(it.groupValues[1].lowercase()) }
        }
        s = saintRe.replace(s) { m ->
            val (abbr, name) = m.destructured
            val (female, case) = nameGenderCase(name)
            val word = saintForms.getValue(if (female) 'f' else 'm')[case.ordinal]
            (if (abbr[0].isUpperCase()) word else word.lowercase()) + " " + name
        }
        for ((re, rep) in abbrevSimple) s = re.replace(s, rep)
        return s
    }

    // 11b. Номер главы/части/книги/раздела — порядковое в падеже и роде существительного (корпус
    // ru-normalizr): «Глава 10.» → «глава десятая», «в главе 10» → «десятой», «смотри главу 5» →
    // «пятую». Только одно число до трёх цифр, не диапазон и не составной номер («2.10.3»).
    // Диапазон номеров после существительного во мн. ч. — «В главах 1–3» → «в главах с первой по третью»,
    // «в томах 2–3» → «со второго по третий» (корпус ru-normalizr); род — по romanNounGender.
    private val chapterRangeRe = Regex(
        """(?<![\p{L}])(глав(?:ах|ы)|част(?:ях|и)|книг(?:ах|и)|том(?:ах|а|ы)|раздел(?:ах|ы)|квартал(?:ах|ы)|акт(?:ах|ы))\s+(\d{1,3})\s*[-–—]\s*(\d{1,3})(?![\d:/]|[.,]\d)""",
        RegexOption.IGNORE_CASE
    )
    private val chapterRe = Regex(
        """(?<![\p{L}])(глав(?:а|ы|е|у|ой|ах|ам|ами)?|част(?:ь|и|ью|ей|ях|ям|ями)|книг(?:а|и|е|у|ой|ах|ам|ами)?|раздел(?:а|у|е|ом|ов|ах|ам|ами)?)\s+(\d{1,3})(?!\d|[.,]\d|[:/]|\s*[-–—]?\s*\d|-?(?:$ordSuffixAlt)(?![а-яё\d]))""",
        RegexOption.IGNORE_CASE
    )
    private fun chapterOrdinals(text: String) = chapterRe.replace(chapterRangeRe.replace(text) { m ->
        val (noun, a, b) = m.destructured
        val stem = romanNounGender.keys.firstOrNull { noun.lowercase().startsWith(it) } ?: return@replace m.value
        val suf = ordSuffixByCase.getValue(romanNounGender.getValue(stem))
        val an = a.toLong()
        val prep = if (an % 10 == 2L && an % 100 != 12L) "со" else "с"
        // сразу словами: суффикс «-й» у второго числа иначе переиначил бы femTailRe по слову следом («3-й опубликованы»)
        "$noun $prep ${ordinal(an, suf[Case.GEN.ordinal])} по ${ordinal(b.toLong(), suf[Case.ACC.ordinal])}"
    }) { m ->
        val (noun, num) = m.destructured
        val stem = romanNounGender.keys.firstOrNull { noun.lowercase().startsWith(it) } ?: return@replace m.value
        val case = romanNounCase(noun.lowercase(), plural = false) ?: return@replace m.value
        "$noun $num-${ordSuffixByCase.getValue(romanNounGender.getValue(stem))[case.ordinal]}"
    }

    // 12. Падеж числительного (task 19b): предлог-триггер перед числом или окончание соседнего
    // слова задают падеж — «около 500 рублей» → «около пятисот рублей», «в 5 случаях» →
    // «в пяти случаях». К этому моменту год, дата, время, «N-ти», дробь и процент уже раскрыты
    // предыдущими проходами — «хвост» после числа (numTailExclude) отсекает их снова: суффикс
    // -й/-го/…, дробь/процент через «.,:/», «год/года/году», а также «день + месяц» без точек
    // («к 1 сентября») — дата без разделителя сюда не долетает, порядковое ей строит dayMonth()
    // после cases() по предлогу («к первому сентября»).
    private val amplifierRe =
        """(?:примерно|почти|приблизительно|чем|всего|целых|лишь|ещё|уже|только|каких-то|где-то)"""
    // Общий «хвост»-фильтр без месяца — отдельно нужен там, где месяц как раз обязателен
    // (второе число в «с N по M месяц», см. rangeSPoMonthRe ниже).
    private val numTailExcludeCore =
        """(?![:/\d]|[.,]\d)(?!-?(?:$ordSuffixAlt)(?![а-яё\d]))(?! ?%)"""
    // «год/года» отсекаем только после трёх и более цифр (это год): «более 1 года» — обычный родительный.
    private val numTailExclude = numTailExcludeCore +
        """(?!\s*году(?![а-яё]))(?!(?<=\d{3})\s*(?:год|года)(?![а-яё]))(?!\s*(?:$monthGenAlt)(?![а-яё]))"""

    // «тем более»/«тем менее» — не триггер (lookbehind на «тем »).
    private val genTriggerAlt = """(?:(?<!тем )более|(?<!тем )менее|больше|меньше|свыше|около|порядка|до|""" +
        """из|от|без|у|для|после|кроме|вместо|против|среди|помимо|старше|моложе|выше|ниже|дальше|""" +
        """дороже|дешевле|ранее|позднее|в\s+возрасте|в\s+количестве|в\s+течение|в\s+размере|в\s+районе|""" +
        """на\s+протяжении|в\s+пределах|начиная\s+с|по\s+поводу|относительно|насчёт|касательно|ни|""" +
        // глаголы с родительным — по основе: «достигаем 5 целей», «требует 5 минут», «не хватает 5 рублей»
        """(?:достиг|избег|избеж|требу|требов|ожида|опаса|дожида|хвата|хвати|касае|касал|коснул|коснёт|коснут|""" +
        """бо(?:юсь|ится|ятся|ялся|ялась|ялись|яться|ишься|имся|итесь)|лиши|лишае|лишил|лишат|лишён|лишен|""" +
        """не\s+име|не\s+достига|(?:не)?достаточно|немало)[а-яё]*|""" +
        // существительные с родительным (точные формы, «потерял» не задеть): «потеря 100 долларов» → «ста»
        """потер[яиею]|потерей|утрат[аыеуой]|нехватк[аиеуой]|недостач[аиеуой]|отсутстви[еяию])"""
    // «равно» — не триггер: «два плюс два равно четыре» читается с именительным (корпус ru-normalizr).
    private val datTriggerAlt = """(?:к|ко|благодаря|вопреки|равен|равна)"""
    private val insTriggerAlt = """(?:между|над|перед|по\s+сравнению\s+с|в\s+сравнении\s+с)"""
    private val preTriggerAlt = """(?:о|об|обо|при)"""

    private fun triggerRe(alt: String) = Regex(
        """(?<![\p{L}-])$alt(?![\p{L}])(?:\s+$amplifierRe(?![\p{L}]))*\s+(-?\d+)$numTailExclude""",
        RegexOption.IGNORE_CASE
    )
    private val genTriggerRe = triggerRe(genTriggerAlt)
    private val genFracRe = Regex(
        """(?<![\p{L}-])$genTriggerAlt(?![\p{L}])(?:\s+$amplifierRe(?![\p{L}]))*\s+(-?)(\d+)[.,](\d+)(?![\d,.:/]| ?%)""",
        RegexOption.IGNORE_CASE
    )
    private fun genFraction(minus: String, intPart: String, frac: String): String? {
        val n = intPart.toLongOrNull() ?: return null
        val f = frac.toLongOrNull() ?: return null
        val stem = fracStems.getOrNull(frac.length - 1) ?: return null
        val one = { x: Long -> x % 10 == 1L && x % 100 != 11L }
        return (if (minus.isEmpty()) "" else "минус ") + Declension.cardinal(n, Case.GEN, feminine = true) +
            (if (one(n)) " целой " else " целых ") + Declension.cardinal(f, Case.GEN, feminine = true) + " " + stem + (if (one(f)) "ой" else "ых")
    }
    private val datTriggerRe = triggerRe(datTriggerAlt)
    private val insTriggerRe = triggerRe(insTriggerAlt)
    private val preTriggerRe = triggerRe(preTriggerAlt)

    // По окончанию соседнего слова, без триггера из списка выше (п.2). «-ами/-ями» и неправильное
    // «-ьми» («детьми», «людьми», «лошадьми») — только творительный мн. ч., предлог не обязателен
    // («2 миллионами»). «-ом/-ем/-ой/-ей/-ью» — неоднозначны с родительным мн. ч. («читателей»),
    // поэтому только после «с/со».
    private val insEndingPluralRe = Regex(
        """(?<![\p{L}\d])(\d+)$numTailExclude\s+\p{L}+(?:ами|ями|ьми)(?![а-яё])""",
        RegexOption.IGNORE_CASE
    )
    private val insEndingWithSRe = Regex(
        """(?<![\p{L}])(?:с|со)(?![\p{L}])\s+(\d+)$numTailExclude\s+\p{L}+(?:ом|ем|ой|ей|ью)(?![а-яё])""",
        RegexOption.IGNORE_CASE
    )
    private val preEndingRe = Regex(
        """(?<![\p{L}])(?:в|во|на|при|о|об)(?![\p{L}])\s+(\d+)$numTailExclude\s+\p{L}+(?:ах|ях)(?![а-яё])""",
        RegexOption.IGNORE_CASE
    )
    // Дательный мн. ч. по окончанию «-ам/-ям» («4 сотрудникам», «5 гостям»). Стоп-слова — частые
    // родительные мн. ч. на «-ам» («5 программ», «2 дам»).
    private val datEndingPluralRe = Regex(
        """(?<![\p{L}\d])(\d+)$numTailExclude\s+(\p{L}{3,}(?:ам|ям))(?![а-яё])""",
        RegexOption.IGNORE_CASE
    )
    private val datEndingStop = setOf("дам", "мам", "рам", "драм", "программ", "реклам", "пижам", "панорам", "диаграмм",
        "телеграмм", "гамм", "сумм", "грамм", "килограмм", "миллиграмм", "ям", "хам")
    // «по 1 рублю», «по 21 рублю» — дательный мужского («одному»), не «одну» из accFemEndingRe ниже.
    private val poDatRe = Regex(
        """(?<![\p{L}])по\s+(\d+)$numTailExclude\s+\p{L}+[ую](?![а-яё])""",
        RegexOption.IGNORE_CASE
    )
    // «при 5 %» → «при пяти процентах»: процент читается в конце numberRe, где падежа уже нет.
    private val prePercentRe = Regex("""(?<![\p{L}])(при)\s+(-?\d+) ?%(?!\s*процент)""", RegexOption.IGNORE_CASE)
    // «1у минуту», «21-у тысячу» — просторечный суффикс винительного женского: «одну».
    private val accSuffixURe = Regex("""(?<![\p{L}\d])(\d+)-?у(?![а-яё\d])""")
    // Круглые тысячи на 1 («1000», «21 000») после предлога/глагола с винительным — «одну тысячу»:
    // у остальных чисел винительный совпадает с именительным, а «одна тысяча долларов» после «за» режет ухо.
    private val accThousandRe = Regex(
        """(?<![\p{L}])(по|за|на|в|во|через|про|спустя|сто(?:ит|ил|ила|ило|ят|или)|вес(?:ит|ил|ила|ят|или)|""" +
        """(?:заплат|потрат|получ|заработ|выиграл|проиграл|отда|верну|привез|принес|наш)[а-яё]*|име(?:ть|ет|ем|ю|ешь|ете|ют|л|ла|ли))""" +
        """(?![\p{L}])\s+(\d+)$numTailExclude""",
        RegexOption.IGNORE_CASE
    )
    // «по 1000 рублей» — дательный: «по одной тысяче».
    // Число на 1 (не 11) + слово на -у/-ю → винительный женского рода («1 книгу» → «одну книгу»).
    private val accFemEndingRe = Regex(
        """(?<![\p{L}\d])(\d+)$numTailExclude\s+(\p{L}+[ую])(?![а-яё])""",
        RegexOption.IGNORE_CASE
    )
    // Число на 2 (не 12) + слово на -ы/-и → «две» («2 книги» → «две книги», «2 стола» не трогаем).
    // «-ьми» («детьми») сюда не долетает: insEndingPluralRe выше уже склонил число в творительный.
    // Число на 1 (не 11) + слово на -а/-я → «одна» («1 женщина», «21 минута»), на -о/-е/-мя → «одно»
    // («1 сообщение», «1 время»), на -ь — по SoftSignGender («1 ночь»); идея из ru-normalizr, у них словарь.
    // После «,», «.» и «/» не трогаем («0,1 литра», «1/2 литра»); стоп-слова — мужские на -а и
    // сравнительные/наречия на -е/-о («на 1 больше»).
    private val nomOneEndingRe = Regex(
        """(?<![\p{L}\d,./])(\d+)$numTailExclude\s+(\p{L}{3,}[аяоеь])(?![а-яё])""",
        RegexOption.IGNORE_CASE
    )
    private val nomOneStop = setOf("мужчина", "папа", "дядя", "дедушка", "юноша", "года", "раза", "часа", "дня", "евро", "тенге", "кофе",
        "больше", "меньше", "дальше", "ближе", "выше", "ниже", "раньше", "позже", "дольше", "дороже", "дешевле", "лучше",
        "хуже", "тоже", "также", "только", "почти", "ровно", "около", "вместо", "кроме", "после", "сразу", "много", "мало",
        "снова", "вроде", "давно", "немного", "менее", "более", "чаще", "реже", "легче", "тяжелее", "быстрее", "медленнее")
    private val nomFemTwoEndingRe = Regex(
        """(?<![\p{L}\d])(\d+)$numTailExclude\s+(\p{L}+[ыи])(?![а-яё])""",
        RegexOption.IGNORE_CASE
    )

    // 13. По таблице морфологии (Normalizer.morph; null — только правила по окончаниям ниже). Слово сразу
    // после числа — существительное с известным родом и падежом: «1 ночь» → «одна», «1 такси» → «одно»,
    // «1 конь» → «один», «2 двери» → «две», «1 книгу» → «одну», «1 другу» → «одному», «5 путями» → «пятью»,
    // «в 1 доме» → «одном». Родительный и именительный не трогаем — это обычная конструкция «пять минут»,
    // «два стола». Число из таблицы решено окончательно — до правил по окончаниям оно уже словами;
    // неизвестное слово или общий род («сирота») оставляем им.
    private val morphNounRe = Regex("""(?<![\p{L}\d,./–-])(\d+)$numTailExclude\s+(\p{L}{2,})(?![а-яё])""", RegexOption.IGNORE_CASE)
    private fun morphNoun(s: String, morph: Morph) = morphNounRe.replace(s) { m ->
        val n = m.groupValues[1].toLongOrNull() ?: return@replace m.value
        val word = m.groupValues[2].lowercase()
        val t = morph.tags(word)
        // стоп-слова — расхождения таблицы с узусом («евро» в AOT среднего рода) и «1 года»/«1 раза»
        if (!Morph.isNoun(t) || word in nomOneStop) return@replace m.value
        m.withGroupReplaced(1 to (numeralByNoun(n, t) ?: return@replace m.value))
    }
    // Прилагательное перед числом задаёт падеж вместе с существительным после (task spec-morph п.2):
    // «последних 20-30 лет» → «двадцати-тридцати», «в последних 5 случаях» → «пяти». Падежи прилагательного
    // (мн. ч.; при одушевлённом существительном к родительному добавляем винительный) пересекаем с падежами
    // существительного; одна клетка не именительного/винительного — склоняем. «последние 5 лет» остаётся.
    private val morphAdjRe = Regex(
        """(?<![\p{L}\d-])(\p{L}{3,})\s+(\d+)(?:\s*[-–]\s*(\d+))?$numTailExclude\s+(\p{L}{2,})(?![а-яё])""",
        RegexOption.IGNORE_CASE
    )
    private fun morphAdj(s: String, morph: Morph) = morphAdjRe.replace(s) { m ->
        val ta = morph.tags(m.groupValues[1]); val tn = morph.tags(m.groupValues[4])
        if (!Morph.isAdjective(ta) || !Morph.isNoun(tn)) return@replace m.value
        val adj = Morph.adjCases(ta, null, plural = true).toMutableSet()
        if (Morph.animate(tn) && Case.GEN in adj) adj += Case.ACC
        if (Case.NOM in adj || Case.ACC in adj) return@replace m.value
        val c = (adj intersect Morph.nounCases(tn, plural = true)).singleOrNull() ?: return@replace m.value
        val fem = Morph.gender(tn) == Gender.F
        val n1 = m.groupValues[2].toLongOrNull() ?: return@replace m.value
        val n2 = m.groupValues[3].toLongOrNull()
        if (n2 == null) m.withGroupReplaced(2 to Declension.cardinal(n1, c, fem))
        else m.withGroupReplaced(2 to Declension.cardinal(n1, c, fem), 3 to Declension.cardinal(n2, c, fem))
    }
    /** Род существительного по таблице для правил по окончаниям ниже: они не спорят с ней, когда слово известно. */
    private fun morphGender(word: String): Gender? = morph?.tags(word)?.takeIf { Morph.isNoun(it) }?.let { Morph.gender(it) }

    /**
     * Числительное перед существительным с тегами [t]: по каждой клетке сетки число×падеж — форма числительного,
     * если конструкция грамматична («2 книги» — Р.п. ед. ч. при 2–4, «1 книгу» — В.п. при 1); клетки, невозможные
     * после числа (ед. ч. косвенных при 5, мн. ч. при 1), пропускаем. Одна форма на все разборы — она, иначе null.
     */
    private fun numeralByNoun(n: Long, t: Int): String? {
        val g = Morph.gender(t)
        val one = n % 10 == 1L && n % 100 != 11L
        val few = n % 10 in 2..4 && n % 100 !in 12..14
        val fem = g == Gender.F
        val out = HashSet<String>()
        fun nom() = when (g) {
            Gender.F -> Declension.cardinal(n, Case.NOM, feminine = true)
            Gender.N -> cardinal(n).removeSuffix("один") + "одно"
            else -> cardinal(n)
        }
        val sg = Morph.nounCases(t, plural = false)
        if (one && g == null) return null
        // «1 стол», «1 такси» (несклоняемое — все клетки): форма именительного читается именительным
        if (one && Case.NOM in sg) return nom()
        for (c in sg) when {
            !one -> if (c == Case.GEN && few) out += Declension.cardinal(n, Case.NOM, fem)
            // «1 коня» → «одного»
            c == Case.ACC -> out += if (g == Gender.N) nom() else Declension.cardinal(n, if (Morph.animate(t) && g == Gender.M) Case.GEN else Case.ACC, fem)
            else -> out += Declension.cardinal(n, c, fem)
        }
        // «5 минут» — родительный мн. ч. при 5+ это именительная конструкция; при 2–4 («3 друзей»,
        // «3 рабочих дня») он же омонимичен прилагательному, не трогаем
        if (!one) for (c in Morph.nounCases(t, plural = true)) when (c) {
            Case.NOM, Case.ACC -> {}
            Case.GEN -> if (!few) out += cardinal(n)
            else -> out += Declension.cardinal(n, c, fem)
        }
        return out.singleOrNull()
    }

    // Диапазоны: «между N и M» — оба И.п.; «с N по M» — первое Р.п., второе как есть (кроме
    // месяца — тогда оба порядковые среднего/мужского рода); «с N до M» — оба Р.п.
    private val rangeMezhduRe = Regex(
        """(?<![\p{L}])между(?![\p{L}])\s+(\d+)$numTailExclude\s+и\s+(\d+)$numTailExclude""",
        RegexOption.IGNORE_CASE
    )
    // Между числами допускаем одно слово: «с 10 000 ливров до 1000».
    private val rangeSDoRe = Regex(
        """(?<![\p{L}])с(?![\p{L}])\s+(\d+)$numTailExclude(?:\s+\p{L}+)?\s+до\s+(\d+)$numTailExclude""",
        RegexOption.IGNORE_CASE
    )
    // У второго числа впереди обязателен месяц — общий numTailExclude его как раз отсекает
    // (см. выше), поэтому тут «безмесячный» вариант фильтра.
    private val rangeSPoMonthRe = Regex(
        """(?<![\p{L}])с(?![\p{L}])\s+(\d+)$numTailExclude\s+по\s+(\d+)$numTailExcludeCore\s+($monthGenAlt)(?![а-яё])""",
        RegexOption.IGNORE_CASE
    )
    private val rangeSPoRe = Regex(
        """(?<![\p{L}])с(?![\p{L}])\s+(\d+)$numTailExclude\s+по\s+(\d+)$numTailExclude""",
        RegexOption.IGNORE_CASE
    )

    // Заменяет цифровую группу(-ы) совпадения на готовое слово, остальной текст (предлог,
    // усилители, соединительные слова) остаётся как в исходнике — по смещениям групп.
    private fun MatchResult.withGroupReplaced(vararg replacements: Pair<Int, String>): String {
        var result = value
        for ((idx, repl) in replacements.sortedByDescending { groups[it.first]!!.range.first }) {
            val g = groups[idx]!!
            val start = g.range.first - range.first
            val end = g.range.last - range.first + 1
            result = result.substring(0, start) + repl + result.substring(end)
        }
        return result
    }

    // «от -5 до 3» — минус перед числом после триггера: «от минус пяти».
    private fun applyCase(text: String, re: Regex, case: Case) = re.replace(text) { m ->
        val n = m.groupValues[1].toLongOrNull() ?: return@replace m.value
        m.withGroupReplaced(1 to (if (n < 0) "минус " else "") + Declension.cardinal(kotlin.math.abs(n), case))
    }

    private fun cases(text: String): String {
        var s = text
        s = rangeMezhduRe.replace(s) { m ->
            val n1 = m.groupValues[1].toLongOrNull() ?: return@replace m.value
            val n2 = m.groupValues[2].toLongOrNull() ?: return@replace m.value
            m.withGroupReplaced(1 to Declension.cardinal(n1, Case.INS), 2 to Declension.cardinal(n2, Case.INS))
        }
        // «с N по M месяц» — порядковые (существующий механизм ordinal()), раньше «с N по M» без месяца.
        s = rangeSPoMonthRe.replace(s) { m ->
            val n1 = m.groupValues[1].toLongOrNull() ?: return@replace m.value
            val n2 = m.groupValues[2].toLongOrNull() ?: return@replace m.value
            m.withGroupReplaced(1 to ordinal(n1, "го"), 2 to ordinal(n2, "е"))
        }
        s = rangeSPoRe.replace(s) { m ->
            val n1 = m.groupValues[1].toLongOrNull() ?: return@replace m.value
            m.withGroupReplaced(1 to Declension.cardinal(n1, Case.GEN))
        }
        s = rangeSDoRe.replace(s) { m ->
            val n1 = m.groupValues[1].toLongOrNull() ?: return@replace m.value
            val n2 = m.groupValues[2].toLongOrNull() ?: return@replace m.value
            m.withGroupReplaced(1 to Declension.cardinal(n1, Case.GEN), 2 to Declension.cardinal(n2, Case.GEN))
        }
        // Окончание «-ами/-ьми» сильнее предлога: «больше чем 2 людьми» — творительный.
        s = applyCase(s, insEndingPluralRe, Case.INS)
        s = applyCase(s, genTriggerRe, Case.GEN)
        s = genFracRe.replace(s) { m ->
            val (minus, intPart, frac) = m.destructured
            val words = genFraction(minus, intPart, frac) ?: return@replace m.value
            m.value.substring(0, m.groups[1]!!.range.first - m.range.first) + words
        }
        s = applyCase(s, datTriggerRe, Case.DAT)
        s = applyCase(s, insTriggerRe, Case.INS)
        s = applyCase(s, preTriggerRe, Case.PRE)
        s = applyCase(s, insEndingWithSRe, Case.INS)
        s = applyCase(s, preEndingRe, Case.PRE)
        s = datEndingPluralRe.replace(s) { m ->
            val n = m.groupValues[1].toLongOrNull() ?: return@replace m.value
            if (m.groupValues[2].lowercase() in datEndingStop) return@replace m.value
            m.withGroupReplaced(1 to Declension.cardinal(n, Case.DAT))
        }
        s = poDatRe.replace(s) { m ->
            val n = m.groupValues[1].toLongOrNull() ?: return@replace m.value
            if (n % 10 != 1L || n % 100 == 11L) return@replace m.value
            m.withGroupReplaced(1 to Declension.cardinal(n, Case.DAT))
        }
        s = prePercentRe.replace(s) { m ->
            val n = m.groupValues[2].toLongOrNull() ?: return@replace m.value
            val one = kotlin.math.abs(n) % 10 == 1L && kotlin.math.abs(n) % 100 != 11L
            "${m.groupValues[1]} ${if (n < 0) "минус " else ""}${Declension.cardinal(kotlin.math.abs(n), Case.PRE)} ${if (one) "проценте" else "процентах"}"
        }
        s = accSuffixURe.replace(s) { m ->
            val n = m.groupValues[1].toLongOrNull() ?: return@replace m.value
            if (n % 10 != 1L || n % 100 == 11L) m.value else Declension.cardinal(n, Case.ACC, feminine = true)
        }
        s = accThousandRe.replace(s) { m ->
            val n = m.groupValues[2].toLongOrNull() ?: return@replace m.value
            val k = n / 1000
            if (n % 1000 != 0L || k % 10 != 1L || k % 100 == 11L) return@replace m.value
            val case = if (m.groupValues[1].equals("по", true)) Case.DAT else Case.ACC
            m.withGroupReplaced(2 to Declension.cardinal(n, case, feminine = true))
        }
        morph?.let { s = morphNoun(morphAdj(s, it), it) }
        s = accFemEndingRe.replace(s) { m ->
            val n = m.groupValues[1].toLongOrNull() ?: return@replace m.value
            if (n % 10 != 1L || n % 100 == 11L || morphGender(m.groupValues[2]).let { it == Gender.M || it == Gender.N }) return@replace m.value
            m.withGroupReplaced(1 to Declension.cardinal(n, Case.ACC, feminine = true))
        }
        s = nomOneEndingRe.replace(s) { m ->
            val n = m.groupValues[1].toLongOrNull() ?: return@replace m.value
            val word = m.groupValues[2].lowercase()
            if (n % 10 != 1L || n % 100 == 11L || word in nomOneStop || morphGender(word) == Gender.M) return@replace m.value
            if (word.endsWith("ь") && !SoftSignGender.isFeminine(word)) return@replace m.value
            val neuter = word.endsWith("мя") || word.last() in "ое"
            m.withGroupReplaced(1 to if (neuter) cardinal(n).removeSuffix("один") + "одно" else Declension.cardinal(n, Case.NOM, feminine = true))
        }
        s = nomFemTwoEndingRe.replace(s) { m ->
            val n = m.groupValues[1].toLongOrNull() ?: return@replace m.value
            if (n % 10 != 2L || n % 100 == 12L || morphGender(m.groupValues[2]).let { it == Gender.M || it == Gender.N }) return@replace m.value
            m.withGroupReplaced(1 to Declension.cardinal(n, Case.NOM, feminine = true))
        }
        return s
    }

    // Основы знаменателя десятичной дроби по числу знаков после запятой: «0,000000003» — «три миллиардных».
    // «90 % процентов» — слово уже есть, второй раз не добавляем.
    private val percentWordAheadRe = Regex("""^\s*процент""", RegexOption.IGNORE_CASE)
    private val fracStems = listOf("десят", "сот", "тысячн", "десятитысячн", "стотысячн", "миллионн",
        "десятимиллионн", "стомиллионн", "миллиардн", "десятимиллиардн", "стомиллиардн", "триллионн")

    private val combiningAcuteRe = Regex("""([аеёиоуыэюяАЕЁИОУЫЭЮЯ])\u0301""")
    private val zeroWidthRe = Regex("""[\u200B-\u200D\uFEFF]""")
    private val minusBetweenRe = Regex("""(?<=\d)\s*−\s*(?=\d)""")
    private val tildeRe = Regex("""(?<![\p{L}\d])~\s*(?=\d)""")
    private val kThousandRe = Regex("""(?<=\d)k(?![\p{L}\d])""")
    // «5 - й этаж», «20 - этажный» — суффикс/основа через дефис в пробелах (корпус ru-normalizr).
    private val spacedOrdSuffixRe = Regex("""(\d)\s+[-–]\s+(?=(?:$ordSuffixAlt)(?![а-яё\d]))""")

    // Ссылка читается по частям, как у ru-normalizr: буквы словами (транслит ниже), цифры по одной,
    // разделители названиями; схема «https://» и хвостовая пунктуация не читаются. Правила
    // drop_links / drop_emails выкидывают ссылку или почту целиком.
    // Почта — тем же способом: «mail@example.com» → «мейл собака ексампл точка ком».
    private val urlRe = Regex("""(?:https?://|www\.)[^\s<>«»"']*[^\s<>«»"'.,;:!?)\]}/]|[\w.+-]+@[\w-]+(?:\.[\w-]+)+""", RegexOption.IGNORE_CASE)
    private val urlSeparators = mapOf(':' to "двоеточие", '/' to "слэш", '.' to "точка", '?' to "вопрос", '&' to "амперсанд",
        '=' to "равно", '-' to "дефис", '_' to "нижнее подчёркивание", '#' to "решётка", '%' to "процент", '+' to "плюс",
        '@' to "собака", '~' to "тильда")
    private val urlSingleLetterRe = Regex("""(?<![\p{L}])[a-z](?![\p{L}])""")
    private fun spellUrl(url: String): String {
        val sb = StringBuilder()
        for (c in url.lowercase().removePrefix("https://").removePrefix("http://")) when {
            c.isDigit() -> sb.append(' ').append(cardinal((c - '0').toLong())).append(' ')
            c in urlSeparators -> sb.append(' ').append(urlSeparators[c]).append(' ')
            else -> sb.append(c)
        }
        // одиночная буква («?x=1») — названием, а не транслитом «кс»
        return urlSingleLetterRe.replace(sb, { Abbrev.latLetterNames.getValue(it.value[0].uppercaseChar()) })
            .replace(Regex(" {2,}"), " ").trim()
    }
    // Без read_links адрес остаётся как есть: на время обработки прячем его за плейсхолдером
    // без цифр и букв, чтобы «5Mb» внутри не стало числом, потом возвращаем.
    fun numbers(text: String, rules: Rules = Rules()): String {
        val hasUrl = text.contains("http", ignoreCase = true) || text.contains("www.", ignoreCase = true) || '@' in text
        if (!hasUrl) return numbersInner(text, rules)
        val kept = mutableListOf<String>()
        val masked = urlRe.replace(text) {
            when {
                rules.on(if ('@' in it.value) "drop_emails" else "drop_links") -> ""
                rules.on("read_links") -> spellUrl(it.value)
                else -> { kept += it.value; "\u0001${"\u0002".repeat(kept.size)}\u0001" }
            }
        }
        var out = numbersInner(masked, rules)
        for ((k, url) in kept.withIndex()) out = out.replace("\u0001${"\u0002".repeat(k + 1)}\u0001", url)
        return out
    }

    private val dotThousandsRe = Regex("""(?<![\d.])(?<!\d,)\d{1,3}\.\d{3}\.\d{3}(?![\d.]|,\d)""")

    private fun numbersInner(text: String, rules: Rules): String {
        // Каждый проход — под своим ключом Rules (вкладка «Правила»); выключенный просто пропускаем.
        fun step(key: String, f: (String) -> String): (String) -> String = if (rules.on(key)) f else { t -> t }
        // U+2212 (настоящий знак «минус», не дефис) — приводим к «-», чтобы его читал
        // тот же numberRe, что уже умеет «-5» (task 18 п.4).
        // Комбинированное ударение (U+0301) → «+» перед гласной, как ждёт модель; невидимые
        // соединители (U+200B–U+200D, U+FEFF) выкидываем — иначе слово рвётся на куски.
        var s = combiningAcuteRe.replace(text) { "+" + it.groupValues[1] }.replace(zeroWidthRe, "")
        // «5−2» (настоящий минус между числами) — «минус»; остальные «−» — как дефис для numberRe.
        s = minusBetweenRe.replace(s, " минус ").replace('−', '-')
        s = tildeRe.replace(s, "примерно ")
        s = kThousandRe.replace(s, " тыс.")
        s = spacedOrdSuffixRe.replace(s) { m -> m.groupValues[1] + "-" }
        s = step("thousands", ::glueThousands)(s)
        // NBSP и другие юникод-пробелы нужны glueThousands() в исходном виде (иначе разряды не
        // склеятся) — сразу после нормализуем всё оставшееся в обычный пробел: JVM \s без
        // UNICODE_CHARACTER_CLASS не матчит NBSP, а regex ниже (degreeRe, cityAbbrevRe и т.д.)
        // на него полагаются (review round 1 п.2, общий фикс вместо точечного на каждый regex).
        s = wsClass.replace(s, " ")
        s = step("thousands", ::glueThousandsSpace)(s)
        s = step("thousands") { t -> dotThousandsRe.replace(t) { m -> m.value.replace(".", "") } }(s)
        s = step("footnotes", ::removeFootnotes)(s)
        // Градусы — до римских цифр: одиночная «C» после «°» иначе читается как римское 100
        // (eligibleAlone в romanNumerals не заглядывает влево, task 18 п.4).
        s = step("degrees", ::degrees)(s)
        s = step("roman_name", ::romanAfterName)(s)
        s = step("roman", ::romanNumerals)(s)
        s = step("dates", ::dates)(s)
        s = step("times", ::times)(s)
        s = step("years", ::yearsWithG)(s)
        s = step("abbrev", ::cityAbbrev)(s)
        s = step("units", ::cardinalGenitiveSuffixUnit)(s)
        s = step("gen_suffix", ::cardinalGenitiveSuffix)(s)
        s = step("gen_suffix", ::cardinalGenitiveSuffixBareH)(s)
        s = step("numbers", ::compounds)(s)
        s = step("arith", ::arithmetic)(s)
        s = step("spoons", ::spoons)(s)
        s = step("units", ::unitRange)(s)
        s = step("units", ::unitDashRange)(s)
        s = step("units", ::units)(s)
        s = step("fractions", ::fractionsSlash)(s)
        s = step("currency", ::currency)(s)
        s = step("abbrev", ::abbreviations)(s)
        s = step("numbers", ::chapterOrdinals)(s)
        // после abbreviations: «п. 2.10.3» должно сначала стать «пункт», а уже потом раскрыть номер
        s = step("sections", ::sectionNumbers)(s)
        s = step("cases", ::cases)(s)
        s = step("abbrev", ::scales)(s)
        s = step("latin", ::greek)(s)
        s = step("letter_digit", ::letterAfterDigit)(s)
        s = step("day_month", ::dayMonth)(s)
        if (rules.on("years")) s = yearRe.replace(s) { m ->
            m.groupValues[1] + "-" + yearSuffix.getValue(m.groupValues[3].lowercase()) + m.groupValues[2] + m.groupValues[3]
        }
        if (!rules.on("numbers")) return s
        return numberRe.replace(s) { m ->
            val (mark, minus, intPart, frac, suffix, percent) = m.destructured
            val sb = StringBuilder()
            // Буква вплотную перед числом («N2», «v1») — отделяем пробелом, иначе слово слипается.
            if (m.range.first > 0 && s[m.range.first - 1].isLetter()) sb.append(' ')
            when (mark) { "№" -> sb.append("номер "); "§" -> sb.append("параграф ") }
            if (minus.isNotEmpty()) sb.append("минус ")
            if (intPart.length > 12) {
                sb.append(intPart.map { cardinal((it - '0').toLong()) }.joinToString(" "))
                return@replace sb.toString()
            }
            val n = intPart.toLongOrNull() ?: return@replace m.value
            if (frac.isNotEmpty()) {
                val fracN = frac.toLongOrNull() ?: return@replace m.value
                val stem = fracStems.getOrNull(frac.length - 1) ?: return@replace m.value
                val denom = Triple(stem + "ая", stem + "ых", stem + "ых")
                sb.append(cardinal(n, feminine = true)).append(if (n % 10 == 1L && n % 100 != 11L) " целая " else " целых ")
                sb.append(cardinal(fracN, feminine = true)).append(' ').append(plural(fracN, denom))
            } else if (suffix.isNotEmpty()) {
                val tail = s.substring(m.range.last + 1)
                val sfx = when (suffix.lowercase()) {
                    "й" -> if (ordFem(tail) ?: femTailRe.containsMatchIn(tail)) "ей" else "й"
                    "е" -> if (ordPlural(tail) ?: pluralTailRe.containsMatchIn(tail)) "ые" else "е"
                    else -> suffix.lowercase()
                }
                sb.append(ordinal(n, sfx, isDecadeTail(tail)))
            } else {
                sb.append(cardinal(n))
            }
            // «12,5%» — «процента» (дробь согласуется как 2-4).
            if (percent.isNotEmpty() && !percentWordAheadRe.containsMatchIn(s.substring(m.range.last + 1)))
                sb.append(' ').append(if (frac.isNotEmpty()) "процента" else plural(n, Triple("процент", "процента", "процентов")))
            sb.toString()
        }
    }

    // Длинные сочетания раньше коротких: «igh» до «gh», «tion» до «ti».
    private val digraphs = listOf(
        "tion" to "шн", "sion" to "жн", "sch" to "ш", "tch" to "ч", "igh" to "ай",
        "sh" to "ш", "ch" to "ч", "th" to "з", "ph" to "ф", "wh" to "в", "qu" to "кв",
        "ck" to "к", "oo" to "у", "ee" to "и", "ea" to "и", "ou" to "ау", "ow" to "оу", "ay" to "эй", "ey" to "эй", "ai" to "эй",
        "oa" to "оу", "oy" to "ой", "oi" to "ой", "aw" to "о", "au" to "о", "ie" to "и", "kn" to "н", "wr" to "р",
        "gh" to "", "ng" to "нг", "ew" to "ью",
    )
    private val singles = mapOf('a' to "а", 'b' to "б", 'c' to "к", 'd' to "д", 'e' to "е", 'f' to "ф", 'g' to "г",
        'h' to "х", 'i' to "и", 'j' to "дж", 'k' to "к", 'l' to "л", 'm' to "м", 'n' to "н", 'o' to "о", 'p' to "п",
        'q' to "к", 'r' to "р", 's' to "с", 't' to "т", 'u' to "а", 'v' to "в", 'w' to "в", 'x' to "кс", 'y' to "й",
        'z' to "з")
    // Транслитерация по таблице, не G2P: слова, которые по правилам читаются не так, как принято, —
    // здесь целиком. Пополнять по жалобам, это дешевле любого правила про английские гласные.
    private val wordFixes = mapOf("iphone" to "айфон", "ipad" to "айпад", "ios" to "айос", "macbook" to "макбук",
        "google" to "гугл", "the" to "зэ", "new" to "нью", "york" to "йорк", "queen" to "квин", "photo" to "фото",
        "charlie" to "чарли", "sherlock" to "шерлок", "windows" to "виндовс", "john" to "джон",
        "apple" to "эпл", "microsoft" to "майкрософт", "facebook" to "фейсбук", "youtube" to "ютуб",
        "telegram" to "телеграм", "whatsapp" to "вотсап", "android" to "андроид", "samsung" to "самсунг",
        "twitter" to "твиттер", "instagram" to "инстаграм", "tiktok" to "тикток", "netflix" to "нетфликс",
        "amazon" to "амазон", "tesla" to "тесла", "linux" to "линукс", "python" to "пайтон", "java" to "джава",
        "computer" to "компьютер", "internet" to "интернет", "online" to "онлайн", "offline" to "офлайн",
        "email" to "имейл", "mail" to "мейл", "ok" to "окей", "okay" to "окей", "hello" to "хеллоу", "hi" to "хай",
        "love" to "лав", "one" to "уан", "two" to "ту", "time" to "тайм", "life" to "лайф", "game" to "гейм",
        "wifi" to "вайфай", "bluetooth" to "блютус", "chrome" to "хром", "github" to "гитхаб", "steam" to "стим",
        "xbox" to "иксбокс", "playstation" to "плейстейшн", "nike" to "найк", "adidas" to "адидас", "sony" to "сони",
        "intel" to "интел", "nvidia" to "энвидиа", "audi" to "ауди", "toyota" to "тойота", "coca" to "кока",
        "cola" to "кола", "pepsi" to "пепси", "store" to "стор", "play" to "плей", "cloud" to "клауд",
        "drive" to "драйв", "office" to "офис", "word" to "ворд", "excel" to "эксель", "zoom" to "зум",
        "skype" to "скайп", "viber" to "вайбер", "discord" to "дискорд", "reddit" to "реддит",
        "wikipedia" to "википедия", "yandex" to "яндекс", "sber" to "сбер", "ozon" to "озон",
        "wildberries" to "вайлдберриз", "aliexpress" to "алиэкспресс", "get" to "гет", "give" to "гив",
        "girl" to "гёрл", "begin" to "бегин", "like" to "лайк", "live" to "лайв", "home" to "хоум", "page" to "пейдж",
        // части ссылок
        "www" to "вэ вэ вэ", "ru" to "ру", "com" to "ком", "org" to "орг", "net" to "нет", "io" to "ай оу",
        "html" to "эйч ти эм эл", "php" to "пи эйч пи", "js" to "джи эс", "api" to "эй пи ай", "id" to "ай ди",
        "en" to "эн", "wiki" to "вики", "index" to "индекс", "blog" to "блог", "news" to "ньюс", "watch" to "вотч")
    private val latinWordRe = Regex("[a-z]+")
    private val softVowels = setOf('e', 'i', 'y')

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

    // Одиночная латинская буква вплотную за цифрой или через дефис за русским словом — названием буквы
    // («5800X» → «экс», «5800X3D» → «экс три ди», «GPT-4o» → «оу», «витамин-D» → «ди»), идея из ru-normalizr.
    // «3x4» не трогаем — это умножение.
    private val letterAfterDigitRe = Regex("""(?<=\d|[а-яё]-)(?!x\d)([A-Za-z])(?![\p{L}])""")
    private fun letterAfterDigit(text: String) = letterAfterDigitRe.replace(text) { m ->
        (if (text[m.range.first - 1].isDigit()) " " else "") + Abbrev.latLetterNames.getValue(m.value[0].uppercaseChar()) +
            (if (text.getOrNull(m.range.last + 1)?.isDigit() == true) " " else "")
    }

    // Одиночная греческая буква — названием («угол α» → «угол альфа»); слова греческими не трогаем.
    private val greekNames = mapOf('α' to "альфа", 'β' to "бета", 'γ' to "гамма", 'δ' to "дельта", 'ε' to "эпсилон",
        'ζ' to "дзета", 'η' to "эта", 'θ' to "тета", 'ι' to "йота", 'κ' to "каппа", 'λ' to "лямбда", 'μ' to "мю",
        'ν' to "ню", 'ξ' to "кси", 'π' to "пи", 'ρ' to "ро", 'σ' to "сигма", 'τ' to "тау", 'υ' to "ипсилон",
        'φ' to "фи", 'χ' to "хи", 'ψ' to "пси", 'ω' to "омега", 'Δ' to "дельта", 'Σ' to "сигма", 'Ω' to "омега",
        'Π' to "пи", 'Λ' to "лямбда", 'Γ' to "гамма", 'Θ' to "тета", 'Φ' to "фи", 'Ψ' to "пси")
    private val greekSingleRe = Regex("""(?<![\p{L}])[α-ωΑ-Ω](?![\p{L}])""")
    private fun greek(text: String) = greekSingleRe.replace(text) { m -> greekNames[m.value[0]] ?: m.value }

    // Регистр приводим к нижнему всегда: алфавит модели строчный, symbols() иначе выкинет заглавные.
    private val hasLatinRe = Regex("[A-Za-z]")
    fun latin(text: String, rules: Rules = Rules()): String {
        val lower = text.lowercase()
        // Без латиницы в тексте гонять его через омоглифы и транслит незачем.
        if (!hasLatinRe.containsMatchIn(lower)) return lower
        val fixed = if (rules.on("homoglyphs")) fixHomoglyphs(lower) else lower
        return if (rules.on("latin")) translit(fixed) else fixed
    }

    private fun translit(text: String): String = latinWordRe.replace(text) { m ->
        wordFixes[m.value] ?: run {
            val w = m.value
            val sb = StringBuilder()
            var i = 0
            while (i < w.length) {
                val d = digraphs.firstOrNull { w.startsWith(it.first, i) }
                val c = w[i]; val next = w.getOrNull(i + 1)
                if (d != null) { sb.append(d.second); i += d.first.length }
                // c/g перед e/i/y мягкие («city», «gentle»); «y» на конце слова — «и» («city»).
                else if (c == 'c' && next in softVowels) { sb.append("с"); i++ }
                else if (c == 'g' && next in softVowels) { sb.append("дж"); i++ }
                else if (c == 'y' && next == null && w.length > 1) { sb.append("и"); i++ }
                else { sb.append(singles[c] ?: ""); i++ }
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
        // «±»/«≈»/«&» — словами (task 28 п.6), иначе фильтр allowed их молча выкинет.
        val normalized = text.replace('—', '–').replace('‑', '-').replace("±", " плюс-минус ").replace("≈", " примерно ")
            .replace("&", " и ").replace(wsClass, " ")
        val sb = StringBuilder(normalized.length)
        for (c in normalized) if (c in allowed) sb.append(c)
        return sb.toString().replace(Regex("\\s+"), " ").trim()
    }

    // Регистр НЕ приводим к нижнему здесь: numbers() должен видеть исходный регистр — иначе
    // римские цифры в CAPS-токене («Людовик XIV») теряют признак «весь токен заглавный» ещё
    // до romanNumerals(). latin() лоуэркейсит сам, так что дальше по пайплайну (символы/фильтр)
    // всё как раньше (review t17 round2 п.1).
    // Abbrev до latin(): latin() лоуэркейсит текст, а аббревиатуры узнаются по КАПСУ.
    fun prepare(text: String, allowed: String, rules: Rules = Rules()): String =
        symbols(latin(Abbrev.apply(numbers(punctuation(text, rules), rules), rules), rules), allowed)
}
