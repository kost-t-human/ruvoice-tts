package ru.kost.ruvoice

import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

/** Языковой пак: `filesDir/packs/<id>/{tts.ptl,pack.json}`. Формат — в спеке 2026-09-14. */
class Pack(json: String, val dir: File) {
    class Lang(val name: String, val speakers: Map<String, Int>)

    val id: String
    val title: String
    val license: String
    val source: String
    val sym: Symbols
    /** Код ISO 639-3 → язык; только языки, которые пак разрешает показывать. */
    val languages: Map<String, Lang>
    /** Код языка → таблица «буква/сочетание → кириллица модели» (грузинский, армянский, латиница). */
    val translit: Map<String, Map<String, String>>
    val ttsFile: File get() = File(dir, "tts.ptl")

    init {
        val o = JSONObject(json)
        require(o.optInt("format") == Packs.FORMAT) { "Неподдерживаемый формат пака: ${o.optString("format")}" }
        id = o.getString("id")
        require(Packs.ID_RE.matches(id)) { "Недопустимый id пака: $id" }
        title = o.optString("title", id); license = o.optString("license", ""); source = o.optString("source", "")
        sym = Symbols.fromJson(o)
        languages = o.getJSONObject("languages").let { j ->
            j.keys().asSequence().associateWith { code ->
                val l = j.getJSONObject(code); val sp = l.getJSONObject("speakers")
                Lang(l.optString("name", code), sp.keys().asSequence().associateWith { sp.getInt(it) })
            }
        }
        require(languages.isNotEmpty() && languages.values.all { it.speakers.isNotEmpty() }) { "В паке нет языков" }
        translit = o.optJSONObject("translit")?.let { j ->
            j.keys().asSequence().associateWith { code -> val t = j.getJSONObject(code); t.keys().asSequence().associateWith { t.getString(it) } }
        } ?: emptyMap()
    }

    fun speakerId(lang: String, speaker: String): Int? = languages[lang]?.speakers?.get(speaker)
}

object Packs {
    const val FORMAT = 1
    val ID_RE = Regex("[a-z0-9_]{1,40}")
    private const val TAG = SileroModels.TAG

    fun dir(filesDir: File) = File(filesDir, "packs")

    // Снимок на процесс: сервис и настройки в одном процессе; сигнатура — каталоги и mtime их pack.json.
    private class Snap(val sig: List<String>, val packs: List<Pack>)
    @Volatile private var snap: Snap? = null

    private fun sig(filesDir: File) = (dir(filesDir).listFiles() ?: emptyArray()).filter { it.isDirectory && !it.name.startsWith(".") }
        .sortedBy { it.name }.map { "${it.name}|${File(it, "pack.json").lastModified()}" }

    @Synchronized fun installed(filesDir: File): List<Pack> {
        val s = sig(filesDir)
        snap?.takeIf { it.sig == s }?.let { return it.packs }
        val packs = (dir(filesDir).listFiles() ?: emptyArray()).filter { it.isDirectory && !it.name.startsWith(".") }.sortedBy { it.name }
            .mapNotNull { d ->
                val json = File(d, "pack.json"); val tts = File(d, "tts.ptl")
                if (!json.isFile || !tts.isFile) return@mapNotNull null
                try { Pack(json.readText(), d).takeIf { it.id == d.name } } catch (e: Exception) { Log.w(TAG, "пак ${d.name} не разобран", e); null }
            }
        return packs.also { snap = Snap(s, it) }
    }

    /**
     * Распаковывает zip во временный каталог `packs/.tmp-<n>`, проверяет, переносит в `packs/<id>`.
     * Любая ошибка — временный каталог удалён, наружу исключение с текстом для Snackbar.
     */
    fun install(zip: InputStream, filesDir: File): Pack {
        val packs = dir(filesDir).also { it.mkdirs() }
        val tmp = File(packs, ".tmp-${System.nanoTime()}").also { it.mkdirs() }
        try {
            var json: String? = null; var hasTts = false
            ZipInputStream(zip).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    when (e.name) {
                        "pack.json" -> json = z.readBytes().decodeToString()
                        "tts.ptl" -> { File(tmp, "tts.ptl").outputStream().use { z.copyTo(it) }; hasTts = true }
                    }
                    z.closeEntry()
                }
            }
            val text = json ?: throw IllegalArgumentException("В файле нет pack.json — это не языковой пак RuVoice")
            if (!hasTts) throw IllegalArgumentException("В паке нет tts.ptl")
            val pack = try { Pack(text, tmp) } catch (e: IllegalArgumentException) { throw e } catch (e: Exception) { throw IllegalArgumentException("pack.json повреждён: ${e.message}") }
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
        File(dir(filesDir), id).deleteRecursively()
        synchronized(this) { snap = null }
    }

    /** Код языка → русское название, по всем пакам; при двух паках на язык — название из первого. */
    fun langs(packs: List<Pack>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (p in packs) for ((code, l) in p.languages) out.putIfAbsent(code, l.name)
        return out
    }

    fun byLang(packs: List<Pack>, lang: String): List<Pack> = packs.filter { lang in it.languages }

    // ponytail: говорящий ищется по имени среди всех паков языка — у cis имена (tat_albina), у turkic/caucasian
    // номера (tat_0), коллизий нет; если появятся — хранить "<packId>:<speaker>".
    fun forSpeaker(packs: List<Pack>, lang: String, speaker: String): Pack? = byLang(packs, lang).firstOrNull { it.speakerId(lang, speaker) != null }
}
