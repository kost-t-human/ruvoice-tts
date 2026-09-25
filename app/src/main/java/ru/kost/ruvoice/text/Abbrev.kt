package ru.kost.ruvoice.text

/**
 * Чтение аббревиатур по буквам: «ФСБ» → «эф эс б+э», «USB» → «ю эс б+и».
 * Токен — 2-6 заглавных кириллических или 2-5 заглавных латинских букв подряд, без цифр
 * и букв другого регистра рядом (границы через lookaround, без \b). Аббревиатура читается
 * по буквам, если в ней нет гласных, либо она явно в списке «по буквам»; иначе токен не
 * трогаем — это либо слово-акроним (НАТО, iPhone), либо не аббревиатура (ГЛАВА).
 * Токены рядом с дефисом и цифрой («Р-9», «8К74») не матчатся — их читает Normalizer.cyrCodes через [codePart].
 */
object Abbrev {
    private const val CYR_VOWELS = "АЕЁИОУЫЭЮЯ"

    // для латиницы I и Y гласными не считаются: FBI, HDMI и подобные всё равно читаются по
    // буквам как «нет гласных», а не остаются словом
    private const val LAT_VOWELS_FOR_AUTO_SPELL = "AEOU"

    // ударная гласная в имени буквы (последняя буква токена получает "+" перед ней)
    private const val VOWELS_IN_NAMES = "аеёиоуыэюя"

    // кириллица с гласными, которая всё равно читается по буквам
    private val cyrSpellSet = setOf(
        "МГУ", "НЛО", "ЭВМ", "ПТУ", "ОАО", "ООО", "ИНН", "ЦРУ", "УВД", "ПВО",
        "ЕС", "АО", "ИО", "ИТ", "ОРТ", "ЛДПР", "КПРФ", "СНГ", "ФРГ", "ГДР", "ЦСКА",
        "ЖКХ", "МВФ", "ЕГЭ"
    )

    // латиница с гласными, которая всё равно читается по буквам
    private val latSpellSet = setOf(
        "USB", "URL", "API", "CPU", "GPU", "IBM", "IP", "ISBN", "OS", "SOS", "UFO",
        "UK", "UN", "USA", "UTF", "VGA", "WTO", "CIA", "EU", "TV", "PC", "AI", "IT",
        "ID", "OK", "DNA", "CEO", "VIP", "USSR"
    )

    // готовое чтение для аббревиатур, где механическая расшифровка по буквам не совпадает
    // с реальным произношением («гэ и бэ дэ дэ» никто так не говорит — только «ги-бэ-дэ-дэ»)
    // США по традиции «сэ-шэ-а», не по именам букв
    private val cyrExceptions = mapOf("ГИБДД" to "ги бэ дэ д+э", "США" to "сэ шэ +а")

    // Три буквы с единственной гласной с краю («ЛКИ», «ТМА», «ШПУ», «АКС») слогом не произносятся —
    // по буквам. Обычные слова того же вида и заголовки капсом не трогаем.
    // ponytail: стоп-лист частых слов, а не словарь; пополнять по жалобам
    private val edgeVowelWords = setOf(
        "что", "кто", "где", "два", "две", "три", "сто", "все", "всё", "вся", "мне", "вне", "для", "дни", "дно",
        "дна", "дня", "зло", "зла", "сны", "сна", "шла", "шло", "шли", "зря", "рта", "рту", "рты", "пса", "псы",
        "мха", "шва", "швы", "вши", "акт", "иск", "ест", "арт", "ёрш", "ост", "спа", "бра", "тла"
    )
    private val capsWordBefore = Regex("""(?<![\p{L}\d])[А-ЯЁ]{4,}[\s,:;—–-]*$""")
    private val capsWordAfter = Regex("""^[\s,:;—–-]*[А-ЯЁ]{4,}(?![\p{L}\d])""")

    private fun edgeVowel(t: String) = t.length == 3 && t.count { it in CYR_VOWELS } == 1 &&
        (t[0] in CYR_VOWELS || t[2] in CYR_VOWELS) && t.none { it in "ЙЬЪ" } && t.lowercase() !in edgeVowelWords

    // «эр», «эн», «эм» модель читает как «р», «н», «м», «че» как «чо» (на слух 24.09.2026, TalkBack);
    // латиница: «зед» звучит «зет», M и N — те же «эм», «эн» (25.09.2026). Эти имена и в аббревиатурах
    private val cyrLetterNames = mapOf(
        'А' to "а", 'Б' to "бэ", 'В' to "вэ", 'Г' to "гэ", 'Д' to "дэ", 'Е' to "е",
        'Ё' to "ё", 'Ж' to "жэ", 'З' to "зэ", 'И' to "и", 'Й' to "и краткое",
        'К' to "ка", 'Л' to "эл", 'М' to "эмм", 'Н' to "энн", 'О' to "о", 'П' to "пэ",
        'Р' to "ээр", 'С' to "эс", 'Т' to "тэ", 'У' to "у", 'Ф' to "эф", 'Х' to "ха",
        'Ц' to "цэ", 'Ч' to "чэ", 'Ш' to "ша", 'Щ' to "ща", 'Ъ' to "твёрдый знак",
        'Ы' to "ы", 'Ь' to "мягкий знак", 'Э' to "э", 'Ю' to "ю", 'Я' to "я"
    )

