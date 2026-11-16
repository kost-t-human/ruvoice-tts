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

    @Test fun decimalBeforeAgreeingNoun() {
        // дробная часть не согласуется с существительным как отдельное число: «1,2 тысячи» было «один,две тысячи»
        assertEquals("продано одна целая две десятых тысячи книг", n("продано 1,2 тысячи книг"))
        assertEquals("продано одна целая две десятых тысячи книг", n("продано 1.2 тысячи книг"))
        assertEquals("одна целая двадцать две сотых тысячи", n("1.22 тысячи"))
        assertEquals("одна целая две сотых тысячи", n("1,02 тысячи"))
        assertEquals("двадцать одна целая две десятых тысячи", n("21,2 тысячи"))
        assertEquals("три целых две десятых тысячи", n("3,2 тысячи"))
        assertEquals("ноль целых две десятых секунды", n("0,2 секунды"))
        assertEquals("перевезли одну целую одну десятую тонну", n("перевезли 1,1 тонну"))
        // целое число и «полторы» — как было
        assertEquals("две тысячи книг", n("2 тысячи книг"))
        assertEquals("двадцать две секунды", n("22 секунды"))
        assertEquals("одну книгу", n("1 книгу"))
        assertEquals("полторы тысячи", n("1,5 тысячи"))
        assertEquals("две целых две десятых миллиона", n("2,2 миллиона"))
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
        // номер перед словом (10.10.2026); число на 1 в середине фразы — счёт
        assertEquals("двадцать вторая глава", n("22 глава"))
        assertEquals("первая глава", n("1 глава"))
        assertEquals("читаю пятый том", n("читаю 5 том"))
        assertEquals("здесь двадцать одна глава", n("здесь 21 глава"))
        assertEquals("двадцать две главы", n("22 главы"))
    }

    @Test fun longNumberDigitByDigit() {
        assertEquals(
            "три три ноль ноль ноль ноль ноль ноль ноль ноль ноль ноль ноль ноль",
            n("33000000000000")
        )
    }

    @Test fun trillionsCardinal() {
        assertEquals("один триллион двадцать шесть миллиардов", Normalizer.cardinal(1_026_000_000_000))
        assertEquals("одного триллиона двадцати шести миллиардов", Declension.cardinal(1_026_000_000_000, Case.GEN))
        assertEquals("девять квинтиллионов двести двадцать три квадриллиона триста семьдесят два триллиона тридцать шесть " +
            "миллиардов восемьсот пятьдесят четыре миллиона семьсот семьдесят пять тысяч восемьсот семь", Normalizer.cardinal(Long.MAX_VALUE))
        assertEquals("двумя квадриллионами пятьюстами триллионами", Declension.cardinal(2_500_000_000_000_000, Case.INS))
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

    @Test fun weatherUnitsFromUserReport() {
        // жалоба 06.10.2026: «м/сек» читалось «м сек», «мм. рт. ст.» — «ээр тэ ст»
        val allowed = "_~|!+,-.:;?абвгдежзийклмнопрстуфхцчшщъыьэюяё–… "
        assertEquals("ветер до четырнадцати метров в секунду.", Normalizer.prepare("Ветер до 14 м/сек.", allowed))
        assertEquals("давление семьсот шестьдесят миллиметров ртутного столба.", Normalizer.prepare("Давление 760 мм. рт. ст.", allowed))
        assertEquals("давление семьсот шестьдесят миллиметров ртутного столба, ветер слабый.",
            Normalizer.prepare("Давление 760 мм рт.ст., ветер слабый.", allowed))
        assertEquals("скорость двадцать километров в час.", Normalizer.prepare("Скорость 20 км/час.", allowed))
        // «гр. С» с заглавной — Цельсия; со строчной «с» — граммы и предлог
        assertEquals("температура пять градусов цельсия.", Normalizer.prepare("Температура 5 гр. С.", allowed))
        assertEquals("при минус двадцати градусах цельсия, днём один градус цельсия.", Normalizer.prepare("При -20 град.С, днём 1 гр С.", allowed))
        assertEquals("взял пять граммов с собой.", Normalizer.prepare("Взял 5 гр. с собой.", allowed))
    }

    @Test fun mainsVoltageAtSentenceEndIsVolts() {
        val allowed = "_~|!+,-.:;?абвгдежзийклмнопрстуфхцчшщъыьэюяё–… "
        assertEquals("напряжение двести двадцать вольт.", Normalizer.prepare("Напряжение 220 В.", allowed))
        assertEquals("сеть сто двадцать семь вольт, потом триста восемьдесят вольт.", Normalizer.prepare("Сеть 127 В, потом 380 В.", allowed))
    }

    /** Заголовок без точки перед текстом: «Глава 5 В глубине души» — предлог, не вольты (жалоба 16.11.2026). */
    @Test fun headingNumberThenPrepositionIsNotUnit() {
        val allowed = "_~|!+,-.:;?абвгдежзийклмнопрстуфхцчшщъыьэюяё–… "
        assertEquals("глава пятая в глубине души.", Normalizer.prepare("Глава 5 В глубине души.", allowed))
        assertEquals("часть вторая а потом пришёл он.", Normalizer.prepare("Часть 2 А потом пришёл он.", allowed))
        assertEquals("книга вторая в поисках.", Normalizer.prepare("Книга 2 В поисках.", allowed))
        assertEquals("глава пятая в глубине души.", Normalizer.prepare("Глава 5\nВ глубине души.", allowed))
        assertEquals("напряжение двести двадцать вольт в сети.", Normalizer.prepare("Напряжение 220 В в сети.", allowed))
    }

    @Test fun arabicCenturyReadAsOrdinal() {
        val allowed = "_~|!+,-.:;?абвгдежзийклмнопрстуфхцчшщъыьэюяё–… "
        assertEquals("построен в двенадцатом веке. потом ушли.", Normalizer.prepare("Построен в 12 в. Потом ушли.", allowed))
        assertEquals("с шестнадцатого по восемнадцатый век было.", Normalizer.prepare("С 16 по 18 в. было.", allowed))
        assertEquals("в пятнадцатом – шестнадцатом веках строили.", Normalizer.prepare("В 15–16 вв. строили.", allowed))
        assertEquals("начала девятнадцатого века", Normalizer.prepare("Начала 19 в.", allowed))
        assertEquals("крепость строили два века подряд.", Normalizer.prepare("Крепость строили 2 в. подряд.", allowed))
    }
}
