package ru.kost.ruvoice

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.EditText
import android.widget.TextView
import java.util.Locale
import androidx.fragment.app.Fragment
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import ru.kost.ruvoice.text.English
import ru.kost.ruvoice.text.Rules

/**
 * Страница настроек: в onViewCreated читает Prefs в поля, в onPause пишет поля в Prefs.
 * ViewPager2 переводит невидимые страницы в STARTED, так что onPause срабатывает и при
 * смене вкладки, и при уходе из приложения — отдельная кнопка «Сохранить» не нужна.
 */
abstract class PageFragment(layout: Int) : Fragment(layout) {
    protected val prefs by lazy { Prefs(requireContext()) }
    protected abstract fun load(v: View)
    protected abstract fun save(v: View)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) { view.markHeadings(); load(view) }
    override fun onPause() {
        // recreate() после импорта (Task 25) сначала распускает старые фрагменты — им нельзя
        // затирать только что импортированный файл своими устаревшими полями.
        if (activity?.intent?.getBooleanExtra(SettingsActivity.EXTRA_IMPORT_DONE, false) != true) view?.let { save(it) }
        super.onPause()
    }

    /** Принудительно сохранить поля в Prefs, не дожидаясь onPause — нужно перед экспортом
     * настроек, чтобы в файл попали несохранённые правки текущей (видимой) вкладки. */
    fun saveNow() { view?.let { save(it) } }

    protected fun EditText.str() = text.toString()
}

// Slider падает при layout, если значение не на сетке шага 0.05 (импорт «0.73»,
// старые quote_rate из текстового поля) — округляем к шагу и зажимаем в диапазон.
private fun snap(raw: Float, from: Float, to: Float) = (Math.round((raw - from) / 0.05f) * 0.05f + from).coerceIn(from, to)

/** Слайдер темпа/высоты: подпись «×1.25» над ним, поплавок «1,25» при перетаскивании. TalkBack
 * слышит один элемент — слайдер с названием [label] и значением из поплавка: строка подписи над
 * ним скрыта, а «×» из значения голос не произносит (или называет «знак умножения»). */
internal fun View.rateSlider(sliderId: Int, valueId: Int, raw: Float, label: String, from: Float = 0.5f, to: Float = 2f): Slider {
    val valueView = findViewById<TextView>(valueId)
    fun format(v: Float) = "×%.2f".format(Locale.ROOT, v)
    val value = snap(raw, from, to)
    valueView.text = format(value)
    (valueView.parent as View).importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    return findViewById<Slider>(sliderId).apply {
        contentDescription = label
        setLabelFormatter { "%.2f".format(Locale("ru"), it) }
        this.value = value
        addOnChangeListener { _, v, _ -> valueView.text = format(v) }
    }
}

/** Голос, частота, прямая речь и проверка (прослушать/разбор). */
class VoiceFragment : PageFragment(R.layout.fragment_voice) {
    private val rates = listOf(48000, 24000)
    private val d by lazy { SileroModels.data(requireContext()) }
    private val packs by lazy { Packs.installed(requireContext().filesDir) }
    // Список голосов из модели и установленных паков (Speaker.names), в списке подписи Speaker.label.
    private val voices by lazy { Speaker.names(d, packs) }
    private fun nameOf(label: String) = voices.firstOrNull { Speaker.label(it) == label }
    /** Голоса прямой речи — того же движка, что основной: «как основной» + Speaker.sameEngine. */
    private fun quoteItems(main: String): List<String> {
        val s = Speaker.resolve(main, d, packs) ?: Speaker.default(d, packs) ?: return listOf(getString(R.string.quote_voice_default))
        return listOf(getString(R.string.quote_voice_default)) + Speaker.sameEngine(s, d, packs).map { Speaker.label(it) }
    }
    private val rateItems by lazy { rates.map { getString(R.string.sample_rate_item, it) } }

