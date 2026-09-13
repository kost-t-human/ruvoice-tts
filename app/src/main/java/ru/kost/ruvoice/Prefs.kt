package ru.kost.ruvoice

import android.content.Context
import java.io.File
import ru.kost.ruvoice.text.Replacements
import ru.kost.ruvoice.text.Rules

class Prefs(private val context: Context) {
    private val p = context.getSharedPreferences("ruvoice", Context.MODE_PRIVATE)
    var voice: String get() = p.getString("voice", "xenia")!!; set(v) = p.edit().putString("voice", v).apply()
    var sampleRate: Int get() = p.getInt("sr", 48000); set(v) = p.edit().putInt("sr", v).apply()
    var sentencePauseMs: Int get() = p.getInt("pause_sentence", 0); set(v) = p.edit().putInt("pause_sentence", v).apply()
    var paragraphPauseMs: Int get() = p.getInt("pause_paragraph", 300); set(v) = p.edit().putInt("pause_paragraph", v).apply()
    var commaPauseMs: Int get() = p.getInt("pause_comma", 100); set(v) = p.edit().putInt("pause_comma", v).apply()
    var idleMinutes: Int get() = p.getInt("idle_min", 5); set(v) = p.edit().putInt("idle_min", v).apply()
    /** Выгружать модели по простою; выключено — держать в памяти, пока жив сервис. */
    var idleOn: Boolean get() = p.getBoolean("idle_on", true); set(v) = p.edit().putBoolean("idle_on", v).apply()
    /** Множители темпа/высоты поверх того, что просит читалка; 1 — без изменений. */
    var rate: Float get() = p.getFloat("rate", 1f); set(v) = p.edit().putFloat("rate", v).apply()
    var pitch: Float get() = p.getFloat("pitch", 1f); set(v) = p.edit().putFloat("pitch", v).apply()
    /** Голос прямой речи; пустая строка — как основной. */
    var quoteVoice: String get() = p.getString("quote_voice", "")!!; set(v) = p.edit().putString("quote_voice", v).apply()
    var quoteRate: Float get() = p.getFloat("quote_rate", 1f); set(v) = p.edit().putFloat("quote_rate", v).apply()
    var quotePitch: Float get() = p.getFloat("quote_pitch", 1f); set(v) = p.edit().putFloat("quote_pitch", v).apply()
    /** Распознавать прямую речь (отдельный голос/темп/высота); по умолчанию выключено. */
    var quoteOn: Boolean get() = p.getBoolean("quote_on", false); set(v) = p.edit().putBoolean("quote_on", v).apply()
    /** Выключенные правила вкладки «Правила» — ключи Rules.KEYS через запятую. */
    var rulesOff: Set<String>
        get() = p.getString("rules_off", "")!!.split(',').filter { it in Rules.KEYS }.toSet()
        set(v) = p.edit().putString("rules_off", v.filter { it in Rules.KEYS }.joinToString(",")).apply()
    var maxLen: Int get() = p.getInt("max_len", Rules.MAX_LEN_DEFAULT); set(v) = p.edit().putInt("max_len", v).apply()

    /** Правила для пайплайна: выключенные тумблеры плюс «прямая речь» с вкладки «Голос». */
    fun rules() = Rules(rulesOff + (if (quoteOn) emptySet() else setOf("speech")), maxLen.coerceIn(Rules.MAX_LEN_MIN, Rules.MAX_LEN_MAX))

    val userDictFile: File get() = File(context.filesDir, "user_stress.txt")
    val userReplaceFile: File get() = File(context.filesDir, "user_replace.txt")

    init {
        // Предустановки замен — только пока файла нет (первый запуск): дальше это обычный
        // пользовательский список, удалённое не возвращаем.
        if (!userReplaceFile.exists()) userReplaceFile.writeText(DEFAULT_REPLACE)
    }

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
            "idle_on" to idleOn,
            "rate" to rate.toDouble(),
            "pitch" to pitch.toDouble(),
            "quote_voice" to quoteVoice,
            "quote_rate" to quoteRate.toDouble(),
            "quote_pitch" to quotePitch.toDouble(),
            "quote_on" to quoteOn,
            "rules_off" to rulesOff.joinToString(","),
            "max_len" to maxLen,
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
        // Только 24000/48000 — реальные частоты модели (review final-fix п.10), другое значение
        // из повреждённого/чужого файла не трогает текущую настройку.
        (prefsMap["sr"] as? Number)?.let { it.toInt() }?.takeIf { it == 24000 || it == 48000 }?.let { sampleRate = it }
        (prefsMap["pause_sentence"] as? Number)?.let { sentencePauseMs = it.toInt().coerceAtLeast(0) }
        (prefsMap["pause_paragraph"] as? Number)?.let { paragraphPauseMs = it.toInt().coerceAtLeast(0) }
        (prefsMap["pause_comma"] as? Number)?.let { commaPauseMs = it.toInt().coerceAtLeast(0) }
        (prefsMap["idle_min"] as? Number)?.let { idleMinutes = it.toInt().coerceAtLeast(1) }
        (prefsMap["idle_on"] as? Boolean)?.let { idleOn = it }
        (prefsMap["rate"] as? Number)?.let { rate = it.toFloat().coerceIn(0.5f, 2f) }
        (prefsMap["pitch"] as? Number)?.let { pitch = it.toFloat().coerceIn(0.5f, 2f) }
        (prefsMap["quote_voice"] as? String)?.let { quoteVoice = it }
        (prefsMap["quote_rate"] as? Number)?.let { quoteRate = it.toFloat().coerceIn(0.5f, 2f) }
        (prefsMap["quote_pitch"] as? Number)?.let { quotePitch = it.toFloat().coerceIn(0.5f, 2f) }
        (prefsMap["quote_on"] as? Boolean)?.let { quoteOn = it }
        (prefsMap["rules_off"] as? String)?.let { rulesOff = it.split(',').toSet() }
        (prefsMap["max_len"] as? Number)?.let { maxLen = it.toInt().coerceIn(Rules.MAX_LEN_MIN, Rules.MAX_LEN_MAX) }
        parsed.stress?.let { userDictFile.writeText(it) }
        parsed.replace?.let { userReplaceFile.writeText(it) }
    }

    companion object {
        /** Примеры для вкладки «Замены»: ударение во фразе перебивает и словарь, и BERT;
         * «ё» там, где в тексте её не пишут, а модель без неё читает не то. */
        const val DEFAULT_REPLACE = "старый замок = старый з+амок\nмалек = малёк\n"
    }
}
