package ru.kost.ruvoice

import android.content.Context

/** Ошибка для пользователя из классов без Context (Pack, SettingsJson): [message] — по-русски для журнала
 * и тестов, на экран идёт строка [res] на языке интерфейса (см. [errorText]). */
class UserError(message: String, val res: Int, vararg val args: Any, cause: Throwable? = null) : IllegalArgumentException(message, cause)

/** Текст ошибки для Snackbar: переведённый у [UserError], у прочих — их сообщение. */
fun Context.errorText(e: Throwable): String = (e as? UserError)?.let { getString(it.res, *it.args) } ?: e.message ?: e.toString()
