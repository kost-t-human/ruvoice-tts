package ru.kost.ruvoice

import org.junit.Assert.*
import org.junit.Test

class SettingsJsonTest {
    private val samplePrefs = mapOf(
        "voice" to "xenia", "sr" to 48000, "pause_sentence" to 0, "pause_paragraph" to 300,
        "pause_comma" to 100, "idle_min" to 5, "rate" to 1.0, "pitch" to 1.0,
        "quote_voice" to "", "quote_rate" to 1.0, "quote_pitch" to 1.0,
    )

    @Test fun roundTripBuildAndParse() {
        val json = SettingsJson.build(samplePrefs, mapOf("Основной" to "творог твор+ог", "Книга" to ""), mapOf("Основной" to "т.е. = то есть"),
            setOf("Книга"), emptySet())
        val parsed = SettingsJson.parse(json)
        assertEquals("xenia", parsed.prefs["voice"])
        assertEquals(48000, (parsed.prefs["sr"] as Number).toInt())
        assertEquals(0, (parsed.prefs["pause_sentence"] as Number).toInt())
        assertEquals(1.0, (parsed.prefs["rate"] as Number).toDouble(), 0.0)
        assertEquals(1.0, (parsed.prefs["pitch"] as Number).toDouble(), 0.0)
        assertEquals(mapOf("Основной" to "творог твор+ог", "Книга" to ""), parsed.stress)
        assertEquals(mapOf("Основной" to "т.е. = то есть"), parsed.replace)
        assertEquals(setOf("Книга"), parsed.stressOff)
        assertEquals(emptySet<String>(), parsed.replaceOff)
    }

    @Test fun v1FlatStringsBecomeMainList() {
        val parsed = SettingsJson.parse("""{"app":"ruvoice","version":1,"prefs":{},"stress":"творог твор+ог","replace":"кот = к+от"}""")
        assertEquals(mapOf("Основной" to "творог твор+ог"), parsed.stress)
        assertEquals(mapOf("Основной" to "кот = к+от"), parsed.replace)
        assertNull(parsed.stressOff)
    }

    @Test fun oldFileWithoutRatePitchKeysHasThemAbsentAfterParse() {
        val parsed = SettingsJson.parse("""{"app":"ruvoice","prefs":{"voice":"baya"}}""")
        assertFalse(parsed.prefs.containsKey("rate"))
        assertFalse(parsed.prefs.containsKey("pitch"))
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
