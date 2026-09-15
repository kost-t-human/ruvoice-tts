package ru.kost.ruvoice

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import ru.kost.ruvoice.text.Stress

/**
 * Омографы на настоящих предложениях: HomographResolutionEval (Илья Козиев, CC BY 4.0), 1741 предложение,
 * у каждого отмечено верное ударение одного омографа. Трещотка: доля верных не ниже MIN; промахи — в logcat
 * и в cache/homo_eval_miss.txt (adb shell run-as ru.kost.ruvoice cat cache/homo_eval_miss.txt).
 */
@RunWith(AndroidJUnit4::class)
class HomographEvalTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val testCtx = InstrumentationRegistry.getInstrumentation().context
    private val wordRe = Regex("[а-яё+-]+", RegexOption.IGNORE_CASE)

    @Test fun homographAccuracy() {
        val m = SileroModels(ctx); m.ensureLoaded()
        val prefs = Prefs(ctx) // системный словарь и списки пользователя, как при чтении
        val repl = prefs.replacements()
        val stress = Stress(m.data, m, prefs.userDict())
        val items = JSONArray(testCtx.assets.open("HomographResolutionEval.json").bufferedReader().readText())
        var total = 0; var ok = 0; val miss = StringBuilder()
        val t0 = System.currentTimeMillis()
        for (i in 0 until items.length()) {
            val it = items.getJSONObject(i)
            val h = it.getString("homograph").lowercase(); val k = h.indexOf('́')
            val target = if (k > 0) (h.substring(0, k - 1) + "+" + h.substring(k - 1)).replace("́", "") else h
            val w = target.replace("+", "")
            val got = stress.apply(repl.apply(it.getString("context").lowercase()))
            val toks = wordRe.findAll(got).map { it.value }.toList()
            val hit = toks.firstOrNull { it.replace("+", "") == w } ?: continue
            total++
            if (hit == target) ok++ else miss.append("${it.getString("context")} | $target | $hit\n")
        }
        val ms = System.currentTimeMillis() - t0
        android.util.Log.i("RuVoiceTest", "омографы: $ok из $total (${ok * 100 / total}%), $ms мс")
        ctx.cacheDir.resolve("homo_eval_miss.txt").writeText(miss.toString())
        assertTrue("омографы: $ok из $total", ok >= (total * MIN).toInt())
    }

    companion object { const val MIN = 0.80 }
}
