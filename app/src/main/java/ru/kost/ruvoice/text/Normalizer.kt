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
    // (ед., 2-4, 5+), род; от квинтиллиона (10^18) — весь диапазон Long: без старших разрядов число
    // от триллиона («1 026 000 000 000 килограммов») давало below1000 больше 999 и падало на hundreds[10]
    private val scales = listOf(
        Triple("квинтиллион", "квинтиллиона", "квинтиллионов") to false,
        Triple("квадриллион", "квадриллиона", "квадриллионов") to false,
        Triple("триллион", "триллиона", "триллионов") to false,
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
        var scale = 1_000_000_000_000_000_000L
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
        // пятизначные круглые тысячи («до 13000 г. до н. э.» — «тринадцатитысячного») — родительный + «тысячн»:
        // «двадцатиоднотысячный», «девяностотысячный»
        (roundThousandStems[n] ?: if (n % 1000 == 0L && n in 11000L..99000L) Declension.cardinal(n / 1000, Case.GEN)
            .replace(" ", "").replace("девяноста", "девяносто").replace(Regex("одного$"), "одно") + "тысячн"
            else null)?.let { stem ->
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
    // виде («...», ". . .») — к «…», дефис/минус в пробелах (или в начале реплики «- Привет»)
    // — к тире, несколько тире подряд — к одному.
    // Смесь знаков («?!», «!?», «?!?») — не шум, а вопрос с восклицанием: сводим к «?!» и оба
    // знака отдаём модели. Оба есть в её алфавите, и на «что?!» против «что?» (wh_q, xenia,
    // 22.09.2026) громкость выше в полтора раза, подъём F0 к концу 222 против 203 Гц — это и есть
    // акцент, который терялся, когда от «?!» оставался один знак. Порядок знаков важен: у «что!?»
    // модель роняет хвост до 130 Гц, а у пака cis «!» не в алфавите и остаётся чистый вопрос.
    private val multiExclQuestRe = Regex("[!?]{2,}")
    private val ellipsisRe = Regex("""\.(?: ?\.){2,}""")
    private val spacedDashRe = Regex("""(?<=^|[ ])[-−](?=[ ])""")
    private val spacedHyphenRe = Regex("""(?<=^|[ ])-(?=[ ])""")
    private val multiDashRe = Regex("[–—]{2,}")
    // Интерфейсные знаки, которые TalkBack шлёт из подписей: «замок · Системный» — пауза как
    // у запятой, ««21» → «двадцать один»» — как у тире; иначе фильтр их съедает и слова слипаются.
    // Не перед коротким словом: «4 кВт · ч» — единица, её читает units().
    private val midDotRe = Regex("""\s+[·•]\s+(?!\p{L}{1,3}(?!\p{L}))""")
    private val arrowRe = Regex("""\s*[→⟶⇒➔]\s*""")
    // Стрелка из символов — «Проверка -> кнопка», «Меню >> Настройки», «=>» — та же стрелка «→»: пауза, а при
    // symbol_names — «стрелка вправо» (было «дефис больше», «больше больше»). «-->» и «==>» тоже.
    // Пробел после знака, забытый при наборе: «вопрос.Присутствует», «книги?Не», «г.Москва», «т.к.Он», «сделал,которая».
    // Без него предложение не отделялось (пауза и интонация конца терялись), «г.Москва» не раскрывалось («городмосква»),
    // ударения считались по склейке двух слов. Точка — только перед русской заглавной («будет.У нас», «А.С.Пушкин»; «IP.Board»,
    // «site.ru» не трогаем); запятая — только между буквами («3,5» — число).
    private val missingSpaceStopRe = Regex("""(?<=\p{L})([.!?…])(?=[А-ЯЁ])""")
    private val ellipsisGlueRe = Regex("""(?<=\p{L})…(?=\p{L})""")
    private val missingSpaceCommaRe = Regex("""(?<=\p{L}),(?=\p{L})""")
    private val asciiArrowRe = Regex("""(?<![-=<>])(?:-+>|=+>|>>)(?![>=])""")
    // Черта из знаков — подпись на форуме («--------------------»), разделитель в тексте («=====», «_____», «***»):
    // пауза как у тире. Раньше дефисы уходили в модель как есть, а при symbol_names «*» звучала двадцать раз.
    private val ruleLineRe = Regex("""(?<![\p{L}\d])([-_=*~#])\1{2,}(?![\p{L}\d])""")
    /** [f] — только к кускам текста вне адресов ([urlRe]); сами адреса как были. */
    private fun outsideUrls(s: String, f: (String) -> String): String {
        if (!hasUrl(s)) return f(s)
        val sb = StringBuilder(); var at = 0
        for (m in urlRe.findAll(s)) { sb.append(f(s.substring(at, m.range.first))).append(m.value); at = m.range.last + 1 }
        return sb.append(f(s.substring(at))).toString()
    }
    fun hasUrl(s: String) = s.contains("http", ignoreCase = true) || s.contains("www.", ignoreCase = true) || '@' in s ||
        ('/' in s && '.' in s) // сайт без схемы: «habr.com/ru/…», путь к файлу
    // Перенос слова в конце строки книги: «Гра-\nфическим» → «Графическим» (было «Гра- фическим»). Частицы
    // и приставки с дефисом («кто-\nто», «кое-\nкак», «из-\nза») — дефис остаётся, без переноса строки.
    private val lineHyphenRe = Regex("""(\p{L}+)-[ \t]*\r?\n[ \t]*(\p{Ll}\p{L}*)""")
    private val hyphenParticles = setOf("то", "либо", "нибудь", "ка", "таки", "де", "кась")
    private val hyphenPrefixes = setOf("кое", "кой", "из", "по", "во", "в")
    // Разрядка подчёркиваниями: «в_н_е в_с_я_к_о_г_о» → «вне всякого»
    private val spacedLettersRe = Regex("""(?<![\p{L}_])\p{L}(?:_\p{L}){2,}(?![\p{L}_])""")
    /** [mathMinus] — минус «−» в пробелах оставить знаком ([TextLayer.mathMinus]), тире делать только из дефиса. */
    fun punctuation(text: String, rules: Rules = Rules(), mathMinus: Boolean = false): String {
        if (!rules.on("punct")) return text
        var s = if ('\n' in text) lineHyphenRe.replace(text) { m ->
            val (a, b) = m.destructured
            // «северо-\nзападное», «генерал-\nмайор» — вторая часть сама слово, склейка — нет: дефис остаётся
            val compound = morph?.let { mo -> mo.tags(b.lowercase()) != 0 && mo.tags((a + b).lowercase()) == 0 } == true
            if (a.lowercase() in hyphenPrefixes || b.lowercase() in hyphenParticles || compound) "$a-$b" else a + b
        } else text
        if ('_' in s) s = spacedLettersRe.replace(s) { it.value.replace("_", "") }
        s = multiExclQuestRe.replace(s) { if ('?' in it.value && '!' in it.value) "?!" else it.value.first().toString() }
        s = ellipsisRe.replace(s, "…")
        // внутри адреса не трогаем: «?ids=1,2&sort=asc,desc» с пробелом после запятой рвался, хвост читался отдельно
        s = outsideUrls(s) { missingSpaceStopRe.replace(it, "$1 ") }
        // «Ну…ладно» — многоточие вплотную к любой букве: без пробела слово после него шло без ударения и паузы
        s = ellipsisGlueRe.replace(s, "… ")
        s = outsideUrls(s) { missingSpaceCommaRe.replace(it, ", ") }
        s = asciiArrowRe.replace(s, " → ")
        // черта в начале или в конце фразы — ни о чём, только висячее тире; между словами — пауза
        s = ruleLineRe.replace(s) { m ->
            val before = s.substring(0, m.range.first)
            // у края, среди одних значков или сразу после знака препинания — пауза уже есть
            val edge = before.none { it.isLetterOrDigit() } || s.substring(m.range.last + 1).none { it.isLetterOrDigit() } ||
                before.trimEnd().lastOrNull()?.let { it in ".!?…,;:" } == true
            if (edge) " " else " – "
        }
        s = (if (mathMinus) spacedHyphenRe else spacedDashRe).replace(s, "–")
        s = multiDashRe.replace(s, "–")
        // с symbol_names знаки называются по имени в symbols(), паузой их не подменяем
        if (!rules.on("symbol_names")) s = symbolPauses(s)
        return s
    }

    /** «·» и «→» между словами — пауза ([midDotRe], [arrowRe]). Белорусский слой знаки по имени не называет: у него всегда. */
    fun symbolPauses(s: String) = arrowRe.replace(midDotRe.replace(s, ", "), " – ")

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
    private val ordChainTailRe = Regex("""^(?:\s*(?:,|\s+и|\s+или)\s+\d+-?[а-яё]{1,3}(?=[\s,]))+""")
    private val monthAheadRe by lazy { Regex("""^\s+(?:$monthGenAlt)(?![а-яё])""", RegexOption.IGNORE_CASE) }
    // То же по таблице морфологии, когда она есть и слово следом — существительное: «-й» → «-ей» у женского
    // рода («3-й дивизии»), «-е» → «-ые», если у слова есть только мн. ч. («2-е ножницы», «2000-е годы»).
    // null — слова нет в таблице или род общий, тогда решают регулярки выше.
    private val tailWordRe = Regex("""^\s+(\p{L}{2,})(?![а-яё])""")
    private fun tailNoun(tail: String): Int? {
        val word = tailWordRe.find(tail)?.groupValues?.get(1) ?: return null
        return morph?.tags(word)?.takeIf { Morph.isNoun(it) }
    }
    /** Следом существительное в родительном мн. ч. — то, что считают («до 2000 рублей», «с 1500 до 2000 человек»). */
    private fun countedNounAhead(tail: String): Boolean =
        tailNoun(tail)?.let { it != 0 && Case.GEN in Morph.nounCases(it, plural = true) } == true
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
    // «-м» — предложный («в 1990-м году», «на 2-м этаже») или творительный/дательный («с 2023-м годом», «к 1990-м
    // годам», «между 1991-м и 1993-м годами», «был 2-м после лидера»): по окончанию существительного следом,
    // без него — по предлогу (было всегда «-ом»: «с две тысячи двадцать третьем годом»).
    private val ordMNounEndRe = Regex("""^\s+(?:\p{L}+\s+(?:и|или)\s+\d+-?м\s+)?(\p{L}{2,})(?![а-яё])""")
    // предлог и через слово: «при Людовике X» → «при Людовике 10-м» (romanAfterName)
    private val ordMPrepRe = Regex("""(?<![\p{L}])(в|во|на|о|об|при)\s+(?:\p{L}+\s+)?$""", RegexOption.IGNORE_CASE)
    private val ordMNotNoun = setOf("после", "перед", "среди", "из", "в", "на", "по", "и", "или", "место", "раз", "уже", "же", "не")
    private fun ordMSuffix(before: String, tail: String): String {
        val w = (ordMNounEndRe.find(tail) ?: tailWordRe.find(tail))?.groupValues?.get(1)?.lowercase()
        val t = w?.let { morph?.tags(it) }
        val noun = w != null && w !in ordMNotNoun && (t == null || Morph.isNoun(t))
        if (noun) when {
            // мн. ч. существительного при одном-двух номерах — каждый номер в ед. ч.: «между 1991-м и 1993-м годами»
            w!!.endsWith("ами") || w.endsWith("ями") -> return "ым"
            w.endsWith("ах") || w.endsWith("ях") -> return "м"
            w.endsWith("ам") || w.endsWith("ям") -> return "ым"
            w.endsWith("ом") || w.endsWith("ём") || w.endsWith("ем") || w.endsWith("ой") || w.endsWith("ей") || w.endsWith("ью") -> return "ым"
            w.last() in "еиую" -> return "м"
        }
        return if (ordMPrepRe.containsMatchIn(before)) "м" else "ым"
    }
    private fun isDecadeTail(tail: String): Boolean {
        if (decadeYearWordRe.containsMatchIn(tail)) return true
        val t = tail.trimStart(' ', '\t')
        return t.isEmpty() || !t[0].isLetter()
    }

    // «в 1917 году» → порядковое: год → -й, года → -го, году → -м
    // Одно-двузначное — только перед «году» («в 21 году»): «2 года» — срок, не год.
    private val yearRe = Regex("""(?<![\d-])(\d{3,4}|\d{1,2}(?=\s+году))(\s+)(годом|году|года|год)(?![а-яё])""", RegexOption.IGNORE_CASE)
    private val yearSuffix = mapOf("год" to "й", "года" to "го", "году" to "м", "годом" to "ым")

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
    // Цепочка целиком («8 000 000 000»): попарная склейка в цикле съедала цифру соседней группы
    // и давала «8000 000000» → «восемь тысяч ноль».
    private val thousandsSpaceRe = Regex("""(?<!\d)(\d{1,3})((?: \d{3})+)(?!\d)""")
    // слэш в пробелах с обеих сторон: «i7 920/3.5 GHz», «1/2 300 рублей» — не разряды
    private val slashAheadRe = Regex("""^ / \d""")
    private val slashBehindRe = Regex("""\d / $""")
    private fun glueThousandsSpace(text: String) = thousandsSpaceRe.replace(text) { m ->
        val groups = m.groupValues[2]
        val tail = text.substring(m.range.last + 1)
        // группа с ведущим нулём («14 033», «1 050») отдельным числом не бывает — это разряды
        // «с 42 500 до 47 000 руб.» — деньги в конце диапазона относятся и к первому числу
        // «4 820 / 11 000» — дробь или счёт, не два числа (жалоба 28.10.2026)
        if (groups.length > 4 || groups == " 000" || groups[1] == '0' || moneyOrUnitAheadRe.containsMatchIn(tail) ||
            slashAheadRe.containsMatchIn(tail) || slashBehindRe.containsMatchIn(text.substring(0, m.range.first)) ||
            // «1 249,90 руб.» — копейки, потом деньги
            fracMoneyAheadRe.find(tail)?.let { moneyOrUnitAheadRe.containsMatchIn(tail.substring(it.range.last + 1)) } == true ||
            rangeMoneyAheadRe.find(tail)?.let { moneyOrUnitAheadRe.containsMatchIn(tail.substring(it.range.last + 1)) } == true)
            m.groupValues[1] + groups.replace(" ", "")
        else m.value
    }

    private val fracMoneyAheadRe = Regex("""^[.,]\d{1,2}""")
    private val rangeMoneyAheadRe = Regex("""^\s*(?:до|[-–—])\s*\d{1,3}(?: \d{3})*(?:[.,]\d+)?""")

    // 2. Сноски вида «[1]» — вырезаются, двойной пробел на их месте схлопывается.
    // и «{5}»; номер сноски вплотную к слову: «исследователи2.», «Печорою19,», «пинежане⁸», «Государство¹»,
    // «инструмента(12)» — не число в тексте (было «исследователи два»). Слово — от трёх строчных букв, следом знак
    // или пробел; степень у единицы («м²», «км³») короче и не трогается, «#Наука2025» — хештег.
    private val footnoteRe = Regex("""\[\d+\]|\{\d+\}""")
    // Цифры вплотную перед пробелом — сноска только у слова на гласную мн. ч./твор. п. («пинежане8 и»): «часа3 или
    // четыре», «том3», «Аполлон11» — опечатка или название, число остаётся.
    private val gluedFootnoteRe = Regex("""(?<=(?<![#@\p{L}])\p{L}?[а-яё]{3,40})(?:(\d{1,3})(?=[.,;:!?)»…—–]|$)|(?<=[еиюя])(\d{1,2})(?=\s)|[¹²³⁴⁵⁶⁷⁸⁹⁰]{1,3}(?=[\s.,;:!?)»…—–]|$)|\(\d{1,3}\)(?=[\s.,;:!?)»…—–]|$))""")
    private val doubleSpaces = Regex(" {2,}")
    private fun removeFootnotes(text: String) = gluedFootnoteRe.replace(footnoteRe.replace(text, ""), "").replace(doubleSpaces, " ")

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
    // «в начале IX в.», «к середине XIX в.», «конец XVI» — век при этих словах в родительном (было «в девятом веке»)
    private val romanPrepCase = listOf("в", "во", "о", "об", "при").associateWith { Case.PRE } +
        listOf("с", "со", "от", "до", "из", "после", "около", "для", "начала", "середины", "конца", "рубежа", "половины", "течение",
            "начале", "середине", "конце", "рубеже", "половине", "протяжении", "начало", "середина", "конец", "половина", "рубеж").associateWith { Case.GEN } +
        mapOf("к" to Case.DAT, "ко" to Case.DAT, "между" to Case.INS, "перед" to Case.INS)
    // «на/за/через/про» — только для винительного у «часть/том» (см. accPrep), своего падежа в таблице нет
    private val romanPrepAlt = (romanPrepCase.keys + listOf("на", "за", "через", "про")).joinToString("|")
    // Существительные-триггеры: основа → род (m/f/n); формы ловим по основе + окончанию.
    private val romanNounGender = mapOf("глав" to 'f', "част" to 'f', "книг" to 'f', "том" to 'm', "раздел" to 'm', "акт" to 'm', "страниц" to 'f',
        "век" to 'm', "столети" to 'n', "тысячелети" to 'n', "квартал" to 'm')
    private val romanNounBeforeAlt = """(?:глав(?:а|ы|е|у|ой|ах|ам|ами)?|част(?:ь|и|ью|ей|ях|ям|ями)|том(?:а|ы|у|е|ом|ов|ах|ам|ами)?|книг(?:а|и|е|у|ой|ах|ам|ами)?|раздел(?:а|ы|у|е|ом|ов|ах|ам|ами)?|акт(?:а|ы|у|е|ом|ов|ах|ам|ами)?|страниц(?:а|ы|е|у|ей|ах))"""
    private val romanNounAfterAlt = """(?:в\.|вв\.|кв\.|стол\.|век(?:а|е|у|ом|ов|ах|ам|ами)?|(?:столети|тысячелети)(?:е|я|и|ю|ем|й|ях|ям|ями)|квартал(?:а|е|у|ом|ов|ах|ам|ами)?|""" +
        """глав(?:а|ы|е|у|ой)|книг(?:а|и|е|у|ой)|том(?:а|е|у|ом)?|част(?:ь|и|ью))"""
    private val romanTok = """([mdclxviхсмі]+)"""
    // Кириллические х/с/м/і визуально совпадают с латинскими римскими буквами (review final-fix
    // п.2) — «ХХ» иначе доходит до Abbrev как обычный кириллический токен («ха х+а»). Токен может
    // быть смешанным («ХIV» — кириллическая Х + латинские IV), поэтому в класс входят обе группы.
    private val romanRe = Regex(
        """(?:(?<![\p{L}])($romanPrepAlt)\s+)?(?:(?<![\p{L}])($romanNounBeforeAlt)\s+)?(?<![\p{L}\d])$romanTok(?![\p{L}\d])""" +
            """(?:\s*(и|или|по|до|[-–—]|,)\s*((?:начал[оае]|конец|конц[ае]|середин[аые]|половин[аые])\s+)?$romanTok(?![\p{L}\d])(?:\s*(?:и|или|,)\s*$romanTok(?![\p{L}\d]))?)?(?:\s+($romanNounAfterAlt)(?![а-яё]))?""",
        RegexOption.IGNORE_CASE
    )
    private val romanValues = mapOf('i' to 1, 'v' to 5, 'x' to 10, 'l' to 50, 'c' to 100, 'd' to 500, 'm' to 1000)
    private val romanCyrMap = mapOf('х' to 'x', 'с' to 'c', 'м' to 'm', 'і' to 'i')
    private fun latinizeRoman(s: String) = s.map { romanCyrMap[it] ?: it }.joinToString("")
    // Суффикс порядкового для numberRe по падежу и роду.
    private val ordSuffixByCase = mapOf(
        'm' to arrayOf("й", "го", "му", "й", "ым", "ом"), 'f' to arrayOf("я", "ой", "ой", "ю", "ой", "ой"),
        'n' to arrayOf("е", "го", "му", "е", "ым", "ом"))
    private val centurySg = arrayOf("век", "века", "веку", "век", "веком", "веке")
    private val centuryPl = arrayOf("века", "веков", "векам", "века", "веками", "веках")
    private val centuryN = arrayOf("столетие", "столетия", "столетию", "столетие", "столетием", "столетии")
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

    // Валидный римский токен: строгая грамматика; кириллический с «с»/«м» — только капсом, иначе предлог «с»
    // после «глава/книга» становится «сотая» (scoped re-review final-fix п.1). Строчные «хх», «хiх» без «с»/«м»
    // берём: «хх век» читалось «ха ха век» (жалоба 06.10.2026); без слова-триггера romanAlone их всё равно не тронет.
    private fun romanValue(token: String): Int? {
        val lower = token.lowercase()
        val normalized = latinizeRoman(lower)
        if (!romanStrictRe.matches(normalized) || normalized.isEmpty()) return null
        if (normalized != lower && !token.all { it.isUpperCase() } && lower.any { it in "см" }) return null
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

    // «в 12 в.», «15–16 вв.», «с 16 по 18 в.» — века арабскими цифрами переписываем римскими, дальше
    // склоняет romanNumerals («в двенадцатом веке»); иначе units читал «в двенадцати веках». Больше 30 — вольты.
    // Только после предлога или «начала/конца…»: «строили 2 в. подряд» — количество, «два века».
    private val arabicCenturyRe = Regex("""(?<=(?<![\p{L}])(?:[вВ]о?|[сСкК]о?|[дД]о|[пП]о|[оО]т|[иИ]з|[нН]а|[оО]б?|[нН]ачала|[кК]онца|[сС]ередины|[рР]убежа|[пП]оловины)\s)(\d{1,2})(?:(\s*[–—-]\s*|\s+(?:по|до|и)\s+)(\d{1,2}))?\s*(вв?\.)""")
    private fun toRoman(n: Int): String {
        var k = n
        return buildString { for ((v, r) in listOf(10 to "X", 9 to "IX", 5 to "V", 4 to "IV", 1 to "I")) while (k >= v) { append(r); k -= v } }
    }
    // «уже 18 века», «в 18 веке» словом — тоже римскими (жалоба 15.10.2026, было «восемнадцать века»): при «века»
    // число на 2–4 — может быть количество («два века»), его не трогаем; «веке», «веку», «веком» — всегда порядковое.
    private val arabicCenturyWordRe = Regex("""(?<![\p{L}\d.,:/-])(\d{1,2})(?=\s+(век[аеу]|веком)(?![а-яё]))""")
    private fun arabicCentury(text: String) = arabicCenturyRe.replace(text) { m ->
        val (a, conn, b, noun) = m.destructured
        val nums = listOfNotNull(a.toInt(), b.toIntOrNull())
        if (nums.any { it !in 1..30 }) m.value
        else toRoman(nums[0]) + (if (b.isEmpty()) "" else conn + toRoman(nums[1])) + " " + noun
    }.let { t -> arabicCenturyWordRe.replace(t) { m ->
        val n = m.groupValues[1].toInt()
        if (n !in 1..30 || m.groupValues[2] == "века" && n % 10 in 2..4 && n % 100 !in 12..14) m.value else toRoman(n)
    } }

    // «IX-го и X-го века» — суффикс у римской: число с тем же суффиксом; «X томов», «XII глав» перед род. мн. — количество
    private val romanSuffixRe = Regex("""(?<![\p{L}\d])([IVXLC]{1,6})-(го|й|я|е|м|ом|ой|ую|ых|х|му|ым)(?![\p{L}\d])""")
    private val romanCountRe = Regex("""(?<![\p{L}\d])([IVXLC]{1,6})(?=\s+(?:томов|книг|глав|частей|лет|раз)(?![\p{L}]))""")
    private fun romanNumerals(text: String): String {
        val s = romanCountRe.replace(romanSuffixRe.replace(text) { m -> romanValue(m.groupValues[1])?.let { "$it-${m.groupValues[2]}" } ?: m.value }) { m ->
            romanValue(m.groupValues[1])?.toString() ?: m.value }
        return romanNumeralsCore(s)
    }
    private fun romanNumeralsCore(text: String) = romanRe.replace(text) { m ->
        val (prep, before, tok1, conn, mid, tok2, tok3, after) = m.destructured
        val v1 = romanValue(tok1) ?: return@replace m.value
        val v2 = if (tok2.isEmpty()) null else romanValue(tok2)
        val v3 = if (tok3.isEmpty()) null else romanValue(tok3)
        if (before.isEmpty() && after.isEmpty()) {
            // Без триггеров — каждый токен сам по себе (старое поведение), остальной текст как есть.
            val repl = mutableListOf(3 to romanAlone(tok1))
            if (tok2.isNotEmpty()) repl += 6 to romanAlone(tok2)
            if (tok3.isNotEmpty()) repl += 7 to romanAlone(tok3)
            return@replace m.withGroupReplaced(*repl.toTypedArray())
        }
        if (tok2.isNotEmpty() && v2 == null || tok3.isNotEmpty() && v3 == null) return@replace m.value
        // «С главой 10» — предлог «с», не сотая: одиночная кириллическая буква перед «главой/книгой/томом» — не число
        if (before.isEmpty() && tok1.length == 1 && tok1[0] in "хсміХСМІ" && after.isNotEmpty() && !after.lowercase().let { it.startsWith("в") || it.startsWith("кв") || it.startsWith("стол") })
            return@replace m.value
        val noun = (if (after.isNotEmpty()) after else before).lowercase()
        val stem = romanNounGender.keys.firstOrNull { noun.startsWith(it) }
        val gender = if (noun == "кв.") 'm' else if (noun == "в." || noun == "вв.") 'm' else if (noun == "стол.") 'n' else romanNounGender[stem] ?: 'm'
        val plural = v2 != null && conn.lowercase() !in setOf("по", "до")
        val prepCase = romanPrepCase[prep.lowercase()]
        // Одно число + «вв.» — старое чтение «двадцатые века» (task 28 п.6).
        if (v2 == null && after.lowercase() == "вв." && before.isEmpty()) return@replace "${if (prep.isEmpty()) "" else "$prep "}$v1-ые века" + endDot(text, m, after)
        val nounCase = if (noun.endsWith(".")) null else romanNounCase(noun, plural)
        val range = v2 != null && conn.lowercase() in setOf("по", "до")
        // «с XVI по XVIII век»: существительное согласуется со вторым числом, первое — по предлогу.
        // «на часть IV», «за том II» — у «часть/том» им. и вин. совпадают, предлог решает: винительный
        val accPrep = prep.lowercase() in setOf("на", "за", "через", "про") && nounCase == Case.NOM
        // «Иконы XV в.», «буддизма IV—VII вв.» — после существительного век в родительном
        val nounBehind = prep.isEmpty() && before.isEmpty() && nounCase == null && after.isNotEmpty() &&
            prevOnlyWordRe.find(text.substring(0, m.range.first))?.groupValues?.get(1)?.lowercase()
                ?.let { w -> morph?.tags(w)?.let { Morph.isNoun(it) && !Morph.isAdjective(it) } } == true
        val case1 = if (range) prepCase ?: Case.GEN else if (accPrep) Case.ACC else nounCase ?: prepCase ?: if (nounBehind) Case.GEN else Case.NOM
        val case2 = when (conn.lowercase()) { "по" -> nounCase ?: Case.ACC; "до" -> Case.GEN; else -> case1 }
        val suf = ordSuffixByCase.getValue(gender)
        val sb = StringBuilder()
        if (prep.isNotEmpty()) sb.append(prep).append(' ')
        if (before.isNotEmpty()) sb.append(before).append(' ')
        sb.append(v1).append('-').append(suf[case1.ordinal])
        if (v2 != null) sb.append(if (conn == ",") "" else " ").append(conn).append(' ').append(mid).append(v2).append('-').append(suf[case2.ordinal])
        // Третье число («IV, V и VI») — в том же падеже, что второе; союз между ними берём из текста.
        if (v3 != null) sb.append(m.value.substring(m.groups[6]!!.range.last + 1 - m.range.first, m.groups[7]!!.range.first - m.range.first))
            .append(v3).append('-').append(suf[case2.ordinal])
        if (after.isNotEmpty()) {
            val a = after.lowercase()
            val nounCaseIdx = (if (v2 != null) case2 else case1).ordinal
            val word = when (a) {
                "в." -> centurySg[nounCaseIdx]
                "вв." -> (if (plural) centuryPl else centurySg)[nounCaseIdx]
                "кв." -> quarterSg[nounCaseIdx]
                "стол." -> centuryN[nounCaseIdx]
                else -> after
            }
            sb.append(' ').append(word).append(endDot(text, m, after))
        }
        sb.toString()
    }
    // «в.», «вв.», «кв.» перед следующим предложением — точка сокращения заодно и конец фразы: «в XII в. Потом…»
    private val sentenceAfterRe = Regex("""^\s+[А-ЯЁA-Z]""")
    private fun endDot(text: String, m: MatchResult, after: String) =
        if (after.endsWith(".") && sentenceAfterRe.containsMatchIn(text.substring(m.range.last + 1))) "." else ""

    // 3b. Римское число после имени с заглавной (task 28 п.2): «Пётр I», «Екатерина II» →
    // порядковое через суффикс для numberRe. Падеж — по окончанию имени, род — по списку женских
    // имён монархов (корпус ru-normalizr): «Людовика XVI» — мужское в родительном («шестнадцатого»),
    // а не женское в именительном. Проход ДО romanNumerals(), иначе тот уже прочитает «XIV»/«II»
    // количественным (eligibleAlone); слова-триггеры («Глава I», «Россия XX века») отдаём старому пути.
    // Только [IVX] — «Размер XL», «Диск CD» остаются бытовыми сокращениями, короли за XX не бывают.
    // Латинское слово следом («Название I Am Legend») — это английская фраза, не номер.
    // Имя слитно с заглавной внутри («ЛжеДмитрий II», «ЛжеДмитрий I») — тоже имя (жалоба 28.10.2026).
    private val romanAfterNameRe = Regex(
        """(?<![\p{L}])((?:[А-ЯЁ][а-яё]+)+)\s+([IVX]+)(?:-(?:$ordSuffixAlt))?(?![\p{L}\d])(?!\s+[A-Za-z])(?!\s*(?:и|[-–—,])\s*[IVX]+(?![\p{L}\d]))""")
    // Предлог/союз с заглавной в начале предложения — не имя («За XV и XVI века», «Между XV и XVI»).
    private val notNames = setOf("за", "на", "но", "из", "под", "над", "от", "до", "по", "для", "без", "через", "около",
        "после", "вокруг", "среди", "между", "перед", "при", "про", "обо", "или", "как", "что", "это", "все", "уже", "ещё",
        "еще", "лишь", "даже", "если", "когда", "где", "там", "тут", "так", "ведь", "только", "потом", "затем", "зато")
    private val romanAfterWordRe = Regex("""^\s+(\p{L}+\.?)""")
    private val romanNounAfterRe by lazy { Regex("^$romanNounAfterAlt$", RegexOption.IGNORE_CASE) }
    private val romanNounBeforeRe = Regex("^$romanNounBeforeAlt$")
    private val femaleStems = setOf("екатерин", "елизавет", "анн", "мари", "виктори", "изабелл", "матильд", "иоанн", "хуан",
        "маргарит", "кристин", "христин", "ульрик", "беатрикс", "вильгельмин", "юлиан", "луиз", "елен", "ирин", "феодор",
        "зо", "клеопатр", "береник", "арсино", "джейн", "софь", "софи", "ядвиг", "констанци", "бланк", "джованн", "тамар",
        "русудан", "мод", "элеонор", "урак", "санч", "петронил", "агнесс", "ольг")
    private val femaleIndecl = setOf("элизабет", "елизабет", "элизабэт", "мэри", "мери", "кэтрин", "энн", "джейн", "маргарет",
        "изабель", "джоан", "эдит", "кармен", "ирэн", "агнес", "жанет")
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
            // Одна буква после неодушевлённого слова или повеления — буква, не номер: «Буква X», «Ось X», «Нажмите X».
            // Имён монархов в morph.bin нет (0), «Карл» одушевлённый; без morph.bin — как раньше
            if (token.length == 1 && (name.lowercase().let { it.endsWith("ите") || it.endsWith("йте") } ||
                    morph?.tags(name.lowercase())?.let { it != 0 && !Morph.animate(it) } == true)) return@replace m.value
            // Явный суффикс после числа («VII-го», «II-я») — берём его как есть.
            val explicit = m.value.substringAfter(token, "").removePrefix("-")
            if (explicit.isNotEmpty()) return@replace "$name ${romanToInt(lower)}-$explicit"
            // Следом «века»/«в.»/«вв.» («Европа XIX в.») — отдать romanNumerals(), там это век.
            val next = romanAfterWordRe.find(s.substring(m.range.last + 1))?.groupValues?.get(1)?.lowercase()
            if (next != null && romanNounAfterRe.matches(next)) return@replace m.value
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
        // без окончания имя мужское: «Иоанн IV», «Хуан II» (основы «иоанн», «хуан» — от Иоанны, Хуаны). «Александр V»
        // читалось «пятая» (жалоба 08.10.2026); «александр», «филипп» из списка убраны — «Александра II» это Александр
        // «Иоанне III», «Хуане» — у основ, общих с женским именем, «-е» мужское (Иоанн, Хуан): женское только «-а/-ы/-у/-ой»
        // несклоняемые женские имена на согласную: «Элизабет I» — «первая», не «первый» (жалоба 15.10.2026); падеж не узнать
        if (n in femaleIndecl) return true to Case.NOM
        val female = (stem != n && stem in femaleStems || (stem.length > 3 && stem.dropLast(1) in femaleStems)) &&
            !(stem in setOf("иоанн", "хуан") && n.last() !in "аыу" && !n.endsWith("ой"))
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
    // После двоеточия («06:58:07.2») — секунды с долями, их читает times().
    // Точка следом — не помеха, если за ней не цифра, а месяц двумя цифрами: «Отпуск 12.05 – 26.05.», «Срок до 1.09.» —
    // точка предложения (было «двадцать шесть целых пять сотых»); «1.10.2» — по-прежнему не дата.
    private val dateNoYearRe = Regex("""(?<![\d.:])(?<![A-Za-z×*+=÷^−] ?)(\d{1,2})\.(\d{1,2})(?!\d|\.\d)""")
    // Диапазон дат через тире: «1.09.26 – 3.10.26», «28.09 – 3.10» — край с днём одной цифрой тоже дата, если месяц
    // (и год) у обоих краёв двумя цифрами, а без года — если хоть один край строго «дд.мм». Дописываем ноль дню, дальше
    // края читают dateShortYearRe и dateNoYearRe. «1.5 – 2.5», «10.5 – 10.25» — не даты, как было.
    private val dateRangeRe = Regex(
        """(?<![\d.:])(?<![A-Za-z×*+=÷^−] ?)(\d{1,2})\.(\d{1,2})(\.\d{2})?(\s*[-–—]\s*)(\d{1,2})\.(\d{1,2})(\.\d{2})?(?!\d|[.,]\d)""")

    // «20.09.26» — год двумя цифрами (форумы, профили, подписи к файлам). День и месяц тоже строго двумя цифрами:
    // «1.10.26» и «0.14.10» — скорее номер версии. После латиницы («v10.12.13») — версия.
    private val dateShortYearRe = Regex("""(?<![\d.])(?<![A-Za-z] ?)(\d{2})\.(\d{2})\.(\d{2})(?![\d]|\.\d)""")
    // «22 сент.», «3 дек.» — сокращённый месяц после дня: полное слово, дальше как обычная дата.
    private val monthAbbr = mapOf("янв" to 1, "фев" to 2, "февр" to 2, "мар" to 3, "апр" to 4, "июн" to 6, "июл" to 7,
        "авг" to 8, "сен" to 9, "сент" to 9, "окт" to 10, "ноя" to 11, "нояб" to 11, "дек" to 12)
    private val monthAbbrRe = Regex("""(?<![\d.])(\d{1,2})\s+(${monthAbbr.keys.sortedByDescending { it.length }.joinToString("|")})\.(?=\s|$|[,;:)])""", RegexOption.IGNORE_CASE)

    // ISO «2024-05-12» — та же дата, что «12.05.2024».
    private val dateIsoRe = Regex("""(?<![\d\-\u2010-\u2014.])([12]\d{3})-(\d{2})-(\d{2})(?![\d\-\u2010-\u2014]|[.,]\d)""")

    // Месяцы в дореформенном написании после числа: «2 Маия», «3 іюля», «5 Генваря»
    private val oldMonthRe = Regex("""(?<=\d\s)(маия|іюня|іюля|генваря|сентебря|октебря)(?![\p{L}])""", RegexOption.IGNORE_CASE)
    private val oldMonths = mapOf("маия" to "мая", "іюня" to "июня", "іюля" to "июля", "генваря" to "января", "сентебря" to "сентября", "октебря" to "октября")
    private fun dates(text: String): String {
        val modern = oldMonthRe.replace(text) { oldMonths.getValue(it.value.lowercase()) }
        val abbr = monthAbbrRe.replace(modern) { m ->
            // точка сокращения в конце предложения — ещё и его точка: «Встреча 3 дек.» / «…дек. Потом»
            val dot = if (sentenceEndAheadRe.containsMatchIn(modern.substring(m.range.last + 1))) "." else ""
            m.groupValues[1] + " " + monthGenitive[monthAbbr.getValue(m.groupValues[2].lowercase())] + dot
        }
        val iso = dateIsoRe.replace(abbr) { m ->
            val (y, mo, d) = m.destructured
            val day = d.toInt(); val month = mo.toInt()
            if (day !in 1..31 || month !in 1..12) m.value else "$day-го ${monthGenitive[month]} $y-го года"
        }
        // «по 03.03.2024» — винительный, «к 01.05.2024» — дательный, «перед» — творительный; иначе родительный
        fun fullDaySfx(before: String) = when (datePrepRe.find(before)?.groupValues?.get(1)?.lowercase()) {
            "по", "на", "за" -> "е"; "к", "ко" -> "му"; "перед" -> "ым"; else -> "го"
        }
        val withYear = dateWithYearRe.replace(iso) { m ->
            val (d, mo, y, gTail) = m.destructured
            val day = d.toInt(); val month = mo.toInt()
            if (day !in 1..31 || month !in 1..12) return@replace m.value
            val rest = iso.substring(m.range.last + 1)
            if (gTail.isEmpty() && dateTailUnitRe.containsMatchIn(rest)) return@replace m.value
            // «г.» перед заглавной/концом текста — ещё и точка предложения, её сохраняем.
            val dot = if (gTail.trim() == "г." && sentenceEndAheadRe.containsMatchIn(rest)) "." else ""
            "$day-${fullDaySfx(iso.substring(0, m.range.first))} ${monthGenitive[month]} $y-го года$dot"
        }
        val ranged = dateRangeRe.replace(withYear) { m ->
            val (d1, m1, y1, dash, d2, m2, y2) = m.destructured
            val valid = listOf(d1 to m1, d2 to m2).all { (d, mo) -> d.toInt() in 1..31 && mo.toInt() in 1..12 }
            // без года: хоть один край строго «дд.мм» или у обоих месяц двумя цифрами («Отпуск 1.05 – 2.05.»: в середине
            // фразы оба края и так даты, ноль не даёт концу фразы прочесть второй край дробью)
            val date = if (y1.isNotEmpty()) y2.isNotEmpty() && m1.length == 2 && m2.length == 2
                else y2.isEmpty() && (d1.length == 2 && m1.length == 2 || d2.length == 2 && m2.length == 2 || m1.length == 2 && m2.length == 2)
            if (!valid || !date) m.value
            else "${d1.padStart(2, '0')}.${m1.padStart(2, '0')}$y1$dash${d2.padStart(2, '0')}.${m2.padStart(2, '0')}$y2"
        }
        val shortYear = dateShortYearRe.replace(ranged) { m ->
            val (d, mo, y) = m.destructured
            val day = d.toInt(); val month = mo.toInt()
            if (day !in 1..31 || month !in 1..12) return@replace m.value
            if (dateTailUnitRe.containsMatchIn(ranged.substring(m.range.last + 1))) return@replace m.value
            "$day-${fullDaySfx(ranged.substring(0, m.range.first))} ${monthGenitive[month]} ${y.toInt()}-го года"
        }
        return dateNoYearRe.replace(shortYear) { m ->
            val (d, mo) = m.destructured
            if (d.length < 2 && mo.length < 2) return@replace m.value
            val before = shortYear.substring(0, m.range.first)
            if (shortYear.getOrNull(m.range.last + 1) == '.') {
                // перед точкой — только месяц двумя цифрами: «составил 16.1.», «версии 10.2.» — дробь, как было
                if (mo.length < 2) return@replace m.value
                // и день двумя цифрами или предлог даты перед ним: «Курс 1.05.», «Средний балл 4.12.» — дробь, как было;
                // «Срок до 1.09.», «Указ от 26.07.» — дата; край диапазона дописан нулём в dateRangeRe
                // «вырос на 2.08.», «подешевели на 2.05.» — изменение величины, не дата («по России на 2.08.» — дата)
                if (d.length < 2 && (!datePrepRe.containsMatchIn(before) || changeNaBehindRe.containsMatchIn(before))) return@replace m.value
            }
            val day = d.toInt(); val month = mo.toInt()
            if (day !in 1..31 || month !in 1..12) return@replace m.value
            // «N.N» перед единицей измерения — дробь («12.5 км/ч»), не дата без года (review
            // final-fix п.8): unitAltPattern определён ниже по файлу, но здесь это функция, не
            // property-инициализатор — к моменту вызова объект полностью сконструирован.
            val after = shortYear.substring(m.range.last + 1)
            if (dateTailUnitRe.containsMatchIn(after)) return@replace m.value
            // разряд или процент следом — количество: «Ст. 12.1 тыс. руб.», «12.5 тысячи строк», «Инфляция составила 1.05%.»
            // (было «двенадцатого января тысяч», «первого мая» без процента). Только у даты без года: «на 01.01.2024 тыс. руб.»
            // — дата в шапке таблицы. День с нулём («01.05», «на 01.01 тыс. руб.») дробью не пишут — разряд его не отменяет,
            // процент («01.05 %») — отменяет.
            if (percentAheadRe.containsMatchIn(after) || !d.startsWith('0') && scaleAheadRe.containsMatchIn(after)) return@replace m.value
            // «в 7.12», «в 15.10.» — время (его читает timeDots), не «в седьмого декабря»: даты без года после «в» не бывает
            if (day <= 23 && mo.length == 2 && timeBehindRe.containsMatchIn(before)) return@replace m.value
            // падеж дня по предлогу, как у даты с годом: «по двадцать шестое мая», «к пятнадцатому августа»
            "$day-${fullDaySfx(before)} ${monthGenitive[month]}"
        }
    }

    // 5. Время «чч:мм[:сс]»: читаем словами, только если рядом есть триггер — предлог перед
    // (в/к/до/с/около/после/на), слово после (утра/дня/вечера/ночи) или сам формат чч:мм:сс.
    // Без триггера «3:16» — это скорее «глава:стих» («Иоанна 3:16»), а не время: оставляем
    // числа по отдельности, их потом читает numberRe (review t17 п.1, Normalizer.kt:176).
    // Доли секунды («06:58:07.2») — дробь, её потом читает numberRe: «семь целых две десятых секунды».
    private val timeRe = Regex(
        """(?:(?<![\p{L}])(в|к|до|с|около|после|на)\s+)?(?<!\d)(\d{1,2}):(\d{2})(?::(\d{2})(?:[.,](\d+))?)?(?!\d)""" +
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
    // «в 3.50 началась», «около 3.50 он уже спал» — время через точку после предлога, если следом не деньги, не единица
    // и не существительное («в 1.25 раза», «в 3.50 рубля» — числа)
    // Точка или запятая следом без цифры — знак фразы: «С 9.00 до 18.00.», «в 10.30, потом» (было «восемнадцать целых ноль сотых»)
    private val timeDotPrepRe = Regex("""(?<=(?<![\p{L}])(?:в|во|около|к|после|до|с)\s)([01]?\d|2[0-3])\.([0-5]\d)(?!\d|[.,]\d|\s?%)""", RegexOption.IGNORE_CASE)
    // «в 10.30 в офисе», «к 9.00 с документами» — строчная «в»/«с» перед словом предлог, не вольты и не секунды
    private val prepNotUnitAheadRe = Regex("""^ (?:в|во|с|со|к|о|у|и|а)\s+\p{L}""")
    // Существительное следом держит число, только если оно в родительном, творительном или предложном — то, что меряют:
    // «в 1.25 раза», «в 3.50 рубля», «с 2.50 рублями», «в 2.30 метрах». Подлежащее и дательный («в 14.06 спасателям
    // поступило», «после 21.00 тишина») — время; раньше любое существительное оставляло число, а «в 14.06» после правки
    // дат стало бы «четырнадцать.шести» (дробь с дательным).
    private fun measuredNounAhead(rest: String): Boolean = tailNoun(rest)?.takeIf { it != 0 }?.let { t ->
        listOf(false, true).any { pl -> Morph.nounCases(t, pl).let { Case.GEN in it || Case.INS in it || Case.PRE in it } } } == true
    private fun timeDots(text: String) = timeDotPrepRe.replace(timeDotRe.replace(text, "$1:$2")) { m ->
        val rest = text.substring(m.range.last + 1)
        if (moneyOrUnitAheadRe.containsMatchIn(rest) && !prepNotUnitAheadRe.containsMatchIn(rest) || countedNounAhead(rest) ||
            measuredNounAhead(rest)) m.value
        else "${m.groupValues[1]}:${m.groupValues[2]}"
    }
    private fun times(text: String) = timeRe.replace(timeDots(text)) { m ->
        val (prep, h, mi, sec, frac, after) = m.destructured
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
            if (frac.isNotEmpty()) sb.append(' ').append(fraction(second!!.toLong(), frac) ?: return@replace m.value).append(" секунды")
            else if (second != null && second > 0) sb.append(' ').append(timeUnit(second, case, 2))
        } else {
            // без триггера — два числа, но ноль минут слышен: «18:06» — «восемнадцать ноль шесть», «16:00» —
            // «шестнадцать ноль ноль» (было «восемнадцать шесть» — как «18:6»); «3:16» — «три шестнадцать», как и было
            sb.append(h).append(' ')
            if (mi[0] == '0') sb.append("ноль ").append(if (mi == "00") "ноль" else mi.substring(1)) else sb.append(mi)
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
        """(?<![\p{L}])(с|со)\s+(\d{3,5})(\s*г\.?(?![а-яё]))?\s+(по|до)\s+(\d{3,5})(\s*(?:гг?\.|г(?![а-яё])|года?(?![а-яё])))?""", RegexOption.IGNORE_CASE)
    private val eraAheadRe = Regex("""^\s*(?:до\s+)?н\.\s*э(?:\.|(?![а-яё]))""", RegexOption.IGNORE_CASE)
    // «между 1990 и 2000 гг.» — творительный.
    private val yearPairMezhduRe = Regex("""(?<![\p{L}])между\s+(\d{4})\s+и\s+(\d{4})\s*гг\.""", RegexOption.IGNORE_CASE)
    private val yearGRangeBareRe = Regex("""$weightWordBehind(?<![а-яё\d])(\d{2,5})\s*[-–—]\s*(\d{2,5})\s*(?:гг\.|г\.$eraAhead|$eraAhead|г\.$gramsWordAhead)""", RegexOption.IGNORE_CASE)
    private val yearGXWithPrepRe = Regex("""(?<![\p{L}])(в|во)\s+(\d{2,4})\s*-?\s*х\s*гг\.""", RegexOption.IGNORE_CASE)
    private val yearGXBareRe = Regex("""(?<![а-яё\d-])(\d{2,4})\s*-?\s*х\s*гг\.""", RegexOption.IGNORE_CASE)
    private val yearGLeftoverRe = Regex("""гг\.""", RegexOption.IGNORE_CASE)
    // «всем гг. батарейным командирам» — «господам»: «гг.» не после числа (годы уже разобраны выше)
    private val gospodaRe = Regex("""(?<![\d\p{L}-])(?<![\d][-–—]?[а-яё]{0,3}\s?)гг\.(?=\s+([а-яё]+))""")
    // 1–2 цифры — год только перед «н. э.» («в 79 г. н. э.»), пять цифр — тоже («до 13000 г. до н. э.»).
    private val yearDigits = """(\d{3,4}|(?:\d{1,2}|\d{5})(?=\s*г\.?\s*(?:до\s+)?н\.\s*э))"""
    // 1–2 цифры с точкой после «в/во», «с/со», «ок.», месяца или «в начале/конце» — год: «в 62 г.», «(с 54 г.)», «(ум. ок.
    // 62 г.)», «в августе 79 г.» (корпус Leipzig и books/lib: 190 из 190). После «около», «до» — граммы чаще («весом от 9
    // до 12 г.», «мера веса, около 30 г.»).
    private val yearGShortPrepRe by lazy { Regex(
        """$weightWordBehind(?<![\p{L}])(в|во|с|со|ок\.|(?:в\s+)?(?:конце|начале|середине|конца|начала|середины|$monthsAlt))\s+(\d{1,2})\s*г\.$gramsWordAhead""",
        RegexOption.IGNORE_CASE) }
    // «(451 г.)», «(64 г.),» — год в скобках сам по себе (корпус: 27 из 28). Вес или продукт перед скобкой — граммы:
    // «По весу (15 г.)», «Сахар (200 г.)»; круглые 50, 100, 250… — тоже граммы (вес упаковки; год так теряется один —
    // «(400 г.)»).
    // «2009—2010 г.г.» — то же «гг.» (было «…десятым годахг.»)
    private val yearGgDottedRe = Regex("""(?<=\d)\s*г\.\s?г\.(?![\p{L}])""")
    // ревью ru-fixes-2 I-1: только три цифры — веса в скобках чаще одно-двузначные («гвоздика (2 г.)», «шарика (15 г.)»)
    private val yearGParenRe = Regex("""(?<=\()(\d{3})\s*г\.(?=\s*[);,])""")
    // Вес по контексту сильнее короткого года (ревью ru-fixes-2 I-1): слово веса или массы раньше во фразе («Монета весила
    // ок. 8 г.», «Вес монеты ок. 8 г.»), существительное в родительном следом по таблице морфологии, а не по списку
    // («в 10 г. спирта», «в 30 г. миндаля», «(35 г.) сахара»; прилагательное перед ним — тоже: «в 10 г. чистого спирта»).
    private val weightInPhraseRe = Regex("""(?<![\p{L}])(?:вес|веса|весу|весом|весе|весит|весят|весил[аио]?|весили|масс[аеуы]|массой|весящ\p{L}*)(?![\p{L}])""", RegexOption.IGNORE_CASE)
    // вес и масса в переносном смысле — не о весе: «В массе своей крестьяне в 15 г.», «Его вес в обществе вырос в 12 г.»
    // (повторное ревью ru-fixes-2 M-a); «в массе монеты», «общественный вес монеты» — прямой смысл (ревью N-1)
    private val weightIdiomRe = Regex("""(?<![\p{L}])(?:(?:в\s+)?массе\s+сво\p{L}+|вес\p{L}*\s+(?:в|среди)\s+обществ\p{L}*|политическ\p{L}*\s+вес\p{L}*)(?![\p{L}])""", RegexOption.IGNORE_CASE)
    // слова о времени после короткого года — год, а не продукт: «Во 2 г. войны», «В 12 г. правления Августа»
    // ponytail: закрытый список; другое слово о времени в родительном прочтётся граммами, как в базе
    private val yearTimeNouns = setOf("войны", "правления", "царствования", "княжения", "жизни", "эры", "века", "правительства",
        "эпохи", "понтификата", "консульства", "существования", "службы", "ссылки", "осады", "похода", "революции", "династии",
        "хиджры", "освобождения", "свободы", "независимости", "республики", "империи", "оккупации", "блокады", "восстания",
        "изгнания", "эмиграции", "брака", "пребывания", "строительства", "обучения", "учёбы", "учебы", "кампании", "экспедиции")
    private val wordsAheadRe = Regex("""^\s*([а-яё]+)(?:\s+([а-яё]+))?""")
    private val wordsAfterParenRe = Regex("""^\s*\)\s*([а-яё]+)(?:\s+([а-яё]+))?""")
    /** [paren] — слово смотрим за «)» («(35 г.) сахара»); иначе только вплотную за «г.»: «(ум. ок. 28 г.) жил» — не о весе. */
    private fun massContext(s: String, start: Int, end: Int, paren: Boolean = false): Boolean {
        val before = s.substring(maxOf(0, start - 80), start).let { b -> b.substring((Regex("""[.!?]\s+(?=[А-ЯЁ])""").findAll(b).lastOrNull()?.range?.last ?: -1) + 1) }
        if (weightInPhraseRe.containsMatchIn(weightIdiomRe.replace(before, " "))) return true
        val m = morph ?: return false
        val w = (if (paren) wordsAfterParenRe else wordsAheadRe).find(s.substring(end, minOf(s.length, end + 60))) ?: return false
        // родительный мн. ч. на «-л» — чаще глагол («жил», «сил»): не берём
        fun genNoun(x: String) = x !in yearTimeNouns && m.tags(x).let { t -> Morph.isNoun(t) &&
            (Case.GEN in Morph.nounCases(t, false) || Case.GEN in Morph.nounCases(t, true) && !x.endsWith("л")) }
        val first = w.groupValues[1]
        if (genNoun(first)) return true
        val t = m.tags(first)
        return t and Morph.ADJ != 0 && t and Morph.PRON == 0 && !Morph.isNoun(t) && w.groupValues[2].isNotEmpty() && genNoun(w.groupValues[2]) &&
            (Case.GEN in Morph.adjCases(t, null, false) || Case.GEN in Morph.adjCases(t, Gender.F, false) || Case.GEN in Morph.adjCases(t, null, true))
    }
    // ponytail: список основ продуктов неполный; продукт не из списка перед «(N г.)» прочтётся годом
    private val massWordBehindRe = Regex(
        """(?<![\p{L}])(?:вес|масс|сахар|мук|масл|сол[ьи]|вод|молок|сливк|сметан|творог|сыр|мяс|фарш|тест|круп|рис|картоф|""" +
            """продукт|крахмал|сод|дрожж|орех|ягод|овощ|фрукт|рыб|хлеб|м[её]д|шоколад|кофе|ча[йяю]|специ|пер[её]?ц|лук|морков|""" +
            """капуст|зелен|изюм|желатин|какао|гриб|колбас|ветчин|кур|филе|говядин|свинин|порци|соус|кефир|йогурт|сал[оа]|""" +
            """чеснок|фасол|горох)\p{L}*\s*\($""", RegexOption.IGNORE_CASE)
    // После «в»/«к» точка у «г» не обязательна («в 862 г был призван Рюрик»); после «до/около/с» —
    // обязательна: «до 500 г» без контекста — граммы (принятое ограничение).
    private val yearGOptDot = """\s*г(?:\.|(?![а-яё.]))"""
    private val yearGPrepVRe = Regex("""$weightWordBehind(?<![\p{L}])(в|во)\s+$yearDigits$yearGOptDot$gramsWordAhead""", RegexOption.IGNORE_CASE)
    private val yearGPrepKRe = Regex("""(?<![\p{L}])(к|ко)\s+$yearDigits$yearGOptDot$gramsWordAhead""", RegexOption.IGNORE_CASE)
    private val monthsAlt = """январ[ьяе]|феврал[ьяе]|март[ае]?|апрел[ьяе]|ма[йяе]|июн[ьяе]|июл[ьяе]|август[ае]?|сентябр[ьяе]|октябр[ьяе]|ноябр[ьяе]|декабр[ьяе]"""
    private val yearGPrepOtherRe = Regex(
        """(?<![\p{L}])(с начала|с конца|с|до|после|от|около|ок\.|(?:в\s+)?(?:конце|начале|середине|течение|конца|начала|середины|половине|половины|рубеже)|лет[оа]м?|осен[ьи]ю?|весн[аы]|весной|зим[аыо]й?|(?:в\s+)?(?:$monthsAlt))\s+$yearDigits\s*г\.$gramsWordAhead""",
        RegexOption.IGNORE_CASE)
    // «г.» без предлога — год только при четырёх цифрах (task 28 п.4): «500 г. муки» — граммы,
    // доходит до units(); трёхзначный год — с предлогом («в 988 г.», yearGPrepVRe) или перед «до н. э.».
    private val yearGBareRe =
        Regex("""$weightWordBehind(?<![а-яё\d])(\d{4}|(?:\d{1,3}|\d{5})(?=\s*г\.?\s*(?:до\s+)?н\.\s*э(?:\.|(?![а-яё]))))$yearGOptDot$gramsWordAhead""", RegexOption.IGNORE_CASE)
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

    // Несколько лет перед «годы/годов/годах…»: «1812 и 1813 годы», «1475—1481 годов», «7092 (1582) года»,
    // «1856/57 гг.», «1941/42 году» — порядковым каждое число, падеж по слову (было «одна тысяча восемьсот
    // двенадцать и тысяча восемьсот тринадцатый годы»). Одно число перед «годом/годам» — так же.
    private val yearWordSuffix = mapOf("год" to "й", "года" to "го", "году" to "ом", "годом" to "ым", "годы" to "й",
        "годов" to "го", "годам" to "му", "годами" to "ым", "годах" to "ом", "гг." to "го")
    private val yearChainRe = Regex(
        """(?<![\d\p{L}.,-])(\d{3,4}(?:\s*\(\d{3,4}\))?(?:(?:\s*(?:,|и|или|[-–—])\s*|/)\d{2,4}(?:\s*\(\d{3,4}\))?)+|\d{3,4}\s*\(\d{3,4}\)|\d{3,4}(?=\s+год(?:ом|ам)(?![а-яё])))""" +
            """(\s*)(год\+?(?:ами|ам|ах|ов|ом|ы|у|а)|год|гг\.|г\.)(?![а-яё])""", RegexOption.IGNORE_CASE)
    private val chainNumRe = Regex("""\d{2,4}""")
    private val monthBehindRe by lazy { Regex("""(?:$monthGenAlt)\s+$""", RegexOption.IGNORE_CASE) }
    private val chainDashRe = Regex("""\s*[-–—]\s*(?=\d)""")
    private val yearGgPrepRe = Regex("""(?<![\p{L}])(в|во|о|об|при|между|над|перед|к|ко|по|с|со|до|от|около|после|для|из|у)\s+$""", RegexOption.IGNORE_CASE)
    // «к 2030 году», «по 2020 году» — дательный (yearRe дал бы предложный «тридцатом»)
    private val yearDatRe = Regex("""(?<![\p{L}])(к|ко|по|благодаря|согласно)\s+(\d{3,4})(\s+)году(?![а-яё])""", RegexOption.IGNORE_CASE)
    // «в 1812 неожиданно», «В 1941 началась», «в 1941, когда» — год без слова «году»; следом не существительное,
    // не единица и не число (иначе «в 2000 рублей», «в 1500 м»). Без таблицы — только перед знаком или глаголом.
    // Диапазон через тире с «г.»/«гг.» («В 1831—40 г.») — не одинокий год: его читает yearGRangeWithPrepRe.
    private val yearBareVRe = Regex("""(?<![\p{L}])(в|во)\s+(1\d{3}|20\d{2})(?![\d/%-]|[.,:]\d|\s*(?:-|год|г\.|г(?![а-яё]))|\s*[–—]\s*\d{2,4}\s*гг?\.)""", RegexOption.IGNORE_CASE)
    // «в апреле 2020», «Апрель 1912», «лета 2021», «до мая 1500» — после месяца/сезона год в родительном.
    private val yearAfterMonthRe = Regex(
        """(?<![\p{L}])((?:$monthsAlt|лет[оа]|летом|зим[аыу]|зимой|весн[аыу]|весной|осен[ьи]|осенью))\s+(1\d{3}|20\d{2})(?![\d/%-]|[.,:]\d|\s*(?:год|г\.|г(?![а-яё])))""", RegexOption.IGNORE_CASE)
    // «М., 1850.», «СПб., 1850» — выходные данные: год именительным
    private val yearImprintRe = Regex("""(?<![\p{L}])((?:М|Л|К|СПб|СПБ|Спб|Пг|Киев|Москва|Ленинград|Петербург|Берлин|Париж)\.?,\s*)(1\d{3}|20\d{2})(?=\s*(?:[.,;)]|$))""")
    // «1941.» в начале строки перед заглавной — заголовок года
    // (переводы строк к этому месту уже пробелы — признак начала строки: начало текста или конец предложения)
    // («1000. Тысячный пункт» — номер пункта: годы с 1100)
    private val yearLineRe = Regex("""(?<=^|[.!?…]\s)(1[1-9]\d{2}|20\d{2})\.(?=\s+[А-ЯЁ])""")
    // «лета 7078 марта», «1709 июня 24-го» — год перед месяцем: число больше 31
    private val yearBeforeMonthRe = Regex("""(?<![\d.,-])(\d{3,4})(\s+)($monthGenAlt)(?![а-яё])""", RegexOption.IGNORE_CASE)
    // «под 964 г.», «Под 1185 г.» — творительный
    private val yearPodRe = Regex("""(?<![\p{L}])(под)\s+(\d{3,4})\s*г\.?(?![а-яё])""", RegexOption.IGNORE_CASE)
    private val yearBirthWordRe = Regex("""(?<![\d\p{L}])(\d{4})\s*(?:г\.|года)\s+(рождения|выпуска|издания|постройки)(?![а-яё])""", RegexOption.IGNORE_CASE)
    private val genPluralLikeRe = Regex("""\p{L}{2,}(?:ов|ев|ей|ий|ых|их)""")
    private val verbLikeAheadRe = Regex("""^\s*(?:[,.;:!?)–—]|$|[а-яё]+(?:ла|ло|ли|лся|лась|лось|лись|ет|ит|ют|ят|ут|ат|ется|ится|ются|ятся)(?![а-яё]))""")
    private fun notCountedAhead(rest: String): Boolean {
        if (moneyOrUnitAheadRe.containsMatchIn(rest)) return false
        val word = tailWordRe.find(rest)?.groupValues?.get(1)?.lowercase()
        val m = morph
        if (word == null || m == null) return verbLikeAheadRe.containsMatchIn(rest)
        val t = m.tags(word)
        // слова нет в таблице: «зивертов» (род. мн.) — счёт, «неожиданно», «стал», «без» — год
        if (t == 0) return !genPluralLikeRe.matches(word)
        if (Morph.isNoun(t) && !Morph.isAdjective(t)) return false
        if (Morph.isAdjective(t) && Case.GEN in Morph.adjCases(t, null, plural = true)) return false
        return true
    }
    private fun yearsBare(text: String): String {
        var s = yearChainRe.replace(text) { m ->
            var noun = m.groupValues[3].lowercase().replace("+", "")
            // диапазон с «г.» («В 300-200 г.», «весом 200-300 г.») — граммы или годы решают правила ниже
            if (noun == "г." && '(' !in m.groupValues[1]) return@replace m.value
            // «7092 (1582) г.» — год, падеж по предлогу или по дате перед ним («16 ноября 7092 (1582) г.» — родительный)
            if (noun == "г.") noun = when (yearGgPrepRe.find(text.substring(0, m.range.first))?.groupValues?.get(1)?.lowercase()) {
                "в", "во", "о", "об", "при" -> "году"; "к", "ко", "по" -> "году"; null -> if (monthBehindRe.containsMatchIn(text.substring(0, m.range.first))) "года" else "год"
                else -> "года"
            }
            // «гг.» — падеж по предлогу, без него именительный, у «1856/57 гг.» (сезон, учебный год) — родительный
            if (noun == "гг.") noun = when (yearGgPrepRe.find(text.substring(0, m.range.first))?.groupValues?.get(1)?.lowercase()) {
                "в", "во", "о", "об", "при" -> "годах"; "между", "над", "перед" -> "годами"; "к", "ко", "по" -> "годам"
                null -> if ('/' in m.groupValues[1]) "годов" else "годы"
                else -> "годов"
            }
            val prep = yearGgPrepRe.find(text.substring(0, m.range.first))?.groupValues?.get(1)?.lowercase()
            val sfx = if (noun == "году" && prep in setOf("к", "ко", "по")) "му" else yearWordSuffix.getValue(noun)
            chainDashRe.replace(chainNumRe.replace(m.groupValues[1]) { it.value + "-" + sfx }, " – ") + m.groupValues[2].ifEmpty { " " } +
                (if (m.groupValues[3] == "гг." || m.groupValues[3] == "г.") noun + dotAfter(text, m) else m.groupValues[3])
        }
        s = yearDatRe.replace(s) { m -> "${m.groupValues[1]} ${m.groupValues[2]}-му${m.groupValues[3]}году" }
        s = yearPodRe.replace(s) { m -> "${m.groupValues[1]} ${m.groupValues[2]}-ым годом" + gDot(m, s) }
        s = yearBirthWordRe.replace(s) { m -> "${m.groupValues[1]}-го года ${m.groupValues[2]}" }
        s = yearBareVRe.replace(s) { m -> if (notCountedAhead(s.substring(m.range.last + 1))) "${m.groupValues[1]} ${m.groupValues[2]}-ом" else m.value }
        s = yearAfterMonthRe.replace(s) { m -> if (notCountedAhead(s.substring(m.range.last + 1))) "${m.groupValues[1]} ${m.groupValues[2]}-го" else m.value }
        s = yearImprintRe.replace(s) { m -> m.groupValues[1] + m.groupValues[2] + "-й" }
        s = yearLineRe.replace(s) { m -> m.groupValues[1] + "-й." }
        s = yearBeforeMonthRe.replace(s) { m -> if (m.groupValues[1].toInt() > 31) "${m.groupValues[1]}-го${m.groupValues[2]}${m.groupValues[3]}" else m.value }
        return s
    }

    private fun yearsWithG(text: String): String {
        // Одинокий год в скобках после слова — «Аустерлицем (1805)», «Джокер (2019)»: порядковое.
        var s = yearInParensRe.replace(yearsBare(yearGgDottedRe.replace(text, " гг."))) { m -> "(${m.groupValues[1]}-й)" }
        s = bareOrdinalYearRe.replace(s) { m -> m.groupValues[1] + "-" + m.groupValues[2] }
        s = yearBirthRe.replace(s) { m -> m.groupValues[1] + "-го года рождения" }
        s = yearPairVRe.replace(s) { m -> "${m.groupValues[1]} ${m.groupValues[2]}-м и ${m.groupValues[3]}-м годах" }
        s = yearRangeSRe.replace(s) { m ->
            val (prep, y1, g1, mid, y2, g2) = m.destructured
            val rest = s.substring(m.range.last + 1)
            // пять цифр — год только перед «н. э.» («с 12500 по 9500 до н. э.»)
            if ((y1.length == 5 || y2.length == 5) && !eraAheadRe.containsMatchIn(rest)) return@replace m.value
            // Без «г.»/«года» и эры — годы, только если оба четырёхзначные в 1000–2100 и следом не деньги, не единица
            // и не существительное: «с 8000 до 6600 руб.», «с 210 до 1040 за 2 месяца», «со 100 до 120 км/ч» — числа
            // (было «с восьмитысячного до шесть тысяч шестисотого года»).
            if (g1.isEmpty() && g2.isEmpty() && !eraAheadRe.containsMatchIn(rest) &&
                (listOf(y1, y2).any { it.length != 4 || it.toInt() !in 1000..2100 } || moneyOrUnitAheadRe.containsMatchIn(rest) ||
                    countedNounAhead(rest))) return@replace m.value
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
        s = gospodaRe.replace(s) { m ->
            val w = m.groupValues[1]
            when {
                w.endsWith("ым") || w.endsWith("им") -> "господам"; w.endsWith("ых") || w.endsWith("их") -> "господ"
                w.endsWith("ыми") || w.endsWith("ими") -> "господами"; else -> "господа" }
        }
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
            // «ок. 8 г.», «ок. 300 г.» при весе в контексте — граммы; четыре цифры — год всегда (вес так не пишут)
            if (prep.equals("ок.", true) && y.length <= 3 && massContext(s, m.range.first, m.range.last + 1)) return@replace m.value
            "$prep $y-го года" + gDot(m, s)
        }
        s = yearGShortPrepRe.replace(s) { m ->
            val (prep, y) = m.destructured
            // «ок.» с одной цифрой — вес («Монета (ок. 8 г.)»): годов «ок. 8 г.» в корпусе нет
            // «во 2 г.» — только год: вес с «во» не пишут («во двух граммах» неграмотно; повторное ревью M-a)
            if (!prep.equals("во", true) && massContext(s, m.range.first, m.range.last + 1) || prep.equals("ок.", true) && y.length == 1) return@replace m.value
            "$prep $y-" +(if (prep.equals("в", true) || prep.equals("во", true)) "м году" else "го года") + gDot(m, s)
        }
        s = yearGParenRe.replace(s) { m ->
            if (m.groupValues[1].toInt() % 50 == 0 || massWordBehindRe.containsMatchIn(s.substring(maxOf(0, m.range.first - 40), m.range.first)) ||
                massContext(s, m.range.first, m.range.last + 1, paren = true)) m.value
            else "${m.groupValues[1]}-й год"
        }
        s = yearGBareRe.replace(s) { m ->
            val y = m.groupValues[1]
            // больше 2100 — граммы, кроме года до нашей эры («Саргон I (2750 г. до н. э.)»)
            if (y.length == 4 && y.toInt() > 2100 && !eraAheadRe.containsMatchIn(s.substring(m.range.last + 1))) m.value else "$y-й год" + gDot(m, s)
        }
        return s
    }

    // 6a. День + месяц словом («20 июня», «к 1 сентября») — порядковое среднего рода, падеж по
    // предлогу перед числом: «к» — дательный, «с/до/после/от» — родительный, иначе именительный.
    // Запускается после cases(): там числа перед месяцем нарочно не трогаются (numTailExclude).
    private val dayPrepRe = Regex("""(?<![а-яё])(к|ко|с|со|до|после|от|начиная\s+с|перед|по|на)\s+$""", RegexOption.IGNORE_CASE)
    // то же для цифровой даты (dates()), плюс «за»: «за 26.05» — «за двадцать шестое мая»
    private val datePrepRe = Regex("""(?<![а-яё])(к|ко|с|со|до|после|от|начиная\s+с|перед|по|на|за)\s+$""", RegexOption.IGNORE_CASE)
    // глагол изменения перед «на»: «ВВП вырос на 2.08.» — дробь, не дата. Граница слова слева и только окончания прошедшего:
    // «подросткам на 1.09.» — дата
    private val changeNaBehindRe = Regex(
        """(?<![\p{L}])(?:(?:вы|под)рос(?:ла|ло|ли)?|(?:упал|снизил|увеличил|уменьшил|сократил|подорожал|подешевел|прибавил|""" +
            """потерял|повысил|понизил|опустил|поднял)(?:а|о|и|ся|ась|ось|ись)?)\s+на\s$""",
        RegexOption.IGNORE_CASE)
    // разряд словом или сокращением, процент — за числом (dates(), sectionLabelRe)
    // только формы, что встают после дроби («1.5 тысячи», «12.3 миллиона», «2.5 тысяч»): именительный («Тысяча и одна ночь»,
    // «миллионы школьников») — не разряд при числе; «тысячелетия» — тоже
    private const val SCALE_AHEAD = """(?:(?:тыс|млн|млрд|трлн|тысячи|тысяч|миллиона|миллионов|миллиарда|миллиардов|""" +
        """триллиона|триллионов)(?![\p{L}]))"""
    private val scaleAheadRe = Regex("""^\s*$SCALE_AHEAD""", RegexOption.IGNORE_CASE)
    private val percentAheadRe = Regex("""^\s*%""")
    // «в 7.12» перед временем: dates() его не берёт, читает timeDots()
    private val timeBehindRe = Regex("""(?<![\p{L}])(?:в|во)\s$""", RegexOption.IGNORE_CASE)
    // Дата с годом без предлога («15 марта 2021 года») — родительный: так читается обстоятельство времени.
    // Без года — родительный тоже, когда это обстоятельство в предложении: глагол перед датой («согласовали 1 июля»)
    // или фраза продолжается после неё («15 июня царь выехал»). Одна дата («5 мая», «Встреча 3 дек.»), подлежащее
    // («8 марта прошло тихо») и «сегодня 1 мая» — именительный. «по/на 3 марта» — винительный, он же именительный.
    private val dayVerbBehindRe = Regex("""(?<![\p{L}])\p{L}+(?:ли|ла|ло|лся|лась|лось|лись|ет|ит|ют|ят|ут|ат|ется|ится|ются|ятся|ешь|ишь)\s+$""", RegexOption.IGNORE_CASE)
    private val dayNomBehindRe = Regex("""(?<![\p{L}])(?:сегодня|завтра|вчера|послезавтра|наступило|наступит|наступает|было|будет|это|есть|стало)\s+$""", RegexOption.IGNORE_CASE)
    private val dayGenAheadRe = Regex("""^\s+(?!(?:[–—-]|\p{L}+(?:ло|лось|ет|ит)(?![\p{L}])|это|является|был|была|было|будет))[\p{L}\d«"]""")
    private fun daySuffix(before: String, withYear: Boolean = false, after: String = "") = when (dayPrepRe.find(before)?.groupValues?.get(1)?.lowercase()) {
        null -> if (withYear) "го" else if (!dayNomBehindRe.containsMatchIn(before) &&
            (dayVerbBehindRe.containsMatchIn(before) || dayGenAheadRe.containsMatchIn(after))) "го" else "е"
        "к", "ко" -> "му"
        "перед" -> "ым"
        "по", "на" -> "е"
        else -> "го"
    }
    // Несколько дней перед месяцем («29 и 30 августа», «1—5 марта», «7/18 июля») — все в одном падеже
    // (было «двадцать девять и тридцатые августа»).
    private val dayMonthRe = Regex("""(?<![\d-])(\d{1,2}(?:(?:\s*(?:,|и|или|[–—-])\s*|/)\d{1,2})*)(\s+)($monthGenAlt)(?![а-яё])(?=(\s+\d{4}(?![\d-]))?)""", RegexOption.IGNORE_CASE)
    private val dayNumRe = Regex("""\d{1,2}""")
    private fun dayMonth(text: String) = dayMonthRe.replace(dayMonthBeforeRe.replace(text) { m ->
        // «Октября 24», «Сентября 8» — месяц перед днём в начале фразы, старые книги: родительный
        if (m.groupValues[2].toInt() in 1..31) "${m.groupValues[1]} ${m.groupValues[2]}-го" else m.value
    }) { m ->
        val (days, sp, month, year) = m.destructured
        val nums = dayNumRe.findAll(days).map { it.value.toInt() }.toList()
        if (nums.any { it !in 1..31 }) return@replace m.value
        val after0 = text.substring(m.range.last + 1)
        // год следом, уже с суффиксом («7092-го (1582-го) года») — тоже дата с годом
        val sfx = daySuffix(text.substring(0, m.range.first), year.isNotEmpty() || yearSuffixedAheadRe.containsMatchIn(after0), after0.let { a ->
            // за месяцем может стоять год — смотрим дальше него
            yearAfterDayRe.find(a)?.let { a.substring(it.range.last + 1) } ?: a })
        val conn = chainDashRe.replace(dayNumRe.replace(days) { "${it.value}-$sfx" }, " – ")
        "$conn$sp$month"
    }
    private val yearSuffixedAheadRe = Regex("""^\s+\d{3,4}-го(?![а-яё])""")
    private val yearAfterDayRe = Regex("""^\s+\d{4}(?:\s*(?:г\.|года|г(?![а-яё])))?""")
    private val dayMonthBeforeRe = Regex("""(?<=^|[.!?…]\s|\n)((?-i:${monthGenitive.drop(1).joinToString("|") { m -> m.replaceFirstChar { it.uppercase() } }}))\s+(\d{1,2})(?![\d.,:]|-?\p{L})""")

    // 6b. «г.» перед словом с заглавной буквы (task 18 п.7) — это «город», а не год: годовые
    // варианты «г.» уже разобраны выше (нужен цифровой год перед точкой), здесь остаётся только
    // «г. Москва» и подобное.
    // После числа это граммы и конец предложения: «Вес 5 г. Далее» (было «пять город Далее»).
    private val cityAbbrevRe = Regex("""(?<![\p{L}\d])(?<!\d\s?)г\.(?=\s*[А-ЯЁ])""")
    private fun cityAbbrev(text: String) = cityAbbrevRe.replace(text, "город")

    // 7. Родительный падеж количественного через дефис: «5-ти» → «пяти», «2-ух» → «двух».
    private val genitiveUnits = mapOf(1 to "одного", 2 to "двух", 3 to "трёх", 4 to "четырёх", 5 to "пяти",
        6 to "шести", 7 to "семи", 8 to "восьми", 9 to "девяти", 10 to "десяти", 11 to "одиннадцати",
        12 to "двенадцати", 13 to "тринадцати", 14 to "четырнадцати", 15 to "пятнадцати", 16 to "шестнадцати",
        17 to "семнадцати", 18 to "восемнадцати", 19 to "девятнадцати")
    private val genitiveTens = mapOf(20 to "двадцати", 30 to "тридцати", 40 to "сорока", 50 to "пятидесяти",
        60 to "шестидесяти", 70 to "семидесяти", 80 to "восьмидесяти", 90 to "девяноста")
    private val cardinalGenSuffixRe = Regex("""(\d+)-(ти|и|ух|ех|ёх|хъ|ми|мя|тью|ум|ём|ть)(?![а-яё])""", RegexOption.IGNORE_CASE)
    private val genPrepBehindRe = Regex("""(?<![\p{L}])(?:около|до|от|из|без|для|у|после|кроме|вместо|среди)\s+$""", RegexOption.IGNORE_CASE)
    private val insNounAheadRe = Regex("""^\s+(?:\p{L}+\s+)?\p{L}+(?:ами|ями|ьми)(?![а-яё])""", RegexOption.IGNORE_CASE)

    private fun genitiveCardinal(n: Int): String = when {
        n in 1..19 -> genitiveUnits.getValue(n)
        n in 20..99 && n % 10 == 0 -> genitiveTens.getValue(n)
        n in 20..99 -> genitiveTens.getValue(n / 10 * 10) + " " + genitiveUnits.getValue(n % 10)
        else -> cardinal(n.toLong())
    }

    // «7-мя», «5-тью» — творительный, «2-ум», «3-ём» — дательный, «6-ть» — просто число; «7-ми» — родительный/предложный,
    // перед творительным («7-ми поездами») — творительный; круглые десятки и годы с «-ми» — десятилетия: «90-ми годами»,
    // «между 1990-ми и 2000-ми» — «девяностыми» (было «семь ми», «одной тысячей девятьюстами девяноста ми»).
    private fun cardinalGenitiveSuffix(text: String) = cardinalGenSuffixRe.replace(text) { m ->
        val n = m.groupValues[1].toLongOrNull() ?: return@replace m.value
        when (m.groupValues[2].lowercase()) {
            "ми" -> if (n >= 20 && n % 10 == 0L && (n < 100 || n >= 1000)) ordinal(n, "ыми")
                else if (insNounAheadRe.containsMatchIn(text.substring(m.range.last + 1))) Declension.cardinal(n, Case.INS)
                else Declension.cardinal(n, Case.GEN)
            // «около 7-мя человек» — опечатка в суффиксе, падеж решает предлог
            "мя", "тью" -> Declension.cardinal(n, if (genPrepBehindRe.containsMatchIn(text.substring(0, m.range.first))) Case.GEN else Case.INS)
            "ум", "ём" -> Declension.cardinal(n, Case.DAT)
            "ть" -> cardinal(n)
            else -> if (n <= Int.MAX_VALUE) genitiveCardinal(n.toInt()) else m.value
        }
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
        "сторонн", "этапн", "частн", "страничн", "мильн", "футов", "пудов", "гектарн", "ваттн", "вольтов", "лошадин", "местечков",
        "фунтов", "вершков", "верстн", "саженн", "аршинн", "золотников", "пушечн", "орудийн", "вёрстн")
    // Через пробел (без дефиса) — только там, где число почти не бывает самостоятельным («5 тонный»);
    // «5 летних дней», «2 местных жителя» — обычные словосочетания, их не склеиваем.
    private val compoundSpaceStems = setOf("тонн", "этажн", "кратн", "процентн", "ступенчат")
    // и дореформенные окончания («12-фунтоваго», «16-ти-фунтовыя», «20-тысячнымъ»)
    private val adjEndAlt = """(?:ый|ая|ое|ые|ого|ому|ым|ом|ой|ую|ых|ыми|ий|яя|ее|ие|его|ему|им|ем|ей|юю|их|ими|аго|яго|ыя|ія|ыхъ|ымъ|омъ)ъ?"""
    private val compoundHyphenRe = Regex(
        """(?<![\p{L}\d])(\d+)(?:-?(?:ти|ми|ух|ех|ёх|хъ|х|и))?\s*[-–]\s*(${compoundStems.joinToString("|")})($adjEndAlt)(?![а-яё])""",
        RegexOption.IGNORE_CASE
    )
    private val compoundSpaceRe = Regex(
        """(?<![\p{L}\d])(\d+)(?:-?(?:ти|ух|ех|ёх|х|и))?\s*(${compoundSpaceStems.joinToString("|")})($adjEndAlt)(?![а-яё])""",
        RegexOption.IGNORE_CASE
    )
    // С падежным суффиксом («2х местный», «5-ти местный») пробел не мешает — число уже не самостоятельное.
    private val compoundSuffixSpaceRe = Regex(
        """(?<![\p{L}\d])(\d+)-?(?:ти|ми|ух|ех|ёх|хъ|х|и)(?:\s+|(?=\p{L}))(${compoundStems.joinToString("|")})($adjEndAlt)(?![а-яё])""",
        RegexOption.IGNORE_CASE
    )
    // «40000-ным войском» — «сорокатысячным», «1000-ный» — «тысячный»
    private val compoundThousandRe = Regex("""(?<![\p{L}\d])(\d{1,3})000-(н)($adjEndAlt)(?![а-яё])""", RegexOption.IGNORE_CASE)
    // «15-тыс. отряд» — «пятнадцатитысячный»; окончание по существительному следом
    private val compoundThousandAbbrRe = Regex("""(?<![\p{L}\d])(\d{1,3})-тыс\.\s+(?=(\p{L}+))""", RegexOption.IGNORE_CASE)
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
    private val halfCompoundRe = Regex("""(?<![\d\p{L}])½-(?=\p{L})""")
    private fun compounds(text: String): String {
        var s = compoundPercentRe.replace(text) { m ->
            val n = m.groupValues[1].toLongOrNull() ?: return@replace m.value
            compoundStem(n) + "процент" + m.groupValues[2]
        }
        s = compoundThousandRe.replace(s) { m ->
            val k = m.groupValues[1].toInt()
            (if (k == 1) "" else compoundBelow1000(k)) + "тысячн" + m.groupValues[3]
        }
        s = compoundThousandAbbrRe.replace(s) { m ->
            val k = m.groupValues[1].toInt()
            val noun = m.groupValues[2].lowercase()
            val end = when { noun.endsWith("а") || noun.endsWith("я") -> "ая"; noun.endsWith("о") || noun.endsWith("е") -> "ое"; noun.endsWith("и") || noun.endsWith("ы") -> "ые"; else -> "ый" }
            (if (k == 1) "" else compoundBelow1000(k)) + "тысячн" + end + " "
        }
        // «½-пудовой» — «полупудовой»
        s = halfCompoundRe.replace(s, "полу")
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
    // Размер «5х10 м», «3x4x5» — «пять на десять»: буквой «х»/«x» пишут размер, «×» остаётся умножением (arithmetic).
    // Не трогаем «0x1F» (одиночный «0») и латиницу за числом: «5800X3D».
    private val dimensionRe = Regex("""(?<=\d)(?<!(?<![\d.,])0)\s*[xXхХ]\s*(?=\d++(?:[.,]\d++)?+(?![A-Za-z](?!\d)))""")
    private fun dimensions(text: String) = dimensionRe.replace(text, " на ")

    // «x = - 5», «x = −5» — минус после «равно» (дефис с пробелом punctuation() уже сделал тире)
    private val equalsMinusRe = Regex("""\s*=\s*[-−–—]\s*(?=\d)""")
    // Степень надстрочными: «10⁻³» — «десять в минус третьей степени», «10²» — «в квадрате», «2³» — «в кубе»
    private val supDigits = "⁰¹²³⁴⁵⁶⁷⁸⁹"
    private val powerRe = Regex("""(?<=\d)([⁻⁺]?)([⁰¹²³⁴⁵⁶⁷⁸⁹]+)(?![⁰-⁹])""")
    private fun powers(text: String) = powerRe.replace(text) { m ->
        val e = m.groupValues[2].map { supDigits.indexOf(it) }.joinToString("").toLongOrNull() ?: return@replace m.value
        val minus = m.groupValues[1] == "⁻"
        when {
            !minus && e == 2L -> " в квадрате"
            !minus && e == 3L -> " в кубе"
            else -> " в " + (if (minus) "минус " else "") + ordinal(e, "ой") + " степени"
        }
    }
    // «12,5 × 8 м», «2560 × 1440 пикселей» — размер, «на»; «2×3» и «4×200 метров» — умножение, как было
    private val timesSizeRe by lazy { Regex("""(?<=\d)\s*×\s*(?=\d+(?:[.,]\d+)?\s*(?:(?:$unitAltPattern)(?![\p{L}\d/])|пиксел|точ[екк]|px))""") }
    // «x ≥ 5», «x < 10» — сравнение словами; «<» и «>» только в пробелах («<3» — сердце, «->» — стрелка)
    private val compareRe = Regex("""\s*([≥≤≠])\s*(?=[-−]?\d)|(?<=[\p{L}\d)])\s+([<>])\s+(?=[-−]?\d)""")
    private val compareWords = mapOf("≥" to "больше или равно", "≤" to "меньше или равно", "≠" to "не равно", "<" to "меньше", ">" to "больше")
    // «√–1», «√2» — «корень из минус одного», «корень из двух»
    private val sqrtRe = Regex("""√\s*([-−–]?)\s*(\d+)(?![\d.,])""")
    // давление «120/80» — «сто двадцать на восемьдесят»: рядом «давлен…» или «мм рт.»
    private val pressureRe = Regex("""(?<![\d/])(\d{2,3})/(\d{2,3})(?![\d/])""")
    private val pressureContextRe = Regex("""давлени\p{L}*[^.!?]{0,40}$""", RegexOption.IGNORE_CASE)
    private val pressurePrepRe = Regex("""(?<![\p{L}])(с|со|до|от|около|выше|ниже|свыше)\s+$""", RegexOption.IGNORE_CASE)
    // «в пропорции 1:4», «в масштабе 1:50 000», «соотношение 2:1» — «один к четырём»
    private val ratioRe = Regex("""(?<=(?:пропорци|масштаб|соотношени|отношени)\p{L}{0,3}\s)(\d+)\s?:\s?(\d{1,3}(?: \d{3})*|\d+)(?![\d:])""", RegexOption.IGNORE_CASE)
    private fun arithmetic(text: String): String {
        var s = timesSizeRe.replace(text, " на ")
        s = compareRe.replace(s) { m -> " " + compareWords.getValue(m.groupValues[1].ifEmpty { m.groupValues[2] }) + " " }
        s = sqrtRe.replace(s) { m ->
            val n = m.groupValues[2].toLongOrNull() ?: return@replace m.value
            "корень из " + (if (m.groupValues[1].isNotEmpty()) "минус " else "") + Declension.cardinal(n, Case.GEN)
        }
        s = pressureRe.replace(s) { m ->
            val before = s.substring(0, m.range.first)
            if (!pressureContextRe.containsMatchIn(before) && !s.substring(m.range.last + 1).trimStart().startsWith("мм рт")) return@replace m.value
            val (a, b) = m.destructured
            val case = if (pressurePrepRe.containsMatchIn(before)) Case.GEN else Case.NOM
            // второе число цифрами: следом «мм рт. ст.» раскрывает units()
            Declension.cardinal(a.toLong(), case) + " на " + b
        }
        s = ratioRe.replace(s) { m ->
            val a = m.groupValues[1].toLongOrNull() ?: return@replace m.value
            val b = m.groupValues[2].replace(" ", "").toLongOrNull() ?: return@replace m.value
            cardinal(a) + " к " + Declension.cardinal(b, Case.DAT)
        }
        s = minusBeforeEqualsRe.replace(equalsMinusRe.replace(s, " равно минус "), " минус ")
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
        // «м/сек», «км/час», «мм рт. ст.» — жалоба пользователя 06.10.2026: читалось «м сек», «ээр тэ ст»; точку в конце
        // ключа не берём: перед строчной её съедает unitRe, в конце предложения она остаётся точкой предложения
        put("м/сек", u("метр", " в секунду")); put("км/сек", u("километр", " в секунду")); put("км/час", u("километр", " в час"))
        for (k in listOf("мм рт. ст", "мм. рт. ст", "мм рт.ст", "мм. рт.ст", "мм рт ст")) put(k, u("миллиметр", " ртутного столба"))
        for (k in listOf("мм вод. ст", "мм. вод. ст", "мм вод.ст", "мм вод ст")) put(k, u("миллиметр", " водяного столба"))
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
        for (k in listOf("п.п.", "п. п.", "п.п", "п. п")) put(k, UnitForms(Triple("процентный пункт", "процентных пункта", "процентных пунктов")))
        put("б.п.", UnitForms(Triple("базисный пункт", "базисных пункта", "базисных пунктов")))
        put("л.с.", UnitForms(Triple("лошадиная сила", "лошадиные силы", "лошадиных сил"), feminine = true))
        put("л. с.", UnitForms(Triple("лошадиная сила", "лошадиные силы", "лошадиных сил"), feminine = true))
        for (k in listOf("уд/мин", "уд./мин")) put(k, u("удар", " в минуту"))
        for (k in listOf("мА·ч", "мА⋅ч", "мА*ч", "мА-ч")) put(k, u("миллиампер-час"))
        for (k in listOf("Н·м", "Н⋅м", "Н*м", "Нм")) put(k, u("ньютон-метр"))
        put("дБ", u("децибел", genPl = "децибел"))
        put("Гн", UnitForms(Triple("генри", "генри", "генри"))); put("мГн", UnitForms(Triple("миллигенри", "миллигенри", "миллигенри")))
        for (k in listOf("м/с²", "м/с2", "м/с^2")) put(k, u("метр", " в секунду в квадрате"))
        for (k in listOf("моль/л", "mol/l")) put(k, u("моль", " на литр"))
        for (k in listOf("кв. км", "кв.км")) put(k, sq("километр"))
        for (k in listOf("кв. миль", "кв. мили", "кв. миля", "кв.миль")) put(k, UnitForms(Triple("миля", "мили", "миль"), feminine = true, prefix = Triple("квадратная", "квадратных", "квадратных")))
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
    // Строчный фолбэк — не для слова с заглавной: «13 См.: с. 22» — «смотри», не «сантиметров».
    private fun unitOf(key: String): UnitForms? = if (key == "М" || key == "мкМ") null else unitTable[key] ?:
        unitTable[key.lowercase()]?.takeUnless { key.length > 1 && key[0].isUpperCase() && key.drop(1).any { it.isLowerCase() } }
    private val unitAltPattern = unitTable.keys.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }
    // Единица сразу после «N.N» (review final-fix п.8) — используется в dates() выше, чтобы не
    // принять такую пару за дату без года: «12.5 км/ч» — дробь, читает fractionsSlash()/numberRe.
    // Предлог в одну букву («12.05.2025 в 09:07», «05.09 в 23:59») — не вольты и не век.
    private val dateTailUnitRe = Regex("""^\s*(?!(?-i:[вскоуиа])(?![\p{L}\d/.]))(?:$unitAltPattern)(?![\p{L}\d/.])""", RegexOption.IGNORE_CASE)
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
        "после", "порядка", "не более", "не менее", "достиг[а-яё]*").associateWith { Case.GEN } +
        mapOf("к" to Case.DAT, "ко" to Case.DAT, "при" to Case.PRE, "о" to Case.PRE, "об" to Case.PRE, "в" to Case.PRE, "во" to Case.PRE,
            "равен" to Case.DAT, "равна" to Case.DAT, "равны" to Case.DAT)
    // «в 100 г продукта» — предложный, но «весом в 300 г», «в 3 ч дня» — нет: винительный = именительный.
    private val unitInAccBehindRe = Regex("""(?:весом|массой|нетто|упаковка|пачка|банка|порция|доза|объёмом|длиной|высотой|шириной|глубиной|ростом|ценой|стоимостью|мощностью|скоростью|радиусом|диаметром|весит|размером|толщиной|весу)\s+$""", RegexOption.IGNORE_CASE)
    private val timeUnitKeys = setOf("ч", "мин", "сек", "мс", "мкс", "нс")
    // Винительный перед женской единицей виден только у «1»: «стоит 1 ₺» → «одну лиру», «на 1 мс» → «одну миллисекунду».
    private val unitAccBehindRe = Regex(
        """(?<![\p{L}])(?:на|за|через|про|спустя|сто(?:ит|ил[аио]?|ят)|вес(?:ит|ил[аио]?|ят)|ждал[аио]?|ждать|занял[аио]?|занимает|""" +
        """длит(?:ся|ься)|длил(?:ся|ась|ось)|каждую|всю|целую|ещё|еще)\s+$""", RegexOption.IGNORE_CASE)
    private val headingNumBehindRe = Regex("""(?<![\p{L}])$romanNounBeforeAlt\s+$""", RegexOption.IGNORE_CASE)
    private val unitTriggerAlt = unitTriggerCase.keys.joinToString("|") { it.replace(" ", "\\s+") }
    private val unitRe = Regex(
        """(?:(?<![\p{L}])($unitTriggerAlt|ок\.)\s+)?(?<![\d/])(\d+(?:[.,]\d+)?)(?:\s*[-–]\s*(?=\p{L}{2})|[^\S\n]*)($unitAltPattern)(?![\p{L}\d/])(?!\.\d)(?:\.(?=\s*(?-i:[а-яё,;:])))?""",
        RegexOption.IGNORE_CASE
    )
    // Диапазон с единицей: «между 2 и 5 км» — оба числа и единица в творительном, «с/от 2 до 5 км»
    // — в родительном. До units(): иначе тот прочитает «5 км»/«до 5 км» сам, а cases() потом
    // не увидит второго числа и первое оставит именительным («с два до пяти километров»).
    private val unitRangeRe = Regex(
        """(?<![\p{L}])(между|с|со|от)\s+(\d+)\s+(и|до)\s+(\d+)\s*($unitAltPattern)(?![\p{L}\d/])""", RegexOption.IGNORE_CASE
    )
    // «до 30 – 40 см» — оба числа диапазона в падеже предлога (корпус ru-normalizr).
    private val unitDashRangeRe = Regex(
        """(?<![\p{L}])(до|от|около|более|менее|свыше|порядка|с|в|во|при|о|об|к|ко)\s+(\d+)\s*([-–—])\s*(\d+)\s*($unitAltPattern)(?![\p{L}\d/])""", RegexOption.IGNORE_CASE
    )
    private fun unitDashRange(text: String) = unitDashRangeRe.replace(text) { m ->
        val (prep, a, dash, b, unitKey) = m.destructured
        val u = unitOf(unitKey) ?: return@replace m.value
        val n1 = a.toLongOrNull() ?: return@replace m.value
        val n2 = b.toLongOrNull() ?: return@replace m.value
        // «в 10-20 км от города» — предложный, но «весом в 10-20 кг», «в 2-3 ч дня» — винительный: не трогаем
        val case = when (prep.lowercase()) {
            "в", "во" -> if (unitKey in timeUnitKeys || unitInAccBehindRe.containsMatchIn(text.substring(0, m.range.first))) return@replace m.value else Case.PRE
            "при", "о", "об" -> Case.PRE; "к", "ко" -> Case.DAT; else -> Case.GEN
        }
        "$prep ${Declension.cardinal(n1, case, u.feminine)} $dash ${Declension.cardinal(n2, case, u.feminine)} ${unitWord(u, n2, case)}"
    }
    // «между 2 ч 30 мин и 3 ч», «между 5 м 30 см и 6 м» — каждое число и единица в творительном
    private val betweenUnitPart by lazy { """\d+\s*(?!(?-i:[вскоуиа])(?![\p{L}\d/.]))(?:$unitAltPattern)(?![\p{L}\d/])""" }
    private val betweenUnitsRe by lazy { Regex("""(?<![\p{L}])(между)\s+((?:$betweenUnitPart\s*){1,2}?)\s*и\s+((?:$betweenUnitPart\s*){1,2})""", RegexOption.IGNORE_CASE) }
    private val unitPartRe by lazy { Regex("""(\d+)\s*(?!(?-i:[вскоуиа])(?![\p{L}\d/.]))($unitAltPattern)(?![\p{L}\d/])""", RegexOption.IGNORE_CASE) }
    private fun betweenUnits(text: String) = betweenUnitsRe.replace(text) { m ->
        fun ins(part: String) = unitPartRe.replace(part) { p ->
            val n = p.groupValues[1].toLongOrNull() ?: return@replace p.value
            val u = unitOf(p.groupValues[2]) ?: return@replace p.value
            Declension.cardinal(n, Case.INS, u.feminine) + " " + unitWord(u, n, Case.INS)
        }.trim()
        "${m.groupValues[1]} ${ins(m.groupValues[2])} и ${ins(m.groupValues[3])} "
    }
    private fun unitRange(text: String) = unitRangeRe.replace(text) { m ->
        val (prep, a, mid, b, unitKey) = m.destructured
        val case = when (prep.lowercase() to mid.lowercase()) { "между" to "и" -> Case.INS; "с" to "до", "со" to "до", "от" to "до" -> Case.GEN; else -> return@replace m.value }
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
    private val verstAheadRe = Regex("""^\s*(?:от|до|к|за)(?![\p{L}])""")
    private val verstForms = UnitForms(Triple("верста", "версты", "вёрст"), feminine = true)
    // «за 8,4 с.», «длилась 5с.» — «с» после числа перед знаком препинания или концом — секунды (как ключ «сек»);
    // перед словом это предлог («3 м с ветром»), его не трогаем.
    // «120 с.» в выходных данных — страницы: секунды только с дробью или после слова о времени.
    private val secondsRe = Regex("""(?<![\p{L}\d.,/])(\d+(?:[.,]\d+)?(?:\s*[–—-]\s*\d+)?) ?с(?=[.,;:!?)]|\s*$)""")
    private val secondsBehindRe = Regex("""(?<![\p{L}])(?:за|через|спустя|каждые|течение|длил\p{L}*|длит\p{L}*|занял\p{L}*|занимает|задержк\p{L}*|пауз\p{L}*|время|выдержк\p{L}*|интервал\p{L}*|раз в)\s+(?:\p{L}+\s+)?$""", RegexOption.IGNORE_CASE)
    private val unitSepSpaceRe = Regex("""(?<=\d ?)(\p{L}{1,5})(?: ?([/·]) ?| )(\p{L}{1,4})(?![\p{L}])""")
    private fun seconds(text: String) = secondsRe.replace(text) { m ->
        if (m.groupValues[1].any { it == ',' || it == '.' } || secondsBehindRe.containsMatchIn(text.substring(0, m.range.first))) m.groupValues[1] + " сек" else m.value
    }
    private fun units(text: String) = unitRe.replace(unitSepSpaceRe.replace(seconds(text)) { m ->
        val (left, sep, right) = m.destructured
        if (sep.isNotEmpty()) return@replace if (unitOf(left + sep + right) != null) left + sep + right else m.value
        if (right.equals("с", true) || right == "c") return@replace m.value
        unitTable.keys.firstOrNull { it.equals("$left/$right", true) || it.equals("$left·$right", true) } ?: m.value
    }) { m ->
        var (trigger, numStr, unitKey) = m.destructured
        // «Глава 5 В глубине души», «Часть 2 А потом» — заголовок без точки, дальше предлог/союз, не вольты и не амперы (жалоба 16.11.2026)
        if (trigger.isEmpty() && headingNumBehindRe.containsMatchIn(text.substring(0, m.range.first))) return@replace m.value
        // «220 В.» в конце предложения — вольты, не века: века больше тридцатого не бывает (сеть 110–400 В), дробных
        // тоже; заглавная «В.» — всегда вольты («1,15 В.»). Строчное «в.» больше тридцати перед «от/до» — вёрсты
        // старых книг: «в 75 в. от Казани» (было «в семидесяти пяти вольтах»).
        val big = (numStr.substringBefore(',').substringBefore('.').toLongOrNull() ?: 0) > 30
        val versts = unitKey == "в." && big && verstAheadRe.containsMatchIn(text.substring(m.range.last + 1))
        val volts = !versts && unitKey.equals("в.", true) && (big || unitKey == "В." || ',' in numStr || '.' in numStr)
        if (volts) unitKey = "В"
        val u = if (versts) verstForms else unitOf(unitKey) ?: return@replace m.value
        val hasFrac = numStr.contains(',') || numStr.contains('.')
        // «ок. 2 км» — «около двух километров»: «ок.» раскрывается здесь, единице нужен его падеж (было «около двух километра»;
        // ревью ru-fixes-2 M-4)
        val ok = trigger.equals("ок.", true)
        val head = if (trigger.isEmpty()) "" else if (ok) (if (trigger[0].isUpperCase()) "Около " else "около ") else "$trigger "
        if (ok) trigger = "около"
        val inAcc = trigger.lowercase() in setOf("в", "во") &&
            (unitKey in timeUnitKeys || unitInAccBehindRe.containsMatchIn(text.substring(0, m.range.first)))
        run {
            if (trigger.isNotEmpty() && !hasFrac && !inAcc) {
                val n = numStr.toLongOrNull() ?: return@replace m.value
                val case = trigger.lowercase().replace(Regex("\\s+"), " ").let { t -> unitTriggerCase[t] ?: if (t.startsWith("достиг")) Case.GEN else return@replace m.value }
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
                // дробь — род. ед.: «1,5 кубического метра»
                val pre = u.prefix?.let { (if (hasFrac) it.first.dropLast(2) + "ого" else plural(n, it)) + " " } ?: ""
                // дробь — род. ед.; у составной единицы прилагательное тоже: «0,25 процентного пункта»
                val form = if (hasFrac) u.forms.second.let { f -> if (' ' in f) f.replaceFirst(Regex("ых(?= )"), "ого") else f } else plural(n, u.forms)
                head + numStr + " " + pre + form + u.suffix
            }
        } + if (volts) "." else ""
    }

    // 8b. Градусы (task 18 п.4): число оставляем цифрами для numberRe ниже, сразу дописываем
    // слово. «°C»/«° C»/«°С» (кириллическая «С» тоже) — Цельсия, «°F» — по Фаренгейту,
    // одиночный «°» — просто «градус(а/ов)» по plural().
    private val degreeForms = Triple("градус", "градуса", "градусов")
    // «5 гр. С», «5 град. С» — тоже Цельсия, но только с заглавной «С»/«C»: «5 гр. с собой» — граммы (жалоба 06.10.2026)
    // Шкала через пробел — только заглавной («30 ° C», «20° Ц.»): «30° с уклоном» — предлог, не Цельсий.
    // Минуты и секунды угла: «55°45′», «45°30′15″», «42°30'» — «пятьдесят пять градусов сорок пять минут».
    private val degreeRe = Regex("""(-?\d+(?:[.,]\d+)?)\s*(?:°(?:\s*(\d{1,2})\s*[′'](?:\s*(\d{1,2}(?:[.,]\d+)?)\s*[″"])?)?(?:\s*(?-i:([CFСЦФ]))\.?|(?-i:([cfс])))?|(?-i:гр(?:ад)?\.?\s*([СC])))(?![\p{L}])""", RegexOption.IGNORE_CASE)
    // Предлог перед градусами задаёт падеж: «при -20°C» → «при минус двадцати градусах», «равен 30°» — «тридцати
    // градусам», «между 42°30' и 43°» — творительный обоих. Число в косвенном падеже — сразу словами.
    private val degreePrepRe = Regex("""(?<![\p{L}])(при|около|до|от|свыше|ниже|выше|к|равен|равна|равно|равны|между)\s+(?:плюс\s+|минус\s+|-)?$""", RegexOption.IGNORE_CASE)
    private val degreeAfterAndRe = Regex("""\s+и\s+(?:плюс\s+|минус\s+|-)?$""")
    private val degreeOblique = mapOf(Case.GEN to ("градуса" to "градусов"), Case.DAT to ("градусу" to "градусам"), Case.PRE to ("градусе" to "градусах"),
        Case.INS to ("градусом" to "градусами"))
    private val minuteCases = arrayOf(arrayOf("минута", "минуты", "минут"), arrayOf("минуты", "минут", "минут"), arrayOf("минуте", "минутам", "минутам"),
        arrayOf("минуту", "минуты", "минут"), arrayOf("минутой", "минутами", "минутами"), arrayOf("минуте", "минутах", "минутах"))
    private val secondCases = minuteCases.map { row -> row.map { it.replace("минут", "секунд") }.toTypedArray() }.toTypedArray()
    // «±5″» — секунды угла; «27″» без знака — дюймы
    // ASCII «"» — только секунды после «±»: после числа это чаще закрывающая кавычка
    private val inchRe = Regex("""(±\s*)?(?<![\d°′'])(\d+(?:[.,]\d+)?)\s*(″|(?<=±\s?\d{1,4}(?:[.,]\d{1,3})?\s?)")(?![\p{L}\d])""")
    // «55°45′ с. ш.», «74° в. д.» — широта и долгота
    private val geoRe = Regex("""(?<=(?:градус|минут|секунд)\p{L}{0,3})\s+(?:([сСюЮ])\.\s*ш\.|([вВзЗ])\.\s*д\.)""")
    private fun degrees(text: String): String {
        var lastBetween = -100
        val s = degreeRe.replace(text) { m ->
            val (numStr, min, sec, sign, signLow, gr) = m.destructured
            val unit = sign.ifEmpty { signLow }.ifEmpty { gr }
            val frac = ',' in numStr || '.' in numStr
            val neg = numStr.startsWith("-")
            val n = numStr.removePrefix("-").substringBefore(',').substringBefore('.').toLongOrNull() ?: return@replace m.value
            val before = text.substring(0, m.range.first)
            val prep = degreePrepRe.find(before)?.groupValues?.get(1)?.lowercase()
            var case = when (prep) { null -> null; "при" -> Case.PRE; "к", "равен", "равна", "равно", "равны" -> Case.DAT; "между" -> Case.INS; else -> Case.GEN }
            if (case == null && m.range.first - lastBetween < 6 && degreeAfterAndRe.containsMatchIn(before)) case = Case.INS
            if (prep == "между" || case == Case.INS) lastBetween = m.range.last + 1
            val one = n % 10 == 1L && n % 100 != 11L
            // дробь согласуется как 2–4 в родительном: «12,5 градуса»
            val noun = if (frac) "градуса" else degreeOblique[case]?.let { if (one) it.first else it.second } ?: plural(n, degreeForms)
            val suffix = when {
                unit.isEmpty() -> ""
                unit in "fFФ" -> " по Фаренгейту"
                else -> " Цельсия"
            }
            val num = if (case != null && case != Case.GEN && !frac) (if (neg) "минус " else "") + Declension.cardinal(n, case) else numStr
            val sb = StringBuilder("$num $noun")
            fun part(v: String, forms: Array<Array<String>>) {
                val k = v.substringBefore(',').substringBefore('.').toLongOrNull() ?: return
                val c = case ?: Case.NOM
                val word = if (',' in v || '.' in v) forms[Case.GEN.ordinal][0] else if (c == Case.NOM || c == Case.ACC)
                    forms[c.ordinal][if (k % 10 == 1L && k % 100 != 11L) 0 else if (k % 10 in 2..4 && k % 100 !in 12..14) 1 else 2]
                    else forms[c.ordinal][if (k % 10 == 1L && k % 100 != 11L) 0 else 2]
                sb.append(' ').append(if (case != null && case != Case.GEN && ',' !in v && '.' !in v) Declension.cardinal(k, c, feminine = true) else v).append(' ').append(word)
            }
            if (min.isNotEmpty()) part(min, minuteCases)
            if (sec.isNotEmpty()) part(sec, secondCases)
            sb.append(suffix)
            sb.toString() + if (sign.isNotEmpty() && m.value.endsWith(".") && sentenceEndAheadRe.containsMatchIn(text.substring(m.range.last + 1))) "." else ""
        }
        val g = geoRe.replace(s) { m ->
            " " + when ((m.groupValues[1] + m.groupValues[2]).lowercase()) { "с" -> "северной широты"; "ю" -> "южной широты"; "в" -> "восточной долготы"; else -> "западной долготы" } +
                dotAfter(s, m)
        }
        return inchRe.replace(g) { m ->
            val v = m.groupValues[2]
            val k = v.substringBefore(',').substringBefore('.').toLongOrNull() ?: return@replace m.value
            val frac = ',' in v || '.' in v
            if (m.groupValues[1].isNotEmpty()) "± $v " + if (frac) "секунды" else plural(k, Triple("секунда", "секунды", "секунд"))
            else "$v " + if (frac) "дюйма" else plural(k, Triple("дюйм", "дюйма", "дюймов"))
        }
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
    // Часть номера с ведущим нулём («2.03.1») — по цифрам: «ноль три»; длиннее Long — тоже (toLong падал).
    private fun sectionPart(p: String) = p.toLongOrNull()?.takeIf { p.length == 1 || !p.startsWith("0") }?.let { cardinal(it) }
        ?: p.map { cardinal((it - '0').toLong()) }.joinToString(" ")
    // Номер пункта, статьи, параграфа, рисунка, стандарта через точку — «п. 4.2» → «четыре точка два», «ст. 20.1»,
    // «§ 4.2», «рис. 1.5А», «ГОСТ 2.105-95»: не дробь и не дата (было «двадцатого января», «четыре целых две
    // десятых»). Строка, которая начинается с «1.2.» и заглавной, — тоже номер пункта. До dates().
    // слова-метки во всех падежах обоих чисел: «частью 2.1», «в пунктах 3.1 и 3.2», «разделы 1.2–1.4»
    private val sectionLabel = """(?:п|пп|подп|ст|ч|рис|табл|гл|разд|прил|§|№|пункт(?:а|е|у|ы|ом|ов|ам|ами|ах)?|""" +
        """подпункт(?:а|е|у|ы|ом|ов|ам|ами|ах)?|стать(?:я|и|ю|е|ёй|ей|ям|ями|ях)|статей|част(?:ь|и|ью|ей|ям|ями|ях)|""" +
        """раздел(?:а|е|у|ом|ы|ов|ам|ами|ах)?|рисун(?:ок|ка|ке|ку|ком|ки|ков|кам|ками|ках)|таблиц(?:а|ы|е|у|ей|ой|ам|ами|ах)|""" +
        """глав(?:а|ы|е|у|ой|ам|ами|ах)|параграф(?:а|е|у|ом|ы|ов|ам|ами|ах)?|ГОСТ(?:а|у|е|ом)?(?:\s{1,2}Р)?|СНиП|СП|ОСТ|ТУ)"""
    private val sectionLabelRe = Regex(
        // разряд следом — количество, не номер: «в обеих частях 1.5 тыс. страниц», «1.5 тысячи страниц» — «полторы тысячи»
        """(?<=(?<![\p{L}\d])$sectionLabel\.?\s{0,2})(\d+)((?:\.\d+)+)(?!\.?\d|[.,]\d|\s*$SCALE_AHEAD)([А-ЯЁA-Z](?![\p{L}\d]))?""", RegexOption.IGNORE_CASE)
    private val sectionLineRe = Regex("""(?<=^|\n|[.!?…]\s)(\d{1,2})\.(\d{1,2})\.(?=\s+[А-ЯЁ])""")
    // Точка номера — меткой SEC_DOT, словом её делает sectionNumbers() (после «п.» → «пункт», «№» → «номер»: им нужна
    // цифра следом). Буква на конце («1.5А») — сразу названием, иначе units() прочтёт амперы.
    // «100 т. чел.» — тысяч человек (не тонн); «20 н.с.» — по новому стилю; «было —2° Ц» — тире у числа перед градусом —
    // минус; «10,000 чел.», «15.000 кв. миль», «1.000—2.000» — разряды старых книг (ровно «000» после знака)
    private val thousandManRe = Regex("""(?<=\d)\s*т\.\s*(?=чел)""")
    private val newStyleRe = Regex("""(?<![\d.,])(\d{1,2})\s?([нс])\.с\.""")
    private val dashMinusRe = Regex("""(?<=\s)[—–](?=\d+(?:[.,]\d+)?\s*°)""")
    private val oldThousandsRe = Regex("""(?<![\d.,])([1-9]\d{0,2})[.,](000)(?![\d.,]\d|\d)""")
    // «192.168.1.10:8080» — порт через «двоеточие» (без слова знак терялся)
    private val ipPortRe = Regex("""(?<![\d.])(\d{1,3}(?:\.\d{1,3}){3}):(\d{2,5})(?![\d])""")
    private fun oldNotations(text: String): String {
        var s = thousandManRe.replace(ipPortRe.replace(text, "$1 двоеточие $2"), " тыс. ")
        s = newStyleRe.replace(s) { "${it.groupValues[1]}-го " + if (it.groupValues[2] == "н") "нового стиля" else "старого стиля" }
        s = dashMinusRe.replace(s, "-")
        return oldThousandsRe.replace(s) { m -> m.groupValues[1] + "000" }
    }
    // Второй номер за меченым через тире или «и/или» — тот же номер раздела: «табл. 5.22 – 5.25», «главы 1.2–1.4»
    // (было «пять целых двадцать пять сотых»)
    private val sectionNextRe = Regex("""(\uE001\d+)(\s?[-–—]\s?|\s(?:и|или)\s)(\d+(?:\.\d+)+)(?!\.?\d|[.,]\d)""")
    private fun sectionLabels(text: String): String {
        var s = sectionLabelRe.replace(text) { m ->
            (m.groupValues[1] + m.groupValues[2]).replace('.', SEC_DOT) +
                m.groupValues[3].let { if (it.isEmpty()) "" else " " + Abbrev.codePart(it) }
        }
        // цепочка «1.2 – 1.4 и 1.6»: каждый проход метит следующий номер
        while (true) s = sectionNextRe.replace(s) { m -> m.groupValues[1] + m.groupValues[2] + m.groupValues[3].replace('.', SEC_DOT) }
            .takeIf { it != s } ?: break
        return sectionLineRe.replace(s) { m -> "${m.groupValues[1]}$SEC_DOT${m.groupValues[2]}." }
    }
    private const val SEC_DOT = '\uE001'
    private val secDotRe = Regex("""\d+(?:\uE001\d+)+""")
    private fun sectionNumbers(text: String): String {
        // сразу словами: число перед словом «точка» иначе согласовалось бы с ним («одна точка пять»)
        val s = hyphenDecimalRe.replace(secDotRe.replace(text) { m -> m.value.split(SEC_DOT).joinToString(" точка ") { sectionPart(it) } }) { m ->
            "${m.groupValues[1]} точка ${m.groupValues[2]}" }
        return sectionRe.replace(s) { m ->
            (if (m.range.first > 0 && s[m.range.first - 1].isLetter()) " " else "") + m.value.split('.').joinToString(" точка ") { sectionPart(it) }
        }
    }

    // 10. Дроби через слэш (task 18 п.5): если знаменатель 2..20 и числитель меньше него —
    // говорим словами («шесть десятых»): числитель — количественное женского рода, знаменатель —
    // порядковое женского рода (a==1 — ед. ч. им. п. «одна вторая», a>1 — мн. ч. род. п.
    // «две третьих»). Числитель больше знаменателя — тоже словами, если знаменатель 2 или круглый
    // («7/2» — «семь вторых», «100/100» — «сто сотых»); «24/7» и «25/3» — как раньше, «a дробь b».
    // «7/18 июля» — дата по двум стилям, её читает dayMonth()
    // «4820 / 11000» с пробелами вокруг слэша — так же, как слитно; не номер с годом («№58 / 2010 г.»)
    private val fractionSlashRe by lazy { Regex("""(?<![\d/])(\d+)(?:/| / )(\d+)(?![\d/])(?!\s+(?:$monthGenAlt)(?![а-яё]))""", RegexOption.IGNORE_CASE) }
    // Триггер падежа перед дробью («без 100/100», «равна 5/7») — оба в этом падеже: «пяти седьмым».
    // by lazy: genTriggerAlt объявлен ниже по файлу.
    private val fracCaseBehind by lazy {
        listOf(genTriggerAlt to Case.GEN, "(?:$datTriggerAlt|по)" to Case.DAT).map { (alt, case) ->
            Regex("""(?<![\p{L}-])$alt(?![\p{L}])(?:\s+$amplifierRe(?![\p{L}]))*\s+$""", RegexOption.IGNORE_CASE) to case
        }
    }
    private val measureAheadRe = Regex("""^\s+[а-яё]{3,}[ая](?![а-яё])""")
    // слэш в пробелах после «№» или перед годом («№58 / 2010 г.», уже «2010-й год») — номер выпуска, не дробь;
    // десятичные по краям («7,5 / 2,5») — не дробь целых; деление в выражении («10 / 2 = 5», «* 24 / 730») — как было
    // цепочка «10 / 20 / 30», «12 / 05 / 2020» и «6204 / 2RS» (буква сразу за знаменателем) — не дробь
    private val spacedSlashNotFracBehind = Regex("""(?:№\s*|\d[.,]|\d\s*/\s*|(?:[*×÷]|умножить на|разделить на|плюс|минус)\s*)$""")
    private val spacedSlashNotFracAhead = Regex("""^(?:\s*/\s*\d|[\p{L}\d]|\s*г(?:\.|од)|-[а-яё]|[.,]\d|\s*(?:[=*×÷]|равно|умножить|разделить|плюс|минус)(?![а-яё]))""")
    private fun fractionsSlash(text: String) = fractionSlashRe.replace(text) { m ->
        val (a, b) = m.destructured
        if (' ' in m.value && (spacedSlashNotFracBehind.containsMatchIn(text.substring(0, m.range.first)) ||
                spacedSlashNotFracAhead.containsMatchIn(text.substring(m.range.last + 1)))) return@replace m.value
        val an = a.toIntOrNull(); val bn = b.toIntOrNull()
        // «5/3 стакана», «7/3 часа» — неправильная дробь перед мерой в род. п. тоже словами («пять третьих»)
        val measure = bn in 3..4 && an != null && an in 1..30 && measureAheadRe.containsMatchIn(text.substring(m.range.last + 1))
        if (an != null && bn != null && an >= 1 && (bn in 2..20 && an < bn || measure || bn == 2 || bn == 10 || bn == 100 || bn == 1000 || bn == 10000)) {
            val before = text.substring(0, m.range.first)
            fracCaseBehind.firstOrNull { it.first.containsMatchIn(before) }?.let { (_, case) ->
                return@replace Declension.cardinal(an.toLong(), case, feminine = true) + " " +
                    // «одной тысяче сотых»: круглая тысяча — существительное, знаменатель при ней остаётся в Р.п.
                    ordinal(bn.toLong(), if (an % 10 == 1 && an % 100 != 11) "ой" else if (case == Case.DAT && an % 1000 != 0) "ым" else "х")
            }
            val denomSuffix = if (an == 1) "я" else "х"
            cardinal(an.toLong(), feminine = true) + " " + ordinal(bn.toLong(), denomSuffix)
        } else {
            // длиннее Long — как есть, без падения на toLong
            cardinal(a.toLongOrNull() ?: return@replace m.value) + " дробь " + cardinal(b.toLongOrNull() ?: return@replace m.value)
        }
    }

    // Смешанные числа: «1 1/2 фунта» — «полтора фунта», «2 1/2 коп.» — «две с половиной копейки», «1½°» — «полтора
    // градуса», «1 1/3 гривны» — «одна целая одна третья гривны» (было «одна одна вторая», «два½»). Одиночные знаки:
    // «½ стакана» — «половина», «¼» — «четверть», «¾» — «три четверти». До units() и currency(): сокращение следом
    // раскрываем здесь же в род. п. ед. ч. (при половинах — по целой части).
    private val vulgar = mapOf('½' to (1 to 2), '¼' to (1 to 4), '¾' to (3 to 4), '⅓' to (1 to 3), '⅔' to (2 to 3),
        '⅛' to (1 to 8), '⅜' to (3 to 8), '⅝' to (5 to 8), '⅞' to (7 to 8), '⅕' to (1 to 5))
    private val vulgarRe = Regex("""(?:(\d+)\s?)?([½¼¾⅓⅔⅛⅜⅝⅞⅕])""")
    private val mixedRe = Regex("""(?<![\d/.,])(\d{1,4})\s+(\d)/(\d{1,2})(?![\d/])(\s*(?:°|[\p{L}$₽€£]+\.?))?""")
    // формы (ед., 2–4, 5+) и род для сокращений после смешанного числа
    private val mixedNouns: Map<String, Pair<Triple<String, String, String>, Boolean>> = mapOf(
        "руб." to (Triple("рубль", "рубля", "рублей") to false), "р." to (Triple("рубль", "рубля", "рублей") to false),
        "коп." to (Triple("копейка", "копейки", "копеек") to true), "к." to (Triple("копейка", "копейки", "копеек") to true),
        "долл." to (Triple("доллар", "доллара", "долларов") to false), "$" to (Triple("доллар", "доллара", "долларов") to false),
        "₽" to (Triple("рубль", "рубля", "рублей") to false), "шт." to (Triple("штука", "штуки", "штук") to true),
        "°" to (Triple("градус", "градуса", "градусов") to false), "ч." to (Triple("час", "часа", "часов") to false),
        "ч" to (Triple("час", "часа", "часов") to false), "фунт." to (Triple("фунт", "фунта", "фунтов") to false))
    private fun mixedWords(n: Long, a: Int, b: Int, tail: String): String {
        val key = tail.trim()
        val unit = mixedNouns[key] ?: unitOf(key.removeSuffix("."))?.let { it.forms to it.feminine }
        val word = Regex("""\p{L}+""").find(key)?.value?.lowercase()
        val fem = unit?.second ?: word?.let { w -> morph?.tags(w)?.takeIf { Morph.isNoun(it) }?.let { Morph.gender(it) == Gender.F }
            ?: (w.endsWith("ы") || w.endsWith("и")) } ?: false
        val noun = unit?.first
        // «ценою 2½ столетий» — существительное в род. мн., значит и число в родительном: «двух с половиной»
        val gen = noun == null && word != null && morph?.tags(word)?.let { t -> Morph.isNoun(t) &&
            Morph.nounCases(t, plural = true) == setOf(Case.GEN) && Morph.nounCases(t, plural = false).isEmpty() } == true
        if (a * 2 == b) {
            val num = if (n == 1L) (if (gen) "полутора" else if (fem) "полторы" else "полтора")
                else Declension.cardinal(n, if (gen) Case.GEN else Case.NOM, fem) + " с половиной"
            return num + (noun?.let { " " + if (n == 1L) it.second else plural(n, it) } ?: tail)
        }
        val whole = cardinal(n, feminine = true) + (if (n % 10 == 1L && n % 100 != 11L) " целая " else " целых ")
        val frac = cardinal(a.toLong(), feminine = true) + " " + ordinal(b.toLong(), if (a == 1) "я" else "х")
        return whole + frac + (noun?.let { " " + it.second } ?: tail)
    }
    private fun mixedFractions(text: String): String {
        var s = vulgarRe.replace(text) { m ->
            val (a, b) = vulgar.getValue(m.groupValues[2][0])
            if (m.groupValues[1].isNotEmpty()) "${m.groupValues[1]} $a/$b" else when (m.groupValues[2][0]) {
                '½' -> "половина"; '¼' -> "четверть"; '¾' -> "три четверти"; '⅓' -> "треть"; '⅔' -> "две трети"; else -> "$a/$b" }
        }
        s = mixedRe.replace(s) { m ->
            val n = m.groupValues[1].toLongOrNull() ?: return@replace m.value
            val a = m.groupValues[2].toInt(); val b = m.groupValues[3].toInt()
            if (a == 0 || a >= b || b !in 2..16) return@replace m.value
            val tail = m.groupValues[4]
            mixedWords(n, a, b, tail) + if (tail.endsWith(".")) dotAfter(s, m) else ""
        }
        return s
    }

    // 10b. Валюты (task 18 п.6): число остаётся цифрами для numberRe ниже, сразу дописываем
    // слово. «€»/евро не склоняется, доллар/рубль/копейка — по plural(), а не всегда одна форма.
    private val dollarForms = Triple("доллар", "доллара", "долларов")
    private val rubleForms = Triple("рубль", "рубля", "рублей")
    private val kopeckForms = Triple("копейка", "копейки", "копеек")
    private val moneyNumRe = """\d+(?:[.,]\d+)?"""
    /** Сокращение с точкой закрыло предложение («100 руб. Дорого», «и т. д. Потом») — точку вернуть,
     * иначе предложения склеятся. Конец текста не считается: нормализатор получает и куски без точки. */
    private val sentenceEndRe = Regex("""^\s*(?:[»"]\s*)?[А-ЯЁA-Z]""")
    // слово капсом следом («КНИГИ И Т. Д. ЛЕЖАЛИ») — текст набран заглавными, это не начало предложения
    private val capsWordAheadRe = Regex("""^\s*[А-ЯЁ]{2,}[\s,.!?;:]""")
    private fun dotAfter(s: String, m: MatchResult) =
        if (m.value.endsWith('.') && sentenceEndRe.containsMatchIn(s.substring(m.range.last + 1)) &&
            !(m.value.any { it.isUpperCase() } && capsWordAheadRe.containsMatchIn(s.substring(m.range.last + 1)))) "." else ""
    // Знак перед числом — только если перед знаком не число: в «1 $ 99 центов» «$» относится к «1»,
    // иначе «$ 99» забирал префикс и выходило «один девяносто девять долларов центов».
    private val dollarPrefixRe = Regex("""(?<!\d)(?<!\d )\$\s?($moneyNumRe)""")
    private val dollarSuffixRe = Regex("""($moneyNumRe)\s?(?:\$|долл\.)""", RegexOption.IGNORE_CASE)
    private val euroPrefixRe = Regex("""(?<!\d)(?<!\d )€($moneyNumRe)""")
    private val euroSuffixRe = Regex("""($moneyNumRe)\s?€""")
    private val rubleSuffixRe = Regex("""($moneyNumRe)\s?(?:₽|руб\.|р\.)""", RegexOption.IGNORE_CASE)
    private val kopeckSuffixRe = Regex("""($moneyNumRe)\s?коп\.""", RegexOption.IGNORE_CASE)

    private fun currencyWord(numStr: String, forms: Triple<String, String, String>): String {
        val hasFrac = numStr.contains(',') || numStr.contains('.')
        if (hasFrac) return forms.second
        val n = numStr.toLongOrNull() ?: return forms.third
        return plural(n, forms)
    }

    // Деньги с копейками/центами — две цифры после запятой или точки: «3,50 руб.» — «три рубля пятьдесят копеек»,
    // «с 89,40 ₽» — «с восьмидесяти девяти рублей сорока копеек», «на 1250,50 ₽» — «на одну тысячу…», «1 р. 92 к.»
    // (было «три целых пятьдесят сотых рубля»). Падеж — по предлогу; ноль копеек не читается («100,00 ₽» — «сто рублей»).
    // Одна цифра («2,5 €») — по-прежнему дробь.
    private class Money(val major: Array<Array<String>>, val majorFem: Boolean, val minor: Array<Array<String>>?)
    // [падеж][ед., 2–4, 5+]
    private fun hardNoun(stem: String, genPl: String = stem + "ов") = arrayOf(arrayOf(stem, stem + "а", genPl), arrayOf(stem + "а", genPl, genPl),
        arrayOf(stem + "у", stem + "ам", stem + "ам"), arrayOf(stem, stem + "а", genPl), arrayOf(stem + "ом", stem + "ами", stem + "ами"),
        arrayOf(stem + "е", stem + "ах", stem + "ах"))
    private val kopeckCases = arrayOf(arrayOf("копейка", "копейки", "копеек"), arrayOf("копейки", "копеек", "копеек"),
        arrayOf("копейке", "копейкам", "копейкам"), arrayOf("копейку", "копейки", "копеек"), arrayOf("копейкой", "копейками", "копейками"),
        arrayOf("копейке", "копейках", "копейках"))
    private val rubleMoney = Money(arrayOf(arrayOf("рубль", "рубля", "рублей"), arrayOf("рубля", "рублей", "рублей"),
        arrayOf("рублю", "рублям", "рублям"), arrayOf("рубль", "рубля", "рублей"), arrayOf("рублём", "рублями", "рублями"),
        arrayOf("рубле", "рублях", "рублях")), false, kopeckCases)
    private val centCases = hardNoun("цент")
    private val dollarMoney = Money(hardNoun("доллар"), false, centCases)
    private val euroMoney = Money(Array(6) { arrayOf("евро", "евро", "евро") }, false, centCases)
    private val poundMoney = Money(hardNoun("фунт"), false, hardNoun("пенс"))
    private val hryvniaMoney = Money(arrayOf(arrayOf("гривна", "гривны", "гривен"), arrayOf("гривны", "гривен", "гривен"),
        arrayOf("гривне", "гривнам", "гривнам"), arrayOf("гривну", "гривны", "гривен"), arrayOf("гривной", "гривнами", "гривнами"),
        arrayOf("гривне", "гривнах", "гривнах")), true, kopeckCases)
    private fun moneyOf(sign: String): Money? = sign.lowercase().trimEnd('.').let { k -> when {
        k == "₽" || k == "руб" || k == "р" || k.startsWith("рубл") -> rubleMoney
        k == "$" || k == "долл" || k == "usd" || k.startsWith("доллар") -> dollarMoney
        k == "€" || k == "eur" || k == "евро" -> euroMoney
        k == "£" -> poundMoney; k == "₴" || k == "грн" || k.startsWith("гривн") || k == "гривен" -> hryvniaMoney; else -> null
    } }
    private val moneyPrepCase = listOf("около", "до", "от", "из", "без", "для", "у", "после", "более", "менее", "свыше", "порядка",
        "кроме", "вместо", "с", "со").associateWith { Case.GEN } + listOf("к", "ко", "по").associateWith { Case.DAT } +
        listOf("о", "об", "при").associateWith { Case.PRE } + listOf("на", "за", "через", "про").associateWith { Case.ACC }
    private val moneyPrepAlt = moneyPrepCase.keys.joinToString("|")
    private fun moneyNoun(forms: Array<Array<String>>, n: Long, case: Case): String {
        val one = n % 10 == 1L && n % 100 != 11L
        return if (case == Case.NOM || case == Case.ACC) forms[case.ordinal][if (one) 0 else if (n % 10 in 2..4 && n % 100 !in 12..14) 1 else 2]
            else forms[case.ordinal][if (one) 0 else 2]
    }
    private fun moneyWords(minus: String, major: Long, minorStr: String?, m: Money, case: Case, minorCase: Case = case): String {
        val sb = StringBuilder()
        if (minus.isNotEmpty()) sb.append("минус ")
        sb.append(Declension.cardinal(major, case, m.majorFem)).append(' ').append(moneyNoun(m.major, major, case))
        val minor = minorStr?.let { (if (it.length == 1) it + "0" else it).toLong() } ?: 0L
        if (minor > 0 && m.minor != null)
            sb.append(' ').append(Declension.cardinal(minor, minorCase, feminine = m.minor === kopeckCases)).append(' ').append(moneyNoun(m.minor, minor, minorCase))
        return sb.toString()
    }
    private val moneyCentsRe = Regex(
        """(?:(?<![\p{L}])($moneyPrepAlt)\s+(?:(?:примерно|почти|около)\s+)?)?(?<![\d.,])(-?)(\d+)[.,](\d{2})(?![\d.,]\d)\s?(₽|руб\.?|р\.|\$|долл\.|usd|€|eur|£|₴|грн\.?|рубл[ьяей]{1,2}|доллар(?:а|ов)?|евро|гривн[аыи]?|гривен)(?![\p{L}])""",
        RegexOption.IGNORE_CASE)
    private val moneyCentsPrefixRe = Regex("""(?:(?<![\p{L}])($moneyPrepAlt)\s+)?(?<![\d.,])([$€£])(\d+)[.,](\d{2})(?![\d.,]\d)""", RegexOption.IGNORE_CASE)
    private val rubKopRe = Regex(
        """(?:(?<![\p{L}])($moneyPrepAlt)\s+)?(?<![\d.,])(\d+)\s?(?:р\.|руб\.)\s*(\d{1,2})\s?(?:к\.|коп\.)""", RegexOption.IGNORE_CASE)
    // «99,90 ₽/кг», «500 ₽/ч», «70 руб./долл.» — «за килограмм», «за час», «за доллар»
    private val perUnitRe = Regex("""(?<=рубл[ьяейюём]{1,3}|рублями|рублях|копе\p{L}{2,4}|доллар\p{L}{0,3}|евро|цент\p{L}{0,3}|гривн\p{L}{0,3}|фунт\p{L}{0,3})\s*/\s*(кг|л|ч|час|шт\.?|мес\.?|мин|сут\.?|м²|м2|м|т|чел\.?|долл\.?|\$|€|евро)(?![\p{L}\d])""", RegexOption.IGNORE_CASE)
    private val perUnitWords = mapOf("кг" to "килограмм", "л" to "литр", "ч" to "час", "час" to "час", "шт" to "штуку", "мес" to "месяц",
        "мин" to "минуту", "сут" to "сутки", "м²" to "квадратный метр", "м2" to "квадратный метр", "м" to "метр", "т" to "тонну",
        "чел" to "человека", "долл" to "доллар", "$" to "доллар", "€" to "евро", "евро" to "евро")
    private fun moneyCents(text: String): String {
        var s = moneyCentsRe.replace(text) { m ->
            val (prep, minus, major, minor, sign) = m.destructured
            val money = moneyOf(sign) ?: return@replace m.value
            val case = moneyPrepCase[prep.lowercase()] ?: Case.NOM
            (if (prep.isEmpty()) "" else "$prep ") + moneyWords(minus, major.toLongOrNull() ?: return@replace m.value, minor, money, case) + (if (sign.endsWith(".")) dotAfter(text, m) else "")
        }
        s = moneyCentsPrefixRe.replace(s) { m ->
            val (prep, sign, major, minor) = m.destructured
            val case = moneyPrepCase[prep.lowercase()] ?: Case.NOM
            (if (prep.isEmpty()) "" else "$prep ") + moneyWords("", major.toLongOrNull() ?: return@replace m.value, minor, moneyOf(sign)!!, case)
        }
        s = rubKopRe.replace(s) { m ->
            val (prep, major, minor) = m.destructured
            val case = moneyPrepCase[prep.lowercase()] ?: Case.NOM
            // «по 1 р. 92 к.» — «по одному рублю девяносто две копейки»: копейки при «по» в именительном
            (if (prep.isEmpty()) "" else "$prep ") + moneyWords("", major.toLongOrNull() ?: return@replace m.value, minor.padStart(2, '0'), rubleMoney, case,
                if (case == Case.DAT) Case.NOM else case) + dotAfter(s, m)
        }
        return s
    }

    private fun currency(text: String): String {
        var s = text
        s = dollarPrefixRe.replace(s) { m -> "${m.groupValues[1]} ${currencyWord(m.groupValues[1], dollarForms)}" }
        s = dollarSuffixRe.replace(s) { m -> "${m.groupValues[1]} ${currencyWord(m.groupValues[1], dollarForms)}" + dotAfter(s, m) }
        s = euroPrefixRe.replace(s) { m -> "${m.groupValues[1]} евро" }
        s = euroSuffixRe.replace(s) { m -> "${m.groupValues[1]} евро" }
        s = rubleSuffixRe.replace(s) { m -> "${m.groupValues[1]} ${currencyWord(m.groupValues[1], rubleForms)}" + dotAfter(s, m) }
        s = kopeckSuffixRe.replace(s) { m -> "${m.groupValues[1]} ${currencyWord(m.groupValues[1], kopeckForms)}" + dotAfter(s, m) }
        s = perUnitRe.replace(s) { m -> " за " + perUnitWords.getValue(m.groupValues[1].lowercase().trimEnd('.')) + dotAfter(s, m) }
        return s
    }

    // 11. Сокращения. Падеж по предлогу («на стр.» → «на странице», «до стр. 5» → «до страницы») — раньше
    // общего списка. «тыс./млн/млрд» после числа — со склонением через plural(), «тыс.» ещё и с родом (жен.).
    // Формы [NOM, GEN, DAT, ACC, INS, PRE].
    private val abbrevNounForms = mapOf(
        "стр" to arrayOf("страница", "страницы", "странице", "страницу", "страницей", "странице"),
        "гл" to arrayOf("глава", "главы", "главе", "главу", "главой", "главе"),
        "табл" to arrayOf("таблица", "таблицы", "таблице", "таблицу", "таблицей", "таблице"),
        "рис" to arrayOf("рисунок", "рисунка", "рисунку", "рисунок", "рисунком", "рисунке"),
        "ст" to arrayOf("статья", "статьи", "статье", "статью", "статьёй", "статье"),
        "п" to arrayOf("пункт", "пункта", "пункту", "пункт", "пунктом", "пункте"),
    )
    // «с» — родительный («со стр. 5»), не творительный; «по» — дательный только у «ст./п.» («по ст. 105»):
    // «со стр. 5 по стр. 10» — диапазон, там винительный, оставляем общему списку.
    private val abbrevPrepCase = listOf("до", "от", "из", "для", "без", "после", "около", "у", "вместо", "кроме", "с", "со",
        "возле").associateWith { Case.GEN } +
        listOf("к", "ко", "согласно", "по").associateWith { Case.DAT } +
        listOf("через", "про").associateWith { Case.ACC } +
        listOf("под", "над", "перед").associateWith { Case.INS } +
        listOf("на", "в", "во", "о", "об", "при").associateWith { Case.PRE }
    // Без номера следом — только «на/в/о/при» и «стр./гл./табл./рис.», как раньше («на стр.» → «на странице»):
    // «из рис.», «у ст.» без числа — скорее крупа и станица.
    private val abbrevPrepRe = Regex(
        """(?<![\p{L}\d])(${abbrevPrepCase.keys.joinToString("|")})\s+(стр|гл|табл|рис|ст|п)\.(\s*(?=(?:№\s*)?\d))?""",
        RegexOption.IGNORE_CASE
    )
    private val abbrevPrepNoNumber = setOf("на", "в", "во", "о", "об", "при")
    private fun abbrevPrep(text: String) = abbrevPrepRe.replace(text) { m ->
        val (prep, abbr) = m.destructured
        val p = prep.lowercase(); val a = abbr.lowercase()
        val numbered = m.groups[3] != null
        if (!numbered && (p !in abbrevPrepNoNumber || a == "ст" || a == "п")) return@replace m.value
        if (p == "по" && a != "ст" && a != "п") return@replace m.value
        // «ссылки на п. 2», «на ст. 105» — винительный (на страницу — «на стр.» — по-прежнему предложный)
        val word = abbrevNounForms.getValue(a)[(if (p == "на" && (a == "п" || a == "ст")) Case.ACC else abbrevPrepCase.getValue(p)).ordinal]
        // «с стр. 5» → «со страницы», «с ст. 5» → «со статьи»
        val pr = if (p == "с" && word[0] == 'с') prep + "о" else prep
        "$pr $word" + if (numbered) " " else ""
    }

    private val scaleAbbrevRe = Regex("""(\d+(?:[.,]\d+)?)\s*(тыс|млн|млрд|трлн)\.?(?![\p{L}])""", RegexOption.IGNORE_CASE)
    private val scaleAbbrevForms = mapOf(
        "тыс" to Triple("тысяча", "тысячи", "тысяч"),
        "млн" to Triple("миллион", "миллиона", "миллионов"),
        "млрд" to Triple("миллиард", "миллиарда", "миллиардов"),
        "трлн" to Triple("триллион", "триллиона", "триллионов"),
    )
    private fun scaleAbbrev(text: String) = scaleAbbrevRe.replace(text) { m ->
        val (numStr, abbrRaw) = m.destructured
        val abbr = abbrRaw.lowercase()
        val forms = scaleAbbrevForms.getValue(abbr)
        // «2,5 млн» — дробь согласуется как 2-4: «миллиона».
        if (numStr.contains(',') || numStr.contains('.')) return@replace numStr + " " + forms.second + dotAfter(text, m)
        val n = numStr.toLongOrNull() ?: return@replace m.value
        (if (abbr == "тыс") cardinal(n, feminine = true) + " " + plural(n, forms)
        else numStr + " " + plural(n, forms)) + dotAfter(text, m)
    }
    // Фолбэки без числа рядом («5 тыс. руб.» — число уже словами) — после scaleAbbrev.
    private val scaleFallback = listOf(
        Regex("""(?<![\p{L}\d])руб\.(?![\p{L}])""", RegexOption.IGNORE_CASE) to "рублей",
        Regex("""(?<![\p{L}\d])долл\.(?![\p{L}])""", RegexOption.IGNORE_CASE) to "долларов",
        Regex("""(?<![\p{L}\d])тыс\.(?![\p{L}])""", RegexOption.IGNORE_CASE) to "тысяч",
        Regex("""(?<![\p{L}\d])млн\.?(?![\p{L}])""", RegexOption.IGNORE_CASE) to "миллионов",
        Regex("""(?<![\p{L}\d])млрд\.?(?![\p{L}])""", RegexOption.IGNORE_CASE) to "миллиардов",
        Regex("""(?<![\p{L}\d])трлн\.?(?![\p{L}])""", RegexOption.IGNORE_CASE) to "триллионов",
    )
    // Дробь словами перед сокращением (её склонил cases(): «достигла двух целых трёх десятых млрд») — род. ед.
    // и в косвенном падеже: «к одной целой трём десятым млн» — «миллиона» (fracCase)
    private val scaleAfterFracRe = Regex("""(?<=(?<![\p{L}])(?:десят|сот|тысячн|десятитысячн|стотысячн|миллионн)(?:ых|ой|ая|ым|ыми)\s)(тыс|млн|млрд|трлн)\.?(?![\p{L}])""", RegexOption.IGNORE_CASE)
    // Единица после разряда словами: «3—4 тыс. км» → «тысячи километров», «15 тысяч т» — род. мн.
    private val unitAfterScaleRe by lazy {
        Regex("""(?<=(?<![\p{L}])(?:тысяч[аи]?|миллион(?:а|ов)?|миллиард(?:а|ов)?|триллион(?:а|ов)?)\s)(?!(?-i:[вскоуиа])(?![\p{L}\d/.]))($unitAltPattern)(?![\p{L}\d/])(?:\.(?=\s*(?-i:[а-яё,;:])))?""") }
    private val unitAfterFracRe by lazy {
        Regex("""(?<![\p{L}])((?:втор|трет|четвёрт|пят|шест|седьм|восьм|девят|десят|сот|тысячн)(?:ых|ой|ая|ьих|ьей|ья|ым|ыми)|степени)\s(?!(?-i:[вскоуиа])(?![\p{L}\d/.]))($unitAltPattern)(?![\p{L}\d/])(?:\.(?=\s*(?-i:[а-яё,;:])))?""") }
    // Порядок: после cases() — «от 60 до 80 тыс.» сначала склоняет оба числа, потом «тысяч».
    private fun scales(text: String): String {
        var s = scaleAbbrev(text)
        s = scaleAfterFracRe.replace(s) { m -> scaleAbbrevForms.getValue(m.groupValues[1].lowercase()).second + dotAfter(s, m) }
        for ((re, rp) in scaleFallback) s = expandAbbrev(s, re, rp)
        s = unitAfterScaleRe.replace(s) { m -> val u = unitOf(m.groupValues[1]) ?: return@replace m.value
            (u.prefix?.let { it.third + " " } ?: "") + u.forms.third + u.suffix + dotAfter(s, m) }
        // «5/7 м» — после дроби род. ед.: «пять седьмых метра»; «10⁻³ моль/л» — «в минус третьей степени моль на литр»
        s = unitAfterFracRe.replace(s) { m -> val u = unitOf(m.groupValues[2]) ?: return@replace m.value
            m.groupValues[1] + " " + if (m.groupValues[1] == "степени") u.forms.first + u.suffix + dotAfter(s, m)
            else (u.prefix?.let { it.first.dropLast(2) + "ого " } ?: "") + u.forms.second + u.suffix + dotAfter(s, m) }
        return s
    }

    // «2,5 млн» — «два с половиной миллиона», «1,5 тыс.» — «полторы тысячи», «около 2,5 млн» — «около двух с половиной
    // миллионов» (было «две целых пять десятых миллиона»). Падеж — по предлогу перед числом.
    private val halfScaleForms = mapOf(
        "тыс" to arrayOf("тысяч", "тысяч", "тысячам", "тысяч", "тысячами", "тысячах"),
        "мил" to arrayOf("миллионов", "миллионов", "миллионам", "миллионов", "миллионами", "миллионах"),
        "млн" to arrayOf("миллионов", "миллионов", "миллионам", "миллионов", "миллионами", "миллионах"),
        "млр" to arrayOf("миллиардов", "миллиардов", "миллиардам", "миллиардов", "миллиардами", "миллиардах"),
        "трл" to arrayOf("триллионов", "триллионов", "триллионам", "триллионов", "триллионами", "триллионах"),
        "три" to arrayOf("триллионов", "триллионов", "триллионам", "триллионов", "триллионами", "триллионах"))
    private val halfScalePrepCase = listOf("около", "до", "от", "из", "более", "менее", "свыше", "порядка", "без", "для", "у", "после",
        "кроме", "среди").associateWith { Case.GEN } + mapOf("к" to Case.DAT, "ко" to Case.DAT, "по" to Case.DAT, "о" to Case.PRE,
        "об" to Case.PRE, "при" to Case.PRE, "между" to Case.INS)
    private val halfScaleRe = Regex(
        """(?:(?<![\p{L}])(${halfScalePrepCase.keys.joinToString("|")})\s+)?(\d+)[.,]5(?!\d)\s*""" +
            """(тыс\.?|тысяч[аи]?|млн\.?|миллион(?:а|ов)?|млрд\.?|миллиард(?:а|ов)?|трлн\.?|триллион(?:а|ов)?)(?![\p{L}])""", RegexOption.IGNORE_CASE)
    private fun halfScales(text: String) = halfScaleRe.replace(text) { m ->
        val (prep, num, word) = m.destructured
        val n = num.toLongOrNull() ?: return@replace m.value
        if (n == 0L || n % 10 == 1L && n % 100 != 11L && n != 1L) return@replace m.value
        val key = word.lowercase().take(3).let { if (it == "мил" && word.lowercase().startsWith("милл")) "мил" else it }
        val fem = key == "тыс"
        val case = halfScalePrepCase[prep.lowercase()] ?: Case.NOM
        val noun = if (case == Case.NOM) {
            val f = if (fem) Triple("тысяча", "тысячи", "тысяч") else scaleAbbrevForms.getValue(when (key) { "мил", "млн" -> "млн"; "млр" -> "млрд"; else -> "трлн" })
            if (n == 1L) f.second else plural(n, f)
        } else halfScaleForms.getValue(key)[case.ordinal]
        val number = if (n == 1L) (if (case == Case.NOM) (if (fem) "полторы" else "полтора") else "полутора")
            else Declension.cardinal(n, case, fem) + " с половиной"
        (if (prep.isEmpty()) "" else "$prep ") + number + " " + noun + if (word.endsWith(".")) dotAfter(text, m) else ""
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
        // последнюю точку забывают: «и т.д не произносятся»
        Regex("""(?<![\p{L}\d])(?:[иИ]\s+[тТ]\.\s*д|И\s+Т\.\s*Д)(?:\.|(?![\p{L}.]))(?![\p{L}])""") to "и так далее",
        Regex("""(?<![\p{L}\d])(?:[иИ]\s+[тТ]\.\s*п|И\s+Т\.\s*П)(?:\.|(?![\p{L}.]))(?![\p{L}])""") to "и тому подобное",
        // «т.-е.» старых книг; «Т. п.» в начале фразы без «и» — «тому подобное»
        Regex("""(?<![\p{L}\d])[тТ]\.\s*-\s*е\.(?![\p{L}])""") to "то есть",
        Regex("""(?<![\p{L}\d])[тТ]\.\s*п\.(?![\p{L}])""") to "тому подобное",
        Regex("""(?<![\p{L}\d])[вВ]\s+[тТ]\.\s*ч\.(?![\p{L}])""") to "в том числе",
        // т. е., т. к., т. н., т. о.: вторая буква строчная, иначе инициалы («Артемьев Т. Е.», «Т.К. Иванов»)
        Regex("""(?<![\p{L}\d])[тТ]\.\s*е\.(?![\p{L}])""") to "то есть",
        Regex("""(?<![\p{L}\d])[тТ]\.\s*к\.(?![\p{L}])""") to "так как",
        Regex("""(?<![\p{L}\d])[тТ]\.\s*н\.(?![\p{L}])""") to "так называемый",
        Regex("""(?<![\p{L}\d])пп\.(?![\p{L}])""", RegexOption.IGNORE_CASE) to "подпункт",
        // Пробел — часть замены (review final-fix п.12): «п.5» без пробела иначе даёт «пункт5»,
        // сам regex съедает исходный пробел, если он был, чтобы не задвоить его.
        Regex("""(?<![\p{L}\d])п\.\s*(?=\d)""", RegexOption.IGNORE_CASE) to "пункт ",
        // 10. «от лат. homo» — после «от/с/из» форма одна: «от латинского»
        Regex("""(?<![\p{L}\d])(от|с|из)\s+лат\.(?![\p{L}])""", RegexOption.IGNORE_CASE) to "$1 латинского",
        Regex("""(?<![\p{L}\d])(от|с|из)\s+греч\.(?![\p{L}])""", RegexOption.IGNORE_CASE) to "$1 греческого",
        // «С. Петербург» — Санкт, а не инициал (Abbrev иначе прочтёт «эс Петербург»)
        Regex("""(?<![\p{L}\d])С\.\s*-?\s*(?=Петербург)""") to "Санкт-",
        // «Роза (лат. Rosa)», «(лат.)» — пометка языка, а не прилагательное: «латынь» (жалоба 08.10.2026).
        // Перед русским существительным («лат. название») раскрывает adjAbbrevRe ниже
        Regex("""(?<![\p{L}\d])лат\.(?=\s*(?:[A-Za-z]|[)\],;:—–]|$))""", RegexOption.IGNORE_CASE) to "латынь",
        Regex("""(?<![\p{L}\d])стр\.(?![\p{L}])""", RegexOption.IGNORE_CASE) to "страница",
        // «рис» — ещё и крупа: «На ужин был рис.» — сокращение только с номером следом («рис. 3», «рис. № 2»)
        Regex("""(?<![\p{L}\d])рис\.(?=\s*(?:№\s*)?\d)""", RegexOption.IGNORE_CASE) to "рисунок",
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
        // «Н. Э. Бауман» — инициалы; заглавные только после «до»: «IV в. до Н. Э.»
        Regex("""(?<![\p{L}\d])(?:[нН]\.\s*э|(?<=[дД]о\s)Н\.\s*Э)\.?(?![\p{L}])""") to "нашей эры",
        Regex("""(?<![\p{L}\d])[тТ]\.\s*о\.(?![\p{L}])""") to "таким образом",
        // «кол-во», «кол-ва», «кол-ву», «кол-вом», «кол-ве» — окончание переносим
        Regex("""(?<![\p{L}\d])кол-в(о|а|у|ом|е)(?![\p{L}])""", RegexOption.IGNORE_CASE) to "количеств$1",
        Regex("""(?<![\p{L}\d])ж/д(?![\p{L}])""", RegexOption.IGNORE_CASE) to "железнодорожный",
        Regex("""(?<![\p{L}\d])б/у(?![\p{L}])""", RegexOption.IGNORE_CASE) to "бывший в употреблении",
        // «ок.»/«кв.» только перед числом, пробел — часть замены (как у «п.» выше)
        Regex("""(?<![\p{L}\d])ок\.\s*(?=\d)""", RegexOption.IGNORE_CASE) to "около ",
        Regex("""(?<![\p{L}\d])кв\.\s*(?=\d)""", RegexOption.IGNORE_CASE) to "квартира ",
        Regex("""(?<![\p{L}\d])доб\.\s*(?=\d)""", RegexOption.IGNORE_CASE) to "добавочный ",
        Regex("""(?<![\p{L}\d])макс\.(?![\p{L}])""") to "максимум",  // без IGNORE_CASE: «Его звали Макс.»
        // «вкл.» не трогаем: в тексте это чаще «включительно» («с 1 по 5 вкл.»)
        Regex("""(?<![\p{L}\d])выкл\.(?![\p{L}])""", RegexOption.IGNORE_CASE) to "выключено",
    )

    // «Св. Георгия» → «Святого Георгия», «Св. Анны» → «Святой Анны»: род и падеж по имени (nameGenderCase).
    private val saintRe = Regex("""(?<![\p{L}\d])(св\.)\s+([А-ЯЁ][а-яё]+)""", RegexOption.IGNORE_CASE)
    private val saintForms = mapOf(
        'm' to arrayOf("Святой", "Святого", "Святому", "Святого", "Святым", "Святом"),
        'f' to arrayOf("Святая", "Святой", "Святой", "Святую", "Святой", "Святой"))
    // 10b. Сокращения-прилагательные — по таблице морфологии: раскрываем, только если существительное
    // следом (через одно-два прилагательных: «гос. часовой службы») известно и все его разборы дают одну
    // форму прилагательного. «службы» — Р.п. ед. или И./В.п. мн. — неоднозначно; тогда решает слово перед
    // сокращением: существительное («порядок гос. службы») или, через «и/или», существительное только в
    // родительном («контроля и полит. системы») — берём родительный, иначе оставляем сокращение.
    private val adjAbbrevStems = mapOf("гос" to "государственн", "офиц" to "официальн", "полит" to "политическ",
        "междунар" to "международн", "лат" to "латинск", "греч" to "греческ", "физ" to "физическ", "хим" to "химическ",
        "мат" to "математическ", "ист" to "историческ", "экон" to "экономическ", "соц" to "социальн", "фед" to "федеральн",
        "муниц" to "муниципальн", "тех" to "техническ", "мед" to "медицинск", "юр" to "юридическ", "воен" to "военн",
        "англ" to "английск", "нем" to "немецк", "фр" to "французск", "рус" to "русск", "рос" to "российск",
        "науч" to "научн", "лит" to "литературн", "ж/д" to "железнодорожн", "гл" to "главн", "св" to "свят",
        "ж.-д" to "железнодорожн", "с.-х" to "сельскохозяйственн")
    // Слова следом необязательны: «ж/д» без существительного после предлога — «железная дорога» (railNoun).
    private val adjAbbrevRe = Regex(
        """(?<![\p{L}\d])((?:${adjAbbrevStems.keys.filter { it != "ж/д" }.joinToString("|") { Regex.escape(it) }})\.|ж/д)""" +
            """(?:(?=\s+(\p{L}+)(?:\s+(\p{L}+))?(?:\s+(\p{L}+))?))?(?![\p{L}\d/])""",
        RegexOption.IGNORE_CASE
    )
    private val fewBehindRe = Regex("""(?<![\p{L}\d])(?:два|две|три|четыре|оба|обе|[234])\s+(?:\p{L}+\s+)?$""", RegexOption.IGNORE_CASE)
    private val shortParticipleRe = Regex("""\p{L}+(?:ён|ен|ан|ян|ит|ят|ено|ано|ена|ана|ены|аны)""", RegexOption.IGNORE_CASE)
    private val prevWordRe = Regex("""(\p{L}+)(\s+(?:и|или))?\s*$""", RegexOption.IGNORE_CASE)
    // «по ж/д», «до ж/д», «на ж/д» — существительное «железная дорога» в падеже предлога.
    private val railNounForms = arrayOf("железная дорога", "железной дороги", "железной дороге", "железную дорогу",
        "железной дорогой", "железной дороге")
    private val railPrepCase = listOf("до", "от", "у", "из", "с", "со", "около", "вдоль", "возле", "для", "без", "после",
        "кроме", "вместо").associateWith { Case.GEN } + listOf("по", "к", "ко").associateWith { Case.DAT } +
        listOf("через", "за", "про").associateWith { Case.ACC } + listOf("перед", "над", "под", "между").associateWith { Case.INS } +
        listOf("на", "в", "во", "о", "об", "при").associateWith { Case.PRE }
    private val prevOnlyWordRe = Regex("""(?<![\p{L}\d])(\p{L}+)\s+$""")
    private fun railNoun(before: String): String? {
        val prep = prevOnlyWordRe.find(before)?.groupValues?.get(1)?.lowercase() ?: return null
        return railNounForms[(railPrepCase[prep] ?: return null).ordinal]
    }
    private fun adjAbbrev(s: String, morph: Morph) = adjAbbrevRe.replace(s) { m ->
        val key = m.groupValues[1].removeSuffix(".").lowercase()
        val stem = adjAbbrevStems.getValue(key)
        val adjs = mutableListOf<Int>()
        var tn = 0
        for (i in 2..4) {
            val w = m.groupValues[i]
            if (w.isEmpty()) break
            val t = morph.tags(w)
            // «часовой» — и существительное, и прилагательное: существительное, если дальше не существительное
            val nextNoun = i < 4 && m.groupValues[i + 1].isNotEmpty() && Morph.isNoun(morph.tags(m.groupValues[i + 1]))
            if (Morph.isNoun(t) && !(Morph.isAdjective(t) && nextNoun)) { tn = t; break }
            if (!Morph.isAdjective(t)) break
            adjs += t
        }
        // «гл. Введение» — глава с названием, не «главное»
        if (key == "гл" && m.groupValues[2].firstOrNull()?.isUpperCase() == true) return@replace m.value
        // «офиц. подтверждён» — перед причастием/наречием наречие: «официально»
        if (tn == 0 && key == "офиц" && m.groupValues[2].isNotEmpty() && shortParticipleRe.matches(m.groupValues[2])) return@replace "официально"
        if (tn == 0) return@replace (if (key == "ж/д") railNoun(s.substring(0, m.range.first)) else null) ?: m.value
        val anim = Morph.animate(tn)
        val forms = LinkedHashMap<String, Case>() // форма прилагательного → падеж, ед. ч. раньше мн. ч.
        // род перебираем: у формы-омонима двух лемм («стрелки» — стрелка и стрелок) он в таблице общий
        for (plural in listOf(false, true)) for (g in if (plural) listOf(null) else Morph.genders(tn)) for (c in Morph.nounCases(tn, plural)) {
            if (adjs.any { a -> Morph.adjCases(a, g, plural).let { c !in it && !(c == Case.ACC && anim && Case.GEN in it) } }) continue
            forms.getOrPut(Declension.adjective(stem, g, plural, c, anim)) { c }
        }
        val before = s.substring(0, m.range.first)
        val form = forms.keys.singleOrNull() ?: run {
            // «два гос. пути» — после 2–4 прилагательное в род. мн.; «восстановили гос. пути» — им./вин. мн.
            if (fewBehindRe.containsMatchIn(before) && Case.GEN in Morph.nounCases(tn, plural = false)) return@run Declension.adjective(stem, null, true, Case.GEN)
            if (dayVerbBehindRe.containsMatchIn(before)) forms.keys.firstOrNull { it.endsWith("ые") || it.endsWith("ие") }?.let { return@run it }
            val prev = prevWordRe.find(before) ?: return@replace m.value
            val tp = morph.tags(prev.groupValues[1])
            if (!Morph.isNoun(tp)) return@replace m.value
            if (prev.groupValues[2].isNotEmpty() && Morph.nounCases(tp, false) + Morph.nounCases(tp, true) != setOf(Case.GEN)) return@replace m.value
            forms.entries.firstOrNull { it.value == Case.GEN }?.key ?: return@replace m.value
        }
        m.withGroupReplaced(1 to if (m.groupValues[1][0].isUpperCase()) form.replaceFirstChar { it.uppercase() } else form)
    }

    // «№ 5», «§ 3» через пробел: numberRe берёт знак только вплотную к числу, а отдельный знак фильтр молча
    // выкидывал («Автобус № 5» → «автобус пять»). Слово — в падеже предлога: «под № 7» → «под номером»,
    // «ко № 12» → «к номеру», «(с № 5)» → «с номером»; «№№» — «номера».
    private val signForms = mapOf("№" to arrayOf("номер", "номера", "номеру", "номер", "номером", "номере"),
        "§" to arrayOf("параграф", "параграфа", "параграфу", "параграф", "параграфом", "параграфе"))
    private val signPrepCase = abbrevPrepCase - setOf("с", "со", "по") + mapOf("с" to Case.INS, "со" to Case.INS,
        "под" to Case.INS, "за" to Case.INS, "между" to Case.INS)
    private val numberSignRe = Regex(
        """(?:(?<![\p{L}\d])(${signPrepCase.keys.joinToString("|")})\s+)?(№№|§§|№|§)\s*(?=\d)""", RegexOption.IGNORE_CASE)
    // «в § 4.2 и § 4.3» — второй знак через «и»/запятую в падеже первого
    private val signAfterAndRe = Regex("""(?:\s+(?:и|или)|,)\s*$""")
    private fun numberSigns(text: String): String {
        var lastCase: Case? = null; var lastEnd = -100
        return numberSignRe.replace(text) { m ->
        val (prep, sign) = m.destructured
        val forms = signForms.getValue(sign.take(1))
        val chained = prep.isEmpty() && m.range.first - lastEnd < 30 && signAfterAndRe.containsMatchIn(text.substring(0, m.range.first))
        val case = signPrepCase[prep.lowercase()] ?: (if (chained) lastCase else null) ?: Case.NOM
        lastCase = case; lastEnd = m.range.last
        // «№№ 5-7» — мн. ч.: номера, номеров…
        val word = if (sign.length == 2) arrayOf("номера", "номеров", "номерам", "номера", "номерами", "номерах")[case.ordinal]
            .let { if (sign[0] == '§') it.replace("номер", "параграф") } else forms[case.ordinal]
        val p = when (prep.lowercase()) { "со" -> prep.dropLast(1); "ко" -> prep.dropLast(1); else -> prep }
        (if (prep.isEmpty()) "" else "$p ") + word + " "
        }
    }

    // Сокращения-существительные адресов и книг — словом в падеже предлога (было «д. десять», «на пл. Победы» — «пэ эл»):
    // перед номером — «д. 10» → «дом», «корп. 2», «оф. 12», «эт. 3» («на эт. 3» — «на этаже»), «т. II», «кн. III», «ч. 1»,
    // «прил. 2», «прим. 5»; перед названием с заглавной — «ул. Лесной», «пл. Победы», «пос. Заречный», «оз. Байкал»,
    // «г. Тверь» («в г. Тверь» — «в городе»), «д. Ивановка» — деревня. «на/в» — предложный, но перед словом на «-ую»
    // («на ул. Лесную») — винительный. «стр.» после номера дома — строение.
    private class NounAbbr(val forms: Array<String>, val digit: Boolean, val name: Boolean)
    private fun nf(vararg f: String) = arrayOf(*f)
    private fun hardM(w: String) = nf(w, w + "а", w + "у", w, w + "ом", w + "е")
    private val nounAbbrs: Map<String, NounAbbr> = mapOf(
        "корп" to NounAbbr(nf("корпус", "корпуса", "корпусу", "корпус", "корпусом", "корпусе"), true, false),
        "влд" to NounAbbr(nf("владение", "владения", "владению", "владение", "владением", "владении"), true, false),
        "вл" to NounAbbr(nf("владение", "владения", "владению", "владение", "владением", "владении"), true, false),
        "пом" to NounAbbr(nf("помещение", "помещения", "помещению", "помещение", "помещением", "помещении"), true, false),
        "оф" to NounAbbr(hardM("офис"), true, false),
        "эт" to NounAbbr(nf("этаж", "этажа", "этажу", "этаж", "этажом", "этаже"), true, false),
        "кн" to NounAbbr(nf("книга", "книги", "книге", "книгу", "книгой", "книге"), true, false),
        "т" to NounAbbr(hardM("том"), true, false),
        "ч" to NounAbbr(nf("часть", "части", "части", "часть", "частью", "части"), true, false),
        "разд" to NounAbbr(hardM("раздел"), true, false),
        "подп" to NounAbbr(hardM("подпункт"), true, false),
        "прил" to NounAbbr(nf("приложение", "приложения", "приложению", "приложение", "приложением", "приложении"), true, false),
        "прим" to NounAbbr(nf("примечание", "примечания", "примечанию", "примечание", "примечанием", "примечании"), true, false),
        "изд" to NounAbbr(nf("издание", "издания", "изданию", "издание", "изданием", "издании"), true, false),
        "ул" to NounAbbr(nf("улица", "улицы", "улице", "улицу", "улицей", "улице"), false, true),
        "пл" to NounAbbr(nf("площадь", "площади", "площади", "площадь", "площадью", "площади"), false, true),
        "пер" to NounAbbr(nf("переулок", "переулка", "переулку", "переулок", "переулком", "переулке"), false, true),
        "б-р" to NounAbbr(hardM("бульвар"), false, true),
        "мкр" to NounAbbr(hardM("микрорайон"), false, true),
        "наб" to NounAbbr(nf("набережная", "набережной", "набережной", "набережную", "набережной", "набережной"), false, true),
        "ш" to NounAbbr(nf("шоссе", "шоссе", "шоссе", "шоссе", "шоссе", "шоссе"), false, true),
        "просп" to NounAbbr(hardM("проспект"), false, true), "пр-т" to NounAbbr(hardM("проспект"), false, true),
        "пр-кт" to NounAbbr(hardM("проспект"), false, true),
        "дер" to NounAbbr(nf("деревня", "деревни", "деревне", "деревню", "деревней", "деревне"), false, true),
        "пос" to NounAbbr(nf("посёлок", "посёлка", "посёлку", "посёлок", "посёлком", "посёлке"), false, true),
        "кв-л" to NounAbbr(hardM("квартал"), false, true),
        "туп" to NounAbbr(nf("тупик", "тупика", "тупику", "тупик", "тупиком", "тупике"), false, true),
        "оз" to NounAbbr(nf("озеро", "озера", "озеру", "озеро", "озером", "озере"), false, true),
        "респ" to NounAbbr(nf("республика", "республики", "республике", "республику", "республикой", "республике"), false, true),
        "г" to NounAbbr(hardM("город"), false, true),
        "д" to NounAbbr(hardM("дом"), true, false),
        "с" to NounAbbr(nf("страница", "страницы", "странице", "страницу", "страницей", "странице"), false, false),
    )
    private val villageForms = nf("деревня", "деревни", "деревне", "деревню", "деревней", "деревне")
    private val buildingForms = nf("строение", "строения", "строению", "строение", "строением", "строении")
    private val nounAbbrPrepCase = abbrevPrepCase + mapOf("близ" to Case.GEN, "за" to Case.INS, "между" to Case.INS, "напротив" to Case.GEN)
    // однобуквенные — только строчные («Т.» — ещё и том в выходных данных, его отдельно), «стр.» — строение после «д. N».
    // Регистр: IGNORE_CASE на всё, регистрозависимое — в (?-i:): встроенного (?iu) ICU на Android не знает.
    private val nounAbbrRe = Regex(
        """(?:(?<![\p{L}])(${nounAbbrPrepCase.keys.joinToString("|")})\s+)?(?<![\p{L}\d-])(?<!\d\s?)""" +
            """((?:корп|влд|вл|пом|оф|эт|кн|разд|подп|прил|прим|изд|ул|пл|пер|просп|мкр|наб|дер|пос|туп|оз|респ)\.|(?:б-р|пр-т|пр-кт|кв-л)\.?|(?-i:[тчгдшс]\.|Т\.|стр\.))""" +
            """(?=\s*(?:№\s*)?\d|\s*[«"]?(?-i:[А-ЯЁ])|\s+(?-i:[IVXLC]+|[ivx]+)(?![\p{L}]))""", RegexOption.IGNORE_CASE)
    private val romanRangeAheadRe = Regex("""^\s+[IVXLC]+\s*[-–—]\s*[IVXLC]+""")
    private val romanLooseAfterRe = Regex("""^\s+([IVXLC]+|[ivx]+)(?![\p{L}\d])""")
    private val romanAfterRe = Regex("""^\s+([IVXLC]+|[ivx]+)(?![\p{L}\d])(?!\.\s+[А-ЯЁ][а-яё])""")
    private val digitAfterRe = Regex("""^\s*(?:№\s*)?\d""")
    private val houseBehindRe = Regex("""(?:д\.|дом[ауе]?)\s*\d+\p{L}?\s*,\s*$""", RegexOption.IGNORE_CASE)
    private val accNameAheadRe = Regex("""^\s*[«"]?[А-ЯЁ][а-яё]*(?:ую|юю)(?![а-яё])""")
    private fun nounAbbrev(text: String) = nounAbbrRe.replace(text) { m ->
        val (prep, abbrRaw) = m.destructured
        val key = abbrRaw.removeSuffix(".").lowercase()
        val rest = text.substring(m.range.last + 1)
        val digit = digitAfterRe.containsMatchIn(rest)
        // у «т.» римская цифра и точка с именем следом — инициалы («Т. I. Смирнов»), у остальных — номер («кн. V. Там»)
        val roman = !digit && (if (key == "т") romanAfterRe else romanLooseAfterRe).containsMatchIn(rest)
        val before = text.substring(0, m.range.first)
        val forms: Array<String> = when {
            key == "стр" -> if (digit && houseBehindRe.containsMatchIn(before + prep + (if (prep.isEmpty()) "" else " "))) buildingForms else return@replace m.value
            // «и т. д. Потом» — «так далее», не деревня
            key == "д" && !digit -> if (etcBehindRe.containsMatchIn(before + prep)) return@replace m.value else villageForms
            // «д. 5» — дом только в адресе (улица перед ним или квартира/корпус следом): «в д. 5 архива» — дело
            key == "д" && !addressBehindRe.containsMatchIn(before) && !addressAheadRe.containsMatchIn(rest) -> return@replace m.value
            else -> nounAbbrs[key]?.let { a ->
                // «т.», «ч.», «кн.», «разд.» — и перед римской цифрой
                // «с. XV», «(с. CVIII—CXIII)» — страница с римским номером; с арабским её читает abbrevSimple
                val ok = (a.digit && (digit || roman && key in setOf("т", "ч", "кн", "разд"))) || (a.name && !digit && !roman) ||
                    key == "с" && roman
                if (key == "с" && roman && romanRangeAheadRe.containsMatchIn(rest)) return@replace (if (prep.isEmpty()) "" else "$prep ") +
                    (if (prep.isEmpty()) "страницы" else "страницах")
                if (ok) a.forms else null
            } ?: return@replace m.value
        }
        // «Т.» с заглавной — том только перед числом («Т. II», «Т. 3»), не инициал («Т. И. Петров»)
        if (abbrRaw == "Т." && !digit && !roman) return@replace m.value
        // «ч.» после числа — часы («2 ч. дня»): сюда не доходит, (?<!\d\s?) выше
        val p = prep.lowercase()
        var case = nounAbbrPrepCase[p] ?: Case.NOM
        if ((p == "на" || p == "в" || p == "во") && (accNameAheadRe.containsMatchIn(rest) || key in setOf("прим", "т", "ч", "кн") && p == "на" ||
                // «переехал в г. Казань», «прибыл в пос. Заречный» — куда: винительный
                motionBehindRe.containsMatchIn(before))) case = Case.ACC
        // без предлога: после «смотри» — винительный («смотри кн. III»), после существительного — родительный
        // («оконечности оз. Иссык-Куль», «в состав Респ. Крым»)
        if (prep.isEmpty()) {
            if (seeBehindRe.containsMatchIn(before)) case = Case.ACC
            else prevOnlyWordRe.find(before)?.groupValues?.get(1)?.lowercase()?.takeUnless { it.startsWith("адрес") }?.let { w ->
                morph?.tags(w)?.let { t -> if (Morph.isNoun(t) && !Morph.isAdjective(t)) case = Case.GEN } }
        }
        val word = forms[case.ordinal].let { if (abbrRaw[0].isUpperCase() && prep.isEmpty()) it.replaceFirstChar { c -> c.uppercase() } else it }
        val pr = if ((p == "с" || p == "к" || p == "в") && word.length > 1 && word[0] in "сзвшж" && word[1] !in "аеёиоуыэюя") prep + "о" else prep
        (if (prep.isEmpty()) "" else "$pr ") + word + if (digit && rest.firstOrNull()?.isWhitespace() != true) " " else ""
    }
    private val addressBehindRe = Regex("""(?:ул\.|улиц\p{L}*|пр-т|пр-кт|просп\.|проспект\p{L}*|пер\.|переул\p{L}*|ш\.|шоссе|наб\.|набережн\p{L}*|пл\.|площад\p{L}*|б-р|бульвар\p{L}*|мкр\.|микрорайон\p{L}*|адрес\p{L}*:?)\s+[^.;!?]{0,40}$""", RegexOption.IGNORE_CASE)
    private val addressAheadRe = Regex("""^\s*\d+\p{L}?\s*,\s*(?:кв|корп|стр|оф|под)\.""")
    private val motionBehindRe = Regex("""(?<![\p{L}])\p{L}*(?:ехал[аио]?|ехать|едет|едут|еду|ехали|шёл|шла|шли|ходил[аи]?|прибыл[аи]?|прибыть|вернул(?:ся|ась|ись)|отправил(?:ся|ась|ись)|направил(?:ся|ась|ись)|переселил(?:ся|ась|ись)|въехал[аи]?|летел[аи]?|везли|вёз|везла)\s+$""", RegexOption.IGNORE_CASE)
    private val etcBehindRe = Regex("""[тТ]\.\s*$""")
    private val seeBehindRe = Regex("""(?<![\p{L}])(?:смотри|см\.|сравни|ср\.)\s+$""", RegexOption.IGNORE_CASE)
    // «Московская обл.», «в Московской обл.» — падеж по прилагательному и предлогу перед ним
    private val oblRe = Regex("""(?:(?<![\p{L}])(в|во|из|до|от|по|у|для|за|к|о|об|при)\s+)?((?-i:[А-ЯЁ][а-яё]+(?:ая|ой|ую|ей)))\s+(обл|р-н)\.?(?![\p{L}])""", RegexOption.IGNORE_CASE)
    private val oblForms = mapOf("обл" to nf("область", "области", "области", "область", "областью", "области"),
        "р-н" to nf("район", "района", "району", "район", "районом", "районе"))
    private fun oblAbbrev(text: String) = oblRe.replace(text) { m ->
        val (prep, adj, key) = m.destructured
        val p = prep.lowercase()
        val case = when {
            adj.endsWith("ая") -> Case.NOM; adj.endsWith("ую") -> Case.ACC
            p.isNotEmpty() -> nounAbbrPrepCase[p] ?: Case.GEN
            else -> Case.GEN
        }
        // регулярка без учёта регистра: «Московская Обл.» даёт ключ «Обл» (без lowercase — NoSuchElementException на весь запрос)
        val word = oblForms.getValue(key.lowercase())[case.ordinal]
        (if (prep.isEmpty()) "" else "$prep ") + adj + " " + word + if (m.value.endsWith(".")) dotAfter(text, m) else ""
    }
    // «Прим. пер.» — примечание переводчика; «на прим. сербы» — например
    private val primPerRe = Regex("""(?<![\p{L}])([пП])рим\.\s*пер\.""")
    private val naPrimRe = Regex("""(?<![\p{L}])([нН])а\s+прим\.(?=\s+[а-яё])""")
    // «Шафарик. Т. I. С. 315.», «Т. 3. С. 45.» — «С.» после тома в выходных данных — страница (сам по себе «С. 6» — инициал)
    private val pageAfterTomeRe = Regex("""(?<=(?:том|Том|т\.|Т\.)\s(?:[IVXLC]{1,8}|\d{1,4})\.?,?\s)С\.\s*(?=\d)""")
    private val pageRangeAheadRe = Regex("""^\d+\s*[-–—]\s*\d+""")
    private fun nounAbbrevs(text: String): String {
        var s = primPerRe.replace(text) { "${if (it.groupValues[1] == "П") "П" else "п"}римечание переводчика" }
        s = naPrimRe.replace(s) { "${it.groupValues[1]}апример" }
        s = pageAfterTomeRe.replace(s) { m -> if (pageRangeAheadRe.containsMatchIn(s.substring(m.range.last + 1))) "страницы " else "страница " }
        return nounAbbrev(oblAbbrev(s))
    }

    // «см. рис. 2 и табл. 3, стр. 4» — всё перечисление после «см.» в винительном: «таблицу три, страницу четыре».
    private val seeListRe = Regex("""(?<![\p{L}])((?:смотри|см\.)\s+)([^!?;\n]*?)(?=[!?;\n]|\.\s+[А-ЯЁ]|\.?\s*$)""", RegexOption.IGNORE_CASE)
    private val seeAccRe = Regex("""(?<![\p{L}\d])(табл|стр|гл)\.(?![\p{L}])""", RegexOption.IGNORE_CASE)
    private val seeAccWords = mapOf("табл" to "таблицу", "стр" to "страницу", "гл" to "главу")
    // «на стр. 12–15» — «на страницах с двенадцатой по пятнадцатую», «смотри стр. 5—6» — «страницы с пятой по шестую»,
    // «(с.22-25)» — «страницы двадцать два — двадцать пять» (было «странице двенадцать пятнадцать»)
    private val pageRangeRe = Regex("""(?:(?<![\p{L}])(на|в|смотри|см\.)\s*)?(?<![\p{L}\d])(?-i:с|стр)\.\s*(\d{1,4})\s*[-–—]\s*(\d{1,4})(?![\d.,]\d)""", RegexOption.IGNORE_CASE)
    private fun pageRanges(text: String) = pageRangeRe.replace(text) { m ->
        val (prep, a, b) = m.destructured
        val an = a.toLongOrNull() ?: return@replace m.value; val bn = b.toLongOrNull() ?: return@replace m.value
        when (prep.lowercase()) {
            "на", "в" -> "$prep страницах с ${ordinal(an, "ой")} по ${ordinal(bn, "ю")}"
            "" -> "страницы $a – $b"
            else -> "$prep страницы с ${ordinal(an, "ой")} по ${ordinal(bn, "ю")}"
        }
    }
    private fun abbreviations(text: String): String {
        var s = seeListRe.replace(abbrevPrep(pageRanges(text))) { m ->
            m.groupValues[1] + seeAccRe.replace(m.groupValues[2]) { seeAccWords.getValue(it.groupValues[1].lowercase()) }
        }
        morph?.let { s = adjAbbrev(s, it) }
        s = saintRe.replace(s) { m ->
            val (abbr, name) = m.destructured
            val (female, case) = nameGenderCase(name)
            val word = saintForms.getValue(if (female) 'f' else 'm')[case.ordinal]
            (if (abbr[0].isUpperCase()) word else word.lowercase()) + " " + name
        }
        for ((re, rep) in abbrevSimple) s = expandAbbrev(s, re, rep)
        return s
    }

    // «ум.», «род.» перед годом, «в …», «ок.», «после», месяцем — «умер», «родился» («(ум. 1945)», «род. в 1890 г.», «(ум. ок. 62
    // г.)»); «род. п.», «в род. падеже» — родительный падеж, не трогаем. Раньше дат и годов: «род.28.10.79» — дата после слова.
    // Голый год после них — «в … году» («(ум. 945)» — «умер в девятьсот сорок пятом году», было «ум. девятьсот сорок пять»);
    // с «г.» предлог «в» дальше читают правила года с предлогом.
    // Род: существительное вплотную к имени («дочь Джулию Суассуна (род.», «Барстоу, внучка, род.»), потом отчество во фразе
    // («-овна» — женский, «-овича» — мужской: «в семье Ивана Дмитриевича Захарова (род. в 1905)»), потом имя: женское из
    // списков монархов или фамилия на «-ова/-ина/-ская…» («Анна Ахматова (род. 1889)» — «родилась»).
    // ponytail: род по окончанию; «Валерия», «Аспасия» читаются «умер», «жена Пушкина (ум. 1837)» — «умерла»
    private fun bio(text: String): String {
        val s = bioYearRe.replace(text) { m ->
            if (!bioPlace(text, m.range.first)) m.value else m.groupValues[1] + " в " + m.groupValues[2].ifEmpty { m.groupValues[3] + "-м году" } }
        return bioAbbrevRe.replace(s) { m ->
            if (!bioPlace(s, m.range.first)) return@replace m.value
            val before = phraseTail(s.substring(maxOf(0, m.range.first - 60), m.range.first))
            val word = if (m.groupValues[1].equals("ум", true)) (if (bioFemale(before)) "умерла" else "умер") else if (bioFemale(before)) "родилась" else "родился"
            (if (m.value[0].isUpperCase()) word.replaceFirstChar(Char::uppercaseChar) else word) +
                (if (s.getOrNull(m.range.last + 1)?.isLetterOrDigit() == true) " " else "")
        }
    }
    // Место биографической пометки: в скобках, после знака («, ум.», «— ум.», «; род.», «(Ричард) род.»), в начале фразы или после слова с
    // заглавной (имени: «Он род. в 1890 г.», «Поэт ум. в 1945 году»). После строчного слова вне скобок «ум.», «род.» — слово:
    // «У него был острый ум. 5 лет он учился», «Это был великий ум. в наше время таких мало».
    private fun bioPlace(s: String, at: Int): Boolean {
        val b = s.substring(maxOf(0, at - 80), at)
        val t = b.trimEnd()
        // в начале куска — только «Ум.», «Род.» с заглавной: кусок чтеца мог начаться посреди фразы («…острый | ум. 5 лет»)
        if (t.isEmpty()) return s.getOrNull(at)?.isUpperCase() == true
        if (t.last() in "(;,:—–-.!?)" || b.count { it == '(' } > b.count { it == ')' }) return true
        val w = t.takeLastWhile { it.isLetter() }
        if (w.isEmpty() || !w[0].isUpperCase()) return false
        // первое слово фразы — прилагательное или местоимение: «Великий ум. 5 лет…», «Его ум. в 1990 году…», «Древний род.
        // 1200 лет…» — слово, не пометка (ревью ru-fixes-2 M-1); «Он род. в 1890 г.» — пометка
        val first = t.dropLast(w.length).trimEnd().let { it.isEmpty() || it.last() in ".!?…" }
        val tags = morph?.tags(w.lowercase()) ?: 0
        val lw = w.lowercase()
        return !(first && (Morph.isAdjective(tags) || lw in bioPronouns) && lw !in setOf("он", "она"))
    }
    // Хвост фразы перед пометкой: режем по концу предложения, но не по точке сокращения — во второй пометке «(род. 1889 — ум.
    // 1966)» имя иначе терялось за точкой «род.» (ревью ru-fixes-2 I-2: «родилась … умер»)
    private val bioPronouns = setOf("его", "её", "ее", "их", "мой", "твой", "наш", "ваш", "свой", "этот", "тот", "весь", "сам", "чей")
    private val abbrDotAtEndRe =Regex("""(?<![\p{L}])(?:\p{L}|ум|род|ок|гг|вв|др|пр|им|ст|св|ср|см)\.$""", RegexOption.IGNORE_CASE)
    private fun phraseTail(b: String): String {
        var i = b.length
        while (true) {
            val j = if (i <= 0) -1 else b.lastIndexOfAny(charArrayOf('.', '!', '?'), i - 1)
            if (j < 0) return b
            if (b[j] == '.' && abbrDotAtEndRe.containsMatchIn(b.substring(0, j + 1))) { i = j; continue }
            return b.substring(j + 1)
        }
    }
    private val wordTokRe = Regex("""[\p{L}-]+""")
    private fun bioFemale(raw: String): Boolean {
        // знак ударения рвёт слово: «В+ера Никол+аевна К+онюхова» — «К» вышло бы инициалом, а «Никол» — именем
        val before = raw.replace("+", "").replace("́", "")
        val toks = wordTokRe.findAll(before).map { it.value }.toList()
        // инициалы пропускаем: «Ахматова А. А. (род.» — имя «Ахматова» (повторное ревью ru-fixes-2 M-b)
        val last = toks.indexOfLast { it[0].isUpperCase() && it.length > 1 }
        if (last >= 0) {
            var first = last
            while (first > 0 && toks[first - 1][0].isUpperCase()) first--
            // существительное рядом с именем или в начале фразы с заглавной: «дочь», «сын», «Работница Симона Эврар»; только
            // единственное число («детей: Мария (род.» — не о детях)
            val m = morph
            if (m != null) for (w in listOfNotNull(toks.getOrNull(last + 1)) + toks.subList(first, last + 1).reversed() + listOfNotNull(toks.getOrNull(first - 1))) {
                val t = m.tags(w.lowercase())
                if (Morph.isNoun(t) && Morph.animate(t) && Morph.nounCases(t, plural = false).isNotEmpty() && Morph.nounCases(t, plural = true).isEmpty())
                    Morph.gender(t)?.let { return it == Gender.F }
            }
            if (femalePatronymicRe.containsMatchIn(before)) return true
            val names = toks.subList(first, last + 1)
            return names.any { it.lowercase() in femaleIndecl || nameGenderCase(it).first } ||
                !malePatronymicRe.containsMatchIn(before) && femaleNameEndRe.containsMatchIn(toks[last])
        }
        return femalePatronymicRe.containsMatchIn(before)
    }
    private val bioYearRe by lazy { Regex(
        """(?<![\p{L}])((?:ум|род)\.)\s*(?:(\d{1,4}\s*г\.)|(\d{3,4})(?![\d/\-–—]|[.,:]\d|\s*(?:год|г\.|$monthGenAlt|до\s+н|н\.\s*э)))""",
        RegexOption.IGNORE_CASE) }
    private val bioAbbrevRe by lazy { Regex(
        // предлог и месяц — только строчными: «острый ум. В 1990 году…» — конец фразы
        """(?<![\p{L}\d])(ум|род)\.(?=\s*(?:\d(?!\d*(?:-\p{L}+)?\s+год(?![\p{L}]))|(?-i:в|во|ок\.|около|после|до)\s|(?-i:$monthsAlt|$monthGenAlt)(?![\p{L}])))""",
        RegexOption.IGNORE_CASE) }
    private val femaleNameEndRe = Regex("""(?:ова|ева|ёва|ина|ына|ская|цкая)$""")
    private val femalePatronymicRe = Regex("""[а-яё](?:овн|евн|ичн)[аы](?![а-яё])""")
    private val malePatronymicRe = Regex("""[а-яё](?:ович|евич|ич)а?(?![а-яё])""")

    /** Сокращение в конце предложения («и т. д. Потом», «100 руб. Дорого») — точка остаётся, иначе предложения
     * склеиваются. Кроме тех, за которыми обычно имя с заглавной: «ул. Ленина», «проф. Иванов», «см. Иванов», «лат. Rosa». */
    private val abbrevBeforeName = setOf("ул", "проф", "акад", "св", "ср", "см", "напр", "им", "mr", "mrs", "dr", "г", "н", "лат")
    private fun expandAbbrev(s: String, re: Regex, rep: String): String {
        if ('$' in rep) return re.replace(s, rep)
        return re.replace(s) { m ->
            if (m.value.dropLast(1).substringAfterLast(' ').lowercase() in abbrevBeforeName) rep else rep + dotAfter(s, m)
        }
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
        """(?<![\p{L}])(глав(?:а|ы|е|у|ой|ах|ам|ами)?|част(?:ь|и|ью|ей|ях|ям|ями)|книг(?:а|и|е|у|ой|ах|ам|ами)?|раздел(?:а|у|е|ом|ов|ах|ам|ами)?)\s+(\d{1,3})(?!\d|[.,]\d|[:/\uE001]|\s*[-–—]?\s*\d|-?(?:$ordSuffixAlt)(?![а-яё\d]))(?:(\s+(?:и|или)\s+)(\d{1,3})(?![\d.,:/-]))?""",
        RegexOption.IGNORE_CASE
    )
    // Номер перед существительным: «22 глава» → «22-я глава» (жалоба 10.10.2026). Только им. ед.: счёт был бы «22 главы»,
    // «5 глав», «22 тома». Число на 1 («21 глава») совпадает со счётом, его — только в начале строки, как заголовок.
    private val chapterBeforeRe = Regex(
        """(?<![\p{L}\d.,:/–—-])(\d{1,3})\s+(глава|часть|книга|том|раздел)(?![\p{L}\d-])""", RegexOption.IGNORE_CASE)
    private fun chapterBefore(text: String) = chapterBeforeRe.replace(text) { m ->
        val (num, noun) = m.destructured
        val n = num.toInt()
        val before = text.substring(0, m.range.first).trimEnd(' ', '\t')
        if (n % 10 == 1 && n % 100 != 11 && before.isNotEmpty() && !before.endsWith('\n')) return@replace m.value
        val stem = romanNounGender.keys.first { noun.lowercase().startsWith(it) }
        "$num-${ordSuffixByCase.getValue(romanNounGender.getValue(stem))[Case.NOM.ordinal]} $noun"
    }
    private fun chapterOrdinals(text: String) = chapterRe.replace(chapterRangeRe.replace(chapterBefore(text)) { m ->
        val (noun, a, b) = m.destructured
        val stem = romanNounGender.keys.firstOrNull { noun.lowercase().startsWith(it) } ?: return@replace m.value
        val suf = ordSuffixByCase.getValue(romanNounGender.getValue(stem))
        val an = a.toLong()
        val prep = if (an % 10 == 2L && an % 100 != 12L) "со" else "с"
        // сразу словами: суффикс «-й» у второго числа иначе переиначил бы femTailRe по слову следом («3-й опубликованы»)
        "$noun $prep ${ordinal(an, suf[Case.GEN.ordinal])} по ${ordinal(b.toLong(), suf[Case.ACC.ordinal])}"
    }) { m ->
        val (noun, num, conj, num2) = m.destructured
        val stem = romanNounGender.keys.firstOrNull { noun.lowercase().startsWith(it) } ?: return@replace m.value
        val case = romanNounCase(noun.lowercase(), plural = false) ?: return@replace m.value
        val sfx = ordSuffixByCase.getValue(romanNounGender.getValue(stem))[case.ordinal]
        // «в главе 8 и 9» — второй номер в том же падеже
        "$noun $num-$sfx" + if (num2.isNotEmpty()) "$conj$num2-$sfx" else ""
    }

    // «во-1, во-2» — «во-первых, во-вторых»; пункты списка «1) … 2) …» после двоеточия, точки с запятой или в начале —
    // «первое, второе» (было «один, два»). Скобка у номера после слова или открытая раньше — не пункт.
    private val voNumRe = Regex("""(?<![\p{L}])[вВ]о?-(\d)(?![\d\p{L}])""")
    private val voWords = arrayOf("", "во-первых", "во-вторых", "в-третьих", "в-четвёртых", "в-пятых", "в-шестых", "в-седьмых", "в-восьмых", "в-девятых")
    // только после двоеточия в той же фразе: «Причины такие: 1) голод; 2) холод», «1) первое 2) второе» без него — скобки
    private val listItemRe = Regex("""(?<=:\s?|[;,]\s|\sи\s)(\d{1,2})\)(?=\s)""")
    // «12 числа» — «двенадцатого», «в 9 часу» — «девятом», «на 27 неделю» — «двадцать седьмую», «22 суток» — «двадцать двое»
    private val dayNumberRe = Regex("""(?<![\d.,\p{L}-])(\d{1,2})(?=\s+числа(?![а-яё]))""")
    private val hourOrdRe = Regex("""(?<=(?<![\p{L}])[вВ]\s)(\d{1,2})(?=\s+часу(?![а-яё]))""")
    private val weekOrdRe = Regex("""(?<=(?<![\p{L}])(?:на|в)\s)(\d{1,2})(?=\s+неделю(?![а-яё]))""", RegexOption.IGNORE_CASE)
    private val sutkiRe = Regex("""(?<![\d.,])(\d+)(?=\s+(суток|сутки)(?![а-яё]))""")
    // Реквизиты — по цифрам: «ИНН 7701234567», «КПП 770101001», «ОГРН …» (было «семь миллиардов…»)
    private val requisiteRe = Regex("""(?<![\p{L}])(ИНН|КПП|ОГРН|ОГРНИП|СНИЛС|БИК|ОКПО|ОКВЭД|ОКАТО|ОКТМО)(\s*:?\s*)(\d[\d -]{2,20}\d)(?![\d.,]\d)""")
    // «494, 495 и 496-й статьи» — суффикс последнего порядкового у всей цепочки
    private val ordChainRe = Regex("""(?<![\d.,/-])(\d+(?:,\s+\d+)*)(,?\s+(?:и|или)\s+)(\d+)-(й|я|е|ю|го|му|м|х|ом|ой|ую|ым|ых)(?![а-яё\d])""")
    private fun listsAndOrdinals(text: String): String {
        var s = ordChainRe.replace(text) { m ->
            val sfx = m.groupValues[4]
            chainNumTokRe.replace(m.groupValues[1]) { "${it.value}-$sfx" } + m.groupValues[2] + m.groupValues[3] + "-" + sfx
        }
        s = voNumRe.replace(s) { m -> voWords[m.groupValues[1].toInt()].let { if (m.value[0] == 'В') it.replaceFirstChar { c -> c.uppercase() } else it }.ifEmpty { m.value } }
        s = listItemRe.replace(s) { m ->
            val sentence = s.substring(0, m.range.first).split('.', '!', '?', '…').last()
            if (':' in sentence) ordinal(m.groupValues[1].toLong(), "е") + "," else m.value
        }
        s = dayNumberRe.replace(s, "$1-го")
        s = hourOrdRe.replace(s, "$1-м")
        s = weekOrdRe.replace(s, "$1-ю")
        s = sutkiRe.replace(s) { m ->
            val n = m.groupValues[1].toLongOrNull() ?: return@replace m.value
            val few = mapOf(2L to "двое", 3L to "трое", 4L to "четверо")[n % 10]
            when {
                n % 100 in 11..14 -> m.value
                n % 10 == 1L && m.groupValues[2] == "сутки" -> cardinal(n).removeSuffix("один") + "одни"
                few != null && m.groupValues[2] == "суток" -> cardinal(n - n % 10).let { if (it == "ноль") "" else "$it " } + few
                else -> m.value
            }
        }
        return s
    }
    private fun requisites(text: String) = requisiteRe.replace(text) { m -> m.groupValues[1] + m.groupValues[2] + spellDigits(m.groupValues[3]) }
    // 11c. «ученик 1 класса», «капитан 4 ранга», «25 категории» — порядковое в род. п. (жалоба 15.10.2026, было
    // «одна класса», «четыре ранга»). Слово следом в род. ед.: при числе на 5–20, 0 количественное было бы в род. мн.
    // («пяти классов»), при числе на 1 — «одного класса», а пишут так почти всегда порядковое, поэтому всегда
    // порядковое, и после предлога («из 5 класса» — «из пятого»). На 2–4 форма совпадает со счётной («2 класса
    // учеников»): порядковое только за существительным по таблице морфологии («инвалид 2 группы»), не после
    // «в/на/о/при + существительное» («в школе 2 класса») и не после глагола или предлога («открыли 2 класса»).
    private val ordNounGender = mapOf("класса" to 'm', "разряда" to 'm', "ранга" to 'm', "уровня" to 'm', "сорта" to 'm',
        "курса" to 'm', "созыва" to 'm', "этапа" to 'm', "категории" to 'f', "степени" to 'f', "группы" to 'f', "очереди" to 'f')
    private val ordNounRe = Regex("""(?<![\p{L}\d.,:/‐-—-])(\d{1,3})\s+(${ordNounGender.keys.joinToString("|")})(?![\p{L}\d])""", RegexOption.IGNORE_CASE)
    private val prevWordsRe = Regex("""(?:(\p{L}+)\s+)?(\p{L}+)\s+$""")
    private val locPreps = setOf("в", "во", "на", "о", "об", "при")
    private fun ordNouns(text: String) = ordNounRe.replace(text) { m ->
        val n = m.groupValues[1].toLong()
        if (n % 10 in 2..4 && n % 100 !in 12..14) {
            val morph = morph ?: return@replace m.value
            val pw = prevWordsRe.find(text.substring(maxOf(0, m.range.first - 60), m.range.first)) ?: return@replace m.value
            val t = morph.tags(pw.groupValues[2].lowercase())
            if (!Morph.isNoun(t) || Morph.isAdjective(t) || pw.groupValues[1].lowercase() in locPreps) return@replace m.value
        }
        m.withGroupReplaced(1 to ordinal(n, if (ordNounGender.getValue(m.groupValues[2].lowercase()) == 'f') "ой" else "го"))
    }

    // 11d. Диапазон дней перед месяцем: «3-15 мая», «7…9 апреля» → «с третьего по пятнадцатое мая» (жалоба 15.10.2026,
    // было «три-пятнадцать мая»). После «с/со» — то же, предлог не дублируем. После другого предлога «с … по» не
    // вставить («до 7-9 апреля», «на 3-15 мая») — оба дня в падеже по предлогу, как в dayMonth(), через тире.
    private val dayRangeRe = Regex(
        """(?<![\p{L}\d.,:/‐-—-])(?:(с|со)\s+)?(\d{1,2})\s*[‐-—…-]\s*(\d{1,2})(\s+)($monthGenAlt)(?![а-яё])""",
        RegexOption.IGNORE_CASE
    )
    private val anyPrepRe = Regex("""(?<![\p{L}])(?:к|ко|до|после|от|перед|на|в|во|по|о|об|около|начиная\s+с)\s+$""", RegexOption.IGNORE_CASE)
    private fun dayRanges(text: String) = dayRangeRe.replace(text) { m ->
        val (s, a, b, sp, month) = m.destructured
        val n1 = a.toLong(); val n2 = b.toLong()
        if (n1 !in 1..30 || n2 !in 2..31 || n1 >= n2) return@replace m.value
        val before = text.substring(maxOf(0, m.range.first - 30), m.range.first)
        if (s.isEmpty() && anyPrepRe.containsMatchIn(before)) {
            val suf = daySuffix(before)
            return@replace "${ordinal(n1, suf)} — ${ordinal(n2, suf)}$sp$month"
        }
        val prep = if (n1 == 2L) "со" else "с"
        "${if (s.isNotEmpty() && s[0].isUpperCase()) prep.replaceFirstChar { it.uppercase() } else prep} ${ordinal(n1, "го")} по ${ordinal(n2, "е")}$sp$month"
    }

    // 12. Падеж числительного (task 19b): предлог-триггер перед числом или окончание соседнего
    // слова задают падеж — «около 500 рублей» → «около пятисот рублей», «в 5 случаях» →
    // «в пяти случаях». К этому моменту год, дата, время, «N-ти», дробь и процент уже раскрыты
    // предыдущими проходами — «хвост» после числа (numTailExclude) отсекает их снова: суффикс
    // -й/-го/…, дробь/процент через «.,:/», «год/года/году», а также «день + месяц» без точек
    // («к 1 сентября») — дата без разделителя сюда не долетает, порядковое ей строит dayMonth()
    // после cases() по предлогу («к первому сентября»).
    private val amplifierRe =
        """(?:примерно|почти|приблизительно|чем|всего|целых|лишь|ещё|уже|только|каких-то|где-то|плюс)"""
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
        """дороже|дешевле|ранее|позднее|в\s+возрасте|в\s+количестве|в\s+течение|в\s+размере|в\s+сумме|в\s+составе|в\s+районе|""" +
        """(?:больше|меньше)\s+или\s+равн[оаы]|из-за|""" +
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

    // Цепочка чисел с общим падежом: «3 и 5 заводами», «3, 5 и 7 участникам», «3-5 квартирами», «не хватило 3 и 5
    // участников» — падеж решает последнее число (его существительное) или предлог перед первым; каждое число
    // склоняется само (было «три и пятью заводами», «в три-пяти квартирах»).
    // запятая — только с пробелом после: «1,1» — дробь
    // сокращение — только с точкой: «с 21 новыми» — предлог, не «с.» (страница)
    private val numLabelBehindRe = Regex("""(?<![\p{L}])(?:(?:пункт\p{L}{0,2}|стать[яиюейё]{1,2}|глав[аыуеой]{1,2}|номер\p{L}{0,2}|том[аеу]?|раздел\p{L}{0,2}|""" +
        """част[ьиюей]{1,2}|параграф\p{L}{0,2}|страниц\p{L}{0,2})\s+|(?:п|пп|ст|гл|ч|рис|табл|стр|с)\.\s*|[§№]\s*)$""", RegexOption.IGNORE_CASE)
    private val numChain = """\d+(?:(?:,\s+|\s*[-–—]\s*|\s+(?:и|или)\s+)\d+){0,6}"""
    private val chainNumTokRe = Regex("""\d+""")
    /** Группа [g] — цепочка чисел: каждое число заменяет [f] (null — число как было). */
    private fun MatchResult.replaceChain(g: Int, f: (Long) -> String?): String =
        withGroupReplaced(g to chainNumTokRe.replace(groupValues[g]) { t -> t.value.toLongOrNull()?.let(f) ?: t.value })
    private fun triggerRe(alt: String) = Regex(
        """(?<![\p{L}-])$alt(?![\p{L}])(?:\s+$amplifierRe(?![\p{L}]))*\s+(-?$numChain)$numTailExclude""",
        RegexOption.IGNORE_CASE
    )
    private val genTriggerRe = triggerRe(genTriggerAlt)
    private val genFracRe = Regex(
        """(?<![\p{L}-])$genTriggerAlt(?![\p{L}])(?:\s+$amplifierRe(?![\p{L}]))*\s+(-?)(\d+)[.,](\d+)(?![\d,.:/]| ?%)""",
        RegexOption.IGNORE_CASE
    )
    /** «7», «25» → «семь целых двадцать пять сотых»; null — дробная часть длиннее fracStems. */
    // «с 102,3 до 104,7» — первая дробь диапазона тоже в родительном
    private val fracFromRe = Regex("""(?<![\p{L}-])(?:с|со)(?![\p{L}])\s+(?:плюс\s+)?(-?)(\d+)[.,](\d+)(?![\d,.:/]| ?%)(?=[^.;!?]{0,40}?\sдо\s)""", RegexOption.IGNORE_CASE)
    private fun fraction(n: Long, frac: String): String? {
        val fracN = frac.toLongOrNull() ?: return null
        val stem = fracStems.getOrNull(frac.length - 1) ?: return null
        return cardinal(n, feminine = true) + (if (n % 10 == 1L && n % 100 != 11L) " целая " else " целых ") +
            cardinal(fracN, feminine = true) + " " + plural(fracN, Triple(stem + "ая", stem + "ых", stem + "ых"))
    }
    private fun genFraction(minus: String, intPart: String, frac: String) = fractionCase(minus, intPart, frac, Case.GEN)
    // окончания «целая»/«десятая» в падеже: единственное (1, 21…) и множественное
    private val fracEndSg = arrayOf("ая", "ой", "ой", "ую", "ой", "ой")
    private val fracEndPl = arrayOf("ых", "ых", "ым", "ых", "ыми", "ых")
    /** Дробь в падеже: «двумя целыми пятьюдесятью сотыми»; null — дробная часть длиннее fracStems. */
    private fun fractionCase(minus: String, intPart: String, frac: String, case: Case): String? {
        val n = intPart.toLongOrNull() ?: return null
        val f = frac.toLongOrNull() ?: return null
        val stem = fracStems.getOrNull(frac.length - 1) ?: return null
        // после «ноль», «тысяча», «миллион» — родительный мн. ч. в любом падеже: «с нулём целых», «с одной тысячей целых»
        // (ревью ru-fixes-2 M-2)
        val end = { x: Long -> if (x == 0L || x >= 1000 && x % 1000 == 0L) "ых"
            else (if (x % 10 == 1L && x % 100 != 11L) fracEndSg else fracEndPl)[case.ordinal] }
        return (if (minus.isEmpty()) "" else "минус ") + Declension.cardinal(n, case, feminine = true) + " цел" + end(n) + " " +
            Declension.cardinal(f, case, feminine = true) + " " + stem + end(f)
    }
    // Дробь в косвенном падеже: «к 7,5 годам» — «к семи целым пяти десятым года», «с 2.50 рублями» — «с двумя целыми
    // пятьюдесятью сотыми рубля» (было «с две целых пятьдесят сотых рублями»). Падеж — по предлогу или по окончанию
    // существительного, как у целых чисел; существительное во мн. ч. после дроби — в родительный ед. ч. (genSgNoun).
    // Родительный (genFracRe) и винительный («в 1,5 раза») — как были.
    // целая часть с нулём впереди («по 06.50 силами») — время, не дробь; «1,5-й» — не дробь в падеже
    private val fracNum = """(-?)((?!0\d)\d+)[.,](\d+)(?![\d,.:/-]| ?%)"""
    private fun fracTriggerRe(alt: String) = Regex(
        // «при минус 2,5 градусах» — «минус» словом между предлогом и числом (ревью ru-fixes-2 M-3)
        """(?<![\p{L}-])$alt(?![\p{L}])(?:\s+$amplifierRe(?![\p{L}]))*\s+(?:минус\s+)?$fracNum(?:(\s+)(\p{L}+))?""", RegexOption.IGNORE_CASE)
    private val fracInsEndingRe = Regex("""(?<![\p{L}\d.,-])$fracNum(\s+)(\p{L}+(?:ами|ями|ьми))(?![\p{L}])""", RegexOption.IGNORE_CASE)
    // «равен 0,5» без существительного — именительный, как в корпусе ru-normalizr («равен ноль целых пять десятых»)
    private val fracCaseRes by lazy { listOf(fracInsEndingRe to Case.INS, fracTriggerRe("""(?:к|ко|благодаря|вопреки)""") to Case.DAT,
        fracTriggerRe(insTriggerAlt) to Case.INS,
        fracTriggerRe(preTriggerAlt) to Case.PRE,
        Regex("""(?<![\p{L}])(?:в|во|на)\s+$fracNum(\s+)(\p{L}+(?:ах|ях))(?![\p{L}])""", RegexOption.IGNORE_CASE) to Case.PRE,
        Regex("""(?<![\p{L}\d.,-])$fracNum(\s+)(\p{L}{3,}(?:ам|ям))(?![\p{L}])""", RegexOption.IGNORE_CASE) to Case.DAT) }
    private fun fracCase(s: String, re: Regex, case: Case) = re.replace(s) { m ->
        val (minus, intPart, frac, sp, noun) = m.destructured
        if (case == Case.DAT && noun.lowercase() in datEndingStop) return@replace m.value
        // «17.00», «7.00 жителям» — время, не дробь
        if (frac == "00") return@replace m.value
        // по окончанию: номер, а не количество («Версия 2.0 пользователям доступна», «глава 1.5 студентам», «LSS 3.0 картами»),
        // и предлог другого падежа перед числом («в 14.30 ударами волн», «по 7.25 рублям») — падеж по окончанию не берём
        if (m.value[0].isDigit() || m.value[0] == '-') {
            val before = s.substring(maxOf(0, m.range.first - 40), m.range.first)
            if (numLabelBehindRe.containsMatchIn(before) || versionBehindRe.containsMatchIn(before) ||
                (if (case == Case.INS) insForeignPrepRe else datForeignPrepRe).containsMatchIn(before)) return@replace m.value
        }
        val words = fractionCase(minus, intPart, frac, case) ?: return@replace m.value
        m.value.substring(0, m.groups[1]!!.range.first - m.range.first) + words + sp + genSgNoun(noun)
    }
    private val versionBehindRe = Regex("""(?:(?<![\p{L}])(?:верси\p{L}{1,2}|обновлени\p{L}{1,2}|релиз\p{L}{0,2})|[A-Za-z]+\.?)\s*$""", RegexOption.IGNORE_CASE)
    private const val FOREIGN_PREP = """в|во|на|о|об|обо|при|по|от|до|из|у|для|без|через|про|за|под|над"""
    private val datForeignPrepRe = Regex("""(?<![\p{L}])(?:$FOREIGN_PREP|с|со|между|перед)\s+$""", RegexOption.IGNORE_CASE)
    private val insForeignPrepRe = Regex("""(?<![\p{L}])(?:в|во|на|о|об|обо|при|по|от|до|из|у|для|без|через|про|к|ко)\s+$""", RegexOption.IGNORE_CASE)
    private val irregularGenSg = mapOf("людьми" to "человека", "людям" to "человека", "людях" to "человека",
        "детьми" to "ребёнка", "детям" to "ребёнка", "детях" to "ребёнка")
    private val pluralObliqueRe = Regex("""^(\p{L}+?)(?:ами|ями|ам|ям|ах|ях)$""")
    /** «рублями» → «рубля», «годам» → «года», «тоннах» → «тонны»: форма из таблицы морфологии; нет её — слово как было. */
    private fun genSgNoun(w: String): String {
        // «людьми», «детьми» — другая основа (ревью ru-fixes-2 M-3)
        irregularGenSg[w.lowercase()]?.let { return it }
        val m = morph ?: return w
        val stem = pluralObliqueRe.find(w.lowercase())?.groupValues?.get(1) ?: return w
        // окончание по роду слова в тексте: женский — «-ы/-и» («силами» — «силы», не «сила»), иначе «-а/-я» («подборами» —
        // «подбора», не «подборы»)
        val fem = Morph.genders(m.tags(w.lowercase())) == listOf(Gender.F)
        for (e in if (fem) listOf("ы", "и") else listOf("а", "я")) {
            val t = m.tags(stem + e)
            if (Morph.isNoun(t) && Case.GEN in Morph.nounCases(t, plural = false))
                return if (w[0].isUpperCase()) (stem + e).replaceFirstChar(Char::uppercaseChar) else stem + e
        }
        return w
    }
    // «около 1 млн человек» — «около одного миллиона» (было «одного миллионов»: число склонял предлог, а «млн» потом scales()
    // читал родительным множественного), «к 2 млн» — «к двум миллионам»: разряд склоняется вместе с числом.
    private fun scaleCaseRe(alt: String) = Regex(
        """(?<![\p{L}-])$alt(?![\p{L}])(?:\s+$amplifierRe(?![\p{L}]))*\s+(\d{1,3})\s*(тыс|млн|млрд|трлн)(?:\.(?=\p{L})|\.?(?![\p{L}\d]))""",
        RegexOption.IGNORE_CASE)
    private val scaleCaseRes by lazy { listOf(scaleCaseRe(genTriggerAlt) to Case.GEN, scaleCaseRe(datTriggerAlt) to Case.DAT,
        scaleCaseRe(insTriggerAlt) to Case.INS, scaleCaseRe(preTriggerAlt) to Case.PRE) }
    private val scaleMult = mapOf("тыс" to 1_000L, "млн" to 1_000_000L, "млрд" to 1_000_000_000L, "трлн" to 1_000_000_000_000L)
    private val datTriggerRe = triggerRe(datTriggerAlt)
    private val insTriggerRe = triggerRe(insTriggerAlt)
    private val preTriggerRe = triggerRe(preTriggerAlt)

    // По окончанию соседнего слова, без триггера из списка выше (п.2). «-ами/-ями» и неправильное
    // «-ьми» («детьми», «людьми», «лошадьми») — только творительный мн. ч., предлог не обязателен
    // («2 миллионами»). «-ом/-ем/-ой/-ей/-ью» — неоднозначны с родительным мн. ч. («читателей»),
    // поэтому только после «с/со».
    private val insEndingPluralRe = Regex(
        // не дробная часть десятичной: «с 2.50 рублями» было «с два.пятьюдесятью рублями»
        """(?<![\p{L}\d]|\d[.,])($numChain)$numTailExclude\s+\p{L}+(?:ами|ями|ьми)(?![а-яё])""",
        RegexOption.IGNORE_CASE
    )
    private val insEndingWithSRe = Regex(
        """(?<![\p{L}])(?:с|со)(?![\p{L}])\s+(\d+)$numTailExclude\s+\p{L}+(?:ом|ем|ой|ей|ью)(?![а-яё])""",
        RegexOption.IGNORE_CASE
    )
    private val preEndingRe = Regex(
        """(?<![\p{L}])(?:в|во|на|при|о|об)(?![\p{L}])\s+($numChain)$numTailExclude\s+\p{L}+(?:ах|ях)(?![а-яё])""",
        RegexOption.IGNORE_CASE
    )
    // Дательный мн. ч. по окончанию «-ам/-ям» («4 сотрудникам», «5 гостям»). Стоп-слова — частые
    // родительные мн. ч. на «-ам» («5 программ», «2 дам»).
    private val datEndingPluralRe = Regex(
        // не дробная часть десятичной: «14.65 спасателям» было «четырнадцать.шестидесяти пяти»
        """(?<![\p{L}\d]|\d[.,])($numChain)$numTailExclude\s+(\p{L}{3,}(?:ам|ям))(?![а-яё])""",
        RegexOption.IGNORE_CASE
    )
    private val datEndingStop = setOf("дам", "мам", "рам", "драм", "программ", "реклам", "пижам", "панорам", "диаграмм",
        "телеграмм", "гамм", "сумм", "грамм", "килограмм", "миллиграмм", "ям", "хам")
    // «по 1 рублю», «по 21 рублю» — дательный мужского («одному»), не «одну» из accFemEndingRe ниже.
    private val poDatRe = Regex(
        """(?<![\p{L}])по\s+(\d+)$numTailExclude\s+\p{L}+[ую](?![а-яё])""",
        RegexOption.IGNORE_CASE
    )
    private val percentCases = arrayOf(arrayOf("процент", "процентов"), arrayOf("процента", "процентов"), arrayOf("проценту", "процентам"),
        arrayOf("процент", "процентов"), arrayOf("процентом", "процентами"), arrayOf("проценте", "процентах"))
    private val andPercentRe = Regex("""(?<![\p{L}])(при|о|об|до|от|около|к|ко)(\s[^,.;!?]{1,40}\s)и\s+(-|минус\s+)?(\d+)\s?%""", RegexOption.IGNORE_CASE)
    private val doAheadRe = Regex("""^[^.;!?]{0,40}?\sдо\s""")
    private val percentCaseRe by lazy { Regex(
        """(?<![\p{L}-])($genTriggerAlt|с|со|к|ко|по|о|об|при)(?![\p{L}])(?:\s+$amplifierRe(?![\p{L}]))*\s+((?:плюс|минус)\s+|-)?(\d+)(?:[.,](\d+))?\s?%""",
        RegexOption.IGNORE_CASE) }
    // «при 5 %» → «при пяти процентах»: процент читается в конце numberRe, где падежа уже нет.
    private val prePercentRe = Regex("""(?<![\p{L}])(при)\s+(-?\d+) ?%(?!\s*процент)""", RegexOption.IGNORE_CASE)
    // «1у минуту», «21-у тысячу» — просторечный суффикс винительного женского: «одну».
    private val accSuffixURe = Regex("""(?<![\p{L}\d])(\d+)-?у(?![а-яё\d])""")
    // Круглые тысячи на 1 («1000», «21 000») после предлога/глагола с винительным — «одну тысячу»:
    // у остальных чисел винительный совпадает с именительным, а «одна тысяча долларов» после «за» режет ухо.
    private val accThousandRe = Regex(
        """(?<![\p{L}])(по|за|на|в|во|через|про|спустя|сто(?:ит|ил|ила|ило|ят|или)|вес(?:ит|ил|ила|ят|или)|""" +
        """(?:заплат|потрат|получ|заработ|выиграл|проиграл|отда|верну|привез|принес|наш)[а-яё]*|име(?:ть|ет|ем|ю|ешь|ете|ют|л|ла|ли))""" +
        """(?![\p{L}])\s+(\d+)$numTailExclude(?=(?:\s+(\p{L}+))?(?:\s+(\p{L}+))?)""",
        RegexOption.IGNORE_CASE
    )
    /** Существительное за числом (прилагательное перед ним пропускаем) стоит в косвенном падеже — не им./вин./род.:
     * «в 1000 новых чатах» — предложный, число склоняет morphNoun («в одной тысяче»), а не винительный «в одну тысячу». */
    private fun obliqueNounAfter(m: MatchResult, first: Int): Boolean {
        val morph = morph ?: return false
        val t = listOf(m.groupValues[first], m.groupValues[first + 1]).filter { it.isNotEmpty() }
            .map { morph.tags(it.lowercase()) }.firstOrNull { Morph.isNoun(it) && !Morph.isAdjective(it) } ?: return false
        val cases = Morph.nounCases(t, plural = false) + Morph.nounCases(t, plural = true)
        return cases.isNotEmpty() && cases.none { it == Case.NOM || it == Case.ACC || it == Case.GEN }
    }
    // «по 1000 рублей» — дательный: «по одной тысяче».
    // Число на 1 (не 11) + слово на -у/-ю → винительный женского рода («1 книгу» → «одну книгу»).
    // Не дробная часть десятичной (здесь и в nomFemTwoEndingRe): «1,1 тонну» было «один,одну», «1,2 тысячи» — «один,две».
    private val accFemEndingRe = Regex(
        """(?<![\p{L}\d]|\d[.,])(\d+)$numTailExclude\s+(\p{L}+[ую])(?![а-яё])""",
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
        "снова", "вроде", "давно", "немного", "менее", "более", "чаще", "реже", "легче", "тяжелее", "быстрее", "медленнее",
        "умножить", "разделить", "равно")
    private val nomFemTwoEndingRe = Regex(
        """(?<![\p{L}\d]|\d[.,])(\d+)$numTailExclude\s+(\p{L}+[ыи])(?![а-яё])""",
        RegexOption.IGNORE_CASE
    )

    // 13. По таблице морфологии (Normalizer.morph; null — только правила по окончаниям ниже). Слово сразу
    // после числа — существительное с известным родом и падежом: «1 ночь» → «одна», «1 такси» → «одно»,
    // «1 конь» → «один», «2 двери» → «две», «1 книгу» → «одну», «1 другу» → «одному», «5 путями» → «пятью»,
    // «в 1 доме» → «одном». Родительный и именительный не трогаем — это обычная конструкция «пять минут»,
    // «два стола». Число из таблицы решено окончательно — до правил по окончаниям оно уже словами;
    // неизвестное слово или общий род («сирота») оставляем им.
    // Слово следом (группа 3, не поглощается) — для прилагательного-омонима, см. ниже.
    // слово через дефис («киловатт-часа») — не то существительное, что в таблице: не берём
    private val morphNounRe = Regex("""(?<![\p{L}\d,./–-])($numChain)$numTailExclude\s+(\p{L}{2,})(?![а-яё-])(?=(?:\s+(\p{L}{2,}))?)""", RegexOption.IGNORE_CASE)
    private fun morphNoun(s: String, morph: Morph) = morphNounRe.replace(s) { m ->
        // номер, а не количество: «Пункт 22 правил», «статьи 1, 2 кодекса», «на странице 91 своего сочинения» — число как есть
        if (numLabelBehindRe.containsMatchIn(s.substring(0, m.range.first))) return@replace m.value
        val word = m.groupValues[2].lowercase()
        val t = morph.tags(word)
        val two = { n: Long -> if (n % 10 == 2L && n % 100 != 12L) Declension.cardinal(n, Case.NOM, feminine = true) else null }
        // «2 новых сообщения», «5 новых сообщений», «2 учёных»: форма на -ых/-их — прилагательное (в таблице
        // «новых» ещё и субстантив), после числа это род. мн., а не предложный «о двух новых» — было «двух новых
        // сообщения» (экранный чтец в Telegram). Падеж — по существительному следом, как в morphAdj:
        // «в 2 новых домах» → «двух»; им./вин./род. или слова нет — число как есть.
        if (Morph.isAdjective(t) && Case.GEN in Morph.adjCases(t, null, plural = true)) {
            val tn = morph.tags(m.groupValues[3].lowercase())
            if (!Morph.isNoun(tn) || Morph.isAdjective(tn)) return@replace m.value
            val c = (Morph.adjCases(t, null, plural = true) intersect Morph.nounCases(tn, plural = true)).singleOrNull()
            // им./вин. при существительном женского рода — «две»: «2 непрочитанных заявки», «22 новых записи»
            if (c == null || c == Case.NOM || c == Case.ACC || c == Case.GEN)
                return@replace if (Morph.genders(tn) == listOf(Gender.F)) m.replaceChain(1, two) else m.value
            return@replace m.replaceChain(1) { n -> Declension.cardinal(n, c, Morph.gender(tn) == Gender.F) }
        }
        // Прилагательное только в косвенном падеже мн. ч. («новыми», «новым») — тот же падеж у числа, если его
        // подтверждает существительное следом: «с 21 новыми сообщениями» — «с двадцатью одним», было «с двадцать один».
        val adjPl = if (Morph.isAdjective(t)) Morph.adjCases(t, null, plural = true) else emptySet()
        if (adjPl.isNotEmpty() && adjPl.none { it == Case.NOM || it == Case.ACC || it == Case.GEN }) {
            val tn = morph.tags(m.groupValues[3].lowercase())
            val c = if (Morph.isNoun(tn) && !Morph.isAdjective(tn)) (adjPl intersect Morph.nounCases(tn, plural = true)).singleOrNull() else null
            if (c != null) return@replace m.replaceChain(1) { n -> Declension.cardinal(n, c, Morph.gender(tn) == Gender.F) }
        }
        // «2 международные единицы», «2 контрольные точки» — прилагательное им. мн., род по существительному за ним
        if (adjPl.isNotEmpty() && (Case.NOM in adjPl || Case.ACC in adjPl) && !Morph.isNoun(t)) {
            val tn = morph.tags(m.groupValues[3].lowercase())
            if (Morph.isNoun(tn) && !Morph.isAdjective(tn) && Morph.genders(tn) == listOf(Gender.F)) return@replace m.replaceChain(1, two)
            return@replace m.value
        }
        // стоп-слова — расхождения таблицы с узусом («евро» в AOT среднего рода) и «1 года»/«1 раза»
        if (!Morph.isNoun(t) || word in nomOneStop) return@replace m.value
        // цепочка («2-6 футов», «3 и 5 участников») — падеж по последнему числу: им. у него — все как есть
        val nums = chainNumTokRe.findAll(m.groupValues[1]).map { it.value.toLongOrNull() }.toList()
        if (nums.any { it == null }) return@replace m.value
        if (nums.size > 1 && numeralByNoun(nums.last()!!, t).let { it == null || it == cardinal(nums.last()!!) }) return@replace m.value
        m.replaceChain(1) { n -> numeralByNoun(n, t) }
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
            // при 2–4 род. мн. существительного («2-3 пациентов», «3 друзей») — родительный или одушевлённый винительный:
            // «двух-трёх»; прилагательное-омоним («3 рабочих дня») сюда не доходит — оно isAdjective
            Case.GEN -> if (!few) out += cardinal(n) else if (!Morph.isAdjective(t)) out += Declension.cardinal(n, Case.GEN, fem)
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
        // род — по существительному следом (прилагательное перед ним пропускаем): «о 1 заявке» — «об одной», не «одном»
        val end = m.groups[1]!!.range.last + 1
        val femNoun = nextWordsRe.find(text.substring(end, minOf(text.length, end + 100)))?.groupValues?.drop(1)?.filter { it.isNotEmpty() }
            ?.firstNotNullOfOrNull { w -> morph?.tags(w.lowercase())?.takeIf { Morph.isNoun(it) && !Morph.isAdjective(it) } }
            ?.let { Morph.gender(it) == Gender.F } == true
        val g = m.groupValues[1]
        if (g.startsWith("-") && g.drop(1).all { it.isDigit() }) {
            val n = g.toLongOrNull() ?: return@replace m.value
            val fem = femNoun && -n % 10 == 1L && -n % 100 != 11L
            return@replace m.withGroupReplaced(1 to "минус " + Declension.cardinal(-n, case, fem))
        }
        m.replaceChain(1) { n -> Declension.cardinal(n, case, femNoun && (n % 10 == 1L || n % 10 == 2L) && n % 100 !in 11..12) }
    }
    // не \G с find(text, start): на ICU \G не привязан к start
    private val nextWordsRe = Regex("""^\s+(\p{L}+)(?:\s+(\p{L}+))?""")

    // «детей 6-12 лет» — возраст в родительном: «шести-двенадцати лет»
    private val ageRangeRe = Regex("""(?<=(?<![\p{L}])(?:детей|подростков|школьников|людей|граждан|пациентов|мужчин|женщин|взрослых|учеников|ребят)\s)(\d+)(\s*[-–—]\s*)(\d+)(?=\s+лет(?![\p{L}]))""", RegexOption.IGNORE_CASE)
    private fun cases(text: String): String {
        var s = ageRangeRe.replace(text) { m ->
            val a = m.groupValues[1].toLongOrNull() ?: return@replace m.value; val b = m.groupValues[3].toLongOrNull() ?: return@replace m.value
            Declension.cardinal(a, Case.GEN) + m.groupValues[2] + Declension.cardinal(b, Case.GEN)
        }
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
        for ((re, case) in scaleCaseRes) s = re.replace(s) { m ->
            val n = m.groupValues[1].toLong() * scaleMult.getValue(m.groupValues[2].lowercase())
            // «тыс.рублей» вплотную — точку меняем на пробел
            val rest = s.substring(m.range.last + 1)
            val glued = m.value.endsWith(".") && rest.firstOrNull()?.isLetter() == true
            // точка предложения — как у одиночного «тыс.» (dotAfter): заглавные смотрим у самого сокращения, не у предлога
            val dot = m.value.endsWith(".") && sentenceEndRe.containsMatchIn(rest) &&
                !(m.groupValues[2].any { it.isUpperCase() } && capsWordAheadRe.containsMatchIn(rest))
            m.value.substring(0, m.groups[1]!!.range.first - m.range.first) + Declension.cardinal(n, case) + if (glued) " " else if (dot) "." else ""
        }
        // Окончание «-ами/-ьми» сильнее предлога: «больше чем 2 людьми» — творительный.
        s = applyCase(s, insEndingPluralRe, Case.INS)
        s = applyCase(s, genTriggerRe, Case.GEN)
        s = fracFromRe.replace(s) { m ->
            val (minus, intPart, frac) = m.destructured
            val words = genFraction(minus, intPart, frac) ?: return@replace m.value
            m.value.substring(0, m.groups[1]!!.range.first - m.range.first) + words
        }
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
            if (m.groupValues[2].lowercase() in datEndingStop) return@replace m.value
            m.replaceChain(1) { n -> Declension.cardinal(n, Case.DAT) }
        }
        s = poDatRe.replace(s) { m ->
            val n = m.groupValues[1].toLongOrNull() ?: return@replace m.value
            if (n % 10 != 1L || n % 100 == 11L) return@replace m.value
            m.withGroupReplaced(1 to Declension.cardinal(n, Case.DAT))
        }
        // проценты после предлога: «до 16%» — «до шестнадцати процентов», «не более 10% суммы», «с +2,5% до −1,75%» —
        // «с плюс двух целых пяти десятых процента до минус одной целой…»; «в размере 1,5%»
        s = percentCaseRe.replace(s) { m ->
            val (trig, sign, intPart, frac) = m.destructured
            val t = trig.lowercase()
            // целое после «при» читает prePercentRe ниже, как было; здесь — только дробь
            if (t == "при" && frac.isEmpty()) return@replace m.value
            val case = when {
                t == "с" || t == "со" -> if (doAheadRe.containsMatchIn(s.substring(m.range.last + 1))) Case.GEN else return@replace m.value
                t in setOf("к", "ко", "по") -> Case.DAT
                t in setOf("при", "о", "об") -> Case.PRE
                else -> Case.GEN
            }
            val minus = sign.trim().let { it == "-" || it.equals("минус", true) }
            val plus = sign.trim().equals("плюс", true)
            val head = m.value.substring(0, (m.groups[2] ?: m.groups[3])!!.range.first - m.range.first)
            // дробь после «по» — «по две целых восемь сотых процента» (распределительное, не дательный)
            val words = if (frac.isNotEmpty()) fractionCase("", intPart, frac, case)?.takeIf { t != "по" }
                ?.let { "$it процента" } ?: return@replace m.value
                else intPart.toLongOrNull()?.let { n -> Declension.cardinal(n, case) + " " + percentCases[case.ordinal][if (n % 10 == 1L && n % 100 != 11L) 0 else 1] }
                ?: return@replace m.value
            head + (if (minus) "минус " else if (plus) "плюс " else "") + words
        }
        // «при −20 °C и −1 %» — падеж предлога и через «и»
        s = andPercentRe.replace(s) { m ->
            val case = when (m.groupValues[1].lowercase()) { "при", "о", "об" -> Case.PRE; "к", "ко" -> Case.DAT; else -> Case.GEN }
            val n = m.groupValues[4].toLongOrNull() ?: return@replace m.value
            val one = n % 10 == 1L && n % 100 != 11L
            m.groupValues[1] + m.groupValues[2] + "и " + (if (m.groupValues[3].isNotEmpty()) "минус " else "") + Declension.cardinal(n, case) + " " +
                percentCases[case.ordinal][if (one) 0 else 1]
        }
        s = prePercentRe.replace(s) { m ->
            val n = m.groupValues[2].toLongOrNull() ?: return@replace m.value
            val one = kotlin.math.abs(n) % 10 == 1L && kotlin.math.abs(n) % 100 != 11L
            "${m.groupValues[1]} ${if (n < 0) "минус " else ""}${Declension.cardinal(kotlin.math.abs(n), Case.PRE)} ${if (one) "проценте" else "процентах"}"
        }
        // дробь в косвенном падеже — после родительного (genFracRe: «до 21.00 местами» — «до двадцати одной…») и процентов
        // (andPercentRe смотрит на запятую дроби: «при 2,5 детей на семью – и 94%» — «и девяносто четыре процента»)
        for ((re, case) in fracCaseRes) s = fracCase(s, re, case)
        s = accSuffixURe.replace(s) { m ->
            val n = m.groupValues[1].toLongOrNull() ?: return@replace m.value
            if (n % 10 != 1L || n % 100 == 11L) m.value else Declension.cardinal(n, Case.ACC, feminine = true)
        }
        s = accThousandRe.replace(s) { m ->
            val n = m.groupValues[2].toLongOrNull() ?: return@replace m.value
            val k = n / 1000
            // и не круглые: «за 1500 лет» — «одну тысячу пятьсот»
            if (n < 1000 || k % 10 != 1L || k % 100 == 11L || obliqueNounAfter(m, 3)) return@replace m.value
            val case = if (m.groupValues[1].equals("по", true)) Case.DAT else Case.ACC
            // «тысячу» женская сама (Declension), род остатка — по существительному следом: «в 1341 раз» — «сорок один раз»,
            // «за 1002 рубля» — «два», «за 1001 ночь» — «одну» (жалоба 28.10.2026)
            val g = listOf(m.groupValues[3], m.groupValues[4]).firstNotNullOfOrNull { morphGender(it.lowercase()) }
            m.withGroupReplaced(2 to Declension.cardinal(n, case, feminine = g != Gender.M && g != Gender.N))
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
            // «1 артиллерийского полка» — прилагательное в родительном, не средний род («на странице 91 своего» — номер)
            if (word.endsWith("ого") || word.endsWith("его")) return@replace if (numLabelBehindRe.containsMatchIn(s.substring(0, m.range.first))) m.value
                else m.withGroupReplaced(1 to Declension.cardinal(n, Case.GEN))
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

    // Гравис (U+0300) — тоже ударение: в словарях и на форумах ставят и его («рукѝ»).
    private val combiningAcuteRe = Regex("""([аеёиоуыэюяАЕЁИОУЫЭЮЯ])[\u0301\u0300]""")
    // Ударение готовой буквой со знаком: латинские «á», «ó», «é», «ý» (их проще набрать) и кириллические «ѐ»,
    // «ѝ» внутри русского слова — «Глáза», «Слóва», «Рукѝ». Фильтр алфавита выкидывал такую букву целиком:
    // «глза», «слва». Латинские берём только рядом с кириллицей — «café» остаётся французским.
    private val precomposedStressRe = Regex("""(?<=[а-яёА-ЯЁ])[áéóýàèòÁÉÓÝÀÈÒ]|[áéóýàèòÁÉÓÝÀÈÒ](?=[а-яёА-ЯЁ])|[ѐѝЀЍ]""")
    private val precomposedBase = mapOf('á' to 'а', 'é' to 'е', 'ó' to 'о', 'ý' to 'у', 'à' to 'а', 'è' to 'е', 'ò' to 'о',
        'Á' to 'А', 'É' to 'Е', 'Ó' to 'О', 'Ý' to 'У', 'À' to 'А', 'È' to 'Е', 'Ò' to 'О', 'ѐ' to 'е', 'ѝ' to 'и', 'Ѐ' to 'Е', 'Ѝ' to 'И')
    private val zeroWidthRe = Regex("""[\u00AD\u200B-\u200D\u2060\uFEFF]""")
    // Названия, чтение которых правилами не вывести: «4PDA» — «четыре пи ди эй» (правила дали бы «четыре пда»:
    // «PDA» с гласной читается словом). И в тексте, и в адресе («4pda.to»).
    private val namedReadings = listOf(Regex("""(?<![\p{L}\d])4pda(?![\p{L}\d])""", RegexOption.IGNORE_CASE) to "четыре пи ди эй",
        // сокращения, набранные строчными (заглавными их читает Abbrev по буквам): без гласных модель их не выговорит
        Regex("""(?<![\p{L}\d])ттс(?![\p{L}\d])""") to "тэ тэ +эс", Regex("""(?<![\p{L}\d])смс(?![\p{L}\d])""") to "эс эм +эс",
        Regex("""(?<![\p{L}\d])пк(?![\p{L}\d])""") to "пэ к+а")
    private fun namedReadings(text: String) = namedReadings.fold(text) { t, (re, v) -> re.replace(t, v) }
    // Смайлы словами, как эмодзи (правило emoji), глаголом, как у имён CLDR: «:D» — «смеётся» (было «двоеточие д»).
    // Только отдельным словом: «в 3:)», «http://» не трогаются. Повтор внутри смайла («:)))») — один раз.
    private val asciiSmileys = listOf(
        """:-?\)+|=\)+|\^_*\^""" to "улыбается",
        """:-?D+|[xXхХ]D+""" to "смеётся",
        """;-?\)+""" to "подмигивает",
        """:-?\(+|=\(+""" to "грустит",
        """:['’]-?\(+|[TТ]_[TТ]|;-?\(+""" to "плачет",
        """:-?[pPрР]""" to "показывает язык",
        """:-?[oOоО0]|[oOоО]_[oOоО]""" to "удивляется",
        """:-?\*""" to "целует",
        """-_-""" to "недоволен",
        // «n <3», «x < 3» — сравнение, не сердце
        """(?<![A-Za-z\d]\s)<3""" to "сердце",
    )
    private val asciiSmileyRe = Regex(asciiSmileys.joinToString("|", """(?<![\p{L}\d:;=<^_-])(?:""", """)(?![\p{L}\d_^])""") { "(${it.first})" })
    private fun asciiSmiley(m: MatchResult) = asciiSmileys[(1..asciiSmileys.size).first { m.groups[it] != null } - 1].second

    // Смайлы-коды форумов и мессенджеров: картинка на 4PDA приходит от TalkBack подписью «:blush:», «:censored:».
    // Известные — словами, остальные — самим словом без двоеточий («:thank_you:» — «thank you», дальше транслит).
    private val shortcodeRe = Regex("""(?<![\p{L}\d:/]):([a-z][a-z0-9_+-]{1,30}):(?![\p{L}\d:])""")
    private val shortcodes = mapOf("smile" to "улыбается", "smiley" to "улыбается", "happy" to "радуется", "biggrin" to "смеётся",
        "lol" to "смеётся", "laugh" to "смеётся", "rofl" to "катается от смеха", "joy" to "смеётся до слёз", "wink" to "подмигивает",
        "sad" to "грустит", "cry" to "плачет", "angry" to "злится", "mad" to "злится", "blush" to "краснеет", "girl_blush" to "краснеет",
        "censored" to "цензура", "facepalm" to "рука-лицо", "shok" to "в шоке", "shock" to "в шоке", "wacko" to "в замешательстве",
        "huh" to "недоумевает", "unsure" to "сомневается", "blink" to "моргает", "beee" to "показывает язык", "tongue" to "показывает язык",
        "clapping" to "аплодирует", "clap" to "аплодирует", "good" to "класс", "thumbsup" to "большой палец вверх", "+1" to "большой палец вверх",
        "thumbsdown" to "большой палец вниз", "-1" to "большой палец вниз", "thank_you" to "спасибо", "yes" to "да", "yes2" to "да",
        "no" to "нет", "ok" to "окей", "heart" to "сердце", "fire" to "огонь", "cool" to "круто", "kiss" to "целует", "hi" to "машет рукой",
        "bye" to "машет рукой", "sleep" to "спит", "sick" to "болеет", "scare" to "испуган", "rolleyes" to "закатывает глаза", "dry" to "недоволен")
    /** Смайлы словами (правило emoji): коды форумов, ASCII, скобки; один и тот же смайл подряд («....)).....))», «:) :)») —
     * один раз, как повтор эмодзи. [tr] — имя на языке слоя (у белорусского «усміхаецца»), [repeat] — повтор этих имён. */
    internal fun smileys(text: String, tr: (String) -> String = { it }, repeat: Regex = smileyRepeatRe): String {
        var s = shortcodeRe.replace(text) { m ->
            ", " + (shortcodes[m.groupValues[1]]?.let(tr) ?: m.groupValues[1].replace('_', ' ').replace('-', ' ')) + ", "
        }
        s = asciiSmileyRe.replace(s) { m -> ", " + tr(asciiSmiley(m)) + ", " }
        return repeat.replace(parenSmileys(s, tr("улыбается"), tr("грустит")), "$1")
    }

    // Скобка-смайл, как пишут в рунете: «спасибо))», «ладно)))))» — «улыбается» (один раз), «жаль((» — «грустит».
    // Смайл — две и больше скобки без пары: «(см. рис.)», «(спасибо :))» — обычные скобки, пауза, как было. Одна «)» без
    // пары — не смайл: текст приходит по предложениям, и скобка из книги («(Он ушёл. Она осталась)») закрывается уже
    // в следующем; так же «1)», «а)» и обрывки подписей, которые TalkBack шлёт по частям («%)»).
    private val smileyRepeatRe = Regex("""(?<![а-яё])(улыбается|смеётся|грустит|плачет|подмигивает)(?:[\s.,…!?]+\1(?![а-яё]))+""")
    private fun parenSmileys(text: String, smileWord: String, sadWord: String): String {
        if (!text.contains("))") && !text.contains("((")) return text
        val n = text.length
        // без пары: «)» — слева направо, «(» — справа налево
        val lone = BooleanArray(n)
        var depth = 0
        for (i in 0 until n) when (text[i]) { '(' -> depth++; ')' -> if (depth > 0) depth-- else lone[i] = true }
        depth = 0
        for (i in n - 1 downTo 0) when (text[i]) { ')' -> if (!lone[i]) depth++; '(' -> if (depth > 0) depth-- else lone[i] = true }
        if (lone.none { it }) return text
        val sb = StringBuilder(n + 16)
        var i = 0
        while (i < n) {
            val c = text[i]
            if (c != '(' && c != ')') { sb.append(c); i++; continue }
            var j = i
            while (j < n && text[j] == c) j++
            val single = j - i
            val loneCount = (i until j).count { lone[it] }
            val kept = c.toString().repeat(single - loneCount)
            val before = text.substring(0, i)
            val after = text.substring(j)
            val smile = loneCount >= 2 && before.isNotBlank() && when (c) {
                ')' -> true
                // «((» — в конце слова или фразы, а не перед текстом («((a) b)» — скобки)
                else -> after.isBlank() || !after.first().isLetterOrDigit()
            }
            if (smile) sb.append(if (c == ')') kept else "").append(", ").append(if (c == ')') smileWord else sadWord).append(", ").append(if (c == '(') kept else "")
            else sb.append(text, i, j)
            i = j
        }
        return sb.toString()
    }
    private val minusBetweenRe = Regex("""(?<=\d)\s*−\s*(?=\d)""")
    private val tildeRe = Regex("""(?<![\p{L}\d])~\s*(?=\d)""")
    // «20k», «20к», «4.5к», «10К» — тысячи. Латинская заглавная «K» — нет: «4K» — разрешение.
    private val kThousandRe = Regex("""(?<=\d)[kкК](?![\p{L}\d])""")
    // «5 - й этаж», «20 - этажный» — суффикс/основа через дефис в пробелах (корпус ru-normalizr).
    private val spacedOrdSuffixRe = Regex("""(\d)\s+[-–]\s+(?=(?:$ordSuffixAlt)(?![а-яё\d]))""")

    // Ссылка читается по частям, как у ru-normalizr: буквы словами (транслит ниже), цифры по одной,
    // разделители названиями; схема «https://» и хвостовая пунктуация не читаются. Правила
    // drop_links / drop_emails выкидывают ссылку или почту целиком; у чтеца с sr_link_word (Rules.LINK_WORD)
    // ссылка — словом «ссылка» и сайтом без «www.» («ссылка, youtube точка com»), это важнее drop_links.
    // В книгах так же читает link_domain (включено по умолчанию), но там включённый drop_links важнее. Почту не трогает.
    // Почта — тем же способом: «mail@example.com» → «мейл собака ексампл точка ком».
    // Адрес без схемы и «www.» — сайт с известной зоной и путём после неё («habr.com/ru/articles/1», «t.me/c/1»):
    // без пути «vk.com» остаётся словом, а зона из списка не даёт принять за адрес «Node.js/Express».
    private const val urlTail = """[^\s<>«»"']*[^\s<>«»"'.,;:!?)\]}/]"""
    private const val urlZones = "com|ru|org|net|io|me|info|su|ua|by|kz|uz|de|uk|co|tv|dev|app|gov|edu|biz|pro|xyz|ai|cc|to|ly|gl|gg|fm|be|us|eu|site|online|store|blog|news"
    // Путь к файлу книги («/home/…/Telegram Desktop/07 E TNOS. CHast pervaya.fb2») — AlReaderX отдаёт его в конце книги
    // (жалоба 08.10.2026). В пути бывают пробелы и «. », поэтому до расширения книжного файла, а не до пробела.
    private const val filePath = """(?<![\w.:/])/(?:home|storage|sdcard|mnt|data|media|Users|tmp)/[^\n<>«»"|]{1,250}?\.(?:fb2|epub|txt|pdf|docx?|rtf|mobi|azw3?|djvu|html?|odt|zip)(?:\.zip)?(?![\p{L}\d])"""
    // «https://www .author .today/work/1» — пробелы перед точками имени сайта в книге (жалоба 08.10.2026): адрес не рвём на «https://author»
    val urlRe = Regex("""$filePath|(?:https?://|www ?\.)(?:(?=[\w-]+(?: ?\.[\w-]+)+[/?#])(?:[\w-]+ ?\.)+)?$urlTail|(?<![\w@./-])(?:[a-z0-9][a-z0-9-]*\.)+(?:$urlZones)/$urlTail|[\w.+-]+@[\w-]+(?:\.[\w-]+)+""", RegexOption.IGNORE_CASE)
    private val urlSeparators = mapOf(':' to "двоеточие", '/' to "слэш", '.' to "точка", '?' to "вопрос", '&' to "амперсанд",
        '=' to "равно", '-' to "дефис", '_' to "нижнее подчёркивание", '#' to "решётка", '%' to "процент", '+' to "плюс",
        '@' to "собака", '~' to "тильда")
    private val urlSingleLetterRe = Regex("""(?<![\p{L}])[a-z](?![\p{L}])""")
    private fun spellUrl(url: String): String {
        val sb = StringBuilder()
        for (c in namedReadings(url.lowercase().removePrefix("https://").removePrefix("http://"))) when {
            c.isDigit() -> sb.append(' ').append(cardinal((c - '0').toLong())).append(' ')
            c in urlSeparators -> sb.append(' ').append(urlSeparators[c]).append(' ')
            else -> sb.append(c)
        }
        // одиночная буква («?x=1») — названием, а не транслитом «кс»
        return urlSingleLetterRe.replace(sb, { Abbrev.latLetterNames.getValue(it.value[0].uppercaseChar()) })
            .replace(Regex(" {2,}"), " ").trim()
    }
    private val urlHostRe = Regex("""^(?:https?://)?(?:www ?\.)?([^/?#:]+)""", RegexOption.IGNORE_CASE)
    private fun urlHost(url: String) = urlHostRe.find(url)?.groupValues?.get(1) ?: url
    // Без read_links адрес остаётся как есть: на время обработки прячем его за плейсхолдером
    // без цифр и букв, чтобы «5Mb» внутри не стало числом, потом возвращаем.
    fun numbers(text: String, rules: Rules = Rules()): String {
        if (!hasUrl(text)) return aboutOne(numbersInner(text, rules))
        val kept = mutableListOf<String>()
        val masked = urlRe.replace(text) {
            when {
                // у пути нет сайта для «ссылка, …»: где ссылка сократилась бы до сайта или выкинута — выкидываем
                it.value.startsWith('/') -> when {
                    rules.on(Rules.LINK_WORD) || rules.on("link_domain") || rules.on("drop_links") -> ""
                    rules.on("read_links") -> spellUrl(it.value)
                    else -> { kept += it.value; "\u0001${"\u0002".repeat(kept.size)}\u0001" }
                }
                '@' !in it.value && (rules.on(Rules.LINK_WORD) || rules.on("link_domain") && !rules.on("drop_links")) -> "ссылка, " + spellUrl(urlHost(it.value))
                rules.on(if ('@' in it.value) "drop_emails" else "drop_links") -> ""
                rules.on("read_links") -> spellUrl(it.value)
                else -> { kept += it.value; "\u0001${"\u0002".repeat(kept.size)}\u0001" }
            }
        }
        var out = aboutOne(numbersInner(masked, rules))
        for ((k, url) in kept.withIndex()) out = out.replace("\u0001${"\u0002".repeat(k + 1)}\u0001", url)
        return out
    }

    // «о 1 заявке», «о 11 письмах» — перед числом словами на гласную предлог «об»: «об одной», «об одиннадцати».
    private val aboutOneRe = Regex("""(?<![\p{L}\d])([оО])(?=\s+(?:одн(?:ом|ой|их|ого|ому|им|ими)|одиннадцат[иью]\p{L}*)(?![\p{L}]))""")
    private fun aboutOne(s: String) = aboutOneRe.replace(s, "$1б")

    private val telRe = Regex("""(?<![\p{L}\d])тел\.(?=\s*[:/+(\d])""", RegexOption.IGNORE_CASE)
    private val unaryPlusRe = Regex("""(?<![\p{L}\d)+])(?<![\d)] )\+ ?(?=\d)""")
    private val dotThousandsRe = Regex("""(?<![\d.])(?<!\d,)\d{1,3}\.\d{3}\.\d{3}(?![\d.]|,\d)""")

    // Телефоны: TalkBack читает ими звонки, СМС и контакты. Без этого прохода «+7 012 345 06 78»
    // склеивалось разрядами тысяч в «семь тысяч девятьсот восемьдесят три…» — терялись ведущие
    // нули, номер на слух был просто другим. Номер читается группами через запятую (пауза),
    // каждая группа — числом с ведущими нулями: «плюс семь, ноль двенадцать, триста сорок пять,
    // ноль шесть, семьдесят восемь». Группы берутся как написаны; длинная группа («0678»,
    // «3450678») и номер вплотную («80123450678») режутся по привычной схеме 3-3-2-2.
    // Кандидат — любая цепочка цифр, скобок и разделителей; телефоном он признаётся только по
    // структуре (phoneParts), иначе остаётся как был: «1 200 000», «12.05.2024», «10-20» не трогаются.
    private val phoneRe = Regex("""(?<![\p{L}\d+\-‐-—.,/])(?:\+ ?)?\(?\d[\d()     \-‐-—.]*\d(?![\p{L}\d])""")
    private val phoneDashes = "-‐‑‒–—"
    // Коды стран СНГ → длины национального номера; для +7 и этих кодов длина проверяется строго,
    // иначе «+5 000 000 000» (прирост на пять миллиардов) прочиталось бы номером.
    private val phoneCodes = mapOf("7" to setOf(10), "373" to setOf(8), "374" to setOf(8), "375" to setOf(9),
        "380" to setOf(9), "992" to setOf(9), "993" to setOf(8), "994" to setOf(9), "995" to setOf(9),
        "996" to setOf(9), "998" to setOf(9))
    // Двузначные коды стран (ITU E.164); однозначные — 1 и 7, остальные трёхзначные.
    private val phoneCodes2 = setOf("20", "27", "30", "31", "32", "33", "34", "36", "39", "40", "41", "43", "44", "45",
        "46", "47", "48", "49", "51", "52", "53", "54", "55", "56", "57", "58", "60", "61", "62", "63", "64", "65", "66",
        "81", "82", "84", "86", "90", "91", "92", "93", "94", "95", "98")
    private fun phoneCodeLen(d: String) = when {
        d[0] == '1' || d[0] == '7' -> 1
        d.take(2) in phoneCodes2 -> 2
        else -> 3
    }
    // Группа длиннее трёх цифр — по-русски парами: 4 → 2-2, 5 → 3-2, 6 → 2-2-2, 7 → 3-2-2.
    private fun phoneSplit(g: String): List<String> = when (g.length) {
        in 0..3 -> listOf(g)
        4 -> listOf(g.take(2), g.drop(2))
        5 -> listOf(g.take(3), g.drop(3))
        6 -> listOf(g.take(2), g.substring(2, 4), g.drop(4))
        7 -> listOf(g.take(3), g.substring(3, 5), g.drop(5))
        else -> listOf(g.take(3)) + phoneSplit(g.drop(3))
    }
    // Национальная часть, записанная вплотную, — по схеме страны: 10 цифр (Россия, Казахстан,
    // «0 44…» Украины и «0 29…» Беларуси с префиксом) — 3-3-2-2, 9 — код оператора 2 (Грузия и
    // Киргизия — 3) и 3-2-2, 8 — 2-2-2-2. Записанная группами — группы как есть.
    private fun phoneNational(cc: String?, nat: List<String>): List<String> {
        if (nat.size != 1) return nat.flatMap(::phoneSplit)
        val g = nat[0]
        fun cut(vararg lens: Int): List<String> { var i = 0; return lens.map { l -> g.substring(i, i + l).also { i += l } } }
        return when (g.length) {
            10 -> cut(3, 3, 2, 2)
            9 -> if (cc == "995" || cc == "996") cut(3, 2, 2, 2) else cut(2, 3, 2, 2)
            8 -> cut(2, 2, 2, 2)
            else -> phoneSplit(g)
        }
    }
    private class PhoneGroup(val digits: String, val paren: Boolean, val start: Int, val end: Int)

    /** Группы для чтения (первая — код страны или префикс «8») или null, если это не телефон. */
    private fun phoneParts(plus: Boolean, gs: List<PhoneGroup>, seps: List<String>): List<String>? {
        val d = gs.joinToString("") { it.digits }
        val n = d.length
        val paren = gs.any { it.paren }
        val first = gs[0].digits
        if (plus) {
            if (n !in 8..15) return null
            val cc = if (gs.size > 1 && first.length <= 3 && !gs[0].paren) first else d.take(phoneCodeLen(d))
            val nat = if (cc == first) gs.drop(1).map { it.digits } else listOf(first.drop(cc.length)) + gs.drop(1).map { it.digits }
            val lens = phoneCodes[cc]
            if (lens != null && n - cc.length !in lens) return null
            // «+5 000 000 000» — число с разрядами, не номер
            if (lens == null && !paren && gs.size > 2 && gs.drop(1).all { it.digits.length == 3 }) return null
            return listOf(cc) + phoneNational(cc, nat.filter { it.isNotEmpty() })
        }
        // Префикс «8» (или «7») и 10 цифр: 8 (012) 345-06-78, 8-012-345-0678, 80123450678, 8 029 123-45-67.
        // Вплотную — только если за префиксом не «0» (коды России и Казахстана с нуля не начинаются) или
        // это белорусское «80 29…»: «80000000000» скорее сумма.
        if (n == 11 && d[0] in "78" && (first.length == 1 || paren || (gs.size == 1 && (d[1] != '0' || (d[0] == '8' && d[2] != '0'))))) {
            val rest = listOf(first.drop(1)).filter { it.isNotEmpty() } + gs.drop(1).map { it.digits }
            return listOf(d.take(1)) + phoneNational(null, rest)
        }
        // Код страны СНГ без плюса: 375 29 123-45-67, 380441234567.
        if (first.length == 3 && first in phoneCodes && n - 3 in phoneCodes.getValue(first) && gs.size >= 3)
            return listOf(first) + phoneNational(first, gs.drop(1).map { it.digits })
        if (gs.size == 1 && n == 12 && d.take(3) in phoneCodes && 9 in phoneCodes.getValue(d.take(3)))
            return listOf(d.take(3)) + phoneNational(d.take(3), listOf(d.drop(3)))
        val lens = gs.map { it.digits.length }
        // 10 цифр без префикса: (012) 345-06-78, (4952) 12-34-56, 044 123 45 67, 012 345 06 78.
        if (n == 10 && (paren || (d[0] == '0' && gs.size >= 3) || lens == listOf(3, 3, 2, 2) || lens == listOf(3, 3, 4)))
            return phoneNational(null, gs.map { it.digits })
        // Местный номер через дефисы: 123-45-67, 12-34-56, 1-23-45. Пробелы («123 45 67») не берём —
        // слишком похоже на несколько чисел подряд.
        if (lens in phoneLocalShapes && seps.all { s -> s.isNotEmpty() && s.all { it in phoneDashes } })
            return gs.map { it.digits }
        return null
    }
    private val phoneLocalShapes = setOf(listOf(3, 2, 2), listOf(2, 2, 2), listOf(1, 2, 2))

    internal fun phoneGroup(g: String): String {
        val z = g.takeWhile { it == '0' }.length
        val words = List(z) { "ноль" } + if (z < g.length) listOf(cardinal(g.drop(z).toLong())) else emptyList()
        return words.joinToString(" ")
    }

    /** Кандидат может содержать номер не целиком («в 2024. 8 012 345 06 78»): берём самый ранний и самый длинный отрезок групп, который читается телефоном, остаток разбираем так же. */
    private fun phoneCandidate(v: String, group: (String) -> String): String {
        val gs = mutableListOf<PhoneGroup>()
        var i = 0
        var inParen = false
        while (i < v.length) {
            val c = v[i]
            when {
                c == '(' -> { inParen = true; i++ }
                c == ')' -> { inParen = false; i++ }
                c.isDigit() -> { val st = i; while (i < v.length && v[i].isDigit()) i++; gs += PhoneGroup(v.substring(st, i), inParen, st, i) }
                else -> i++
            }
        }
        // группы через одну точку вплотную — одно число (дата, раздел); номер его не режет: «28.09.26 – 03.10.26» — не «… ноль девять, …»
        fun dotted(k: Int) = k in 1 until gs.size && v.substring(gs[k - 1].end, gs[k].start) == "."
        for (a in gs.indices) for (b in minOf(gs.size, a + 9) downTo a + 1) {
            if (dotted(a) || dotted(b)) continue
            var st = gs[a].start
            if (gs[a].paren) st = v.lastIndexOf('(', st)
            var en = gs[b - 1].end
            if (gs[b - 1].paren) en = v.indexOf(')', en).let { if (it < 0) en else it + 1 }
            val span = v.substring(st, en)
            if (span.count { it == '(' } != span.count { it == ')' } || span.count { it == '(' } > 1) continue
            val plus = a == 0 && v.startsWith("+")
            val sub = gs.subList(a, b)
            val seps = (1 until sub.size).map { k -> v.substring(sub[k - 1].end, sub[k].start).filter { it != '(' && it != ')' } }
            val parts = phoneParts(plus, sub, seps) ?: continue
            val head = if (plus) "" else v.substring(0, st)
            val read = (if (plus) "плюс " else "") + parts.joinToString(", ", transform = group)
            return head + read + phoneCandidate(v.substring(en), group)
        }
        return v
    }
    /** [group] — чтение группы цифр: у белорусского слоя свои числительные ([BelNormalizer.group]). */
    internal fun phones(text: String, group: (String) -> String = ::phoneGroup) = phoneRe.replace(text) { m -> phoneCandidate(m.value, group) }

    // Коды из СМС, пароли, PIN, номера карт — по цифрам, группами через запятую: их набирают на
    // клавиатуре, «четыреста восемьдесят две тысячи…» пришлось бы переводить обратно в цифры.
    // Код узнаётся по слову рядом («код», «пароль», «PIN», «CVC», «code» — до или сразу после
    // числа), карта — по виду: 4 группы по 4 цифры (Visa/Мир/MasterCard) или маска «•• 1234».
    private val codeWord = """(?:пин-?код|код|пароль|одноразовый пароль|pin|cvc2?|cvv2?|otp|code|passcode)"""
    private val codeWordForms = """(?:пин-?код(?:а|у|ом|е|ы|ов)?|код(?:а|у|ом|е|ы|ов)?|парол(?:ь|я|ю|ем|е|и|ей)|pin|cvc2?|cvv2?|otp|code|passcode)"""
    private val codeDigits = """\d{2,4}(?:[ -]\d{2,4}){1,2}|\d{3,8}"""
    private val codeNotAfter = """(?!\d|[.,:]\d|\s*(?:%|₽|\$|€|°|руб|р\.|г\.|год|лет|шт|мин|сек|час|раз|км|м\b|кг))"""
    private val codeAfterWordRe = Regex("""(?<![\p{L}\d])($codeWordForms)(?!\p{L})([^\d.!?\n]{0,40}?)(?<![\p{L}\d,.])($codeDigits)$codeNotAfter""", RegexOption.IGNORE_CASE)
    private val codeBeforeWordRe = Regex("""(?<![\p{L}\d,.+])(\d{4,8})(?!\d|[.,]\d)(?=\s*[-–—:]?\s*(?:это\s+)?(?:ваш[аеи]?\s+)?$codeWord(?!\p{L}))""", RegexOption.IGNORE_CASE)
    private val cardRe = Regex("""(?<![\d.,])(\d{4})([ -])(\d{4})\2(\d{4})\2(\d{4})(?:\2(\d{1,3}))?(?![\d.,]\d|\d)""")
    private val cardMaskRe = Regex("""(?<![\p{L}\d])[*•·xх]{1,12}\s?(\d{4})(?!\d)""")
    private val digitNames = arrayOf("ноль", "один", "два", "три", "четыре", "пять", "шесть", "семь", "восемь", "девять")

    /** «482913» → «четыре восемь два, девять один три»; группы как написаны, сплошное число — по три
     * (4 цифры — две пары, остаток в одну цифру склеивается с соседней группой: 7 → 3-2-2). */
    internal fun spellDigits(v: String, whole: Boolean = false, names: Array<String> = digitNames): String {
        val written = v.split(Regex("""\D+""")).filter { it.isNotEmpty() }
        if (written.isEmpty()) return v
        val groups = if (written.size > 1 || whole) written else when (written.single().length) {
            4 -> written.single().chunked(2)
            else -> written.single().chunked(3).let { c -> if (c.size > 1 && c.last().length == 1) c.dropLast(2) + c[c.size - 2].let { p -> listOf(p.take(2), p.drop(2) + c.last()) } else c }
        }
        return groups.joinToString(", ") { g -> g.map { names[it - '0'] }.joinToString(" ") }
    }

    private fun codes(text: String): String {
        var s = cardRe.replace(text) { m -> spellDigits(listOf(1, 3, 4, 5, 6).mapNotNull { m.groups[it]?.value }.joinToString(" ")) }
        s = cardMaskRe.replace(s) { m -> spellDigits(m.groupValues[1], whole = true) }
        s = codeAfterWordRe.replace(s) { m -> m.groupValues[1] + m.groupValues[2] + spellDigits(m.groupValues[3]) }
        s = codeBeforeWordRe.replace(s) { m -> spellDigits(m.groupValues[1]) }
        return s
    }

    private fun numbersInner(text: String, rules: Rules): String {
        // Каждый проход — под своим ключом Rules (вкладка «Правила»); выключенный просто пропускаем.
        fun step(key: String, f: (String) -> String): (String) -> String = if (rules.on(key)) f else { t -> t }
        // U+2212 (настоящий знак «минус», не дефис) — приводим к «-», чтобы его читал
        // тот же numberRe, что уже умеет «-5» (task 18 п.4).
        // Комбинированное ударение (U+0301) → «+» перед гласной, как ждёт модель; невидимые
        // соединители (U+200B–U+200D, U+2060, U+FEFF) и мягкий перенос книг (U+00AD) выкидываем — иначе слово рвётся на куски.
        // Эмодзи — до выкидывания соединителей: «👨‍👩‍👧» держится на U+200D, а «1️⃣» ушёл бы в числа.
        var s = Emoji.shared?.takeIf { rules.on("emoji") }?.apply(text) ?: text
        s = namedReadings(s)
        if (rules.on("emoji")) s = smileys(s)
        s = precomposedStressRe.replace(s) { "" + precomposedBase.getValue(it.value[0]) + '\u0301' }
        s = combiningAcuteRe.replace(s) { "+" + it.groupValues[1] }.replace(zeroWidthRe, "")
        // «тел.» — «телефон» только перед номером (до phones, пока он цифрами): «лежало множество тел.» — слово.
        s = step("abbrev") { t -> telRe.replace(t, "телефон") }(s)
        // Телефоны — первыми: дальше разряды тысяч, минус и диапазоны разобрали бы номер на куски.
        s = step("phones", ::phones)(s)
        s = step("codes", ::codes)(s)
        s = step("codes", ::requisites)(s)
        // «точка» — меткой, словом её делает symbols(): число перед словом «точка» согласовалось бы с ним («одна точка»)
        s = step("latin") { t -> fileExtRe.replace(t) { " $EXT_DOT " + it.groupValues[1] } }(s)
        // «+15%», «+5 °C» — «плюс»; «5 + 3» и «5+3» остаются arithmetic().
        if (rules.on("numbers")) s = unaryPlusRe.replace(s, "плюс ")
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
        s = step("thousands", ::oldNotations)(s)
        s = step("sections", ::sectionLabels)(s)
        // до римских цифр: «т. II», «кн. III» им нужны полными словами
        s = step("abbrev", ::nounAbbrevs)(s)
        // Градусы — до римских цифр: одиночная «C» после «°» иначе читается как римское 100
        // (eligibleAlone в romanNumerals не заглядывает влево, task 18 п.4).
        s = step("degrees", ::degrees)(s)
        s = step("roman_name", ::romanAfterName)(s)
        s = step("roman", ::arabicCentury)(s)
        s = step("roman", ::romanNumerals)(s)
        // до дат и единиц: «Р-7А» иначе «семь ампер», «УР-100Н» — «сто ньютонов»
        s = step("letter_digit", ::cyrCodes)(s)
        s = step("abbrev", ::bio)(s)
        s = step("dates", ::dates)(s)
        s = step("times", ::times)(s)
        s = step("years", ::yearsWithG)(s)
        s = step("abbrev", ::cityAbbrev)(s)
        s = step("units", ::cardinalGenitiveSuffixUnit)(s)
        // сложные прилагательные — раньше «-ти»: «17-ти-летняя» иначе «семнадцати летняя»
        s = step("numbers", ::compounds)(s)
        s = step("gen_suffix", ::cardinalGenitiveSuffix)(s)
        s = step("gen_suffix", ::cardinalGenitiveSuffixBareH)(s)
        s = step("arith", ::dimensions)(s)
        s = step("arith", ::arithmetic)(s)
        s = step("arith", ::powers)(s)
        s = step("fractions", ::mixedFractions)(s)
        s = step("spoons", ::spoons)(s)
        // деньги с копейками — до units(): «£», «₴» там единицы, копейки бы потерялись
        s = step("currency", ::moneyCents)(s)
        s = step("units", ::betweenUnits)(s)
        s = step("units", ::unitRange)(s)
        s = step("units", ::unitDashRange)(s)
        s = step("units", ::units)(s)
        s = step("fractions", ::fractionsSlash)(s)
        s = step("currency", ::currency)(s)
        s = step("abbrev", ::abbreviations)(s)
        s = step("numbers", ::numberSigns)(s)
        s = step("numbers", ::listsAndOrdinals)(s)
        s = step("numbers", ::chapterOrdinals)(s)
        s = step("numbers", ::ordNouns)(s)
        s = step("day_month", ::dayRanges)(s)
        // после abbreviations: «п. 2.10.3» должно сначала стать «пункт», а уже потом раскрыть номер
        s = step("sections", ::sectionNumbers)(s)
        s = step("numbers", ::halfScales)(s)
        s = step("cases", ::cases)(s)
        s = step("abbrev", ::scales)(s)
        s = step("latin", ::greek)(s)
        s = step("letter_digit", ::letterAfterDigit)(s)
        s = step("letter_digit", ::latinAtDigits)(s)
        s = step("day_month", ::dayMonth)(s)
        if (rules.on("years")) s = yearRe.replace(s) { m ->
            m.groupValues[1] + "-" + yearSuffix.getValue(m.groupValues[3].lowercase()) + m.groupValues[2] + m.groupValues[3]
        }
        if (!rules.on("numbers")) return s
        return accFem(numberWords(s))
    }

    // Винительный у дроби и десятичной: «тушите 1,5 часа», «заняла 1/8 с», «за 1/2», «в 1/4 финала», «проверила 1,1 млн
    // заявок» — «одну целую», «одну восьмую» (было «одна»). Перед числом предлог винительного или глагол (кроме «быть»,
    // «стать», «остаться», «пройти» — после них именительный). «со ста» вместо «с ста».
    private val ordFemAlt = """(?:целая|вторая|третья|четвёртая|пятая|шестая|седьмая|восьмая|девятая|десятая|сотая|тысячная|десятитысячная|стотысячная|миллионная)"""
    private val accFemRe = Regex(
        """(?<![\p{L}])((?:за|на|через|про|спустя|в|во)|\p{L}{2,}(?:ла|ли|ло|ил|ал|ял|ел|ите|йте|ть|ет|ит|ют|ят|ешь|ишь)(?![\p{L}]))((?:\s+$amplifierRe)*\s+)одна\s+($ordFemAlt)(?:(\s+)одна\s+($ordFemAlt))?(?![\p{L}])""",
        RegexOption.IGNORE_CASE)
    private val accFemStop = setOf("был", "была", "было", "были", "будет", "стал", "стала", "стало", "стали", "осталось", "остался",
        "осталась", "прошло", "прошла", "прошёл", "являлась", "являлось", "являлся", "есть", "ушло", "оставалось", "составляет")
    private fun accFemWord(w: String) = when (w.lowercase()) { "целая" -> "целую"; "третья" -> "третью"; else -> w.dropLast(2) + "ую" }
    // «двадцать одна целая» — «-ть» у числительного не глагол
    private val numeralWordRe = Regex("""(?:пять|шесть|семь|восемь|девять|десять|\p{L}+дцать|\p{L}+десят)""", RegexOption.IGNORE_CASE)
    private val soStoRe = Regex("""(?<![\p{L}])([сС])(\s+)(?=ст[ао](?![\p{L}])|стами(?![\p{L}]))""")
    private fun accFem(text: String) = soStoRe.replace(accFemRe.replace(text) { m ->
        if (m.groupValues[1].lowercase() in accFemStop || numeralWordRe.matches(m.groupValues[1])) return@replace m.value
        val sb = StringBuilder(m.groupValues[1]).append(m.groupValues[2]).append("одну ").append(accFemWord(m.groupValues[3]))
        if (m.groupValues[5].isNotEmpty()) sb.append(m.groupValues[4]).append("одну ").append(accFemWord(m.groupValues[5]))
        sb.toString()
    }) { "${it.groupValues[1]}о${it.groupValues[2]}" }

    private fun numberWords(s: String): String {
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
                sb.append(fraction(n, frac) ?: return@replace m.value)
            } else if (suffix.isNotEmpty()) {
                val tail = s.substring(m.range.last + 1)
                val sfx = when (suffix.lowercase()) {
                    "м" -> ordMSuffix(s.substring(0, m.range.first), tail)
                    // «между 1-й и 2-й линиями» — род по существительному за вторым номером
                    "й" -> (ordChainTailRe.find(tail)?.let { tail.substring(it.range.last + 1) } ?: tail).let { t ->
                        if (ordFem(t) ?: femTailRe.containsMatchIn(t)) "ей" else "й" }
                    // «8-е марта» — день месяца, ед. ч. («-а» у «марта» не мн. ч.)
                    "е" -> if (!monthAheadRe.containsMatchIn(tail) && (ordPlural(tail) ?: pluralTailRe.containsMatchIn(tail))) "ые" else "е"
                    else -> suffix.lowercase()
                }
                sb.append(ordinal(n, sfx, isDecadeTail(tail)))
            } else {
                sb.append(cardinal(n))
            }
            // «12,5%» — «процента» (дробь согласуется как 2-4).
            if (percent.isNotEmpty() && !percentWordAheadRe.containsMatchIn(s.substring(m.range.last + 1)))
                sb.append(' ').append(if (frac.isNotEmpty()) "процента" else plural(n, Triple("процент", "процента", "процентов")))
            // буквы вплотную, не суффикс и не единица («2б», «5кркр») — отдельным словом, иначе «дваб»
            // («3ее число» — несуществующий суффикс из гласных, читается буквально, как было: «триее»)
            val letters = s.substring(m.range.last + 1).takeWhile { it.isLetter() }
            if (letters.isNotEmpty() && !letters.all { it.lowercaseChar() in "аеёиоуыэюя" }) sb.append(' ')
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
        "wifi" to "вайфай", "wi" to "вай", "fi" to "фай", "bluetooth" to "блютус", "chrome" to "хром", "github" to "гитхаб", "steam" to "стим",
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
        "en" to "эн", "wiki" to "вики", "index" to "индекс", "blog" to "блог", "news" to "ньюс", "watch" to "вотч",
        // форумы и приложения: 4PDA, подписи, имена файлов
        "ruvoice" to "ру войс", "talkback" to "токбэк", "huawei" to "хуавэй", "xiaomi" to "сяоми", "txt" to "тэ икс тэ",
        "sqlite" to "эс кью лайт", "mp" to "эм пэ", "fb" to "эф бэ", "epub" to "и паб", "lite" to "лайт", "edge" to "эдж",
        "edition" to "эдишн", "engine" to "энджин", "ui" to "ю ай", "etc" to "эт сетера", "pro" to "про", "max" to "макс",
        "ultra" to "ультра", "note" to "ноут", "reader" to "ридер", "voice" to "войс",
        "to" to "ту", "forum" to "форум", "type" to "тайп", "power" to "пауэр", "poweramp" to "пауэрамп",
        // частые слова из интерфейсов и кода — как их говорят по-русски
        "audio" to "аудио", "focus" to "фокус", "manager" to "менеджер", "builder" to "билдер", "build" to "билд",
        "pause" to "поуз", "status" to "статус", "volume" to "вольюм", "player" to "плеер", "change" to "чейндж",
        "usage" to "юзидж", "attributes" to "атрибьютс", "language" to "лэнгвидж", "russian" to "рашн", "brian" to "брайан")
    // Латинское слово без гласных — сокращение, по буквам: «fp32», «pss», «rgb», «css», «dB» (было «фп», «ргб»).
    // Английских слов без гласных нет, кроме междометий — их транслитом, как было.
    private val latinInterjections = setOf("hm", "hmm", "hmmm", "shh", "sh", "ssh", "psst", "pst", "brr", "grr", "zzz", "pff", "pfff", "mm", "mmm", "tsk", "nth")
    private fun latinNoVowels(w: String) = w.length in 2..6 && w.none { it in "aeiouy" } && w !in latinInterjections && w.toSet().size > 1
    private val latinWordRe = Regex("[a-z]+")
    private val softVowels = setOf('e', 'i', 'y')

    // 12. Омоглифы: латинская буква внутри преимущественно кириллического слова — опечатка
    // раскладки («прoблема» с латинской «o»), а не английское слово — возвращаем в кириллицу.
    // и двойники заглавных («Bсе», «Kнига», «Mама», «Hет», «Tам»), после lowercase — строчные
    private val homoglyphMap = mapOf('a' to 'а', 'c' to 'с', 'e' to 'е', 'o' to 'о', 'p' to 'р', 'x' to 'х', 'y' to 'у',
        'b' to 'в', 'k' to 'к', 'm' to 'м', 'h' to 'н', 't' to 'т')
    private val mixedWordRe = Regex("""\p{L}+""")
    private fun fixHomoglyphs(text: String) = mixedWordRe.replace(text) { m ->
        val w = m.value
        val cyr = w.count { it in 'а'..'я' || it == 'ё' }
        val lat = w.count { it in 'a'..'z' }
        when {
            cyr == 0 || lat == 0 -> w
            // все латинские — двойники кириллицы: опечатка раскладки
            cyr > lat && w.all { it !in 'a'..'z' || it in homoglyphMap } -> w.map { homoglyphMap[it] ?: it }.joinToString("")
            // латиница целым куском в начале или в конце («файлmicrosoft», «файлreverse») — два слова, не опечатка
            scriptSplitRe.matches(w) -> scriptEdgeRe.replace(w, " ")
            cyr > lat -> w.map { homoglyphMap[it] ?: it }.joinToString("")
            else -> w
        }
    }
    // одна латинская буква («ттs») — опечатка, не отдельное слово: так не делим
    private val scriptSplitRe = Regex("""[а-яё]{2,}[a-z]{2,}|[a-z]{2,}[а-яё]{2,}""")
    private val scriptEdgeRe = Regex("""(?<=[а-яё])(?=[a-z])|(?<=[a-z])(?=[а-яё])""")

    // Слово со склеенными заглавными — по частям: «EdgeTTS» — «эдж ти ти эс», «MoonReader», «FoxyBook», «AlReaderX»;
    // раньше транслит читал его одним словом («едджеттс»). Слова из wordFixes («YouTube», «iPhone», «WhatsApp») не
    // трогаем. До Abbrev: отделённая часть заглавными («TTS») читается по буквам. Одиночная заглавная латинская
    // буква — названием («V», «X»): транслит давал «в», «кс».
    private val camelWordRe = Regex("""(?<![\p{L}\d])[A-Za-z]*[a-z][A-Z][A-Za-z]*""")
    // одна строчная перед заглавной — приставка («iPhone», «eBook», «dB», «mAbook»), по ней не делим
    private val camelEdgeRe = Regex("""(?<=[A-Z][a-z]|[a-z]{2})(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])""")
    // «P.S.», «U.S.A.» — сокращение с точками, не отдельная буква
    private val loneCapRe = Regex("""(?<![\p{L}\d'’.@/_-])[A-Z](?![\p{L}\d'’@/_-]|\.\p{L})""")
    fun camelCase(text: String, rules: Rules = Rules()): String {
        if (!rules.on("latin") || !hasLatinRe.containsMatchIn(text)) return text
        val split = camelWordRe.replace(text) { m -> if (m.value.lowercase() in wordFixes) m.value else camelEdgeRe.replace(m.value, " ") }
        return loneCapRe.replace(split) { Abbrev.latLetterNames.getValue(it.value[0]) }
    }

    // Одиночная латинская буква вплотную за цифрой или через дефис за русским словом — названием буквы
    // («5800X» → «экс», «5800X3D» → «экс три ди», «GPT-4o» → «оу», «витамин-D» → «ди»), идея из ru-normalizr.
    // «3x4» не трогаем — это умножение.
    private val letterAfterDigitRe = Regex("""(?<=\d|[а-яё]-)(?!x\d)([A-Za-z])(?![\p{L}])""")
    private fun letterAfterDigit(text: String) = letterAfterDigitRe.replace(text) { m ->
        (if (text[m.range.first - 1].isDigit()) " " else "") + Abbrev.latLetterNames.getValue(m.value[0].uppercaseChar()) +
            (if (text.getOrNull(m.range.last + 1)?.isDigit() == true) " " else "")
    }

    // Одиночная латинская буква вплотную перед цифрами — названием: «S20» — «эс двадцать», «v2» — «ви два», «i7», «X3»,
    // «A32» (было «с двадцать», «в два», «кс три»). Буква внутри слова («mp3», «fp32») сюда не попадает.
    // «N2» — не буква, а знак номера в библиографии («Земля и Вселенная, 1980, N2»): оставляем как было.
    private val letterBeforeDigitRe = Regex("""(?<![\p{L}\d'])([A-MO-Za-mo-z])(?=\d)""")
    // Латинское слово вплотную за цифрами («4PDA», «13pro», «77ruS») — через пробел: иначе число словами («четыре»)
    // склеивалось с латиницей в одно слово, и омоглифы делали из «pda» кириллическое «рда» — «четырерда».
    // После единиц: «5GB», «100mAh» они читают сами. Одна буква («5800X», «7A») — letterAfterDigit.
    private val latinAfterDigitsRe = Regex("""(?<=\d)(?=[A-Za-z]{2})""")
    private fun latinAtDigits(text: String) =
        latinAfterDigitsRe.replace(letterBeforeDigitRe.replace(text) { Abbrev.latLetterNames.getValue(it.value[0].uppercaseChar()) + " " }, " ")

    // Расширение файла — «точка» словом: «Brian.wav», «ruvoice-pack-ru.zip», «0.14.10-lite.apk». Без слова точка
    // уходила в модель внутри слова и не звучала. Адреса («index.html» в ссылке) разобраны раньше, в numbers().
    private val fileExtRe = Regex("""(?<=[\p{L}\d_)\]])\.(zip|apk|mp3|mp4|m4a|m4b|ogg|opus|flac|wav|fb2|epub|txt|pdf|djvu|docx?|rtf|jpe?g|png|gif|webp|exe|rar|7z|json|xml|html?|csv)(?![\p{L}\d])""", RegexOption.IGNORE_CASE)

    // Технические коды кириллицей — буквы при цифрах названиями: «Р-9» → «+эр-9», «8К74» → «8 +ка 74»,
    // «Т-72Б3» → «+тэ-72 +бэ 3», «РД-170» → «эр д+э-170»; «ГАЗ-66» остаётся словом (Abbrev.codePart).
    // Цифры оставляем числу. Смешанный регистр («Ту-154», «МиГ-29») — слово, не трогаем; число с единицей
    // вплотную («5А», «100Н») — units.
    private val cyrCodeRe = Regex("""(?<![\p{L}\d-])(?=[А-ЯЁ\d-]*[А-ЯЁ])(?=[А-ЯЁ\d-]*\d)[А-ЯЁ\d]+(?:-[А-ЯЁ\d]+)*(?![\p{L}\d]|-[\p{L}\d])""")
    private val codeRunRe = Regex("""[А-ЯЁ]+|\d+|-""")
    // Перед номером дома, места, квартиры, рисунка буква вплотную — литера, не единица: «место 12А», «дом 7Б»,
    // «выход № 7C» (было «двенадцать ампер»).
    private val letterNumberBehindRe = Regex(
        """(?:(?<![\p{L}])(?:дом|д|место|мест[ае]|кв|квартир[аеуы]|корп|корпус|рис|табл|п|пункт|выход|гейт|школ[аеуы]|класс|""" +
            """кабинет|каб|палат[аеуы]|маршрут|автобус|трамвай|троллейбус|вагон|ряд|сектор|участок|литер[аеуы]?|номер|№)\.?)\s*$""",
        RegexOption.IGNORE_CASE)
    private fun cyrCodes(text: String) = cyrCodeRe.replace(text) { m ->
        val runs = codeRunRe.findAll(m.value).map { it.value }.toList()
        if (runs.size == 2 && runs[0][0].isDigit() && unitOf(runs[1]) != null &&
            !(runs[1].length == 1 && letterNumberBehindRe.containsMatchIn(text.substring(0, m.range.first)))) return@replace m.value
        val sb = StringBuilder()
        for ((i, r) in runs.withIndex()) {
            if (i > 0 && r != "-" && runs[i - 1] != "-") sb.append(' ')
            // ведущие нули в номере произносятся: «ТМА-01М» — «ноль один»
            val zeros = if (r.length > 1) r.takeWhile { it == '0' }.let { if (it == r) it.dropLast(1) else it } else ""
            sb.append(if (r[0].isLetter()) Abbrev.codePart(r) else (zeros.map { "0" } + r.drop(zeros.length)).joinToString(" "))
        }
        sb.toString()
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
        wordFixes[m.value] ?: if (latinNoVowels(m.value)) m.value.map { Abbrev.latLetterNames.getValue(it.uppercaseChar()) }.joinToString(" ") else run {
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
                // «y» между согласными — «и»: «system», «gym», «Kyutai» (было «сйстем», «джйм»)
                else if (c == 'y' && i > 0 && w[i - 1] !in "aeiou" && next != null && next !in "aeiouy") { sb.append("и"); i++ }
                else { sb.append(singles[c] ?: ""); i++ }
            }
            // немое e на конце
            if (w.length > 2 && w.endsWith("e") && !w.endsWith("ee")) sb.setLength(sb.length - 1)
            // «e» в начале слова — «э»: «episteme», «eos», «energy» (было «епистем», «еос»)
            if (w[0] == 'e' && sb.startsWith("е")) sb.setCharAt(0, 'э')
            sb.toString()
        }
    }

    // JVM \s матчит только ASCII-пробелы; Python \s матчит любой Unicode-пробел (NBSP U+00A0 и т.п.).
    // Сначала приводим все такие пробелы к обычному, иначе они просто выпадают на фильтрации и слова слипаются.
    private val wsClass = Regex("[\\s\\p{Zs}\\u0085\\u2028\\u2029\\u001C-\\u001F]")
    /** Юникодные дефисы и минус внутри слова — в обычный дефис, иначе фильтр их выкинет и «во‐первых» станет «воперв+ых». */
    private val hyphenClass = Regex("[\\u2010\\u2011\\u2012\\u2043\\u2212\\uFE63\\uFF0D]")

    // Скобок в алфавите модели нет, а пауза на вставке нужна, как на «;» и «:»: скобка → запятая; у края
    // предложения или вплотную к другому знаку — просто убираем.
    private val parenRe = Regex("""\s*[()\[\]]\s*""")
    private fun parens(s: String) = parenRe.replace(s) { m ->
        val before = s.getOrNull(m.range.first - 1); val after = s.getOrNull(m.range.last + 1)
        when {
            after == null || after in ".,;:!?…" -> ""
            before == null -> ""
            before in ".,;:!?…–-" || after in "–-" -> " "
            else -> ", "
        }
    }

    fun symbols(text: String, allowed: String, rules: Rules = Rules()): String {
        // «±»/«≈»/«&» — словами (task 28 п.6), иначе фильтр allowed их молча выкинет.
        // Тире — то, что знает модель: v5_5_ru «–», cis-пак только «—»; иначе фильтр съест знак и паузы не будет.
        val dash = if ('–' in allowed || '—' !in allowed) '–' else '—'
        val normalized = (if (rules.on("pause_parens")) ::parens else { s: String -> s })(text.replace('—', '–').replace('–', dash).replace(hyphenClass, "-").replace("±", " плюс-минус ").replace("≈", " примерно ")
            .replace("&", " и ").replace(wsClass, " "))
        val sb = StringBuilder(normalized.length)
        val names = rules.on("symbol_names")
        // «+» пропускается фильтром как знак ударения, но ударение — только перед гласной. Остальной «+» — «плюс»:
        // «Moon+ Reader», «C++», «Aimp + MoonReader» (раньше модель его молча глотала).
        val plused = plusNotStressRe.replace(normalized, " плюс ").replace(EXT_DOT.toString(), " точка ")
        for (c in plused) {
            if (c in allowed) sb.append(c)
            // знак, который фильтр выкинул бы, — словом в пробелах; ударение «+» не трогаем
            else if (names && c != '+' && SymbolNames.of(c) != null) sb.append(' ').append(SymbolNames.of(c)).append(' ')
            // выкинутый знак — пробел, иначе «и/или» слипается в «иили»; буквы чужих алфавитов и
            // надстрочные знаки выпадают без следа
            else if (!c.isLetter() && Character.getType(c) != Character.NON_SPACING_MARK.toInt()) sb.append(GAP)
        }
        // пробел нужен только между словами: у пробела, знака препинания и края строки зазор не ставим
        val gapped = if (GAP !in sb) sb.toString() else gapEdgeRe.replace(sb, "").replace(GAP, ' ')
        // пустые паузы подряд («[][][]», «))» после скобок) — одна; перед точкой и в начале — не нужна
        return commaEdgeRe.replace(commaRunRe.replace(gapped, ","), "").replace(Regex("\\s+"), " ").replace(" ,", ",").trim()
    }
    private val plusNotStressRe = Regex("""\+(?![аеёиоуыэюяАЕЁИОУЫЭЮЯ])""")
    private val commaRunRe = Regex(""",(?:\s*,)+""")
    private val commaEdgeRe = Regex("""^[\s,]+|(?<=[:;.!?…])\s*,|\s*,(?=\s*(?:[.!?…;:]|$))""")
    private const val GAP = '\u0000'
    /** Точка перед расширением файла (fileExtRe) до symbols(), где она становится словом «точка». */
    private const val EXT_DOT = '\uE000'
    private val gapEdgeRe = Regex("""\u0000+(?=[\s.,!?;:…»)]|$)|(?<=^|[\s«(])\u0000+""")

    // Регистр НЕ приводим к нижнему здесь: numbers() должен видеть исходный регистр — иначе
    // римские цифры в CAPS-токене («Людовик XIV») теряют признак «весь токен заглавный» ещё
    // до romanNumerals(). latin() лоуэркейсит сам, так что дальше по пайплайну (символы/фильтр)
    // всё как раньше (review t17 round2 п.1).
    // Abbrev до latin(): latin() лоуэркейсит текст, а аббревиатуры узнаются по КАПСУ.
    fun prepare(text: String, allowed: String, rules: Rules = Rules()): String =
        symbols(latin(Abbrev.apply(camelCase(numbers(punctuation(imageMark(text, rules), rules), rules), rules), rules), rules), allowed, rules)

    // Пометка картинки от AlReaderX «(- Иллюстрация -)»: с дефисами вокруг модель на быстром темпе комкала слово
    // (жалоба 08.10.2026). Чистым словом с точкой, по drop_images — не читаем вовсе.
    private val imageMarkRe = Regex("""\(\s*[-–—]?\s*Иллюстрация\s*[-–—]?\s*\)""")
    private fun imageMark(text: String, rules: Rules) =
        if ('(' !in text) text else imageMarkRe.replace(text, if (rules.on("drop_images")) "" else "Иллюстрация.")
}
