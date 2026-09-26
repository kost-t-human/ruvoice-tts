package ru.kost.ruvoice.text

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.kost.ruvoice.TestData

/** Фразы из книг, прочитанные неверно (на слух 26.09.2026), полным prepare(). */
class BookPhrasesTest {
    private val allowed = TestData.data().allowed
    private fun p(s: String) = Normalizer.prepare(s, allowed)

    @Test fun dimensions() {
        assertEquals("площадь участка составляет пять на десять метров, пять на десять, а объем – тридцать кубических метров",
            p("Площадь участка составляет 5х10 м (5 на 10), а объем — 30 м³"))
        assertEquals("три на четыре на пять", p("3x4x5"))
        assertEquals("два умножить на три", p("2×3"))
    }

    @Test fun initialsNotTakimObrazom() {
        assertEquals("это таким образом решено.", p("Это т. о. решено."))
        assertEquals("таким образом, всё.", p("Т. о., всё."))
        assertEquals(false, p("Артемьев Т. О. пришёл.").contains("таким"))
        assertEquals(false, p("Т.О. Артемьев").contains("таким"))
        assertEquals("артемьев т. е. пришёл.", p("Артемьев Т. Е. пришёл."))
        assertEquals("иванов т. к. пришёл.", p("Иванов Т.К. пришёл."))
        assertEquals("петров т. н. сказал.", p("Петров Т. Н. сказал."))
        assertEquals("н. э. бауман", p("Н. Э. Бауман"))
        assertEquals("петров и т. д. смирнов", p("Петров и Т. Д. Смирнов"))
        assertEquals("и так далее. и тому подобное, всё", p("И т. д. И т. п., всё"))
        assertEquals("то есть всё.", p("Т. е. всё."))
        assertEquals("так как поздно.", p("Т.к. поздно."))
        assertEquals("так называемый друг.", p("Т. н. друг."))
        assertEquals("в пятом веке нашей эры", p("в V в. н. э."))
        assertEquals("четвёртый век до нашей эры", p("IV в. до Н. Э."))
    }

    @Test fun telOnlyBeforeNumber() {
        assertEquals("лежало множество тел.", p("лежало множество тел."))
        assertEquals(true, p("Звоните по тел. 8 012 345-67-89").startsWith("звоните по телефон "))
        assertEquals(true, p("тел.: +7 012 345 67 89").startsWith("телефон"))
    }

    @Test fun ellipsisGluedToWord() {
        assertEquals("ну… ладно.", p("Ну…ладно."))
        assertEquals("ну… ладно.", p("Ну...ладно."))
    }
}
