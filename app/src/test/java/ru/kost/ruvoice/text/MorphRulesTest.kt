package ru.kost.ruvoice.text

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import ru.kost.ruvoice.TestData
import java.io.File

/** Правила нормализатора, которым нужна таблица морфологии (Normalizer.morph); без неё их проверяют NumbersTest/CasesTest. */
class MorphRulesTest {
    private fun n(s: String) = Normalizer.numbers(s)

    @Before fun load() { Normalizer.morph = Morph.open(File(TestData.root(), "app/src/main/assets/morph.bin")) }
    @After fun unload() { Normalizer.morph = null }

    @Test fun genderAfterOne() {
        assertEquals("одна ночь", n("1 ночь"))
        assertEquals("один конь", n("1 конь"))
        assertEquals("один путь", n("1 путь"))
        assertEquals("одно такси", n("1 такси"))
        assertEquals("одно сообщение", n("1 сообщение"))
        assertEquals("одно время", n("1 время"))
        assertEquals("двадцать одна минута", n("21 минута"))
        assertEquals("одну книгу", n("1 книгу"))
        assertEquals("одного коня", n("1 коня"))
        // общий род — по старой эвристике (окончание -а)
        assertEquals("одна сирота", n("1 сирота"))
        // неизвестное слово — по старой эвристике
        assertEquals("одна абракадабра", n("1 абракадабра"))
        // не существительное — не трогаем
        assertEquals("на один больше", n("на 1 больше"))
    }

    @Test fun genderAfterTwo() {
        assertEquals("две двери", n("2 двери"))
        assertEquals("две ночи", n("2 ночи"))
        assertEquals("два стола", n("2 стола"))
        assertEquals("два сообщения", n("2 сообщения"))
        assertEquals("пять минут", n("5 минут"))
    }

    @Test fun ordinalSuffixByNoun() {
        assertEquals("в третьей дивизии", n("в 3-й дивизии"))
        assertEquals("первый номер", n("1-й номер"))
        assertEquals("в третий день", n("в 3-й день"))
        assertEquals("вторые ножницы", n("2-е ножницы"))
        assertEquals("второе место", n("2-е место"))
        assertEquals("в двухтысячные годы", n("в 2000-е годы"))
        assertEquals("второе сентября", n("2-е сентября"))
    }
}
