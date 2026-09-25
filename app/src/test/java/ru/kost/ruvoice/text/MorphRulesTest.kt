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

    @Test fun caseByNoun() {
        assertEquals("одному другу", n("1 другу"))
        assertEquals("в одном доме", n("в 1 доме"))
        assertEquals("одной двери", n("1 двери"))
        assertEquals("двадцатью одним столом", n("21 столом"))
        assertEquals("пятью путями", n("5 путями"))
        assertEquals("семи гномам", n("7 гномам"))
        assertEquals("три друзей", n("3 друзей"))
        assertEquals("два–три рабочих дня", n("2–3 рабочих дня"))
        assertEquals("пять друзей", n("5 друзей"))
        assertEquals("пять минут", n("5 минут"))
        assertEquals("пять программ", n("5 программ"))
        assertEquals("два стола", n("2 стола"))
        // именительный/винительный — как раньше
        assertEquals("три дня", n("3 дня"))
        assertEquals("около пятисот рублей", n("около 500 рублей"))
    }

    @Test fun caseByAdjective() {
        assertEquals("последних двадцати-тридцати лет", n("последних 20-30 лет"))
        assertEquals("первых пяти минут", n("первых 5 минут"))
        assertEquals("в последних пяти случаях", n("в последних 5 случаях"))
        assertEquals("последним пяти годам", n("последним 5 годам"))
        assertEquals("за последние пять лет", n("за последние 5 лет"))
        assertEquals("первые три дня", n("первые 3 дня"))
        // одушевлённое: «-их» — родительный или винительный, оставляем
        assertEquals("последних пять друзей", n("последних 5 друзей"))
    }

    @Test fun adjectiveAbbreviations() {
        assertEquals("снижение государственного контроля", n("снижение гос. контроля"))
        assertEquals("положение официальной часовой стрелки", n("положение офиц. часовой стрелки"))
        assertEquals("порядок государственной часовой службы", n("порядок гос. часовой службы"))
        assertEquals("Государственная служба", n("Гос. служба"))
        assertEquals("в государственных органах", n("в гос. органах"))
        assertEquals("техническое задание", n("тех. задание"))
        assertEquals("с греческого языка", n("с греч. языка"))
        assertEquals("от латинского homo", n("от лат. homo"))
        // «службы» — Р.п. ед. или И./В.п. мн.: решает существительное перед сокращением
        assertEquals("порядок государственной службы", n("порядок гос. службы"))
        assertEquals("реформа международного контроля и политической системы", n("реформа междунар. контроля и полит. системы"))
        assertEquals("проверил гос. службы", n("проверил гос. службы"))
        assertEquals("все гос. службы", n("все гос. службы"))
        assertEquals("правительство и гос. службы", n("правительство и гос. службы"))
        // «ж/д» — прилагательное в роде и падеже существительного, без него после предлога — «железная дорога»
        assertEquals("железнодорожная станция", n("ж/д станция"))
        assertEquals("до железнодорожного вокзала", n("до ж/д вокзала"))
        assertEquals("на железнодорожных путях", n("на ж/д путях"))
        assertEquals("по железной дороге можно доехать", n("по ж/д можно доехать"))
        assertEquals("до железной дороги.", n("до ж/д."))
        // неизвестное слово следом — как было
        assertEquals("гос. абырвалг", n("гос. абырвалг"))
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
