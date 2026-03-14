package ru.kost.ruvoice.text

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.kost.ruvoice.TestData

class BertTokenizerTest {
    private val d = TestData.data()
    private val tok = BertTokenizer(d)

    @Test fun goldenBertIds() {
        val g = TestData.golden()
        var checked = 0
        for (i in 0 until g.length()) {
            val o = g.getJSONObject(i)
            if (!o.has("bert")) continue
            val arr = o.getJSONArray("bert")
            for (j in 0 until arr.length()) {
                val b = arr.getJSONObject(j)
                val exp = b.getJSONArray("ids").let { a -> LongArray(a.length()) { a.getLong(it) } }
                assertEquals(b.getString("marked"), exp.toList(), tok.encode(b.getString("marked")).toList())
                checked++
            }
        }
        assert(checked >= 5) { "мало омографов в golden: $checked" }
    }

    @Test fun unknownWordBecomesUnk() {
        // "ъъъъъъъъ" из брифа реально разбивается WordPiece на ъ + 7×##ъ (обе формы есть в
        // словаре) — это корректное поведение жадного алгоритма, не баг порта. Берём слово,
        // для которого нет ни целого токена, ни ##-подслова ни для одного префикса.
        val ids = tok.encode("漢漢漢漢漢漢漢漢")
        assertEquals(listOf(d.bertCls.toLong(), d.bertUnk.toLong(), d.bertSep.toLong()), ids.toList())
    }
}
