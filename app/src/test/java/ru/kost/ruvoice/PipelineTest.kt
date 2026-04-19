package ru.kost.ruvoice

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.kost.ruvoice.text.Replacements
import ru.kost.ruvoice.text.Segment

class PipelineTest {
    private val d = TestData.data()

    @Test fun plainTextToSentencesWithPauses() {
        val s = Pipeline.plan("Раз. Два!\nТри?", d, sentencePauseMs = 100, paragraphPauseMs = 300)
        assertEquals(listOf(
            Segment("Раз.", breakMs = 100), Segment("Два!", breakMs = 400, paragraph = true), Segment("Три?", breakMs = 100)
        ), s)
    }

    @Test fun ssmlKeepsProsodyPerSentence() {
        val s = Pipeline.plan("<speak><prosody rate=\"fast\">Раз. Два.</prosody><break time=\"1s\"/></speak>", d, 0, 300)
        assertEquals(listOf(Segment("Раз.", rate = 1.2f), Segment("Два.", rate = 1.2f, breakMs = 1000)), s)
    }

    @Test fun pauseMarkerInPlainTextBecomesBreak() {
        // маркер прямо в исходном тексте, без словаря замен
        val s = Pipeline.plan("Раз.{pause:700}Два.", d, sentencePauseMs = 0, paragraphPauseMs = 300)
        assertEquals(listOf(Segment("Раз.", breakMs = 700), Segment("Два.")), s)
    }

    @Test fun pauseMarkerAtStartProducesEmptyLeadingSegment() {
        val s = Pipeline.plan("{pause:300}Раз.", d, sentencePauseMs = 0, paragraphPauseMs = 0)
        assertEquals(listOf(Segment("", breakMs = 300), Segment("Раз.")), s)
    }

    @Test fun twoConsecutivePauseMarkersSum() {
        val s = Pipeline.plan("Раз.{pause:200}{pause:300}Два.", d, sentencePauseMs = 0, paragraphPauseMs = 0)
        assertEquals(listOf(Segment("Раз.", breakMs = 500), Segment("Два.")), s)
    }

    @Test fun pauseMarkerThroughReplacement() {
        val r = Replacements.parse(listOf("*** = {pause:800}"))
        val s = Pipeline.plan("Раз. *** Два.", d, sentencePauseMs = 0, paragraphPauseMs = 0, replacements = r)
        assertEquals(listOf(Segment("Раз.", breakMs = 800), Segment("Два.")), s)
    }

    @Test fun pauseMarkerOverflowClampedTo10000() {
        val s = Pipeline.plan("Раз.{pause:99999}Два.", d, sentencePauseMs = 0, paragraphPauseMs = 0)
        assertEquals(listOf(Segment("Раз.", breakMs = 10000), Segment("Два.")), s)
    }

    @Test fun severalSentencesInPieceBeforeMarker() {
        val s = Pipeline.plan("Раз. Два.{pause:500}Три.", d, sentencePauseMs = 0, paragraphPauseMs = 0)
        assertEquals(listOf(Segment("Раз."), Segment("Два.", breakMs = 500), Segment("Три.")), s)
    }
}
