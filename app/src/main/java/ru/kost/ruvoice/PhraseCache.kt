package ru.kost.ruvoice

/**
 * Готовый звук коротких фраз. TalkBack повторяет одно и то же сотни раз («кнопка», «включено»,
 * «дважды нажмите, чтобы активировать»), а синтез каждой — это прогон модели. Повтор с тем же
 * ключом отдаётся сразу из памяти вместе с точками подсветки слов (rangeStart).
 *
 * Ключ собирает сервис: текст, голос, частота, темп, высота, отпечаток настроек (Prefs.stamp) и
 * словарей — любая правка меняет ключ, старое вытесняется само. Кэшируются запросы до [MAX_TEXT]
 * символов, всего не больше [budgetBytes] звука; дольше всех не звучавшее уходит первым.
 */
class PhraseCache(private val budgetBytes: Long = 12L shl 20) {
    /** [pcm] — звук без тишины перед фразой (её сервис решает на каждый запрос заново);
     * [ranges] — тройки (сэмпл от начала pcm, начало, конец слова в тексте запроса). */
    class Entry(val pcm: ShortArray, val ranges: IntArray) {
        val bytes get() = pcm.size * 2L + ranges.size * 4L + 64
    }

    private val map = LinkedHashMap<String, Entry>(64, 0.75f, true)
    private var bytes = 0L

    @Synchronized fun get(key: String): Entry? = map[key]

    @Synchronized fun put(key: String, e: Entry) {
        if (e.bytes > budgetBytes / 4) return // длинное не держим: вытеснит десятки коротких
        map.remove(key)?.let { bytes -= it.bytes }
        map[key] = e; bytes += e.bytes
        val it = map.entries.iterator()
        while (bytes > budgetBytes && it.hasNext()) { bytes -= it.next().value.bytes; it.remove() }
    }

    @Synchronized fun clear() { map.clear(); bytes = 0 }

    @get:Synchronized val size get() = map.size

    /** Сбор звука и точек подсветки по ходу синтеза — для [put] после удачного конца. */
    class Recorder {
        private val parts = ArrayList<ShortArray>()
        private val ranges = ArrayList<Int>()
        private var samples = 0
        fun audio(pcm: ShortArray) { parts += pcm; samples += pcm.size }
        /** [offset] — сэмпл от начала ещё не записанного куска. */
        fun range(offset: Int, start: Int, end: Int) { ranges += samples + offset; ranges += start; ranges += end }
        fun entry(): Entry {
            val pcm = ShortArray(samples)
            var at = 0
            for (p in parts) { p.copyInto(pcm, at); at += p.size }
            return Entry(pcm, ranges.toIntArray())
        }
    }

    companion object {
        const val MAX_TEXT = 80
    }
}
