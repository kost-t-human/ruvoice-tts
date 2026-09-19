package ru.kost.ruvoice.text

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.kost.ruvoice.TestData

/** Твёрдое [э] после ударений: assets/hard_e.txt из tools/hard_e.py. */
class HardETest {
    private val h = HardE(TestData.root().resolve("app/src/main/assets/hard_e.txt").readLines())

    @Test fun plusStaysAndCaseKept() {
        // «+» снимается, правило меняет только буквы, «+» возвращается на место; регистр из текста
        assertEquals("Эн+эргия, энэрг+ичный, энэрг+етика и т+эст.", h.apply("Эн+ергия, энерг+ичный, энерг+етика и т+ест."))
        assertEquals("б+изнэс-пл+ан, про+экт, тэстост+эрон", h.apply("б+изнес-пл+ан, про+ект, тестост+ерон"))
    }

    @Test fun shortStemsDoNotSpill() {
        // «тест*» зацепил бы «тесто», «темп*» — «температуру», «мистер*» — «мистерию»: там формы перечислены
        assertEquals("т+есто, температ+ура, мист+ерия, м+истэр, т+эмп", h.apply("т+есто, температ+ура, мист+ерия, м+истер, т+емп"))
    }

    @Test fun stressAppliesItAfterDictionaries() {
        val d = TestData.data()
        val firstVowel = object : StressModels {
            override fun accentor(words: List<String>) = Pair(Array(words.size) { FloatArray(10).also { it[0] = 1f } }, Array(words.size) { FloatArray(7).also { it[0] = 1f } })
            override fun homo(ids: List<LongArray>, starts: LongArray, ends: LongArray) = FloatArray(ids.size) { 0.9f }
        }
        // словарь ищет «энергия» через «е» и ставит «+»; «э» подставляется уже в результат
        assertEquals("эн+эргия т+эст", Stress(d, firstVowel, mapOf("энергия" to "эн+ергия"), hardE = h).apply("энергия тест"))
        assertEquals("+энергия", Stress(d, firstVowel, hardE = h, rules = Rules(setOf("hard_e"))).apply("энергия"))
    }
}
