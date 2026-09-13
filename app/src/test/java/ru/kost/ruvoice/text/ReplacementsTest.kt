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

    @Test fun regexRuleWithMissingGroupDoesNotCrashOtherRules() {
        // $2 при одной группе — на подстановке вылетает IndexOutOfBoundsException, правило
        // должно быть пропущено (текст этого куска остаётся как есть), но не ронять apply()
        val r = Replacements.parse(listOf("""~(\d+) = $2""", "кот = к+от"))
        assertEquals("5 к+от", r.apply("5 кот"))
    }

    @Test fun regexRuleWithLoneDollarDoesNotCrashOtherRules() {
        // одинокий $ в замене — IllegalArgumentException на подстановке, правило пропускается
        val r = Replacements.parse(listOf("""~цена(\d+) = $ руб.""", "кот = к+от"))
        assertEquals("цена100 к+от", r.apply("цена100 кот"))
    }
}

class ReplacementsRegexTest {
    @Test fun regexKeyMayContainEqualsInsideLookaround() {
        // разделитель для regex-строк — « = » с пробелами, «=» внутри (?<=…) строку не рвёт
        val r = Replacements.parse(listOf("""~(?<=\d)=(?=\d) = равно"""))
        assertEquals("2равно2", r.apply("2=2"))
        assertEquals("(?<=\\d)=(?=\\d)" to "равно", Replacements.split("""~(?<=\d)=(?=\d) = равно""")?.let { it.first.removePrefix("~") to it.second })
    }

    @Test fun regexLineWithoutSpacedSeparatorSplitsAtFirstEquals() {
        assertEquals("~\\d+" to "число", Replacements.split("~\\d+=число"))
    }

    @Test fun inlineFlagTurnsCaseSensitivityOn() {
        val r = Replacements.parse(listOf("~(?-i)Бог = Б+ог"))
        assertEquals("Б+ог и бог", r.apply("Бог и бог"))
    }

    @Test fun patternErrorReportsBrokenRegexOnly() {
        assertNotNull(Replacements.patternError("[("))
        assertNull(Replacements.patternError("""(\d+)\s*шт\.?"""))
    }

    @Test fun replacementErrorCatchesWhatWouldFailAtSubstitution() {
        assertNotNull(Replacements.replacementError("""(\d+)""", "$2"))
        assertNull(Replacements.replacementError("""(\d+)""", "$1 и $0"))
        assertNotNull(Replacements.replacementError("x", "$ руб."))
        assertNull(Replacements.replacementError("x", "\\$ руб."))
        assertNotNull(Replacements.replacementError("x", "хвост\\"))
        assertNull(Replacements.replacementError("[(", "$1"))
    }
}
