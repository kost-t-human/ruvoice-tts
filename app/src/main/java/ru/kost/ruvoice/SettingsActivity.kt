package ru.kost.ruvoice

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.method.LinkMovementMethod
import android.text.util.Linkify
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.color.DynamicColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import java.util.Locale

/**
 * Экран настроек: тулбар с меню (экспорт/импорт настроек, «О программе»), вкладки и
 * страницы-фрагменты (см. SettingsPages.kt). Кнопки «Сохранить» нет — каждая страница
 * пишет свои поля в Prefs в onPause, то есть при уходе с вкладки и при сворачивании
 * приложения.
 */
class SettingsActivity : AppCompatActivity() {
    private val pages = listOf(
        R.string.tab_voice to { VoiceFragment() },
        R.string.tab_pauses to { PausesFragment() },
        R.string.tab_stress to { StressFragment() },
        R.string.tab_replace to { ReplaceFragment() },
        R.string.tab_rules to { RulesFragment() },
    )
    private val prefs by lazy { Prefs(this) }
    private var tts: TextToSpeech? = null

    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri != null) exportTo(uri)
        }
    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) importFrom(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_settings)

        // Edge-to-edge: контент отступает от системных баров и от клавиатуры, фон под
        // статус-баром — цвет окна, тулбар Material3 того же цвета, шва не видно.
        val root = findViewById<View>(R.id.root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            insets
        }

        // После импорта окно создаётся заново (finish + startActivity), поэтому Snackbar
        // «Настройки импортированы» показываем здесь, а не в месте вызова.
        if (intent.getBooleanExtra(EXTRA_IMPORT_DONE, false)) {
            intent.removeExtra(EXTRA_IMPORT_DONE)
            root.post { showSnackbar(getString(R.string.import_done)) }
        }

        if (!prefs.setupShown) { prefs.setupShown = true; root.post { showSetupHelp() } }

        findViewById<MaterialToolbar>(R.id.toolbar).setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.setup_help -> { showSetupHelp(); true }
                R.id.about -> {
                    val dialog = MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.about_title)
                        .setMessage(getString(R.string.about, packageManager.getPackageInfo(packageName, 0).versionName))
                        .setPositiveButton(R.string.close, null)
                        .show()
                    // ссылка на GitHub в тексте — кликабельная
                    dialog.findViewById<TextView>(android.R.id.message)?.movementMethod = LinkMovementMethod.getInstance()
                    dialog.findViewById<TextView>(android.R.id.message)?.let { Linkify.addLinks(it, Linkify.WEB_URLS) }
                    true
                }
                R.id.export_settings -> {
                    saveAllVisiblePages()
                    exportLauncher.launch("ruvoice-settings.json")
                    true
                }
                R.id.import_settings -> {
                    importLauncher.launch(arrayOf("application/json", "text/*", "*/*"))
                    true
                }
                else -> false
            }
        }

        val pager = findViewById<ViewPager2>(R.id.pager)
        pager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount() = pages.size
            override fun createFragment(position: Int): Fragment = pages[position].second()
        }
        TabLayoutMediator(findViewById<TabLayout>(R.id.tabs), pager) { tab, i -> tab.setText(pages[i].first) }.attach()
    }

    /** «Как включить»: путь к системному экрану синтеза речи и объяснение стандартного
     * предупреждения Android про сторонний движок. Показывается при первом запуске и из меню. */
    fun showSetupHelp() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.setup_title)
            .setMessage(R.string.setup_text)
            .setPositiveButton(R.string.setup_open) { _, _ -> openSysTtsSettings(findViewById(R.id.root)) }
            .setNegativeButton(R.string.setup_ok, null)
            .show()
    }

    /** Сохраняет поля всех сейчас созданных страниц (обычно это видимая и её соседи по
     * ViewPager2) — вызывается перед экспортом, чтобы в файл попали правки текущей вкладки,
     * которые иначе сохранились бы только в onPause при уходе со страницы. */
    private fun saveAllVisiblePages() {
        supportFragmentManager.fragments.forEach { (it as? PageFragment)?.saveNow() }
    }

    private fun exportTo(uri: Uri) {
        try {
            contentResolver.openOutputStream(uri)?.use { it.write(prefs.exportJson().toByteArray()) }
                ?: throw IllegalStateException("Не удалось открыть файл для записи")
            showSnackbar(getString(R.string.export_done))
        } catch (e: Exception) {
            showSnackbar(e.message ?: e.toString())
        }
    }

    private fun importFrom(uri: Uri) {
        val text = try {
            contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
                ?: throw IllegalStateException("Не удалось открыть файл")
        } catch (e: Exception) {
            showSnackbar(e.message ?: e.toString())
            return
        }
        MaterialAlertDialogBuilder(this)
            .setMessage(R.string.import_confirm)
            .setPositiveButton(R.string.import_confirm_yes) { _, _ ->
                try {
                    prefs.importJson(text)
                    // Не recreate(): он восстанавливает состояние вьюх поверх load(), и старые
                    // значения полей потом уезжают в Prefs при onPause (проверено на устройстве).
                    // Флаг на текущем intent — чтобы старые фрагменты не сохранялись при finish().
                    intent.putExtra(EXTRA_IMPORT_DONE, true)
                    finish()
                    startActivity(Intent(this, SettingsActivity::class.java).putExtra(EXTRA_IMPORT_DONE, true))
                    overridePendingTransition(0, 0)
                } catch (e: Exception) {
                    showSnackbar(e.message ?: e.toString())
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showSnackbar(text: String) =
        Snackbar.make(findViewById(R.id.root), text, Snackbar.LENGTH_LONG).show()

    // Прослушивание идёт через платформенный TextToSpeech, а не напрямую через SileroModels:
    // так проверяется тот же путь, которым звук получит читалка (наш сервис как движок).
    // Общий помощник для всех вкладок (голос, диалоги ударений и замен) — один TextToSpeech
    // на Activity вместо отдельного инстанса на фрагмент. tts?.shutdown() внутри preview()
    // обрывает предыдущий запрос без onDone/onError, поэтому его кнопку разблокируем сами —
    // иначе на экранах с несколькими ▶ вторая кнопка навсегда «съедала» разблокировку первой.
    private var busyButton: View? = null

    fun preview(button: View, text: String, params: Bundle? = null) {
        val ctx = applicationContext
        busyButton?.isEnabled = true
        busyButton = button
        button.isEnabled = false
        tts?.shutdown()
        tts = TextToSpeech(ctx, { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale("ru", "RU")
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) { button.post { release(button) } }
                    override fun onError(utteranceId: String?) { button.post { release(button) } }
                })
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "preview")
            } else button.post {
                Toast.makeText(ctx, getString(R.string.preview_failed, status.toString()), Toast.LENGTH_LONG).show()
                release(button)
            }
        }, ctx.packageName)
    }

    /** Разблокирует button, только если она всё ещё «занятая» — поздний callback от уже
     * остановленного (shutdown в preview()) движка не должен трогать кнопку следующего запроса. */
    private fun release(button: View) {
        if (busyButton === button) { button.isEnabled = true; busyButton = null }
    }

    override fun onDestroy() {
        busyButton?.isEnabled = true
        tts?.shutdown()
        super.onDestroy()
    }

    companion object {
        // internal: PageFragment.onPause читает его, чтобы не затирать только что
        // импортированные файлы устаревшими полями старых фрагментов при recreate().
        internal const val EXTRA_IMPORT_DONE = "import_done"
    }
}

/** Системный экран «Синтез речи»; на прошивке без него — Snackbar на [anchor]. */
fun Context.openSysTtsSettings(anchor: View) {
    try {
        startActivity(Intent("com.android.settings.TTS_SETTINGS"))
    } catch (e: ActivityNotFoundException) {
        Snackbar.make(anchor, R.string.sys_settings_missing, Snackbar.LENGTH_LONG).show()
    }
}
