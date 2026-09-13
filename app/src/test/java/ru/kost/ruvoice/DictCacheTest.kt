package ru.kost.ruvoice

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DictCacheTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun file(name: String, text: String): File = tmp.newFile(name).apply { writeText(text) }

    @Test fun stressMergesFilesLaterWins() {
        val a = file("a.txt", "творог твор+ог\nзамок з+амок\n")
        val b = file("b.txt", "замок зам+ок\n")
        val m = DictCache.stress(listOf(a, b))
        assertEquals("твор+ог", m["творог"])
        assertEquals("зам+ок", m["замок"])
    }

    @Test fun replacementsConcatenateAllFiles() {
        val a = file("a.txt", "кот = к+от\n")
        val b = file("b.txt", "пёс = п+ёс\n")
        assertEquals("к+от и п+ёс", DictCache.replacements(listOf(a, b)).apply("кот и пёс"))
    }

    @Test fun snapshotReusedUntilFileChanges() {
        val a = file("a.txt", "кот = к+от\n")
        val r1 = DictCache.replacements(listOf(a))
        assertSame(r1, DictCache.replacements(listOf(a)))
        a.writeText("кот = к+от\nпёс = п+ёс\n") // длина другая — mtime может совпасть в ту же мс
        val r2 = DictCache.replacements(listOf(a))
        assertNotSame(r1, r2)
        assertEquals("п+ёс", r2.apply("пёс"))
        // другой набор файлов — другой снимок
        assertNotSame(r2, DictCache.replacements(emptyList()))
    }

    @Test fun warmReportsProgressAndDone() {
        val a = file("a.txt", (1..5000).joinToString("\n") { "слово$it = сл+ово$it" })
        val seen = ArrayList<Int>()
        var done = false
        DictCache.warm(listOf(a), Dicts.Kind.REPLACE, { seen += it }, { done = true }).join()
        assertTrue(done)
        assertTrue(seen.toString(), seen.isNotEmpty() && seen.last() == 100 && seen == seen.sorted())
        assertEquals("сл+ово7", DictCache.replacements(listOf(a)).apply("слово7"))
    }
}
