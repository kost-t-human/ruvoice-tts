package ru.kost.ruvoice.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.kost.ruvoice.TestData
import java.io.File

/** Правило symbol_names: служебные знаки словами, по умолчанию выключено. */
class SymbolNamesTest {
    private val allowed = "абвгдеёжзийклмнопрстуфхцчшщъыьэюяАБВГДЕЁЖЗИЙКЛМНОПРСТУФХЦЧШЩЪЫЬЭЮЯ .,!?-–:;+«»()"
    private val on = Rules(off = setOf("symbol_names"))
    private fun say(s: String, r: Rules = Rules()) = Normalizer.prepare(s, allowed, r)

    @Test fun offByDefault() {
        assertEquals(false, Rules().on("symbol_names"))
        assertEquals("а – б", say("а → б"))
        assertEquals("ударение", say("Ударение ▾"))
    }

    // выкинутый фильтром знак не склеивает слова
    @Test fun droppedSymbolKeepsGap() {
        assertEquals("и или", say("и/или"))
        assertEquals("мама папа", say("мама/папа"))
        assertEquals("он ушёл.", say("Он ушёл.*"))
        assertEquals("глава первая начало", say("Глава 1 | Начало"))
    }

    @Test fun named() {
        assertEquals("а стрелка вправо б", say("а → б", on))
        assertEquals("ударение треугольник вниз", say("Ударение ▾", on))
        assertEquals("меню три точки вертикально", say("меню ⋮", on))
        assertEquals("замок точка посередине системный", say("замок · Системный", on))
        // что читают числа и единицы — остаётся за ними
        assertEquals("десять процентов", say("10 %", on))
        // ударение «+» — не знак
        assertEquals("мол+око", say("мол+око", on))
    }

    private val cldr = File(TestData.root(), "app/src/main/assets/symbols_ru.tsv").bufferedReader().useLines { SymbolNames.parse(it) }

    @Test fun cldrTable() {
        assertTrue(cldr.size > 300)
        assertEquals("сумма", cldr['∑']); assertEquals("интеграл", cldr['∫'])
        assertEquals("стрелка направо", cldr['→'])     // кавычки из имени CLDR убраны
        // пунктуация — паузы, не слова: ни точки, ни скобок, ни кавычек, ни тире
        for (c in ".,!?:;…()[]{}«»„“”\"'-–—") assertNull(c.toString(), cldr[c])
    }

    @Test fun ownNamesOverCldr() {
        SymbolNames.cldr = cldr
        try {
            assertEquals("стрелка вправо", SymbolNames.of('→'))   // как у TalkBack, не «стрелка направо»
            assertEquals("собака", SymbolNames.of('@'))
            assertEquals("сумма", SymbolNames.of('∑'))            // нет в своей — из CLDR
            assertEquals("а сумма б", say("а ∑ б", on))
            // у cis-пака нет «!» — знак выпадает, но словом не становится
            assertEquals("привет", Normalizer.prepare("Привет!", allowed.replace("!", ""), on))
        } finally { SymbolNames.cldr = null }
    }
}
