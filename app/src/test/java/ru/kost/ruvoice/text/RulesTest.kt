package ru.kost.ruvoice.text

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.kost.ruvoice.TestData

/** Тесты на новые правила нормализации (задача t17), TDD: ассерты написаны до реализации. */
class RulesTest {
    private fun n(s: String) = Normalizer.numbers(s)
    private fun p(s: String) = Normalizer.prepare(s, TestData.data().allowed)

    @Test fun thousandsSeparators() {
        assertEquals("двенадцать миллионов триста сорок пять тысяч шестьсот семьдесят восемь человек",
            n("12 345 678 человек"))
    }

    @Test fun thousandsSeparatorOnlyNonBreakingSpace() {
        // review t17 п.3 (Normalizer.kt:99): обычный пробел — не разделитель разрядов.
        assertEquals("Глава один двести читателей", n("Глава 1 200 читателей"))
    }

    @Test fun thousandsSeparatorRegularSpaceHeuristic() {
        // review t17 round2 п.2 (Normalizer.kt:99): обычный пробел склеивает разряды, только
        // если группа круглая («000») или сразу следует ещё одна группа из трёх цифр.
        assertEquals("пять тысяч рублей", n("5 000 рублей"))
        assertEquals("один миллион двести тысяч человек", n("1 200 000 человек"))
        assertEquals("глава один двести читателей", n("глава 1 200 читателей"))
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

    @Test fun romanNumeralsLowercaseWithoutTriggerIsAnAcceptedLimitation() {
        // review t17 round2 п.1: «людовик xiv» строчными и без триггера так и остаётся как есть —
        // принятое ограничение (см. отчёт), в отличие от заглавного «XIV» без триггера.
        assertEquals("людовик xiv", n("людовик xiv"))
    }

    @Test fun caseInsensitiveThroughPrepare() {
        // review t17 round2 п.1 (Normalizer.kt: prepare()): регистр больше не теряется до
        // numbers() — предлоги/сокращения/триггеры матчатся независимо от регистра исходника,
        // а «MIX» без «m»-исключения из romanNumerals остаётся обычным словом, не числом 1009.
        assertEquals("людовик четырнадцать правил", p("Людовик XIV правил"))
        assertEquals("в тысяча девятьсот девяностом году", p("В 1990 Году"))
        assertEquals("иоанна три шестнадцать", p("Иоанна 3:16"))
        assertEquals("в двадцатом веке", p("в XX веке"))
        assertEquals("микс стилей", p("MIX стилей"))
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

    // Task 18 п.3: пунктуация — до чисел, первым проходом в prepare().
    @Test fun repeatedExclamationOrQuestionMarks() {
        assertEquals("что?", p("Что?!"))
        assertEquals("ура!", p("Ура!!!"))
    }

    @Test fun ellipsisVariants() {
        assertEquals("всё…", p("Всё..."))
        assertEquals("всё…", p("Всё...."))
        assertEquals("всё…", p("Всё. . ."))
    }

    @Test fun spacedHyphenBecomesEnDash() {
        assertEquals("иди – сюда", p("иди - сюда"))
    }

    @Test fun repeatedDashesCollapse() {
        assertEquals("иди – сюда", p("иди –– сюда"))
    }

    @Test fun degrees() {
        assertEquals("один градус цельсия", p("1 °C"))
        assertEquals("минус пять градусов цельсия", p("−5 °C"))
        assertEquals("двадцать два градуса", p("22°"))
        assertEquals("минус двадцать градусов по фаренгейту", p("−20°F"))
    }

    @Test fun fractionsAsWords() {
        assertEquals("одна вторая", n("1/2"))
        assertEquals("две третьих", n("2/3"))
        assertEquals("три четвёртых", n("3/4"))
        assertEquals("шесть десятых", n("6/10"))
        assertEquals("двадцать пять дробь три", n("25/3"))
    }

    @Test fun fractionsSlashDoesNotBreakDates() {
        assertEquals("пятого декабря две тысячи двадцатого года", n("05/12/2020"))
    }

    @Test fun currency() {
        assertEquals("пять долларов", n("$5"))
        assertEquals("двадцать один доллар", n("21 $"))
        assertEquals("пять рублей тридцать копеек", n("5 руб. 30 коп."))
        assertEquals("две целых пять десятых евро", n("2,5 €"))
        assertEquals("один рубль", n("1 руб."))
    }

    @Test fun cityAbbreviation() {
        assertEquals("город москва", p("г. Москва"))
        assertEquals("в тысяча девятьсот девяностом году", n("в 1990 г."))
        assertEquals("двадцать один грамм", n("21 г"))
    }

    @Test fun decades() {
        assertEquals("в девяностых", n("в 90-х"))
        assertEquals("двухтысячные", n("2000-е"))
        assertEquals("в тысяча девятьсот девяностых годах", n("в 1990-х годах"))
    }
}
