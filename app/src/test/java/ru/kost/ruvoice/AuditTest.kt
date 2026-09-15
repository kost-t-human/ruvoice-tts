package ru.kost.ruvoice

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AuditTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun namesAreCapitalizedWordsNotAtSentenceStart() {
        val a = Audit(tmp.root)
        val raw = "Хагрид кивнул. — Пойдём, — сказал Хагрид Гарри и повёл его в Косой переулок. «Ага», — буркнул Рон. Москва спала."
        val accented = "хагр+ид кивн+ул. пойд+ём, сказ+ал хагр+ид г+арри +и пов+ёл ег+о в кос+ой пере+улок. ага, б+уркнул р+он. москв+а спал+а."
        a.names(raw, accented) { it == "москва" }
        val got = a.entries(Audit.Kind.NAMES).map { it.word to it.variant }
        // «Хагрид» в начале предложения не считается, второе вхождение — считается; «Пойдём» после тире — начало
        // предложения; «Косой» — да (проверять всё равно надо), «Ага» — начало, «Рон» — одна гласная, «Москва» — знает словарь
        assertEquals(listOf("хагрид" to "хагр+ид", "гарри" to "г+арри", "косой" to "кос+ой"), got)
        a.names("Снова Хагрид.", "сн+ова хагр+ид.") { false }
        assertEquals(2, a.entries(Audit.Kind.NAMES).first { it.word == "хагрид" }.count)
    }

    @Test fun persistsAndCaps() {
        val a = Audit(tmp.root)
        a.add(Audit.Kind.UNSURE, "творог", "тв+орог", "ел творог\tс молоком")
        a.add(Audit.Kind.UNSURE, "творог", "твор+ог", "снова")
        a.flush()
        val b = Audit(tmp.root)
        val e = b.entries(Audit.Kind.UNSURE).single()
        assertEquals("тв+орог", e.variant); assertEquals(2, e.count); assertEquals("ел творог с молоком", e.context)
        assertTrue(b.entries(Audit.Kind.NAMES).isEmpty())
        for (i in 0 until Audit.MAX + 5) b.add(Audit.Kind.UNSURE, "слово$i", "сл+ово$i", "")
        assertEquals(Audit.MAX, b.entries(Audit.Kind.UNSURE).size)
        assertNull(b.entries(Audit.Kind.UNSURE).firstOrNull { it.word == "творог" }) // самое старое вытеснено
        b.remove(Audit.Kind.UNSURE, "слово${Audit.MAX + 4}")
        assertEquals(Audit.MAX - 1, Audit(tmp.root).entries(Audit.Kind.UNSURE).size)
    }

    @Test fun hiddenWordsStayHiddenAndSurviveCap() {
        val a = Audit(tmp.root)
        a.add(Audit.Kind.NAMES, "хагрид", "хагр+ид", "")
        a.hide(Audit.Kind.NAMES, "хагрид", true)
        a.add(Audit.Kind.NAMES, "хагрид", "х+агрид", "снова") // при чтении не всплывает
        assertTrue(a.entries(Audit.Kind.NAMES).isEmpty())
        for (i in 0 until Audit.MAX + 5) a.add(Audit.Kind.NAMES, "слово$i", "сл+ово$i", "")
        a.flush()
        val b = Audit(tmp.root)
        assertEquals(Audit.MAX, b.entries(Audit.Kind.NAMES).size)
        assertEquals(listOf("хагрид"), b.entries(Audit.Kind.NAMES, hidden = true).map { it.word }) // старое, но скрытое — не вытеснено
        b.hide(Audit.Kind.NAMES, "хагрид", false)
        assertEquals(2, b.entries(Audit.Kind.NAMES).first { it.word == "хагрид" }.count)
        b.clear(Audit.Kind.NAMES, hidden = true)
        assertEquals(Audit.MAX + 1, Audit(tmp.root).entries(Audit.Kind.NAMES).size)
    }
}
