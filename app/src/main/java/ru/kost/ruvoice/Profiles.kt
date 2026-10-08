package ru.kost.ruvoice

import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.service.quicksettings.TileService
import org.json.JSONArray
import org.json.JSONObject

/**
 * Профили настроек (вкладка «Профили», плитка в шторке). Рабочие настройки как были — в prefs «ruvoice»,
 * весь остальной код читает их оттуда и о профилях не знает. Профиль — снимок этих prefs: при
 * переключении снимок текущих уходит в активный профиль, prefs заполняются снимком нового.
 * В снимок идёт всё, кроме служебного и состояния экрана ([ProfileData.isProfileKey]) — новые настройки
 * попадают в профили сами. Словари (файлы) общие, в профиле — какие из них выключены.
 *
 * Хранилище — prefs «ruvoice_profiles»: список JSON, id активного и счётчик переключений [gen]. Пустое
 * хранилище (первый запуск после обновления со старой версии) — один профиль «Основной» из текущих
 * настроек, prefs не меняются.
 *
 * Привязка к голосу ([bind]): голос → профиль, у голоса не больше одного профиля. Читалка сменила голос
 * запроса на привязанный ([followVoice]) или голос выбрали на вкладке «Голос» — включается его профиль.
 * Хранится отдельно от списка («binds»), чтобы сервис на каждой фразе не разбирал все снимки.
 */
class Profiles(private val context: Context) {
    class Profile(val id: String, val name: String, val data: Map<String, Any>) {
        val main get() = id == MAIN
    }

    private val live = context.getSharedPreferences("ruvoice", Context.MODE_PRIVATE)
    private val store = context.getSharedPreferences("ruvoice_profiles", Context.MODE_PRIVATE)

    fun list(): List<Profile> = synchronized(LOCK) { read() }
    fun active(): Profile = synchronized(LOCK) { read().let { l -> l.firstOrNull { it.id == activeId() } ?: l.first() } }
    /** Счётчик переключений: открытые страницы настроек по нему понимают, что их поля — от другого профиля. */
    val gen: Int get() = store.getInt("gen", 0)

    /** Имя свободно (без учёта регистра); [except] — профиль, который переименовывают. */
    fun nameFree(name: String, except: String? = null) = list().none { it.id != except && it.name.equals(name.trim(), ignoreCase = true) }

    /** Новый профиль — копия текущих настроек, сразу активный. */
    fun add(name: String): Profile = synchronized(LOCK) {
        val p = Profile("p" + System.currentTimeMillis().toString(36), name.trim(), capture())
        write(read().map { if (it.id == activeId()) Profile(it.id, it.name, p.data) else it } + p, p.id)
        notifyTile(); p
    }

    /** Голос → id профиля. */
    fun binds(): Map<String, String> = synchronized(LOCK) { readBinds() }
    fun boundVoice(id: String): String? = binds().entries.firstOrNull { it.value == id }?.key
    /** Профиль, привязанный к голосу [voice], если он есть и это не активный. */
    fun boundOther(voice: String): Profile? = synchronized(LOCK) {
        val id = readBinds()[voice]?.takeIf { it != activeId() } ?: return@synchronized null
        read().firstOrNull { it.id == id }
    }

    /** Привязать профиль [id] к голосу [voice] (null — отвязать). Голос, привязанный к другому профилю,
     * переходит к этому; прежняя привязка этого профиля снимается. */
    fun bind(id: String, voice: String?) = synchronized(LOCK) {
        writeBinds(readBinds().filter { it.value != id && it.key != voice } + listOfNotNull(voice?.let { it to id }))
    }

    /**
     * Запрос читалки [caller] пришёл голосом [voice]: если голос у этой читалки сменился и к нему привязан
     * профиль — включить профиль, голос в prefs — этот. Сравнение с прошлым голосом той же читалки, а не
     * с prefs: читалка шлёт голос, запомненный при подключении, и после ручной смены профиля (плитка)
     * прежний голос в её запросах назад не переключает. Первый запрос читалки сравнивается с голосом из prefs.
     * Вернёт включённый профиль или null.
     */
    fun followVoice(caller: String, voice: String): Profile? = synchronized(LOCK) {
        val key = "seen_voice_$caller"
        val last = store.getString(key, null) ?: live.getString("voice", null)
        if (last == voice) return@synchronized null
        store.edit().putString(key, voice).commit()
        val target = boundOther(voice) ?: return@synchronized null
        switchTo(target.id)
        live.edit().putString("voice", voice).commit()
        target
    }

    fun rename(id: String, name: String) = synchronized(LOCK) {
        write(read().map { if (it.id == id) Profile(it.id, name.trim(), it.data) else it }, activeId()); notifyTile()
    }

