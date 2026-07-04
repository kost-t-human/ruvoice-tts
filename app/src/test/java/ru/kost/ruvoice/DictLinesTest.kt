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
        assertEquals("творо́г", DictLines.accentDisplay("твор+ог"))
        assertEquals("творо́г", DictLines.accentDisplay("твор+ог"))
    }

    @Test fun accentDisplayPlusAtStart() {
        assertEquals("о́к", DictLines.accentDisplay("+ок"))
    }

    @Test fun accentDisplayNoPlusReturnsAsIs() {
        assertEquals("творог", DictLines.accentDisplay("творог"))
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
}
