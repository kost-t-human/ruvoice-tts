package ru.kost.ruvoice.text

import org.junit.Assert.assertEquals
import org.junit.Test

/** Тесты на новые правила нормализации (задача t17), TDD: ассерты написаны до реализации. */
class RulesTest {
    private fun n(s: String) = Normalizer.numbers(s)

    @Test fun thousandsSeparators() {
        assertEquals("двенадцать миллионов триста сорок пять тысяч шестьсот семьдесят восемь человек",
            n("12 345 678 человек"))
    }

    @Test fun footnotes() {
        assertEquals("текст дальше", n("текст[1] дальше"))
    }

    @Test fun romanNumerals() {
        assertEquals("в двадцатом веке", n("в xx веке"))
        assertEquals("глава четвёртая", n("глава iv"))
        assertEquals("людовик четырнадцать", n("людовик xiv"))
        assertEquals("в лесу и в поле", n("в лесу и в поле"))
        assertEquals("том первый", n("том i"))
    }

    @Test fun dates() {
        assertEquals("пятого декабря две тысячи двадцатого года", n("05.12.2020"))
        assertEquals("шестого октября две тысячи двадцать четвёртого года", n("6/10/2024"))
        assertEquals("пятого декабря", n("05.12"))
        assertEquals("двенадцатого мая", n("12.05"))
        assertEquals("три целых пять десятых", n("3.5"))
    }

    @Test fun time() {
        assertEquals("четырнадцать часов тридцать минут", n("14:30"))
        assertEquals("один час пять минут", n("1:05"))
        assertEquals("двадцать один час", n("21:00"))
        assertEquals("семь часов одна минута", n("7:01"))
    }

    @Test fun yearsWithG() {
        assertEquals("в тысяча девятьсот девяностом году", n("в 1990 г."))
        assertEquals("с тысяча девятьсот девяностого года", n("с 1990 г."))
        assertEquals("тысяча девятьсот девяностый год", n("1990 г."))
        assertEquals("в тысяча девятьсот девяностых годах", n("в 1990-х гг."))
        assertEquals("в тысяча девятьсот сорок первом – тысяча девятьсот сорок пятом годах",
            n("в 1941–1945 гг."))
    }

    @Test fun ordinalNinetiesPlural() {
        assertEquals("тысяча девятьсот девяностых", Normalizer.ordinal(1990, "х"))
    }

    @Test fun cardinalGenitiveSuffixes() {
        assertEquals("в пяти километрах", n("в 5-ти километрах"))
        assertEquals("двух", n("2-ух"))
        assertEquals("трёх", n("3-ёх"))
        assertEquals("двадцати пяти", n("25-ти"))
    }

    @Test fun units() {
        assertEquals("пять килограммов", n("5 кг"))
        assertEquals("два километра", n("2 км"))
        assertEquals("двадцать один грамм", n("21 г"))
        assertEquals("три километра в час", n("3 км/ч"))
        assertEquals("одна минута", n("1 мин"))
        assertEquals("две минуты", n("2 мин"))
        assertEquals("одна целая пять десятых литра", n("1,5 л"))
        assertEquals("пять мая", n("5 мая"))
    }

    @Test fun sectionNumbers() {
        assertEquals("пункт один точка два точка три", n("пункт 1.2.3"))
        assertEquals("три целых пять десятых", n("3.5"))
    }

    @Test fun fractionsSlash() {
        assertEquals("шесть дробь десять", n("6/10"))
        assertEquals("один дробь два", n("1/2"))
    }

    @Test fun abbreviations() {
        assertEquals("и так далее", n("и т. д."))
        assertEquals("то есть", n("т.е."))
        assertEquals("на странице пять", n("на стр. 5"))
        assertEquals("смотри рисунок три", n("см. рис. 3"))
        assertEquals("пять тысяч рублей", n("5 тыс. руб."))
        assertEquals("два миллиона", n("2 млн"))
        assertEquals("двадцать одна тысяча", n("21 тыс."))
    }

    @Test fun homoglyphs() {
        assertEquals("проблема", Normalizer.latin("прoблема"))
        assertEquals("айфон", Normalizer.latin("iphone"))
    }
}