    /** «Основной» не удаляется; удалили активный — включается «Основной». */
    fun delete(id: String) = synchronized(LOCK) {
        if (id == MAIN) return@synchronized
        if (id == activeId()) switchTo(MAIN)
        write(read().filter { it.id != id }, activeId()); bind(id, null); notifyTile()
    }

    fun switchTo(id: String) = synchronized(LOCK) {
        val list = read()
        val target = list.firstOrNull { it.id == id } ?: return@synchronized
        if (id == activeId()) return@synchronized
        val cur = activeId()
        val saved = list.map { if (it.id == cur) Profile(it.id, it.name, capture()) else it }
        write(saved, id)
        load(target.data)
        notifyTile()
    }

    /** Следующий по списку — для плитки при двух профилях. */
    fun next(): Profile = list().let { l -> l[(l.indexOfFirst { it.id == activeId() } + 1) % l.size] }

    /** Для экспорта: все профили, у активного — текущие prefs. */
    fun snapshot(): List<Profile> = synchronized(LOCK) {
        read().map { if (it.id == activeId()) Profile(it.id, it.name, capture()) else it }
    }

    /**
     * Импорт профилей из файла: «Основной» файла — в «Основной», прочие — в одноимённые или новые;
     * [exact] (отмена импорта) — ещё и удалить те, которых в файле нет. Потом включается [activeName]
     * файла, prefs заполняются его снимком (без сохранения текущих поверх — их только что заменили).
     */
    fun import(entries: List<SettingsJson.ProfileEntry>, activeName: String?, exact: Boolean) = synchronized(LOCK) {
        val cur = activeId()
        var list = read().map { if (it.id == cur) Profile(it.id, it.name, capture()) else it }
        if (exact) list = list.filter { p -> p.main || entries.any { !it.main && it.name.equals(p.name, ignoreCase = true) } }
        var n = 0
        // привязки: у профилей из файла — как в файле, у прочих остаются (при [exact] прочих нет)
        var binds = readBinds().filterValues { id -> list.any { it.id == id } }
        for (e in entries) {
            val name = e.name.trim()
            if (name.isEmpty()) continue
            val i = list.indexOfFirst { if (e.main) it.main else !it.main && it.name.equals(name, ignoreCase = true) }
            val id = if (i >= 0) list[i].id else "p" + System.currentTimeMillis().toString(36) + (n++)
            list = if (i >= 0) list.toMutableList().also { it[i] = Profile(id, name, e.prefs) }
                else list + Profile(id, name, e.prefs)
            binds = binds.filterValues { it != id }
            e.voice?.takeIf { it.isNotEmpty() }?.let { v -> binds = binds - v + (v to id) }
        }
        writeBinds(binds)
        // одноимённые после импорта (файл назвал «Основным» другой профиль) — второму номер
        val seen = HashSet<String>()
        list = list.map { p -> var nm = p.name; var k = 2; while (!seen.add(nm.lowercase())) nm = "${p.name} ${k++}"; Profile(p.id, nm, p.data) }
        val target = entries.firstOrNull { it.name.trim() == activeName?.trim() }
            ?.let { e -> list.firstOrNull { if (e.main) it.main else it.name.equals(e.name.trim(), ignoreCase = true) } }
            ?: list.firstOrNull { it.id == cur } ?: list.first()
        write(list, target.id)
        load(target.data)
        notifyTile()
    }

    private fun activeId(): String = store.getString("active", MAIN)!!

    private fun read(): List<Profile> {
        val raw = store.getString("list", null)
        if (raw != null) runCatching {
            val arr = JSONArray(raw)
            val l = (0 until arr.length()).map { arr.getJSONObject(it) }.map {
                // неизменённое имя основного — на языке интерфейса, а не того, на каком его создали
                val name = it.getString("name").let { n -> if (it.getString("id") == MAIN && n in MAIN_NAMES) context.getString(R.string.profile_main) else n }
                Profile(it.getString("id"), name, ProfileData.decode(it.optJSONObject("prefs") ?: JSONObject()))
            }
            if (l.any { it.main }) return l
        }
        // обновление со старой версии (или повреждённое хранилище): «Основной» из текущих настроек
        val l = listOf(Profile(MAIN, context.getString(R.string.profile_main), capture()))
        write(l, MAIN)
        return l
    }

