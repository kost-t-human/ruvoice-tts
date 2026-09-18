package ru.kost.ruvoice

import android.util.Log
import org.json.JSONObject
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

/** Пак голосов: `filesDir/packs/<id>/{pack.json, tts_mel.ptl, backbone.pte, head.ptl}` — та же нарезка,
 * что у штатной модели в assets. Формат — спека 2026-09-18. */
class Pack(json: String, val dir: File) {
    val id: String
    val title: String
    val license: String
    val source: String
    val sym: Symbols
    /** Имя голоса в модели (ru_alexandr) → speaker id. */
    val speakers: Map<String, Int>
    /** forward с type_ids и focus_mask (13 аргументов, v5_5_ru); false — 11, как у v5_ru и cis. */
    val types: Boolean
    val size: Long get() = Packs.MODEL_FILES.sumOf { File(dir, it).length() }

    init {
        val o = JSONObject(json)
        require(o.optInt("format") == Packs.FORMAT) { "Пак собран для другой версии приложения (формат ${o.optString("format")})" }
        id = o.getString("id")
        require(Packs.ID_RE.matches(id)) { "Недопустимый id пака: $id" }
        title = o.optString("title", id); license = o.optString("license", ""); source = o.optString("source", "")
        sym = Symbols.fromJson(o)
        speakers = o.getJSONObject("speakers").let { j -> j.keys().asSequence().associateWith { j.getInt(it) } }
        require(speakers.isNotEmpty()) { "В паке нет голосов" }
        types = o.optBoolean("types", false)
    }
}

object Packs {
    const val FORMAT = 2
    val MODEL_FILES = listOf("tts_mel.ptl", "backbone.pte", "head.ptl")
    val ID_RE = Regex("[a-z0-9_]{1,40}")
    const val NOT_A_PACK = "Это не пак RuVoice"
    private const val TAG = SileroModels.TAG

    fun dir(filesDir: File) = File(filesDir, "packs")

    // Снимок на процесс: сервис и настройки в одном процессе; сигнатура — каталоги и mtime их pack.json.
    private class Snap(val sig: List<String>, val packs: List<Pack>)
    @Volatile private var snap: Snap? = null

    private fun dirs(filesDir: File) = (dir(filesDir).listFiles() ?: emptyArray()).filter { it.isDirectory && !it.name.startsWith(".") }.sortedBy { it.name }
    private fun sig(filesDir: File) = dirs(filesDir).map { "${it.name}|${File(it, "pack.json").lastModified()}" }

    @Synchronized fun installed(filesDir: File): List<Pack> {
        val s = sig(filesDir)
        snap?.takeIf { it.sig == s }?.let { return it.packs }
        val packs = dirs(filesDir).mapNotNull { d ->
            val json = File(d, "pack.json")
            if (!json.isFile || MODEL_FILES.any { !File(d, it).isFile }) return@mapNotNull null
            try { Pack(json.readText(), d).takeIf { it.id == d.name } } catch (e: Exception) { Log.w(TAG, "пак ${d.name} не разобран", e); null }
        }
        return packs.also { snap = Snap(s, it) }
    }

    fun find(filesDir: File, id: String): Pack? = installed(filesDir).firstOrNull { it.id == id }

    /**
     * Распаковывает zip во временный каталог `packs/.tmp-<n>`, проверяет, переносит в `packs/<id>`.
     * Временный каталог убирается при любом отказе. Битый или обрезанный архив и ошибки формата —
     * IllegalArgumentException с текстом для Snackbar, ошибка записи на диск (ENOSPC) — IOException
     * со своим текстом.
     */
    fun install(zip: InputStream, filesDir: File): Pack {
        val packs = dir(filesDir).also { it.mkdirs() }
        // хвосты .tmp-* от убитого посреди распаковки процесса
        packs.listFiles()?.filter { it.name.startsWith(".tmp-") }?.forEach { it.deleteRecursively() }
        val tmp = File(packs, ".tmp-${System.nanoTime()}").also { it.mkdirs() }
        try {
            var json: String? = null
            try {
                ZipInputStream(zip).use { z ->
                    while (true) {
                        val e = z.nextEntry ?: break
                        when (e.name) {
                            "pack.json" -> json = z.readBytes().decodeToString()
                            in MODEL_FILES -> File(tmp, e.name).outputStream().use { z.copyTo(it) }
                        }
                        z.closeEntry()
                    }
                }
            } catch (e: ZipException) {
                throw IllegalArgumentException(NOT_A_PACK, e)
            } catch (e: EOFException) {
                throw IllegalArgumentException(NOT_A_PACK, e)
            }
            val text = json ?: throw IllegalArgumentException(NOT_A_PACK)
            if (MODEL_FILES.any { !File(tmp, it).isFile }) throw IllegalArgumentException(NOT_A_PACK)
            val pack = try { Pack(text, tmp) } catch (e: IllegalArgumentException) { throw e } catch (e: Exception) { throw IllegalArgumentException(NOT_A_PACK) }
            File(tmp, "pack.json").writeText(text)
            val dest = File(packs, pack.id)
            if (dest.exists()) dest.deleteRecursively()
            if (!tmp.renameTo(dest)) throw IOException("Не удалось переместить пак в ${dest.name}")
            synchronized(this) { snap = null }
            return Pack(text, dest)
        } catch (e: Exception) {
            tmp.deleteRecursively()
            throw e
        }
    }

    fun delete(filesDir: File, id: String) {
        require(ID_RE.matches(id)) { "Недопустимый id пака: $id" }
        File(dir(filesDir), id).deleteRecursively()
        synchronized(this) { snap = null }
    }
}
