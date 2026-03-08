package ru.kost.ruvoice.text

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.kost.ruvoice.TestData

class SentenceTypeTest {
    private val d = TestData.data()

    @Test fun goldenTypes() {
        val g = TestData.golden()
        for (i in 0 until g.length()) {
            val o = g.getJSONObject(i)
            assertEquals(o.getString("text"), o.getString("type"), SentenceType.classify(o.getString("text"), d))
        }
    }

    @Test fun typeIdsPerChar() {
        // prepared: "а? б." → две «предложения», sos + 5 символов + eos = 7
        val ids = SentenceType.typeIds("а? б.", "general_q|st", 7, d)
        assertEquals(listOf(2L, 2L, 2L, 2L, 0L, 0L, 2L), ids.toList())
    }
}
