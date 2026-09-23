package ru.kost.ruvoice

import android.content.Context
import ru.kost.ruvoice.text.Marks
import ru.kost.ruvoice.text.Rules
import ru.kost.ruvoice.text.Stress
import java.util.zip.ZipInputStream

/**
 * «Книга с ударениями» (вкладка «Проверка»): исходная книга, в которую вписаны ударения того же конвейера, что у
 * чтения вслух (замены, правила, нормализация, словари, омографы, акцентор). Ударение возвращается в исходное слово
 * по ключу (Marks.Matcher, как подсветка читаемого слова в сервисе), поэтому регистр, знаки и вёрстка не меняются.
 * Слова, которые нормализация или замены переписали (число, сокращение, «кафе» → «кафэ»), заменяются тем, что
 * прочтёт модель, с ударениями и заглавной, если исходное слово с неё. fb2 правится на месте по абзацам, txt и epub
 * сперва собираются в простой fb2.
 */
object BookAccent {
    enum class Mode { PLUS, ACUTE }
    const val ACUTE = '\u0301'

    /** Замена [start, end) исходного текста на [text] в записи с «+» перед ударной гласной (вывод — [render]). */
    data class Edit(val start: Int, val end: Int, val text: String)

    /**
     * Правки [src] по [accented] (выход Stress по сегментам [src]). Совпавшее по ключу слово получает ударение и «ё»;
     * исходные слова между совпавшими, у которых ключ не сошёлся, заменяются словами Stress между теми же совпавшими.
     * Односложные слова без знака.
     */
    fun edits(src: String, accented: List<String>, hardE: Boolean = false, abbr: Boolean = true): List<Edit> {
        val words = wordRe.findAll(src).filter { Marks.key(it.value).isNotEmpty() }.toList()
        val toks = accented.flatMap { a -> wordRe.findAll(a).map { it.value }.filter { Marks.key(it).isNotEmpty() }.toList() }
        val out = ArrayList<Edit>()
        var pt = -1; var pw = -1
        for ((ti, wj) in anchors(toks.map { Marks.key(it) }, words.map { Marks.key(it.value) }) + (toks.size to words.size)) {
            if (wj > pw + 1 && ti > pt + 1) {
                val ws = words.subList(pw + 1, wj); val ts = toks.subList(pt + 1, ti)
                // запасной путь без аббревиатур: латиницу («USA») правило latin всё равно перепишет; в одном пропуске
                // с числом раскроется вместе с ним — в make() буквенное чтение выключено, так что это редкость
                if (!transfer(src, ws, ts, out, hardE) && (abbr || !ws.all { isAbbr(it.value) })) substitute(src, ws, ts)?.let { out += it }
            }
            if (ti < toks.size) transfer(src, listOf(words[wj]), listOf(toks[ti]), out, hardE)
            pt = ti; pw = wj
        }
        return out
    }

    /**
     * Опоры — пары (слово Stress, исходное слово) с равными ключами: наибольшая общая подпоследовательность, чтобы
     * раскрытое число не привязалось к такому же слову дальше («5 раз, пять»). Абзац больше [MAX_CELLS] — жадно (Matcher).
     */
    fun anchors(tk: List<String>, wk: List<String>): List<Pair<Int, Int>> {
        val n = tk.size; val m = wk.size
        if (n.toLong() * m > MAX_CELLS) {
            val g = Marks.Matcher(wk)
            return tk.indices.mapNotNull { i -> g.next(tk[i]).takeIf { it >= 0 }?.let { i to it } }
        }
        // dp[i*(m+1)+j] — длина общей подпоследовательности хвостов tk[i:] и wk[j:]
        val w = m + 1
        val dp = IntArray((n + 1) * w)
        for (i in n - 1 downTo 0) for (j in m - 1 downTo 0)
            dp[i * w + j] = if (tk[i] == wk[j]) dp[(i + 1) * w + j + 1] + 1 else maxOf(dp[(i + 1) * w + j], dp[i * w + j + 1])
        val out = ArrayList<Pair<Int, Int>>()
        var i = 0; var j = 0
        // склейка без пары («н+ебыло» за «не было»): слово, которое её дописывает, не берём в опору, если то же
        // слово найдётся дальше без потерь («Было» следующей фразы)
        fun completesGlue(): Boolean {
            val pt = out.lastOrNull()?.first ?: -1; val pw = out.lastOrNull()?.second ?: -1
            return i > pt + 1 && dp[i * w + j + 1] == dp[i * w + j] &&
                (pt + 1 until i).joinToString("") { tk[it] } == (pw + 1..j).joinToString("") { wk[it] }
        }
        while (i < n && j < m) when {
            tk[i] == wk[j] && dp[i * w + j] == dp[(i + 1) * w + j + 1] + 1 && !completesGlue() -> { out += i to j; i++; j++ }
            dp[(i + 1) * w + j] > dp[i * w + j + 1] -> i++
            dp[(i + 1) * w + j] == dp[i * w + j + 1] && !(tk[i] == wk[j]) -> i++
            else -> j++
        }
        return out
    }

