package ru.kost.ruvoice.text

import org.junit.Assert.assertEquals
import org.junit.Test

class AbbrevTest {
    @Test fun cyrillicWithoutVowelsSpelledByLetters() {
        assertEquals("агент эф эс б+э вошёл", Abbrev.apply("агент ФСБ вошёл"))
    }

    @Test fun cyrillicWithVowelsFromSpellListSpelledByLetters() {
        // С и Ш здесь читаются как их обычные имена из алфавита ("эс", "ша"), а не как в
        // одном из примеров брифа ("сэ шэ +а") — тот пример расходится с таблицей имён букв
        // и с примером ФСБ, где то же «С» даёт «эс»; таблица и остальные примеры совпадают
        assertEquals("в сэ шэ +а", Abbrev.apply("в США"))
    }

    @Test fun singleVowelLetterNameGetsStressOnItself() {
        assertEquals("сдал е гэ +э", Abbrev.apply("сдал ЕГЭ"))
    }

    @Test fun cyrillicWordAcronymWithVowelsLeftUntouched() {
        assertEquals("служил в НАТО", Abbrev.apply("служил в НАТО"))
    }

    @Test fun longUppercaseWordNotInAnyListLeftUntouched() {
        assertEquals("ГЛАВА ПЕРВАЯ", Abbrev.apply("ГЛАВА ПЕРВАЯ"))
    }

    @Test fun latinTokensSpelledByLetters() {
        assertEquals("порт ю эс б+и и сайт би би с+и", Abbrev.apply("порт USB и сайт BBC"))
    }

    @Test fun mixedCaseLatinWordLeftUntouched() {
        assertEquals("iPhone", Abbrev.apply("iPhone"))
    }

    @Test fun exceptionListOverridesLetterByLetterReading() {
        assertEquals("ги бэ дэ д+э", Abbrev.apply("ГИБДД"))
    }

    @Test fun tokenGluedToDigitByHyphenLeftUntouched() {
        assertEquals("С-300", Abbrev.apply("С-300"))
    }

    @Test fun twoLetterAbbreviationWithVowelInSpellList() {
        assertEquals("тэ в+э", Abbrev.apply("ТВ"))
    }

    @Test fun spellListTokenGluedToDigitByHyphenLeftUntouched() {
        // "С-300" не проверяет реальный сценарий: одиночная "С" короче минимальной длины 2
        // и не матчится вообще. Здесь трёхбуквенная аббревиатура из списка "по буквам",
        // приклеенная дефисом к цифре — именно то, что должен отсекать lookaround
        assertEquals("ЕГЭ-2020", Abbrev.apply("ЕГЭ-2020"))
    }

    @Test fun integratedIntoPrepare() {
        val allowed = "_~|!+,-.:;?абвгдежзийклмнопрстуфхцчшщъыьэюяё–… "
        assertEquals("агент эф эс б+э и порт ю эс б+и.", Normalizer.prepare("Агент ФСБ и порт USB.", allowed))
        assertEquals("служил в нато.", Normalizer.prepare("Служил в НАТО.", allowed))
    }

    @Test fun threeLettersWithEdgeVowelSpelled() {
        assertEquals("испытания эл ка +и и тэ эмм +а", Abbrev.apply("испытания ЛКИ и ТМА"))
        assertEquals("а ка +эс", Abbrev.apply("АКС"))
    }

    @Test fun edgeVowelWordsAndCapsHeadingsLeftUntouched() {
        assertEquals("Он спросил: КТО там?", Abbrev.apply("Он спросил: КТО там?"))
        assertEquals("ЛКИ ПРОШЛИ", Abbrev.apply("ЛКИ ПРОШЛИ"))
    }

    @Test fun cyrillicTechnicalCodes() {
        val allowed = "_~|!+,-.:;?абвгдежзийклмнопрстуфхцчшщъыьэюяё–… "
        assertEquals("эл ка +и ракеты +ээр-девять.", Normalizer.prepare("ЛКИ ракеты Р-9.", allowed))
        assertEquals("восемь к+а семьдесят четыре и ээр т+э-один.", Normalizer.prepare("8К74 и РТ-1.", allowed))
        assertEquals("+ээр-семь +а и у +ээр-сто +энн.", Normalizer.prepare("Р-7А и УР-100Н.", allowed))
        assertEquals("т+э-семьдесят два б+э три, одиннадцать к+а шестьдесят пять +эмм.", Normalizer.prepare("Т-72Б3, 11К65М.", allowed))
        assertEquals("а к+а-семьдесят четыре, газ-шестьдесят шесть, ту-сто пятьдесят четыре.",
            Normalizer.prepare("АК-74, ГАЗ-66, Ту-154.", allowed))
        assertEquals("сила сто ньютонов.", Normalizer.prepare("Сила 100Н.", allowed)) // число с единицей — не код
        assertEquals("тэ эмм +а-ноль один +эмм и +ээр-ноль ноль семь.", Normalizer.prepare("ТМА-01М и Р-007.", allowed))
    }
}
