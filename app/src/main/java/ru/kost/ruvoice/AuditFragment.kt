package ru.kost.ruvoice

import android.net.Uri
import android.text.Html
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.textfield.TextInputEditText
import ru.kost.ruvoice.text.Rules
import ru.kost.ruvoice.text.Stress
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputLayout

/** Вкладка «Проверка»: слова, собранные сервисом при чтении (Audit), с добавлением в словарь ударений или в замены. */
class AuditFragment : PageFragment(R.layout.fragment_audit) {
    private lateinit var recycler: RecyclerView
    private lateinit var emptyView: TextView
    private val kind = Audit.Kind.NAMES
    private var hidden = false
    private lateinit var filterField: TextInputEditText
    private var items: List<Audit.Entry> = emptyList()
    private val scanLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) scan(uri) }
    @Volatile private var scanCancelled = false
    private companion object { const val SCAN_BATCH = 64 }

    override fun load(v: View) {
        v.findViewById<MaterialSwitch>(R.id.namesOn).apply { isChecked = prefs.auditNames; setOnCheckedChangeListener { _, c -> prefs.auditNames = c } }
        v.findViewById<TextView>(R.id.help).setOnClickListener {
            MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.audit_help_title)
                .setMessage(Html.fromHtml(getString(R.string.audit_help).replace("\n", "<br>"), Html.FROM_HTML_MODE_LEGACY))
                .setPositiveButton(android.R.string.ok, null).show()
        }
        v.findViewById<CheckBox>(R.id.showHidden).setOnCheckedChangeListener { _, c -> hidden = c; refresh() }
        v.findViewById<View>(R.id.clear).setOnClickListener {
            val name = getString(R.string.audit_tab_names) + if (hidden) getString(R.string.audit_hidden_suffix) else ""
            MaterialAlertDialogBuilder(requireContext()).setMessage(getString(R.string.audit_clear_confirm, name))
                .setPositiveButton(R.string.delete) { _, _ -> prefs.audit.clear(kind, hidden); refresh() }
                .setNegativeButton(R.string.cancel, null).show()
        }
        v.findViewById<View>(R.id.scan).setOnClickListener {
            MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.audit_scan).setMessage(R.string.audit_scan_help)
                .setPositiveButton(R.string.audit_scan_pick) { _, _ -> scanLauncher.launch(arrayOf("*/*")) }
                .setNegativeButton(R.string.cancel, null).show()
        }
        filterField = v.findViewById(R.id.filter)
        filterField.doAfterTextChanged { refresh() }
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
                // смахнутое прячем, а не удаляем — чтобы не всплывало при следующем чтении; из скрытых свайп возвращает
                items.getOrNull(vh.bindingAdapterPosition)?.let { prefs.audit.hide(kind, it.word, !hidden) }
                refresh()
            }
        }).attachToRecyclerView(recycler)
        refresh()
    }

    override fun save(v: View) {}
    override fun onResume() { super.onResume(); if (view != null) refresh() }

    /** Имена из книги без чтения вслух. Акцентор нужен только самим именам, поэтому не конвейер сервиса по всему
     * тексту, а один проход по кандидатам (Audit.candidates) и акцентор батчами по уникальным словам: слова через
     * пробел — одно «предложение» для Stress.apply, как в сервисе, но без грамматики и BERT омографов.
     * Проценты: первая половина — проход по строкам, вторая — батчи акцентора. */
    private fun scan(uri: Uri) {
        val ctx = requireContext().applicationContext
        scanCancelled = false
        val dialog = MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.audit_scan).setMessage("0 %")
            .setNegativeButton(R.string.cancel) { _, _ -> scanCancelled = true }.setCancelable(false).show()
        Thread {
            val models = SileroModels(ctx)
            val audit = prefs.audit
            val before = audit.entries(kind).size
            val result = try {
                val text = Book.text(ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw IllegalStateException("Не удалось открыть файл"))
                val d = SileroModels.data(ctx)
                val userDict = prefs.userDict()
                val r = prefs.rules()
                val stress = Stress(d, models, userDict, Rules(r.off + setOf("gram", "homo"), r.maxLen, r.focus))
                val known = Audit.known(d, userDict)
                var shown = -1
                fun progress(pct: Int) { if (pct != shown) { shown = pct; activity?.runOnUiThread { dialog.setMessage("$pct %") } } }
                // слово → сколько раз и кусок текста вокруг первого вхождения (по границам слов)
                val found = LinkedHashMap<String, Pair<Int, String>>()
                val lines = text.lines()
                for ((i, line) in lines.withIndex()) {
                    if (scanCancelled) break
                    progress(i * 50 / lines.size)
                    for ((w, range) in Audit.candidates(line, known)) {
                        val e = found[w]
                        if (e != null) { found[w] = Pair(e.first + 1, e.second); continue }
                        val a = maxOf(0, range.first - 40); val b = minOf(line.length, range.last + 80)
                        val ctxText = line.substring(a, b).let { if (a > 0) it.substringAfter(' ') else it }.let { if (b < line.length) it.substringBeforeLast(' ') else it }
                        found[w] = Pair(1, ctxText)
                    }
                }
                val batches = found.keys.chunked(SCAN_BATCH)
                for ((i, batch) in batches.withIndex()) {
                    if (scanCancelled) break
                    progress(50 + i * 50 / batches.size)
                    for ((w, v) in batch.zip(stress.apply(batch.joinToString(" ")).split(" "))) {
                        val (count, context) = found.getValue(w)
                        if ('+' in v) audit.add(kind, w, v, context, count)
                    }
                }
                getString(R.string.audit_scan_done, audit.entries(kind).size - before)
            } catch (e: Exception) { e.message ?: e.toString() } finally { audit.flush(); models.release() }
            activity?.runOnUiThread {
                dialog.dismiss()
                if (view != null) { refresh(); Snackbar.make(requireView(), result, Snackbar.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun refresh() {
        val query = filterField.text?.toString()?.trim().orEmpty()
        items = prefs.audit.entries(kind, hidden).filter { query.isEmpty() || it.word.contains(query, ignoreCase = true) }
            .sortedWith(compareByDescending<Audit.Entry> { it.count }.thenBy(Dicts.COLLATOR) { it.word })
        emptyView.setText(if (hidden) R.string.audit_empty_hidden else R.string.audit_empty)
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

    /** Слово в словарь: «Ударение» — гласная чипом (предвыбрана та, что поставила модель), «Замена» — поле «На что»
     * с самим словом (дописать «ё» или «+»). Режим и списки назначения запоминаются; «Системный» не предлагается. */
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
        val valueLayout = view.findViewById<TextInputLayout>(R.id.valueLayout)
        val valueField = view.findViewById<TextInputEditText>(R.id.value)
        valueField.setText(e.word)
        valueLayout.setEndIconOnClickListener { btn -> valueField.text.toString().takeIf { it.isNotBlank() }?.let { (activity as SettingsActivity).preview(btn, it) } }
        val target = view.findViewById<MaterialAutoCompleteTextView>(R.id.target)
        val mode = view.findViewById<MaterialButtonToggleGroup>(R.id.mode)
        fun replaceMode() = mode.checkedButtonId == R.id.modeReplace
        fun lists() = prefs.dictFiles(if (replaceMode()) Dicts.Kind.REPLACE else Dicts.Kind.STRESS).map { Dicts.name(it) }.filter { it != Dicts.SYSTEM }
        fun switchMode() {
            val replace = replaceMode()
            chips.visibility = if (replace) View.GONE else View.VISIBLE
            valueLayout.visibility = if (replace) View.VISIBLE else View.GONE
            val names = lists()
            target.setSimpleItems(names.toTypedArray())
            target.setText((if (replace) prefs.auditReplaceDict else prefs.auditDict(kind)).takeIf { it in names } ?: names.firstOrNull() ?: Dicts.MAIN, false)
        }
        mode.check(if (prefs.auditReplace) R.id.modeReplace else R.id.modeStress)
        mode.addOnButtonCheckedListener { _, _, isChecked -> if (isChecked) switchMode() }
        switchMode()
        MaterialAlertDialogBuilder(ctx).setTitle(DictLines.accentDisplay(e.variant)).setView(view)
            .setPositiveButton(R.string.save) { _, _ ->
                val replace = replaceMode(); prefs.auditReplace = replace
                val name = target.text.toString().ifBlank { Dicts.MAIN }
                val line = if (replace) {
                    val value = valueField.text.toString().trim().filter { it != '=' }.ifBlank { return@setPositiveButton }
                    prefs.auditReplaceDict = name
                    DictLines.formatReplace(e.word, value, false)
                } else {
                    val pos = chips.checkedChipId.takeIf { it != View.NO_ID }?.let { chips.findViewById<Chip>(it)?.tag as? Int } ?: return@setPositiveButton
                    prefs.setAuditDict(kind, name)
                    DictLines.formatStress(e.word, pos)
                }
                val f = Dicts.file(ctx.filesDir, if (replace) Dicts.Kind.REPLACE else Dicts.Kind.STRESS, name)
                f.parentFile!!.mkdirs()
                f.appendText((if (f.exists() && f.length() > 0 && !f.readText().endsWith("\n")) "\n" else "") + line + "\n")
                prefs.audit.remove(kind, e.word); refresh()
                val shown = if (replace) line.substringAfter(" = ") else DictLines.accentDisplay(line.substringAfter(' '))
                Snackbar.make(requireView(), getString(R.string.audit_added, shown, name), Snackbar.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null).show()
    }
}
