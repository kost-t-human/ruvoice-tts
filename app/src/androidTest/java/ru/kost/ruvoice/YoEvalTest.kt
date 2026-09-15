package ru.kost.ruvoice

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import ru.kost.ruvoice.text.Stress

/**
 * Буква «ё» на живом тексте: 2000 предложений русской Википедии с «ё» (CC BY-SA 4.0, выборка
 * tools/wiki_yo_corpus.py). Из предложения «ё» стирается, конвейер (замены, gramPass, омографы,
 * акцентор, словари) ставит её заново. Трещотка по доле восстановленных «ё»; лишние «ё» считаются
 * отдельно (часть из них — пропуски самой Википедии: «все ещё», «идет»). Промахи — cache/yo_eval_miss.txt.
 */
@RunWith(AndroidJUnit4::class)
class YoEvalTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val testCtx = InstrumentationRegistry.getInstrumentation().context
    private val wordRe = Regex("[а-яё]+")
    private val gotRe = Regex("[а-яё+]+")

    @Test fun yoRestored() {
        val m = SileroModels(ctx); m.ensureLoaded()
        val prefs = Prefs(ctx); val repl = prefs.replacements()
        val stress = Stress(m.data, m, prefs.userDict())
        var withYo = 0; var restored = 0; var extra = 0; var sentences = 0; val miss = StringBuilder()
        val t0 = System.currentTimeMillis()
        testCtx.assets.open("wiki_yo.txt").bufferedReader().forEachLine { sent ->
            val orig = wordRe.findAll(sent.lowercase()).map { it.value }.toList()
            val deyo = sent.replace('ё', 'е').replace('Ё', 'Е')
            val got = gotRe.findAll(stress.apply(repl.apply(deyo)).lowercase()).map { it.value.replace("+", "") }.toList()
            if (got.size != orig.size) return@forEachLine
            sentences++
            for (i in orig.indices) {
                val o = orig[i]; val g = got[i]
                if ('ё' in o) { withYo++; if (g == o) restored++ else miss.append("потеряна | $o | $g | $sent\n") }
                else if ('ё' in g) { extra++; miss.append("лишняя | $o | $g | $sent\n") }
            }
        }
        val ms = System.currentTimeMillis() - t0
        android.util.Log.i("RuVoiceTest", "ё: предложений $sentences, слов с ё $withYo, восстановлено $restored (${restored * 100 / withYo}%), лишних $extra, $ms мс")
        ctx.cacheDir.resolve("yo_eval_miss.txt").writeText(miss.toString())
        assertTrue("ё восстановлена в $restored из $withYo", restored >= (withYo * MIN).toInt())
    }

    companion object { const val MIN = 0.92 }
}
