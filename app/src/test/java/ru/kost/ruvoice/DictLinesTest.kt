package ru.kost.ruvoice

import org.junit.Assert.*
import org.junit.Test

class DictLinesTest {
    @Test fun parseStressRoundTrip() {
        assertEquals("творог" to "твор+ог", DictLines.parseStress("творог твор+ог"))
    }

    @Test fun parseStressTrimsExtraSpaces() {
        assertEquals("творог" to "твор+ог", DictLines.parseStress("   творог    твор+ог   "))
    }

    @Test fun parseStressSkipsBlankAndComments() {
        assertNull(DictLines.parseStress(""))
        assertNull(DictLines.parseStress("   "))
        assertNull(DictLines.parseStress("# коммент"))
    }

    @Test fun parseStressSkipsBrokenLine() {
        assertNull(DictLines.parseStress("однослово"))
    }

    @Test fun formatStressLowercasesAndInsertsPlus() {
        assertEquals("творог твор+ог", DictLines.formatStress("Творог", 4))
        assertEquals("замок з+амок", DictLines.formatStress("ЗАМОК", 1))
    }

    @Test fun vowelPositionsFindsBothO() {
        assertEquals(listOf(2, 4), DictLines.vowelPositions("творог"))
    }

    @Test fun vowelPositionsEmptyWhenNoVowels() {
        assertEquals(emptyList<Int>(), DictLines.vowelPositions("ткрст"))
    }

    @Test fun accentDisplayInsertsCombiningAcute() {
        assertEquals("творо́г", DictLines.accentDisplay("твор+ог"))
    }

    @Test fun accentDisplayPlusAtStart() {
        assertEquals("о́к", DictLines.accentDisplay("+ок"))
    }

    @Test fun accentDisplayNoPlusReturnsAsIs() {
        assertEquals("творог", DictLines.accentDisplay("творог"))
    }

    @Test fun accentDisplayStripsFurtherPluses() {
        // такого в норме быть не должно (одно ударение на слово), но лишние «+» не должны
        // всплывать в отображаемом тексте как есть
        assertEquals("тво́рог", DictLines.accentDisplay("тв+ор+ог"))
    }

    @Test fun parseReplaceLiteral() {
        assertEquals(Triple("т.е.", "то есть", false), DictLines.parseReplace("т.е. = то есть"))
    }

    @Test fun parseReplaceRegex() {
        assertEquals(
            Triple("(\\d+)\\s*км", "$1 километров", true),
            DictLines.parseReplace("""~(\d+)\s*км = $1 километров""")
        )
    }

    @Test fun parseReplaceEmptyValue() {
        assertEquals(Triple("[1]", "", false), DictLines.parseReplace("[1] ="))
    }

    @Test fun parseReplaceSkipsBlankCommentsAndNoEquals() {
        assertNull(DictLines.parseReplace(""))
        assertNull(DictLines.parseReplace("# коммент"))
        assertNull(DictLines.parseReplace("без равенства"))
    }

    @Test fun formatReplaceRoundTrip() {
        assertEquals("т.е. = то есть", DictLines.formatReplace("т.е.", "то есть", false))
        assertEquals(
            """~(\d+)\s*км = $1 километров""",
            DictLines.formatReplace("""(\d+)\s*км""", "$1 километров", true)
        )
    }

    @Test fun wordRangeAtFindsWordUnderCursorAndSticksLeftOnBoundary() {
        assertEquals(7 until 13, DictLines.wordRangeAt("старый з+амок", 9))
        assertEquals(0 until 6, DictLines.wordRangeAt("старый замок", 6))
        assertEquals(null, DictLines.wordRangeAt("{pause:300} слово", 3))
        assertEquals(null, DictLines.wordRangeAt("", 0))
    }

    @Test fun setWordStressMovesPlusAndClears() {
        assertEquals("старый за+мок", DictLines.setWordStress("старый з+амок", 7 until 13, 2))
        assertEquals("старый замок", DictLines.setWordStress("старый з+амок", 7 until 13, null))
        assertEquals("прогр+амм*", DictLines.setWordStress("программ*", 0 until 8, 5))
    }

    // «молоко»: три одинаковые «о» — чипы различаются номером слога
    @Test fun syllableOfSameVowels() {
        assertEquals(Triple(1, 3, 'о'), DictLines.syllable("молоко", 1))
        assertEquals(Triple(2, 3, 'о'), DictLines.syllable("молоко", 3))
        assertEquals(Triple(3, 3, 'о'), DictLines.syllable("Молоко", 5))
        assertEquals(Triple(1, 1, 'ё'), DictLines.syllable("Ёж", 0))
    }
}
