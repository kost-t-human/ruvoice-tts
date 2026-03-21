package ru.kost.ruvoice

import android.content.Context
import java.io.File

class Prefs(private val context: Context) {
    private val p = context.getSharedPreferences("ruvoice", Context.MODE_PRIVATE)
    var voice: String get() = p.getString("voice", "xenia")!!; set(v) = p.edit().putString("voice", v).apply()
    var sampleRate: Int get() = p.getInt("sr", 48000); set(v) = p.edit().putInt("sr", v).apply()
    var sentencePauseMs: Int get() = p.getInt("pause_sentence", 0); set(v) = p.edit().putInt("pause_sentence", v).apply()
    var paragraphPauseMs: Int get() = p.getInt("pause_paragraph", 300); set(v) = p.edit().putInt("pause_paragraph", v).apply()
    var idleMinutes: Int get() = p.getInt("idle_min", 5); set(v) = p.edit().putInt("idle_min", v).apply()

    val userDictFile: File get() = File(context.filesDir, "user_stress.txt")

    /** Строки «слово сл+ово»; пустые и с # пропускаются. */
    fun userDict(): Map<String, String> {
        if (!userDictFile.exists()) return emptyMap()
        return userDictFile.readLines().mapNotNull { line ->
            val t = line.trim(); if (t.isEmpty() || t.startsWith("#")) return@mapNotNull null
            val parts = t.split(Regex("\\s+")); if (parts.size < 2) null else parts[0].lowercase() to parts[1].lowercase()
        }.toMap()
    }
}
