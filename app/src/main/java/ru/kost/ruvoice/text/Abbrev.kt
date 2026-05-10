package ru.kost.ruvoice.text

/**
 * Чтение аббревиатур по буквам: «ФСБ» → «эф эс б+э», «USB» → «ю эс б+и».
 * Токен — 2-6 заглавных кириллических или 2-5 заглавных латинских букв подряд, без цифр
 * и букв другого регистра рядом (границы через lookaround, без \b). Аббревиатура читается
 * по буквам, если в ней нет гласных, либо она явно в списке «по буквам»; иначе токен не
 * трогаем — это либо слово-акроним (НАТО, iPhone), либо не аббревиатура (ГЛАВА).
 * Токены рядом с дефисом и цифрой («Ту-154», «С-300») не матчатся — это другая задача.
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
        "МГУ", "НЛО", "ЭВМ", "ПТУ", "ОАО", "ООО", "ИНН", "США", "ЦРУ", "УВД", "ПВО",
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
    private val cyrExceptions = mapOf("ГИБДД" to "ги бэ дэ д+э")

    private val cyrLetterNames = mapOf(
        'А' to "а", 'Б' to "бэ", 'В' to "вэ", 'Г' to "гэ", 'Д' to "дэ", 'Е' to "е",
        'Ё' to "ё", 'Ж' to "жэ", 'З' to "зэ", 'И' to "и", 'Й' to "и краткое",
        'К' to "ка", 'Л' to "эл", 'М' to "эм", 'Н' to "эн", 'О' to "о", 'П' to "пэ",
        'Р' to "эр", 'С' to "эс", 'Т' to "тэ", 'У' to "у", 'Ф' to "эф", 'Х' to "ха",
        'Ц' to "цэ", 'Ч' to "че", 'Ш' to "ша", 'Щ' to "ща", 'Ъ' to "твёрдый знак",
        'Ы' to "ы", 'Ь' to "мягкий знак", 'Э' to "э", 'Ю' to "ю", 'Я' to "я"
    )

    private val latLetterNames = mapOf(
        'A' to "эй", 'B' to "би", 'C' to "си", 'D' to "ди", 'E' to "и", 'F' to "эф",
        'G' to "джи", 'H' to "эйч", 'I' to "ай", 'J' to "джей", 'K' to "кей", 'L' to "эл",
        'M' to "эм", 'N' to "эн", 'O' to "оу", 'P' to "пи", 'Q' to "кью", 'R' to "ар",
        'S' to "эс", 'T' to "ти", 'U' to "ю", 'V' to "ви", 'W' to "дабл ю", 'X' to "экс",
        'Y' to "уай", 'Z' to "зед"
    )

    // (?<!\d-)...(?!-\d) — не матчить токен, если он приклеен к цифре через дефис
    private val cyrToken = Regex("""(?<!\d-)(?<![\p{L}\d])[А-ЯЁ]{2,6}(?![\p{L}\d])(?!-\d)""")
    private val latToken = Regex("""(?<!\d-)(?<![\p{L}\d])[A-Z]{2,5}(?![\p{L}\d])(?!-\d)""")

    fun apply(text: String): String {
        val withCyr = cyrToken.replace(text) { spellCyr(it.value) }
        return latToken.replace(withCyr) { spellLat(it.value) }
    }

    private fun spellCyr(token: String): String {
        cyrExceptions[token]?.let { return it }
        val spell = token.none { it in CYR_VOWELS } || token in cyrSpellSet
        return if (spell) spellOut(token, cyrLetterNames) else token
    }

    private fun spellLat(token: String): String {
        val spell = token.none { it in LAT_VOWELS_FOR_AUTO_SPELL } || token in latSpellSet
        return if (spell) spellOut(token, latLetterNames) else token
    }

    // имена букв через пробел, "+" перед гласной в имени последней буквы
    private fun spellOut(token: String, names: Map<Char, String>): String {
        val parts = token.map { names.getValue(it) }.toMutableList()
        parts[parts.lastIndex] = withStress(parts.last())
        return parts.joinToString(" ")
    }

    private fun withStress(name: String): String {
        val i = name.indexOfFirst { it in VOWELS_IN_NAMES }
        return if (i < 0) name else name.substring(0, i) + "+" + name.substring(i)
    }
}
