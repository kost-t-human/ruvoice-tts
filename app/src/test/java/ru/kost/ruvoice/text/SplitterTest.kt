package ru.kost.ruvoice.text

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.kost.ruvoice.TestData

class SplitterTest {
    @Test fun paragraphsByNewline() {
        assertEquals(listOf("Первый абзац.", "Второй."), Splitter.paragraphs("Первый абзац.\n\nВторой.\n"))
    }

    @Test fun dehyphenatesLineWrap() {
        // Task 18 п.1: строчная после переноса — деление слова, дефис и перенос убираем.
        assertEquals(listOf("собака"), Splitter.paragraphs("со-\nбака"))
    }

    @Test fun keepsHyphenForCapitalizedCompound() {
        // Task 18 п.1: заглавная после переноса — составное слово, дефис оставляем.
        assertEquals(listOf("Санкт-Петербург"), Splitter.paragraphs("Санкт-\nПетербург"))
    }

    @Test fun crlfLineEndingsAreNormalizedForDehyphenation() {
        // финальный fix-раунд п.9: hyphenBreakRe ждёт «-\n» вплотную, «-\r\n» без нормализации
        // не матчится — дефис после переноса строки не убирается.
        assertEquals(listOf("собака"), Splitter.paragraphs("со-\r\nбака"))
    }

    @Test fun singleNewlineBeforeLowercaseIsSpace() {
        // Task 18 п.2: одиночный перенос строки внутри предложения — не новый абзац.
        assertEquals(listOf("Он шёл домой.", "Новый абзац."),
            Splitter.paragraphs("Он шёл\nдомой.\n\nНовый абзац."))
    }

    @Test fun singleNewlineBeforeUppercaseIsParagraphBreak() {
        assertEquals(listOf("Он шёл", "Домой"), Splitter.paragraphs("Он шёл\nДомой"))
    }

    @Test fun keepsHyphenForParticleAfterBreak() {
        // review round 1 п.3: «то» после переноса — частица, не окончание слова.
        assertEquals(listOf("кто-то"), Splitter.paragraphs("кто-\nто"))
    }

    @Test fun keepsHyphenForShortPrefixBeforeBreak() {
        // review round 1 п.3: «по» перед переносом — короткая приставка-предлог.
        assertEquals(listOf("по-русски"), Splitter.paragraphs("по-\nрусски"))
        assertEquals(listOf("из-за"), Splitter.paragraphs("из-\nза"))
        assertEquals(listOf("кое-кто"), Splitter.paragraphs("кое-\nкто"))
        assertEquals(listOf("во-первых"), Splitter.paragraphs("во-\nпервых"))
    }

    // review round 1 п.1: реальный пайплайн режет на предложения ДО Normalizer.prepare(),
    // поэтому Splitter.sentences() сам должен чистить пунктуацию через Normalizer.punctuation().
    @Test fun sentencesCleanUpPunctuationBeforeSplitting() {
        assertEquals(listOf("Всё…", "Дальше."), Splitter.sentences("Всё. . . Дальше."))
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