    override fun load(v: View) {
        // lite без пака: голосов нет — пустые выпадашки, кнопки ниже ничего не делают.
        // Через Speaker.resolve: в lite голое «aidar» из старых prefs — это «ru/aidar» из voices.
        val main = Speaker.resolve(prefs.voice, d, packs)?.name ?: Speaker.default(d, packs)?.name ?: ""
        val voiceView = v.dropdown(R.id.voice, voices.map { Speaker.label(it) }, Speaker.label(main))
        val quote = Speaker.resolve(prefs.quoteVoice, d, packs)?.name?.let(Speaker::label)?.takeIf { it in quoteItems(main) }
        val quoteView = v.dropdown(R.id.quoteVoice, quoteItems(main), quote ?: quoteItems(main).first())
        voiceView.setOnItemClickListener { _, _, _, _ ->
            // сменился движок — список прямой речи другой, несовместимый выбор на «как основной»
            val items = quoteItems(nameOf(voiceView.str()) ?: main)
            quoteView.setSimpleItems(items.toTypedArray())
            if (quoteView.str() !in items) quoteView.setText(items.first(), false)
        }
        v.dropdown(R.id.sampleRate, rateItems, rateItems[rates.indexOf(prefs.sampleRate).coerceAtLeast(0)])
        v.rateSlider(R.id.rate, R.id.rateValue, prefs.rate, getString(R.string.quote_rate))
        v.rateSlider(R.id.pitch, R.id.pitchValue, prefs.pitch, getString(R.string.quote_pitch))
        v.rateSlider(R.id.volume, R.id.volumeValue, prefs.volume, getString(R.string.volume))
        v.rateSlider(R.id.quoteRate, R.id.quoteRateValue, prefs.quoteRate, getString(R.string.quote_rate_a11y))
        v.rateSlider(R.id.quotePitch, R.id.quotePitchValue, prefs.quotePitch, getString(R.string.quote_pitch_a11y))
        // Настройки прямой речи видны только при включённом распознавании.
        val quoteGroup = v.findViewById<View>(R.id.quoteGroup)
        v.findViewById<MaterialSwitch>(R.id.quoteOn).apply {
            isChecked = prefs.quoteOn
            quoteGroup.visibility = if (isChecked) View.VISIBLE else View.GONE
            setOnCheckedChangeListener { _, on -> quoteGroup.visibility = if (on) View.VISIBLE else View.GONE }
        }
        v.findViewById<Button>(R.id.sysTtsSettings).setOnClickListener { requireContext().openSysTtsSettings(v) }
        v.findViewById<TextView>(R.id.setupHelp).apply {
            paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG // иначе сливается с подписями ниже
            setOnClickListener { (activity as? SettingsActivity)?.showSetupHelp() }
        }
        val previewText = v.findViewById<EditText>(R.id.previewText)
        if (previewText.text.isEmpty()) previewText.setText(prefs.previewText.ifEmpty { getString(R.string.preview_text) })

        v.findViewById<Button>(R.id.preview).setOnClickListener { btn ->
            if (voices.isEmpty()) return@setOnClickListener
            save(v)
            (activity as SettingsActivity).preview(btn, previewText.str().ifBlank { getString(R.string.preview_text) })
        }
        v.findViewById<Button>(R.id.analyze).setOnClickListener {
            if (voices.isEmpty()) return@setOnClickListener
            save(v)
            (activity as SettingsActivity).analyze(previewText.str().ifBlank { getString(R.string.preview_text) })
        }
    }

