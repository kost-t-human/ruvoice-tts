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
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
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
import ru.kost.ruvoice.text.Marks
import ru.kost.ruvoice.text.Normalizer
import ru.kost.ruvoice.text.SentenceType
import ru.kost.ruvoice.text.Stress

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
        R.string.tab_audit to { AuditFragment() },
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
    private val packLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) installPack(uri) }

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
        // После установки/удаления пака окно тоже создаётся заново (списки голосов — lazy в фрагментах).
        intent.getStringExtra(EXTRA_SNACK)?.let { msg -> intent.removeExtra(EXTRA_SNACK); root.post { showSnackbar(msg) } }
        if (intent.getBooleanExtra(EXTRA_SHOW_PACKS, false)) { intent.removeExtra(EXTRA_SHOW_PACKS); root.post { showPacks() } }
        // lite без пака или запрос читалки «установить данные» (INSTALL_TTS_DATA): сразу диалог паков
        if (intent.action == TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA ||
            Speaker.names(SileroModels.data(this), Packs.installed(filesDir)).isEmpty()) root.post { showPacks() }

        if (!prefs.setupShown) { prefs.setupShown = true; root.post { showSetupHelp() } }

        findViewById<MaterialToolbar>(R.id.toolbar).setOnMenuItemClickListener { item ->
            when (item.itemId) {
                // как перед экспортом: refreshPages глушит save() старых фрагментов, правки вкладки иначе пропадут
                R.id.voice_packs -> { saveAllVisiblePages(); showPacks(); true }
                R.id.setup_help -> { showSetupHelp(); true }
                R.id.troubleshoot -> { startActivity(Intent(this, TroubleshootActivity::class.java)); true }
                R.id.about -> { startActivity(Intent(this, AboutActivity::class.java)); true }
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
        // smoothScroll = false: при плавной прокрутке к вкладке через несколько страниц
        // LinearSmoothScroller шагает по времени, а создание страниц по пути (инфлейт + load)
        // блокирует кадры — один шаг перелетал все страницы, цель успевала уйти в recycle,
        // пейджер вставал на последней вкладке с индикатором на нужной. Свайп не затронут.
        TabLayoutMediator(findViewById<TabLayout>(R.id.tabs), pager, true, false) { tab, i -> tab.setText(pages[i].first) }.attach()
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

    // «Разбор» с «Голоса» и из окна «Проверить» на вкладках списков: по сегментам — текст после
    // замен и то, что уходит в модель после нормализации и всех ударений (→, ударения над
    // буквой), тем же путём, что в SileroTtsService.synthSegment. Словари и модели читаются с диска — считаем в
    // фоновом потоке, диалог показываем на UI-потоке.
    fun analyze(text: String) {
        val ctx = applicationContext
        Thread {
            val report = try {
                val d = SileroModels.data(ctx)
                val packs = Packs.installed(ctx.filesDir)
                // фильтр символов — того движка, что озвучит: у cis-пака нет «!» и апострофа
                val allowed = (Speaker.resolve(prefs.voice, d, packs) ?: Speaker.default(d, packs))?.sym?.allowed ?: d.sym.allowed
                val rules = prefs.rules()
                // те же акцентор и BERT, что у сервиса (SileroModels.shared); если сервис их выгрузил, Stress догрузит
                val models = SileroModels.shared(ctx)
                val stress = Stress(d, models, prefs.userDict(), rules)
                val segments = Pipeline.plan(text, d, prefs.sentencePauseMs, prefs.paragraphPauseMs, prefs.replacements(), rules)
                buildString {
                    for (seg in segments) {
                        var marks = ""
                        if (seg.speech) marks += " [речь]"
                        if (seg.paragraph) marks += " [¶]"
                        appendLine(seg.text + marks)
                        var t = if (rules.on("exclaim")) Marks.exclaim(seg.text) else seg.text
                        if (rules.on("question") && SentenceType.classify(t, d, rules) == "general_q") t = Marks.question(t)
                        val prepared = Normalizer.prepare(Marks.parse(t, rules.focusLevel).text, allowed, rules)
                        // монитор models — тот же, что у синтеза и выгрузки в сервисе: форварды не параллелим
                        val accented = synchronized(models) { stress.apply(prepared, t) }
                        appendLine("→ " + accented.split(' ').joinToString(" ") { DictLines.accentDisplay(it) })
                        if (seg.breakMs > 0) appendLine("пауза ${seg.breakMs} мс")
                        appendLine()
                    }
                }.trimEnd()
            } catch (e: Exception) {
                e.toString()
            }
            runOnUiThread {
                // экран могли закрыть, пока считали — окно без Activity уронит show()
                if (isFinishing || isDestroyed) return@runOnUiThread
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.analyze_title)
                    .setMessage(report)
                    .setPositiveButton(R.string.close, null)
                    .show()
            }
        }.start()
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

    private var packsDialog: AlertDialog? = null

    /** Диалог со списком установленных паков; пересобирается после установки и удаления. */
    private fun showPacks() {
        packsDialog?.dismiss()
        val v = layoutInflater.inflate(R.layout.dialog_packs, null)
        val list = v.findViewById<LinearLayout>(R.id.packsList)
        val packs = Packs.installed(filesDir)
        // подсказка всегда видна, ссылка на Releases кликабельная как в «О программе»
        v.findViewById<TextView>(R.id.packsHint).let { Linkify.addLinks(it, Linkify.WEB_URLS); it.movementMethod = LinkMovementMethod.getInstance() }
        v.findViewById<View>(R.id.packsNone).visibility = if (Speaker.names(SileroModels.data(this), packs).isEmpty()) View.VISIBLE else View.GONE
        for (p in packs) {
            val row = layoutInflater.inflate(R.layout.item_pack, list, false)
            row.findViewById<TextView>(R.id.title).text = p.title
            row.findViewById<TextView>(R.id.info).text = getString(R.string.pack_info, p.speakers.size, (p.size / 1048576).toInt(), p.license)
            row.findViewById<Button>(R.id.delete).setOnClickListener {
                MaterialAlertDialogBuilder(this).setMessage(getString(R.string.pack_delete_confirm, p.title))
                    .setPositiveButton(R.string.pack_delete) { _, _ ->
                        Packs.delete(filesDir, p.id)
                        // голос удалённого пака — на штатный; save фрагмента после этого его не вернёт (окно пересоздаётся).
                        // Пак ru в full (после lite) — те же голоса встроены: «ru/aidar» → «aidar», не на xenia.
                        val same = Speaker.builtin && p.id == Speaker.RU_PACK
                        if (prefs.voice.startsWith(p.id + "/")) prefs.voice = if (same) prefs.voice.substringAfter('/') else Speaker.DEFAULT
                        if (prefs.quoteVoice.startsWith(p.id + "/")) prefs.quoteVoice = if (same) prefs.quoteVoice.substringAfter('/') else ""
                        refreshPages(getString(R.string.pack_deleted))
                    }
                    .setNegativeButton(R.string.cancel, null).show()
            }
            list.addView(row)
        }
        v.findViewById<Button>(R.id.packsInstall).setOnClickListener { packLauncher.launch(arrayOf("application/zip", "*/*")) }
        packsDialog = MaterialAlertDialogBuilder(this).setTitle(R.string.packs_title).setView(v)
            .setPositiveButton(R.string.close, null).show()
    }

    /** Копирование ~90 МБ идёт в фоне под неотменяемым индикатором. */
    private fun installPack(uri: Uri) {
        val progress = MaterialAlertDialogBuilder(this).setMessage(R.string.packs_installing).setCancelable(false).show()
        Thread {
            val result = runCatching {
                contentResolver.openInputStream(uri)?.use { Packs.install(it, filesDir) } ?: throw IllegalStateException("Не удалось открыть файл")
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                progress.dismiss()
                result.onSuccess { refreshPages(getString(R.string.pack_installed, it.title)) }
                    .onFailure { showSnackbar(it.message ?: it.toString()) }
            }
        }.start()
    }

    /** Списки голосов в фрагментах — lazy от установленных паков: пересоздаём окно, как после импорта
     * настроек; новое окно показывает Snackbar и снова открывает диалог паков. */
    private fun refreshPages(message: String) {
        // Фрагменты не сохраняются в onPause после finish(): их save() вернул бы голос удалённого пака.
        intent.putExtra(EXTRA_IMPORT_DONE, true)
        packsDialog?.dismiss()
        finish()
        startActivity(Intent(this, SettingsActivity::class.java).putExtra(EXTRA_SNACK, message).putExtra(EXTRA_SHOW_PACKS, true))
        overridePendingTransition(0, 0)
    }

    companion object {
        // internal: PageFragment.onPause читает его, чтобы не затирать только что
        // импортированные файлы устаревшими полями старых фрагментов при recreate().
        internal const val EXTRA_IMPORT_DONE = "import_done"
        internal const val EXTRA_SNACK = "snack"
        internal const val EXTRA_SHOW_PACKS = "show_packs"
    }
}

/** Системный экран «Звук» (эффекты вроде Dolby Atmos живут там); без него — Snackbar на [anchor]. */
fun Context.openSysSoundSettings(anchor: View) {
    try {
        startActivity(Intent(android.provider.Settings.ACTION_SOUND_SETTINGS))
    } catch (e: ActivityNotFoundException) {
        Snackbar.make(anchor, R.string.sound_settings_missing, Snackbar.LENGTH_LONG).show()
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
