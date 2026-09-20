package ru.kost.ruvoice.text

import org.junit.Assert.*
import org.junit.Test
import ru.kost.ruvoice.TestData

class MarksTest {
    private val d = TestData.data()

    @Test fun parseStripsMarkersAndKeepsWords() {
        val p = Marks.parse("Он *не* пришёл {prosody:150:110}в 5 часов{prosody}, увы.")
        assertEquals("Он не пришёл в 5 часов, увы.", p.text)
        assertEquals(listOf("он", "не", "пришел", "в", "5", "часов", "увы"), p.words.map { it.first })
        assertEquals(Marks.Mark(focus = 3), p.words[1].second)
        assertEquals(Marks.Mark(rate = 1.5f, pitch = 1.1f), p.words[3].second)
        assertEquals(Marks.Mark(rate = 1.5f, pitch = 1.1f), p.words[5].second)
        assertEquals(Marks.NONE, p.words[6].second)
    }

    @Test fun loneAndTripleStarsAreNotFocus() {
        val p = Marks.parse("сноска* и *** конец")
        assertEquals("сноска* и *** конец", p.text)
        assertTrue(p.words.all { it.second == Marks.NONE })
    }

    @Test fun alignFollowsNormalizedWords() {
        // «5» стало «пять» — не совпало, берёт пометку слова, на котором стоит указатель («5»)
        val p = Marks.parse("в {prosody:150:100}5 *часов*{prosody} утра")
        val accented = "в п+ять час+ов утр+а"
        val seq = d.sequence(accented)
        val a = Marks.align(p.words, accented, seq.size, d.sym)
        val t = Marks.tokens(accented, d.sym)
        assertEquals(4, t.size)
        assertEquals(1.0f, a.rates[t[0].seqStart], 0f)
        assertEquals(1.5f, a.rates[t[1].seqStart], 0f)
        assertEquals(1.5f, a.rates[t[2].seqEnd - 1], 0f)
        assertEquals(3L, a.focus[t[2].seqStart]); assertEquals(0L, a.focus[t[1].seqStart])
        assertEquals(1.0f, a.rates[t[3].seqStart], 0f)
        assertEquals(1.0f, a.rates[seq.size - 1], 0f) // eos
    }

    @Test fun focusLevelAndOff() {
        assertEquals(1, Marks.parse("*а* б", 1).words[0].second.focus)
        assertEquals(Marks.NONE, Marks.parse("*а* б", 0).words[0].second)
        assertEquals("а б", Marks.parse("*а* б", 0).text)
    }

    @Test fun inlinePauseBecomesCommaWithDuration() {
        val p = Marks.parse("он ушёл{pause:300}и всё")
        assertEquals("он ушёл, и всё", p.text)
        assertEquals(300, p.words[1].second.pauseMs)
        // знак уже есть — новый не ставится, паузы складываются
        val q = Marks.parse("стой, {pause:200} {pause:100} иди")
        assertEquals("стой, иди", q.text)
        assertEquals(300, q.words[0].second.pauseMs)
        // в начале текста паузе не на чем жить
        assertEquals("иди", Marks.parse("{pause:200}иди").text)
    }

    @Test fun alignPutsPauseOnPunctuationOfRewrittenWord() {
        // «5» стало «пять», знак остался — пауза уходит на него, ровно один раз
        val p = Marks.parse("ждал 5{pause:500}минут")
        val accented = "жд+ал п+ять, мин+ут"
        val seq = d.sequence(accented)
        val a = Marks.align(p.words, accented, seq.size, d.sym)
        val comma = seq.indexOfFirst { it.toInt() == d.symbolToId.getValue(',') }
        assertEquals(mapOf(comma.toLong() to Marks.frames(500)), a.symbDurs)
        assertEquals(50L, Marks.frames(500))
    }

    @Test fun blankKeepsOffsets() {
        val src = "пять{pause:600}минут {prosody:80:90}и"
        assertEquals("пять           минут                и", Marks.blank(src))
    }

    @Test fun tokensSkipSymbolsOutsideAlphabet() {
        // «x» не в алфавите — индексы seq его не считают
        val t = Marks.tokens("а x б", d.sym)
        assertEquals(listOf(1, 3, 4), t.map { it.seqStart })
        assertEquals(3, t[1].seqEnd); assertEquals(5, t[2].seqEnd)
    }

    @Test fun matcherLooksAheadWithinWindow() {
        val m = Marks.Matcher(listOf("а", "б", "в", "г"))
        assertEquals(2, m.next("в")); assertEquals(3, m.pos)
        assertEquals(-1, m.next("а")); assertEquals(-1, m.next(""))
        assertEquals(3, m.next("г"))
    }

    @Test fun shortExclamationFocusesLastWord() {
        assertEquals("Эй, *вы*!", Marks.exclaim("Эй, вы!"))
        assertEquals("*Бам*!", Marks.exclaim("Бам!"))
        assertEquals("— Ах *ты*!»", Marks.exclaim("— Ах ты!»"))
        assertEquals("Ну и что!", Marks.exclaim("Ну и что!"))
        assertEquals("Ты где?!", Marks.exclaim("Ты где?!"))
        assertEquals("*Бам*!", Marks.exclaim("*Бам*!"))
        assertEquals("Бам.", Marks.exclaim("Бам."))
        val p = Marks.parse(Marks.exclaim("Эй, вы!"))
        assertEquals(listOf(0, 3), p.words.map { it.second.focus })
    }

    @Test fun verblessQuestionFocusesLastWord() {
        assertEquals("Вы барон *Гордеев*?", Marks.question("Вы барон Гордеев?", null))
        assertEquals("— Барон Андрей Николаевич *Гордеев*?»", Marks.question("— Барон Андрей Николаевич Гордеев?»", null))
        assertEquals("Это *правда*?", Marks.question("Это правда?", null))
        assertEquals("Вы *уверены*?", Marks.question("Вы уверены?", null))
        // с глаголом (и в начале, с заглавной) — как есть; своё логическое ударение не трогаем
        assertEquals("Ты пилила доску?", Marks.question("Ты пилила доску?", null))
        assertEquals("Придёшь завтра?", Marks.question("Придёшь завтра?", null))
        assertEquals("Вы *барон* Гордеев?", Marks.question("Вы *барон* Гордеев?", null))
    }
}
