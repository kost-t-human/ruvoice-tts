package ru.kost.ruvoice.text

/**
 * Эхо ввода и удаления от экранного чтеца: буква в окружении служебных слов — «Удаление заглавная Р»,
 * «Удаление Р заглавная», «Р удалено» (Jieshuo msg_deleted «%s удалено»), «Р Заглавная буква Р»
 * (upper_case_format «%s Заглавная буква %s»). Модель читает голое «Р» как «ррр», поэтому букву
 * называем: «удаление, +ээр, заглавная».
 *
 * Перехват уверенный: весь запрос — одна буква (можно повторённая, как в шаблоне Jieshuo) и слова из
 * [ACTION] и [CASE], больше ничего. «Удаление в» тогда — буква «вэ», а не предлог: других слов нет.
 * Запрос из одной голой буквы или «Заглавная П» разбирает Pipeline.loneLetter, здесь — всё, где есть
 * слово-действие. Строки Jieshuo — res/values-ru/strings.xml в github.com/nirenr/jieshuo; «Удаление»
 * там нет — его, судя по всему, говорит клавиатура или код чтеца, поэтому набор слов с запасом.
 */
object LetterEcho {
    /** Что случилось с буквой: удаление, ввод, выделение. */
    private val ACTION = setOf(
        "удаление", "удалено", "удалена", "удален", "удалить", "удалил", "удалила", "стерто", "стерт", "стереть",
        "backspace", "ввод", "введено", "вставлено", "вставка", "выделено", "выбрано", "символ",
    )
    /** Регистр буквы: идут после имени буквы, как в Pipeline.loneLetter («п+э, заглавная»). */
    private val CASE = setOf("заглавная", "заглавный", "прописная", "прописной", "большая", "строчная", "маленькая",
        "буква", "верхний", "регистр")

    private val tokenRe = Regex("[^\\s,.:;!?()«»\"—–-]+")
    private val junk = Regex("[\\s,.:;!?()«»\"—–-]*")

    /** Запрос → текст с именем буквы; null — не эхо буквы, читать как есть. */
    fun rewrite(text: String): String? {
        val toks = tokenRe.findAll(text).toList()
        if (toks.size < 2) return null
        // между словами — только пробелы и знаки
        var prev = 0
        for (t in toks) { if (!junk.matches(text.substring(prev, t.range.first))) return null; prev = t.range.last + 1 }
        if (!junk.matches(text.substring(prev))) return null
        val letters = toks.filter { it.value.length == 1 && it.value[0].isLetter() }
        if (letters.isEmpty() || letters.any { !it.value.equals(letters[0].value, ignoreCase = true) }) return null
        val words = toks.filter { it !in letters }.map { it.value.lowercase().replace('ё', 'е') }
        if (words.any { it !in ACTION && it !in CASE } || words.none { it in ACTION }) return null
        // имя как у голой буквы («в+э.», «— +ы» — иначе «вы», «пы»); точку перед следующей частью заменяет запятая
        val name = Abbrev.loneLetterName(letters[0].value[0]) ?: return null
        val first = letters[0].range.first
        val before = toks.filter { it !in letters && it.range.first < first }.map { it.value.lowercase() }
        val after = toks.filter { it !in letters && it.range.first > first }.map { it.value.lowercase() }
        val pre = before.filter { it.replace('ё', 'е') in ACTION }
        val case = (before + after).filter { it.replace('ё', 'е') in CASE }.distinct()
        val post = after.filter { it.replace('ё', 'е') in ACTION }
        val tail = listOfNotNull(case.joinToString(" ").ifEmpty { null }, post.joinToString(" ").ifEmpty { null })
        return listOfNotNull(pre.joinToString(" ").ifEmpty { null }, if (tail.isEmpty()) name else name.trimEnd('.'), *tail.toTypedArray())
            .joinToString(", ")
    }
}
