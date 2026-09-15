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

    @Test fun goldenHomographContexts() {
        // контекст с [HOMO]-маркерами (окно 150 символов, чистка HomoSolver._clean_text) — как в Python
        val g = TestData.golden()
        var checked = 0
        for (i in 0 until g.length()) {
            val o = g.getJSONObject(i)
            if (!o.has("bert")) continue
            val exp = o.getJSONArray("bert").let { a -> List(a.length()) { a.getJSONObject(it).getString("marked") } }
            assertEquals(o.getString("prepared"), exp, Stress(d, firstVowel).tagHomos(o.getString("prepared")).map { it.marked })
            checked += exp.size
        }
        assert(checked >= 5) { "мало омографов в golden: $checked" }
    }

    @Test fun phraseBeatsModel() {
        // «замок казался очень тихим» есть во фразах Silero Stress → з+амок, хотя заглушка-BERT даёт зам+ок
        val neural = Stress(d, firstVowel).apply("замок")
        assertEquals("зам+ок", neural)
        assertEquals("З+амок к+азался +очень т+ихим.", Stress(d, firstVowel).apply("Замок казался очень тихим."))
    }

    @Test fun phraseOnlyWordWithoutPhraseIsLeftToAccentor() {
        // «толстая» есть только во фразах («людмила толстая»); без фразы слово идёт в accentor, не в BERT
        assertEquals("т+олстая", Stress(d, firstVowel).apply("толстая"))
        assertEquals("л+юдмила толст+ая", Stress(d, firstVowel).apply("людмила толстая"))
    }
}
