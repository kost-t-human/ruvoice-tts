package ru.kost.ruvoice

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Чистая (без Context) сборка и разбор JSON-файла экспорта настроек RuVoice TTS.
 * Формат v2: {"app":"ruvoice","version":2,"prefs":{...},"stress":{"имя":"текст",…},
 * "replace":{"имя":"текст",…},"stress_off":["имя",…],"replace_off":[…],
 * "audit":{"names":"текст файла"}} — список вкладки «Проверка» вместе со скрытыми (см. Audit); прочие ключи (старый "unsure") пропускаются.
 * "profiles":{"active":"имя","list":[{"name":"Основной","main":true,"prefs":{снимок ProfileData},"voice_bind":"голос"},…]} — профили (Profiles),
 * "voice_bind" — только у привязанного к голосу;
 * "prefs" — по-прежнему настройки активного профиля: старая версия приложения прочтёт их и пропустит профили,
 * а файл старой версии без "profiles" ложится в активный профиль.
 * В v1 stress/replace были строками одного файла — при разборе они становятся списком «Основной».
 * Prefs.exportJson/importJson — тонкие обёртки поверх этого объекта, здесь же вся логика,
 * которую удобно тестировать без Android Context.
 */
object SettingsJson {
    private const val APP = "ruvoice"
    private const val VERSION = 2

    /** Разобранный файл: prefs как есть (типы JSON); словари и выключенные — null, если ключа не было. */
    data class Parsed(val prefs: Map<String, Any>, val stress: Map<String, String>?, val replace: Map<String, String>?,
                      val stressOff: Set<String>?, val replaceOff: Set<String>?, val audit: Map<String, String>? = null,
                      val profiles: List<ProfileEntry>? = null, val activeProfile: String? = null)

    /** [voice] — голос, к которому привязан профиль (Profiles.bind), null — не привязан. */
    class ProfileEntry(val name: String, val main: Boolean, val prefs: Map<String, Any>, val voice: String? = null)

    fun build(prefsMap: Map<String, Any>, stress: Map<String, String>, replace: Map<String, String>,
              stressOff: Set<String>, replaceOff: Set<String>, audit: Map<String, String> = emptyMap(),
              profiles: List<ProfileEntry> = emptyList(), activeProfile: String? = null): String {
        val prefsJson = JSONObject()
        for ((key, value) in prefsMap) prefsJson.put(key, value)
        val root = JSONObject()
        root.put("app", APP)
        root.put("version", VERSION)
        root.put("prefs", prefsJson)
        root.put("stress", JSONObject(stress))
        root.put("replace", JSONObject(replace))
        root.put("stress_off", JSONArray(stressOff))
        root.put("replace_off", JSONArray(replaceOff))
        if (audit.isNotEmpty()) root.put("audit", JSONObject(audit))
        if (profiles.isNotEmpty()) root.put("profiles", JSONObject().put("active", activeProfile).put("list", JSONArray().also { arr ->
            profiles.forEach { arr.put(JSONObject().put("name", it.name).put("main", it.main).put("prefs", ProfileData.encode(it.prefs))
                .apply { it.voice?.let { v -> put("voice_bind", v) } }) }
        }))
        return root.toString(2)
    }

    fun parse(text: String): Parsed {
        val root = try {
            JSONObject(text)
        } catch (e: JSONException) {
            throw UserError("Файл повреждён или это не JSON: ${e.message}", R.string.err_settings_json, e.message ?: "")
        }
        if (root.optString("app") != APP) {
            throw UserError("Это не файл настроек RuVoice TTS", R.string.err_not_settings)
        }
        val prefsJson = root.optJSONObject("prefs")
        val prefs = mutableMapOf<String, Any>()
        if (prefsJson != null) {
            for (key in prefsJson.keys()) prefs[key] = prefsJson.get(key)
        }
        val prof = root.optJSONObject("profiles")
        val list = prof?.optJSONArray("list")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.filter { it.optString("name").isNotBlank() }.map {
                ProfileEntry(it.optString("name"), it.optBoolean("main"), ProfileData.decode(it.optJSONObject("prefs") ?: JSONObject()),
                    it.optString("voice_bind").takeIf { v -> v.isNotEmpty() })
            }
        }?.takeIf { it.isNotEmpty() }
        return Parsed(prefs, dicts(root, "stress"), dicts(root, "replace"), names(root, "stress_off"), names(root, "replace_off"), dicts(root, "audit"),
            list, prof?.optString("active")?.takeIf { list != null && it.isNotEmpty() })
    }

    /** v2 — объект имя→текст; v1 — строка, она же список «Основной». */
    private fun dicts(root: JSONObject, key: String): Map<String, String>? {
        if (!root.has(key)) return null
        val obj = root.optJSONObject(key) ?: return mapOf(Dicts.MAIN to root.optString(key))
        return obj.keys().asSequence().associateWith { obj.optString(it) }
    }

    private fun names(root: JSONObject, key: String): Set<String>? {
        val arr = root.optJSONArray(key) ?: return null
        return (0 until arr.length()).map { arr.optString(it) }.toSet()
    }
}
