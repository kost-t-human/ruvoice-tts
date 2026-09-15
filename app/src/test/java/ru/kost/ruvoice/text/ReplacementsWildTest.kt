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

    @Test fun hyphenPrefixMaskDoesNotTouchOtherWords() {
        val r = Replacements.parse(listOf("черно-* = чёрно-*"))
        assertEquals("чёрно-белый чёрного черно", r.apply("черно-белый чёрного черно"))
        assertEquals("черного", r.apply("черного"))
        assertEquals("записи «черного ящика».", r.apply("записи «черного ящика»."))
        assertEquals("чёрно-белый чёрного", Replacements.parse(listOf("черно-* = чёрно-*", "темно-* = тёмно-*", "желто-* = жёлто-*")).apply("черно-белый чёрного"))
    }

    @Test fun stressOnlyRuleKeepsCaseOfMatchedText() {
        val r = Replacements.parse(listOf("старый замок = старый з+амок", "дорого = д+орого"))
        assertEquals("Старый з+амок. СТАРЫЙ З+АМОК, д+орого", r.apply("Старый замок. СТАРЫЙ ЗАМОК, дорого"))
    }

    @Test fun literalMatchIsCaseInsensitiveAndKeepsTextAroundIt() {
        val r = Replacements.parse(listOf("дорого не надо = дорого не н+адо-то"))
        assertEquals("Тут дорого не н+адо-то Было", r.apply("Тут ДОРОГО не надо Было"))
    }

    @Test fun dollarPrefixMakesKeyCaseSensitive() {
        // «$Ворон» — имя, «ворон» — птица; по руководству Демагога «$» включает учёт регистра
        val r = Replacements.parse(listOf("\$Ворон поднял = В+орон поднял", "\$Серов* = Сер+ов*"))
        assertEquals("В+орон поднял голову, ворон поднял голову", r.apply("Ворон поднял голову, ворон поднял голову"))
        assertEquals("Сер+ова, серова", r.apply("Серова, серова"))
    }

    @Test fun doubledDollarAndHashAreLiteral() {
        val r = Replacements.parse(listOf("\$\$100 = сто долларов", "##2 = номер два", "# комментарий = нет"))
        assertEquals("дали сто долларов и номер два", r.apply("дали \$100 и #2"))
        assertEquals("# комментарий", r.apply("# комментарий"))
    }

    @Test fun duplicateKeyLastLineWins() {
        // словари Демагога и KooBAudio правят ошибки строкой в конце, не поиском старой
        val r = Replacements.parse(listOf("абсент = абс+ент", "авиа* = +авиа-*", "абсент = абс+энт"))
        assertEquals("абс+энт", r.apply("абсент"))
    }

    @Test fun valueWithoutStarKeepsCaptures() {
        // формат Говорилки/Демагога: «туник*=туни<к», «*графия=гра<фия», «ворот* города=воро<т го<рода»
        val r = Replacements.parse(listOf("туник* = тун+ик", "*графия = гр+афия", "*автобус* = авт+обус", "*ё* = е", "прочита* =",
            "ворот* города = вор+от г+орода"))
        assertEquals("тун+ика, фотогр+афия, микроавт+обусы", r.apply("туника, фотография, микроавтобусы"))
        assertEquals("все", r.apply("всё"))
        assertEquals("не то", r.apply("прочитанное не то"))
        assertEquals("на вор+отах г+орода", r.apply("на воротах города"))
    }

    @Test fun implicitStarsPlacement() {
        assertEquals("*вор+от* г+орода*", Replacements.implicitStars("*ворот* города*", "вор+от г+орода"))
        assertEquals("*- Аллё,*", Replacements.implicitStars("*- Але,*", "- Аллё,"))
        // слов не поровну: края ключа — на края замены, серединная «*» уходит в хвост вместе с последней
        assertEquals("Зм+ея*", Replacements.implicitStars("Змея Горыныч*", "Зм+ея"))
        assertEquals("*Зм+ея**", Replacements.implicitStars("*Змея гор* ыныч*", "Зм+ея"))
        assertEquals("Зм+ея", Replacements.implicitStars("Змея гор* ыныч", "Зм+ея"))
        // уже явные «*», пустая замена и ключ без маски — как есть
        assertEquals("а*", Replacements.implicitStars("а*", "а*"))
        assertEquals("", Replacements.implicitStars("а*", ""))
        assertEquals("б", Replacements.implicitStars("***", "б"))
    }
}
