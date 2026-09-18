package ru.kost.ruvoice

import java.util.zip.ZipInputStream

/**
 * Текст книги для сбора имён (вкладка «Проверка», кнопка «Имена из книги»): txt как есть, fb2 — тело без
 * сносок и картинок, epub (и fb2.zip) — главы из архива подряд. Нужны только слова, не вёрстка, поэтому теги режутся
 * регэкспом без XML-парсера; абзац — строка.
 */
object Book {
    fun text(bytes: ByteArray): String {
        if (bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) return zip(bytes)
        val s = decode(bytes)
        return if ("<FictionBook" in s.take(2000)) fb2(s) else s
    }

    private fun fb2(s: String) = strip(s.replace(fb2Skip, ""))

    /** Кодировка из заголовка XML (fb2 часто в cp1251), иначе как у словарей: UTF-8 или cp1251. */
    private fun decode(bytes: ByteArray): String {
        val head = String(bytes, 0, minOf(bytes.size, 200), Charsets.ISO_8859_1)
        val enc = encRe.find(head)?.groupValues?.get(1)
        return try { if (enc != null) String(bytes, charset(enc)) else Dicts.decode(bytes) } catch (e: Exception) { Dicts.decode(bytes) }
    }

    private fun zip(bytes: ByteArray): String = buildString {
        ZipInputStream(bytes.inputStream()).use { zip ->
            generateSequence { zip.nextEntry }.forEach { e ->
                when (e.name.lowercase().substringAfterLast('.')) {
                    "xhtml", "html", "htm" -> append(strip(decode(zip.readBytes()))).append('\n')
                    "fb2" -> append(fb2(decode(zip.readBytes()))).append('\n')
                }
            }
        }
    }

    fun strip(html: String): String = html.replace(skipRe, "").replace(breakRe, "\n").replace(tagRe, "")
        .replace(entityRe) { m ->
            val e = m.groupValues[1]
            when {
                e.startsWith("#x") -> e.drop(2).toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: m.value
                e.startsWith("#") -> e.drop(1).toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value
                else -> entities[e] ?: m.value
            }
        }

    private val encRe = Regex("encoding=[\"']([^\"']+)")
    private val fb2Skip = Regex("<(description|binary|body name=\"(notes|comments)\")[\\s\\S]*?</(description|binary|body)>")
    private val skipRe = Regex("<(head|style|script)\\b[\\s\\S]*?</\\1>|<!--[\\s\\S]*?-->", RegexOption.IGNORE_CASE)
    private val breakRe = Regex("</(p|v|div|h[1-6]|li|title|subtitle|text-author|tr)>|<br\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val tagRe = Regex("<[^>]+>")
    private val entityRe = Regex("&(#x?[0-9a-fA-F]+|[a-zA-Z]+);")
    private val entities = mapOf("lt" to "<", "gt" to ">", "amp" to "&", "quot" to "\"", "apos" to "'", "nbsp" to " ",
        "mdash" to "—", "ndash" to "–", "laquo" to "«", "raquo" to "»", "hellip" to "…", "shy" to "")
}
