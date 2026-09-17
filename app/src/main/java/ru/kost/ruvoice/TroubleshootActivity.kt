package ru.kost.ruvoice

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

/** «Решение проблем» из меню ⋮: список типичных проблем не на нашей стороне и что с ними делать. */
class TroubleshootActivity : AppCompatActivity() {
    /** Проблема: название, описание и кнопка под описанием (null — без кнопки). */
    private class Item(val title: Int, val text: Int, val button: Int? = null, val action: ((View) -> Unit)? = null)

    private val items = listOf(
        Item(R.string.ts_fade_title, R.string.ts_fade_text, R.string.troubleshoot_sound) { openSysSoundSettings(it) },
        Item(R.string.ts_stutter_title, R.string.ts_stutter_text),
        Item(R.string.ts_delay_title, R.string.ts_delay_text),
    )

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
