package ru.kost.ruvoice

import org.junit.Assert.*
import org.junit.Test
import ru.kost.ruvoice.text.Normalizer

/** Тире доходит до модели тем знаком, который она знает: v5_5_ru — «–», cis-пак — «—». */
class DashPauseTest {
    private val cisAllowed = "+,-.:;?hабвгдежзийклмнопрстуфхцчшщъыьэюяё—… "

    @Test fun dashSurvivesToSequence() {
        val d = TestData.data()
        for (t in listOf("Он замолчал — и вышел.", "Он замолчал – и вышел.", "Он замолчал - и вышел.")) {
            val prepared = Normalizer.prepare(t, d.sym.allowed)
            assertEquals(t, "он замолчал – и вышел.", prepared)
            assertTrue(d.sym.symbolToId.getValue('–') in d.sym.sequence(prepared).map { it.toInt() })
            assertEquals(t, "он замолчал — и вышел.", Normalizer.prepare(t, cisAllowed))
        }
        assertEquals("кто-то", Normalizer.prepare("кто-то", cisAllowed))
    }
}
