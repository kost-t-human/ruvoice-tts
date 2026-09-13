package ru.kost.ruvoice.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
            Segment("Два {prosody:150:110}три {prosody}четыре", breakMs = 300),
        ), s)
    }

    @Test fun paragraphsAndUnknownTags() {
        val s = Ssml.parse("<speak><p><s>Первое.</s><s>Второе.</s></p><p>Третье <emphasis>слово</emphasis>.</p></speak>")
        assertEquals(listOf(
            Segment("Первое."), Segment("Второе.", paragraph = true), Segment("Третье *слово*.", paragraph = true)
        ), s)
    }

    @Test fun numericRate() {
        assertEquals(listOf(Segment("{prosody:80:100}а")), Ssml.parse("<speak><prosody rate=\"80%\">а</prosody></speak>"))
        assertEquals(listOf(Segment("{prosody:120:100}а")), Ssml.parse("<speak><prosody rate=\"1.2\">а</prosody></speak>"))
    }

    @Test fun pitchWordTable() {
        assertEquals(listOf(Segment("{prosody:100:75}а")), Ssml.parse("<speak><prosody pitch=\"x-low\">а</prosody></speak>"))
        assertEquals(listOf(Segment("{prosody:100:0}а")), Ssml.parse("<speak><prosody pitch=\"robot\">а</prosody></speak>"))
    }

    @Test fun xmlProlog() {
        assertEquals(listOf(Segment("а")), Ssml.parse("<?xml version=\"1.0\"?><speak>а</speak>"))
    }

    @Test fun entityDecoding() {
        assertEquals(listOf(Segment("а & б <в> «г»")), Ssml.parse("<speak>а &amp; б &lt;в&gt; &#171;г&#187;</speak>"))
    }

    @Test fun prosodyAcrossBreakAndSentence() {
        // маркер ставится перед текстом, а не сразу за тегом: «Раз.{prosody} Два.» не резался бы по точке
        val s = Ssml.parse("<speak><prosody rate=\"fast\">Раз.<break time=\"1s\"/>Два.</prosody> Три.</speak>")
        assertEquals(listOf(Segment("{prosody:120:100}Раз.", breakMs = 1000), Segment("Два. {prosody}Три.")), s)
    }

    @Test fun blankTagsKeepsOffsets() {
        val src = "<speak>Раз <break time=\"1s\"/>два</speak>"
        val b = Ssml.blankTags(src)
        assertEquals(src.length, b.length)
        assertEquals("Раз", b.substring(7, 10)); assertEquals("два", b.substring(src.indexOf("два"), src.indexOf("два") + 3))
        assertTrue(b.trim().split(Regex("\\s+")) == listOf("Раз", "два"))
    }

    @Test fun unmatchedClosingProsody() {
        assertEquals(listOf(Segment("а б")), Ssml.parse("<speak>а</prosody> б</speak>"))
    }
}
