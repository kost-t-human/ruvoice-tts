package ru.kost.ruvoice.text

import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import ru.kost.ruvoice.Dicts
import ru.kost.ruvoice.TestData
import java.io.File

/**
 * Прогон живых словарей Демагога целиком: каждая строка «ключ=замена» — кейс, ключ подаётся
 * как текст, на выходе ждём замену. Словари чужие и в репозиторий не входят: файлы (или ссылки
 * на них) лежат в app/src/test/resources/local/, каталог в .gitignore; без файлов тест
 * пропускается. Маска «*» и в ключе, и в замене подменяется буквой «ъ»; «$» регистра, «$$»
 * и «##» с ключа снимаются, как их снял бы Демагог.
 * Тест — трещотка по доле совпадений; расхождения пишутся в app/build/demagog_diff_<имя>.txt.
 * Расхождение — не обязательно баг движка: в словаре на десятки тысяч строк правила накрывают
 * друг друга (ключ одного целиком содержит ключ другого — второе доставит своё ударение), а
 * словари Балаболки/KooBAudio ещё и рассчитаны на движок без сцепки правил. Поэтому порог мягкий,
 * а смотреть надо в diff. Словари под Николая («<» после гласной) переводит tools/nicolai_to_plus.py.
 */
class DemagogCorpusTest {
    private val spaces = Regex(" {2,}")
    private fun mask(key: String) = '*' in key && key.any { it.isLetterOrDigit() }

    @Test fun corpus() {
        val files = File(TestData.root(), "app/src/test/resources/local").listFiles { f -> f.name.endsWith(".txt") }.orEmpty()
        Assume.assumeTrue("нет словарей в app/src/test/resources/local", files.isNotEmpty())
        val bad = ArrayList<String>()
        for (f in files.sortedBy { it.name }) {
            val lines = Dicts.importText(Dicts.decode(f.readBytes()), Dicts.Kind.REPLACE).lines()
            val r = Replacements.parse(lines)
            val diff = StringBuilder(); var total = 0; var matched = 0
            for (line in lines) {
                val (key, value) = Replacements.split(line) ?: continue
                if (key.startsWith("~")) continue
                total++
                val text = if (key.startsWith("$") || key.startsWith("##")) key.substring(1) else key
                val star = if (mask(key)) "ъ" else "*"
                val expected = spaces.replace(if (value.equals("{skip}", true)) "" else Replacements.implicitStars(text, value).replace("*", star), " ").trim()
                val got = r.apply(text.replace("*", star))
                if (got == expected) matched++ else diff.append("$line\n  ждём: $expected\n  есть: $got\n\n")
            }
            val summary = "${f.name}: $matched/$total"
            File(TestData.root(), "app/build/demagog_diff_${f.nameWithoutExtension}.txt").apply { parentFile.mkdirs() }.writeText("$summary\n\n$diff")
            println("Демагог $summary")
            if (matched < total * MIN_RATIO) bad += summary
        }
        assertTrue(bad.joinToString(), bad.isEmpty())
    }

    companion object { const val MIN_RATIO = 0.9 }
}
