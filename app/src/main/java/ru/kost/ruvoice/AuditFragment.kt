package ru.kost.ruvoice

import android.net.Uri
import com.google.android.material.button.MaterialButton
import com.google.android.material.divider.MaterialDivider
import android.view.Gravity
import android.content.Context
import android.content.Intent
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.view.WindowManager
import androidx.core.text.HtmlCompat
import android.view.LayoutInflater
import android.view.MotionEvent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.RadioGroup
import android.widget.RadioButton
import android.widget.LinearLayout
import androidx.core.widget.NestedScrollView
import androidx.core.widget.TextViewCompat
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
import com.google.android.material.color.MaterialColors
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
    // «Книга с ударениями»: режим знака и «э» (Prefs), затем исходник, затем куда сохранить. Пока открыт выбор
    // «куда сохранить», Android может уничтожить экран или весь процесс: исходник переживает это через
    // savedInstanceState и постоянное разрешение на чтение, иначе окно закрывалось и ничего не происходило
    private var accentIn: Uri? = null
    private val accentOutLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/x-fictionbook+xml")) { out ->
        val src = accentIn
        when {
            out == null -> src?.let { releaseRead(it) }
            src == null -> {
                runCatching { DocumentsContract.deleteDocument(requireContext().contentResolver, out) }
                view?.let { Snackbar.make(it, R.string.accent_book_lost, Snackbar.LENGTH_LONG).show() }
            }
            else -> accentBook(src, out)
        }
    }
    private val accentInLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            accentIn = uri
            runCatching { requireContext().contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            accentOutLauncher.launch(displayName(uri).replace(bookExt, "") + " (ударения).fb2")
        }
    }
    private fun releaseRead(uri: Uri) = runCatching { requireContext().contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedInstanceState?.getString(KEY_ACCENT_IN)?.let { accentIn = Uri.parse(it) }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        accentIn?.let { outState.putString(KEY_ACCENT_IN, it.toString()) }
    }
    private companion object {
        const val SCAN_BATCH = 64
        const val KEY_ACCENT_IN = "accent_in"
        val bookExt = Regex("\\.(fb2\\.zip|fb2|epub|txt|zip)$", RegexOption.IGNORE_CASE)
    }

    override fun load(v: View) {
        v.findViewById<MaterialSwitch>(R.id.namesOn).apply { isChecked = prefs.auditNames; setOnCheckedChangeListener { _, c -> prefs.auditNames = c } }
        v.findViewById<TextView>(R.id.help).setOnClickListener {
            MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.audit_help_title)
                .setMessage(HtmlCompat.fromHtml(getString(R.string.audit_help).replace("\n", "<br>"), HtmlCompat.FROM_HTML_MODE_LEGACY))
                .setPositiveButton(android.R.string.ok, null).show()
        }
        v.findViewById<CheckBox>(R.id.showHidden).setOnCheckedChangeListener { _, c -> hidden = c; refresh() }
        v.findViewById<View>(R.id.clear).setOnClickListener {
            val name = getString(R.string.audit_tab_names) + if (hidden) getString(R.string.audit_hidden_suffix) else ""
            MaterialAlertDialogBuilder(requireContext()).setMessage(getString(R.string.audit_clear_confirm, name))
                .setPositiveButton(R.string.delete) { _, _ -> prefs.audit.clear(kind, hidden); refresh() }
                .setNegativeButton(R.string.cancel, null).show()
        }
        val sortAlpha = v.findViewById<ImageButton>(R.id.sortAlpha)
        fun tintSort() {
            sortAlpha.setColorFilter(MaterialColors.getColor(sortAlpha, if (prefs.auditSortAlpha) com.google.android.material.R.attr.colorPrimary else com.google.android.material.R.attr.colorControlNormal))
            sortAlpha.say(getString(if (prefs.auditSortAlpha) R.string.state_on else R.string.state_off)) // цвет TalkBack не видит
        }
        tintSort()
        sortAlpha.setOnClickListener { prefs.auditSortAlpha = !prefs.auditSortAlpha; tintSort(); refresh() }
        v.findViewById<View>(R.id.scan).setOnClickListener {
            bodyDialog(R.string.audit_scan, getString(R.string.audit_scan_help), emptyList(),
                R.string.cancel to {}, R.string.audit_scan_pick to { scanLauncher.launch(arrayOf("*/*")) },
                extra = Triple(R.string.accent_book_caption, R.string.accent_book_go) { accentDialog() })
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
                        found[w] = Pair(1, line)   // цитату вырежет Audit.add
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

    private fun accentDialog() {
        val ctx = requireContext()
        val acute = RadioButton(ctx).apply { id = View.generateViewId(); setText(R.string.accent_book_acute) }
        val plus = RadioButton(ctx).apply { id = View.generateViewId(); setText(R.string.accent_book_plus) }
        val group = RadioGroup(ctx).apply { addView(acute); addView(plus); check(if (prefs.accentBookPlus) plus.id else acute.id) }
        val hardE = CheckBox(ctx).apply { setText(R.string.accent_book_hard_e); isChecked = prefs.accentBookHardE }
        val abbr = CheckBox(ctx).apply { setText(R.string.accent_book_abbr); isChecked = prefs.accentBookAbbr }
        bodyDialog(R.string.accent_book, getString(R.string.accent_book_help), listOf(group, hardE, abbr), R.string.cancel to {}, R.string.audit_scan_pick to {
            prefs.accentBookPlus = group.checkedRadioButtonId == plus.id
            prefs.accentBookHardE = hardE.isChecked
            prefs.accentBookAbbr = abbr.isChecked
            accentInLauncher.launch(arrayOf("*/*"))
        })
    }

    /**
     * Диалог, где кнопки, поля и текст справки — одна прокручиваемая область. Штатные кнопки при крупном шрифте
     * уезжали за экран или прокручивались отдельной полосой; здесь они сверху, [extra] (подпись, кнопка) — под чертой.
     */
    private fun bodyDialog(title: Int, message: String, views: List<View>, vararg buttons: Pair<Int, () -> Unit>, extra: Triple<Int, Int, () -> Unit>? = null) {
        val ctx = requireContext()
        val pad = (24 * resources.displayMetrics.density).toInt()
        lateinit var dialog: androidx.appcompat.app.AlertDialog
        val text = TextView(ctx).apply {
            TextViewCompat.setTextAppearance(this, com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
            setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant))
            this.text = message
        }
        // последняя кнопка — основное действие, с заливкой; остальные с рамкой
        val row = ButtonRow(ctx).apply {
            gravity = Gravity.END
            for ((i, b) in buttons.withIndex()) {
                val style = if (i == buttons.lastIndex) com.google.android.material.R.attr.materialButtonStyle else com.google.android.material.R.attr.materialButtonOutlinedStyle
                addView(MaterialButton(ctx, null, style).apply { setText(b.first); setOnClickListener { dialog.dismiss(); b.second() } },
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = pad / 3 })
            }
        }
        val wide = { LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT) }
        // сверху кнопки и отдельное действие, под ними поля, потом черта и справка: действия видны сразу
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; setPadding(pad, 0, pad / 2, pad / 2)
            addView(row, wide())
            // отдельное действие — под чертой, с подписью, чтобы не путалось с кнопками выше
            extra?.let { (caption, label, action) ->
                addView(MaterialDivider(ctx), wide().apply { setMargins(0, pad / 2, pad / 2, pad / 3) })
                addView(TextView(ctx).apply {
                    TextViewCompat.setTextAppearance(this, com.google.android.material.R.style.TextAppearance_Material3_TitleSmall)
                    setText(caption)
                })
                addView(MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    setText(label); setOnClickListener { dialog.dismiss(); action() }
                }, wide().apply { marginEnd = pad / 2 })
            }
            views.forEach { addView(it) }
            addView(MaterialDivider(ctx), wide().apply { setMargins(0, pad / 2, pad / 2, pad / 2) })
            addView(text)
        }
        // что ниже есть ещё: полоса прокрутки не гаснет, край текста внизу затухает
        // стиль scrollViewStyle — чтобы полоса прокрутки была инициализирована: у вида из кода без него
        // isScrollbarFadingEnabled = false падает NPE в ScrollBarDrawable при первой отрисовке
        val scroll = NestedScrollView(ctx, null, android.R.attr.scrollViewStyle).apply {
            isVerticalScrollBarEnabled = true; isScrollbarFadingEnabled = false
            isVerticalFadingEdgeEnabled = true; setFadingEdgeLength(pad * 2)
            addView(box)
        }
        dialog = MaterialAlertDialogBuilder(ctx).setTitle(title).setView(scroll).show()
    }

    /** Кнопки в строку, а если не влезают — столбиком, как у кнопок диалога. */
    private class ButtonRow(ctx: Context) : LinearLayout(ctx) {
        override fun onMeasure(widthSpec: Int, heightSpec: Int) {
            orientation = HORIZONTAL
            super.onMeasure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), heightSpec)
            orientation = if (measuredWidth <= MeasureSpec.getSize(widthSpec)) HORIZONTAL else VERTICAL
            super.onMeasure(widthSpec, heightSpec)
        }
    }

    private fun displayName(uri: Uri): String = runCatching {
        requireContext().contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull() ?: "книга"

    /** Книга целиком через конвейер сервиса (BookAccent), по абзацам; прервали или упало — недописанный файл удаляется. */
    private fun accentBook(src: Uri, out: Uri) {
        val ctx = requireContext().applicationContext
        scanCancelled = false
        val dialog = MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.accent_book).setMessage(R.string.accent_book_loading)
            .setNegativeButton(R.string.cancel) { _, _ -> scanCancelled = true }.setCancelable(false).show()
        // на час работы экран не гасим: в фоне процесс могут прибить
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Thread {
            var ok = false
            val result = try {
                val bytes = ctx.contentResolver.openInputStream(src)?.use { it.readBytes() } ?: throw IllegalStateException("Не удалось открыть файл")
                // абзацы идут быстрее процента: счётчик обновляем и по времени, чтобы было видно, что работа идёт
                var shownAt = 0L; var shownPct = -1
                val done = BookAccent.make(ctx, bytes, displayName(src).replace(bookExt, "")) { i, n ->
                    val pct = if (n == 0) 100 else i * 100 / n
                    val now = System.currentTimeMillis()
                    if (pct != shownPct || now - shownAt >= 400) {
                        shownPct = pct; shownAt = now
                        activity?.runOnUiThread { dialog.setMessage(ctx.getString(R.string.accent_book_progress, pct, i, n)) }
                    }
                    !scanCancelled
                }
                if (done == null) ctx.getString(R.string.accent_book_cancelled) else {
                    ctx.contentResolver.openOutputStream(out, "wt")?.use { it.write(done.toByteArray()) } ?: throw IllegalStateException("Не удалось записать файл")
                    ok = true
                    ctx.getString(R.string.accent_book_done)
                }
            } catch (e: Exception) { e.message ?: e.toString() }
            if (!ok) runCatching { DocumentsContract.deleteDocument(ctx.contentResolver, out) }
            runCatching { ctx.contentResolver.releasePersistableUriPermission(src, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            activity?.runOnUiThread {
                activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                dialog.dismiss()
                if (view != null) Snackbar.make(requireView(), result, Snackbar.LENGTH_LONG).show()
            }
        }.start()
    }

    private fun refresh() {
        val query = filterField.text?.toString()?.trim().orEmpty()
        items = prefs.audit.entries(kind, hidden).filter { query.isEmpty() || it.word.contains(query, ignoreCase = true) }
            .sortedWith(if (prefs.auditSortAlpha) compareBy(Dicts.COLLATOR) { it.word } else compareByDescending<Audit.Entry> { it.count }.thenBy(Dicts.COLLATOR) { it.word })
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
            val shownWord = DictLines.accentDisplay(e.variant)
            holder.word.text = if (e.count > 1) getString(R.string.audit_count, shownWord, resources.getQuantityString(R.plurals.audit_times, e.count, e.count)) else shownWord
            holder.context.text = e.context
            holder.itemView.setOnClickListener { showDialog(e) }
            holder.play.setOnClickListener { btn -> (activity as SettingsActivity).preview(btn, e.variant) }
            holder.play.contentDescription = getString(R.string.preview_word, shownWord)
            // TalkBack: свайп «скрыть/вернуть» — действием в меню
            holder.itemView.clearActions()
            holder.itemView.clickLabel(getString(R.string.audit_to_dict))
            holder.itemView.action(getString(if (hidden) R.string.audit_unhide else R.string.audit_hide)) {
                prefs.audit.hide(kind, e.word, !hidden); refresh()
            }
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
            chip.showStress(e.word, pos)
            chip.tag = pos
            chips.addView(chip)
            if (pos == modelPos) chip.isChecked = true
        }
        val valueLayout = view.findViewById<TextInputLayout>(R.id.valueLayout)
        val valueField = view.findViewById<TextInputEditText>(R.id.value)
        valueField.setText(e.word)
        valueLayout.setEndIconOnClickListener { btn -> valueField.text.toString().takeIf { it.isNotBlank() }?.let { (activity as SettingsActivity).preview(btn, it) } }
        fun selectedPos() = chips.checkedChipId.takeIf { it != View.NO_ID }?.let { chips.findViewById<Chip>(it)?.tag as? Int }
        // без словаря, как в StressFragment: иначе Stress.userDictPass подменит выбранное ударение
        val nodict = Bundle().apply { putString("ruvoice.nodict", "1") }
        val listenRow = view.findViewById<View>(R.id.listenRow)
        view.findViewById<Button>(R.id.listenModel).setOnClickListener { btn -> (activity as SettingsActivity).preview(btn, e.variant, nodict) }
        view.findViewById<Button>(R.id.listenStressed).setOnClickListener { btn ->
            selectedPos()?.let { pos -> (activity as SettingsActivity).preview(btn, e.word.substring(0, pos) + "+" + e.word.substring(pos), nodict) }
        }
        val target = view.findViewById<MaterialAutoCompleteTextView>(R.id.target)
        val mode = view.findViewById<MaterialButtonToggleGroup>(R.id.mode)
        fun replaceMode() = mode.checkedButtonId == R.id.modeReplace
        fun lists() = prefs.dictFiles(if (replaceMode()) Dicts.Kind.REPLACE else Dicts.Kind.STRESS).map { Dicts.name(it) }.filter { it != Dicts.SYSTEM }
        fun switchMode() {
            val replace = replaceMode()
            chips.visibility = if (replace) View.GONE else View.VISIBLE
            listenRow.visibility = chips.visibility
            view.findViewById<View>(R.id.chipsLabel).visibility = chips.visibility
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
                    val pos = selectedPos() ?: return@setPositiveButton
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
