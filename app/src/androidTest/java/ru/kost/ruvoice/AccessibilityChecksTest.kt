package ru.kost.ruvoice

import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.accessibility.AccessibilityChecks
import androidx.test.espresso.action.ViewActions.clearText
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.matcher.ViewMatchers.isDescendantOfA
import androidx.test.espresso.matcher.ViewMatchers.isRoot
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.hamcrest.Matchers.allOf
import org.junit.Assert.fail
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Проверки доступности (Accessibility Test Framework) на каждом действии Espresso: подписи у значков,
 * области нажатия от 48 dp, контраст текста, дубли кликабельных областей — по всему окну, не только
 * по нажатому. Ошибка валит тест с именем вьюхи и правилом. Прогон: adb install -r оба APK и
 * adb shell am instrument (connectedAndroidTest после прогона удаляет пакет вместе с данными),
 * сборка full (у lite без пака сверху открыт диалог паков).
 */
@RunWith(AndroidJUnit4::class)
class AccessibilityChecksTest {
    companion object {
        @BeforeClass @JvmStatic fun enableChecks() {
            // второй enable() в том же процессе (после LargeTextTest) бросает исключение
            runCatching { AccessibilityChecks.enable().setRunChecksFromRootView(true) }
        }
    }

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before fun skipFirstRunHelp() {
        Prefs(ctx).setupShown = true // иначе окно «Как включить» закрывает экран
    }

    /** Всё, что нажимается, берёт фокус: иначе физическая клавиатура и Switch Access до него не дойдут
     * (ATF этого не проверяет — TalkBack кликабельное находит и без фокуса). */
    private fun assertKeyboardReachable() = onView(isRoot()).check { root, _ ->
        val bad = ArrayList<String>()
        fun walk(v: View) {
            if (!v.isShown) return
            if (v.isClickable && v.isEnabled && !v.isFocusable)
                bad += runCatching { v.resources.getResourceEntryName(v.id) }.getOrDefault(v.javaClass.simpleName)
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        if (bad.isNotEmpty()) fail("Нажимается, но не берёт фокус: " + bad.joinToString())
    }

    /** Все вкладки настроек по очереди — проверки идут на каждом нажатии. */
    @Test fun settingsTabs() {
        ActivityScenario.launch(SettingsActivity::class.java).use {
            assertKeyboardReachable()
            for (tab in listOf(R.string.tab_pauses, R.string.tab_stress, R.string.tab_replace, R.string.tab_audit, R.string.tab_voice)) {
                onView(allOf(withText(ctx.getString(tab)), isDescendantOfA(withId(R.id.tabs)))).perform(click())
                assertKeyboardReachable()
            }
        }
    }

    /** Экран правил: список переключателей, «Для TalkBack» с ползунками, поиск. */
    @Test fun rulesScreen() {
        ActivityScenario.launch(RulesActivity::class.java).use {
            onView(withId(R.id.rulesFilter)).perform(replaceText("телефон"), closeSoftKeyboard())
            onView(withId(R.id.rulesFilter)).perform(clearText(), closeSoftKeyboard())
            assertKeyboardReachable()
        }
    }

    /** Окно «Решение проблем» и «О программе». */
    @Test fun helperScreens() {
        ActivityScenario.launch(TroubleshootActivity::class.java).use { onView(withId(R.id.toolbar)).perform(click()) }
        ActivityScenario.launch(AboutActivity::class.java).use { onView(withId(R.id.toolbar)).perform(click()) }
    }
}
