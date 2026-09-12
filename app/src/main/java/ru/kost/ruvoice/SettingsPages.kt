package ru.kost.ruvoice

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import java.util.Locale
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import ru.kost.ruvoice.text.Normalizer

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
    // Список голосов берём из модели (SileroModels.data — общий на процесс), а не из
    // вручную вписанного списка, чтобы он не разошёлся с ней.
    private val voices by lazy { SileroModels.data(requireContext()).speakers.keys.sorted() }
    private val quoteVoices by lazy { listOf(getString(R.string.quote_voice_default)) + voices }
    private val rateItems by lazy { rates.map { getString(R.string.sample_rate_item, it) } }

    override fun load(v: View) {
        v.dropdown(R.id.voice, voices, prefs.voice.takeIf { it in voices } ?: voices.first())
        v.dropdown(R.id.sampleRate, rateItems, rateItems[rates.indexOf(prefs.sampleRate).coerceAtLeast(0)])
        v.dropdown(R.id.quoteVoice, quoteVoices, prefs.quoteVoice.takeIf { it in voices } ?: quoteVoices.first())
        v.slider(R.id.rate, R.id.rateValue, prefs.rate)
        v.slider(R.id.pitch, R.id.pitchValue, prefs.pitch)
        v.slider(R.id.quoteRate, R.id.quoteRateValue, prefs.quoteRate)
        v.slider(R.id.quotePitch, R.id.quotePitchValue, prefs.quotePitch)
        v.sysSlider(R.id.sysRate, R.id.sysRateValue, "tts_default_rate", 0.25f, 3f)
        v.sysSlider(R.id.sysPitch, R.id.sysPitchValue, "tts_default_pitch", 0.5f, 2f)
        v.findViewById<Button>(R.id.sysTtsSettings).setOnClickListener { openSysTtsSettings() }
        val previewText = v.findViewById<EditText>(R.id.previewText)
        if (previewText.text.isEmpty()) previewText.setText(R.string.preview_text)

        v.findViewById<Button>(R.id.preview).setOnClickListener { btn ->
            save(v)
            (activity as SettingsActivity).preview(btn, previewText.str().ifBlank { getString(R.string.preview_text) })
        }
        v.findViewById<Button>(R.id.analyze).setOnClickListener {
            save(v)
            analyze(previewText.str().ifBlank { getString(R.string.preview_text) })
        }
    }

    override fun save(v: View) {
        v.findViewById<TextView>(R.id.voice).str().let { if (it in voices) prefs.voice = it }
        v.findViewById<TextView>(R.id.quoteVoice).str().let { prefs.quoteVoice = if (it in voices) it else "" }
        rateItems.indexOf(v.findViewById<TextView>(R.id.sampleRate).str()).let { if (it >= 0) prefs.sampleRate = rates[it] }
        prefs.rate = v.findViewById<Slider>(R.id.rate).value
        prefs.pitch = v.findViewById<Slider>(R.id.pitch).value
        prefs.quoteRate = v.findViewById<Slider>(R.id.quoteRate).value
        prefs.quotePitch = v.findViewById<Slider>(R.id.quotePitch).value
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

    /**
     * Системные темп/высота (Settings.Secure tts_default_rate/pitch, 100 = ×1) — те, что
     * читалка присылает движку. Не наши: в Prefs, save() и экспорт не попадают, пишутся
     * прямо в Settings по концу жеста (одна запись на жест, не на каждый пиксель). Запись
     * требует WRITE_SECURE_SETTINGS, выдаваемого только через adb, — без него возвращаем
     * слайдер к прочитанному значению и объясняем, как выдать.
     * ponytail: пишем только по касанию; сдвиг клавиатурой/TalkBack не сохраняется —
     * при жалобе добавить запись из addOnChangeListener(fromUser) с задержкой.
     */
    private fun View.sysSlider(sliderId: Int, valueId: Int, key: String, from: Float, to: Float) {
        val cr = requireContext().contentResolver
        fun read() = Settings.Secure.getInt(cr, key, 100) / 100f
        slider(sliderId, valueId, read(), from, to).addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {}
            override fun onStopTrackingTouch(slider: Slider) {
                val ok = try {
                    requireContext().checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED &&
                        Settings.Secure.putInt(cr, key, Math.round(slider.value * 100))
                } catch (e: SecurityException) {
                    false
                }
                if (ok) return
                slider.value = snap(read(), from, to)
                MaterialAlertDialogBuilder(requireContext())
                    .setMessage(R.string.sys_no_permission)
                    .setNegativeButton(R.string.sys_settings) { _, _ -> openSysTtsSettings() }
                    .setPositiveButton(R.string.close, null)
                    .show()
                    // команду adb удобно скопировать прямо из окна
                    .findViewById<TextView>(android.R.id.message)?.setTextIsSelectable(true)
            }
        })
    }

    private fun openSysTtsSettings() {
        try {
            startActivity(Intent("com.android.settings.TTS_SETTINGS"))
        } catch (e: ActivityNotFoundException) {
            Snackbar.make(requireView(), R.string.sys_settings_missing, Snackbar.LENGTH_LONG).show()
        }
    }

    // Разбор на сегменты и их нормализация читают словари с диска (SileroModels.data,
    // Normalizer) — считаем в фоновом потоке, диалог показываем на UI-потоке.
    private fun analyze(text: String) {
        val ctx = requireContext().applicationContext
        Thread {
            val report = try {
                val d = SileroModels.data(ctx)
                val segments = Pipeline.plan(text, d, prefs.sentencePauseMs, prefs.paragraphPauseMs, prefs.replacements())
                buildString {
                    for (seg in segments) {
                        var marks = ""
                        if (seg.speech) marks += " [речь]"
                        if (seg.paragraph) marks += " [¶]"
                        appendLine(seg.text + marks)
                        appendLine("→ " + Normalizer.prepare(seg.text, d.allowed))
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
        v.findViewById<EditText>(R.id.idleMinutes).setText(prefs.idleMinutes.toString())
    }

    override fun save(v: View) {
        prefs.sentencePauseMs = v.int(R.id.pauseSentence, 0).coerceAtLeast(0)
        prefs.paragraphPauseMs = v.int(R.id.pauseParagraph, 300).coerceAtLeast(0)
        prefs.commaPauseMs = v.int(R.id.pauseComma, 100).coerceAtLeast(0)
        prefs.idleMinutes = v.int(R.id.idleMinutes, 5).coerceAtLeast(1)
    }

    private fun View.int(id: Int, default: Int) = findViewById<EditText>(id).str().toIntOrNull() ?: default
}
