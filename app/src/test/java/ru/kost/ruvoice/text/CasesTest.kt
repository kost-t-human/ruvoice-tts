package ru.kost.ruvoice.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import ru.kost.ruvoice.TestData

/** Тесты на падеж числительного по предлогу-триггеру и по окончанию соседнего слова (task 19b). */
class CasesTest {
    private fun n(s: String) = Normalizer.numbers(s)
    private fun p(s: String) = Normalizer.prepare(s, TestData.data().allowed)

    @Test fun genitiveByTrigger() {
        assertEquals("около пятисот рублей", n("около 500 рублей"))
        assertEquals("более пяти лет", n("более 5 лет"))
        assertEquals("более чем ста лет", n("более чем 100 лет"))
        assertEquals("у трёх сестёр", n("у 3 сестёр"))
    }

    @Test fun genitiveRangeOtDo() {
        assertEquals("от одного до пяти дней", n("от 1 до 5 дней"))
    }

    @Test fun dativeByTrigger() {
        assertEquals("к двадцати пяти годам", n("к 25 годам"))
    }

    @Test fun instrumentalRangeMezhdu() {
        assertEquals("между пятью и десятью", n("между 5 и 10"))
    }

    @Test fun instrumentalByEnding() {
        assertEquals("с пятьюстами рублями", n("с 500 рублями"))
    }

    @Test fun prepositionalByEnding() {
        assertEquals("в пяти случаях", n("в 5 случаях"))
    }

    @Test fun prepositionalByTrigger() {
        assertEquals("при двадцати градусах", n("при 20 градусах"))
        assertEquals("о трёх мушкетёрах", n("о 3 мушкетёрах"))
    }

    @Test fun rangeSPoWithoutMonth() {
        assertEquals("с пяти по десять", n("с 5 по 10"))
    }

    @Test fun rangeSPoWithMonth() {
        assertEquals("с пятого по десятое мая", n("с 5 по 10 мая"))
    }

    @Test fun temBoleeIsNotTrigger() {
        assertEquals("тем более пять человек", n("тем более 5 человек"))
    }

    @Test fun accusativeFeminineEndingOne() {
        assertEquals("одну книгу", n("1 книгу"))
        assertEquals("двадцать одну минуту", n("21 минуту"))
    }

    @Test fun nominativeFeminineTwo() {
        assertEquals("две книги", n("2 книги"))
        assertEquals("два стола", n("2 стола"))
    }

    @Test fun unitAbbreviationStaysCardinal() {
        assertEquals("в пять километров", n("в 5 км"))
    }

    @Test fun yearWithGenitiveTriggerStaysOrdinal() {
        assertEquals("до тысяча девятьсот семнадцатого года", n("до 1917 года"))
    }

    @Test fun noTriggerStaysAsBefore() {
        assertEquals("дом пять", n("дом 5"))
    }

    @Test fun casesThroughPrepare() {
        assertEquals("около пятисот рублей", p("около 500 рублей"))
        assertEquals("в пяти случаях", p("в 5 случаях"))
    }

    // Fix round 1: «-ьми» — неправильный творительный («детьми», «людьми»), оканчивается на «и»,
    // но это не повод склонять числительное «два/двадцать два» в «две/двадцать две».
    @Test fun nominativeFeminineTwoDoesNotMatchIrregularInstrumentalTail() {
        assertEquals("с два детьми", n("с 2 детьми"))
        assertEquals("были заняты два детьми", n("были заняты 2 детьми"))
        assertEquals("двадцать два людьми", n("22 людьми"))
    }

    // Fix round 1: «день + месяц» без точек («к 1 сентября») — не дата в формате dd.mm, cases()
    // не должен путать её с обычным «к 1» и давать дательный «одному»; правильный порядковый
    // разбор такой даты вне рамок задачи (ponytail-потолок), число остаётся как раньше.
    @Test fun dayAndMonthWithoutDotsStaysUntouched() {
        assertEquals("к один сентября", n("к 1 сентября"))
        assertFalse(n("к 1 сентября").contains("одному"))
        assertEquals("к один июля", n("к 1 июля"))
    }
}
