package ru.kost.ruvoice

import org.junit.Assert.*
import org.junit.Test

class SettingsJsonTest {
    private val samplePrefs = mapOf(
        "voice" to "xenia", "sr" to 48000, "pause_sentence" to 0, "pause_paragraph" to 300,
        "pause_comma" to 100, "idle_min" to 5, "quote_voice" to "", "quote_rate" to 1.0, "quote_pitch" to 1.0,
    )

    @Test fun roundTripBuildAndParse() {
        val json = SettingsJson.build(samplePrefs, "творог твор+ог", "т.е. = то есть")
        val parsed = SettingsJson.parse(json)
        assertEquals("xenia", parsed.prefs["voice"])
        assertEquals(48000, (parsed.prefs["sr"] as Number).toInt())
        assertEquals(0, (parsed.prefs["pause_sentence"] as Number).toInt())
        assertEquals("творог твор+ог", parsed.stress)
        assertEquals("т.е. = то есть", parsed.replace)
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsForeignApp() {
        SettingsJson.parse("""{"app":"other"}""")
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsGarbage() {
        SettingsJson.parse("это совсем не json")
    }

    @Test fun missingKeysAreAbsentAfterParse() {
        val parsed = SettingsJson.parse("""{"app":"ruvoice","version":1,"prefs":{"voice":"baya"}}""")
        assertEquals("baya", parsed.prefs["voice"])
        assertFalse(parsed.prefs.containsKey("sr"))
        assertNull(parsed.stress)
        assertNull(parsed.replace)
    }

    @Test fun unknownPrefKeyDoesNotBreakParsing() {
        val parsed = SettingsJson.parse("""{"app":"ruvoice","prefs":{"unknown_key":123}}""")
        assertEquals(123, (parsed.prefs["unknown_key"] as Number).toInt())
    }
}
