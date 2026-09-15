package ru.kost.ruvoice.text

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

/**
 * Якорный индекс должен давать ровно тот же результат, что и тупой последовательный прогон
 * всех правил regex-ом (эталон здесь, в тесте). Правила синтетические, в формате словарей
 * Демагога: фразы из 1–4 слов, «+» в одном из слов, изредка маска «*» и запятая без пробела.
 */
class ReplacementsIndexTest {
    private val core = ("глаза руки ноги дома окна краю стрелы края свои берега стоит пропасть дорогой четыре другом " +
        "города не и в на с что он она они был была было уже чуть вылезли орбит места рейтинге вопрос денег остро " +
        "медальон знаком таким вот столе сказал майор сопел спиной ворон обучал когда никогда земли шанс найти стоящее").split(" ")
    /** Словарь ~4000 псевдослов; выбор по Ципфу, чтобы частые слова были частыми, как в тексте. */
    private val pool = core + Random(0).let { rnd ->
        val syl = "ба ве ги до жу за ки ло ми на по ре су ти ху че ша ще ю я ст пр кр".split(" ")
        List(4000) { List(2 + rnd.nextInt(3)) { syl[rnd.nextInt(syl.size)] }.joinToString("") }
    }
    private fun word(rnd: Random): String {
        val r = rnd.nextDouble()
        return pool[(r * r * r * pool.size).toInt().coerceIn(0, pool.size - 1)]
    }

    /** Демагог-подобное правило: ударение «+» ставится перед первой гласной одного из слов.
     * Длина ключа как в живых словарях: 2 слова — 70 %, 3 — 23 %, 1 и 4 — по единицам процентов. */
    private fun rule(rnd: Random): String {
        val n = rnd.nextInt(100).let { if (it < 3) 1 else if (it < 73) 2 else if (it < 96) 3 else 4 }
        val words = List(n) { word(rnd) }.toMutableList()
        val target = rnd.nextInt(n)
        val stressed = words[target].let { w -> val i = w.indexOfFirst { it in "аеёиоуыэюя" }; if (i < 0) w else w.substring(0, i) + "+" + w.substring(i) }
        val key = words.joinToString(" ").let { if (rnd.nextInt(15) == 0) it.replaceFirst(" ", ",") else it }
            .let { if (rnd.nextInt(15) == 0) "$it*" else it }
        val value = words.toMutableList().also { it[target] = stressed }.joinToString(" ").let { v ->
            var out = v; if (key.contains(',')) out = out.replaceFirst(" ", ","); if (key.endsWith("*")) out += "*"; out }
        return "$key=$value"
    }

    private fun rules(count: Int, seed: Int) = Random(seed).let { rnd -> List(count) { rule(rnd) } }

    /** Эталон: каждое правило — regex через ту же Replacements.toRegex, применяются все подряд. */
    private fun reference(lines: List<String>): (String) -> String {
        val rules = lines.mapNotNull { Replacements.split(it) }.reversed().distinctBy { it.first }.reversed()
            .sortedByDescending { it.first.length }
            .map { (k, v) -> Replacements.toRegex(k) to v }
        return { text ->
            rules.fold(text) { t, (re, v) ->
                re.replace(t) { m -> var g = 0; v.replace(Regex("\\*")) { m.groupValues.getOrNull(++g) ?: "" } }
            }.replace(Regex(" {2,}"), " ").trim()
        }
    }

    @Test fun indexMatchesSequentialScan() {
        val lines = rules(1500, 1)
        val r = Replacements.parse(lines)
        val ref = reference(lines)
        val rnd = Random(2)
        var hits = 0
        repeat(300) {
            val text = "Он сказал, что " + List(12) { word(rnd) }.joinToString(" ") + ", и всё."
            val expected = ref(text)
            assertEquals(text, expected, r.apply(text))
            if (expected != text) hits++
        }
        assertTrue("должны быть срабатывания, а не совпадение на пустых: $hits", hits > 100)
    }

    @Test fun sixtyThousandRulesApplyFast() {
        val lines = rules(62_000, 3)
        val t0 = System.nanoTime()
        val r = Replacements.parse(lines)
        val parseMs = (System.nanoTime() - t0) / 1_000_000
        val rnd = Random(4)
        val paragraph = List(500) { word(rnd) }.joinToString(" ") + "."
        r.apply(paragraph) // прогрев
        val t1 = System.nanoTime()
        repeat(20) { r.apply(paragraph) }
        val applyMs = (System.nanoTime() - t1) / 1_000_000.0 / 20
        println("62k правил: parse $parseMs мс, apply ~${"%.2f".format(applyMs)} мс на абзац из 500 слов")
        assertNotEquals(paragraph, r.apply(paragraph))
        assertTrue("parse дольше 5 с — что-то сломалось", parseMs < 5000)
    }
}
