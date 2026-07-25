package ru.kost.ruvoice

import android.os.Bundle
import android.text.InputFilter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
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
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.io.File
import java.text.Collator
import java.util.Locale

/**
 * Общая часть вкладок «Ударения» и «Замены»: файл хранится как список сырых строк (lines) —
 * комментарии и пустые строки сохраняются как есть и не показываются в списке. Видимый список
 * (shown — индексы в lines) пересчитывается заново при любом изменении lines/фильтра, без
 * DiffUtil (записей мало, полная перерисовка не заметна). Свайп удаляет строку с Undo,
 * FAB открывает диалог добавления, поле фильтра — см. refresh().
 */
abstract class DictListFragment(layout: Int) : PageFragment(layout) {
    protected abstract val file: File
    protected abstract val emptyHintRes: Int
    /** true — видимый список сортируется по sortKey (ударения, это словарь); false — порядок
     * файла важен и сохраняется как есть (замены: длинные ключи должны идти раньше). */
    protected open val sorted: Boolean = false
    protected open fun sortKey(index: Int): String = ""

    protected val lines = mutableListOf<String>()
    /** Индексы строк в lines, которые сейчас показаны (после разбора, сортировки и фильтра). */
    protected var shown = listOf<Int>(); private set

    protected abstract fun isEntry(index: Int): Boolean
    protected abstract fun matches(index: Int, query: String): Boolean
    protected abstract fun createAdapter(): RecyclerView.Adapter<*>
    protected abstract fun showAddDialog()

    private lateinit var recycler: RecyclerView
    private lateinit var filterLayout: TextInputLayout
    private lateinit var filterField: TextInputEditText
    private lateinit var emptyView: TextView

    override fun load(v: View) {
        recycler = v.findViewById(R.id.list)
        emptyView = v.findViewById<TextView>(R.id.empty).apply { setText(emptyHintRes) }
        filterLayout = v.findViewById(R.id.filterLayout)
        filterField = v.findViewById(R.id.filter)
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = createAdapter()
        filterField.doAfterTextChanged { refresh() }
        v.findViewById<FloatingActionButton>(R.id.add).setOnClickListener { showAddDialog() }
        attachSwipeToDelete()
        // Файл могли поменять извне (импорт настроек + recreate, Task 25) — читаем заново,
        // не кэшируем между пересозданиями.
        lines.clear()
        if (file.exists()) lines.addAll(file.readLines())
        refresh()
    }

    override fun save(v: View) = persist()

    /** Пишет lines в файл напрямую, без View — вызывается и из onPause/save (где view есть),
     * и из действий над списком (свайп, Undo, диалог), где к моменту записи view уже могло
     * не быть (например, Undo в Snackbar сработал после ухода со страницы). */
    protected fun persist() = file.writeText(lines.joinToString("\n"))

    /** Пересчитать видимый список после изменения lines или фильтра. */
    protected fun refresh() {
        val all = lines.indices.filter { isEntry(it) }
        val query = filterField.text?.toString()?.trim().orEmpty()
        val filtered = if (query.isEmpty()) all else all.filter { matches(it, query) }
        shown = if (sorted) filtered.sortedWith(compareBy(RU_COLLATOR) { sortKey(it) }) else filtered
        // ponytail: порог 8 — просто «когда список уже неудобно листать»; сделать настраиваемым,
        // если попросят показывать фильтр всегда.
        filterLayout.visibility = if (all.size >= 8) View.VISIBLE else View.GONE
        emptyView.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
        recycler.visibility = if (shown.isEmpty()) View.GONE else View.VISIBLE
        recycler.adapter?.notifyDataSetChanged()
    }

    private fun attachSwipeToDelete() {
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder) = false
            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {
                val pos = vh.bindingAdapterPosition
                if (pos !in shown.indices) { refresh(); return }
                val lineIndex = shown[pos]
                val removed = lines.removeAt(lineIndex)
                refresh(); persist()
                Snackbar.make(recycler, R.string.deleted, Snackbar.LENGTH_LONG)
                    .setAction(R.string.undo) {
                        lines.add(lineIndex.coerceAtMost(lines.size), removed)
                        refresh(); persist()
                    }.show()
            }
        }).attachToRecyclerView(recycler)
    }

    companion object {
        // ё стоит в Unicode после я — обычное String.compareTo() увело бы её в конец списка
        private val RU_COLLATOR: Collator = Collator.getInstance(Locale("ru"))
    }
}

