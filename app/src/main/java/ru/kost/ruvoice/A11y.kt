package ru.kost.ruvoice

import android.app.Activity
import android.graphics.Typeface
import android.os.Build
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import android.widget.CompoundButton
import android.widget.Switch
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat
import com.google.android.material.chip.Chip
import com.google.android.material.color.MaterialColors
import com.google.android.material.snackbar.Snackbar

// Помощники для TalkBack. Каждый — про то, как экран слышит незрячий: один фокус на строку,
// состояние словами, действия вместо жестов, которые TalkBack забирает себе.

/** Строка с тумблером — один элемент: название, подпись и «включено/выключено», двойной тап
 * переключает. Сам тумблер фокуса не берёт — иначе на каждое правило два шага, и у второго нет имени. */
fun View.asSwitchRow(toggle: CompoundButton) {
    toggle.isFocusable = false
    toggle.isClickable = false // касание уходит строке, она и переключает
    toggle.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    isFocusable = true
    setOnClickListener { toggle.toggle() }
    ViewCompat.setAccessibilityDelegate(this, object : AccessibilityDelegateCompat() {
        override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
            super.onInitializeAccessibilityNodeInfo(host, info)
            info.className = Switch::class.java.name
            info.isCheckable = true
            info.isChecked = toggle.isChecked
        }
    })
}

/** Заголовок секции — для навигации TalkBack «по заголовкам» (на API 28+ хватает стиля Section). */
fun View.asHeading() = ViewCompat.setAccessibilityHeading(this, true)

/** Заголовки из разметки (стиль Section, метка «heading») — для Android 6–8: атрибут accessibilityHeading
 * там не работает, ViewCompat отмечает заголовок через совместимый путь. На 9+ хватает стиля. */
fun View.markHeadings() {
    if (Build.VERSION.SDK_INT >= 28) return
    if (tag == "heading") asHeading()
    if (this is ViewGroup) for (i in 0 until childCount) getChildAt(i).markHeadings()
}

/** Состояние, которое видно только глазами (цвет значка, треугольник ▾), — словами. */
fun View.say(state: CharSequence?) = ViewCompat.setStateDescription(this, state)

/** Действие в меню действий TalkBack — замена свайпа и мелких кнопок в строке списка. */
fun View.action(label: CharSequence, run: () -> Unit) {
    val id = ViewCompat.addAccessibilityAction(this, label) { _, _ -> run(); true }
    @Suppress("UNCHECKED_CAST")
    val ids = getTag(R.id.a11y_actions) as? MutableList<Int> ?: mutableListOf<Int>().also { setTag(R.id.a11y_actions, it) }
    ids += id
}

/** Снять действия, добавленные [action], — строки списков переиспользуются. */
fun View.clearActions() {
    @Suppress("UNCHECKED_CAST")
    (getTag(R.id.a11y_actions) as? MutableList<Int>)?.let { ids -> for (id in ids) ViewCompat.removeAccessibilityAction(this, id); ids.clear() }
}

/** Подпись к двойному тапу: «Дважды нажмите, чтобы изменить» вместо безликого «активировать». */
fun View.clickLabel(label: CharSequence) =
    ViewCompat.replaceAccessibilityAction(this, AccessibilityActionCompat.ACTION_CLICK, label, null)

/** Чип выбора ударной гласной. Видно: ударная буква заглавной, жирной, цветом акцента и со знаком
 * ударения — «молокО́», не только крошечный значок, который на трёх «о» подряд не различить.
 * Слышно: «молоко́ — 3-й слог из 3, «о»» — номер слога различает чипы любым голосом TalkBack,
 * а слово со знаком ударения RuVoice ещё и произнесёт как выбрано. */
