package ru.kost.ruvoice.text

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.kost.ruvoice.TestData

class SplitterTest {
    @Test fun paragraphsByNewline() {
        assertEquals(listOf("Первый абзац.", "Второй."), Splitter.paragraphs("Первый абзац.\n\nВторой.\n"))
    }

    @Test fun sentencesByPunctuation() {
        assertEquals(listOf("Он ждал.", "Никто не пришёл!", "Почему?", "Всё…", "Конец"),
            Splitter.sentences("Он ждал. Никто не пришёл! Почему? Всё… Конец"))
    }

    @Test fun keepsInitialsTogether() {
        assertEquals(listOf("Л. Н. Толстой родился в 1828 г. в Ясной Поляне.", "Он писал."),
            Splitter.sentences("Л. Н. Толстой родился в 1828 г. в Ясной Поляне. Он писал."))
        assertEquals(listOf("Раз.", "Два!"), Splitter.sentences("Раз. Два!"))
    }

    @Test fun keepsAbbreviationStopWordsTogether() {
        assertEquals(listOf("Смотри стр. 5 и рис. 2.", "Дальше идёт текст."),
            Splitter.sentences("Смотри стр. 5 и рис. 2. Дальше идёт текст."))
        assertEquals(listOf("Он ушёл.", "Она осталась."), Splitter.sentences("Он ушёл. Она осталась."))
    }

    @Test fun okIsNotAnAbbreviationStopWord() {
        // review t17 п.6 (Splitter.kt:10): «ок» — разговорное слово в диалогах, не сокращение.
        assertEquals(listOf("Хорошо, ок.", "Идём дальше."), Splitter.sentences("Хорошо, ок. Идём дальше."))
    }

    @Test fun longSentenceSplitsAtComma() {
        val long = (1..30).joinToString(", ") { "слово$it" } + "."
        val parts = Splitter.sentences(long, maxLen = 80)
        assert(parts.all { it.length <= 80 }) { parts }
        assertEquals(long.replace(",", ""), parts.joinToString(" ").replace(",", "").replace("  ", " "))
    }

    @Test fun sequenceMatchesGolden() {
        val d = TestData.data()
        val g = TestData.golden()
        for (i in 0 until g.length()) {
            val o = g.getJSONObject(i)
            val exp = o.getJSONArray("ids").let { a -> LongArray(a.length()) { a.getLong(it) } }
            assertEquals(o.getString("text"), exp.toList(), d.sequence(o.getString("accented")).toList())
        }
    }
}
