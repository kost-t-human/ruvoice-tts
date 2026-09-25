package ru.kost.ruvoice.text

/**
 * Эхо ввода и удаления от экранного чтеца: буква в окружении служебных слов — «Удаление заглавная Р»,
 * «Удаление Р заглавная», «Р удалено» (Jieshuo msg_deleted «%s удалено»), «Р Заглавная буква Р»
 * (upper_case_format «%s Заглавная буква %s»). Модель читает голое «Р» как «ррр», поэтому букву
 * называем: «удаление, +ээр, заглавная».
 *
 * Перехват уверенный: весь запрос — одна буква (можно повторённая, как в шаблоне Jieshuo) или один знак
 * и слова из [ACTION], [CASE], [FILLER], больше ничего; хотя бы одно слово — действие. «Удаление в» тогда — буква «вэ», а не предлог: других слов нет.
 * Запрос из одной голой буквы или «Заглавная П» разбирает Pipeline.loneLetter, здесь — всё, где есть
 * слово-действие. Строки Jieshuo — res/values-ru/strings.xml в github.com/nirenr/jieshuo; «Удаление»
 * там нет — его, судя по всему, говорит клавиатура или код чтеца, поэтому набор слов с запасом. Шаблоны
 * TalkBack (github.com/google/talkback, utils и talkback values-ru): «Текст %1$s удален.», «Вырезан текст
 * "%1$s"», «Вставлен текст "%1$s"», «Выделен текст "%1$s"», «Отменено выделение текста "%1$s"»,
 * «Буква "d" удалена», «прописная буква %1$s.».
 *
 * Удалённый знак называется так же: «Удаление ,» — «удаление, запятая» (SymbolNames), иначе знак
 * пропадал и оставалось одно «удаление». Знак считается, только если стоит отдельно или в кавычках:
 * точка в конце «Текст удален.» — конец фразы, а не удалённая точка.
 */
object LetterEcho {
    /** Что случилось с буквой: удаление, ввод, выделение. */
    private val ACTION = setOf(
        "удаление", "удалено", "удалена", "удален", "удалить", "удалил", "удалила", "стерто", "стерт", "стереть",
        "backspace", "ввод", "введено", "вставлено", "вставлен", "вставка", "вырезан", "вырезано", "выделено", "выделен",
        "выбрано", "отменено", "выделение", "символ",
    )
    /** Регистр буквы: идут после имени буквы, как в Pipeline.loneLetter («п+э, заглавная»). */
    private val CASE = setOf("заглавная", "заглавный", "прописная", "прописной", "большая", "строчная", "маленькая",
        "буква", "верхний", "регистр")
    /** Слова шаблонов TalkBack вокруг буквы, сами по себе ничего не значат. */
    private val FILLER = setOf("текст", "текста")

    /** Обычная пунктуация, которую SymbolNames не называет (в тексте это паузы); имена — как у TalkBack
     * (utils/src/main/res/values-ru/strings_symbols.xml), чтобы голос не спорил с чтецом. */
    private val PUNCT_NAMES = mapOf(',' to "запятая", '.' to "точка", '!' to "восклицательный знак", '?' to "вопросительный знак",
        ':' to "двоеточие", ';' to "точка с запятой", '"' to "кавычка", '\'' to "апостроф", '-' to "дефис", '—' to "длинное тире",
        '–' to "короткое тире", '…' to "многоточие", '(' to "открывающая скобка", ')' to "закрывающая скобка",
        '«' to "открывающая кавычка", '»' to "закрывающая кавычка")

    private const val QUOTES = "\"«»“”„'‘’"
    private const val PUNCT = ",.:;!?"

    private sealed class Tok {
        class Word(val text: String) : Tok()
        class Letter(val c: Char) : Tok()
        class Symbol(val c: Char) : Tok()
    }

    private fun norm(w: String) = w.lowercase().replace('ё', 'е')

    /** Запрос → текст с именем буквы или знака; null — не эхо ввода, читать как есть. */
    fun rewrite(text: String): String? {
        val toks = ArrayList<Tok>()
        for (raw in text.split(Regex("\\s+")).filter { it.isNotEmpty() }) {
            val q = raw.trim { it in QUOTES }
            if (q.isEmpty()) { if (raw.length == 1) toks += Tok.Symbol(raw[0]); continue }   // удалённая кавычка
            if (q.length == 1 && !q[0].isLetterOrDigit()) { toks += Tok.Symbol(q[0]); continue }
            val core = q.trim { it in PUNCT || it in QUOTES }
            when {
                core.isEmpty() -> return null
                core.length == 1 && core[0].isLetter() -> toks += Tok.Letter(core[0])
                norm(core).let { it in ACTION || it in CASE || it in FILLER } -> toks += Tok.Word(core.lowercase())
                else -> return null
            }
        }
        val subjects = toks.filter { it !is Tok.Word }
        val words = toks.filterIsInstance<Tok.Word>()
        if (subjects.isEmpty() || words.none { norm(it.text) in ACTION }) return null
        val name = when {
            subjects.all { it is Tok.Letter && it.c.equals((subjects[0] as Tok.Letter).c, ignoreCase = true) } ->
                // имя как у голой буквы («в+э.», «— +ы» — иначе «вы», «пы»); точку перед следующей частью заменяет запятая
                Abbrev.loneLetterName((subjects[0] as Tok.Letter).c)
            subjects.size == 1 && subjects[0] is Tok.Symbol -> (subjects[0] as Tok.Symbol).c.let { PUNCT_NAMES[it] ?: SymbolNames.of(it) }
            else -> null
        } ?: return null
        val first = toks.indexOf(subjects[0])
        fun joined(ws: List<Tok>) = ws.filterIsInstance<Tok.Word>().map { it.text }.filter { norm(it) !in CASE }.joinToString(" ").ifEmpty { null }
        val case = words.map { it.text }.filter { norm(it) in CASE }.distinct().joinToString(" ").ifEmpty { null }
        val tail = listOfNotNull(case, joined(toks.subList(first + 1, toks.size)))
        return listOfNotNull(joined(toks.subList(0, first)), if (tail.isEmpty()) name else name.trimEnd('.'), *tail.toTypedArray())
            .joinToString(", ")
    }
}
