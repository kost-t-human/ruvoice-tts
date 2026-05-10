package ru.kost.ruvoice

import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale

class SettingsActivity : AppCompatActivity() {
    private lateinit var prefs: Prefs
    private val rates = listOf(48000, 24000)
    private var tts: TextToSpeech? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        prefs = Prefs(this)
        // Список голосов берём из модели (SileroModels.data — общий на процесс), а не из
        // вручную вписанного списка, чтобы он не разошёлся с ней.
        val voices = SileroModels.data(this).speakers.keys.sorted()
        val voice = findViewById<Spinner>(R.id.voice).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, voices)
            setSelection(voices.indexOf(prefs.voice).coerceAtLeast(0))
        }
        val quoteVoices = listOf(getString(R.string.quote_voice_default)) + voices
        val quoteVoice = findViewById<Spinner>(R.id.quoteVoice).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, quoteVoices)
            setSelection((voices.indexOf(prefs.quoteVoice) + 1).coerceAtLeast(0))
        }
        val quoteRate = findViewById<EditText>(R.id.quoteRate).apply { setText(prefs.quoteRate.toString()) }
        val quotePitch = findViewById<EditText>(R.id.quotePitch).apply { setText(prefs.quotePitch.toString()) }
        val sr = findViewById<Spinner>(R.id.sampleRate).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, rates.map { "$it Гц" })
            setSelection(rates.indexOf(prefs.sampleRate).coerceAtLeast(0))
        }
        val ps = findViewById<EditText>(R.id.pauseSentence).apply { setText(prefs.sentencePauseMs.toString()) }
        val pp = findViewById<EditText>(R.id.pauseParagraph).apply { setText(prefs.paragraphPauseMs.toString()) }
        val pc = findViewById<EditText>(R.id.pauseComma).apply { setText(prefs.commaPauseMs.toString()) }
        val idle = findViewById<EditText>(R.id.idleMinutes).apply { setText(prefs.idleMinutes.toString()) }
        val dict = findViewById<EditText>(R.id.userDict).apply { setText(if (prefs.userDictFile.exists()) prefs.userDictFile.readText() else "") }
        val replace = findViewById<EditText>(R.id.userReplace).apply { setText(if (prefs.userReplaceFile.exists()) prefs.userReplaceFile.readText() else "") }
        val previewText = findViewById<EditText>(R.id.previewText).apply { setText(getString(R.string.preview_text)) }

        fun save() {
            prefs.voice = voices[voice.selectedItemPosition]
            prefs.quoteVoice = quoteVoice.selectedItemPosition.let { if (it == 0) "" else voices[it - 1] }
            prefs.quoteRate = (quoteRate.text.toString().toFloatOrNull() ?: 1f).coerceIn(0.5f, 2f)
            prefs.quotePitch = (quotePitch.text.toString().toFloatOrNull() ?: 1f).coerceIn(0.5f, 2f)
            prefs.sampleRate = rates[sr.selectedItemPosition]
            prefs.sentencePauseMs = (ps.text.toString().toIntOrNull() ?: 0).coerceAtLeast(0)
            prefs.paragraphPauseMs = (pp.text.toString().toIntOrNull() ?: 300).coerceAtLeast(0)
            prefs.commaPauseMs = (pc.text.toString().toIntOrNull() ?: 100).coerceAtLeast(0)
            prefs.idleMinutes = (idle.text.toString().toIntOrNull() ?: 5).coerceAtLeast(1)
            prefs.userDictFile.writeText(dict.text.toString())
            prefs.userReplaceFile.writeText(replace.text.toString())
            Toast.makeText(this, R.string.save, Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.save).setOnClickListener { save() }
        findViewById<Button>(R.id.preview).setOnClickListener { btn ->
            save()
            btn.isEnabled = false
            preview(btn as Button, previewText.text.toString().ifBlank { getString(R.string.preview_text) })
        }
    }

    // Прослушивание идёт через платформенный TextToSpeech, а не напрямую через SileroModels:
    // так проверяется тот же путь, которым звук получит читалка (наш сервис как движок).
    private fun preview(button: Button, text: String) {
        tts?.shutdown()
        tts = TextToSpeech(this, { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale("ru", "RU")
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) { runOnUiThread { button.isEnabled = true } }
                    override fun onError(utteranceId: String?) { runOnUiThread { button.isEnabled = true } }
                })
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "preview")
            } else runOnUiThread {
                Toast.makeText(this, getString(R.string.preview_failed, status.toString()), Toast.LENGTH_LONG).show()
                button.isEnabled = true
            }
        }, packageName)
    }

    override fun onDestroy() {
        tts?.shutdown()
        super.onDestroy()
    }
}