    /**
     * Ударения и «ё» из [toks] в буквы слов [ws], буквы книги остаются. Буквы сопоставляются выравниванием
     * (Левенштейн), поэтому годится и переписанное произношение («нар+ошно» → «нар+очно», «н+аногу» → «н+а ногу»).
     * false — прочтение слишком не похоже на исходник (другое слово: «доктор» за «Д-р»), тогда [substitute].
     * [hardE] — «э» из прочтения вместо «е» книги (правило hard_e).
     */
    private fun transfer(src: String, ws: List<MatchResult>, toks: List<String>, out: MutableList<Edit>, hardE: Boolean): Boolean {
        val pos = ws.flatMap { w -> w.value.indices.filter { w.value[it].isLetterOrDigit() }.map { w.range.first + it } }
        // буквы прочтения: сама буква, стоит ли перед ней «+», многосложно ли её слово (в односложных знак не ставим)
        val tc = StringBuilder(); val mark = ArrayList<Boolean>(); val many = ArrayList<Boolean>()
        for (t in toks) {
            val syl = t.count { it.lowercaseChar() in VOWELS } > 1
            var plus = false
            for (c in t) {
                if (c == '+') { plus = true; continue }
                if (!c.isLetterOrDigit()) continue
                tc.append(c); mark += plus; many += syl; plus = false
            }
        }
        val n = pos.size; val m = tc.length
        if (n.toLong() * m > MAX_ALIGN) return false
        fun norm(c: Char) = c.lowercaseChar().let { if (it == 'ё' || it == 'э') 'е' else it }
        val w = m + 1
        val d = IntArray((n + 1) * w)
        for (i in 0..n) d[i * w] = i
        for (j in 0..m) d[j] = j
        for (i in 1..n) for (j in 1..m)
            d[i * w + j] = minOf(d[(i - 1) * w + j - 1] + if (norm(src[pos[i - 1]]) == norm(tc[j - 1])) 0 else 1, d[(i - 1) * w + j] + 1, d[i * w + j - 1] + 1)
        if (d[n * w + m] > maxOf(1, maxOf(n, m) / 3)) return false
        if (ws.any { '+' in it.value || ACUTE in it.value }) return true   // ударение уже стоит в самом тексте
        // буква прочтения → буква книги (-1 — вставка), обратным проходом, диагональ в приоритете
        val at = IntArray(m) { -1 }
        var i = n; var j = m
        while (i > 0 && j > 0) {
            val sub = d[(i - 1) * w + j - 1] + if (norm(src[pos[i - 1]]) == norm(tc[j - 1])) 0 else 1
            when (d[i * w + j]) {
                sub -> { at[j - 1] = pos[i - 1]; i--; j-- }
                d[(i - 1) * w + j] + 1 -> i--
                else -> j--
            }
        }
        var pending = false
        for (k in 0 until m) {
            if (mark[k] && many[k]) pending = true
            val p = at[k]
            if (p < 0) continue
            val o = src[p]; val c = tc[k].lowercaseChar()
            val lo = o.lowercaseChar()
            val letter = when {
                c == 'ё' && lo == 'е' -> if (o.isUpperCase()) 'Ё' else 'ё'
                c == 'э' && lo == 'е' && hardE -> if (o.isUpperCase()) 'Э' else 'э'
                else -> o
            }
            val plus = pending && c != 'ё' && lo != 'ё' && lo in VOWELS
            pending = false
            if (plus || letter != o) out += Edit(p, p + 1, (if (plus) "+" else "") + letter)
        }
        return true
    }

    /** «КП», «США», «НКВД,» — от двух букв, все заглавные. */
    private fun isAbbr(w: String) = w.filter { it.isLetter() }.let { it.length >= 2 && it.all { c -> c.isUpperCase() } }

