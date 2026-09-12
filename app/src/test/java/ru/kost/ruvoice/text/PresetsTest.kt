package ru.kost.ruvoice.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.kost.ruvoice.TestData

/** Новые предустановки нормализатора (task 28 п.6): сокращения, единицы, символы. */
class PresetsTest {
    private fun n(s: String) = Normalizer.numbers(s)
    private fun p(s: String) = Normalizer.prepare(s, TestData.data().allowed)

    @Test fun abbreviations() {
        assertEquals("господин", n("г-н"))
        assertEquals("госпожа", n("г-жа"))
        assertEquals("нашей эры", n("н. э."))
        assertEquals("нашей эры", n("н.э."))
        assertEquals("до нашей эры", n("до н. э."))
        assertEquals("таким образом", n("т. о."))
        assertEquals("количество", n("кол-во"))
        assertEquals("железнодорожный", n("ж/д"))
        assertEquals("бывший в употреблении", n("б/у"))
        assertEquals("около пятисот человек", n("ок. 500 человек"))
        assertEquals("квартира пять", n("кв. 5"))
        assertEquals("телефон", n("тел."))
        assertEquals("максимум", n("макс."))
    }

    @Test fun countUnits() {
        assertEquals("пять штук", n("5 шт"))
        // точка в конце предложения остаётся, как у остальных единиц (final-fix п.5)
        assertEquals("пять штук.", n("5 шт."))
        assertEquals("две штуки", n("2 шт"))
        assertEquals("три человека", n("3 чел"))
        assertEquals("три человека в ряд", n("3 чел. в ряд"))
        assertEquals("семь экземпляров", n("7 экз"))
    }

    @Test fun squareAndCubicUnits() {
        assertEquals("пять квадратных метров", n("5 м²"))
        assertEquals("один квадратный километр", n("1 км²"))
        assertEquals("три кубических метра", n("3 м³"))
        assertEquals("два квадратных сантиметра", n("2 см²"))
        assertEquals("десять кубических сантиметров", n("10 см³"))
    }

    @Test fun dollarAbbreviation() {
        assertEquals("пять долларов", n("5 долл."))
        assertEquals("двадцать один доллар", n("21 долл."))
    }

    @Test fun romanCenturyAbbreviation() {
        assertTrue(p("в XX в.").contains("двадцатый век"))
        // первый токен диапазона остаётся количественным — принятый потолок
        assertEquals("девятнадцать–двадцатые века", n("XIX–XX вв."))
    }

    @Test fun symbols() {
        assertEquals("плюс-минус пять", p("±5"))
        assertEquals("примерно сто человек", p("≈ 100 человек"))
        assertEquals("джонсон и джонсон", p("Джонсон & Джонсон"))
    }
}
