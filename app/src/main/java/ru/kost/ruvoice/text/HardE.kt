package ru.kost.ruvoice.text

import android.content.Context
import android.util.Log

/**
 * Твёрдый согласный перед «е» в заимствованиях (assets/hard_e.txt — Викисловарь по ОЭСРЯ-2010, tools/hard_e.py):
 * «энергия» → «энэргия», «тест» → «тэст», «проект» → «проэкт». Модель голоса читает «е» мягко. Применяется после
 * Stress: акцентор, словарь ударений и омографы видят обычное написание («энэрг+ия» на «э»-форме, «интэрнэт» без
 * ударения вовсе). Правила меняют только «е» на «э», длина слова та же, поэтому «+» снимаются и ставятся обратно на
 * свои места; регистр берётся из исходного текста. По словам: «бизнес-план» — маска «бизнес*» ловит первую часть.
 */
class HardE(lines: List<String>) {
    private val rules = Replacements.parse(lines)

    private val wordRe = Regex("[а-яё+]+", RegexOption.IGNORE_CASE)

    fun apply(accented: String): String = wordRe.replace(accented) { m -> word(m.value) }

    private fun word(w: String): String {
        val plus = w.indices.filter { w[it] == '+' }
        val bare = if (plus.isEmpty()) w else w.replace("+", "")
        val out = rules.apply(bare)
        if (out.length != bare.length) return w   // правило не сохранило длину — не рискуем
        val cased = StringBuilder(w.length)
        for (i in bare.indices) cased.append(if (bare[i].isUpperCase()) out[i].uppercaseChar() else out[i])
        for (p in plus) cased.insert(p, '+')
        return cased.toString()
    }

    companion object {
        /** Общий на процесс, ставит SileroModels.data(); null — выключено (JVM-тесты без ассета). */
        @Volatile var shared: HardE? = null

        fun open(context: Context): HardE {
            val t = System.nanoTime()
            return HardE(context.assets.open("hard_e.txt").bufferedReader().readLines())
                .also { Log.i("RuVoice", "hard_e.txt: ${(System.nanoTime() - t) / 1_000_000} мс") }
        }
    }
}
