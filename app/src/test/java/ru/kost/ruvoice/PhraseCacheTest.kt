package ru.kost.ruvoice

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Кэш коротких фраз TalkBack: звук и точки подсветки, вытеснение по объёму. */
class PhraseCacheTest {
    @Test fun recorderJoinsAudioAndOffsets() {
        val r = PhraseCache.Recorder()
        r.range(0, 0, 6)            // слово в начале первого куска
        r.audio(ShortArray(100) { 1 })
        r.audio(ShortArray(50))     // пауза между предложениями
        r.range(10, 7, 12)          // слово на 10-м сэмпле второго куска
        r.audio(ShortArray(80) { 2 })
        val e = r.entry()
        assertEquals(230, e.pcm.size)
        assertEquals(1, e.pcm[0].toInt()); assertEquals(0, e.pcm[120].toInt()); assertEquals(2, e.pcm[229].toInt())
        assertArrayEquals(intArrayOf(0, 0, 6, 160, 7, 12), e.ranges)
    }

    @Test fun lruByBytes() {
        val c = PhraseCache(budgetBytes = 4000)
        fun entry() = PhraseCache.Entry(ShortArray(400), IntArray(0)) // 864 байта с накладными
        c.put("a", entry()); c.put("b", entry()); c.put("c", entry()); c.put("d", entry())
        assertNotNull(c.get("a"))            // «a» звучала последней — дольше всех не звучала «b»
        c.put("e", entry())                  // пятая не влезает в 4000 — уходит «b»
        assertNull(c.get("b"))
        for (k in listOf("a", "c", "d", "e")) assertNotNull(k, c.get(k))
    }

    @Test fun longPhraseNotCached() {
        val c = PhraseCache(budgetBytes = 4000)
        c.put("long", PhraseCache.Entry(ShortArray(1000), IntArray(0))) // 2 КБ — больше четверти бюджета
        assertNull(c.get("long"))
        assertEquals(0, c.size)
    }
}
