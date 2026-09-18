package ru.kost.ruvoice

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class SymbolsTest {
    private val golden = JSONObject(File(TestData.root(), "app/src/test/resources/golden_pack.json").readText())
    private val sym = Symbols.fromJson(golden.getJSONObject("pack"))

    @Test fun packSequenceMatchesGolden() {
        val ids = golden.getJSONArray("ids").let { a -> LongArray(a.length()) { a.getLong(it) } }
        assertArrayEquals(ids, sym.sequence(golden.getString("text")))
    }

    @Test fun allowedSkipsThreeServiceSymbols() {
        // у cis symbols начинаются с «|!'» — как фильтр symbols[3:] в пакете Silero, «!» и «'» выпадают
        assertFalse('!' in sym.allowed); assertFalse('\'' in sym.allowed); assertTrue('+' in sym.allowed)
        val ru = TestData.data().sym
        assertTrue('!' in ru.allowed); assertFalse('_' in ru.allowed)
    }

    @Test fun sequenceDropsUnknownChars() {
        // «!» есть в таблице пака (id), sequence его не фильтрует — фильтрует Normalizer.prepare через allowed
        assertEquals(5, sym.sequence("а!б").size)
        assertEquals(2, sym.sequence("ӫ").size)  // символа нет в таблице — остаются только sos и eos
    }
}
