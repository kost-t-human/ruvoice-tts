package ru.kost.ruvoice

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith
import ru.kost.ruvoice.text.Marks
import ru.kost.ruvoice.text.Normalizer
import ru.kost.ruvoice.text.Stress

/** Что уйдёт в модель для фразы: конвейер сервиса без синтеза, результат в logcat (RuVoiceTest). */
@RunWith(AndroidJUnit4::class)
class PhraseStressTest {
    private val text = "Старый замок на холме запер ржавый замок, и в нём давно никто не живёт. " +
        "Муки прошлого стоят дорого, а муки на кухне не хватает даже на пирог. " +
        "Большая половина гостей уже разошлась, и только Пётр Ильич, стоящий у окна, всё ещё ждёт ответа. " +
        "Ты придёшь завтра? Ну конечно, приду! " +
        "Впрочем, посмотрим: погода обещает дождь, а зонт остался в электричке."

    @Test fun logAccented() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val m = SileroModels(ctx); m.ensureLoaded()
        val prefs = Prefs(ctx); val d = m.data; val rules = prefs.rules()
        val stress = Stress(d, m, prefs.userDict(), rules)
        for (seg in Pipeline.plan(text, d, 0, 0, prefs.replacements(), rules)) {
            val marks = Marks.parse(if (rules.on("exclaim")) Marks.exclaim(seg.text) else seg.text, rules.focusLevel)
            val prepared = Normalizer.prepare(marks.text, d.allowed, rules)
            android.util.Log.i("RuVoiceTest", "ACC " + stress.apply(prepared))
        }
    }
}
