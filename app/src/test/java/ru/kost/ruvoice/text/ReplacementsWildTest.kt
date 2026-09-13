package ru.kost.ruvoice.text

import org.junit.Assert.*
import org.junit.Test

/** Маска «*» и запятая в обычном ключе — формат словарей Демагога, теперь и наш. */
class ReplacementsWildTest {
    @Test fun starAtEndMatchesWholeWordWithPrefix() {
        val r = Replacements.parse(listOf("на прочита* не стоит = на прочита* не сто+ит"))
        assertEquals("на прочитанное не сто+ит.", r.apply("на прочитанное не стоит."))
        assertEquals("напрочитанное не стоит", r.apply("напрочитанное не стоит"))
    }

    @Test fun starInValueSubstitutesCapturedPart() {
        val r = Replacements.parse(listOf("программ* = прогр+амм*"))
        assertEquals("прогр+аммой, прогр+амм", r.apply("программой, программ"))
    }

    @Test fun starInValueStaysLiteralWhenKeyHasNoMask() {
        // *слово* — маркер логического ударения для Marks, замена без маски в ключе его не трогает
        val r = Replacements.parse(listOf("важно = *важно*", "да,нет = *да*, нет"))
        assertEquals("это *важно*", r.apply("это важно"))
        assertEquals("*да*, нет", r.apply("да, нет"))
    }

    @Test fun keyWithoutLettersKeepsStarsLiteral() {
        // «***» — разделитель сцен, маске не на что опереться, это текст как раньше
        val r = Replacements.parse(listOf("*** = {pause:800}"))
        assertEquals("Раз. {pause:800} Два.", r.apply("Raz. *** Два.".replace("Raz", "Раз")))
        assertEquals("а * б", r.apply("а * б"))
    }

    @Test fun starAtStartMatchesSuffix() {
        val r = Replacements.parse(listOf("*ходить = *ход+ить"))
        assertEquals("отъходить", r.apply("отъходить").let { it.replace("+", "") })
        assertEquals("уход+ить", r.apply("уходить"))
    }

    @Test fun loneStarMatchesAnyWord() {
        val r = Replacements.parse(listOf("нет * уже не стоит = нет * уже не сто+ит"))
        assertEquals("нет ничего уже не сто+ит", r.apply("нет ничего уже не стоит"))
    }

    @Test fun commaInKeyMatchesWithOrWithoutSpace() {
        val r = Replacements.parse(listOf("молока,не то = молок+а, не то"))
        assertEquals("молок+а, не то виски", r.apply("молока, не то виски"))
        assertEquals("молок+а, не то виски", r.apply("молока,не то виски"))
    }

    @Test fun longerKeyStillWinsThroughIndex() {
        val r = Replacements.parse(listOf("руки = р+уки", "его руки были = его р+уки были", "руки были в = руки были в+"))
        assertEquals("его р+уки были в тепле", r.apply("его руки были в тепле"))
    }

    @Test fun ruleAfterMatchedRuleOnSameWordStillApplies() {
        // первое правило меняет текст; второе якорится на слове, которого первое не тронуло
        val r = Replacements.parse(listOf("старый замок = старый з+амок", "замок стоял = замок сто+ял"))
        assertEquals("старый з+амок стоял", r.apply("старый замок стоял"))
        val r2 = Replacements.parse(listOf("старый замок = старый з+амок", "стоял = сто+ял"))
        assertEquals("старый з+амок сто+ял", r2.apply("старый замок стоял"))
    }

    @Test fun keyWithDigitsAndPunctuationIsLiteral() {
        val r = Replacements.parse(listOf("из \"РПГ-7\" ворот = из \"РПГ-7\" вор+от"))
        assertEquals("у из \"РПГ-7\" вор+от", r.apply("у из \"РПГ-7\" ворот"))
    }

    @Test fun literalMatchIsCaseInsensitiveAndKeepsTextAroundIt() {
        val r = Replacements.parse(listOf("дорого не надо = д+орого не надо"))
        assertEquals("Тут д+орого не надо Было", r.apply("Тут ДОРОГО не надо Было"))
    }
}
