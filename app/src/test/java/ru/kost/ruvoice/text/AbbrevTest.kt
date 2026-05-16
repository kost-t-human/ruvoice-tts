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
}
