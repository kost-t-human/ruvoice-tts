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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import ru.kost.ruvoice.text.Marks
import ru.kost.ruvoice.text.Normalizer
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

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) = load(view)
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
        v.slider(R.id.rate, R.id.rateValue, prefs.rate)
        v.slider(R.id.pitch, R.id.pitchValue, prefs.pitch)
        v.slider(R.id.quoteRate, R.id.quoteRateValue, prefs.quoteRate)
        v.slider(R.id.quotePitch, R.id.quotePitchValue, prefs.quotePitch)
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
            analyze(previewText.str().ifBlank { getString(R.string.preview_text) })
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
        }

    // Slider падает при layout, если значение не на сетке шага 0.05 (импорт «0.73»,
    // старые quote_rate из текстового поля) — округляем к шагу и зажимаем в диапазон.
    private fun snap(raw: Float, from: Float, to: Float) = (Math.round((raw - from) / 0.05f) * 0.05f + from).coerceIn(from, to)

    /** Слайдер темпа/высоты: подпись «×1.25» над ним, поплавок с тем же форматом при перетаскивании. */
    private fun View.slider(sliderId: Int, valueId: Int, raw: Float, from: Float = 0.5f, to: Float = 2f): Slider {
        val valueView = findViewById<TextView>(valueId)
        fun format(v: Float) = "×%.2f".format(Locale.ROOT, v)
        val value = snap(raw, from, to)
        valueView.text = format(value)
        return findViewById<Slider>(sliderId).apply {
            setLabelFormatter(::format)
            this.value = value
            addOnChangeListener { _, v, _ -> valueView.text = format(v) }
        }
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


    // Разбор на сегменты и их нормализация читают словари с диска (SileroModels.data,
    // Normalizer) — считаем в фоновом потоке, диалог показываем на UI-потоке.
    private fun analyze(text: String) {
        val ctx = requireContext().applicationContext
        Thread {
            val report = try {
                val d = SileroModels.data(ctx)
                val packs = Packs.installed(ctx.filesDir)
                // фильтр символов — того движка, что озвучит: у cis-пака нет «!» и апострофа
                val allowed = (Speaker.resolve(prefs.voice, d, packs) ?: Speaker.default(d, packs))?.sym?.allowed ?: d.sym.allowed
                val rules = prefs.rules()
                val segments = Pipeline.plan(text, d, prefs.sentencePauseMs, prefs.paragraphPauseMs, prefs.replacements(), rules)
                buildString {
                    for (seg in segments) {
                        var marks = ""
                        if (seg.speech) marks += " [речь]"
                        if (seg.paragraph) marks += " [¶]"
                        appendLine(seg.text + marks)
                        appendLine("→ " + Normalizer.prepare(Marks.parse(seg.text).text, allowed, rules))
                        if (seg.breakMs > 0) appendLine("пауза ${seg.breakMs} мс")
                        appendLine()
                    }
                }.trimEnd()
            } catch (e: Exception) {
                e.toString()
            }
            activity?.runOnUiThread {
                // экран могли закрыть, пока считали — окно без Activity уронит show()
                val a = activity ?: return@runOnUiThread
                if (a.isFinishing || a.isDestroyed) return@runOnUiThread
                MaterialAlertDialogBuilder(a)
                    .setTitle(R.string.analyze_title)
                    .setMessage(report)
                    .setPositiveButton(R.string.close, null)
                    .show()
            }
        }.start()
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
}

/** Тумблеры правил обработки текста (Rules.KEYS) и длина куска. */
class RulesFragment : PageFragment(R.layout.fragment_rules) {
    private fun res(name: String) = resources.getIdentifier(name, "string", requireContext().packageName)

    override fun load(v: View) {
        val list = v.findViewById<LinearLayout>(R.id.rulesList)
        list.removeAllViews()
        val rules = Rules(prefs.rulesOff)
        val inflater = LayoutInflater.from(v.context)
        for ((i, key) in Rules.KEYS.withIndex()) {
            Rules.SECTIONS[key]?.let { section ->
                list.addView(TextView(v.context, null, 0, R.style.Section).apply { setText(res("rules_section_$section")) },
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                        .apply { topMargin = if (i == 0) 0 else (24 * resources.displayMetrics.density).toInt() })
            }
            val row = inflater.inflate(R.layout.item_rule, list, false)
            row.findViewById<TextView>(R.id.title).setText(res("rule_$key"))
            row.findViewById<TextView>(R.id.hint).setText(res("rule_${key}_hint"))
            val toggle = row.findViewById<MaterialSwitch>(R.id.toggle)
            toggle.tag = key
            toggle.isChecked = rules.on(key)
            row.setOnClickListener { toggle.toggle() }
            list.addView(row)
            // поле силы ударения — сразу под своим тумблером
            if (key == "focus") list.addView(inflater.inflate(R.layout.item_focus_level, list, false).apply {
                findViewById<EditText>(R.id.focusLevel).setText(prefs.focusLevel.toString())
            })
        }
        v.findViewById<EditText>(R.id.maxLen).setText(prefs.maxLen.toString())
    }

    override fun save(v: View) {
        val list = v.findViewById<LinearLayout>(R.id.rulesList)
        prefs.rulesOff = (0 until list.childCount).mapNotNull { list.getChildAt(it).findViewById<MaterialSwitch>(R.id.toggle) }
            .filter { it.isChecked == (it.tag in Rules.DEFAULT_OFF) }.map { it.tag as String }.toSet()
        prefs.maxLen = (v.findViewById<EditText>(R.id.maxLen).str().toIntOrNull() ?: Rules.MAX_LEN_DEFAULT)
            .coerceIn(Rules.MAX_LEN_MIN, Rules.MAX_LEN_MAX)
        prefs.focusLevel = (v.findViewById<EditText>(R.id.focusLevel).str().toIntOrNull() ?: Rules.FOCUS_DEFAULT)
            .coerceIn(Rules.FOCUS_MIN, Rules.FOCUS_MAX)
    }
}