/** Вкладка «Ударения»: список слов из user_stress.txt в алфавитном порядке, диалог с чипами
 * по гласным слова и прослушиванием «как модель» / «с ударением». */
class StressFragment : DictListFragment(R.layout.fragment_dict_list) {
    override val file get() = prefs.userDictFile
    override val emptyHintRes = R.string.stress_empty_hint
    override val sorted = true

    private fun parsed(index: Int) = DictLines.parseStress(lines[index])
    override fun isEntry(index: Int) = parsed(index) != null
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
            holder.itemView.setOnClickListener { showDialog(index) }
            holder.play.setOnClickListener { btn -> (activity as SettingsActivity).preview(btn, variant) }
        }
    }

    override fun showAddDialog() = showDialog(null)

    private fun findLineIndexForWord(word: String): Int? =
        lines.indices.firstOrNull { parsed(it)?.first?.equals(word, ignoreCase = true) == true }

    private fun showDialog(editIndex: Int?) {
        val ctx = requireContext()
        val view = LayoutInflater.from(ctx).inflate(R.layout.dialog_stress, null)
        val wordField = view.findViewById<TextInputEditText>(R.id.word)
        // пробелы ломают индексацию гласных/чипов (rebuildChips строит их по live-тексту, а
        // formatStress получил бы уже обрезанный trim()-ом текст с другими индексами) — проще
        // не пускать их в поле вообще, слову с ударением фразы всё равно не место
        wordField.filters = arrayOf(InputFilter { s, _, _, _, _, _ -> s.filter { !it.isWhitespace() } })
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
                            lines[editIndex] = line
                            if (dupIndex != null && dupIndex != editIndex) lines.removeAt(dupIndex)
                        }
                        dupIndex != null -> lines[dupIndex] = line
                        else -> lines.add(line)
                    }
                    refresh(); persist()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            posButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            updateSaveEnabled()
        }
        dialog.show()
    }
}

/** Вкладка «Замены»: список правил из user_replace.txt в порядке файла (порядок важен —
 * см. Replacements.parse), диалог «ключ / на что / regex» с прослушиванием замены как есть. */
class ReplaceFragment : DictListFragment(R.layout.fragment_dict_list) {
    override val file get() = prefs.userReplaceFile
    override val emptyHintRes = R.string.replace_empty_hint

    private fun parsed(index: Int) = DictLines.parseReplace(lines[index])
    override fun isEntry(index: Int) = parsed(index) != null
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
        val keyField = view.findViewById<TextInputEditText>(R.id.key)
        // «=» — разделитель «ключ = замена» в файле, ключ с ним внутри сломал бы формат строки
        keyField.filters = arrayOf(InputFilter { s, _, _, _, _, _ -> s.filter { it != '=' } })
        val valueLayout = view.findViewById<TextInputLayout>(R.id.valueLayout)
        val valueField = view.findViewById<TextInputEditText>(R.id.value)
        val regexSwitch = view.findViewById<MaterialSwitch>(R.id.regex)
        var posButton: Button? = null

        fun updateHelper() {
            valueLayout.helperText = getString(
                if (regexSwitch.isChecked) R.string.replace_value_helper_regex else R.string.replace_value_helper
            )
        }
        regexSwitch.setOnCheckedChangeListener { _, _ -> updateHelper() }
        updateHelper()

        fun updateSaveEnabled() { posButton?.isEnabled = keyField.text?.isNotBlank() == true }
        keyField.doAfterTextChanged { updateSaveEnabled() }

        editIndex?.let { i ->
            val (key, value, isRegex) = parsed(i)!!
            keyField.setText(key)
            valueField.setText(value)
            regexSwitch.isChecked = isRegex
            updateHelper()
        }

        view.findViewById<Button>(R.id.listen).setOnClickListener { btn ->
            val text = valueField.text.toString()
            if (text.isNotBlank()) (activity as SettingsActivity).preview(btn, text)
        }

        val dialog = MaterialAlertDialogBuilder(ctx)
            .setView(view)
            .setPositiveButton(R.string.save) { _, _ ->
                val key = keyField.text.toString().trim()
                if (key.isNotBlank()) {
                    val line = DictLines.formatReplace(key, valueField.text.toString().trim(), regexSwitch.isChecked)
                    if (editIndex != null) lines[editIndex] = line else lines.add(line)
                    refresh(); persist()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            posButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            updateSaveEnabled()
        }
        dialog.show()
    }
}
