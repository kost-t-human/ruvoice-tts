package ru.kost.ruvoice

import org.junit.Assert.assertEquals
import org.junit.Test

class BookAccentTest {
    private fun plus(src: String, vararg acc: String) = BookAccent.apply(src, BookAccent.edits(src, acc.toList()), BookAccent.Mode.PLUS)

    @Test fun stressBackIntoSourceWords() {
        // регистр и знаки исходника; число — тем, что прочтёт модель; «он», «и» односложные — без знака;
        // «все» → «всё», «Елка» → «Ёлка» без знака, «Всё» уже с ё — не трогаем
        assertEquals("«Х+агрид», — сказ+ал он в т+ысяча девятьс+от семн+адцатом г+оду, и всё. Всё Ёлка?",
            plus("«Хагрид», — сказал он в 1917 году, и все. Всё Елка?",
                "х+агрид, — сказ+ал +он в т+ысяча девятьс+от семн+адцатом г+оду,", "+и всё. всё +ёлка?"))
    }

    @Test fun hyphenAndSeveralStresses() {
        // «кт+о-то»: в слове две гласные — знак ставится
        assertEquals("с+еверо-з+апад, кт+о-то", plus("северо-запад, кто-то", "с+еверо-з+апад, кт+о-то"))
    }

    @Test fun rewrittenWordsTakeModelReading() {
        // замена со знаками вокруг: скобки и точка исходника остаются, заглавная — как была
        assertEquals("(Д+октор) В+атсон, то есть Холмс. В т+ысяча девятьс+от семн+адцатом г+оду.", plus("(Д-р) Ватсон, т. е. Холмс. В 1917 г.", "д+октор в+атсон, т+о +есть х+олмс.", "в т+ысяча девятьс+от семн+адцатом г+оду."))
        assertEquals("В две т+ысячи три. В+осемьдесят два дня.", plus("В 2003. 82 дня.", "в две т+ысячи тр+и.", "в+осемьдесят два дн+я."))
        assertEquals("Дом (н+омер пять)", plus("Дом (№5)", "д+ом н+омер п+ять"))
        assertEquals("в пять проце́нтов", BookAccent.apply("в 5%", BookAccent.edits("в 5%", listOf("в п+ять проц+ентов")), BookAccent.Mode.ACUTE))
    }

    @Test fun fb2InPlaceKeepsTagsAndEntities() {
        val xml = """<?xml version="1.0" encoding="windows-1251"?><FictionBook><description><p>Аннотация</p></description>
            <body><title><p>Глава</p></title><p>Шёл <emphasis>Хагрид</emphasis>&#160;&mdash; и все, 5 &amp; 10.</p><p>Раз <emphasis>1</emphasis>0 два</p><empty-line/><p/></body></FictionBook>"""
        val acc = mapOf("Глава" to "глав+а", "Шёл Хагрид\u00a0— и все, 5 & 10." to "шёл х+агрид — +и всё, п+ять +и д+есять.", "Раз 10 два" to "р+аз д+есять дв+а")
        var calls = 0
        val out = BookAccent.fb2(xml, BookAccent.Mode.ACUTE, false, true, { _, _ -> true }) { calls++; listOf(acc.getValue(it)) }!!
        assertEquals(3, calls)   // «1</emphasis>0» — замена поперёк тега, абзац без правок
        assertEquals("""<?xml version="1.0" encoding="utf-8"?><FictionBook><description><p>Аннотация</p></description>
            <body><title><p>Глава́</p></title><p>Шёл <emphasis>Ха́грид</emphasis>&#160;&mdash; и всё, пять и де́сять.</p><p>Раз <emphasis>1</emphasis>0 два</p><empty-line/><p/></body></FictionBook>""", out)
        assertEquals(null, BookAccent.fb2(xml, BookAccent.Mode.PLUS, false, true, { i, _ -> i < 1 }) { listOf(acc.getValue(it)) })
    }

    @Test fun repeatedWordDoesNotStealAnchor() {
        // раскрытое число совпадает со словом дальше по тексту: опора — не оно, текст между не теряется
        assertEquals("Д+октор пять раз, пять.", plus("Д-р 5 раз, пять.", "д+октор п+ять р+аз, п+ять."))
        assertEquals("Два час+а, два. В два.", plus("2 часа, два. В 2.", "дв+а час+а, дв+а.", "в дв+а."))
    }

    @Test fun respellingKeepsBookLetters() {
        // орфоэпия из замен: буквы книги, ударение из прочтения; склейка «н+аногу» раскладывается по словам
        assertEquals("Нар+очно ког+о-то, гм, н+а ногу.", plus("Нарочно кого-то, гм, на ногу.", "нар+ошно ков+о-то, гмм, н+аногу."))
        // твёрдое [э]: без галочки буквы книги, с галочкой — «э» из прочтения
        assertEquals("Каф+е закр+ыто", plus("Кафе закрыто", "каф+э закр+ыто"))
        assertEquals("Каф+э закр+ыто", BookAccent.apply("Кафе закрыто", BookAccent.edits("Кафе закрыто", listOf("каф+э закр+ыто"), hardE = true), BookAccent.Mode.PLUS))
        // другое слово, а не произношение, — подставляется
        assertEquals("Ул+ица Лен+ина", plus("Ул. Ленина", "ул+ица лен+ина"))
    }

    @Test fun gluedPairDoesNotStealNextWord() {
        // «Манька» Казакова: Stress склеил «н+ебыло», следующее «Было» не должно уйти к «было.» этой же фразы
        assertEquals("Вдал+и н+е было. Б+ыло т+емно.", plus("Вдали не было. Было темно.", "вдал+и н+ебыло.", "б+ыло т+емно."))
    }

    @Test fun abbreviationsKeptOnRequest() {
        // раскрытая аббревиатура отдельным пропуском: по галочке остаётся как в книге («штаб» односложное — без знака)
        val src = "Штаб КП рядом. Штаб USA рядом."
        val acc = listOf("шт+аб к+а п+э р+ядом.", "шт+аб +уса р+ядом.")
        assertEquals("Штаб Ка пэ р+ядом. Штаб +Уса р+ядом.", plus(src, *acc.toTypedArray()))
        assertEquals("Штаб КП р+ядом. Штаб USA р+ядом.", BookAccent.apply(src, BookAccent.edits(src, acc, abbr = false), BookAccent.Mode.PLUS))
    }

    @Test fun txtWrappedAsFb2() {
        val x = BookAccent.source("Раз & два\n\n<Три>\n".toByteArray(), "Книга")
        assertEquals(true, "<book-title>Книга</book-title>" in x)
        assertEquals(true, "<p>Раз &amp; два</p>\n<p>&lt;Три&gt;</p>" in x)
    }
}
