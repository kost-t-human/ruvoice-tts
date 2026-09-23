package ru.kost.ruvoice

import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import androidx.core.text.HtmlCompat
import androidx.core.text.buildSpannedString
import androidx.core.text.color
import android.text.InputFilter
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.io.File
import ru.kost.ruvoice.text.Replacements

/**
 * Общая часть вкладок «Ударения» и «Замены». У каждой вкладки несколько именных списков
 * (Dicts): сверху выбор текущего и меню «⋮» (включить, переименовать, удалить, новый,
 * импорт, экспорт). Файл текущего списка хранится как список сырых строк (lines) —
 * комментарии и пустые строки сохраняются как есть и не показываются. Разбор строк (parsedLines)
 * и порядок сортировки (order) считаются один раз при изменении lines: списки бывают на
 * десятки тысяч строк, разбирать и сортировать их на каждый символ фильтра нельзя. Видимый
 * список (shown — индексы в lines) пересчитывается при любом изменении lines/фильтра, без
 * DiffUtil. Поиск по умолчанию идёт и по другим спискам вида (область — меню в поле поиска):
 * их строки (extra) идут после своих с подписью имени списка, тап открывает тот список и
 * редактор строки. Свайп удаляет строку с Undo, FAB открывает диалог добавления. После каждой
 * правки и переключения списков кэш словарей (DictCache) греется в фоне; индикатор виден,
 * только если прогрев длится дольше 150 мс.
 */
abstract class DictListFragment(layout: Int) : PageFragment(layout) {
    protected abstract val kind: Dicts.Kind
    protected abstract val emptyHintRes: Int
    /** Текст справки вкладки — открывается попапом по ссылке над поиском; заголовок — имя вкладки. */
    protected abstract val helpRes: Int
    protected abstract val helpTitleRes: Int
    /** true — видимый список сортируется по sortKey (ударения, это словарь); false — порядок
     * файла важен и сохраняется как есть (замены: длинные ключи должны идти раньше). */
    protected open val sorted: Boolean = false
    protected open fun sortKey(parsed: Any): String = ""

    /** Файл текущего списка. */
    protected val file: File get() = prefs.current(kind)
    /** Системный список: смотреть и выключать можно, править и удалять — нет. */
    protected val readOnly: Boolean get() = Dicts.name(file) == Dicts.SYSTEM
    private val lines = mutableListOf<String>()
    private val parsedLines = ArrayList<Any?>()
    /** Индексы записей (не комментариев) в порядке показа до фильтра; null — пересчитать. */
    private var order: List<Int>? = null
    /** Индексы строк в lines, которые сейчас показаны (после разбора, сортировки и фильтра). */
    protected var shown = listOf<Int>(); private set
    /** Найденные поиском строки других списков (имя списка, разбор) — в адаптере после shown. */
    private var extra = listOf<Pair<String, Any>>()
    /** Разобранные строки других списков по имени, в порядке показа; читаются при первом поиске
     * по ним и сбрасываются в loadLines. Системный — 100 тыс. строк, ~полсекунды один раз,
     * столько же стоит открыть его самого. */
    private val others = HashMap<String, List<Any>>()

    protected val lineCount get() = lines.size
    protected fun line(index: Int) = lines[index]
    /** Разобранная строка (результат parseLine), null — комментарий/пустая/битая. */
    protected fun parsedAny(index: Int): Any? = parsedLines[index]
    protected abstract fun parseLine(line: String): Any?
    protected abstract fun matches(parsed: Any, query: String): Boolean
    protected abstract fun createAdapter(): RecyclerView.Adapter<*>
    /** Диалог строки: null — добавить новую. */
    protected abstract fun showDialog(editIndex: Int?)

    protected val rowCount get() = shown.size + extra.size
    /** Позиция адаптера → (имя списка, null для открытого; разбор строки). */
    protected fun item(position: Int): Pair<String?, Any> =
        if (position < shown.size) null to parsedAny(shown[position])!! else extra[position - shown.size]
    /** Тап по строке другого списка: открыть тот список (поиск остаётся) и редактор этой строки. */
    protected fun openIn(name: String, parsed: Any) {
        prefs.setCurrent(kind, name); loadLines()
        if (!readOnly) (0 until lineCount).firstOrNull { parsedAny(it) == parsed }?.let { showDialog(it) }
    }
    /** Подпись строки из другого списка: «слово · Системный», имя приглушённым. */
    protected fun labeled(text: CharSequence, list: String?, view: TextView): CharSequence = if (list == null) text else buildSpannedString {
        append(text); color(MaterialColors.getColor(view, com.google.android.material.R.attr.colorOnSurfaceVariant)) { append(" · $list") }
    }

