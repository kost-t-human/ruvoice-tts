package ru.kost.ruvoice.text

/**
 * Английские куски текста — для правила «Английский другим движком» (en_proxy): их читает системный
 * движок синтеза (EnglishProxy), остальное — Silero, как раньше.
 *
 * Внутри русского предложения английским считается отрезок из minWords и больше английских слов
 * подряд (между ними — пробелы, знаки, числа): «Он сказал: I don't know what you mean.». По умолчанию
 * с одного слова; порог побольше (настройка «От скольких слов») оставляет короткие названия вроде
 * «Microsoft Word» транслитерации, чтобы голос не прыгал на каждом. Кусок вовсе без кириллицы —
 * английский с первого же слова при любом пороге: так TalkBack читает пункты «Settings», «Wi-Fi».
 *
 * Английским словом не считаются: римские числа («XIV» — номер главы), одиночные буквы, сокращения
 * капсом до четырёх букв («USB», «OK»); они отрезок не рвут, но и не начинают. Короткое слово капсом из
 * [CAPS_WORDS] («HELP», «I LOVE YOU») — слово: трёх-четырёхбуквенное само по себе, двухбуквенное («IT»,
 * «NO») — как «I», только рядом с другим английским словом, иначе «отдел IT» ушёл бы английскому голосу. «I» и «a» идут в счёт
 * слов, только если рядом есть настоящее английское слово («I love you», но не «Глава I»). Слово
 * с кириллицей, цифрами, «/», «@», точкой внутри (ссылки, почта, «4PDA») отрезок рвёт.
 */
object English {
    const val MIN_WORDS = 1
    const val MAX_WORDS = 10

    private val tokenRe = Regex("\\S+")
    // слово с апострофом или дефисом внутри: don't, Wi-Fi, mother-in-law
    private val wordRe = Regex("[A-Za-z]+(?:['’-][A-Za-z]+)*")
    private val numberRe = Regex("\\d+(?:[.,:]\\d+)*")
    private val romanRe = Regex("[IVXLCDM]+")
    private val cyrRe = Regex("[А-Яа-яЁё]")
    // знаки по краям слова, которые не мешают ему быть словом: кавычки, скобки, препинание
    private const val EDGE = "\"'«»“”„‘’()[]{},.;:!?…—–-*"
    private const val SENT_END = ".!?…"

    /** Частые короткие английские слова, которые пишут капсом в заголовках, кнопках и криках. Сокращений
     * (USA, API, CEO, NASA) здесь нет — их по-прежнему читает русский голос по буквам. */
    private val CAPS_WORDS = setOf(
        "THE", "AND", "YOU", "ARE", "FOR", "NOT", "BUT", "ALL", "CAN", "HER", "HIS", "WAS", "ONE", "OUR", "OUT",
        "GET", "HAS", "HIM", "HOW", "NEW", "NOW", "OLD", "SEE", "TWO", "WAY", "WHO", "DID", "ITS", "LET", "PUT",
        "SAY", "SHE", "TOO", "USE", "YES", "WHY", "BIG", "END", "RUN", "TOP", "OFF", "GOT", "HOT", "RED", "SEX",
        "LOVE", "HELP", "STOP", "OPEN", "SAVE", "EXIT", "MENU", "HOME", "BACK", "NEXT", "DONE", "EDIT", "SEND",
        "PLAY", "WAIT", "KILL", "HATE", "GAME", "OVER", "LIKE", "WHAT", "WHEN", "WITH", "THIS", "THAT", "YOUR",
        "FROM", "HAVE", "JUST", "COME", "MAKE", "TAKE", "GIVE", "GOOD", "BEST", "FREE", "SALE", "CALL", "LIVE",
        "READ", "MORE", "LESS", "WORK", "TIME", "LIFE", "KING", "GIRL", "LOOK", "KEEP", "CALM", "DEAD", "LOST",
        "FIND", "WANT", "NEED", "KNOW", "ONLY", "HERE", "THEY", "THEM", "WILL", "BEEN", "WERE", "LAST", "DARK",
        "IS", "IT", "TO", "OF", "IN", "ON", "AT", "BE", "WE", "ME", "MY", "HE", "GO", "DO", "NO", "SO", "UP", "OR",
        "IF", "BY", "AN", "AM", "AS", "OH", "HI",
    )

    private enum class Kind { WORD, WEAK, SOFT, BREAK }

    private class Tok(val start: Int, val end: Int, val kind: Kind)

    private fun kind(core: String): Kind = when {
        core.isEmpty() -> Kind.SOFT
        numberRe.matches(core) -> Kind.SOFT
        !wordRe.matches(core) -> Kind.BREAK
        core == "I" || core == "a" || core == "A" -> Kind.WEAK
        core in CAPS_WORDS -> if (core.length <= 2) Kind.WEAK else Kind.WORD
        romanRe.matches(core) -> Kind.SOFT
        core.length == 1 -> Kind.SOFT
        core.length <= 4 && core.none { it.isLowerCase() } -> Kind.SOFT
        else -> Kind.WORD
    }

    /**
     * Текст → куски по порядку, true — английский. Склеив куски, получаем исходный текст. Маркеры
     * `{pause:N}`/`{prosody}` остаются в тексте куска — их снимает тот, кто озвучивает.
     */
    fun split(text: String, minWords: Int = MIN_WORDS): List<Pair<String, Boolean>> {
        if (text.none { it in 'A'..'Z' || it in 'a'..'z' }) return listOf(text to false)
        val plain = Marks.blank(text)
        val toks = tokenRe.findAll(plain).map { m ->
            val s = m.value
            var a = 0; var b = s.length
            while (a < b && s[a] in EDGE) a++
            while (b > a && s[b - 1] in EDGE) b--
            Tok(m.range.first + a, m.range.first + b, kind(s.substring(a, b)))
        }.toList()
        val need = if (!cyrRe.containsMatchIn(plain)) 1 else minWords
        // отрезки: группа слов между BREAK, от первого до последнего WORD/WEAK; SOFT внутри не рвёт
        val runs = ArrayList<IntRange>()
        var i = 0
        while (i < toks.size) {
            if (toks[i].kind == Kind.BREAK) { i++; continue }
            var first = -1; var last = -1; var strong = 0; var words = 0; var j = i
            while (j < toks.size && toks[j].kind != Kind.BREAK) {
                val k = toks[j].kind
                if (k == Kind.WORD || k == Kind.WEAK) { if (first < 0) first = j; last = j; words++ }
                if (k == Kind.WORD) strong++
                j++
            }
            if (strong > 0 && words >= need) runs += toks[first].start until toks[last].end
            i = j
        }
        if (runs.isEmpty()) return listOf(text to false)
        val out = ArrayList<Pair<String, Boolean>>()
        var from = 0
        for (r in runs) {
            // знак конца предложения сразу за словом — английскому куску: от него зависит интонация
            var end = r.last + 1
            while (end < text.length && text[end] in SENT_END) end++
            if (r.first > from) out += text.substring(from, r.first) to false
            out += text.substring(r.first, end) to true
            from = end
        }
        if (from < text.length) out += text.substring(from) to false
        return out
    }

    /** Кусок, в котором нечего читать русскому движку: только знаки и пробелы, например «», » от кавычек
     * вокруг английского. Маркер ({prosody}, {pause}) — не шум: он действует на следующие сегменты. */
    fun isNoise(text: String): Boolean = text.none { it.isLetterOrDigit() }
}