    override fun save(v: View) {
        val main = nameOf(v.findViewById<TextView>(R.id.voice).str()) ?: prefs.voice
        if (main in voices) prefs.voice = main
        // без голосов (lite до пака) выпадашка пустая — не затирать голос прямой речи из prefs
        if (voices.isNotEmpty()) prefs.quoteVoice = nameOf(v.findViewById<TextView>(R.id.quoteVoice).str())?.takeIf { Speaker.label(it) in quoteItems(main) } ?: ""
        rateItems.indexOf(v.findViewById<TextView>(R.id.sampleRate).str()).let { if (it >= 0) prefs.sampleRate = rates[it] }
        prefs.rate = v.findViewById<Slider>(R.id.rate).value
        prefs.pitch = v.findViewById<Slider>(R.id.pitch).value
        prefs.volume = v.findViewById<Slider>(R.id.volume).value
        prefs.quoteRate = v.findViewById<Slider>(R.id.quoteRate).value
        prefs.quotePitch = v.findViewById<Slider>(R.id.quotePitch).value
        prefs.quoteOn = v.findViewById<MaterialSwitch>(R.id.quoteOn).isChecked
        prefs.previewText = v.findViewById<EditText>(R.id.previewText).str()
    }

    private fun TextView.str() = text.toString()

    private fun View.dropdown(id: Int, items: List<String>, value: String) =
        findViewById<MaterialAutoCompleteTextView>(id).apply {
            setSimpleItems(items.toTypedArray())
            setText(value, false)
            opensFromKeyboard()
        }

    // Системные темп/высота (Settings.Secure, 100 = ×1) — только показать: запись требует
    // WRITE_SECURE_SETTINGS, которое обычному приложению не выдают. Обновляем при возврате
    // с системного экрана (onResume).
    override fun onResume() {
        super.onResume()
        val cr = requireContext().contentResolver
        fun read(key: String) = "%.2f".format(Locale.ROOT, Settings.Secure.getInt(cr, key, 100) / 100f)
        view?.findViewById<TextView>(R.id.sysValues)?.text =
            getString(R.string.sys_values, read("tts_default_rate"), read("tts_default_pitch"))
    }

}

/** Паузы и выгрузка моделей. */
class PausesFragment : PageFragment(R.layout.fragment_pauses) {
    override fun load(v: View) {
        v.findViewById<EditText>(R.id.pauseSentence).setText(prefs.sentencePauseMs.toString())
        v.findViewById<EditText>(R.id.pauseParagraph).setText(prefs.paragraphPauseMs.toString())
        v.findViewById<EditText>(R.id.pauseComma).setText(prefs.commaPauseMs.toString())
        v.findViewById<EditText>(R.id.pauseDash).setText(prefs.dashPauseMs.toString())
        v.findViewById<EditText>(R.id.idleMinutes).setText(prefs.idleMinutes.toString())
        val minutes = v.findViewById<View>(R.id.idleMinutesLayout)
        v.findViewById<MaterialSwitch>(R.id.idleOn).apply {
            isChecked = prefs.idleOn
            minutes.visibility = if (isChecked) View.VISIBLE else View.GONE
            setOnCheckedChangeListener { _, on -> minutes.visibility = if (on) View.VISIBLE else View.GONE }
        }
        // «Не выгружать, пока работает экранный чтец» — то же правило, что в «Чтение с экрана»: пишем сразу,
        // save() его не трогает — иначе старое значение вкладки затёрло бы правку с экрана правил
        v.findViewById<MaterialSwitch>(R.id.srKeepLoaded).apply { isChecked = Rules(prefs.rulesOff).on("sr_keep_loaded") }.setOnCheckedChangeListener { sw, on -> if (sw.tag !== SYNC) prefs.setRule("sr_keep_loaded", on) }
    }

    // экран правил мог переключить то же правило, пока вкладка стояла в фоне
    override fun onResume() {
        super.onResume()
        view?.findViewById<MaterialSwitch>(R.id.srKeepLoaded)?.let { sw ->
            val on = Rules(prefs.rulesOff).on("sr_keep_loaded")
            if (sw.isChecked != on) { sw.tag = SYNC; sw.isChecked = on; sw.tag = null }
        }
    }