    private fun write(list: List<Profile>, active: String) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("id", it.id).put("name", it.name).put("prefs", ProfileData.encode(it.data))) }
        val gen = if (active != activeId()) store.getInt("gen", 0) + 1 else store.getInt("gen", 0)
        store.edit().putString("list", arr.toString()).putString("active", active).putInt("gen", gen).commit()
    }

    private fun readBinds(): Map<String, String> = runCatching {
        val o = JSONObject(store.getString("binds", null) ?: return emptyMap())
        o.keys().asSequence().associateWith { o.getString(it) }
    }.getOrDefault(emptyMap())

    private fun writeBinds(binds: Map<String, String>) {
        store.edit().putString("binds", JSONObject(binds).toString()).commit()
    }

    private fun capture(): Map<String, Any> = live.all.filterKeys(ProfileData::isProfileKey).mapNotNull { (k, v) -> v?.let { k to it } }.toMap()

    /** Заполнить prefs снимком: ключи профиля, которых в снимке нет, сбрасываются к умолчанию. Значение
     * не того типа, что уже лежит в prefs (чужой или повреждённый файл), пропускается — геттер Prefs упал бы. */
    private fun load(data: Map<String, Any>) {
        val cur = live.all
        val e = live.edit()
        cur.keys.filter { ProfileData.isProfileKey(it) && it !in data }.forEach { e.remove(it) }
        for ((k, v) in data) {
            if (!ProfileData.isProfileKey(k)) continue
            val old = cur[k]
            if (old != null && old::class != v::class && !(old is Set<*> && v is Set<*>)) continue
            when (v) {
                is String -> e.putString(k, v)
                is Boolean -> e.putBoolean(k, v)
                is Int -> e.putInt(k, v)
                is Long -> e.putLong(k, v)
                is Float -> e.putFloat(k, v)
                is Set<*> -> e.putStringSet(k, v.map { it.toString() }.toSet())
            }
        }
        e.commit()
    }

    private fun notifyTile() {
        if (Build.VERSION.SDK_INT >= 24) runCatching {
            TileService.requestListeningState(context, ComponentName(context, ProfileTileService::class.java))
        }
    }

    companion object {
        const val MAIN = "main"
        /** profile_main во всех переводах (values, values-en, values-zh): добавили язык — добавить сюда. */
        private val MAIN_NAMES = setOf("Основной", "Main", "主方案")
        private val LOCK = Any()
    }
}

/** Снимок prefs в JSON с типами (Int/Long/Float иначе не различить после разбора) — без Context, для тестов. */
object ProfileData {
    /** Не настройки: служебное, журнал отправителей, пакеты-чтецы этого телефона, состояние экрана. */
    private val NOT_PROFILE = setOf("recent_callers", "setup_shown", "system_dicts_at", "names_dict_made", "sr_force", "sr_never",
        "preview_text", "dict_preview_text", "audit_sort_alpha", "audit_replace", "replace_stress_open", "replace_sample_open")
    private val NOT_PROFILE_PREFIX = listOf("dict_cur_", "dict_scope_", "accent_book_")

    fun isProfileKey(key: String) = key !in NOT_PROFILE && NOT_PROFILE_PREFIX.none { key.startsWith(it) }

    /** Строка, логическое и набор строк — как есть; числа — {"i":…}, {"l":…}, {"f":…}. */
    fun encode(data: Map<String, Any>): JSONObject = JSONObject().also { o ->
        for ((k, v) in data) o.put(k, when (v) {
            is String, is Boolean -> v
            is Int -> JSONObject().put("i", v)
            is Long -> JSONObject().put("l", v)
            is Float -> JSONObject().put("f", v.toDouble())
            is Set<*> -> JSONArray(v.map { it.toString() }.sorted())
            else -> continue
        })
    }

    fun decode(o: JSONObject): Map<String, Any> = o.keys().asSequence().mapNotNull { k ->
        val v: Any? = when (val raw = o.get(k)) {
            is String, is Boolean -> raw
            is JSONArray -> (0 until raw.length()).map { raw.optString(it) }.toSet()
            is JSONObject -> when {
                raw.has("i") -> raw.optInt("i")
                raw.has("l") -> raw.optLong("l")
                raw.has("f") -> raw.optDouble("f").toFloat()
                else -> null
            }
            else -> null
        }
        v?.let { k to it }
    }.toMap()
}

/** Окно настроек следит за сменой профиля со стороны (плитка в шторке): при возврате в окно или сразу,
 * если окно на экране, зовёт [onChange] — поля на экране от прежнего профиля. */
class ProfileWatch(private val activity: android.app.Activity, private val onChange: () -> Unit) {
    private val store by lazy { activity.getSharedPreferences("ruvoice_profiles", Context.MODE_PRIVATE) }
    private var seen = -1
    private var fired = false
    private val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key -> if (key == "gen") check() }

    fun resume() {
        if (seen < 0) seen = store.getInt("gen", 0)
        check()
        store.registerOnSharedPreferenceChangeListener(listener)
    }

    fun pause() = store.unregisterOnSharedPreferenceChangeListener(listener)

    private fun check() {
        if (fired || store.getInt("gen", 0) == seen || activity.isFinishing) return
        fired = true; onChange()
    }
}
