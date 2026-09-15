package ru.kost.ruvoice

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.text.Html
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
 * DiffUtil. Свайп удаляет строку с Undo, FAB открывает диалог добавления. После каждой правки
 * и переключения списков кэш словарей (DictCache) греется в фоне; индикатор виден, только
 * если прогрев длится дольше 150 мс.
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
    protected open fun sortKey(index: Int): String = ""

    /** Файл текущего списка. */
    protected val file: File get() = prefs.current(kind)
    private val lines = mutableListOf<String>()
    private val parsedLines = ArrayList<Any?>()
    /** Индексы записей (не комментариев) в порядке показа до фильтра; null — пересчитать. */
    private var order: List<Int>? = null
    /** Индексы строк в lines, которые сейчас показаны (после разбора, сортировки и фильтра). */
    protected var shown = listOf<Int>(); private set

    protected val lineCount get() = lines.size
    protected fun line(index: Int) = lines[index]
    /** Разобранная строка (результат parseLine), null — комментарий/пустая/битая. */
    protected fun parsedAny(index: Int): Any? = parsedLines[index]
    protected abstract fun parseLine(line: String): Any?
    protected abstract fun matches(index: Int, query: String): Boolean
    protected abstract fun createAdapter(): RecyclerView.Adapter<*>
    protected abstract fun showAddDialog()

    private lateinit var recycler: RecyclerView
    private lateinit var filterField: TextInputEditText
    private lateinit var emptyView: TextView
    private lateinit var nameField: MaterialAutoCompleteTextView
    private lateinit var onSwitch: MaterialSwitch

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
        filterField = v.findViewById(R.id.filter)
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
        v.findViewById<FloatingActionButton>(R.id.add).setOnClickListener { showAddDialog() }
        attachSwipeToDelete()
        // Файл могли поменять извне (импорт настроек + recreate, Task 25) — читаем заново,
        // не кэшируем между пересозданиями.
        loadLines()
        warm()
    }

    override fun save(v: View) = persist()

    private fun loadLines() {
        val f = file
        lines.clear(); parsedLines.clear()
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
        refresh()
    }

    /** Пишет lines в файл напрямую, без View — вызывается и из onPause/save (где view есть),
     * и из действий над списком (свайп, Undo, диалог), где к моменту записи view уже могло
     * не быть (например, Undo в Snackbar сработал после ухода со страницы). */
    protected fun persist() { file.writeText(lines.joinToString("\n")); warm() }

    // ---- правки lines: только через эти методы, чтобы разбор и порядок не разъехались ----
    protected fun setLine(index: Int, line: String) { lines[index] = line; parsedLines[index] = parseLine(line); order = null }
    protected fun addLine(line: String) { lines += line; parsedLines += parseLine(line); order = null }
    protected fun insertLine(index: Int, line: String) { lines.add(index, line); parsedLines.add(index, parseLine(line)); order = null }
    protected fun removeLine(index: Int): String { parsedLines.removeAt(index); order = null; return lines.removeAt(index) }

    /** Пересчитать видимый список после изменения lines или фильтра. */
    protected fun refresh() {
        val all = order ?: lines.indices.filter { parsedLines[it] != null }
            .let { if (sorted) it.sortedWith(compareBy(Dicts.COLLATOR) { sortKey(it) }) else it }
            .also { order = it }
        val query = filterField.text?.toString()?.trim().orEmpty()
        shown = if (query.isEmpty()) all else all.filter { matches(it, query) }
        emptyView.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
        recycler.visibility = if (shown.isEmpty()) View.GONE else View.VISIBLE
        recycler.adapter?.notifyDataSetChanged()
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
        popup.menu.findItem(R.id.dict_delete).isEnabled = prefs.dictFiles(kind).size > 1
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
            val pad = (20 * resources.displayMetrics.density).toInt(); setPadding(pad, pad / 2, pad, 0)
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

    override fun onResume() {
        super.onResume()
        val v = view ?: return
        val lang = prefs.lang
        val note = v.findViewById<TextView>(R.id.note)
        if (lang == "rus") { note.visibility = View.GONE; v.findViewById<View>(R.id.add).isEnabled = true; return }
        val name = Packs.langs(Packs.installed(requireContext().filesDir))[lang] ?: lang
        note.text = getString(R.string.stress_ru_only, name)
        note.visibility = View.VISIBLE
        v.findViewById<View>(R.id.add).isEnabled = false
    }

    override fun parseLine(line: String) = DictLines.parseStress(line)
    @Suppress("UNCHECKED_CAST")
    private fun parsed(index: Int) = parsedAny(index) as Pair<String, String>?
    override fun sortKey(index: Int) = parsed(index)!!.first
    override fun matches(index: Int, query: String) = parsed(index)!!.first.contains(query, ignoreCase = true)

    override fun createAdapter(): RecyclerView.Adapter<*> = Adapter()

    private inner class Adapter : RecyclerView.Adapter<Adapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val word: TextView = v.findViewById(R.id.word)
            val play: ImageButton = v.findViewById(R.id.play)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_stress, parent, false))

        override fun getItemCount() = shown.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val index = shown[position]
            val (_, variant) = parsed(index)!!
            holder.word.text = DictLines.accentDisplay(variant)
            // как FAB на onResume: для языка пака редактор (со слушалками) и прослушивание неактивны —
            // disabled clickable View события клика не шлёт, отдельно снимать слушатели не нужно.
            val editable = prefs.lang == "rus"
            holder.itemView.isEnabled = editable
            holder.itemView.setOnClickListener { showDialog(index) }
            holder.play.isEnabled = editable
            holder.play.setOnClickListener { btn -> (activity as SettingsActivity).preview(btn, variant) }
        }
    }

    override fun showAddDialog() = showDialog(null)

    private fun findLineIndexForWord(word: String): Int? =
        (0 until lineCount).firstOrNull { parsed(it)?.first?.equals(word, ignoreCase = true) == true }

    private fun showDialog(editIndex: Int?) {
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
    override fun matches(index: Int, query: String): Boolean {
        val (key, value, _) = parsed(index)!!
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

        override fun getItemCount() = shown.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val index = shown[position]
            val (key, value, isRegex) = parsed(index)!!
            holder.key.text = key
            holder.regexTag.visibility = if (isRegex) View.VISIBLE else View.GONE
            val skip = isSkip(value)
            holder.value.text = if (skip) getString(R.string.replace_skip) else getString(R.string.replace_arrow, value)
            holder.play.visibility = if (skip) View.GONE else View.VISIBLE
            holder.itemView.setOnClickListener { showDialog(index) }
            holder.play.setOnClickListener { btn -> (activity as SettingsActivity).preview(btn, value) }
        }
    }

    override fun showAddDialog() = showDialog(null)

    private fun showDialog(editIndex: Int?) {
        val ctx = requireContext()
        val view = LayoutInflater.from(ctx).inflate(R.layout.dialog_replace, null)
        val keyLayout = view.findViewById<TextInputLayout>(R.id.keyLayout)
        val keyField = view.findViewById<TextInputEditText>(R.id.key)
        val valueLayout = view.findViewById<TextInputLayout>(R.id.valueLayout)
        val valueField = view.findViewById<TextInputEditText>(R.id.value)
        val regexSwitch = view.findViewById<MaterialSwitch>(R.id.regex)
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
            val sample = sampleField.text.toString()
            sampleResult.text = if (ok && sample.isNotBlank()) getString(R.string.replace_arrow, Replacements.parse(listOf(currentLine())).apply(sample)) else ""
        }
        regexSwitch.setOnCheckedChangeListener { _, _ -> validate() }
        keyField.doAfterTextChanged { validate() }
        valueField.doAfterTextChanged { validate() }
        sampleField.doAfterTextChanged { validate() }

        view.findViewById<Button>(R.id.regexHelp).setOnClickListener {
            // справка длинная — HTML из assets, сообщение диалога само прокручивается
            val html = ctx.assets.open("regex_help.html").bufferedReader().readText()
            MaterialAlertDialogBuilder(ctx).setTitle(R.string.regex_help_title)
                .setMessage(Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT))
                .setPositiveButton(android.R.string.ok, null).show()
        }

        editIndex?.let { i ->
            val (key, value, isRegex) = parsed(i)!!
            regexSwitch.isChecked = isRegex
            keyField.setText(key)
            valueField.setText(value)
        }
        validate()

        view.findViewById<Button>(R.id.listen).setOnClickListener { btn ->
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