    override fun save(v: View) {
        prefs.sentencePauseMs = v.int(R.id.pauseSentence, 0).coerceAtLeast(0)
        prefs.paragraphPauseMs = v.int(R.id.pauseParagraph, 300).coerceAtLeast(0)
        prefs.commaPauseMs = v.int(R.id.pauseComma, 100).coerceAtLeast(0)
        prefs.dashPauseMs = v.int(R.id.pauseDash, 150).coerceAtLeast(0)
        prefs.idleMinutes = v.int(R.id.idleMinutes, 5).coerceAtLeast(1)
        prefs.idleOn = v.findViewById<MaterialSwitch>(R.id.idleOn).isChecked
    }

    private fun View.int(id: Int, default: Int) = findViewById<EditText>(id).str().toIntOrNull() ?: default

    private companion object { val SYNC = Any() }
}

/** Тумблеры правил обработки текста (Rules.KEYS) и длина куска. */
class RulesFragment : PageFragment(R.layout.fragment_rules) {
    private fun res(name: String) = resources.getIdentifier(name, "string", requireContext().packageName)

    /** Строка списка для поиска: заголовок секции ([text] пустой) или правило/блок под ним с текстом. */
    private class Entry(val view: View, val text: String, val group: Int, val header: Boolean)
    private val entries = ArrayList<Entry>()

    override fun load(v: View) {
        val list = v.findViewById<LinearLayout>(R.id.rulesList)
        list.removeAllViews()
        entries.clear()
        var group = -1
        val rules = Rules(prefs.rulesOff)
        val inflater = LayoutInflater.from(v.context)
        for ((i, key) in Rules.KEYS.withIndex()) {
            Rules.SECTIONS[key]?.let { section ->
                val header = TextView(v.context, null, 0, R.style.Section).apply { setText(res("rules_section_$section")); asHeading() }
                list.addView(header,
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                        .apply { topMargin = if (i == 0) 0 else (24 * resources.displayMetrics.density).toInt() })
                group++
                entries += Entry(header, header.text.toString(), group, true)
                // под «Чтение с экрана (TalkBack)» — какие программы это; в поиске ведёт себя как заголовок
                if (section == "talkback") {
                    val hint = TextView(v.context, null, 0, R.style.SectionHint).apply { setText(R.string.rules_section_talkback_hint) }
                    list.addView(hint)
                    entries += Entry(hint, hint.text.toString(), group, true)
                }
            }
            val row = inflater.inflate(R.layout.item_rule, list, false)
            row.findViewById<TextView>(R.id.title).setText(res("rule_$key"))
            row.findViewById<TextView>(R.id.hint).setText(res("rule_${key}_hint"))
            val rowText = getString(res("rule_$key")) + " " + getString(res("rule_${key}_hint"))
            fun extra(block: View, text: String) { list.addView(block); entries += Entry(block, "$rowText $text", group, false) }
            val toggle = row.findViewById<MaterialSwitch>(R.id.toggle)
            toggle.tag = key
            toggle.isChecked = rules.on(key)
            row.asSwitchRow(toggle)
            list.addView(row)
            entries += Entry(row, rowText, group, false)
            // под правилами «Чтение с экрана» — темп и высота чтеца и кто обращался к движку
            if (key == "sr_keep_loaded") {
                extra(inflater.inflate(R.layout.item_sr_sliders, list, false).apply {
                    rateSlider(R.id.srRate, R.id.srRateValue, prefs.srRate, getString(R.string.sr_rate))
                    rateSlider(R.id.srPitch, R.id.srPitchValue, prefs.srPitch, getString(R.string.sr_pitch))
                    rateSlider(R.id.srVolume, R.id.srVolumeValue, prefs.srVolume, getString(R.string.sr_volume))
                }, getString(R.string.sr_rate) + " " + getString(R.string.sr_pitch) + " " + getString(R.string.sr_volume) + " " + getString(R.string.sr_sliders_hint))
                extra(callersBlock(inflater, list), getString(R.string.callers_title) + " " + getString(R.string.callers_hint) + " " + prefs.recentCallers().joinToString(" ") { it.first.label + " " + it.first.pkg })
            }
            // английский другим движком: при включении — предупреждение, под тумблером — выбор движка
            if (key == "en_proxy_books" || key == "en_proxy_sr") toggle.setOnCheckedChangeListener { _, on -> if (on) confirmEnglish(toggle) }
            if (key == "en_proxy_sr") {
                val voiceRow = inflater.inflate(R.layout.item_rule, list, false)
                extra(engineRow(inflater, list) { describeVoice(voiceRow) }, getString(R.string.en_engine))
                extra(voiceRow.also { setupVoiceRow(it) }, getString(R.string.en_voice))
                extra(inflater.inflate(R.layout.item_en_words, list, false).apply {
                    findViewById<EditText>(R.id.enMinWords).setText(prefs.enMinWords.toString())
                }, getString(R.string.en_min_words) + " " + getString(R.string.en_min_words_hint))
            }
            // поле силы ударения — сразу под своим тумблером
            if (key == "focus") extra(inflater.inflate(R.layout.item_focus_level, list, false).apply {
                findViewById<EditText>(R.id.focusLevel).setText(prefs.focusLevel.toString())
            }, getString(R.string.focus_level) + " " + getString(R.string.focus_level_hint))
        }
        v.findViewById<EditText>(R.id.maxLen).setText(prefs.maxLen.toString())
        v.findViewById<EditText>(R.id.rulesFilter).apply {
            doAfterTextChanged { filter(v, it?.toString().orEmpty()) }
            filter(v, text.toString())
        }
    }

