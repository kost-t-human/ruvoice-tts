package ru.kost.ruvoice

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.color.DynamicColors
import com.google.android.material.snackbar.Snackbar

/** «О программе» из меню ⋮: группа в Telegram, кнопка поддержки, версия, исходники и лицензии. */
class AboutActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_about)
        val root = findViewById<View>(R.id.root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
        findViewById<TextView>(R.id.body).text = getString(R.string.about_body, packageManager.getPackageInfo(packageName, 0).versionName) +
            Packs.installed(filesDir).joinToString("") { getString(R.string.about_pack, it.title, it.source, it.license) }
        findViewById<View>(R.id.support).setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SUPPORT_URL)))
            } catch (e: ActivityNotFoundException) {
                Snackbar.make(root, R.string.browser_missing, Snackbar.LENGTH_LONG).show()
            }
        }
    }

    companion object {
        const val SUPPORT_URL = "https://pay.cloudtips.ru/p/ddc25c30"
    }
}
