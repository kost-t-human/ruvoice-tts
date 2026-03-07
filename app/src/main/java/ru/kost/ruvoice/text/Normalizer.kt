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

    /** «2024-м» → «две тысячи двадцать четвёртом»: порядковым делаем только последнее слово. */
    fun ordinal(n: Long, suffix: String): String {
        val e = endings[suffix] ?: return cardinal(n)
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
        val prefix = if (head > 0) cardinal(head) + " " else ""
        return prefix + stem + ending
    }

    private val numberRe = Regex("""(№|§)?(?<!\d)(-?)(\d+)(?:[.,](\d+))?(?:-(й|го|му|м|х|е|я|ю)(?![а-яё]))?(%)?""")

    fun numbers(text: String): String = numberRe.replace(text) { m ->
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
            val fracN = frac.toLong()
            val denom = when (frac.length) { 1 -> Triple("десятая", "десятых", "десятых"); 2 -> Triple("сотая", "сотых", "сотых"); else -> Triple("тысячная", "тысячных", "тысячных") }
            sb.append(cardinal(n, feminine = true)).append(if (n % 10 == 1L && n % 100 != 11L) " целая " else " целых ")
            sb.append(cardinal(fracN, feminine = true)).append(' ').append(plural(fracN, denom))
        } else if (suffix.isNotEmpty()) {
            sb.append(ordinal(n, suffix))
        } else {
            sb.append(cardinal(n))
        }
        if (percent.isNotEmpty()) sb.append(' ').append(plural(n, Triple("процент", "процента", "процентов")))
        sb.toString()
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

    fun latin(text: String): String = latinWordRe.replace(text.lowercase()) { m ->
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

    fun symbols(text: String, allowed: String): String {
        val sb = StringBuilder(text.length)
        for (c in text.replace('—', '–').replace('‑', '-')) if (c in allowed) sb.append(c)
        return sb.toString().replace(Regex("\\s+"), " ").trim()
    }

    fun prepare(text: String, allowed: String): String =
        symbols(latin(numbers(text.lowercase())), allowed)
}