    /** Поиск: правило видно, если в названии или подписи есть все слова запроса; заголовок — если
     * виден хоть один пункт секции или совпало само имя секции (тогда видна вся секция). */
    private fun filter(v: View, query: String) {
        val words = query.lowercase().split(' ').filter { it.isNotBlank() }
        fun hit(text: String) = text.lowercase().let { t -> words.all { it in t } }
        val found = v.findViewById<TextView>(R.id.rulesFound)
        if (words.isEmpty()) {
            for (e in entries) e.view.visibility = View.VISIBLE
            found.visibility = View.GONE
            return
        }
        val wholeGroups = entries.filter { it.header && hit(it.text) }.map { it.group }.toSet()
        var count = 0
        for (e in entries) if (!e.header) {
            val show = e.group in wholeGroups || hit(e.text)
            e.view.visibility = if (show) View.VISIBLE else View.GONE
            if (show) count++
        }
        for (e in entries) if (e.header)
            e.view.visibility = if (entries.any { !it.header && it.group == e.group && it.view.visibility == View.VISIBLE }) View.VISIBLE else View.GONE
        found.visibility = View.VISIBLE
        found.text = if (count == 0) getString(R.string.rules_found_none) else resources.getQuantityString(R.plurals.rules_found, count, count)
    }

