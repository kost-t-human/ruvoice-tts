package ru.kost.ruvoice

import android.app.Application
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

/** Тема из диалога «Язык и тема»: AppCompat режим ночи не запоминает, ставим при старте процесса.
 * Отдельный файл prefs: не в профилях, не в экспорте и не в отпечатке кэша фраз (Prefs.stamp). */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        AppCompatDelegate.setDefaultNightMode(nightMode(this))
    }

    companion object {
        private fun ui(ctx: Context) = ctx.getSharedPreferences("ui", Context.MODE_PRIVATE)
        fun nightMode(ctx: Context) = ui(ctx).getInt("night", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        fun setNightMode(ctx: Context, mode: Int) {
            ui(ctx).edit().putInt("night", mode).apply()
            AppCompatDelegate.setDefaultNightMode(mode)
        }
    }
}