    /** Слова [ws] от первой до последней буквы → [toks] без знаков по краям; заглавная — как у исходного. */
    private fun substitute(src: String, ws: List<MatchResult>, toks: List<String>): Edit? {
        // от первой до последней буквы, плюс прочитанные словом символы по краям («№5», «5%»), но не знаки и скобки
        var start = ws.first().let { w -> w.range.first + w.value.indexOfFirst { it.isLetterOrDigit() } }
        while (start > ws.first().range.first && src[start - 1] !in KEEP) start--
        var end = ws.last().let { w -> w.range.first + w.value.indexOfLast { it.isLetterOrDigit() } + 1 }
        while (end <= ws.last().range.last && src[end] !in KEEP) end++
        // точка сокращения («т. е.», «г.»): у модели её нет — уходит вместе со словом
        if (src.getOrNull(end) == '.' && !toks.last().endsWith('.')) end++
        if ('+' in src.substring(start, end) || ACUTE in src.substring(start, end)) return null
        var text = toks.joinToString(" ") { t -> if (t.count { it.lowercaseChar() in VOWELS } > 1) t.replace("+ё", "ё") else t.replace("+", "") }
            .dropWhile { !it.isLetterOrDigit() && it != '+' }.dropLastWhile { !it.isLetterOrDigit() }
        // заглавная — как у исходного; у числа и знака регистра нет, тогда по началу предложения
        val before = src.substring(0, start).trimEnd { it.isWhitespace() || it in "«\"„“—–-(" }
        val upper = if (src[start].isLetter()) src[start].isUpperCase() else before.isEmpty() || before.last() in ".!?…"
        if (upper) text.indexOfFirst { it.isLetter() }.let { i -> if (i >= 0) text = text.substring(0, i) + text[i].uppercaseChar() + text.substring(i + 1) }
        // внутри подстановки новое предложение («2223. 82» → «…три. Восемьдесят два») — тоже с заглавной
        text = sentenceRe.replace(text) { it.value.dropLast(1) + it.value.last().uppercaseChar() }
        return if (text.isEmpty()) null else Edit(start, end, text)
    }

    /** «+» перед гласной — как есть или знаком над ней. */
    fun render(text: String, mode: Mode) = if (mode == Mode.PLUS) text else plusRe.replace(text) { it.groupValues[1] + ACUTE }

    /** Текст абзаца с правками — для проверки [edits]. */
    fun apply(src: String, edits: List<Edit>, mode: Mode): String = buildString {
        var last = 0
        for (e in edits.sortedBy { it.start }) { if (e.start < last) continue; append(src, last, e.start).append(render(e.text, mode)); last = e.end }
        append(src, last, src.length)
    }

