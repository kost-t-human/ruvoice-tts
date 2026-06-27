package ru.kost.ruvoice

import android.content.Context
import java.io.File
import ru.kost.ruvoice.text.Replacements

class Prefs(private val context: Context) {
    private val p = context.getSharedPreferences("ruvoice", Context.MODE_PRIVATE)
    var voice: String get() = p.getString("voice", "xenia")!!; set(v) = p.edit().putString("voice", v).apply()
    var sampleRate: Int get() = p.getInt("sr", 48000); set(v) = p.edit().putInt("sr", v).apply()
    var sentencePauseMs: Int get() = p.getInt("pause_sentence", 0); set(v) = p.edit().putInt("pause_sentence", v).apply()
    var paragraphPauseMs: Int get() = p.getInt("pause_paragraph", 300); set(v) = p.edit().putInt("pause_paragraph", v).apply()
    var commaPauseMs: Int get() = p.getInt("pause_comma", 100); set(v) = p.edit().putInt("pause_comma", v).apply()
    var idleMinutes: Int get() = p.getInt("idle_min", 5); set(v) = p.edit().putInt("idle_min", v).apply()
    /** Голос прямой речи; пустая строка — как основной. */
    var quoteVoice: String get() = p.getString("quote_voice", "")!!; set(v) = p.edit().putString("quote_voice", v).apply()
    var quoteRate: Float get() = p.getFloat("quote_rate", 1f); set(v) = p.edit().putFloat("quote_rate", v).apply()
    var quotePitch: Float get() = p.getFloat("quote_pitch", 1f); set(v) = p.edit().putFloat("quote_pitch", v).apply()

    val userDictFile: File get() = File(context.filesDir, "user_stress.txt")
    val userReplaceFile: File get() = File(context.filesDir, "user_replace.txt")

    /** Строки «слово сл+ово»; пустые и с # пропускаются. */
    fun userDict(): Map<String, String> {
        if (!userDictFile.exists()) return emptyMap()
        return userDictFile.readLines().mapNotNull { line ->
            val t = line.trim(); if (t.isEmpty() || t.startsWith("#")) return@mapNotNull null
            val parts = t.split(Regex("\\s+")); if (parts.size < 2) null else parts[0].lowercase() to parts[1].lowercase()
        }.toMap()
    }

    fun replacements(): Replacements =
        Replacements.parse(if (userReplaceFile.exists()) userReplaceFile.readLines() else emptyList())

    /** Собирает JSON-файл экспорта настроек (текущие Prefs + словарь ударений + замены). */
    fun exportJson(): String {
        val prefsMap = mapOf(
            "voice" to voice,
            "sr" to sampleRate,
            "pause_sentence" to sentencePauseMs,
            "pause_paragraph" to paragraphPauseMs,
            "pause_comma" to commaPauseMs,
            "idle_min" to idleMinutes,
            "quote_voice" to quoteVoice,
            "quote_rate" to quoteRate.toDouble(),
            "quote_pitch" to quotePitch.toDouble(),
        )
        val stress = if (userDictFile.exists()) userDictFile.readText() else ""
        val replace = if (userReplaceFile.exists()) userReplaceFile.readText() else ""
        return SettingsJson.build(prefsMap, stress, replace)
    }

    /**
     * Разбирает JSON-файл экспорта и применяет его: отсутствующие в файле ключи не трогает,
     * неизвестные игнорирует, числа приводит к тем же границам, что и UI (см. SettingsPages).
     */
    fun importJson(text: String) {
        val parsed = SettingsJson.parse(text)
        val prefsMap = parsed.prefs
        (prefsMap["voice"] as? String)?.let { voice = it }
        (prefsMap["sr"] as? Number)?.let { sampleRate = it.toInt() }
        (prefsMap["pause_sentence"] as? Number)?.let { sentencePauseMs = it.toInt().coerceAtLeast(0) }
        (prefsMap["pause_paragraph"] as? Number)?.let { paragraphPauseMs = it.toInt().coerceAtLeast(0) }
        (prefsMap["pause_comma"] as? Number)?.let { commaPauseMs = it.toInt().coerceAtLeast(0) }
        (prefsMap["idle_min"] as? Number)?.let { idleMinutes = it.toInt().coerceAtLeast(1) }
        (prefsMap["quote_voice"] as? String)?.let { quoteVoice = it }
        (prefsMap["quote_rate"] as? Number)?.let { quoteRate = it.toFloat().coerceIn(0.5f, 2f) }
        (prefsMap["quote_pitch"] as? Number)?.let { quotePitch = it.toFloat().coerceIn(0.5f, 2f) }
        parsed.stress?.let { userDictFile.writeText(it) }
        parsed.replace?.let { userReplaceFile.writeText(it) }
    }
}
