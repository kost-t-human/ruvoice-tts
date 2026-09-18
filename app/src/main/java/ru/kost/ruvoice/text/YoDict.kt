package ru.kost.ruvoice.text

import android.content.Context
import android.util.Log

/**
 * Словарь бесспорной «ё» (assets/eyo_safe.txt — safe.txt из github.com/e2yo/eyo-kernel, MIT): слово через «е» →
 * то же с «ё». Формат и правила как в eyo: «Ёжиков(а|ой|у|ы)» — формы, «# …» — комментарий, строчное слово
 * подходит и с заглавной («Ежик» в начале фразы), «Ёжиков» с заглавной — только так, «_киёв» — только строчными
 * (Киев — город). Спорные слова (все/всё, небо/нёбо) в safe.txt не входят. Применяется в Stress.accentorPass
 * до модели: слово из словаря получает «ё» и ударение на ней, модель про него не спрашивают.
 */
class YoDict(lines: Sequence<String>) {
    private val dict = HashMap<String, String>(1 shl 17)

    init {
        for (raw in lines) {
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) continue
            val open = line.indexOf('(')
            if (open < 0) add(line)
            else for (e in line.substring(open + 1, line.indexOf(')', open).coerceAtLeast(open + 1)).split('|')) add(line.substring(0, open) + e)
        }
    }

    private fun add(entry: String) {
        val lowerOnly = entry.startsWith("_")
        val word = entry.removePrefix("_")
        val key = word.replace('ё', 'е').replace('Ё', 'Е')
        dict[key] = word
        if (!lowerOnly && !word[0].isUpperCase()) dict[key.replaceFirstChar { it.uppercaseChar() }] = word.replaceFirstChar { it.uppercaseChar() }
    }

    val size get() = dict.size

    /** Слово с «ё», если оно в словаре, иначе null. Регистр как в тексте. */
    fun restore(word: String): String? = dict[word]

    companion object {
        /** Общий на процесс, ставит SileroModels.data(); null — словарь выключен (JVM-тесты без ассета). */
        @Volatile var shared: YoDict? = null

        fun open(context: Context): YoDict {
            val t = System.nanoTime()
            return context.assets.open("eyo_safe.txt").bufferedReader().useLines { YoDict(it) }
                .also { Log.i("RuVoice", "eyo_safe.txt: ${it.size} форм, ${(System.nanoTime() - t) / 1_000_000} мс") }
        }
    }
}
