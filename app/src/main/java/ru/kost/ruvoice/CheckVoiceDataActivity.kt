package ru.kost.ruvoice

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech

class CheckVoiceDataActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // lite без пака: голосов нет — читалка покажет «установить данные» и пришлёт INSTALL_TTS_DATA
        val has = Speaker.names(SileroModels.data(this), Packs.installed(filesDir)).isNotEmpty()
        val result = Intent().apply {
            putStringArrayListExtra(TextToSpeech.Engine.EXTRA_AVAILABLE_VOICES, arrayListOf<String>().apply { if (has) add("rus-RUS") })
            putStringArrayListExtra(TextToSpeech.Engine.EXTRA_UNAVAILABLE_VOICES, arrayListOf<String>().apply { if (!has) add("rus-RUS") })
        }
        setResult(if (has) TextToSpeech.Engine.CHECK_VOICE_DATA_PASS else TextToSpeech.Engine.CHECK_VOICE_DATA_MISSING_DATA, result)
        finish()
    }
}