    /** «Кто читает через движок»: последние отправители запросов, у каждого тумблер «экранный чтец».
     * По умолчанию — как решила автоматика (ScreenReaders), переключение запоминается для пакета.
     * В рамке и со своим заголовком: глазами видно, что строки — не правила, TalkBack доходит жестом «заголовки». */
    private fun callersBlock(inflater: LayoutInflater, parent: LinearLayout): View {
        val ctx = parent.context
        val dp = resources.displayMetrics.density
        val card = MaterialCardView(ctx, null, com.google.android.material.R.attr.materialCardViewOutlinedStyle).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = (16 * dp).toInt(); bottomMargin = (8 * dp).toInt() }
        }
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; val p = (16 * dp).toInt(); setPadding(p, p, p, p / 2) }
        card.addView(box)
        box.addView(TextView(ctx, null, 0, R.style.Section).apply { setText(R.string.callers_title); asHeading() },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        box.addView(TextView(ctx, null, 0, R.style.SectionHint).apply { setText(R.string.callers_hint) })
        val callers = prefs.recentCallers()
        if (callers.isEmpty()) box.addView(TextView(ctx, null, 0, R.style.SectionHint).apply { setText(R.string.callers_empty) })
        for ((c, _) in callers) {
            val row = inflater.inflate(R.layout.item_rule, box, false)
            val hint = row.findViewById<TextView>(R.id.hint)
            val toggle = row.findViewById<MaterialSwitch>(R.id.toggle)
            row.findViewById<TextView>(R.id.title).text = c.label
            fun describe() {
                val manual = c.pkg in prefs.srForce || c.pkg in prefs.srNever
                hint.text = getString(if (manual) R.string.caller_manual else if (c.auto) R.string.caller_auto_sr else R.string.caller_auto_app, c.pkg)
            }
            toggle.isChecked = ScreenReaders.isScreenReader(prefs, c)
            describe()
            toggle.setOnCheckedChangeListener { _, on ->
                prefs.srForce = if (on && !c.auto) prefs.srForce + c.pkg else prefs.srForce - c.pkg
                prefs.srNever = if (!on && c.auto) prefs.srNever + c.pkg else prefs.srNever - c.pkg
                describe()
            }
            row.asSwitchRow(toggle)
            box.addView(row)
        }
        return card
    }

    /** Включили «Английский другим движком» — спокойно объясняем, куда уйдёт текст. «Отмена» или
     * закрытие окна тумблер возвращают; без других движков включать нечего. */
    private fun confirmEnglish(toggle: MaterialSwitch) {
        val engine = EnglishProxy.resolve(requireContext(), prefs.enEngine)
        if (engine == null) {
            toggle.isChecked = false
            MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.en_proxy_confirm_title)
                .setMessage(R.string.en_engine_none).setPositiveButton(R.string.close, null).show()
            return
        }
        var accepted = false
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.en_proxy_confirm_title)
            .setMessage(getString(R.string.en_proxy_confirm, engine.label))
            .setPositiveButton(R.string.en_proxy_enable) { _, _ -> accepted = true }
            .setNegativeButton(R.string.cancel, null)
            .setOnDismissListener { if (!accepted) toggle.isChecked = false }
            .show()
    }

    /** Строка «Движок для английского»: какой сейчас и выбор из установленных. */
    /** Строка «Голос для английского»: голоса движка грузятся в фоне (клиент подключается до пяти секунд). */
    private fun setupVoiceRow(row: View) {
        row.findViewById<View>(R.id.toggle).visibility = View.GONE
        row.findViewById<TextView>(R.id.title).setText(R.string.en_voice)
        describeVoice(row)
        row.isFocusable = true
        row.clickLabel(getString(R.string.en_voice_pick))
        row.setOnClickListener {
            val ctx = requireContext().applicationContext
            val engine = EnglishProxy.resolve(ctx, prefs.enEngine) ?: return@setOnClickListener
            val hint = row.findViewById<TextView>(R.id.hint)
            hint.setText(R.string.en_voice_loading)
            Thread {
                val voices = EnglishProxy.shared(ctx).voices(engine.pkg)
                activity?.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    describeVoice(row)
                    if (voices.isNullOrEmpty()) {
                        MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.en_voice_pick)
                            .setMessage(getString(R.string.en_voice_none, engine.label)).setPositiveButton(R.string.close, null).show()
                        return@runOnUiThread
                    }
                    val labels = listOf(getString(R.string.en_voice_default)) + voices.map { voiceLabel(it) }
                    val cur = voices.indexOfFirst { it.name == prefs.enVoice } + 1
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.en_voice_pick)
                        .setSingleChoiceItems(labels.toTypedArray(), cur) { d, i ->
                            prefs.enVoice = if (i == 0) "" else voices[i - 1].name; describeVoice(row); d.dismiss()
                        }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                }
            }.start()
        }
    }

    /** «Английский (Великобритания), en-gb-x-rjs-local» и пометки: нужен интернет, не скачан. */
    private fun voiceLabel(v: EnglishProxy.VoiceInfo): String =
        listOfNotNull("${v.locale.getDisplayName(Locale("ru")).replaceFirstChar { it.uppercase() }}, ${v.name}",
            getString(R.string.en_voice_network).takeIf { v.network },
            getString(R.string.en_voice_not_installed).takeIf { !v.installed }).joinToString(" · ")

    private fun describeVoice(row: View) {
        row.findViewById<TextView>(R.id.hint).text =
            if (prefs.enVoice.isEmpty()) getString(R.string.en_voice_default) else getString(R.string.en_voice_value, prefs.enVoice)
    }

    private fun engineRow(inflater: LayoutInflater, parent: LinearLayout, onChange: () -> Unit): View {
        val row = inflater.inflate(R.layout.item_rule, parent, false)
        row.findViewById<View>(R.id.toggle).visibility = View.GONE
        row.findViewById<TextView>(R.id.title).setText(R.string.en_engine)
        val hint = row.findViewById<TextView>(R.id.hint)
        fun describe() {
            val e = EnglishProxy.resolve(requireContext(), prefs.enEngine)
            hint.text = if (e == null) getString(R.string.en_engine_none) else getString(R.string.en_engine_value, e.label)
        }
        describe()
        row.isFocusable = true
        row.clickLabel(getString(R.string.en_engine_pick))
        row.setOnClickListener {
            EnglishProxy.forget()
            val engines = EnglishProxy.engines(requireContext())
            if (engines.isEmpty()) { describe(); return@setOnClickListener }
            val cur = EnglishProxy.resolve(requireContext(), prefs.enEngine)
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.en_engine_pick)
                .setSingleChoiceItems(engines.map { it.label }.toTypedArray(), engines.indexOfFirst { it.pkg == cur?.pkg }) { d, i ->
                    // голос прежнего движка новому не подходит
                    if (prefs.enEngine != engines[i].pkg) prefs.enVoice = ""
                    prefs.enEngine = engines[i].pkg; describe(); onChange(); d.dismiss()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
        return row
    }

    override fun save(v: View) {
        val list = v.findViewById<LinearLayout>(R.id.rulesList)
        // тумблеры правил помечены ключом; у строк «Кто читает через движок» тега нет
        prefs.rulesOff = (0 until list.childCount).mapNotNull { list.getChildAt(it).findViewById<MaterialSwitch>(R.id.toggle) }
            .filter { it.tag is String }
            .filter { it.isChecked == (it.tag in Rules.DEFAULT_OFF) }.map { it.tag as String }.toSet()
        prefs.maxLen = (v.findViewById<EditText>(R.id.maxLen).str().toIntOrNull() ?: Rules.MAX_LEN_DEFAULT)
            .coerceIn(Rules.MAX_LEN_MIN, Rules.MAX_LEN_MAX)
        v.findViewById<Slider>(R.id.srRate)?.let { prefs.srRate = it.value }
        v.findViewById<Slider>(R.id.srPitch)?.let { prefs.srPitch = it.value }
        v.findViewById<Slider>(R.id.srVolume)?.let { prefs.srVolume = it.value }
        v.findViewById<EditText>(R.id.enMinWords)?.let { prefs.enMinWords = (it.str().toIntOrNull() ?: English.MIN_WORDS).coerceIn(English.MIN_WORDS, English.MAX_WORDS) }
        prefs.focusLevel = (v.findViewById<EditText>(R.id.focusLevel).str().toIntOrNull() ?: Rules.FOCUS_DEFAULT)
            .coerceIn(Rules.FOCUS_MIN, Rules.FOCUS_MAX)
    }
}