    /** Исходник как fb2: сам fb2, fb2 из zip, иначе абзацы Book.text в простой обёртке с названием [title]. */
    fun source(bytes: ByteArray, title: String): String {
        if (bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte())
            ZipInputStream(bytes.inputStream()).use { zip ->
                generateSequence { zip.nextEntry }.firstOrNull { it.name.lowercase().endsWith(".fb2") }?.let { return Book.decode(zip.readBytes()) }
            }
        else Book.decode(bytes).let { if ("<FictionBook" in it.take(2000)) return it }
        return buildString {
            append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<FictionBook xmlns=\"http://www.gribuser.ru/xml/fictionbook/2.0\">\n")
            append("<description><title-info><book-title>").append(esc(title)).append("</book-title><lang>ru</lang></title-info></description>\n<body><section>\n")
            for (line in Book.text(bytes).lines()) if (line.isNotBlank()) append("<p>").append(esc(line.trim())).append("</p>\n")
            append("</section></body>\n</FictionBook>\n")
        }
    }

    /**
     * Ударения во все абзацы fb2 после description. [accent] — абзац → выход Stress по его сегментам;
     * [progress] (сделано, всего) → false — прервать, тогда null. Кодировка в заголовке — utf-8, писать в UTF-8.
     */
    fun fb2(xml: String, mode: Mode, hardE: Boolean, abbr: Boolean, progress: (Int, Int) -> Boolean, accent: (String) -> List<String>): String? {
        // абзацы: открывающий тег одним Matcher на всю книгу, закрывающий — indexOf. На Android (ICU) каждый
        // Regex.find/matchAt по книге копирует её целиком в нативную память: на книге в 1 МБ это гигабайты, и lmkd
        // убивал процесс. Поэтому здесь ни одного регэкспа по всему xml в цикле
        val paras = ArrayList<IntRange>()
        val mt = openRe.toPattern().matcher(xml)
        var from = maxOf(0, xml.indexOf("</description>"))
        while (from < xml.length && mt.find(from)) {
            from = mt.end()
            if (mt.group().endsWith("/>")) continue
            val close = xml.indexOf("</${mt.group(1)}>", from)
            if (close < 0) break
            paras += from until close
            from = close
        }
        val out = StringBuilder(xml.length + xml.length / 8)
        var last = 0
        for ((i, p) in paras.withIndex()) {
            if (!progress(i, paras.size)) return null
            val inner = p
            // текст абзаца без тегов и сущностей; для каждого его символа — начало и конец в xml
            val plain = StringBuilder(); val starts = ArrayList<Int>(); val ends = ArrayList<Int>()
            var x = inner.first
            while (x <= inner.last) {
                val c = xml[x]
                if (c == '<') { x = xml.indexOf('>', x).let { if (it < 0) inner.last else it } + 1; continue }
                val semi = if (c == '&') xml.indexOf(';', x).takeIf { it in x + 2..minOf(x + 12, inner.last) } else null
                val s = semi?.let { Book.entity(xml.substring(x + 1, it)) }
                val end = if (s != null) semi + 1 else x + 1
                for (ch in s ?: c.toString()) { plain.append(ch); starts += x; ends += end }
                x = end
            }
            if (plain.isBlank()) continue
            for (e in edits(plain.toString(), accent(plain.toString()), hardE, abbr).sortedBy { it.start }) {
                val xs = starts[e.start]; val xe = ends[e.end - 1]
                if (xs < last || (xs until xe).any { xml[it] == '<' }) continue   // правка поперёк тега — пропускаем
                out.append(xml, last, xs).append(esc(render(e.text, mode))); last = xe
            }
        }
        progress(paras.size, paras.size)
        out.append(xml, last, xml.length)
        return out.toString().replaceFirst(encRe, "encoding=\"utf-8\"")
    }

    /**
     * Книга [bytes] → fb2 с ударениями по настройкам приложения (замены, правила, голос; режим, «э» и аббревиатуры из Prefs).
     * Свои модели на время работы, как у «Имён из книги». [progress] как у [fb2]; прервали — null.
     */
    fun make(ctx: Context, bytes: ByteArray, title: String, progress: (Int, Int) -> Boolean): String? {
        val prefs = Prefs(ctx)
        val models = SileroModels(ctx)
        try {
            val d = SileroModels.data(ctx)
            val packs = Packs.installed(ctx.filesDir)
            val allowed = (Speaker.resolve(prefs.voice, d, packs) ?: Speaker.default(d, packs))?.sym?.allowed ?: d.sym.allowed
            val r = prefs.rules()
            // твёрдое [э] — своей галочкой: в книге «кафэ» нужно не всем, кто её слушает
            val hardE = prefs.accentBookHardE
            val abbr = prefs.accentBookAbbr
            // off — переключатели относительно DEFAULT_OFF: key в off ровно тогда, когда нужное состояние не по умолчанию
            fun Set<String>.set(key: String, on: Boolean) = if (on == (key in Rules.DEFAULT_OFF)) this + key else this - key
            // без аббревиатур «КП» не читается по буквам и доходит до книги как есть (сокращения «г.», «т. е.» — правило abbrev — раскрываются)
            val rules = Rules(r.off.set("hard_e", hardE).let { if (abbr) it else it.set("spell_cyr", false).set("spell_lat", false) }, r.maxLen, r.focus)
            val stress = Stress(d, models, prefs.userDict(), rules)
            val replacements = prefs.replacements()
            return fb2(source(bytes, title), if (prefs.accentBookPlus) Mode.PLUS else Mode.ACUTE, hardE, prefs.accentBookAbbr, progress) { p ->
                Pipeline.plan(p, d, 0, 0, replacements, rules).map { Pipeline.accent(it.text, d, stress, allowed, rules) }
            }
        } finally { models.release() }
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private const val MAX_CELLS = 4_000_000L   // 16 МБ на абзац; длиннее — редкость (сплошной текст без абзацев)
    private const val MAX_ALIGN = 1_000_000L
    private const val VOWELS = "аеёиоуыэюя"
    private const val KEEP = ".,;:!?…–—-«»\"'„“”()[]{}*"
    private val wordRe = Regex("\\S+")
    private val plusRe = Regex("\\+(.)")
    private val sentenceRe = Regex("[.!?…] +\\+?\\p{Ll}")
    private val openRe = Regex("<(p|v|subtitle|text-author|td|th)(?:\\s[^>]*)?>")
    private val encRe = Regex("encoding=[\"'][^\"']+[\"']")
}
