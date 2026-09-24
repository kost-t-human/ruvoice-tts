package ru.kost.ruvoice.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Секция «Для TalkBack»: правила экранного чтеца поверх общих, книгам ничего не меняется. */
class ScreenReaderRulesTest {
    private val allowed = "абвгдеёжзийклмнопрстуфхцчшщъыьэюяАБВГДЕЁЖЗИЙКЛМНОПРСТУФХЦЧШЩЪЫЬЭЮЯ .,!?-–:;+«»()"

    @Test fun defaults() {
        val sr = Rules().screenReader()
        assertTrue(sr.on("symbol_names"))
        assertFalse(sr.on("speech"))
        assertFalse(sr.on("lead_in"))
        // общие — как были
        assertFalse(Rules().on("symbol_names"))
        assertTrue(Rules().on("lead_in"))
        assertTrue(Rules().on("sr_pauses_off"))
    }

    @Test fun sectionSwitchesOff() {
        val r = Rules(off = setOf("sr_symbols", "sr_quote_off", "sr_lead_in_off")).screenReader()
        assertFalse(r.on("symbol_names"))
        assertTrue(r.on("speech"))
        assertTrue(r.on("lead_in"))
    }

    @Test fun keepsOtherSettings() {
        val base = Rules(off = setOf("phones", "lead_in"), maxLen = 200, focus = 2)
        val sr = base.screenReader()
        assertFalse(sr.on("phones"))
        assertEquals(200, sr.maxLen)
        assertEquals(2, sr.focusLevel)
        // «Служебные символы везде» уже включены — остаются включены
        assertTrue(Rules(off = setOf("symbol_names")).screenReader().on("symbol_names"))
    }

    @Test fun booksVsTalkBack() {
        assertEquals("", Normalizer.prepare("* * *", allowed))
        assertEquals("ударение треугольник вниз", Normalizer.prepare("Ударение ▾", allowed, Rules().screenReader()))
    }
}
