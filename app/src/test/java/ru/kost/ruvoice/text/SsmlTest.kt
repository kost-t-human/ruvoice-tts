package ru.kost.ruvoice.text

import org.junit.Assert.assertEquals
import org.junit.Test

class SsmlTest {
    @Test fun detects() {
        assert(Ssml.isSsml("<speak>привет</speak>"))
        assert(Ssml.isSsml("  <speak version=\"1.0\">x"))
        assert(!Ssml.isSsml("привет <b>"))
    }

    @Test fun breaksAndProsody() {
        val s = Ssml.parse("<speak>Раз.<break time=\"500ms\"/>Два <prosody rate=\"x-fast\" pitch=\"+10%\">три</prosody> четыре<break strength=\"strong\"/></speak>")
        assertEquals(listOf(
            Segment("Раз.", breakMs = 500),
            Segment("Два "),
            Segment("три", rate = 1.5f, pitch = 1.1f),
            Segment(" четыре", breakMs = 300),
        ), s)
    }

    @Test fun paragraphsAndUnknownTags() {
        val s = Ssml.parse("<speak><p><s>Первое.</s><s>Второе.</s></p><p>Третье <emphasis>слово</emphasis>.</p></speak>")
        assertEquals(listOf(
            Segment("Первое."), Segment("Второе.", paragraph = true), Segment("Третье слово.", paragraph = true)
        ), s)
    }

    @Test fun numericRate() {
        assertEquals(listOf(Segment("а", rate = 0.8f)), Ssml.parse("<speak><prosody rate=\"80%\">а</prosody></speak>"))
        assertEquals(listOf(Segment("а", rate = 1.2f)), Ssml.parse("<speak><prosody rate=\"1.2\">а</prosody></speak>"))
    }

    @Test fun pitchWordTable() {
        assertEquals(listOf(Segment("а", pitch = 0.75f)), Ssml.parse("<speak><prosody pitch=\"x-low\">а</prosody></speak>"))
    }

    @Test fun xmlProlog() {
        assertEquals(listOf(Segment("а")), Ssml.parse("<?xml version=\"1.0\"?><speak>а</speak>"))
    }

    @Test fun entityDecoding() {
        assertEquals(listOf(Segment("а & б <в> «г»")), Ssml.parse("<speak>а &amp; б &lt;в&gt; &#171;г&#187;</speak>"))
    }

    @Test fun unmatchedClosingProsody() {
        assertEquals(listOf(Segment("а б")), Ssml.parse("<speak>а</prosody> б</speak>"))
    }
}
