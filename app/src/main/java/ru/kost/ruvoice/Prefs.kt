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
    var dashPauseMs: Int get() = p.getInt("pause_dash", 150); set(v) = p.edit().putInt("pause_dash", v).apply()
    var idleMinutes: Int get() = p.getInt("idle_min", 5); set(v) = p.edit().putInt("idle_min", v).apply()
    /** Выгружать модели по простою; выключено — держать в памяти, пока жив сервис. */
    var idleOn: Boolean get() = p.getBoolean("idle_on", true); set(v) = p.edit().putBoolean("idle_on", v).apply()
    /** Пакеты, которые пользователь сам отметил экранным чтецом / не чтецом (ScreenReaders). В экспорт
     * настроек не идут — это про приложения конкретного телефона. */
    var srForce: Set<String> get() = p.getStringSet("sr_force", emptySet())!!.toSet(); set(v) = p.edit().putStringSet("sr_force", v).apply()
    var srNever: Set<String> get() = p.getStringSet("sr_never", emptySet())!!.toSet(); set(v) = p.edit().putStringSet("sr_never", v).apply()

    /** Последние отправители запросов (до 8): пакет, подпись, решение автоматики, время — видно на
     * вкладке правил, чтобы на телефоне проверить, кто читает через движок и кем он признан. */
    fun recentCallers(): List<Pair<ScreenReaders.Caller, Long>> =
        p.getString("recent_callers", "")!!.split('\n').mapNotNull { l ->
            val f = l.split('\t'); if (f.size < 4) null else ScreenReaders.Caller(f[0], f[1], f[2] == "1") to (f[3].toLongOrNull() ?: 0L)
        }

    /** Запомнить отправителя; пишем, только если он новый, автоматика передумала или прошёл час —
     * TalkBack шлёт запрос на каждый свайп, писать prefs каждый раз незачем. */
    fun rememberCaller(c: ScreenReaders.Caller) {
        val now = System.currentTimeMillis()
        val list = recentCallers()
        val old = list.firstOrNull { it.first.pkg == c.pkg }
        if (old != null && old.first == c && now - old.second < 3_600_000L) return
        val next = (listOf(c to now) + list.filter { it.first.pkg != c.pkg }).take(8)
        p.edit().putString("recent_callers", next.joinToString("\n") { (k, t) ->
            listOf(k.pkg, k.label.replace('\t', ' ').replace('\n', ' '), if (k.auto) "1" else "0", t.toString()).joinToString("\t") }).apply()
    }

    /** Включить/выключить одно правило, не трогая остальные (тумблер вне вкладки правил). */
    fun setRule(key: String, on: Boolean) { rulesOff = Rules(rulesOff).with(key, on).off }

    /** Отпечаток всех настроек для кэша фраз (PhraseCache): любая правка на вкладках меняет его.
     * Журнал отправителей не в счёт — он пишется сам по себе раз в час. */
    fun stamp(): Int = p.all.filterKeys { it != "recent_callers" }.hashCode()

    /** Справка «Как включить» показана при первом запуске. */
    var setupShown: Boolean get() = p.getBoolean("setup_shown", false); set(v) = p.edit().putBoolean("setup_shown", v).apply()
    /** Множители темпа/высоты поверх того, что просит читалка; 1 — без изменений. */
    var rate: Float get() = p.getFloat("rate", 1f); set(v) = p.edit().putFloat("rate", v).apply()
    var pitch: Float get() = p.getFloat("pitch", 1f); set(v) = p.edit().putFloat("pitch", v).apply()
    /** Голос прямой речи; пустая строка — как основной. */
    var quoteVoice: String get() = p.getString("quote_voice", "")!!; set(v) = p.edit().putString("quote_voice", v).apply()
    var quoteRate: Float get() = p.getFloat("quote_rate", 1f); set(v) = p.edit().putFloat("quote_rate", v).apply()
    var quotePitch: Float get() = p.getFloat("quote_pitch", 1f); set(v) = p.edit().putFloat("quote_pitch", v).apply()
    /** Темп и высота для экранного чтеца (секция «Для TalkBack»): множители поверх темпа самого TalkBack
     * вместо rate/pitch — книги и TalkBack настраиваются отдельно. */
    /** Громкость голоса — множитель звука модели (0,5–2), свой для экранного чтеца. */
    var volume: Float get() = p.getFloat("volume", 1f); set(v) = p.edit().putFloat("volume", v).apply()
    var srVolume: Float get() = p.getFloat("sr_volume", 1f); set(v) = p.edit().putFloat("sr_volume", v).apply()
    var srRate: Float get() = p.getFloat("sr_rate", 1f); set(v) = p.edit().putFloat("sr_rate", v).apply()
    var srPitch: Float get() = p.getFloat("sr_pitch", 1f); set(v) = p.edit().putFloat("sr_pitch", v).apply()
    /** Распознавать прямую речь (отдельный голос/темп/высота); по умолчанию выключено. */
    var quoteOn: Boolean get() = p.getBoolean("quote_on", false); set(v) = p.edit().putBoolean("quote_on", v).apply()
    /** Текст поля «Проверка» на вкладке «Голос»; пустая строка — показывать пример. */
    var previewText: String get() = p.getString("preview_text", "")!!; set(v) = p.edit().putString("preview_text", v).apply()
    /** Переключённые относительно умолчания правила вкладки «Правила» — ключи Rules.KEYS через запятую. */
    var rulesOff: Set<String>
        get() = p.getString("rules_off", "")!!.split(',').filter { it in Rules.KEYS }.toSet()
        set(v) = p.edit().putString("rules_off", v.filter { it in Rules.KEYS }.joinToString(",")).apply()
    var maxLen: Int get() = p.getInt("max_len", Rules.MAX_LEN_DEFAULT); set(v) = p.edit().putInt("max_len", v).apply()
    /** Вкладка «Проверка»: копить имена; список, куда добавлять. */
    var auditNames: Boolean get() = p.getBoolean("audit_names", false); set(v) = p.edit().putBoolean("audit_names", v).apply()
    /** Список, куда в прошлый раз добавляли слово с вкладки «Проверка». */
    fun auditDict(kind: Audit.Kind): String = p.getString("audit_dict_${kind.name}", if (kind == Audit.Kind.NAMES) Dicts.NAMES else Dicts.MAIN)!!
    fun setAuditDict(kind: Audit.Kind, name: String) = p.edit().putString("audit_dict_${kind.name}", name).apply()
    /** Диалог «Проверки»: слово в замены, а не в ударения (ёфикация имён), и список замен, куда. */
    var auditSortAlpha: Boolean get() = p.getBoolean("audit_sort_alpha", false); set(v) = p.edit().putBoolean("audit_sort_alpha", v).apply()
    var accentBookPlus: Boolean get() = p.getBoolean("accent_book_plus", false); set(v) = p.edit().putBoolean("accent_book_plus", v).apply()
    var accentBookHardE: Boolean get() = p.getBoolean("accent_book_hard_e", false); set(v) = p.edit().putBoolean("accent_book_hard_e", v).apply()
    var accentBookAbbr: Boolean get() = p.getBoolean("accent_book_abbr", true); set(v) = p.edit().putBoolean("accent_book_abbr", v).apply()
    var auditReplace: Boolean
        get() = p.getBoolean("audit_replace", false)
        set(v) = p.edit().putBoolean("audit_replace", v).apply()
    var auditReplaceDict: String
        get() = p.getString("audit_dict_replace", Dicts.MAIN)!!
        set(v) = p.edit().putString("audit_dict_replace", v).apply()
    val audit: Audit get() = AUDIT ?: synchronized(Audit::class.java) { AUDIT ?: Audit(context.filesDir).also { AUDIT = it } }
    /** Диалог замен: спойлер «Ударение» с чипами раскрыт; по умолчанию свёрнут. */
    var replaceStressOpen: Boolean get() = p.getBoolean("replace_stress_open", false); set(v) = p.edit().putBoolean("replace_stress_open", v).apply()
    var replaceSampleOpen: Boolean get() = p.getBoolean("replace_sample_open", false); set(v) = p.edit().putBoolean("replace_sample_open", v).apply()
    var focusLevel: Int get() = p.getInt("focus_level", Rules.FOCUS_DEFAULT); set(v) = p.edit().putInt("focus_level", v).apply()

    /** Правила для пайплайна: выключенные тумблеры плюс «прямая речь» с вкладки «Голос». */
    fun rules() = Rules(rulesOff + (if (quoteOn) emptySet() else setOf("speech")), maxLen.coerceIn(Rules.MAX_LEN_MIN, Rules.MAX_LEN_MAX),
        focusLevel.coerceIn(Rules.FOCUS_MIN, Rules.FOCUS_MAX))

    /** Выключенные списки вида — имена через \n (в имени может быть запятая). */
    fun off(kind: Dicts.Kind): Set<String> =
        p.getString("${kind.dir}_off", "")!!.split('\n').filter { it.isNotEmpty() }.toSet()
    fun setOff(kind: Dicts.Kind, names: Set<String>) = p.edit().putString("${kind.dir}_off", names.joinToString("\n")).apply()

    /** Список, открытый на вкладке; если такого файла уже нет — первый не системный. */
    fun current(kind: Dicts.Kind): File {
        val files = dictFiles(kind)
        val name = p.getString("dict_cur_${kind.dir}", Dicts.MAIN)!!
        return files.firstOrNull { Dicts.name(it) == name } ?: files.firstOrNull { Dicts.name(it) != Dicts.SYSTEM }
            ?: Dicts.file(context.filesDir, kind, Dicts.MAIN).also { it.parentFile!!.mkdirs(); it.writeText("") }
    }
    fun setCurrent(kind: Dicts.Kind, name: String) = p.edit().putString("dict_cur_${kind.dir}", name).apply()
    /** Область поиска на вкладке; по умолчанию все списки — иначе слово из разбора приходится искать по каждому. */
    fun searchScope(kind: Dicts.Kind): Dicts.Scope = Dicts.Scope.values().firstOrNull { it.name == p.getString("dict_scope_${kind.dir}", "") } ?: Dicts.Scope.ALL
    fun setSearchScope(kind: Dicts.Kind, s: Dicts.Scope) = p.edit().putString("dict_scope_${kind.dir}", s.name).apply()
    /** Текст окна «Проверить» на вкладках ударений и замен — свой, не с «Голоса»: тот фрагмент пишет своё поле в onPause и затёр бы. */
    var dictPreviewText: String get() = p.getString("dict_preview_text", "")!!; set(v) = p.edit().putString("dict_preview_text", v).apply()

    fun dictFiles(kind: Dicts.Kind): List<File> = Dicts.files(context.filesDir, kind)
    fun enabledDictFiles(kind: Dicts.Kind): List<File> = off(kind).let { off -> dictFiles(kind).filter { Dicts.name(it) !in off } }

    init {
        Dicts.migrate(context.filesDir, DEFAULT_REPLACE)
        // Системный список кладём раз на установку/обновление APK, а не на каждый Prefs():
        // у каждой страницы настроек свой Prefs, а чтение 4 МБ из assets в init давало
        // 300–600 мс фриза на каждую вкладку и проскок ViewPager2 мимо нужной страницы.
        val installedAt = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        if (p.getLong("system_dicts_at", -1) != installedAt) {
            Dicts.installSystem(context.filesDir) { path -> runCatching { context.assets.open(path).bufferedReader().readText() }.getOrNull() }
            p.edit().putLong("system_dicts_at", installedAt).apply()
        }
        // пустой список «Имена» один раз: удалённый пользователем не воскрешаем
        if (!p.getBoolean("names_dict_made", false)) {
            Dicts.file(context.filesDir, Dicts.Kind.STRESS, Dicts.NAMES).takeIf { !it.exists() }?.let { it.parentFile!!.mkdirs(); it.writeText("") }
            p.edit().putBoolean("names_dict_made", true).apply()
        }
    }

    /** Слитые включённые списки ударений, из кэша процесса. */
    fun userDict(): Map<String, String> = DictCache.stress(enabledDictFiles(Dicts.Kind.STRESS))

    fun replacements(): Replacements = DictCache.replacements(enabledDictFiles(Dicts.Kind.REPLACE))

    /** Собирает JSON-файл экспорта настроек (текущие Prefs + все списки ударений и замен). */
    fun exportJson(): String {
        val prefsMap = mapOf(
            "voice" to voice,
            "sr" to sampleRate,
            "pause_sentence" to sentencePauseMs,
            "pause_paragraph" to paragraphPauseMs,
            "pause_comma" to commaPauseMs,
            "pause_dash" to dashPauseMs,
            "idle_min" to idleMinutes,
            "idle_on" to idleOn,
            "rate" to rate.toDouble(),
            "pitch" to pitch.toDouble(),
            "quote_voice" to quoteVoice,
            "quote_rate" to quoteRate.toDouble(),
            "quote_pitch" to quotePitch.toDouble(),
            "volume" to volume.toDouble(),
            "sr_volume" to srVolume.toDouble(),
            "sr_rate" to srRate.toDouble(),
            "sr_pitch" to srPitch.toDouble(),
            "quote_on" to quoteOn,
            "rules_off" to rulesOff.joinToString(","),
            "max_len" to maxLen,
            "focus_level" to focusLevel,
            "audit_names" to auditNames,
            "audit_dict_names" to auditDict(Audit.Kind.NAMES),
            "audit_dict_replace" to auditReplaceDict,
        )
        fun all(kind: Dicts.Kind) = dictFiles(kind).associate { Dicts.name(it) to it.readText() }
        val auditLists = Audit.Kind.values().associate { it.name.lowercase() to audit.text(it) }.filterValues { it.isNotEmpty() }
        return SettingsJson.build(prefsMap, all(Dicts.Kind.STRESS), all(Dicts.Kind.REPLACE), off(Dicts.Kind.STRESS), off(Dicts.Kind.REPLACE), auditLists)
    }

    /**
     * Разбирает JSON-файл экспорта и применяет его: отсутствующие в файле ключи не трогает,
     * неизвестные игнорирует, числа приводит к тем же границам, что и UI (см. SettingsPages).
     * Списки из файла перезаписывают одноимённые, остальные остаются.
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
        (prefsMap["pause_dash"] as? Number)?.let { dashPauseMs = it.toInt().coerceAtLeast(0) }
        (prefsMap["idle_min"] as? Number)?.let { idleMinutes = it.toInt().coerceAtLeast(1) }
        (prefsMap["idle_on"] as? Boolean)?.let { idleOn = it }
        (prefsMap["rate"] as? Number)?.let { rate = it.toFloat().coerceIn(0.5f, 2f) }
        (prefsMap["pitch"] as? Number)?.let { pitch = it.toFloat().coerceIn(0.5f, 2f) }
        (prefsMap["quote_voice"] as? String)?.let { quoteVoice = it }
        (prefsMap["quote_rate"] as? Number)?.let { quoteRate = it.toFloat().coerceIn(0.5f, 2f) }
        (prefsMap["quote_pitch"] as? Number)?.let { quotePitch = it.toFloat().coerceIn(0.5f, 2f) }
        (prefsMap["volume"] as? Number)?.let { volume = it.toFloat().coerceIn(0.5f, 2f) }
        (prefsMap["sr_volume"] as? Number)?.let { srVolume = it.toFloat().coerceIn(0.5f, 2f) }
        (prefsMap["sr_rate"] as? Number)?.let { srRate = it.toFloat().coerceIn(0.5f, 2f) }
        (prefsMap["sr_pitch"] as? Number)?.let { srPitch = it.toFloat().coerceIn(0.5f, 2f) }
        (prefsMap["quote_on"] as? Boolean)?.let { quoteOn = it }
        (prefsMap["rules_off"] as? String)?.let { rulesOff = it.split(',').toSet() }
        (prefsMap["max_len"] as? Number)?.let { maxLen = it.toInt().coerceIn(Rules.MAX_LEN_MIN, Rules.MAX_LEN_MAX) }
        (prefsMap["focus_level"] as? Number)?.let { focusLevel = it.toInt().coerceIn(Rules.FOCUS_MIN, Rules.FOCUS_MAX) }
        (prefsMap["audit_names"] as? Boolean)?.let { auditNames = it }
        (prefsMap["audit_dict_names"] as? String)?.let { setAuditDict(Audit.Kind.NAMES, it) }
        (prefsMap["audit_dict_replace"] as? String)?.let { auditReplaceDict = it }
        parsed.audit?.forEach { (k, text) -> Audit.Kind.values().firstOrNull { it.name.equals(k, ignoreCase = true) }?.let { audit.load(it, text) } }
        fun write(kind: Dicts.Kind, dicts: Map<String, String>?) = dicts?.forEach { (name, body) ->
            if (Dicts.validName(name)) Dicts.file(context.filesDir, kind, name.trim()).also { it.parentFile!!.mkdirs() }.writeText(body)
        }
        write(Dicts.Kind.STRESS, parsed.stress); write(Dicts.Kind.REPLACE, parsed.replace)
        parsed.stressOff?.let { setOff(Dicts.Kind.STRESS, it) }
        parsed.replaceOff?.let { setOff(Dicts.Kind.REPLACE, it) }
    }

    companion object {
        /** Один на процесс: сервис пишет, настройки читают. */
        @Volatile private var AUDIT: Audit? = null
        /** Пример для вкладки «Замены»: «ё» там, где в тексте её не пишут, а модель без неё читает не то. */
        const val DEFAULT_REPLACE = "малек = малёк\n"
    }
}
