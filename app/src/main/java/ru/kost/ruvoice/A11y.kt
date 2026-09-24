package ru.kost.ruvoice

import android.view.View
import android.widget.CompoundButton
import android.widget.Switch
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat

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
