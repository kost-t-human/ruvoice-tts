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

    /** Отключённая строка системного уходит в «Системный удалённые», переживает обновление (сверка по ключу,
     * даже если вариант в assets поменялся) и возвращается, когда её убирают оттуда. */
    @Test fun removedSystemLinesSurviveUpdateAndComeBack() {
        val root = tmp.root
        var stress = "# v1\nтворог твор+ог\nзамок з+амок\n"
        var replace = "г. = год\n~(\\d+)р = $1 рублей\n"
        fun install() = Dicts.installSystem(root) { path -> when (path) { "dicts/stress/Системный.txt" -> stress; "dicts/replace/Системный.txt" -> replace; else -> null } }
        install()
        val sys = Dicts.file(root, Dicts.Kind.STRESS, Dicts.SYSTEM)
        val sysR = Dicts.file(root, Dicts.Kind.REPLACE, Dicts.SYSTEM)
        Dicts.addRemoved(root, Dicts.Kind.STRESS, "творог твор+ог")
        Dicts.addRemoved(root, Dicts.Kind.REPLACE, "~(\\d+)р = $1 рублей")
        for (k in Dicts.Kind.values()) Dicts.rebuildSystem(root, k)
        assertEquals("# v1\nзамок з+амок\n", sys.readText())
        assertEquals("г. = год\n", sysR.readText())
        // удалённые — сразу за системным; полная копия (Системный.full) в списки не попадает
        Dicts.file(root, Dicts.Kind.STRESS, "Азбука").writeText("")
        assertEquals(listOf(Dicts.SYSTEM, Dicts.REMOVED, "Азбука"), Dicts.files(root, Dicts.Kind.STRESS).map { Dicts.name(it) })
        // обновление: ударение в «твороге» поменялось, слово всё равно остаётся убранным
        stress = "# v2\nтворог тв+орог\nзамок з+амок\nмолоко молок+о\n"
        replace = "г. = года\n~(\\d+)р = $1 руб.\n"
        install()
        assertEquals("# v2\nзамок з+амок\nмолоко молок+о\n", sys.readText())
        assertEquals("г. = года\n", sysR.readText())
        // вернули — в системный приходит вариант из новой версии, на прежнее место
        Dicts.dropRemoved(root, Dicts.Kind.STRESS, "творог твор+ог")
        Dicts.dropRemoved(root, Dicts.Kind.REPLACE, "~(\\d+)р = $1 рублей")
        for (k in Dicts.Kind.values()) Dicts.rebuildSystem(root, k)
        assertEquals(stress, sys.readText())
        assertEquals(replace, sysR.readText())
        assertEquals("", Dicts.file(root, Dicts.Kind.STRESS, Dicts.REMOVED).readText())
    }

    @Test fun scopesTreatRemovedAsSystem() {
        assertTrue(Dicts.Scope.SYSTEM.covers(Dicts.REMOVED))
        assertFalse(Dicts.Scope.USER.covers(Dicts.REMOVED))
        assertTrue(Dicts.Scope.USER.covers(Dicts.MAIN))
    }

    /** Свои списки перебивают системный: он первый в порядке слияния, а при совпадении побеждает поздний. */
    @Test fun userListsOverrideSystem() {
        val root = tmp.root
        for (kind in Dicts.Kind.values()) Dicts.dir(root, kind).mkdirs()
        Dicts.file(root, Dicts.Kind.STRESS, Dicts.SYSTEM).writeText("творог твор+ог\n")
        Dicts.file(root, Dicts.Kind.STRESS, Dicts.NAMES).writeText("")
        Dicts.file(root, Dicts.Kind.STRESS, Dicts.MAIN).writeText("творог тв+орог\n")
        Dicts.file(root, Dicts.Kind.REPLACE, Dicts.SYSTEM).writeText("амбарный замок = амбарный зам+ок\n")
        Dicts.file(root, Dicts.Kind.REPLACE, Dicts.MAIN).writeText("амбарный замок = амбарный з+амок\n")
        assertEquals(listOf(Dicts.SYSTEM, Dicts.NAMES, Dicts.MAIN), Dicts.files(root, Dicts.Kind.STRESS).map { Dicts.name(it) })
        assertEquals("тв+орог", DictCache.stress(Dicts.files(root, Dicts.Kind.STRESS))["творог"])
        assertEquals("амбарный з+амок", DictCache.replacements(Dicts.files(root, Dicts.Kind.REPLACE)).apply("амбарный замок"))
    }

    /** Свои списки перебивают и системный другого вида: своё слово в ударениях снимает системную замену с ним
     * (иначе её «+» заставит словарь ударений слово пропустить), своя однословная замена — системную фразу с этим словом. */
    @Test fun userListsOverrideSystemAcrossKinds() {
        val root = tmp.root
        for (kind in Dicts.Kind.values()) Dicts.dir(root, kind).mkdirs()
        Dicts.file(root, Dicts.Kind.STRESS, Dicts.SYSTEM).writeText("")
        Dicts.file(root, Dicts.Kind.STRESS, Dicts.MAIN).writeText("потом пот+ом\n")
        Dicts.file(root, Dicts.Kind.REPLACE, Dicts.SYSTEM).writeText(
            "потом = п+отом\nобливаясь потом = обливаясь п+отом\nстарый замок = старый зам+ок\nворон = в+орон\nкот = к+от\n")
        Dicts.file(root, Dicts.Kind.REPLACE, Dicts.MAIN).writeText("замок = з+амок\nВорон = вор+он\n")
        val r = DictCache.replacements(Dicts.files(root, Dicts.Kind.REPLACE), stress = Dicts.files(root, Dicts.Kind.STRESS))
        assertEquals("обливаясь потом", r.apply("обливаясь потом"))
        assertEquals("старый з+амок", r.apply("старый замок"))
        assertEquals("вор+он", r.apply("ворон"))
        assertEquals("к+от", r.apply("кот"))
    }

    @Test fun systemDictsFromAssetsParse() {
        // ударения: «слово сл+ово»; замены: «фраза = фраза с ударением», слово с «+» входит в ключ (ё-вариант — «все же = вс+ё же»)
        val stress = TestData.root().resolve("app/src/main/assets/dicts/stress/Системный.txt").readLines()
        val parsed = stress.mapNotNull { DictLines.parseStress(it) }
        assertTrue(parsed.size > 20000)
        // имена с «ё» и через «е»: «федоров ф+ёдоров»; из библиотеки — и твёрдое «э»: «малдер м+алдэр» (словарь подменяет буквы слова)
        fun e(s: String) = s.replace('ё', 'е').replace('э', 'е')
        assertTrue(parsed.all { (w, v) -> e(v.replace("+", "")) == e(w) && v.count { it == '+' } == 1 })
        val stressMap = parsed.toMap()   // выход нормализатора («около 300 рублей», «300-й»): модель читает «тр+ёхсот», норма «трёхс+от»
        assertEquals("м+алдэр", stressMap["малдер"]); assertEquals("трёхк+омнатную", stressMap["трехкомнатную"]); assertEquals("трёхк+омнатную", stressMap["трёхкомнатную"])
        assertEquals("трёхс+от", stressMap["трёхсот"]); assertEquals("четырёхс+отый", stressMap["четырёхсотый"]); assertEquals("трехт+ысячного", stressMap["трехтысячного"])
        val replace = TestData.root().resolve("app/src/main/assets/dicts/replace/Системный.txt").readLines()
        val r = Replacements.parse(replace)
        val phrases = replace.dropWhile { !it.startsWith("# Фразы-подсказки") }   // выше — сложные слова и орфоэпия («гм = гмм»)
        val pairs = phrases.takeWhile { !it.startsWith("# Ударение на предлоге") }.mapNotNull { Replacements.split(it) }
        assertTrue(pairs.size > 5000)
        assertTrue(pairs.filter { '*' !in it.first }.all { (k, v) -> v.replace("+", "").replace('ё', 'е') == k.replace('ё', 'е').removePrefix("$") && v.count { it == '+' } in 1..2 })
        // ударение на предлоге: предлог слеплен со следующим словом («н+абок»), иначе оба звучат ударно
        val clitic = phrases.dropWhile { !it.startsWith("# Ударение на предлоге") }.mapNotNull { Replacements.split(it) }
        assertTrue(clitic.size > 100)
        assertTrue(clitic.all { (k, v) -> v.replace("+", "").replace(" ", "") == k.replace(" ", "") && v.split(" ").any { it.length > 1 && it.indexOf('+') in 0..3 && ' ' !in it } })
        assertEquals("Он н+ебыл дома и ворочался с б+оку н+абок.", r.apply("Он не был дома и ворочался с боку на бок."))
        assertEquals("Сражаться н+ескем, спросить н+еского.", r.apply("Сражаться не с кем, спросить не с кого."))
        assertEquals("Корабль спустили н+аводу, а он сидел н+огу н+аногу.", r.apply("Корабль спустили на воду, а он сидел ногу на ногу."))   // маска «*» в ключе и замене
        assertEquals("Амбарный зам+ок висел", r.apply("Амбарный замок висел"))
        // eugene в начале предложения глотает «с» у «спокойн…», с тире — нет
        assertEquals("— Спокойное лицо. Он ушёл... — спокойно, спокойно.", r.apply("Спокойное лицо. Он ушёл... спокойно, спокойно."))
        assertEquals("— Спокойный вечер, успокойся.", r.apply("— Спокойный вечер, успокойся."))
        assertEquals("— Спокойной н+очи. — Спокойно н+ачал.", r.apply("Спокойной ночи. Спокойно начал."))   // фраза-подсказка и тире вместе
        assertEquals("в+олны силы из-под земл+и и новые з+емли", r.apply("волны силы из-под земли и новые земли"))
        assertEquals("опыт Толст+ого и толстого кота", r.apply("опыт Толстого и толстого кота")) // «$» — только с заглавной
        assertEquals("тёмно-зеленый и темно", r.apply("темно-зеленый и темно"))
        // дефисные из Balamoote (tools/hyphen_first.txt): первая часть «медико-*» и слово целиком; отдельно «медико» не трогаем
        assertEquals("М+едико-биологический, м+ало-м+альски, всего-н+авсего, медико", r.apply("Медико-биологический, мало-мальски, всего-навсего, медико"))
        assertEquals("водяные пар+ы и позитронные п+ары", r.apply("водяные пары и позитронные пары"))
        assertEquals("ут+ёсный паук возбужд+ённее", r.apply("утесный паук возбужденнее"))
        assertEquals("гмм, тест и тенденции, стоят д+орога", r.apply("гм, тест и тенденции, стоят дорого"))   // [тэ] — в HardE после акцентора
        assertEquals("нар+ошно я+ишницу для Марьи Иль+инишны, ништ+о и ничтожный", r.apply("нарочно яичницу для Марьи Ильиничны, ничто и ничтожный"))   // [шн], где модель читает [чн]
        assertEquals("ков+ото, всев+ото и ев+ото, но много-то и кого", r.apply("Кого-то, всего-то и его-то, но много-то и кого"))   // [в] перед частицей
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
        // импорт файла «Системный удалённые.txt», пока своего такого нет, не становится списком удалённых
        assertEquals("${Dicts.REMOVED} (2)", Dicts.freeName(tmp.root, Dicts.Kind.STRESS, Dicts.REMOVED))
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
