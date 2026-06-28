package ru.kost.ruvoice

import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import ru.kost.ruvoice.text.Normalizer
import java.util.Locale

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
    override fun onPause() { view?.let { save(it) }; super.onPause() }

    /** Принудительно сохранить поля в Prefs, не дожидаясь onPause — нужно перед экспортом
     * настроек, чтобы в файл попали несохранённые правки текущей (видимой) вкладки. */
    fun saveNow() { view?.let { save(it) } }

    protected fun EditText.str() = text.toString()
}

/** Голос, частота, прямая речь и проверка (прослушать/разбор). */
class VoiceFragment : PageFragment(R.layout.fragment_voice) {
    private val rates = listOf(48000, 24000)
    private var tts: TextToSpeech? = null
    // Список голосов берём из модели (SileroModels.data — общий на процесс), а не из
    // вручную вписанного списка, чтобы он не разошёлся с ней.
    private val voices by lazy { SileroModels.data(requireContext()).speakers.keys.sorted() }
    private val quoteVoices by lazy { listOf(getString(R.string.quote_voice_default)) + voices }
    private val rateItems by lazy { rates.map { getString(R.string.sample_rate_item, it) } }

    override fun load(v: View) {
        v.dropdown(R.id.voice, voices, prefs.voice.takeIf { it in voices } ?: voices.first())
        v.dropdown(R.id.sampleRate, rateItems, rateItems[rates.indexOf(prefs.sampleRate).coerceAtLeast(0)])
        v.dropdown(R.id.quoteVoice, quoteVoices, prefs.quoteVoice.takeIf { it in voices } ?: quoteVoices.first())
        v.findViewById<EditText>(R.id.quoteRate).setText(prefs.quoteRate.toString())
        v.findViewById<EditText>(R.id.quotePitch).setText(prefs.quotePitch.toString())
        val previewText = v.findViewById<EditText>(R.id.previewText)
        if (previewText.text.isEmpty()) previewText.setText(R.string.preview_text)

        v.findViewById<Button>(R.id.preview).setOnClickListener { btn ->
            save(v)
            btn.isEnabled = false
            preview(btn as Button, previewText.str().ifBlank { getString(R.string.preview_text) })
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
        prefs.quoteRate = v.findViewById<EditText>(R.id.quoteRate).factor()
        prefs.quotePitch = v.findViewById<EditText>(R.id.quotePitch).factor()
    }

    private fun EditText.factor() = (str().replace(',', '.').toFloatOrNull() ?: 1f).coerceIn(0.5f, 2f)
    private fun TextView.str() = text.toString()

    private fun View.dropdown(id: Int, items: List<String>, value: String) =
        findViewById<MaterialAutoCompleteTextView>(id).apply {
            setSimpleItems(items.toTypedArray())
            setText(value, false)
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

    // Прослушивание идёт через платформенный TextToSpeech, а не напрямую через SileroModels:
    // так проверяется тот же путь, которым звук получит читалка (наш сервис как движок).
    private fun preview(button: Button, text: String) {
        val ctx = requireContext().applicationContext
        tts?.shutdown()
        tts = TextToSpeech(ctx, { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale("ru", "RU")
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) { button.post { button.isEnabled = true } }
                    override fun onError(utteranceId: String?) { button.post { button.isEnabled = true } }
                })
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "preview")
            } else button.post {
                Toast.makeText(ctx, getString(R.string.preview_failed, status.toString()), Toast.LENGTH_LONG).show()
                button.isEnabled = true
            }
        }, ctx.packageName)
    }

    override fun onDestroy() {
        tts?.shutdown()
        super.onDestroy()
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

/** Текстовый редактор одного из пользовательских файлов: словарь ударений или замены. */
class EditorFragment : PageFragment(R.layout.fragment_editor) {
    private val stress get() = requireArguments().getBoolean(ARG_STRESS)
    private val file get() = if (stress) prefs.userDictFile else prefs.userReplaceFile

    override fun load(v: View) {
        v.findViewById<TextView>(R.id.hint).setText(if (stress) R.string.user_dict_hint else R.string.user_replace_hint)
        v.findViewById<TextView>(R.id.example).setText(if (stress) R.string.user_dict_example else R.string.user_replace_example)
        v.findViewById<EditText>(R.id.editor).apply {
            hint = getString(if (stress) R.string.user_dict_placeholder else R.string.user_replace_placeholder)
            // после поворота текст восстановит сама вьюха; с диска читаем только при первом показе
            if (text.isEmpty()) setText(if (file.exists()) file.readText() else "")
        }
    }

    override fun save(v: View) = file.writeText(v.findViewById<EditText>(R.id.editor).str())

    companion object {
        private const val ARG_STRESS = "stress"
        private fun of(stress: Boolean) = EditorFragment().apply { arguments = Bundle().apply { putBoolean(ARG_STRESS, stress) } }
        fun stress() = of(true)
        fun replace() = of(false)
    }
}