    val latLetterNames = mapOf(
        'A' to "эй", 'B' to "би", 'C' to "си", 'D' to "ди", 'E' to "и", 'F' to "эф",
        'G' to "джи", 'H' to "эйч", 'I' to "ай", 'J' to "джей", 'K' to "кей", 'L' to "эл",
        'M' to "эмм", 'N' to "энн", 'O' to "оу", 'P' to "пи", 'Q' to "кью", 'R' to "ар",
        'S' to "эс", 'T' to "ти", 'U' to "ю", 'V' to "ви", 'W' to "дабл ю", 'X' to "экс",
        'Y' to "уай", 'Z' to "зэдд"
    )

    // (?<!\d-)...(?!-\d) — не матчить токен, если он приклеен к цифре через дефис
    private val cyrToken = Regex("""(?<!\d-)(?<![\p{L}\d])[А-ЯЁ]{2,6}(?![\p{L}\d])(?!-\d)""")
    private val latToken = Regex("""(?<!\d-)(?<![\p{L}\d])[A-Z]{2,5}(?![\p{L}\d])(?!-\d)""")

    fun apply(text: String, rules: Rules = Rules()): String {
        val withCyr = if (rules.on("spell_cyr")) cyrToken.replace(text) { m ->
            // соседнее слово капсом — заголовок или крик («ЧТО ДЕЛАТЬ»), не аббревиатура
            val caps = capsWordBefore.containsMatchIn(text.substring(maxOf(0, m.range.first - 40), m.range.first)) ||
                capsWordAfter.containsMatchIn(text.substring(m.range.last + 1, minOf(text.length, m.range.last + 41)))
            spellCyr(m.value, !caps)
        } else text
        return if (rules.on("spell_lat")) latToken.replace(withCyr) { spellLat(it.value) } else withCyr
    }

    private fun spellCyr(token: String, edge: Boolean = true): String {
        cyrExceptions[token]?.let { return it }
        val spell = token.none { it in CYR_VOWELS } || token in cyrSpellSet || edge && edgeVowel(token)
        return if (spell) spellOut(token, cyrLetterNames) else token
    }

    /** Буквенная часть кода («Р» в «Р-9», «К» в «8К74», «РД» в «РД-170»): одна-две буквы — всегда по буквам
     * («АК-74» — «а ка»), длиннее — как аббревиатура, а слово («ГАЗ-66», «ЗИЛ-130») остаётся словом. */
    fun codePart(letters: String): String =
        if (letters.length <= 2) spellOut(letters, cyrLetterNames) else spellCyr(letters)

    // Две заглавные латинские почти всегда сокращение («UX», «SE», «FE», «AI»), кроме английских слов.
    private val latTwoLetterWords = setOf("OK", "NO", "GO", "SO", "DO", "WE", "ME", "MY", "HE", "BE", "HI", "OH", "IF", "OR", "AT",
        "AS", "IN", "ON", "TO", "IT", "IS", "AN", "AM", "UP", "OF", "BY",
        // эра («500 CE») — отдельная нерешённая история (NORMALIZER.md), не трогаем
        "CE", "BC", "AD")

    private fun spellLat(token: String): String {
        val spell = token.none { it in LAT_VOWELS_FOR_AUTO_SPELL } || token in latSpellSet ||
            token.length == 2 && token !in latTwoLetterWords
        return if (spell) spellOut(token, latLetterNames) else token
    }

    // имена букв через пробел, "+" перед гласной в имени последней буквы
    private fun spellOut(token: String, names: Map<Char, String>): String {
        val parts = token.map { names.getValue(it).replace("+", "") }.toMutableList()
        parts[parts.lastIndex] = withStress(names.getValue(token.last()))
        return parts.joinToString(" ")
    }

    // Запрос из одной буквы без слов вокруг (эхо ввода Jieshuo, на телефоне на слух 25.09.2026): «вэ» звучит «вы»,
    // «ы» — «пы»; точка у остальных букв хуже, поэтому только эти
    private val loneRequestNames = mapOf('В' to "в+э.", 'Ы' to "— +ы")

    /** Имя буквы, когда весь запрос — одна буква: [letterName] с поправками для голой буквы. */
    fun loneLetterName(c: Char): String? = loneRequestNames[c.uppercaseChar()] ?: letterName(c)

    /** Имя буквы с ударением для любой кириллической/латинской буквы; null — не буква из таблиц.
     * Для одиночной буквы (TalkBack, посимвольная TtsSpan), не для аббревиатур. */
    fun letterName(c: Char): String? =
        (cyrLetterNames[c.uppercaseChar()] ?: latLetterNames[c.uppercaseChar()])?.let(::withStress)

    private fun withStress(name: String): String {
        if (name == "уай") return "у+ай"   // ударение не на первой гласной
        val i = name.indexOfFirst { it in VOWELS_IN_NAMES }
        return if (i < 0) name else name.substring(0, i) + "+" + name.substring(i)
    }
}
