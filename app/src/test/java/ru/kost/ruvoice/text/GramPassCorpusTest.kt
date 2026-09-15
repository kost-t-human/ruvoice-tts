package ru.kost.ruvoice.text

import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import ru.kost.ruvoice.TestData
import java.io.File

/**
 * Stress.gramPass на фразах чужих словарей замен: app/src/test/resources/local/ss_rows.tsv (гитигнорный,
 * «ключ \t словарь \t Silero Stress», снимается скриптом из tools/stress_survey.py по словарям в local/).
 * Везде, где gramPass поставил ударение, оно сравнивается со словарём. Трещотка по двум числам:
 * правило не должно ни замолчать (сработок не меньше MIN_FIRED), ни начать спорить со словарём чаще,
 * чем в MAX_WRONG случаев на сработку. Все споры — в app/build/gram_pass_diff.txt; часть из них — мусор
 * самих словарей («об козы»), поэтому порог не ноль.
 */
class GramPassCorpusTest {
    private val wordRe = Regex("[а-яё+-]+", RegexOption.IGNORE_CASE)
    private val models = object : StressModels {
        override fun accentor(words: List<String>) = Pair(Array(words.size) { FloatArray(10) }, Array(words.size) { FloatArray(7) })
        override fun homo(ids: List<LongArray>, starts: LongArray, ends: LongArray) = FloatArray(ids.size)
    }

    @Test fun corpus() {
        val f = File(TestData.root(), "app/src/test/resources/local/ss_rows.tsv")
        Assume.assumeTrue("нет app/src/test/resources/local/ss_rows.tsv", f.exists())
        val stress = Stress(TestData.data(), models, morph = Morph.open(File(TestData.root(), "app/src/main/assets/morph.bin")))
        var fired = 0; var wrong = 0; val diff = StringBuilder()
        f.forEachLine { line ->
            val p = line.split('\t'); if (p.size < 2) return@forEachLine
            val key = p[0]; val dict = wordRe.findAll(p[1]).map { it.value }.toList()
            val ours = wordRe.findAll(stress.gramPass(key)).map { it.value }.toList()
            if (ours.size != dict.size) return@forEachLine
            for (i in ours.indices) {
                if ('+' !in ours[i] || '+' !in dict[i] || ours[i].replace("+", "") != dict[i].replace("+", "")) continue
                fired++
                if (ours[i] != dict[i]) { wrong++; diff.append("$key | ${dict[i]} | ${ours[i]}\n") }
            }
        }
        File(TestData.root(), "app/build/gram_pass_diff.txt").writeText(diff.toString())
        println("gramPass: сработок $fired, споров со словарём $wrong (${"%.2f".format(wrong * 100.0 / fired)}%)")
        assertTrue("gramPass сработал всего $fired раз, ждали не меньше $MIN_FIRED", fired >= MIN_FIRED)
        assertTrue("gramPass спорит со словарём в $wrong из $fired, порог $MAX_WRONG", wrong <= fired * MAX_WRONG)
    }

    companion object {
        const val MIN_FIRED = 10000
        const val MAX_WRONG = 0.025
    }
}
