package ru.kost.ruvoice.text

import org.junit.Assert.assertEquals
import org.junit.Test

/** Что TalkBack шлёт движку помимо обычного текста: TtsSpan пунктуации и знаки из подписей интерфейса. */
class TalkBackTextTest {
    @Test fun spanPunctuation() {
        // пунктуация «Все»: запятая и точка — спаны с названием знака
        assertEquals("Привет запятая мир точка", SpanText.substitute("Привет,мир.", listOf(Triple(6, 7, "запятая"), Triple(10, 11, "точка"))))
        assertEquals("а запятая б", SpanText.substitute("а, б", listOf(Triple(1, 2, "запятая"))))
        assertEquals("15 дефисов", SpanText.substitute("---------------", listOf(Triple(0, 15, "15 дефисов"))))
    }

    @Test fun spanBroken() {
        assertEquals("а,б", SpanText.substitute("а,б", emptyList()))
        // пересекающийся и вылезающий за текст — пропускаются
        assertEquals("а запятая б", SpanText.substitute("а,б", listOf(Triple(1, 2, "запятая"), Triple(1, 3, "x"), Triple(2, 9, "y"))))
    }

    @Test fun interfaceSigns() {
        assertEquals("замок, Системный", Normalizer.punctuation("замок · Системный"))
        assertEquals("«21» – «двадцать один»", Normalizer.punctuation("«21» → «двадцать один»"))
        assertEquals("меню – «Настройки»", Normalizer.punctuation("меню → «Настройки»"))
        // точка в единицах без пробелов не трогается
        assertEquals("Н·м", Normalizer.punctuation("Н·м"))
    }

    @Test fun switchedOff() {
        assertEquals("Имена (выключено)", Normalizer.numbers("Имена (выкл.)"))
        assertEquals("с одного по пять вкл.", Normalizer.numbers("с 1 по 5 вкл."))
    }
}
