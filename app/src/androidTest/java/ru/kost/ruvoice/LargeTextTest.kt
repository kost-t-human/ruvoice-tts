package ru.kost.ruvoice

import android.os.ParcelFileDescriptor
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.accessibility.AccessibilityChecks
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.isDescendantOfA
import androidx.test.espresso.matcher.ViewMatchers.isRoot
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.hamcrest.Matchers.allOf
import org.junit.AfterClass
import org.junit.Assert.fail
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Шрифт 200 % (предел Android 14) и тёмная тема: проверки доступности (контраст, зоны нажатия) и
 * своя — текст не обрезан по высоте (надпись не влезла в кнопку или строку фиксированной высоты).
 * Строки с заданным maxLines обрезаны намеренно (контекст в «Проверке»), их не трогаем.
 * Настройки меняются через shell и возвращаются после прогона. Прогон — как AccessibilityChecksTest.
 */
@RunWith(AndroidJUnit4::class)
class LargeTextTest {
    companion object {
        private val ui get() = InstrumentationRegistry.getInstrumentation().uiAutomation
        private fun sh(cmd: String): String =
            ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand(cmd)).bufferedReader().use { it.readText().trim() }

        private var oldScale = "null"
        private var oldNight = ""

        @BeforeClass @JvmStatic fun bigTextDark() {
            // второй enable() в том же процессе (после AccessibilityChecksTest) бросает исключение
            runCatching { AccessibilityChecks.enable().setRunChecksFromRootView(true) }
            oldScale = sh("settings get system font_scale")
            oldNight = sh("cmd uimode night").substringAfter(": ").trim()
            sh("settings put system font_scale 2.0")
            sh("cmd uimode night yes")
        }

        @AfterClass @JvmStatic fun restore() {
            if (oldScale == "null" || oldScale.isEmpty()) sh("settings delete system font_scale") else sh("settings put system font_scale $oldScale")
            if (oldNight.isNotEmpty()) sh("cmd uimode night $oldNight")
        }
    }

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before fun skipFirstRunHelp() {
        Prefs(ctx).setupShown = true
    }

    /** Обрезанные по высоте надписи в окне — одной ошибкой со списком. */
    private fun assertNoClippedText() = onView(isRoot()).check { root, _ ->
        val bad = ArrayList<String>()
        fun walk(v: View) {
            if (!v.isShown) return
            if (v is TextView && v !is EditText && v.maxLines == Int.MAX_VALUE) {
                val layout = v.layout
                if (layout != null && v.text.isNotEmpty()) {
                    val avail = v.height - v.compoundPaddingTop - v.compoundPaddingBottom
                    val need = layout.getLineTop(v.lineCount)
                    if (need > avail + 1) {
                        val id = runCatching { v.resources.getResourceEntryName(v.id) }.getOrDefault("?")
                        bad += "$id «${v.text.take(40)}»: нужно $need px, есть $avail"
                    }
                }
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        if (bad.isNotEmpty()) fail("Текст обрезан при шрифте 200 %:\n" + bad.joinToString("\n"))
    }

    @Test fun settingsTabs() {
        ActivityScenario.launch(SettingsActivity::class.java).use {
            assertNoClippedText()
            for (tab in listOf(R.string.tab_pauses, R.string.tab_stress, R.string.tab_replace, R.string.tab_audit, R.string.tab_voice)) {
                onView(allOf(withText(ctx.getString(tab)), isDescendantOfA(withId(R.id.tabs)))).perform(click())
                assertNoClippedText()
            }
        }
    }

    @Test fun rulesScreen() {
        ActivityScenario.launch(RulesActivity::class.java).use { assertNoClippedText() }
    }

    @Test fun helperScreens() {
        ActivityScenario.launch(TroubleshootActivity::class.java).use { assertNoClippedText() }
        ActivityScenario.launch(AboutActivity::class.java).use { assertNoClippedText() }
    }
}
