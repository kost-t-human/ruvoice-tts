package ru.kost.ruvoice.text

import org.junit.Assert.*
import org.junit.Test

class ReplacementsTest {
    @Test fun wholeWordCaseInsensitiveLiteralReplacement() {
        val r = Replacements.parse(listOf("Гарри = Г+арри"))
        assertEquals("Г+арри и Г+арри!", r.apply("Гарри и гарри!"))
    }

    @Test fun phraseWithStressOnNeighbourWord() {
        val r = Replacements.parse(listOf("старый замок = старый з+амок"))
        assertEquals("старый з+амок стоял.", r.apply("Старый замок стоял."))
    }

    @Test fun emptyReplacementDeletesKeyAndCollapsesSpaces() {
        val r = Replacements.parse(listOf("[1] ="))
        assertEquals("текст дальше", r.apply("текст[1] дальше"))
    }

    @Test fun deletesWholeWordOnly() {
        val r = Replacements.parse(listOf("мама = "))
        assertEquals("мамаша", r.apply("мамаша"))
    }

    @Test fun longerKeyWinsOverShorter() {
        val r = Replacements.parse(listOf("кот = к+от", "кот в сапогах = к+от в сапог+ах"))
        assertEquals("к+от в сапог+ах", r.apply("кот в сапогах"))
    }

    @Test fun ignoresCommentsAndLinesWithoutEquals() {
        val r = Replacements.parse(listOf("# коммент", "без равенства", "", "кот = к+от"))
        assertEquals("к+от", r.apply("кот"))
    }

    @Test fun regexRuleWithBackreferences() {
        val r = Replacements.parse(listOf("""~(\d+)-(\d+) = $1 по $2"""))
        assertEquals("стр. 5 по 7 и 10 по 12", r.apply("стр. 5-7 и 10-12"))
    }

    @Test fun brokenRegexRuleIsSkippedSilently() {
        val r = Replacements.parse(listOf("~[( = что-то", "кот = к+от"))
        assertEquals("к+от", r.apply("кот"))
    }

    @Test fun skipMarkerActsAsEmptyReplacement() {
        val r = Replacements.parse(listOf("Автор = {skip}"))
        assertEquals("Глава 1", r.apply("Глава 1 Автор"))
    }
}
