package ru.kost.ruvoice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import ru.kost.ruvoice.audio.Pcm
import ru.kost.ruvoice.text.Normalizer
import ru.kost.ruvoice.text.SentenceType
import ru.kost.ruvoice.text.Stress

class SettingsActivity : AppCompatActivity() {
    private lateinit var prefs: Prefs
    private val rates = listOf(48000, 24000)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        prefs = Prefs(this)
        val voices = listOf("aidar", "baya", "kseniya", "eugene", "xenia")
        val voice = findViewById<Spinner>(R.id.voice).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, voices)
            setSelection(voices.indexOf(prefs.voice).coerceAtLeast(0))
        }
        val sr = findViewById<Spinner>(R.id.sampleRate).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, rates.map { "$it Гц" })
            setSelection(rates.indexOf(prefs.sampleRate).coerceAtLeast(0))
        }
        val ps = findViewById<EditText>(R.id.pauseSentence).apply { setText(prefs.sentencePauseMs.toString()) }
        val pp = findViewById<EditText>(R.id.pauseParagraph).apply { setText(prefs.paragraphPauseMs.toString()) }
        val idle = findViewById<EditText>(R.id.idleMinutes).apply { setText(prefs.idleMinutes.toString()) }
        val dict = findViewById<EditText>(R.id.userDict).apply { setText(if (prefs.userDictFile.exists()) prefs.userDictFile.readText() else "") }

        fun save() {
            prefs.voice = voices[voice.selectedItemPosition]
            prefs.sampleRate = rates[sr.selectedItemPosition]
            prefs.sentencePauseMs = ps.text.toString().toIntOrNull() ?: 0
            prefs.paragraphPauseMs = pp.text.toString().toIntOrNull() ?: 300
            prefs.idleMinutes = (idle.text.toString().toIntOrNull() ?: 5).coerceAtLeast(1)
            prefs.userDictFile.writeText(dict.text.toString())
            Toast.makeText(this, R.string.save, Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.save).setOnClickListener { save() }
        findViewById<Button>(R.id.preview).setOnClickListener { save(); Thread { preview() }.start() }
    }

    private fun preview() {
        val models = SileroModels(this)
        val d = models.data
        val text = getString(R.string.preview_text)
        val prepared = Normalizer.prepare(text, d.allowed)
        val sr = prefs.sampleRate
        // Тот же монитор, что у SileroModels.release()/ensureLoaded() и у сервиса —
        // release() по простою не может destroy() модуль посреди этого forward.
        val audio = synchronized(models) {
            models.ensureLoaded()
            val seq = d.sequence(Stress(d, models, prefs.userDict()).apply(prepared))
            models.synthesize(seq, d.speakers.getValue(prefs.voice), sr, FloatArray(seq.size) { 1f }, FloatArray(seq.size) { 1f },
                SentenceType.typeIds(prepared, SentenceType.classify(text, d), seq.size, d))
        }
        Pcm.fadeEdges(audio, sr)
        val pcm = Pcm.toPcm16(audio)
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(sr).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(pcm.size * 2).setTransferMode(AudioTrack.MODE_STATIC).build()
        track.write(pcm, 0, pcm.size)
        track.play()
        Thread.sleep(pcm.size * 1000L / sr + 200)
        track.release()
        models.release()
    }
}