    private lateinit var recycler: RecyclerView
    private lateinit var filterField: TextInputEditText
    private lateinit var emptyView: TextView
    private lateinit var nameField: MaterialAutoCompleteTextView
    private lateinit var onSwitch: MaterialSwitch
    private lateinit var addButton: FloatingActionButton

    // Экспорт: байты готовятся до выбора файла, после записи — необязательное продолжение
    // (для Демагога — предложить сохранить пропущенные regex-строки).
    private var pendingExport: ByteArray? = null
    private var afterExport: (() -> Unit)? = null
    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val bytes = pendingExport ?: return@registerForActivityResult
        pendingExport = null
        if (uri == null) return@registerForActivityResult
        try {
            requireContext().contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: throw IllegalStateException("Не удалось открыть файл для записи")
            snack(getString(R.string.dict_exported))
            afterExport?.invoke(); afterExport = null
        } catch (e: Exception) { snack(e.message ?: e.toString()) }
    }
    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importFrom(uri)
    }

    override fun load(v: View) {
        recycler = v.findViewById(R.id.list)
        emptyView = v.findViewById<TextView>(R.id.empty).apply { setText(emptyHintRes) }
        v.findViewById<TextView>(R.id.help).setOnClickListener {
            MaterialAlertDialogBuilder(requireContext()).setTitle(helpTitleRes).setMessage(helpRes)
                .setPositiveButton(android.R.string.ok, null).show()
        }
        v.findViewById<View>(R.id.check).setOnClickListener { showCheckDialog() }
        filterField = v.findViewById(R.id.filter)
        // область поиска: меню по кнопке в конце поля, выбранная — в подписи поля («Поиск · все списки»),
        // при не «этот список» кнопка цветом акцента
        val filterLayout = v.findViewById<TextInputLayout>(R.id.filterLayout)
        val scopeTitles = resources.getStringArray(R.array.search_scopes)
        fun showScope() {
            val scope = prefs.searchScope(kind)
            filterLayout.hint = getString(R.string.filter_scope_hint, scopeTitles[scope.ordinal])
            filterLayout.setEndIconTintList(ColorStateList.valueOf(MaterialColors.getColor(filterLayout,
                if (scope == Dicts.Scope.CURRENT) com.google.android.material.R.attr.colorControlNormal else com.google.android.material.R.attr.colorPrimary)))
        }
        showScope()
        filterLayout.setEndIconOnClickListener { anchor ->
            val popup = PopupMenu(requireContext(), anchor)
            for (s in Dicts.Scope.values()) popup.menu.add(0, s.ordinal, s.ordinal, scopeTitles[s.ordinal].replaceFirstChar { it.uppercase() }).isChecked = s == prefs.searchScope(kind)
            popup.menu.setGroupCheckable(0, true, true)
            popup.setOnMenuItemClickListener { prefs.setSearchScope(kind, Dicts.Scope.values()[it.itemId]); showScope(); refresh(); true }
            popup.show()
        }
        nameField = v.findViewById(R.id.dictName)
        nameField.setOnItemClickListener { _, _, pos, _ ->
            prefs.setCurrent(kind, Dicts.name(prefs.dictFiles(kind)[pos]))
            loadLines()
        }
        v.findViewById<View>(R.id.dictMenu).setOnClickListener { showMenu(it) }
        onSwitch = v.findViewById(R.id.dictOn)
        onSwitch.setOnCheckedChangeListener { _, checked ->
            val name = Dicts.name(file)
            val off = prefs.off(kind)
            if ((name in off) == !checked) return@setOnCheckedChangeListener // это loadLines выставил
            prefs.setOff(kind, if (checked) off - name else off + name)
            loadLines(); warm()
        }
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = createAdapter()
        filterField.doAfterTextChanged { refresh() }
        addButton = v.findViewById<FloatingActionButton>(R.id.add).apply { setOnClickListener { showDialog(null) } }
        attachSwipeToDelete()
        // Файл могли поменять извне (импорт настроек + recreate, Task 25) — читаем заново,
        // не кэшируем между пересозданиями.
        loadLines()
        warm()
    }

    override fun save(v: View) = persist()

    /** «Проверка» дописывает слова прямо в файл списка; устаревшие lines затёрли бы их первым же
     * persist (переименование, onPause). Правки вкладки пишутся сразу, так что перечитать безопасно. */
    override fun onResume() { super.onResume(); if (view != null && diskStamp() != stamp) loadLines() }
    private var stamp = ""
    private fun diskStamp() = file.let { "${it.path}|${it.lastModified()}|${it.length()}" }

    private fun loadLines() {
        val f = file
        lines.clear(); parsedLines.clear(); others.clear()
        if (f.exists()) lines.addAll(f.readLines())
        for (l in lines) parsedLines += parseLine(l)
        order = null
        val off = prefs.off(kind)
        val names = prefs.dictFiles(kind).map { Dicts.name(it) }
        nameField.setSimpleItems(names.map { if (it in off) getString(R.string.dict_off_suffix, it) else it }.toTypedArray())
        val name = Dicts.name(f)
        nameField.setText(if (name in off) getString(R.string.dict_off_suffix, name) else name, false)
        onSwitch.isChecked = name !in off
        onSwitch.setText(if (name in off) R.string.dict_off else R.string.dict_on)
        addButton.visibility = if (readOnly) View.GONE else View.VISIBLE
        refresh()
        stamp = diskStamp()
    }

    /** Пишет lines в файл напрямую, без View — вызывается и из onPause/save (где view есть),
     * и из действий над списком (свайп, Undo, диалог), где к моменту записи view уже могло
     * не быть (например, Undo в Snackbar сработал после ухода со страницы). */
    protected fun persist() { file.writeText(lines.joinToString("\n")); stamp = diskStamp(); warm() }

    // ---- правки lines: только через эти методы, чтобы разбор и порядок не разъехались ----
    protected fun setLine(index: Int, line: String) { lines[index] = line; parsedLines[index] = parseLine(line); order = null }
    protected fun addLine(line: String) { lines += line; parsedLines += parseLine(line); order = null }
    protected fun insertLine(index: Int, line: String) { lines.add(index, line); parsedLines.add(index, parseLine(line)); order = null }
    protected fun removeLine(index: Int): String { parsedLines.removeAt(index); order = null; return lines.removeAt(index) }

    /** Пересчитать видимый список после изменения lines или фильтра. */
    protected fun refresh() {
        val all = order ?: lines.indices.filter { parsedLines[it] != null }
            .let { if (sorted) it.sortedWith(compareBy(Dicts.COLLATOR) { sortKey(parsedLines[it]!!) }) else it }
            .also { order = it }
        val query = filterField.text?.toString()?.trim().orEmpty()
        val scope = prefs.searchScope(kind)
        val name = Dicts.name(file)
        shown = when {
            query.isEmpty() -> all
            scope == Dicts.Scope.CURRENT || scope.covers(name) -> all.filter { matches(parsedLines[it]!!, query) }
            else -> emptyList()
        }
        extra = if (query.isEmpty()) emptyList() else prefs.dictFiles(kind).map { Dicts.name(it) to it }
            .filter { (n, _) -> n != name && scope.covers(n) }
            .flatMap { (n, f) ->
                others.getOrPut(n) { f.readLines().mapNotNull { parseLine(it) }.let { if (sorted) it.sortedWith(compareBy(Dicts.COLLATOR) { sortKey(it) }) else it } }
                    .filter { matches(it, query) }.map { n to it }
            }
        val empty = shown.isEmpty() && extra.isEmpty()
        emptyView.visibility = if (empty) View.VISIBLE else View.GONE
        recycler.visibility = if (empty) View.GONE else View.VISIBLE
        recycler.adapter?.notifyDataSetChanged()
    }

    /** «Проверить»: то же окно, что раздел «Проверка» на «Голосе», — послушать и разобрать фразу
     * со словом из списка, не уходя с вкладки. Правки уже на диске (persist после каждой). */
    private fun showCheckDialog() {
        val ctx = requireContext()
        val box = LayoutInflater.from(ctx).inflate(R.layout.preview_box, null)
        val pad = (12 * resources.displayMetrics.density).toInt(); box.setPadding(pad, pad, pad, 0)
        val field = box.findViewById<TextInputEditText>(R.id.previewText).apply { setText(prefs.dictPreviewText) }
        // пустое поле — ничего: подставлять пример, как на «Голосе», здесь сбивает с толку
        fun text() = field.text.toString().also { prefs.dictPreviewText = it }.takeIf { it.isNotBlank() }
        box.findViewById<Button>(R.id.preview).setOnClickListener { btn -> text()?.let { (activity as SettingsActivity).preview(btn, it) } }
        box.findViewById<Button>(R.id.analyze).setOnClickListener { text()?.let { (activity as SettingsActivity).analyze(it) } }
        MaterialAlertDialogBuilder(ctx).setView(box).setPositiveButton(R.string.close, null).show()
    }

    /** Удаляет строку файла и даёт «Отменить» в снекбаре. */
    protected fun deleteLine(lineIndex: Int) {
        val removed = removeLine(lineIndex)
        refresh(); persist()
        // якорь на FAB: иначе снекбар ложится под «+», и тап по «Отменить» открывает диалог
        Snackbar.make(recycler, R.string.deleted, 6000) // LENGTH_LONG (2,75 с) не хватает, чтобы дотянуться до «Отменить»
            .setAnchorView(requireView().findViewById<View>(R.id.add))
            .setAction(R.string.undo) {
                insertLine(lineIndex.coerceAtMost(lines.size), removed)
                refresh(); persist()
            }.show()
    }

    private fun snack(text: String) {
        val v = view ?: return
        Snackbar.make(v, text, Snackbar.LENGTH_LONG).setAnchorView(v.findViewById<View>(R.id.add)).show()
    }

    /** Прогрев кэша словарей в фоне; индикатор показывается, только если не уложились в 150 мс. */
    private fun warm() {
        val v = view ?: return
        val row = v.findViewById<View>(R.id.cacheRow)
        val bar = v.findViewById<LinearProgressIndicator>(R.id.cacheBar)
        val text = v.findViewById<TextView>(R.id.cacheText)
        val handler = Handler(Looper.getMainLooper())
        val show = Runnable { row.visibility = View.VISIBLE }
        handler.postDelayed(show, 150)
        DictCache.warm(prefs.enabledDictFiles(kind), kind,
            // фрагмент могли закрыть, пока грелось — getString без контекста упадёт
            { pct -> handler.post { if (isAdded) { bar.progress = pct; text.text = getString(R.string.dict_cache, pct) } } },
            { handler.post { handler.removeCallbacks(show); row.visibility = View.GONE } })
    }

    // ---- меню списка ----
    private fun showMenu(anchor: View) {
        val name = Dicts.name(file)
        val popup = PopupMenu(requireContext(), anchor)
        popup.inflate(R.menu.dict_list)
        popup.menu.findItem(R.id.dict_delete).isEnabled = prefs.dictFiles(kind).size > 1 && !readOnly
        popup.menu.findItem(R.id.dict_rename).isEnabled = !readOnly
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.dict_new -> nameDialog(R.string.dict_new, "") { newName ->
                    Dicts.file(requireContext().filesDir, kind, newName).writeText("")
                    prefs.setCurrent(kind, newName); loadLines()
                }
                R.id.dict_rename -> nameDialog(R.string.dict_rename, name) { newName ->
                    persist()
                    if (file.renameTo(Dicts.file(requireContext().filesDir, kind, newName))) {
                        prefs.off(kind).let { if (name in it) prefs.setOff(kind, it - name + newName) }
                        prefs.setCurrent(kind, newName); loadLines(); warm()
                    }
                }
                R.id.dict_delete -> {
                    val count = parsedLines.count { it != null }
                    MaterialAlertDialogBuilder(requireContext())
                        .setMessage(getString(R.string.dict_delete_confirm, name, count))
                        .setPositiveButton(R.string.delete) { _, _ ->
                            file.delete()
                            prefs.setOff(kind, prefs.off(kind) - name)
                            prefs.setCurrent(kind, Dicts.MAIN); loadLines(); warm()
                        }
                        .setNegativeButton(R.string.cancel, null).show()
                }
                R.id.dict_import -> importLauncher.launch(arrayOf("text/*", "*/*"))
                R.id.dict_export -> { persist(); launchExport("$name.txt", file.readBytes()) }
                R.id.dict_export_demagog -> {
                    persist()
                    val (text, skipped) = Dicts.toDemagog(lines, kind)
                    launchExport("$name.txt", Dicts.demagogBytes(text)) {
                        if (skipped.isNotEmpty()) MaterialAlertDialogBuilder(requireContext())
                            .setMessage(getString(R.string.dict_regex_skipped, skipped.size))
                            .setPositiveButton(R.string.dict_regex_save) { _, _ ->
                                launchExport("$name-regex.txt", skipped.joinToString("\n", postfix = "\n").toByteArray())
                            }
                            .setNegativeButton(R.string.cancel, null).show()
                    }
                }
            }
            true
        }
        popup.show()
    }

    private fun launchExport(fileName: String, bytes: ByteArray, after: (() -> Unit)? = null) {
        pendingExport = bytes; afterExport = after
        exportLauncher.launch(fileName)
    }

    /** Диалог имени списка для «новый»/«переименовать»: проверка допустимости и занятости на лету. */
    private fun nameDialog(titleRes: Int, initial: String, onOk: (String) -> Unit) {
        val ctx = requireContext()
        val layout = TextInputLayout(ctx, null, com.google.android.material.R.attr.textInputOutlinedStyle).apply {
            hint = getString(R.string.dict_name_hint)
            val pad = (12 * resources.displayMetrics.density).toInt(); setPadding(pad, pad / 2, pad, 0)
        }
        val field = TextInputEditText(layout.context).apply { setText(initial); setSelection(initial.length) }
        layout.addView(field)
        var posButton: Button? = null
        fun validate() {
            val name = field.text.toString().trim()
            layout.error = when {
                name.isEmpty() -> null
                !Dicts.validName(name) -> getString(R.string.dict_name_bad)
                name != initial && Dicts.file(ctx.filesDir, kind, name).exists() -> getString(R.string.dict_name_taken)
                else -> null
            }
            posButton?.isEnabled = name.isNotEmpty() && name != initial && layout.error == null
        }
        field.doAfterTextChanged { validate() }
        val dialog = MaterialAlertDialogBuilder(ctx).setTitle(titleRes).setView(layout)
            .setPositiveButton(R.string.save) { _, _ -> onOk(field.text.toString().trim()) }
            .setNegativeButton(R.string.cancel, null).create()
        dialog.setOnShowListener { posButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE); validate() }
        dialog.show()
    }

    /** Импорт файла списка: кодировка по содержимому (UTF-8, иначе cp1251 Демагога), имя —
     * из имени файла, занятое имя получает « (2)»; список становится текущим. */
    private fun importFrom(uri: Uri) {
        val ctx = requireContext()
        try {
            val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw IllegalStateException("Не удалось открыть файл")
            val display = ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            } ?: "Импорт"
            val base = display.substringBeforeLast('.').trim().take(60).ifEmpty { "Импорт" }.replace('/', ' ').replace('\\', ' ')
            val name = Dicts.freeName(ctx.filesDir, kind, base)
            Dicts.file(ctx.filesDir, kind, name).writeText(Dicts.importText(Dicts.decode(bytes), kind))
            prefs.setCurrent(kind, name)
            loadLines(); warm()
            snack(getString(R.string.dict_imported, name))
        } catch (e: Exception) { snack(e.message ?: e.toString()) }
    }

    private fun attachSwipeToDelete() {
        // Список живёт внутри ViewPager2: горизонтальный жест иначе то удаляет, то листает
        // вкладки. Над списком касание целиком наше; вкладки переключаются по тапу.
        recycler.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                if (e.actionMasked == MotionEvent.ACTION_DOWN) rv.parent.requestDisallowInterceptTouchEvent(true)
                return false
            }
        })
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder) = false
            override fun getSwipeDirs(rv: RecyclerView, vh: RecyclerView.ViewHolder) =
                if (readOnly || vh.bindingAdapterPosition >= shown.size) 0 else super.getSwipeDirs(rv, vh)
            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {
                val pos = vh.bindingAdapterPosition
                if (pos !in shown.indices) { refresh(); return }
                deleteLine(shown[pos])
            }
        }).attachToRecyclerView(recycler)
    }
}

