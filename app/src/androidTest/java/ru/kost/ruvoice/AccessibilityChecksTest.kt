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
import androidx.test.espresso.Espresso.onData
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.hasDescendant
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isClickable
import org.hamcrest.Matchers.anything
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
        // страница «Английский»: тумблеры, пороги, рамки выбора движка и голоса
        ActivityScenario.launch<RulesActivity>(android.content.Intent(InstrumentationRegistry.getInstrumentation().targetContext, RulesActivity::class.java)
            .putExtra(RulesActivity.EXTRA_ENGLISH, true)).use { assertKeyboardReachable() }
    }

    /** Путь незрячего на странице «Английский»: строка «Голос для английского» — кнопка с понятным действием,
     * в окне пункт выбирается, «Прослушать» играет и окно не закрывает, «Выбрать» сохраняет. Проверки ATF — на
     * каждом нажатии, в том числе в окне. Настройки пользователя возвращаются как были. */
    @Test fun englishVoicePick() {
        val prefs = Prefs(ctx)
        val engine = EnglishProxy.suggest(ctx, "")
        org.junit.Assume.assumeTrue("нет другого движка", engine != null)
        val saved = Triple(prefs.rulesOff, prefs.enEngine, prefs.enVoice)
        try {
            prefs.enEngine = engine!!.pkg; prefs.enVoice = ""
            prefs.rulesOff = ru.kost.ruvoice.text.Rules(prefs.rulesOff).with("en_proxy_books", true).off
            ActivityScenario.launch<RulesActivity>(android.content.Intent(ctx, RulesActivity::class.java).putExtra(RulesActivity.EXTRA_ENGLISH, true)).use {
                // для TalkBack: кнопка, действие «Выбрать голос», в тексте — название и текущее значение
                onView(allOf(isAssignableFrom(android.widget.LinearLayout::class.java), isClickable(), hasDescendant(withText(R.string.en_voice)))).check { v, _ ->
                    val info = v.createAccessibilityNodeInfo()
                    assertEquals(android.widget.Button::class.java.name, info.className)
                    assertTrue("нет фокуса", v.isFocusable)
                    val click = info.actionList.firstOrNull { it.id == android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK }
                    assertEquals(ctx.getString(R.string.en_voice_pick), click?.label?.toString())
                }.perform(androidx.test.espresso.action.ViewActions.scrollTo(), click())
                waitFor(withText(R.string.en_voice_choose), 15_000) // голоса грузятся в фоне
                onData(anything()).inRoot(isDialog()).atPosition(1).perform(click())
                onView(withText(R.string.preview)).inRoot(isDialog()).perform(click())
                onView(withText(R.string.en_voice_choose)).inRoot(isDialog()).check(matches(isDisplayed())) // «Прослушать» не закрыл окно
                onView(withText(R.string.en_voice_choose)).inRoot(isDialog()).perform(click())
                assertTrue("голос не сохранён", prefs.enVoice.isNotEmpty())
                assertKeyboardReachable()
            }
        } finally {
            prefs.rulesOff = saved.first; prefs.enEngine = saved.second; prefs.enVoice = saved.third
        }
    }

    private fun waitFor(m: org.hamcrest.Matcher<View>, ms: Long) {
        val end = System.currentTimeMillis() + ms
        while (true) {
            val ok = runCatching { onView(m).inRoot(isDialog()).check(matches(isDisplayed())) }.isSuccess
            if (ok) return
            if (System.currentTimeMillis() > end) fail("не дождался окна выбора голоса")
            Thread.sleep(200)
        }
    }

    /** Окно «Решение проблем» и «О программе». */
    @Test fun helperScreens() {
        ActivityScenario.launch(TroubleshootActivity::class.java).use { onView(withId(R.id.toolbar)).perform(click()) }
        ActivityScenario.launch(AboutActivity::class.java).use { onView(withId(R.id.toolbar)).perform(click()) }
    }
}
