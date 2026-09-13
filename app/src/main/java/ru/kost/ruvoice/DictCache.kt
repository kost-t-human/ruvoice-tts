package ru.kost.ruvoice

import java.io.File
import ru.kost.ruvoice.text.Replacements

/**
 * Разобранные словари на процесс: сервис TTS и настройки живут в одном процессе, поэтому
 * один снимок обслуживает и синтез, и прогрев из UI. Снимок привязан к сигнатуре набора
 * файлов (путь, mtime, размер) — правка в настройках или переключение списка меняют её,
 * и следующий запрос пересобирает. Запрос синтеза во время прогрева просто ждёт его.
 */
object DictCache {
    private class Snap<T>(val sig: List<String>, val value: T)
    @Volatile private var stressSnap: Snap<Map<String, String>>? = null
    @Volatile private var replaceSnap: Snap<Replacements>? = null

    private fun sig(files: List<File>) = files.map { "${it.path}|${it.lastModified()}|${it.length()}" }

    /** Слияние включённых списков ударений: при одинаковом слове побеждает более поздний файл. */
    @Synchronized fun stress(files: List<File>, onProgress: ((Int) -> Unit)? = null): Map<String, String> {
        val s = sig(files)
        stressSnap?.takeIf { it.sig == s }?.let { return it.value }
        val lines = files.flatMap { it.readLines() }
        val map = HashMap<String, String>(lines.size * 2)
        for ((i, line) in lines.withIndex()) {
            if (onProgress != null && i % 2000 == 0) onProgress(i * 100 / lines.size)
            val (w, v) = DictLines.parseStress(line) ?: continue
            map[w.lowercase()] = v.lowercase()
        }
        onProgress?.invoke(100)
        return map.also { stressSnap = Snap(s, it) }
    }

    /** Слияние включённых списков замен: строки всех файлов подряд, дальше Replacements.parse. */
    @Synchronized fun replacements(files: List<File>, onProgress: ((Int) -> Unit)? = null): Replacements {
        val s = sig(files)
        replaceSnap?.takeIf { it.sig == s }?.let { return it.value }
        // 62k строк: ~80 мс чтение + ~350–700 мс разбор на среднем телефоне (аллокации на ART)
        val lines = files.flatMap { it.readLines() }
        val r = Replacements.parse(lines, onProgress?.let { cb -> { done -> cb(if (lines.isEmpty()) 100 else done * 100 / lines.size) } })
        return r.also { replaceSnap = Snap(s, it) }
    }

    /** Пересобрать снимок в фоне; колбэки зовутся из фонового потока. Возвращает поток —
     * тестам есть что join-ить, UI это не нужно. */
    fun warm(files: List<File>, kind: Dicts.Kind, onProgress: (Int) -> Unit, onDone: () -> Unit): Thread =
        Thread {
            try { if (kind == Dicts.Kind.STRESS) stress(files, onProgress) else replacements(files, onProgress) }
            catch (e: Exception) { /* битый файл — сервис получит то же исключение при синтезе, тут молчим */ }
            onDone()
        }.apply { start() }
}
