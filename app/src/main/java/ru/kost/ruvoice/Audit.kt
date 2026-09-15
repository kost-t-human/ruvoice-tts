package ru.kost.ruvoice

import java.io.File

/**
 * Слова на проверку, вкладка «Проверка»: NAMES — слова с заглавной не в начале предложения (имена,
 * которых модель не знает и ударение в которых угадывает), UNSURE — слова, где модель ниже порога
 * уверенности. Копятся сервисом при чтении, по одному файлу на список в данных приложения:
 * «слово \t вариант с + \t сколько раз \t кусок фразы [\t h]». Не больше MAX видимых записей, старые
 * вытесняются. Смахнутое слово не удаляется, а прячется (h): при чтении снова не всплывает, в UI
 * его показывает переключатель «Скрытые».
 */
class Audit(private val dir: File) {
    enum class Kind(val file: String) { NAMES("audit_names.txt"), UNSURE("audit_unsure.txt") }
    class Entry(val word: String, var variant: String, var count: Int, var context: String, var hidden: Boolean = false)

    private val lists = HashMap<Kind, LinkedHashMap<String, Entry>>()
    private val dirty = HashSet<Kind>()

    private fun list(kind: Kind): LinkedHashMap<String, Entry> = lists.getOrPut(kind) {
        val map = LinkedHashMap<String, Entry>()
        val f = File(dir, kind.file)
        if (f.exists()) for (line in f.readLines()) {
            val p = line.split('\t')
            if (p.size >= 4) map[p[0]] = Entry(p[0], p[1], p[2].toIntOrNull() ?: 1, p[3], p.getOrNull(4) == "h")
        }
        map
    }

    /** Первое вхождение слова запоминает вариант и фразу, повторы только считаются. */
    @Synchronized fun add(kind: Kind, word: String, variant: String, context: String) {
        val map = list(kind)
        val e = map[word]
        if (e != null) e.count++
        else {
            map[word] = Entry(word, variant, 1, context.take(CONTEXT).replace('\t', ' ').replace('\n', ' '))
            while (map.count { !it.value.hidden } > MAX) map.remove(map.entries.first { !it.value.hidden }.key)
        }
        dirty += kind
    }

    @Synchronized fun entries(kind: Kind, hidden: Boolean = false): List<Entry> = list(kind).values.filter { it.hidden == hidden }
    @Synchronized fun remove(kind: Kind, word: String) { if (list(kind).remove(word) != null) { dirty += kind; flush() } }
    @Synchronized fun hide(kind: Kind, word: String, hidden: Boolean) { list(kind)[word]?.let { it.hidden = hidden; dirty += kind; flush() } }
    /** Убирает видимые или скрытые записи списка. */
    @Synchronized fun clear(kind: Kind, hidden: Boolean = false) { list(kind).values.removeAll { it.hidden == hidden }; dirty += kind; flush() }

    /** Файл списка как есть — для экспорта настроек. */
    @Synchronized fun text(kind: Kind): String { flush(); return File(dir, kind.file).takeIf { it.exists() }?.readText().orEmpty() }
    /** Список из экспорта целиком вместо нынешнего. */
    @Synchronized fun load(kind: Kind, text: String) { flush(); File(dir, kind.file).writeText(text); lists.remove(kind) }

    /** Пишет изменённые списки; сервис зовёт в конце запроса. */
    @Synchronized fun flush() {
        for (kind in dirty) File(dir, kind.file).writeText(list(kind).values.joinToString("") { "${it.word}\t${it.variant}\t${it.count}\t${it.context}${if (it.hidden) "\th" else ""}\n" })
        dirty.clear()
    }

    /** Имена: слово с заглавной не первое в предложении и не из словарей. [known] — слова, которые словарь и так знает. */
    fun names(raw: String, accented: String, known: (String) -> Boolean) {
        val stressed = HashMap<String, String>()
        for (m in wordRe.findAll(accented)) { val w = m.value; if ('+' in w) stressed.putIfAbsent(w.replace("+", ""), w) }
        var first = true
        for (m in tokenRe.findAll(raw)) {
            val t = m.value
            val letters = t.trim { !it.isLetter() }
            if (letters.isEmpty()) continue // тире, кавычки, цифры — не слово и не конец предложения
            val isName = !first && letters.length >= 3 && letters[0].isUpperCase() && letters.drop(1).all { it in 'а'..'я' || it == 'ё' }
            first = t.trimEnd { it in "\"»)" }.lastOrNull() in ".!?…".toSet()
            if (!isName) continue
            val low = letters.lowercase()
            if (known(low) || low.count { it in VOWELS } < 2) continue
            val v = stressed[low] ?: continue
            add(Kind.NAMES, low, v, raw)
        }
    }

    companion object {
        const val MAX = 2000
        const val CONTEXT = 120
        /** Порог уверенности акцентора по умолчанию: ниже него слово попадает в «неуверенные». */
        const val MIN_DEFAULT = 0.7f
        private const val VOWELS = "аеёиоуыэюя"
        private val wordRe = Regex("[а-яё+]+", RegexOption.IGNORE_CASE)
        private val tokenRe = Regex("\\S+")
    }
}
