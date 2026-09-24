package ru.kost.ruvoice

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.DynamicColors
import com.google.android.material.snackbar.Snackbar

/** «Решение проблем» из меню ⋮: список типичных проблем не на нашей стороне и что с ними делать. */
class TroubleshootActivity : AppCompatActivity() {
    /** Проблема: название, описание и кнопка под описанием (null — без кнопки). */
    private class Item(val title: Int, val text: Int, val button: Int? = null, val action: ((View) -> Unit)? = null)

    private val items = listOf(
        Item(R.string.ts_fade_title, R.string.ts_fade_text, R.string.troubleshoot_sound) { openSysSoundSettings(it) },
        Item(R.string.ts_stutter_title, R.string.ts_stutter_text),
        Item(R.string.ts_delay_title, R.string.ts_delay_text),
        Item(R.string.ts_crash_title, R.string.ts_crash_text, R.string.ts_crash_copy) { copyExitReport(it) },
    )

    /** Телефон, версия и последние завершения нашего процесса по данным системы (Android 11+): причина, описание
     * (для нативного падения — сигнал и abort message), для Java-падения — трейс. В буфер обмена, чтобы прислать. */
    private fun copyExitReport(anchor: View) {
        val sb = StringBuilder("RuVoice ${packageManager.getPackageInfo(packageName, 0).versionName}, ")
            .append("${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (${Build.VERSION.SDK_INT}), ")
            .append(Build.SUPPORTED_ABIS.joinToString(","))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val exits = getSystemService(ActivityManager::class.java).getHistoricalProcessExitReasons(packageName, 0, 5)
            if (exits.isEmpty()) sb.append("\n(нет записей о завершениях)")
            for (e in exits) {
                sb.append("\n\n").append(java.text.DateFormat.getDateTimeInstance().format(java.util.Date(e.timestamp)))
                    .append(": reason=${e.reason} status=${e.status} pss=${e.pss / 1024} МБ ${e.description ?: ""}")
                if (e.reason == ApplicationExitInfo.REASON_CRASH)
                    runCatching { e.traceInputStream?.bufferedReader()?.use { it.readText() } }.getOrNull()?.let { sb.append("\n").append(it.take(4000)) }
            }
        } else sb.append("\n(записи о завершениях есть только с Android 11)")
        val journal = SileroTtsService.journal()
        sb.append("\n\n--- журнал запросов (${journal.size}) ---")
        if (journal.isEmpty()) sb.append("\n(пусто: движок в этом процессе ещё не читал)")
        for (l in journal) sb.append("\n").append(l)
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("RuVoice", sb))
        Snackbar.make(anchor, R.string.ts_crash_copied, Snackbar.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_troubleshoot)
        val root = findViewById<View>(R.id.root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        val list = findViewById<LinearLayout>(R.id.list)
        val inflater = LayoutInflater.from(this)
        for (item in items) {
            val row = inflater.inflate(R.layout.item_trouble, list, false)
            row.findViewById<TextView>(R.id.title).setText(item.title)
            row.markHeadings()
            row.findViewById<TextView>(R.id.text).setText(item.text)
            if (item.button != null) row.findViewById<MaterialButton>(R.id.action).apply {
                visibility = View.VISIBLE
                setText(item.button)
                setOnClickListener { item.action?.invoke(root) }
            }
            list.addView(row)
        }
    }
}
