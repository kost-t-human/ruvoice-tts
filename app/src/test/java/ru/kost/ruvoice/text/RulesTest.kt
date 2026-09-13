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

    @Test fun thousandsSeparatorRegularSpaceBeforeMoneyOrUnit() {
        // Некруглая группа без продолжения, но следом деньги/единица — это одно число.
        assertEquals("одна тысяча двести рублей", n("1 200 рублей"))
        assertEquals("одна тысяча двести рублей", n("1 200 руб."))
        assertEquals("одна тысяча двести рублей", n("1 200 ₽"))
        assertEquals("две тысячи пятьсот километров", n("2 500 км"))
        assertEquals("три тысячи пятьсот долларов", n("3 500 $"))
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
        assertEquals("Людовик четырнадцатый", n("Людовик XIV"))
    }

    @Test fun romanNumeralAfterCapitalizedNameIsOrdinal() {
        // task 28 п.2: «Имя I/II/XIV» — порядковое, род по окончанию имени; триггеры
        // (глава/часть/том, век) — старым путём; короткие латинские токены без имени — не число.
        assertEquals("Пётр первый", n("Пётр I"))
        assertEquals("Николай второй", n("Николай II"))
        assertEquals("Екатерина вторая", n("Екатерина II"))
        assertEquals("Людовик четырнадцатый", n("Людовик XIV"))
        assertEquals("Иоанн Павел второй", n("Иоанн Павел II"))
        assertEquals("Глава первая", n("Глава I"))
        assertEquals("часть вторая", n("часть II"))
        assertEquals("Россия двадцатого века", n("Россия XX века"))
        assertEquals("Европа девятнадцатый век", n("Европа XIX в."))
        assertEquals("Название I Am Legend", n("Название I Am Legend"))
        assertEquals("Windows XP", n("Windows XP"))
    }

    @Test fun romanNumeralsLowercaseWithoutTriggerIsAnAcceptedLimitation() {
        // review t17 round2 п.1: «людовик xiv» строчными и без триггера так и остаётся как есть —
        // принятое ограничение (см. отчёт), в отличие от заглавного «XIV» без триггера.
        assertEquals("людовик xiv", n("людовик xiv"))
    }

    @Test fun romanNumeralsCyrillicLookalikes() {
        // финальный fix-раунд п.2: кириллические х/с/м/і визуально совпадают с латинскими римскими
        // буквами («ХХ» иначе доходит до Abbrev как обычный кириллический токен — «ха х+а»).
        assertEquals("в двадцатом веке", n("в ХХ веке"))
        assertEquals("двадцать первого века", n("ХХI века"))
        assertEquals("Людовик четырнадцать", n("Людовик ХIV"))
    }

    @Test fun shortLatinTokensWithoutTriggerStayUntouched() {
        // финальный fix-раунд п.6: одно- и двухбуквенные латинские токены без триггера — не
        // римские числа («I love you», «XL», «CD», «C++»); «Имя II» — порядковое (task 28 п.2).
        assertEquals("I love you", n("I love you"))
        assertEquals("Размер XL", n("Размер XL"))
        assertEquals("Николай второй", n("Николай II"))
        assertEquals("Книга с картинками", n("Книга с картинками"))
        assertEquals("с века на век", n("с века на век"))
        assertEquals("Диск CD", n("Диск CD"))
        assertEquals("Язык C++", n("Язык C++"))
    }

    @Test fun caseInsensitiveThroughPrepare() {
        // review t17 round2 п.1 (Normalizer.kt: prepare()): регистр больше не теряется до
        // numbers() — предлоги/сокращения/триггеры матчатся независимо от регистра исходника,
        // а «MIX» без «m»-исключения из romanNumerals остаётся обычным словом, не числом 1009.
        assertEquals("людовик четырнадцатый правил", p("Людовик XIV правил"))
        assertEquals("в тысяча девятьсот девяностом году", p("В 1990 Году"))
        assertEquals("иоанна три шестнадцать", p("Иоанна 3:16"))
        assertEquals("в двадцатом веке", p("в XX веке"))
        // не римское 1009; капс с единственной гласной I Abbrev читает по буквам (как FBI)
        assertEquals("эм ай +экс стилей", p("MIX стилей"))
    }

    @Test fun dates() {
        assertEquals("пятого декабря две тысячи двадцатого года", n("05.12.2020"))
        assertEquals("шестого октября две тысячи двадцать четвёртого года", n("6/10/2024"))
        assertEquals("пятого декабря", n("05.12"))
        assertEquals("двенадцатого мая", n("12.05"))
        assertEquals("три целых пять десятых", n("3.5"))
    }

    @Test fun dateDigitsBeforeUnitIsFractionNotDate() {
        // финальный fix-раунд п.8: «N.N» перед единицей измерения — дробь, не дата без года.
        val out = n("12.5 км/ч")
        assertEquals("двенадцать целых пять десятых километра в час", out)
        assert(!out.contains("мая")) { out }
    }

    @Test fun time() {
        assertEquals("в четырнадцать часов тридцать минут", n("в 14:30"))
        assertEquals("в один час пять минут", n("в 1:05"))
        assertEquals("в двадцать один час", n("в 21:00"))
        assertEquals("семь часов одна минута утра", n("7:01 утра"))
    }

    @Test fun timeWithCasePreposition() {
        // task 28 п.1: «с/до/около/после» — родительный, «к» — дательный, «в/на» — как раньше.
        assertEquals("с девяти часов", n("с 9:00"))
        assertEquals("с девяти часов тридцати минут", n("с 9:30"))
        assertEquals("до восемнадцати часов тридцати минут", n("до 18:30"))
        assertEquals("к десяти часам", n("к 10:00"))
        assertEquals("к одному часу", n("к 1:00"))
        assertEquals("после двадцати одного часа пятнадцати минут", n("после 21:15"))
        assertEquals("около двенадцати часов дня", n("около 12:00 дня"))
        assertEquals("с девяти часов до восемнадцати часов", n("с 9:00 до 18:00"))
        assertEquals("с двадцати одного часа одной минуты", n("с 21:01"))
        assertEquals("в двадцать один час тридцать минут", n("в 21:30"))
        assertEquals("на девять часов", n("на 9:00"))
        assertEquals("девять часов утра", n("9:00 утра"))
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

    @Test fun gramsWithDotAreNotYear() {
        // task 28 п.4: голое «N г.» — год только при четырёх цифрах, «500 г.» — граммы;
        // с предлогом «в» трёхзначный год остаётся годом.
        assertEquals("пятьсот граммов муки", n("500 г. муки"))
        assertEquals("двести граммов сахара и два яйца", n("200 г. сахара и 2 яйца"))
        assertEquals("в девятьсот восемьдесят восьмом году", n("в 988 г."))
        assertEquals("в тысяча девятьсот девяностом году началась", n("в 1990 г. началась"))
        assertEquals("тысяча девятьсот девяностый год", n("1990 г."))
        // трёхзначное без предлога, но перед «до н. э.» — всё же год
        assertEquals("четыреста девяностый год до нашей эры", n("490 г. до н. э."))
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
        assertEquals("в пяти километрах от города", n("в 5-ти км от города"))
        assertEquals("к пяти километрам", n("к 5-ти км"))
        assertEquals("пяти километров", n("5-ти км"))
        assertEquals("в двух километрах", n("в 2-ух км"))
    }

    @Test fun units() {
        assertEquals("пять килограммов", n("5 кг"))
        assertEquals("два километра", n("2 км"))
        assertEquals("двадцать один грамм", n("21 г"))
        assertEquals("три километра в час", n("3 км/ч"))
        assertEquals("одна минута", n("1 мин"))
        assertEquals("две минуты", n("2 мин"))
        assertEquals("одна целая пять десятых литра", n("1,5 л"))
        assertEquals("пятое мая", n("5 мая"))
    }

    @Test fun spoons() {
        // task 28 п.3: «ч. л.»/«ст. л.» с числом — ложки, не часы/литры; дробь — родительный ед.
        assertEquals("две чайные ложки", n("2 ч. л."))
        assertEquals("две чайные ложки", n("2 ч л"))
        assertEquals("две чайные ложки", n("2ч.л."))
        assertEquals("одна чайная ложка", n("1 ч. л."))
        assertEquals("пять чайных ложек", n("5 ч. л."))
        assertEquals("двадцать одна чайная ложка", n("21 ч. л."))
        assertEquals("три столовые ложки", n("3 ст. л."))
        assertEquals("одна столовая ложка", n("1 ст.л."))
        assertEquals("десять столовых ложек", n("10 ст. л."))
        assertEquals("ноль целых пять десятых чайной ложки", n("0,5 ч. л."))
        assertEquals("одна вторая столовой ложки", n("1/2 ст. л."))
        assertEquals("две чайные ложки соли", n("2 ч. л. соли"))
        assertEquals("две чайные ложки. Соль", n("2 ч. л. Соль"))
        assertEquals("два часа.", n("2 ч."))
    }

    @Test fun sectionNumbers() {
        assertEquals("пункт один точка два точка три", n("пункт 1.2.3"))
        assertEquals("три целых пять десятых", n("3.5"))
    }

    @Test fun sectionNumberNotSplitAsDate() {
        // финальный fix-раунд п.3: «10.3» внутри «2.10.3» не должен читаться как дата (10-е марта).
        assertEquals("пункт два точка десять точка три и таблица", n("пункт 2.10.3 и табл."))
    }

    @Test fun sectionNumberAtSentenceEndKeepsPeriod() {
        // финальный fix-раунд п.3: точка предложения после составного номера не блокирует матч
        // целиком — номер читается полностью, а точка остаётся на месте.
        assertEquals("пункт два точка десять точка три.", n("пункт 2.10.3."))
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

    @Test fun sectionAbbreviationWithoutSpace() {
        // финальный fix-раунд п.12: «п.5» без пробела не должен склеиваться в «пункт5».
        assertEquals("пункт пять", n("п.5"))
        assertEquals("пункт пять", n("п. 5"))
    }

    @Test fun unitAbbreviationAtSentenceEnd() {
        // финальный fix-раунд п.5: «см.»/«ч.» с точкой — единица, не сокращение «смотри»/обрыв
        // предложения; точка перед строчной (продолжение абзаца) съедается, перед заглавной (новое
        // предложение) остаётся.
        assertEquals("рост сто восемьдесят сантиметров. он был высок.", p("Рост 180 см. Он был высок."))
        assertEquals("в пять часов дня", p("в 5 ч. дня"))
    }

    @Test fun cardinalGenitiveSuffixBareH() {
        // финальный fix-раунд п.7: «2-х»/«3-х»/«4-х» — тот же родительный падеж, что «2-ух»/«3-ёх».
        assertEquals("у двух друзей", n("у 2-х друзей"))
        assertEquals("у трёх друзей", n("у 3-х друзей"))
        assertEquals("у четырёх друзей", n("у 4-х друзей"))
    }

    @Test fun cardinalGenitiveSuffixBareHDoesNotTouchTeensOrDecades() {
        assertEquals("двенадцатых", n("12-х"))
        assertEquals("в девяностых", n("в 90-х"))
        assertEquals("двухтысячные", n("2000-е"))
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

    @Test fun degreesWithNbsp() {
        // review round 1 п.2: NBSP (U+00A0) не матчится JVM-ом \s без UNICODE_CHARACTER_CLASS —
        // «1 °C» раньше уходил как «один °сто» (° не находило C рядом).
        assertEquals("один градус цельсия", p("1 °C"))
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

    @Test fun currencyDollarSignWithSpace() {
        // review round 1 п.5: пробел после «$» тоже допустим.
        assertEquals("сто долларов", n("$ 100"))
    }

    @Test fun cityAbbreviation() {
        assertEquals("город москва", p("г. Москва"))
        assertEquals("в тысяча девятьсот девяностом году", n("в 1990 г."))
        assertEquals("двадцать один грамм", n("21 г"))
    }

    @Test fun cityAbbreviationWithNbsp() {
        // review round 1 п.2: та же причина, что и у градусов — \s не видит NBSP.
        assertEquals("город москва", p("г. Москва"))
    }

    @Test fun thousandsSeparatorNbspNotBrokenByGeneralWhitespaceFix() {
        assertEquals("двенадцать тысяч триста сорок пять", n("12 345"))
    }

    @Test fun decades() {
        assertEquals("в девяностых", n("в 90-х"))
        assertEquals("двухтысячные", n("2000-е"))
        assertEquals("в тысяча девятьсот девяностых годах", n("в 1990-х годах"))
    }

    @Test fun decadesNeedYearContextForRoundThousand() {
        // review round 1 п.4: круглая тысяча с «-е» — множественное только с годовым контекстом
        // (годы/гг./годов/годах/года, конец строки, знак препинания), иначе обычное порядковое.
        assertEquals("тысячное место", n("1000-е место"))
        assertEquals("в двухтысячные", n("в 2000-е"))
        assertEquals("двухтысячные годы", n("2000-е годы"))
    }

    // «день месяц гггг г.» — день порядковый среднего рода, год через «г.» — год, не граммы.
    @Test fun dayMonthYearWithG() {
        assertEquals("двадцатое июня две тысячи пятидесятого года", n("20 июня 2050 г."))
        assertEquals("первое сентября", n("1 сентября"))
        assertEquals("к первому сентября", n("к 1 сентября"))
        assertEquals("с двадцать третьего февраля две тысячи двадцать четвёртого года", n("с 23 февраля 2024 г."))
        assertEquals("двадцатое июня две тысячи пятидесятого года", n("20 июня 2050 года"))
        // «г» без точки после четырёх цифр — год, «500 г» — по-прежнему граммы
        assertEquals("двадцатое июня две тысячи пятидесятого года", n("20 июня 2050 г"))
        assertEquals("в тысяча девятьсот девяностом году", n("в 1990 г"))
        assertEquals("пятьсот граммов муки", n("500 г муки"))
        assertEquals("с первого по пятое сентября", n("с 1 по 5 сентября"))
    }
}
