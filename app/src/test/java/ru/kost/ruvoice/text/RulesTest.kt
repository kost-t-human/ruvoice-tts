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

    @Test fun thousandsSeparatorOnlyNonBreakingSpace() {
        // review t17 п.3 (Normalizer.kt:99): обычный пробел — не разделитель разрядов.
        assertEquals("Глава один двести читателей", n("Глава 1 200 читателей"))
    }

    @Test fun footnotes() {
        assertEquals("текст дальше", n("текст[1] дальше"))
    }

    @Test fun romanNumerals() {
        assertEquals("в двадцатом веке", n("в xx веке"))
        assertEquals("глава четвёртая", n("глава iv"))
        assertEquals("в лесу и в поле", n("в лесу и в поле"))
        assertEquals("том первый", n("том i"))
    }

    @Test fun romanNumeralsNeedTriggerOrAllCaps() {
        // review t17 п.2 (Normalizer.kt:114-141): без триггера и без CAPS строчное «mix»/«civ» —
        // обычное слово, а не число; заглавный токен в исходнике («XIV») — число и без триггера.
        assertEquals("это был mix двух стилей", n("это был mix двух стилей"))
        assertEquals("Людовик четырнадцать", n("Людовик XIV"))
    }

    @Test fun dates() {
        assertEquals("пятого декабря две тысячи двадцатого года", n("05.12.2020"))
        assertEquals("шестого октября две тысячи двадцать четвёртого года", n("6/10/2024"))
        assertEquals("пятого декабря", n("05.12"))
        assertEquals("двенадцатого мая", n("12.05"))
        assertEquals("три целых пять десятых", n("3.5"))
    }

    @Test fun time() {
        assertEquals("в четырнадцать часов тридцать минут", n("в 14:30"))
        assertEquals("в один час пять минут", n("в 1:05"))
        assertEquals("в двадцать один час", n("в 21:00"))
        assertEquals("семь часов одна минута утра", n("7:01 утра"))
    }

    @Test fun timeWithoutTriggerStaysPlainNumbers() {
        // review t17 п.1 (Normalizer.kt:176): «3:16» без предлога/«утра»/чч:мм:сс — это ссылка
        // на стих («Иоанна 3:16»), а не время, числа читаются по отдельности.
        assertEquals("Иоанна три шестнадцать", n("Иоанна 3:16"))
    }

    @Test fun timeWithSecondsIsAlwaysTriggered() {
        assertEquals("двадцать три часа пятьдесят девять минут одна секунда", n("23:59:01"))
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

    @Test fun ordinalRoundThousands() {
        // review t17 п.5 (Normalizer.kt:80): у ordinalStems нет основы под 0, круглая тысяча
        // «2000» проваливалась в cardinal-фолбэк вместо «двухтысячный».
        assertEquals("двухтысячный", Normalizer.ordinal(2000, "й"))
        assertEquals("в тысяча девятьсот девяностом – двухтысячном годах", n("в 1990–2000 гг."))
    }

    @Test fun cardinalGenitiveSuffixes() {
        assertEquals("в пяти километрах", n("в 5-ти километрах"))
        assertEquals("двух", n("2-ух"))
        assertEquals("трёх", n("3-ёх"))
        assertEquals("двадцати пяти", n("25-ти"))
    }

    @Test fun cardinalGenitiveSuffixBeforeUnitAbbreviation() {
        // review t17 п.4 (Normalizer.kt:228-284): суффикс числа перед сокращением единицы —
        // единица тоже должна раскрыться, а не остаться «км».
        assertEquals("в пяти километров от города", n("в 5-ти км от города"))
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
