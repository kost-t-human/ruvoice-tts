package ru.kost.ruvoice.text

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

enum class Gender { M, F, N }

/**
 * Таблица морфологии AOT (`assets/morph.bin`, строит `tools/aot_morph.py`): существительные,
 * прилагательные, порядковые и местоимения-прилагательные, форма → теги. Записи по 12 байт
 * (хэш FNV-1a 64 + 32 бита тегов), отсортированы по хэшу — mmap ассета и бинарный поиск, старт
 * приложения не задерживает. Биты тегов: 0–3 часть речи (1 N, 2 A, 4 порядковое, 8 местоимение),
 * 4–6 род (16 м, 32 ж, 64 ср), 7 одушевлённое, 8–13 ед. ч. по падежам nom gen dat acc ins loc,
 * 14–19 мн. ч., 20–25 и 26–31 — ед. ч. ж. и ср. рода у прилагательных (м. р. — в 8–13).
 * Винительный у прилагательных — неодушевлённый; одушевлённый совпадает с родительным.
 */
class Morph(private val buf: ByteBuffer) {
    private val size = buf.limit() / 12

    /** Теги формы; 0 — формы в таблице нет. */
    fun tags(form: String): Int {
        val h = hash(form)
        var lo = 0; var hi = size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val v = buf.getLong(mid * 12)
            when {
                v < h -> lo = mid + 1
                v > h -> hi = mid - 1
                else -> return buf.getInt(mid * 12 + 8)
            }
        }
        return 0
    }

    companion object {
        const val NOUN = 1
        const val ADJ = 2
        const val ORD = 4
        const val PRON = 8

        fun isNoun(t: Int) = t and NOUN != 0
        fun isAdjective(t: Int) = t and (ADJ or ORD or PRON) != 0
        fun animate(t: Int) = t and 128 != 0
        /** Род леммы существительного; null — формы нет, род не проставлен или общий («сирота»). */
        fun gender(t: Int): Gender? = when (t shr 4 and 7) { 1 -> Gender.M; 2 -> Gender.F; 4 -> Gender.N; else -> null }
        /** Все роды леммы (у формы-омонима двух лемм их может быть два). */
        fun genders(t: Int): List<Gender> = Gender.values().filter { t shr (4 + it.ordinal) and 1 != 0 }
        fun nounCases(t: Int, plural: Boolean): Set<Case> = cases(t shr if (plural) 14 else 8)
        fun adjCases(t: Int, gender: Gender?, plural: Boolean): Set<Case> =
            cases(t shr when { plural -> 14; gender == Gender.F -> 20; gender == Gender.N -> 26; else -> 8 })
        private fun cases(bits: Int): Set<Case> = Case.values().filterTo(HashSet()) { bits shr it.ordinal and 1 != 0 }

        /** FNV-1a 64 бита от UTF-8 формы строчными, «ё» → «е»; та же функция в tools/aot_morph.py. */
        fun hash(form: String): Long {
            var h = -0x340d631b7bdddcdbL // 0xcbf29ce484222325
            for (b in form.lowercase().replace('ё', 'е').toByteArray()) h = (h xor (b.toLong() and 0xff)) * 0x100000001b3L
            return h
        }

        fun open(file: File): Morph =
            Morph(FileChannel.open(file.toPath()).use { it.map(FileChannel.MapMode.READ_ONLY, 0, it.size()) }.order(ByteOrder.LITTLE_ENDIAN))

        /** mmap ассета; если он сжат (build.gradle noCompress «bin» это исключает) — читаем целиком. */
        fun open(context: Context): Morph {
            val t = System.nanoTime()
            val buf = try {
                context.assets.openFd("morph.bin").use { fd ->
                    fd.createInputStream().channel.use { it.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.length) }
                }
            } catch (e: IOException) {
                Log.w("RuVoice", "morph.bin без mmap, читаем целиком: $e")
                ByteBuffer.wrap(context.assets.open("morph.bin").use { it.readBytes() })
            }
            Log.i("RuVoice", "morph.bin: ${buf.limit() / 12} форм, ${(System.nanoTime() - t) / 1_000_000} мс")
            return Morph(buf.order(ByteOrder.LITTLE_ENDIAN))
        }
    }
}
