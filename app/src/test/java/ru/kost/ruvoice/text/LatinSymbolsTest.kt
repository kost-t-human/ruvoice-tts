package ru.kost.ruvoice.text

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.kost.ruvoice.TestData

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

    @Test fun nbspNormalizedBeforeFiltering() {
        // NBSP (U+00A0) не входит в allowed и не входит в JVM \s: раньше он просто вырезался
        // фильтром символов, и «в доме» слипалось в «вдоме». Сначала NBSP должен стать обычным пробелом.
        assertEquals("в доме", Normalizer.symbols("в\u00A0доме", allowed))
    }

    @Test fun prepareWholePipeline() {
        assertEquals("в две тысячи двадцать четвёртом году вышел айфон пятнадцать.",
            Normalizer.prepare("В 2024-м году вышел iPhone 15.", allowed))
        assertEquals("глава три. конец.", Normalizer.prepare("  Глава  3.   Конец.  ", allowed))
    }

    // Контракт нормализатора (спецификация §2): Normalizer.prepare на golden.json обязан
    // совпадать байт в байт с тем, что сохранил Python-пайплайн Silero.
    @Test fun prepareMatchesGolden() {
        val d = TestData.data()
        val g = TestData.golden()
        for (i in 0 until g.length()) {
            val o = g.getJSONObject(i)
            assertEquals(o.getString("text"), o.getString("prepared"), Normalizer.prepare(o.getString("text"), d.allowed))
        }
    }
}
