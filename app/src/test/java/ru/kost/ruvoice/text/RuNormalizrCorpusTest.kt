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
 */
class RuNormalizrCorpusTest {
    private fun canon(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    private fun ours(text: String) = Abbrev.apply(Normalizer.numbers(Normalizer.punctuation(text)))

    @Test fun corpus() {
        val doc = JSONObject(File(TestData.root(), "app/src/test/resources/ru_normalizr.json").readText())
        val cases = doc.getJSONArray("cases")
        val diff = StringBuilder()
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
        val summary = total.keys.sorted().joinToString(", ") { "$it ${matched[it] ?: 0}/${total[it]}" }
        File(TestData.root(), "app/build/ru_normalizr_diff.txt").apply { parentFile.mkdirs() }.writeText("$summary\n\n$diff")
        println("ru-normalizr: $summary")
        assertTrue(summary, (matched["safe"] ?: 0) >= MIN_MATCHED)
    }

    companion object { const val MIN_MATCHED = 485 }
}
