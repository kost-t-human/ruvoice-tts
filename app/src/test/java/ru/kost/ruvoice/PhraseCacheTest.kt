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

    // фразы чтеца на диске переживают процесс: новый PhraseCache (как после перезапуска) находит их по тому же ключу
    @Test fun diskSurvivesNewProcess() {
        val dir = kotlin.io.path.createTempDirectory("phrases").toFile()
        try {
            val a = PhraseCache(diskBytes = 1 shl 20).apply { this.dir = dir }
            a.put("кнопка", PhraseCache.Entry(ShortArray(300) { it.toShort() }, intArrayOf(0, 0, 6)), disk = true)
            a.put("только память", PhraseCache.Entry(ShortArray(10), IntArray(0)))
            val b = PhraseCache(diskBytes = 1 shl 20).apply { this.dir = dir }
            assertNotNull(b.get("кнопка", disk = true)); val hit = b.get("кнопка")!!
            assertArrayEquals(ShortArray(300) { it.toShort() }, hit.pcm)
            assertArrayEquals(intArrayOf(0, 0, 6), hit.ranges)
            assertNull(b.get("кнопка-2", disk = true))
            assertNull(b.get("только память", disk = true))
            b.clearDisk()
            assertNull(PhraseCache().apply { this.dir = dir }.get("кнопка", disk = true))
        } finally { dir.deleteRecursively() }
    }

    @Test fun diskBudgetDropsOldest() {
        val dir = kotlin.io.path.createTempDirectory("phrases").toFile()
        try {
            val c = PhraseCache(diskBytes = 2500).apply { this.dir = dir }
            for (k in 1..3) { c.put("f$k", PhraseCache.Entry(ShortArray(500), IntArray(0)), disk = true); Thread.sleep(20) }
            val fresh = PhraseCache().apply { this.dir = dir }
            assertNull(fresh.get("f1", disk = true))
            assertNotNull(fresh.get("f3", disk = true))
        } finally { dir.deleteRecursively() }
    }
}