fun Chip.showStress(word: String, pos: Int) {
    val (n, total, vowel) = DictLines.syllable(word, pos)
    val shown = word.substring(0, pos) + word[pos].uppercaseChar() + '\u0301' + word.substring(pos + 1)
    text = SpannableString(shown).apply {
        setSpan(StyleSpan(Typeface.BOLD), pos, pos + 2, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        setSpan(ForegroundColorSpan(MaterialColors.getColor(this@showStress, com.google.android.material.R.attr.colorPrimary)), pos, pos + 2, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    contentDescription = context.getString(R.string.stress_chip, DictLines.accentDisplay(word.substring(0, pos) + "+" + word.substring(pos)), n, total, vowel.toString())
}

/** Снекбар с кнопкой («Отменить») при экранном чтеце — не меньше 30 с: до кнопки надо дойти жестами,
 * а прочитать сообщение TalkBack успевает раньше. Без чтеца срок прежний; на Android 10+ Material
 * ещё и берёт большее из этого и системного «Времени на выполнение действия». */
fun Snackbar.patient(): Snackbar = apply {
    if (duration != Snackbar.LENGTH_INDEFINITE && ScreenReaders.anyActive(context)) duration = maxOf(duration, PATIENT_MS)
}

private const val PATIENT_MS = 30_000

/** Enter в поле (Done на экранной клавиатуре, Enter на физической) — как нажатие [button], если она
 * активна: с клавиатурой и Switch Access не надо идти фокусом до «Сохранить». Поле — однострочное. */
fun EditText.submits(button: () -> Button?) {
    isSingleLine = true
    imeOptions = EditorInfo.IME_ACTION_DONE
    setOnEditorActionListener { _, id, ev ->
        val enter = ev?.keyCode == KeyEvent.KEYCODE_ENTER
        if (id != EditorInfo.IME_ACTION_DONE && !enter) return@setOnEditorActionListener false
        // физический Enter приходит дважды (нажатие и отпускание) — срабатываем на нажатие
        if (ev == null || ev.action == KeyEvent.ACTION_DOWN) button()?.takeIf { it.isEnabled }?.performClick()
        true
    }
}

/** Ctrl+F на физической клавиатуре — в видимое поле поиска (правила, списки словарей), как в браузере
 * и почте. Для Activity.onKeyShortcut; false — не наше сочетание или поиска на экране нет. */
fun Activity.ctrlF(keyCode: Int, event: KeyEvent): Boolean {
    if (keyCode != KeyEvent.KEYCODE_F || !event.isCtrlPressed) return false
    fun find(v: View): EditText? {
        if (!v.isShown) return null
        if (v is EditText && v.id in SEARCH_IDS) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) { val f = find(v.getChildAt(i)); if (f != null) return f }
        return null
    }
    val field = find(window.decorView) ?: return false
    field.requestFocus()
    field.selectAll()
    return true
}

private val SEARCH_IDS = setOf(R.id.rulesFilter, R.id.filter)

/** Выпадающий список (поле без ввода) с физической клавиатуры: Enter, пробел или Alt+↓ открывают, как
 * у системного списка; в открытом стрелки и Enter — уже свои у AutoCompleteTextView. Просто ↓ не
 * перехватываем — это переход к следующему полю. Касанием и TalkBack список открывался и раньше. */
fun AutoCompleteTextView.opensFromKeyboard() {
    setOnKeyListener { _, code, ev ->
        if (ev.action != KeyEvent.ACTION_DOWN || isPopupShowing) return@setOnKeyListener false
        val open = code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER ||
            code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_SPACE ||
            (code == KeyEvent.KEYCODE_DPAD_DOWN && ev.isAltPressed)
        if (open) showDropDown()
        open
    }
}

/** Сообщение окна с процентами («37 %») — живая область: чтец сам скажет, как идёт работа, фокус остаётся
 * на «Отмене». При чтеце текст меняется только шагами по 10 %: каждые полсекунды TalkBack перебивал бы
 * себя и не договаривал ни одного числа. Без чтеца — как раньше, на каждое изменение. Окно — после show(). */
class LiveProgress(private val dialog: AlertDialog) {
    private val reader = ScreenReaders.anyActive(dialog.context)
    private var step = -1

    init {
        dialog.findViewById<TextView>(android.R.id.message)?.let { ViewCompat.setAccessibilityLiveRegion(it, ViewCompat.ACCESSIBILITY_LIVE_REGION_POLITE) }
    }

    fun show(pct: Int, text: CharSequence) {
        if (reader) { val s = pct / 10; if (s == step) return; step = s }
        dialog.setMessage(text)
    }
}
