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
