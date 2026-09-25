package ru.kost.ruvoice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class EnglishVoiceTitleTest {
    private val ru = Locale("ru")

    @Test fun googleNameReadable() {
        val t = EnglishProxy.voiceTitle("en-us-x-iol-local", null, ru, "голос")
        assertTrue(t, t.startsWith("Английский") && t.endsWith(", голос iol"))
        assertTrue(EnglishProxy.voiceTitle("en-gb-x-rjs-network", null, ru, "голос").endsWith(", голос rjs"))
    }

    @Test fun otherNamesKeptWithLocale() {
        val t = EnglishProxy.voiceTitle("Alex", Locale.US, ru, "голос")
        assertTrue(t, t.startsWith("Английский") && t.endsWith(", голос Alex"))
        assertEquals("Alex", EnglishProxy.voiceTitle("Alex", null, ru, "голос"))
    }
}
