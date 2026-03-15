package ru.kost.ruvoice.text

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.kost.ruvoice.TestData

class StressTest {
    private val d = TestData.data()

    /** ударение на первую гласную, ё нет */
    private val firstVowel = object : StressModels {
        override fun accentor(words: List<String>) = Pair(
            Array(words.size) { FloatArray(10).also { it[0] = 1f } },
            Array(words.size) { FloatArray(7).also { it[0] = 1f } })
        override fun homo(ids: List<LongArray>, starts: LongArray, ends: LongArray) = FloatArray(ids.size) { 0.9f }
    }

    @Test fun exceptionsWinOverModel() {
        // «его» → [2,-1]: ударение перед индексом 2
        assertEquals("ег+о", Stress(d, firstVowel).apply("его"))
    }

    @Test fun modelStressAndSingleVowel() {
        assertEquals("+а м+ама", Stress(d, firstVowel).apply("а мама"))
    }

    @Test fun userStressIsKept() {
        assertEquals("мам+а", Stress(d, firstVowel).apply("мам+а"))
    }

    @Test fun homographPicksSortedVariantByPrediction() {
        // sigmoid 0.9 → round = 1 → sorted(homodict["замок"])[1]
        val variants = d.homodict.getValue("замок").sorted()
        val out = Stress(d, firstVowel).apply("замок")
        assertEquals(variants[1], out)
    }

    @Test fun userDictAppliedLast() {
        val s = Stress(d, firstVowel, mapOf("мама" to "мам+а"))
        assertEquals("мам+а", s.apply("мама"))
    }

    @Test fun userDictKeepsOriginalCapitalization() {
        // словарное значение хранится строчными, регистр восстанавливаем по исходному слову
        val s = Stress(d, firstVowel, mapOf("мама" to "мам+а"))
        assertEquals("Мам+а", s.apply("Мама"))
    }

    @Test fun nbspTreatedAsWordSeparator() {
        // NBSP (U+00A0): JVM \s его не матчит, Python \s матчит. «в» без гласной остаётся как есть,
        // у «доме» firstVowel-заглушка ставит ударение перед первой гласной («о»): «д+оме».
        assertEquals("в\u00A0д+оме", Stress(d, firstVowel).apply("в\u00A0доме"))
    }

    @Test fun punctuationAndHyphenPreserved() {
        assertEquals("кт+о-то, +а т+ы?", Stress(d, firstVowel).apply("кто-то, а ты?"))
    }
}
