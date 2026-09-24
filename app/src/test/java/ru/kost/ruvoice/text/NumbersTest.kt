package ru.kost.ruvoice.text

import org.junit.Assert.assertEquals
import org.junit.Test

class NumbersTest {
    private fun n(s: String) = Normalizer.numbers(s)

    @Test fun units() {
        assertEquals("ноль", n("0"))
        assertEquals("один", n("1"))
        assertEquals("девятнадцать", n("19"))
        assertEquals("сорок два", n("42"))
        assertEquals("сто", n("100"))
        assertEquals("двести пятьдесят один", n("251"))
    }

    @Test fun thousandsFeminine() {
        assertEquals("одна тысяча", n("1000"))
        assertEquals("две тысячи пятьсот", n("2500"))
        assertEquals("пять тысяч", n("5000"))
        assertEquals("двадцать одна тысяча", n("21000"))
        assertEquals("тысяча девятьсот семнадцатый год", n("1917 год"))
        assertEquals("в тысяча девятьсот семнадцатом году", n("в 1917 году"))
        assertEquals("с две тысячи первого года", n("с 2001 года"))
        assertEquals("одна тысяча девятьсот семнадцать", n("1917"))
        assertEquals("в две тысячи двадцать втором году", n("в 2022 году"))
    }

    @Test fun millionsAndBillions() {
        assertEquals("один миллион", n("1000000"))
        assertEquals("два миллиона триста тысяч", n("2300000"))
        assertEquals("пять миллиардов", n("5000000000"))
    }

    @Test fun decimalsAndPercent() {
        assertEquals("три целых пять десятых", n("3.5"))
        assertEquals("три целых пять десятых", n("3,5"))
        assertEquals("одна целая двадцать пять сотых", n("1,25"))
        assertEquals("пятьдесят процентов", n("50%"))
        assertEquals("один процент", n("1%"))
        assertEquals("двадцать два процента", n("22%"))
    }

    @Test fun negativeAndSpecial() {
        assertEquals("минус пять", n("-5"))
        assertEquals("номер семь", n("№7"))
        assertEquals("параграф три", n("§3"))
        assertEquals("в две тысячи двадцать четвёртом году", n("в 2024-м году"))
        assertEquals("пятого мая", n("5-го мая"))
        assertEquals("третий", n("3-й"))
        assertEquals("второй", n("2-й"))
        assertEquals("первое", n("1-е"))
    }

    @Test fun textAroundIsKept() {
        assertEquals("глава двенадцатая, страница три", n("глава 12, страница 3"))
        assertEquals("без чисел", n("без чисел"))
    }

    @Test fun longNumberDigitByDigit() {
        assertEquals(
            "три три ноль ноль ноль ноль ноль ноль ноль ноль ноль ноль ноль ноль",
            n("33000000000000")
        )
    }

    @Test fun rangesAndCompounds() {
        assertEquals("одна тысяча девятьсот сорок один-одна тысяча девятьсот сорок пять", n("1941-1945"))
        assertEquals("десять-пятнадцать минут", n("10-15 минут"))
        assertEquals("а минус пять", n("а -5"))
        assertEquals("пятиметровый", n("5-метровый"))
        assertEquals("трёхэтажный", n("3-этажный"))
        assertEquals("стодвадцатиоднолетний юбилей", n("121-летний юбилей"))
        assertEquals("тридцатипроцентная надбавка", n("30%-ная надбавка"))
        assertEquals("два плюс три равно пять", n("2 + 3 = 5"))
    }

    @Test fun spacedThousandsChain() {
        assertEquals("восемь миллиардов", n("8 000 000 000"))
        assertEquals("один миллиард двести тысяч", n("1 000 200 000"))
        assertEquals("пять тысяч", n("5 000"))
        assertEquals("одна тысяча двести рублей", n("1 200 рублей"))
        assertEquals("глава один двести читателей", n("глава 1 200 читателей"))
    }

    @Test fun isoDate() {
        assertEquals("двенадцатого мая две тысячи двадцать четвёртого года", n("2024-05-12"))
        assertEquals("первого января тысяча девятьсот сорок первого года", n("1941-01-01"))
        // не дата: месяц 13
        assertEquals("две тысячи двадцать четыре-тринадцать-двенадцать", n("2024-13-12"))
    }

    @Test fun unaryPlus() {
        assertEquals("плюс пятнадцать процентов", n("+15%"))
        assertEquals("плюс пять миллиардов", n("+5 000 000 000"))
        assertEquals("рост плюс три", n("рост +3"))
        assertEquals("пять плюс три", n("5 + 3"))
        assertEquals("пять плюс три", n("5+3"))
    }
}