/** Вкладка «Ударения»: список слов из user_stress.txt в алфавитном порядке, диалог с чипами
 * по гласным слова и прослушиванием «как модель» / «с ударением». */
class StressFragment : DictListFragment(R.layout.fragment_dict_list) {
    override val kind = Dicts.Kind.STRESS
    override val emptyHintRes = R.string.stress_empty_hint
    override val helpRes = R.string.stress_help
    override val helpTitleRes = R.string.tab_stress
    override val sorted = true

    override fun parseLine(line: String) = DictLines.parseStress(line)
    @Suppress("UNCHECKED_CAST")
    private fun parsed(index: Int) = parsedAny(index) as Pair<String, String>?
    @Suppress("UNCHECKED_CAST")
    private fun pair(parsed: Any) = parsed as Pair<String, String>
    override fun sortKey(parsed: Any) = pair(parsed).first
    override fun matches(parsed: Any, query: String) = pair(parsed).first.contains(query, ignoreCase = true)

    override fun createAdapter(): RecyclerView.Adapter<*> = Adapter()

    private inner class Adapter : RecyclerView.Adapter<Adapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val word: TextView = v.findViewById(R.id.word)
            val play: ImageButton = v.findViewById(R.id.play)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_stress, parent, false))

        override fun getItemCount() = rowCount

        override fun onBindViewHolder(holder: VH, position: Int) {
            val (list, p) = item(position)
            val (_, variant) = pair(p)
            holder.word.text = labeled(DictLines.accentDisplay(variant), list, holder.word)
            holder.itemView.setOnClickListener { if (list != null) openIn(list, p) else if (!readOnly) showDialog(shown[position]) }
            holder.play.setOnClickListener { btn -> (activity as SettingsActivity).preview(btn, variant) }
        }
    }

    private fun findLineIndexForWord(word: String): Int? =
        (0 until lineCount).firstOrNull { parsed(it)?.first?.equals(word, ignoreCase = true) == true }

    override fun showDialog(editIndex: Int?) {
        val ctx = requireContext()
        val view = LayoutInflater.from(ctx).inflate(R.layout.dialog_stress, null)
        val wordField = view.findViewById<TextInputEditText>(R.id.word)
        // пробелы ломают индексацию гласных/чипов (rebuildChips строит их по live-тексту, а
        // formatStress получил бы уже обрезанный trim()-ом текст с другими индексами) — проще
        // не пускать их в поле вообще, слову с ударением фразы всё равно не место
        // null = «не менять»: иначе теряется composing-спан IME и буквы дублируются при наборе
        wordField.filters = arrayOf(InputFilter { s, _, _, _, _, _ -> if (s.any { it.isWhitespace() }) s.filter { !it.isWhitespace() } else null })
        val chips = view.findViewById<ChipGroup>(R.id.chips)
        var pendingSelect: Int? = null
        var posButton: Button? = null

        fun selectedPos(): Int? = chips.checkedChipId.takeIf { it != View.NO_ID }
            ?.let { chips.findViewById<Chip>(it)?.tag as? Int }

        fun updateSaveEnabled() {
            posButton?.isEnabled = wordField.text?.isNotBlank() == true && selectedPos() != null
        }

        fun rebuildChips(selectPos: Int?) {
            chips.removeAllViews()
            val word = wordField.text.toString()
            for (pos in DictLines.vowelPositions(word)) {
                val chip = LayoutInflater.from(ctx).inflate(R.layout.item_chip, chips, false) as Chip
                // у всех чипов из item_chip.xml один и тот же android:id — ChipGroup.checkedChipId
                // различает их только по id, назначаем каждому свой
                chip.id = View.generateViewId()
                chip.text = DictLines.accentDisplay(word.substring(0, pos) + "+" + word.substring(pos))
                chip.tag = pos
                chip.setOnCheckedChangeListener { _, _ -> updateSaveEnabled() }
                chips.addView(chip)
            }
            val autoSelect = selectPos ?: DictLines.vowelPositions(word).singleOrNull()
            autoSelect?.let { p ->
                (0 until chips.childCount).map { chips.getChildAt(it) as Chip }.firstOrNull { it.tag == p }?.isChecked = true
            }
            updateSaveEnabled()
        }

        wordField.doAfterTextChanged {
            val raw = it?.toString().orEmpty()
            val plusIdx = raw.indexOf('+')
            if (plusIdx >= 0) {
                // ударение вставили из буфера прямо в поле — убираем «+», выбираем чип вместо него
                pendingSelect = plusIdx
                wordField.setText(raw.removeRange(plusIdx, plusIdx + 1))
                wordField.setSelection(wordField.text?.length ?: 0)
            } else {
                rebuildChips(pendingSelect)
                pendingSelect = null
            }
        }

        editIndex?.let { i ->
            val (w, variant) = parsed(i)!!
            pendingSelect = variant.indexOf('+').takeIf { it >= 0 }
            wordField.setText(w)
        }

        // «Как читает модель» слушает слово без пользовательского словаря (иначе при
        // редактировании уже существующего слова «до» не услышать — оно им же и переопределено).
        // «С ударением» — то же самое явное ударение, что уйдёт в файл: тоже без словаря,
        // иначе Stress.userDictPass молча заменит его на старый вариант из файла.
        val nodict = Bundle().apply { putString("ruvoice.nodict", "1") }
        view.findViewById<Button>(R.id.listenModel).setOnClickListener { btn ->
            val word = wordField.text.toString()
            if (word.isNotBlank()) (activity as SettingsActivity).preview(btn, word, nodict)
        }
        view.findViewById<Button>(R.id.listenStressed).setOnClickListener { btn ->
            val word = wordField.text.toString()
            val pos = selectedPos()
            if (word.isNotBlank() && pos != null) {
                (activity as SettingsActivity).preview(btn, word.substring(0, pos) + "+" + word.substring(pos), nodict)
            }
        }

        val dialog = MaterialAlertDialogBuilder(ctx)
            .setView(view)
            .setPositiveButton(R.string.save) { _, _ ->
                val word = wordField.text.toString().trim()
                val pos = selectedPos()
                if (word.isNotBlank() && pos != null) {
                    val line = DictLines.formatStress(word, pos)
                    // ищем дубликат всегда, не только при добавлении: переименование слова в
                    // диалоге редактирования могло совпасть с уже существующей записью
                    val dupIndex = findLineIndexForWord(word)
                    when {
                        editIndex != null -> {
                            setLine(editIndex, line)
                            if (dupIndex != null && dupIndex != editIndex) removeLine(dupIndex)
                        }
                        dupIndex != null -> setLine(dupIndex, line)
                        else -> addLine(line)
                    }
                    refresh(); persist()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .apply { if (editIndex != null) setNeutralButton(R.string.delete) { _, _ -> deleteLine(editIndex) } }
            .create()
        dialog.setOnShowListener {
            posButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            updateSaveEnabled()
        }
        dialog.show()
    }
}

/** Вкладка «Замены»: список правил из user_replace.txt в порядке файла (порядок важен —
 * см. Replacements.parse), диалог «ключ / на что / regex» с проверкой regex на лету, полем
 * «проверить на тексте» и прослушиванием замены как есть. */
class ReplaceFragment : DictListFragment(R.layout.fragment_dict_list) {
    override val kind = Dicts.Kind.REPLACE
    override val emptyHintRes = R.string.replace_empty_hint
    override val helpRes = R.string.replace_help
    override val helpTitleRes = R.string.tab_replace

    override fun parseLine(line: String) = DictLines.parseReplace(line)
    @Suppress("UNCHECKED_CAST")
    private fun parsed(index: Int) = parsedAny(index) as Triple<String, String, Boolean>?
    @Suppress("UNCHECKED_CAST")
    private fun triple(parsed: Any) = parsed as Triple<String, String, Boolean>
    override fun matches(parsed: Any, query: String): Boolean {
        val (key, value, _) = triple(parsed)
        return key.contains(query, ignoreCase = true) || value.contains(query, ignoreCase = true)
    }

    private fun isSkip(value: String) = value.isBlank() || value.equals("{skip}", ignoreCase = true)

    override fun createAdapter(): RecyclerView.Adapter<*> = Adapter()

    private inner class Adapter : RecyclerView.Adapter<Adapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val key: TextView = v.findViewById(R.id.key)
            val regexTag: TextView = v.findViewById(R.id.regexTag)
            val value: TextView = v.findViewById(R.id.value)
            val play: ImageButton = v.findViewById(R.id.play)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_replace, parent, false))

        override fun getItemCount() = rowCount

        override fun onBindViewHolder(holder: VH, position: Int) {
            val (list, p) = item(position)
            val (key, value, isRegex) = triple(p)
            holder.key.text = labeled(key, list, holder.key)
            holder.regexTag.visibility = if (isRegex) View.VISIBLE else View.GONE
            val skip = isSkip(value)
            holder.value.text = if (skip) getString(R.string.replace_skip) else getString(R.string.replace_arrow, value)
            holder.play.visibility = if (skip) View.GONE else View.VISIBLE
            holder.itemView.setOnClickListener { if (list != null) openIn(list, p) else if (!readOnly) showDialog(shown[position]) }
            holder.play.setOnClickListener { btn -> (activity as SettingsActivity).preview(btn, value) }
        }
    }

    override fun showDialog(editIndex: Int?) {
        val ctx = requireContext()
        val view = LayoutInflater.from(ctx).inflate(R.layout.dialog_replace, null)
        val keyLayout = view.findViewById<TextInputLayout>(R.id.keyLayout)
        val keyField = view.findViewById<TextInputEditText>(R.id.key)
        val valueLayout = view.findViewById<TextInputLayout>(R.id.valueLayout)
        val valueField = view.findViewById<CursorEditText>(R.id.value)
        val stressChips = view.findViewById<ChipGroup>(R.id.stressChips)
        val stressToggle = view.findViewById<Button>(R.id.stressToggle)
        val stressHint = view.findViewById<TextView>(R.id.stressHint)
        val regexSwitch = view.findViewById<MaterialSwitch>(R.id.regex)
        val sampleLayout = view.findViewById<TextInputLayout>(R.id.sampleLayout)
        val sampleToggle = view.findViewById<Button>(R.id.sampleToggle)
        val sampleField = view.findViewById<TextInputEditText>(R.id.sample)
        val sampleResult = view.findViewById<TextView>(R.id.sampleResult)
        var posButton: Button? = null
        // «=» — разделитель «ключ = замена» в файле, в обычном ключе он сломал бы строку;
        // regex-строки делятся по « = » с пробелами, там «=» нужен для (?<=…) и (?=…)
        keyField.filters = arrayOf(InputFilter { s, _, _, _, _, _ ->
            if (!regexSwitch.isChecked && s.contains('=')) s.filter { it != '=' } else null
        })

        fun currentLine(): String {
            val key = keyField.text.toString().trim().let { if (regexSwitch.isChecked) it else it.filter { c -> c != '=' } }
            return DictLines.formatReplace(key, valueField.text.toString().trim(), regexSwitch.isChecked)
        }

        /** Ошибки regex-ключа и замены — в поля, кнопка «Сохранить» гаснет; результат применения
         * правила к тексту из поля «проверить» — под ним. */
        fun validate() {
            val key = keyField.text.toString().trim()
            val regex = regexSwitch.isChecked
            val keyErr = if (regex) Replacements.patternError(key) else null
            val valErr = if (regex && keyErr == null) Replacements.replacementError(key, valueField.text.toString().trim()) else null
            keyLayout.error = keyErr
            valueLayout.error = valErr
            if (valErr == null) valueLayout.helperText = getString(if (regex) R.string.replace_value_helper_regex else R.string.replace_value_helper)
            val ok = key.isNotBlank() && keyErr == null && valErr == null
            posButton?.isEnabled = ok
            val sampleOpen = prefs.replaceSampleOpen
            sampleToggle.setText(if (sampleOpen) R.string.replace_sample_open else R.string.replace_sample_closed)
            sampleLayout.visibility = if (sampleOpen) View.VISIBLE else View.GONE
            val sample = if (sampleOpen) sampleField.text.toString() else ""
            sampleResult.text = if (ok && sample.isNotBlank()) getString(R.string.replace_arrow, Replacements.parse(listOf(currentLine())).apply(sample)) else ""
            sampleResult.visibility = if (sampleResult.text.isEmpty()) View.GONE else View.VISIBLE
        }

        /** Спойлер «Ударение»: чипы по гласным слова под курсором; тап переставляет «+» в этом
         * слове, повторный тап по отмеченному чипу снимает ударение. */
        var rebuilding = false
        fun rebuildStressChips() {
            if (rebuilding) return
            val open = prefs.replaceStressOpen
            stressToggle.setText(if (open) R.string.replace_stress_open else R.string.replace_stress_closed)
            // пустое «На что» и ключ-слово (не regex): чипы по ключу, тап заполняет замену им же
            // с ударением — «замок = з+амок» без перепечатывания слова
            val fromKey = valueField.text.isNullOrBlank() && !regexSwitch.isChecked
            val text = if (fromKey) keyField.text.toString().trim() else valueField.text.toString()
            val range = if (fromKey) DictLines.wordRangeAt(text, 0)?.takeIf { it == text.indices } else DictLines.wordRangeAt(text, valueField.selectionStart)
            val bare = range?.let { text.substring(it).replace("+", "") }.orEmpty()
            stressChips.removeAllViews()
            stressChips.visibility = if (open && bare.isNotEmpty()) View.VISIBLE else View.GONE
            stressHint.visibility = if (open && bare.isEmpty()) View.VISIBLE else View.GONE
            if (!open || range == null) return
            val stressed = text.substring(range).indexOf('+')
            for (pos in DictLines.vowelPositions(bare)) {
                val chip = LayoutInflater.from(ctx).inflate(R.layout.item_chip, stressChips, false) as Chip
                chip.id = View.generateViewId()
                chip.text = DictLines.accentDisplay(bare.substring(0, pos) + "+" + bare.substring(pos))
                chip.isChecked = pos == stressed
                chip.setOnClickListener {
                    rebuilding = true
                    valueField.setText(DictLines.setWordStress(text, range, pos.takeIf { chip.isChecked }))
                    valueField.setSelection(range.first + pos + if (chip.isChecked) 1 else 0)
                    rebuilding = false
                    rebuildStressChips()
                }
                stressChips.addView(chip)
            }
        }
        valueField.onCursorMoved = { rebuildStressChips() }
        stressToggle.setOnClickListener { prefs.replaceStressOpen = !prefs.replaceStressOpen; rebuildStressChips() }
        regexSwitch.setOnCheckedChangeListener { _, _ -> validate(); rebuildStressChips() }
        keyField.doAfterTextChanged { validate(); rebuildStressChips() }
        valueField.doAfterTextChanged { validate() }
        sampleField.doAfterTextChanged { validate() }
        sampleToggle.setOnClickListener { prefs.replaceSampleOpen = !prefs.replaceSampleOpen; validate() }

        view.findViewById<Button>(R.id.regexHelp).setOnClickListener {
            // справка длинная — HTML из assets, сообщение диалога само прокручивается
            val html = ctx.assets.open("regex_help.html").bufferedReader().readText()
            MaterialAlertDialogBuilder(ctx).setTitle(R.string.regex_help_title)
                .setMessage(HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_COMPACT))
                .setPositiveButton(android.R.string.ok, null).show()
        }

        editIndex?.let { i ->
            val (key, value, isRegex) = parsed(i)!!
            regexSwitch.isChecked = isRegex
            keyField.setText(key)
            valueField.setText(value)
        }
        validate()

        valueLayout.setEndIconOnClickListener { btn ->
            val text = valueField.text.toString()
            if (text.isNotBlank()) (activity as SettingsActivity).preview(btn, text)
        }

        val dialog = MaterialAlertDialogBuilder(ctx)
            .setView(view)
            .setPositiveButton(R.string.save) { _, _ ->
                if (keyField.text?.isNotBlank() == true) {
                    val line = currentLine()
                    if (editIndex != null) setLine(editIndex, line) else addLine(line)
                    refresh(); persist()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .apply { if (editIndex != null) setNeutralButton(R.string.delete) { _, _ -> deleteLine(editIndex) } }
            .create()
        dialog.setOnShowListener {
            posButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            validate()
        }
        dialog.show()
    }
}
