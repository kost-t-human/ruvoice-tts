package ru.kost.ruvoice

import android.view.KeyEvent
import android.view.View
import android.widget.AutoCompleteTextView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.pressKey
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.hasDescendant
import androidx.test.espresso.matcher.ViewMatchers.isChecked
import androidx.test.espresso.matcher.ViewMatchers.isClickable
import androidx.test.espresso.matcher.ViewMatchers.isDescendantOfA
import androidx.test.espresso.matcher.ViewMatchers.isFocusable
import androidx.test.espresso.matcher.ViewMatchers.isNotChecked
import androidx.test.espresso.matcher.ViewMatchers.isRoot
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.slider.Slider
import ru.kost.ruvoice.text.Rules
import org.hamcrest.Matcher
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.any
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Физическая клавиатура (и Switch Access, который шлёт те же Enter и стрелки): раздел экранного чтеца
 * в правилах — Enter переключает строку, стрелки двигают ползунки; список голосов открывается Enter.
 * Всё, что тест меняет, он же возвращает (правила сохраняются при уходе с экрана).
 */
@RunWith(AndroidJUnit4::class)
class KeyboardTest {
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before fun skipFirstRunHelp() {
        Prefs(ctx).setupShown = true
    }

    /** Фокус на вьюху, как после Tab: клавиши дальше идут в неё. */
    private fun focus(): ViewAction = object : ViewAction {
        override fun getConstraints(): Matcher<View> = any(View::class.java)
        override fun getDescription() = "requestFocus"
        override fun perform(uc: UiController, v: View) { assertTrue("не берёт фокус", v.requestFocus()); uc.loopMainThreadUntilIdle() }
    }

    private fun key(code: Int) = onView(isRoot()).perform(pressKey(code))

    @Test fun screenReaderSection() {
        ActivityScenario.launch(RulesActivity::class.java).use {
            val row = onView(allOf(isFocusable(), isClickable(), hasDescendant(withText(R.string.rule_sr_symbols))))
            val wasOn = Rules(Prefs(ctx).rulesOff).on("sr_symbols")
            val on = if (wasOn) isChecked() else isNotChecked()
            val off = if (wasOn) isNotChecked() else isChecked()
            row.perform(focus())
            key(KeyEvent.KEYCODE_ENTER)
            row.check(matches(hasDescendant(allOf(withId(R.id.toggle), off))))
            key(KeyEvent.KEYCODE_ENTER)
            row.check(matches(hasDescendant(allOf(withId(R.id.toggle), on))))

            for (id in listOf(R.id.srRate, R.id.srPitch, R.id.srVolume)) {
                var before = 0f
                onView(withId(id)).perform(focus()).check { v, _ -> before = (v as Slider).value }
                val (there, back) = if (before < 2f) KeyEvent.KEYCODE_DPAD_RIGHT to KeyEvent.KEYCODE_DPAD_LEFT
                    else KeyEvent.KEYCODE_DPAD_LEFT to KeyEvent.KEYCODE_DPAD_RIGHT
                key(there)
                onView(withId(id)).check { v, _ -> assertTrue("ползунок не сдвинулся", (v as Slider).value != before) }
                key(back)
                onView(withId(id)).check { v, _ -> assertEquals(before, (v as Slider).value, 0.001f) }
            }
        }
    }

    @Test fun voiceDropdown() {
        ActivityScenario.launch(SettingsActivity::class.java).use {
            onView(allOf(withText(ctx.getString(R.string.tab_voice)), isDescendantOfA(withId(R.id.tabs)))).perform(click())
            onView(withId(R.id.voice)).perform(focus())
            key(KeyEvent.KEYCODE_ENTER)
            onView(withId(R.id.voice)).check { v, _ -> assertTrue("список не открылся", (v as AutoCompleteTextView).isPopupShowing) }
            key(KeyEvent.KEYCODE_ESCAPE)
        }
    }
}
