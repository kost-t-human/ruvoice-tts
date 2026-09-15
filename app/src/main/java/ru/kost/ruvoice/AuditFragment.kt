package ru.kost.ruvoice

import android.text.Html
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import java.util.Locale

/** Вкладка «Проверка»: слова, собранные сервисом при чтении (Audit), с добавлением в словарь ударений. */
class AuditFragment : PageFragment(R.layout.fragment_audit) {
    private lateinit var recycler: RecyclerView
    private lateinit var emptyView: TextView
    private var kind = Audit.Kind.NAMES
    private var items: List<Audit.Entry> = emptyList()

    override fun load(v: View) {
        v.findViewById<MaterialSwitch>(R.id.namesOn).apply { isChecked = prefs.auditNames; setOnCheckedChangeListener { _, c -> prefs.auditNames = c } }
        v.findViewById<MaterialSwitch>(R.id.unsureOn).apply { isChecked = prefs.auditUnsure; setOnCheckedChangeListener { _, c -> prefs.auditUnsure = c } }
        val minValue = v.findViewById<TextView>(R.id.minValue)
        v.findViewById<Slider>(R.id.minSlider).apply {
            value = prefs.auditMin.coerceIn(valueFrom, valueTo)
            minValue.text = String.format(Locale.ROOT, "%.2f", value)
            addOnChangeListener { _, value, _ -> prefs.auditMin = value; minValue.text = String.format(Locale.ROOT, "%.2f", value) }
        }
        v.findViewById<TextView>(R.id.help).setOnClickListener {
            MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.audit_help_title)
                .setMessage(Html.fromHtml(getString(R.string.audit_help).replace("\n", "<br>"), Html.FROM_HTML_MODE_LEGACY))
                .setPositiveButton(android.R.string.ok, null).show()
        }
        val which = v.findViewById<MaterialButtonToggleGroup>(R.id.which)
        which.check(R.id.showNames)
        which.addOnButtonCheckedListener { _, id, checked ->
            if (checked) { kind = if (id == R.id.showNames) Audit.Kind.NAMES else Audit.Kind.UNSURE; refresh() }
        }
        v.findViewById<View>(R.id.clear).setOnClickListener {
            val name = getString(if (kind == Audit.Kind.NAMES) R.string.audit_tab_names else R.string.audit_tab_unsure)
            MaterialAlertDialogBuilder(requireContext()).setMessage(getString(R.string.audit_clear_confirm, name))
                .setPositiveButton(R.string.delete) { _, _ -> prefs.audit.clear(kind); refresh() }
                .setNegativeButton(R.string.cancel, null).show()
        }
        emptyView = v.findViewById(R.id.empty)
        recycler = v.findViewById(R.id.list)
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = Adapter()
        // список внутри ViewPager2: горизонтальный жест — наш (см. DictFragments.attachSwipeToDelete)
        recycler.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                if (e.actionMasked == MotionEvent.ACTION_DOWN) rv.parent.requestDisallowInterceptTouchEvent(true)
                return false
            }
        })
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder) = false
            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {
                items.getOrNull(vh.bindingAdapterPosition)?.let { prefs.audit.remove(kind, it.word) }
                refresh()
            }
        }).attachToRecyclerView(recycler)
        refresh()
    }

    override fun save(v: View) {}
    override fun onResume() { super.onResume(); if (view != null) refresh() }

    private fun refresh() {
        items = prefs.audit.entries(kind).sortedWith(compareByDescending<Audit.Entry> { it.count }.thenBy(Dicts.COLLATOR) { it.word })
        emptyView.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        recycler.adapter?.notifyDataSetChanged()
    }

    private inner class Adapter : RecyclerView.Adapter<Adapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val word: TextView = v.findViewById(R.id.word)
            val context: TextView = v.findViewById(R.id.context)
            val play: ImageButton = v.findViewById(R.id.play)
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(LayoutInflater.from(parent.context).inflate(R.layout.item_audit, parent, false))
        override fun getItemCount() = items.size
        override fun onBindViewHolder(holder: VH, position: Int) {
            val e = items[position]
            holder.word.text = if (e.count > 1) getString(R.string.audit_count, DictLines.accentDisplay(e.variant), e.count) else DictLines.accentDisplay(e.variant)
            holder.context.text = e.context
            holder.itemView.setOnClickListener { showDialog(e) }
            holder.play.setOnClickListener { btn -> (activity as SettingsActivity).preview(btn, e.variant) }
        }
    }

    /** Слово в словарь ударений: гласная чипом (предвыбрана та, что поставила модель), список — из запомненного. */
    private fun showDialog(e: Audit.Entry) {
        val ctx = requireContext()
        val view = LayoutInflater.from(ctx).inflate(R.layout.dialog_audit, null)
        view.findViewById<TextView>(R.id.context).text = e.context
        val chips = view.findViewById<ChipGroup>(R.id.chips)
        val modelPos = e.variant.indexOf('+')
        for (pos in DictLines.vowelPositions(e.word)) {
            val chip = LayoutInflater.from(ctx).inflate(R.layout.item_chip, chips, false) as Chip
            chip.id = View.generateViewId()
            chip.text = DictLines.accentDisplay(e.word.substring(0, pos) + "+" + e.word.substring(pos))
            chip.tag = pos
            chips.addView(chip)
            if (pos == modelPos) chip.isChecked = true
        }
        val names = prefs.dictFiles(Dicts.Kind.STRESS).map { Dicts.name(it) }.filter { it != Dicts.SYSTEM }
        val target = view.findViewById<MaterialAutoCompleteTextView>(R.id.target)
        target.setSimpleItems(names.toTypedArray())
        target.setText(prefs.auditDict.takeIf { it in names } ?: names.firstOrNull() ?: Dicts.MAIN, false)
        MaterialAlertDialogBuilder(ctx).setTitle(DictLines.accentDisplay(e.variant)).setView(view)
            .setPositiveButton(R.string.save) { _, _ ->
                val pos = chips.checkedChipId.takeIf { it != View.NO_ID }?.let { chips.findViewById<Chip>(it)?.tag as? Int } ?: return@setPositiveButton
                val name = target.text.toString().ifBlank { Dicts.MAIN }
                prefs.auditDict = name
                val f = Dicts.file(ctx.filesDir, Dicts.Kind.STRESS, name)
                f.parentFile!!.mkdirs()
                val line = DictLines.formatStress(e.word, pos)
                f.appendText((if (f.exists() && f.length() > 0 && !f.readText().endsWith("\n")) "\n" else "") + line + "\n")
                prefs.audit.remove(kind, e.word); refresh()
                Snackbar.make(requireView(), getString(R.string.audit_added, DictLines.accentDisplay(line.substringAfter(' ')), name), Snackbar.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null).show()
    }
}
