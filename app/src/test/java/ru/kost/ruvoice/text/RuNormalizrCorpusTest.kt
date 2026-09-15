package ru.kost.ruvoice.text

import org.json.JSONObject
import ru.kost.ruvoice.TestData
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Сверка с корпусом ru-normalizr (см. source в ru_normalizr.json). Сравниваем ядро без latin():
 * буквы и цифры в нижнем регистре, пунктуация отброшена. Поле «ours» у кейса — наш сознательный
 * вариант вместо их «out» (см. note в json). Тест — трещотка: число совпадений
 * не должно падать ниже MIN_MATCHED; полный список расхождений пишется в build/ru_normalizr_diff.txt.
 * Прогон дважды: без таблицы морфологии (как в тестах без ассета) и с ней (как в приложении);
 * трещотка — по второму, без таблицы — не ниже MIN_MATCHED_NO_MORPH.
 */
class RuNormalizrCorpusTest {
    private fun canon(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    private fun ours(text: String) = Abbrev.apply(Normalizer.numbers(Normalizer.punctuation(text)))

    /** Число совпадений по режимам; расхождения — в diff. */
    private fun run(diff: StringBuilder): Pair<Map<String, Int>, Map<String, Int>> {
        val doc = JSONObject(File(TestData.root(), "app/src/test/resources/ru_normalizr.json").readText())
        val cases = doc.getJSONArray("cases")
        val matched = mutableMapOf<String, Int>()
        val total = mutableMapOf<String, Int>()
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val mode = c.getString("mode")
            total[mode] = (total[mode] ?: 0) + 1
            val got = ours(c.getString("in"))
            val expected = c.optString("ours", c.getString("out"))
            if (canon(got) == canon(expected)) matched[mode] = (matched[mode] ?: 0) + 1
            else diff.append("[$mode] ${c.getString("in")}\n  их:  $expected\n  мы:  $got\n\n")
        }
        return matched to total
    }

    private fun summary(m: Pair<Map<String, Int>, Map<String, Int>>) =
        m.second.keys.sorted().joinToString(", ") { "$it ${m.first[it] ?: 0}/${m.second[it]}" }

    @Test fun corpus() {
        val diff = StringBuilder()
        Normalizer.morph = null
        val without = run(StringBuilder())
        Normalizer.morph = Morph.open(File(TestData.root(), "app/src/main/assets/morph.bin"))
        val with = try { run(diff) } finally { Normalizer.morph = null }
        val s = "с morph.bin: ${summary(with)}; без: ${summary(without)}"
        File(TestData.root(), "app/build/ru_normalizr_diff.txt").apply { parentFile.mkdirs() }.writeText("$s\n\n$diff")
        println("ru-normalizr: $s")
        assertTrue(s, (with.first["safe"] ?: 0) >= MIN_MATCHED)
        assertTrue(s, (without.first["safe"] ?: 0) >= MIN_MATCHED_NO_MORPH)
    }

    companion object { const val MIN_MATCHED = 487; const val MIN_MATCHED_NO_MORPH = 485 }
}
