package ru.kost.ruvoice

import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.View
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
