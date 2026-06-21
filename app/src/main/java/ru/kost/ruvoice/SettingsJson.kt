package ru.kost.ruvoice

import org.json.JSONException
import org.json.JSONObject

/**
 * Чистая (без Context) сборка и разбор JSON-файла экспорта настроек RuVoice TTS.
 * Формат: {"app":"ruvoice","version":1,"prefs":{...},"stress":"...","replace":"..."}.
 * Prefs.exportJson/importJson — тонкие обёртки поверх этого объекта, здесь же вся логика,
 * которую удобно тестировать без Android Context.
 */
object SettingsJson {
    private const val APP = "ruvoice"
    private const val VERSION = 1

    /** Разобранный файл: prefs как есть (типы JSON), stress/replace — null, если ключа не было. */
    data class Parsed(val prefs: Map<String, Any>, val stress: String?, val replace: String?)

    fun build(prefsMap: Map<String, Any>, stress: String, replace: String): String {
        val prefsJson = JSONObject()
        for ((key, value) in prefsMap) prefsJson.put(key, value)
        val root = JSONObject()
        root.put("app", APP)
        root.put("version", VERSION)
        root.put("prefs", prefsJson)
        root.put("stress", stress)
        root.put("replace", replace)
        return root.toString(2)
    }

    fun parse(text: String): Parsed {
        val root = try {
            JSONObject(text)
        } catch (e: JSONException) {
            throw IllegalArgumentException("Файл повреждён или это не JSON: ${e.message}")
        }
        if (root.optString("app") != APP) {
            throw IllegalArgumentException("Это не файл настроек RuVoice TTS")
        }
        val prefsJson = root.optJSONObject("prefs")
        val prefs = mutableMapOf<String, Any>()
        if (prefsJson != null) {
            for (key in prefsJson.keys()) prefs[key] = prefsJson.get(key)
        }
        val stress = if (root.has("stress")) root.optString("stress") else null
        val replace = if (root.has("replace")) root.optString("replace") else null
        return Parsed(prefs, stress, replace)
    }
}
