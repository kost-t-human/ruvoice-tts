package ru.kost.ruvoice

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ru.kost.ruvoice.text.Replacements

class DictsTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun migrateMovesOldFilesIntoMainListAndSeedsDefaults() {
        val root = tmp.root
        root.resolve("user_stress.txt").writeText("творог твор+ог\n")
        Dicts.migrate(root, "старый замок = старый з+амок\n")
        assertFalse(root.resolve("user_stress.txt").exists())
        assertEquals("творог твор+ог\n", root.resolve("dicts/stress/Основной.txt").readText())
        // замен не было — предустановки, как раньше при первом запуске
        assertEquals("старый замок = старый з+амок\n", root.resolve("dicts/replace/Основной.txt").readText())
        // повторный вызов ничего не трогает
        root.resolve("dicts/replace/Основной.txt").writeText("")
        Dicts.migrate(root, "старый замок = старый з+амок\n")
        assertEquals("", root.resolve("dicts/replace/Основной.txt").readText())
    }

    @Test fun installSystemWritesAssetAndRefreshesItAfterUpdate() {
        val root = tmp.root
        var asset = "# v1\nтворог твор+ог\n"
        Dicts.installSystem(root) { path -> if (path == "dicts/stress/Системный.txt") asset else null }
        val f = root.resolve("dicts/stress/Системный.txt")
        assertEquals(asset, f.readText())
        assertFalse(root.resolve("dicts/replace/Системный.txt").exists())
        f.writeText("правка пользователя\n")
        Dicts.installSystem(root) { path -> if (path == "dicts/stress/Системный.txt") asset else null }
        assertEquals(asset, f.readText()) // системный список всегда как в assets
        asset = "# v2\nтворог твор+ог\n"
        Dicts.installSystem(root) { path -> if (path == "dicts/stress/Системный.txt") asset else null }
        assertEquals(asset, f.readText())
    }

    @Test fun systemDictsFromAssetsParse() {
        // ударения: «слово сл+ово»; замены: «фраза = фраза с ударением», слово с «+» входит в ключ
        val stress = TestData.root().resolve("app/src/main/assets/dicts/stress/Системный.txt").readLines()
        val parsed = stress.mapNotNull { DictLines.parseStress(it) }
        assertTrue(parsed.size > 1000)
        assertTrue(parsed.all { (w, v) -> v.replace("+", "") == w && v.count { it == '+' } == 1 })
        val replace = TestData.root().resolve("app/src/main/assets/dicts/replace/Системный.txt").readLines()
        val r = Replacements.parse(replace)
        val pairs = replace.mapNotNull { Replacements.split(it) }
        assertTrue(pairs.size > 5000)
        assertTrue(pairs.all { (k, v) -> v.replace("+", "") == k && v.count { it == '+' } == 1 })
        assertEquals("Амбарный зам+ок висел", r.apply("Амбарный замок висел"))
    }

    @Test fun filesAreSortedByRussianCollation() {
        val dir = tmp.root.resolve("dicts/replace").apply { mkdirs() }
        for (n in listOf("яблоко", "Ёлка", "автор", "note")) dir.resolve("$n.txt").writeText("")
        dir.resolve("мусор.bak").writeText("")
        assertEquals(listOf("note", "автор", "Ёлка", "яблоко"), Dicts.files(tmp.root, Dicts.Kind.REPLACE).map { Dicts.name(it) })
    }

    @Test fun decodeStrictUtf8ThenCp1251() {
        assertEquals("твор+ог", Dicts.decode("твор+ог".toByteArray()))
        assertEquals("твор+ог", Dicts.decode(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "твор+ог".toByteArray()))
        assertEquals("твор+ог", Dicts.decode("твор+ог".toByteArray(charset("windows-1251"))))
    }

    @Test fun freeNameAddsCounterWhenTaken() {
        val dir = tmp.root.resolve("dicts/stress").apply { mkdirs() }
        assertEquals("Книга", Dicts.freeName(tmp.root, Dicts.Kind.STRESS, "Книга"))
        dir.resolve("Книга.txt").writeText("")
        assertEquals("Книга (2)", Dicts.freeName(tmp.root, Dicts.Kind.STRESS, "Книга"))
        dir.resolve("Книга (2).txt").writeText("")
        assertEquals("Книга (3)", Dicts.freeName(tmp.root, Dicts.Kind.STRESS, "Книга"))
    }

    @Test fun validNameRejectsSlashesEmptyAndLong() {
        assertTrue(Dicts.validName("Серия Ведьмак"))
        assertFalse(Dicts.validName("  "))
        assertFalse(Dicts.validName("a/b"))
        assertFalse(Dicts.validName("a\\b"))
        assertFalse(Dicts.validName("x".repeat(61)))
    }

    @Test fun demagogExportDropsRegexLinesAndTightensEquals() {
        val (text, skipped) = Dicts.toDemagog(listOf("старый замок = старый з+амок", "~(\\d+)-(\\d+) = $1 по $2", "# коммент", "малек=малёк"), Dicts.Kind.REPLACE)
        assertEquals("старый замок=старый з+амок\r\nмалек=малёк\r\n", text)
        assertEquals(listOf("~(\\d+)-(\\d+) = $1 по $2"), skipped)
        val (stress, none) = Dicts.toDemagog(listOf("творог твор+ог", "", "замок з+амок"), Dicts.Kind.STRESS)
        assertEquals("творог=твор+ог\r\nзамок=з+амок\r\n", stress)
        assertTrue(none.isEmpty())
    }

    @Test fun stressImportAcceptsDemagogEquals() {
        assertEquals("творог твор+ог\nзамок з+амок\n# note\n", Dicts.importText("творог=твор+ог\r\nзамок з+амок\n# note\n", Dicts.Kind.STRESS))
        // замены — как есть, только переводы строк
        assertEquals("a=b\n", Dicts.importText("a=b\r\n", Dicts.Kind.REPLACE))
    }
}
