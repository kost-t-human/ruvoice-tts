package ru.kost.ruvoice.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** TtsSpan, размеченные приложением: что подставляется вместо куска текста. */
class SpanSayTest {
    private fun say(type: String, vararg a: Pair<String, Any?>) = SpanSay.say(type, mapOf(*a))

    @Test fun textAndNumbers() {
        assertEquals("запятая", say("text", "text" to "запятая"))
        assertEquals("15", say("cardinal", "number" to "15"))
        assertEquals("3,14", say("decimal", "integer_part" to "3", "fractional_part" to "14"))
        assertEquals("1/2", say("fraction", "numerator" to "1", "denominator" to "2"))
        assertEquals("одна целая и 1/2", say("fraction", "integer_part" to "1", "numerator" to "1", "denominator" to "2"))
        assertNull(say("cardinal"))
        assertNull(say("unknown", "text" to "x"))
    }

    @Test fun ordinalAgreement() {
        assertEquals("пятый", say("ordinal", "number" to "5"))
        assertEquals("пятая", say("ordinal", "number" to "5", "gender" to "female"))
        assertEquals("пятое", say("ordinal", "number" to "5", "gender" to "neutral"))
        assertEquals("пятого", say("ordinal", "number" to "5", "case" to "genitive"))
        assertEquals("пятой", say("ordinal", "number" to "5", "gender" to "female", "case" to "dative"))
        assertEquals("третьей", say("ordinal", "number" to "3", "gender" to "female", "case" to "genitive"))
        assertEquals("пятых", say("ordinal", "number" to "5", "multiplicity" to "plural", "case" to "locative"))
    }

    @Test fun timeAndDate() {
        assertEquals("семь часов пять минут", say("time", "hours" to 7, "minutes" to 5))
        assertEquals("двадцать один час одна минута", say("time", "hours" to 21, "minutes" to 1))
        assertEquals("двенадцать часов", say("time", "hours" to 12, "minutes" to 0))
        // месяц с нуля, день недели с воскресенья (TtsSpan.MONTH_JANUARY = 0, WEEKDAY_SUNDAY = 1)
        assertEquals("вторник, пятое марта две тысячи двадцать четвёртого года",
            say("date", "weekday" to 3, "day" to 5, "month" to 2, "year" to 2024))
        assertEquals("первое января", say("date", "day" to 1, "month" to 0))
        assertEquals("март", say("date", "month" to 2))
    }

    @Test fun telephone() {
        // PhoneNumberUtils.createTtsSpan: код страны отдельно, части номера через пробел
        assertEquals("плюс семь, девятьсот двенадцать, триста сорок пять, шестьдесят семь, восемьдесят девять",
            say("telephone", "country_code" to "7", "number_parts" to "912 345 67 89"))
        assertEquals("восемь, ноль двенадцать, триста сорок пять, ноль шесть, семьдесят восемь",
            say("telephone", "number_parts" to "80123450678"))
        assertEquals("сто двадцать три, сорок пять, шестьдесят семь, добавочный двенадцать",
            say("telephone", "number_parts" to "123-45-67", "extension" to "12"))
    }

    @Test fun moneyAndMeasure() {
        assertEquals("сто рублей пятьдесят копеек", say("money", "integer_part" to "100", "fractional_part" to "50", "currency" to "RUB"))
        assertEquals("один доллар девяносто девять центов", say("money", "integer_part" to "1", "fractional_part" to "99", "currency" to "usd"))
        assertEquals("две гривны", say("money", "integer_part" to "2", "currency" to "UAH"))
        assertEquals("один рубль пятьдесят копеек", say("money", "integer_part" to "1", "fractional_part" to "5", "currency" to "RUB"))
        assertEquals("5 CAD", say("money", "integer_part" to "5", "currency" to "CAD"))
        assertEquals("5 км", say("measure", "number" to "5", "unit" to "kilometers"))
        assertEquals("60 км/ч", say("measure", "number" to "60", "unit" to "kilometer-per-hour"))
        assertEquals("2,5 кг", say("measure", "integer_part" to "2", "fractional_part" to "5", "unit" to "kilogram"))
        assertEquals("три мили", say("measure", "number" to "3", "unit" to "miles"))
        assertEquals("пять дюймов", say("measure", "number" to "5", "unit" to "inches"))
    }

    @Test fun spelling() {
        assertEquals("четыре восемь два", say("digits", "digits" to "482"))
        assertEquals("б+э два, д+э", say("verbatim", "verbatim" to "Б2 Д"))
        assertEquals("user@example.com", say("electronic", "username" to "user", "domain" to "example.com"))
        assertEquals("https://example.com/a?q=1", say("electronic", "protocol" to "https", "domain" to "example.com", "path" to "a", "query_string" to "q=1"))
    }
}
