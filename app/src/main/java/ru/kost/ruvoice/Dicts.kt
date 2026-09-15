package ru.kost.ruvoice

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.text.Collator
import java.util.Locale

/**
 * Раскладка именных списков на диске (без Context, чтобы тестировать на temp-каталоге):
 * `<filesDir>/dicts/stress/<имя>.txt` и `<filesDir>/dicts/replace/<имя>.txt`, имя списка —
 * имя файла без расширения. Формат строк внутри — прежний (DictLines / Replacements).
 * Плюс кодировки и конверсия для обмена с Демагогом.
 */
object Dicts {
    enum class Kind(val dir: String) { STRESS("stress"), REPLACE("replace") }

    const val MAIN = "Основной"
    /** Встроенный список из assets/dicts/<вид>/Системный.txt: не удаляется и не правится, но выключается как остальные. */
    const val SYSTEM = "Системный"
    /** Пустой список ударений под имена с вкладки «Проверка»; создаётся при первом запуске, удалять можно. */
    const val NAMES = "Имена"
    private const val MAX_NAME = 60
    val COLLATOR: Collator = Collator.getInstance(Locale("ru"))
    private val CP1251: Charset = Charset.forName("windows-1251")

    fun dir(root: File, kind: Kind): File = File(root, "dicts/${kind.dir}")
    fun file(root: File, kind: Kind, name: String): File = File(dir(root, kind), "$name.txt")
    fun name(file: File): String = file.name.removeSuffix(".txt")

    /** Все списки вида: системный первым, остальные по алфавиту (ё на своём месте, см. COLLATOR). Порядок —
     * это и приоритет при слиянии (поздний побеждает), так что свои списки всегда перебивают системный. */
    fun files(root: File, kind: Kind): List<File> =
        dir(root, kind).listFiles { f -> f.isFile && f.name.endsWith(".txt") }.orEmpty()
            .sortedWith(compareBy<File> { name(it) != SYSTEM }.thenBy(COLLATOR) { name(it) })

    /** Однократный переезд со старых user_stress.txt / user_replace.txt в списки «Основной».
     * Если замен не было вовсе — «Основной» с предустановками, как раньше при первом запуске. */
    fun migrate(root: File, defaultReplace: String) {
        val dicts = File(root, "dicts")
        if (dicts.isDirectory) return
        for ((old, kind) in listOf("user_stress.txt" to Kind.STRESS, "user_replace.txt" to Kind.REPLACE)) {
            dir(root, kind).mkdirs()
            File(root, old).takeIf { it.exists() }?.renameTo(file(root, kind, MAIN))
        }
        if (files(root, Kind.REPLACE).isEmpty()) file(root, Kind.REPLACE, MAIN).writeText(defaultReplace)
    }

    /** Кладёт (или обновляет после апдейта приложения) системный список; читает assets через [read]. */
    fun installSystem(root: File, read: (String) -> String?) {
        for (kind in Kind.values()) {
            val text = read("dicts/${kind.dir}/$SYSTEM.txt") ?: continue
            val f = file(root, kind, SYSTEM)
            if (!f.exists() || f.length() != text.toByteArray().size.toLong() || f.readText() != text) { f.parentFile!!.mkdirs(); f.writeText(text) }
        }
    }

    fun validName(name: String): Boolean {
        val t = name.trim()
        return t.isNotEmpty() && t.length <= MAX_NAME && '/' !in t && '\\' !in t
    }

    /** name, если свободно, иначе «name (2)», «name (3)»… */
    fun freeName(root: File, kind: Kind, name: String): String {
        if (!file(root, kind, name).exists()) return name
        var n = 2
        while (file(root, kind, "$name ($n)").exists()) n++
        return "$name ($n)"
    }

    /** Строгий UTF-8 (BOM срезается); не декодируется — windows-1251, как у Демагога. */
    fun decode(bytes: ByteArray): String {
        val body = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) bytes.copyOfRange(3, bytes.size) else bytes
        return try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(body)).toString()
        } catch (e: CharacterCodingException) { String(body, CP1251) }
    }

    /** Текст импортируемого файла → содержимое списка: переводы строк \n; для ударений строки
     * Демагога «слово=сл+ово» приводятся к «слово сл+ово». */
    fun importText(text: String, kind: Kind): String {
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val out = if (kind == Kind.STRESS) lines.map { l ->
            val t = l.trim()
            if (t.startsWith("#") || '=' !in t || ' ' in t) l else t.replaceFirst('=', ' ')
        } else lines
        return out.joinToString("\n").let { if (it.endsWith("\n")) it else "$it\n" }
    }

    /** Список → файл Демагога: «ключ=замена» без пробелов, CRLF; regex-строки («~») Демагог не
     * понимает — их возвращаем отдельно, чтобы предложить сохранить. Комментарии и пустые
     * строки выбрасываются. */
    fun toDemagog(lines: List<String>, kind: Kind): Pair<String, List<String>> {
        val sb = StringBuilder(); val skipped = ArrayList<String>()
        for (line in lines) {
            if (kind == Kind.STRESS) {
                val (w, v) = DictLines.parseStress(line) ?: continue
                sb.append(w).append('=').append(v).append("\r\n")
            } else {
                val (k, v, isRegex) = DictLines.parseReplace(line) ?: continue
                if (isRegex) { skipped += line; continue }
                sb.append(k).append('=').append(v).append("\r\n")
            }
        }
        return sb.toString() to skipped
    }

    fun demagogBytes(text: String): ByteArray = text.toByteArray(CP1251)
}
