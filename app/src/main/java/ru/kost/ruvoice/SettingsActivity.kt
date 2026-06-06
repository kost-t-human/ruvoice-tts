package ru.kost.ruvoice

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.color.DynamicColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator

/**
 * Экран настроек: тулбар с меню «О программе», вкладки и страницы-фрагменты
 * (см. SettingsPages.kt). Кнопки «Сохранить» нет — каждая страница пишет свои поля
 * в Prefs в onPause, то есть при уходе с вкладки и при сворачивании приложения.
 */
class SettingsActivity : AppCompatActivity() {
    private val pages = listOf(
        R.string.tab_voice to { VoiceFragment() },
        R.string.tab_pauses to { PausesFragment() },
        R.string.tab_stress to { EditorFragment.stress() },
        R.string.tab_replace to { EditorFragment.replace() },
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_settings)

        // Edge-to-edge: контент отступает от системных баров и от клавиатуры, фон под
        // статус-баром — цвет окна, тулбар Material3 того же цвета, шва не видно.
        val root = findViewById<android.view.View>(R.id.root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            insets
        }

        findViewById<MaterialToolbar>(R.id.toolbar).setOnMenuItemClickListener { item ->
            if (item.itemId != R.id.about) return@setOnMenuItemClickListener false
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.about_title)
                .setMessage(R.string.about)
                .setPositiveButton(R.string.close, null)
                .show()
            true
        }

        val pager = findViewById<ViewPager2>(R.id.pager)
        pager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount() = pages.size
            override fun createFragment(position: Int): Fragment = pages[position].second()
        }
        TabLayoutMediator(findViewById<TabLayout>(R.id.tabs), pager) { tab, i -> tab.setText(pages[i].first) }.attach()
    }
}
