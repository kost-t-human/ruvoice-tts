package ru.kost.ruvoice

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest

/**
 * Готовый звук коротких фраз. TalkBack повторяет одно и то же сотни раз («кнопка», «включено»,
 * «дважды нажмите, чтобы активировать»), а синтез каждой — это прогон модели. Повтор с тем же
 * ключом отдаётся сразу из памяти вместе с точками подсветки слов (rangeStart).
 *
 * Ключ собирает сервис: текст, голос, частота, темп, высота, отпечаток настроек (Prefs.stamp) и
 * словарей — любая правка меняет ключ, старое вытесняется само. Кэшируются запросы до [MAX_TEXT]
 * символов, всего не больше [budgetBytes] звука; дольше всех не звучавшее уходит первым.
 *
 * Фразы экранного чтеца ещё и на диске ([dir], правило sr_phrase_disk): кэш в памяти пропадает вместе с процессом,
 * а после его перезапуска (Samsung выгружает фоновые процессы) «кнопка» и «назад» снова ждали бы модель.
 * Файл на фразу, имя — SHA-1 ключа; на диске не больше [diskBytes], дольше всех не звучавшее удаляется.
 * Ключ для диска должен быть одинаковым в разных процессах — без identityHashCode.
 */
class PhraseCache(private val budgetBytes: Long = 12L shl 20, private val diskBytes: Long = 24L shl 20) {
    /** [pcm] — звук без тишины перед фразой (её сервис решает на каждый запрос заново);
     * [ranges] — тройки (сэмпл от начала pcm, начало, конец слова в тексте запроса). */
    class Entry(val pcm: ShortArray, val ranges: IntArray) {
        val bytes get() = pcm.size * 2L + ranges.size * 4L + 64
    }

    private val map = LinkedHashMap<String, Entry>(64, 0.75f, true)
    private var bytes = 0L

    /** Каталог дискового кэша; null — только память. */
    @Volatile var dir: File? = null

    /** [disk] — искать и на диске (запрос экранного чтеца при включённом правиле). */
    @Synchronized fun get(key: String, disk: Boolean = false): Entry? = map[key] ?: if (!disk) null else
        dir?.let { File(it, name(key)) }?.takeIf { it.isFile }?.let { f ->
            runCatching { read(f) }.getOrNull()?.also { e ->
                f.setLastModified(System.currentTimeMillis())
                remember(key, e)
            } ?: run { f.delete(); null }
        }

    @Synchronized fun put(key: String, e: Entry, disk: Boolean = false) {
        if (e.bytes > budgetBytes / 4) return // длинное не держим: вытеснит десятки коротких
        remember(key, e)
        if (disk) dir?.let { d -> runCatching { d.mkdirs(); write(File(d, name(key)), e); trimDisk(d) } }
    }

    private fun remember(key: String, e: Entry) {
        map.remove(key)?.let { bytes -= it.bytes }
        map[key] = e; bytes += e.bytes
        val it = map.entries.iterator()
        while (bytes > budgetBytes && it.hasNext()) { bytes -= it.next().value.bytes; it.remove() }
    }

    @Synchronized fun clear() { map.clear(); bytes = 0 }

    /** Стереть дисковый кэш (правило выключили). */
    @Synchronized fun clearDisk() { dir?.takeIf { it.exists() }?.deleteRecursively() }

    private fun name(key: String) =
        MessageDigest.getInstance("SHA-1").digest(key.toByteArray()).joinToString("") { "%02x".format(it) } + ".pcm"

    private fun write(f: File, e: Entry) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        DataOutputStream(tmp.outputStream().buffered()).use { o ->
            o.writeInt(MAGIC); o.writeInt(e.ranges.size); for (r in e.ranges) o.writeInt(r)
            o.writeInt(e.pcm.size); for (x in e.pcm) o.writeShort(x.toInt())
        }
        if (!tmp.renameTo(f)) tmp.delete()
    }

    private fun read(f: File): Entry = DataInputStream(f.inputStream().buffered()).use { i ->
        check(i.readInt() == MAGIC)
        val ranges = IntArray(i.readInt()) { i.readInt() }
        Entry(ShortArray(i.readInt()) { i.readShort() }, ranges)
    }

    private fun trimDisk(d: File) {
        val files = d.listFiles()?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (f in files) { if (total <= diskBytes) break; total -= f.length(); f.delete() }
    }

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
        private const val MAGIC = 0x52565031 // «RVP1»
    }
}
