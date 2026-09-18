package ru.kost.ruvoice

import android.content.Context
import android.util.AttributeSet
import com.google.android.material.textfield.TextInputEditText

/** TextInputEditText с колбэком на перемещение курсора: чипы ударения в диалоге замен строятся
 * по слову под курсором, а из-за ручки выделения и стрелок клавиатуры клик это не ловит. */
class CursorEditText @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : TextInputEditText(ctx, attrs) {
    var onCursorMoved: (() -> Unit)? = null

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        onCursorMoved?.invoke()
    }
}
