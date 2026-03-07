package ru.kost.ruvoice.text

import org.junit.Assert.assertEquals
import org.junit.Test

class LatinSymbolsTest {
    private val allowed = "!+,-.:;?абвгдежзийклмнопрстуфхцчшщъыьэюяё–… "

    @Test fun latinDigraphsAndSingles() {
        assertEquals("айфон", Normalizer.latin("iphone"))
        assertEquals("виндовс", Normalizer.latin("windows"))
        assertEquals("шерлок", Normalizer.latin("sherlock"))
        assertEquals("чарли", Normalizer.latin("charlie"))
        assertEquals("гугл", Normalizer.latin("google"))
        assertEquals("джон", Normalizer.latin("john"))
        assertEquals("квин", Normalizer.latin("queen"))
        assertEquals("фото", Normalizer.latin("photo"))
        assertEquals("зэ", Normalizer.latin("the"))
        assertEquals("нью йорк", Normalizer.latin("new york"))
    }

    @Test fun cyrillicUntouched() {
        assertEquals("привет мир", Normalizer.latin("привет мир"))
    }

    @Test fun symbolsFiltered() {
        assertEquals("иди сюда, – сказала она.", Normalizer.symbols("«иди сюда», – сказала она.", allowed))
        assertEquals("а – б", Normalizer.symbols("а — б", allowed))
        assertEquals("всё…", Normalizer.symbols("всё…", allowed))
        assertEquals("дом тот", Normalizer.symbols("дом (тот)", allowed)) // скобок в алфавите нет, они удаляются
    }

    @Test fun prepareWholePipeline() {
        assertEquals("в две тысячи двадцать четвёртом году вышел айфон пятнадцать.",
            Normalizer.prepare("В 2024-м году вышел iPhone 15.", allowed))
        assertEquals("глава три. конец.", Normalizer.prepare("  Глава  3.   Конец.  ", allowed))
    }
}
