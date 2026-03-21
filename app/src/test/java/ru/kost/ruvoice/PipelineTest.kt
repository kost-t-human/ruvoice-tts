package ru.kost.ruvoice

import org.junit.Assert.assertEquals
import org.junit.Test
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
}
