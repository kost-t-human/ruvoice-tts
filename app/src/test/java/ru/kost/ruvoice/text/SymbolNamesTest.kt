package ru.kost.ruvoice.text

import org.junit.Assert.assertEquals
import org.junit.Test

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
}
