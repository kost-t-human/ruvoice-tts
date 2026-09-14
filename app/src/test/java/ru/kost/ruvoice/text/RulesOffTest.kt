package ru.kost.ruvoice.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.kost.ruvoice.Pipeline
import ru.kost.ruvoice.TestData

/** Переключатели правил (вкладка «Правила»): выключенное правило не трогает текст. */
class RulesOffTest {
    private val d = TestData.data()
    private fun off(vararg keys: String) = Rules(off = keys.toSet())

    private val firstVowel = object : StressModels {
        override fun accentor(words: List<String>) = Pair(
            Array(words.size) { FloatArray(10).also { it[0] = 1f } },
            Array(words.size) { FloatArray(7).also { it[0] = 1f } })
        override fun homo(ids: List<LongArray>, starts: LongArray, ends: LongArray) = FloatArray(ids.size) { 0.9f }
    }

    @Test fun allOnByDefault() {
        assertEquals("в двадцатом веке", Normalizer.numbers("в XX веке"))
        assertEquals("в двадцатом веке", Normalizer.numbers("в XX веке", Rules()))
    }

    @Test fun romanOff() {
        assertEquals("в XX веке", Normalizer.numbers("в XX веке", off("roman")))
    }

    @Test fun romanNameOffKeepsTriggerRoman() {
        assertEquals("Пётр I в двадцатом веке", Normalizer.numbers("Пётр I в XX веке", off("roman_name")))
    }

    @Test fun numbersOffLeavesDigits() {
        assertEquals("глава 3", Normalizer.numbers("глава 3", off("numbers")))
    }

    @Test fun unitsOff() {
        assertEquals("около три км", Normalizer.numbers("около 3 км", off("units", "cases")))
    }

    @Test fun abbrevOff() {
        assertEquals("см. рис. три", Normalizer.numbers("см. рис. 3", off("abbrev")))
    }

    @Test fun spellCyrOffLatStillOn() {
        assertEquals("ФСБ и ю эс б+и", Abbrev.apply("ФСБ и USB", off("spell_cyr")))
    }

    @Test fun spellLatOff() {
        assertEquals("эф эс б+э и USB", Abbrev.apply("ФСБ и USB", off("spell_lat")))
    }

    @Test fun latinOffStillLowercases() {
        assertEquals("iphone", Normalizer.latin("iPhone", off("latin")))
    }

    @Test fun homoglyphsOff() {
        // латинская «o» внутри кириллического слова остаётся (транслитерация тоже выключена,
        // иначе она сама прочитает «o» как «о»)
        assertEquals("прoблема", Normalizer.latin("прoблема", off("homoglyphs", "latin")))
        assertEquals("проблема", Normalizer.latin("прoблема", Rules()))
    }

    @Test fun punctuationOff() {
        assertEquals("Ну!!! Да...", Normalizer.punctuation("Ну!!! Да...", off("punct")))
    }

    @Test fun speechOffNoSpeechSegments() {
        val s = Pipeline.plan("— Привет, — сказал он.", d, 0, 0, rules = off("speech"))
        assertEquals(1, s.size)
        assertTrue(s.none { it.speech })
    }

    @Test fun speechOnSplitsSpeech() {
        val s = Pipeline.plan("— Привет, — сказал он.", d, 0, 0, rules = Rules())
        assertTrue(s.any { it.speech })
    }

    @Test fun ssmlOffReadsTagsAsText() {
        val s = Pipeline.plan("<speak>Раз.</speak>", d, 0, 0, rules = off("ssml"))
        assertEquals("<speak>Раз.</speak>", s.single().text)
    }

    @Test fun dehyphenOff() {
        // мягкий перенос ещё работает: \n → пробел, дефис остаётся
        assertEquals(listOf("со- бака"), Splitter.paragraphs("со-\nбака", off("dehyphen")))
        assertEquals(listOf("собака"), Splitter.paragraphs("со-\nбака"))
    }

    @Test fun softBreakOffMakesParagraph() {
        assertEquals(listOf("раз", "два"), Splitter.paragraphs("раз\nдва", off("soft_break")))
        assertEquals(listOf("раз два"), Splitter.paragraphs("раз\nдва"))
    }

    @Test fun maxLenLimitsChunk() {
        val long = (1..60).joinToString(" ") { "слово" }  // 359 символов
        assertEquals(1, Splitter.sentences(long).size)
        assertTrue(Splitter.sentences(long, Rules(maxLen = 100)).size >= 3)
    }

    @Test fun intonationOff() {
        assertEquals("general_q", SentenceType.classify("Ты идёшь?", d))
        assertEquals("st", SentenceType.classify("Ты идёшь?", d, off("intonation")))
    }

    @Test fun homoOffFallsToAccentor() {
        assertEquals("з+амок", Stress(d, firstVowel, rules = off("homo")).apply("замок"))
    }

    @Test fun accentorOffLeavesWord() {
        assertEquals("мама", Stress(d, firstVowel, rules = off("accentor")).apply("мама"))
        // словарь исключений и пользовательский словарь по-прежнему работают
        assertEquals("мам+а", Stress(d, firstVowel, mapOf("мама" to "мам+а"), off("accentor")).apply("мама"))
    }

    @Test fun keysCoverEveryRule() {
        // каждый ключ, который знает UI, должен быть в списке — иначе тумблер ничего не выключает
        for (k in listOf("numbers", "cases", "roman", "roman_name", "dates", "day_month", "years", "times",
            "units", "degrees", "currency", "fractions", "spoons", "gen_suffix", "sections", "thousands",
            "footnotes", "abbrev", "spell_cyr", "spell_lat", "latin", "homoglyphs", "dehyphen", "soft_break",
            "punct", "ssml", "homo", "accentor", "intonation", "pause_semicolon", "lead_in"))
            assertTrue(k, k in Rules.KEYS)
    }
}
