package ru.kost.ruvoice.text

import org.junit.Assert.assertEquals
import org.junit.Test

class EnglishTest {
    private fun en(t: String, min: Int = 3) = English.split(t, min).filter { it.second }.map { it.first }

    @Test fun phraseInsideRussian() {
        val t = "Он сказал: «I don't know what you mean», и ушёл."
        assertEquals(listOf("I don't know what you mean"), en(t))
        assertEquals(t, English.split(t).joinToString("") { it.first })
    }

    @Test fun shortNamesStayRussian() {
        assertEquals(emptyList<String>(), en("Открыл Microsoft Word и закрыл."))
        assertEquals(emptyList<String>(), en("Порт USB и iPhone рядом."))
        assertEquals(emptyList<String>(), en("Глава I. Начало"))
    }

    @Test fun byDefaultFromOneWord() {
        assertEquals(listOf("Microsoft Word", "iPhone."), en("Открыл Microsoft Word на iPhone.", 1))
        // сокращения капсом и римские — по-прежнему русскому голосу
        assertEquals(emptyList<String>(), en("Порт USB, глава XIV.", 1))
        assertEquals(1, English.MIN_WORDS)
    }

    @Test fun pronounCountsNextToRealWord() {
        assertEquals(listOf("I love you."), en("Она прошептала: I love you."))
    }

    @Test fun wholeLatinSegmentFromOneWord() {
        assertEquals(listOf("Settings"), en("Settings"))
        assertEquals(listOf("Wi-Fi"), en("Wi-Fi"))
        assertEquals(listOf("Hello, world!"), en("Hello, world!"))
    }

    @Test fun abbreviationsAndRomanAloneStayRussian() {
        assertEquals(emptyList<String>(), en("XIV"))
        assertEquals(emptyList<String>(), en("OK"))
        assertEquals(emptyList<String>(), en("USB"))
        assertEquals(emptyList<String>(), en("https://example.com/page"))
    }

    @Test fun numbersInsidePhrase() {
        assertEquals(listOf("Windows 11 is the latest version"), en("Написано: Windows 11 is the latest version, но это не так."))
    }

    @Test fun cyrillicBreaksRun() {
        assertEquals(emptyList<String>(), en("Good и bad и ugly"))
    }

    @Test fun noLatinNoSplit() {
        assertEquals(listOf("Привет." to false), English.split("Привет."))
    }

    @Test fun sentenceEndGoesToEnglish() {
        assertEquals(listOf("Он ответил: " to false, "See you later!" to true), English.split("Он ответил: See you later!"))
    }

    @Test fun capsEnglishWords() {
        assertEquals(listOf("I LOVE YOU"), en("I LOVE YOU", 1))
        assertEquals(listOf("HELP"), en("HELP", 1))
        assertEquals(listOf("GAME OVER"), en("На экране: GAME OVER", 1))
        // сокращения и двухбуквенные без соседей — по-прежнему русскому голосу
        assertEquals(emptyList<String>(), en("Отдел IT и NASA", 1))
        assertEquals(emptyList<String>(), en("USA", 1))
        // «DID» — не римское число, если это слово из списка
        assertEquals(listOf("WHAT DID YOU SAY"), en("WHAT DID YOU SAY", 1))
    }
}
