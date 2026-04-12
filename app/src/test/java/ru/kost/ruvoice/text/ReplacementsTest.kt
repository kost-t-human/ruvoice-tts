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
}
