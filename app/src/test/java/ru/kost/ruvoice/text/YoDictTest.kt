package ru.kost.ruvoice.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.kost.ruvoice.TestData
import java.io.File

/** Словарь бесспорной «ё» (assets/eyo_safe.txt) и его место в Stress.accentorPass. */
class YoDictTest {
    private val d = TestData.data()
    private val yo = File(TestData.root(), "app/src/main/assets/eyo_safe.txt").bufferedReader().useLines { YoDict(it) }

    /** Ударение на первую гласную, «ё» модель не ставит никогда — всё, что с «ё», пришло из словаря. */
    private val firstVowel = object : StressModels {
        override fun accentor(words: List<String>) = Pair(
            Array(words.size) { FloatArray(10).also { it[0] = 1f } },
            Array(words.size) { FloatArray(7).also { it[0] = 0f } })
        override fun homo(ids: List<LongArray>, starts: LongArray, ends: LongArray) = FloatArray(ids.size) { 0.9f }
    }

    @Test fun formatAsInEyo() {
        assertTrue(yo.size > 100_000)
        assertEquals("ёжик", yo.restore("ежик"))
        assertEquals("Ёжик", yo.restore("Ежик"))          // строчное слово подходит и с заглавной
        assertEquals("зелёного", yo.restore("зеленого"))   // «зелён(ый|ого|…)» — формы в скобках
        assertNull(yo.restore("ЕЖИК"))                     // капс — не наш случай
        assertNull(yo.restore("все"))                      // спорные слова в safe.txt не входят
        assertNull(yo.restore("небо"))
        assertNull(yo.restore("Киев"))                     // «_киёв» — только строчными
        assertEquals("киёв", yo.restore("киев"))
    }

    @Test fun accentorPassUsesDict() {
        val s = Stress(d, firstVowel, yo = yo)
        assertEquals("+ёжик ш+ёл", s.apply("ежик шел"))           // «ё» из словаря, ударение на неё
        assertEquals("+ёлки-п+алки", s.apply("елки-палки"))         // часть через дефис ищется без дефиса
        assertEquals("м+ама", s.apply("мама"))                     // не словарное — как было
        assertEquals("+ежик", Stress(d, firstVowel, yo = yo, rules = Rules(off = setOf("yo"))).apply("ежик"))
        // слово из пользовательского словаря словарь «ё» не трогает: иначе пользовательская запись не найдётся
        assertEquals("еж+ик", Stress(d, firstVowel, mapOf("ежик" to "еж+ик"), yo = yo).apply("ежик"))
        // слово уже с ударением из фразы/текста — не трогаем
        assertEquals("еж+ик", s.apply("еж+ик"))
    }
}
