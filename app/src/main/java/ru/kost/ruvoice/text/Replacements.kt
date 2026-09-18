package ru.kost.ruvoice.text

import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.Matcher
import java.util.regex.PatternSyntaxException

/**
 * Пользовательский словарь замен: алиасы, ударение с оглядкой на соседние слова, пропуск мусора.
 * Строка «ключ = замена»; пустая замена удаляет ключ из текста.
 * «*» в обычном ключе — маска: буквы/цифры/дефис, в том числе ничего; для границ слова
 * считается буквой («прочита*» — слово с таким началом, «*ходить» — с таким концом, одна «*» —
 * любое слово). «*» в замене подставляет то, что захватила такая же по счёту «*» ключа; замена
 * без «*» подменяет только буквы ключа, захват «*» остаётся на месте («туник* = тун+ик» читает
 * «тун+ика») — так у Говорилки и Демагога; см. implicitStars. Пустая замена удаляет всё совпадение.
 * «,» в обычном ключе матчит запятую с любым числом пробелов после — словари Демагога пишут
 * её без пробела. «$» в начале ключа — совпадение с учётом регистра («$Ворон» — только имя);
 * «$$» и «##» в начале — просто «$» и «#». Это и есть формат Демагога, конвертер не нужен.
 * Ключ, начинающийся с «~», — Java-regex (без автоматических границ слова), его замена
 * подставляется как есть (работают $1, $2); битый regex молча пропускается. Регистр
 * игнорируется, внутри regex это отключается через «(?-i)».
 * Один и тот же ключ дважды — действует последняя строка.
 * Замена «{skip}» равносильна пустой. Маркер «{pause:N}» в замене превращается в паузу
 * N мс — его уже разбирает Pipeline.plan, здесь это просто часть подставляемого текста.
 *
 * Рассчитано на десятки тысяч правил: Regex при разборе не компилируется вовсе (кроме «~»),
 * правила индексируются по якорному слову ключа, и на текст пробуются только те, все слова
 * которых в нём есть. Ключ без маски ищется indexOf-ом; с маской — regex, скомпилированный
 * при первом применении и запускаемый только в окне вокруг якорного слова.
 */
class Replacements private constructor(private val rules: List<Rule>, private val index: HashMap<String, IntArray>,
                                       private val always: IntArray) {
    /** key — lowercase ключ без «~» (для правила с «$» — как написан). value: LITERAL — текст как есть, WILD — текст с «*» на месте
     * захваченного, REGEX — строка замены Java. tokens — слова ключа не у «*» (для индекса и
     * отсечки), anchor — самое редкое из них, по нему правило лежит в index. */
    private class Rule(val key: String, val value: String, val kind: Int, val isWord: Boolean, val tokens: Array<String>,
                       var re: Regex? = null, val caseSensitive: Boolean = false) {
        var anchor: String? = null
        /** Замена — тот же ключ с ударениями («старый замок = старый з+амок»): «+» ставится в текст как есть, регистр не трогаем. */
        val stressOnly = kind == LITERAL && '+' in value && value.replace("+", "") == key
    }

    fun apply(text: String): String {
        var result = text
        var lower = text.lowercase()
        // lowercase у редких букв меняет длину строки — тогда индексы lower и text разъедутся,
        // indexOf-путь нельзя, всё через regex по всему тексту
        var aligned = lower.length == text.length
        val words = HashSet<String>()
        for (m in token.findAll(lower)) words += m.value
        for (id in candidates(words)) {
            val rule = rules[id]
            // правило не может совпасть, если хоть одного его слова нет в тексте
            if (!rule.tokens.all { it in words }) continue
            val next = when {
                rule.kind == REGEX -> applyRegex(result, rule)
                rule.kind == LITERAL && aligned -> applyLiteral(result, lower, rule)
                else -> applyWild(result, if (aligned) lower else null, rule)
            }
            if (next != null && next != result) {
                result = next; lower = result.lowercase(); aligned = lower.length == result.length
            }
        }
        // после удаления ключей могли остаться двойные или краевые пробелы
        return spaces.replace(result, " ").trim()
    }

    /** Номера правил, которые стоит пробовать на этом тексте, по возрастанию (= порядок файла
     * после сортировки по длине). ponytail: слова считаются по тексту до применения — если
     * правило A породило слово, по которому якорится B, B в этом проходе не сработает; и слово
     * ключа ищется как целое слово текста, так что ключ «abc.» не найдётся внутри «xabc.»
     * (regex без границ его бы нашёл). */
    private fun candidates(words: Set<String>): IntArray {
        if (index.isEmpty()) return always
        var out = always.copyOf(); var n = out.size
        for (w in words) {
            val ids = index[w] ?: continue
            if (n + ids.size > out.size) out = out.copyOf(maxOf(out.size * 2, n + ids.size))
            System.arraycopy(ids, 0, out, n, ids.size); n += ids.size
        }
        val res = out.copyOf(n); res.sort(); return res
    }

    private fun applyLiteral(text: String, lower: String, rule: Rule): String? {
        val key = rule.key
        val hay = if (rule.caseSensitive) text else lower
        var i = hay.indexOf(key); if (i < 0) return null
        var sb: StringBuilder? = null; var last = 0
        while (i >= 0) {
            val end = i + key.length
            val ok = !rule.isWord || ((i == 0 || !wordChar(lower[i - 1])) && (end == lower.length || !wordChar(lower[end])))
            if (ok) {
                val out = sb ?: StringBuilder(text.length).also { sb = it }
                out.append(text, last, i)
                if (rule.stressOnly) { var k = i; for (c in rule.value) if (c == '+') out.append('+') else out.append(text[k++]) } else out.append(rule.value)
                last = end; i = hay.indexOf(key, end)
            } else i = hay.indexOf(key, i + 1)
        }
        return sb?.append(text, last, text.length)?.toString()
    }

    /** Маска (или ключ без маски, когда indexOf-путь недоступен): regex гоняется не по всему
     * тексту, а в окне вокруг каждого вхождения якорного слова — совпадение обязано его
     * содержать. Без lower/якоря — одно окно на весь текст. ponytail: окно = длина ключа + 64
     * символа в обе стороны, захват «*» длиннее этого совпадение потеряет. */
    private fun applyWild(text: String, lower: String?, rule: Rule): String? {
        val re = rule.re ?: toRegex(rule.key, rule.caseSensitive).also { rule.re = it }
        val m = re.toPattern().matcher(text).useTransparentBounds(true).useAnchoringBounds(false)
        var sb: StringBuilder? = null; var last = 0
        fun scan(from: Int, to: Int) {
            if (to <= last) return
            m.region(maxOf(last, from), to)
            while (m.find()) {
                (sb ?: StringBuilder(text.length).also { sb = it }).append(text, last, m.start()).append(expand(rule.value, m))
                last = m.end()
            }
        }
        val anchor = rule.anchor
        if (lower == null || anchor == null) scan(0, text.length)
        else {
            val slack = rule.key.length + 64
            var i = lower.indexOf(anchor)
            while (i >= 0) {
                val aEnd = i + anchor.length
                if ((i == 0 || !wordChar(lower[i - 1])) && (aEnd == lower.length || !wordChar(lower[aEnd])))
                    scan(i - slack, minOf(text.length, aEnd + slack))
                i = lower.indexOf(anchor, aEnd)
            }
        }
        return sb?.append(text, last, text.length)?.toString()
    }

    private fun applyRegex(text: String, rule: Rule): String? {
        // regex-правило может ссылаться на несуществующую группу ($2 при одной группе) или
        // содержать одинокий $ — это всплывает только при подстановке, а не при компиляции
        // regex; такое правило просто пропускаем, остальные не должны падать из-за него.
        return try { rule.re!!.replace(text, rule.value) }
            catch (e: IndexOutOfBoundsException) { null }
            catch (e: IllegalArgumentException) { null }
    }

    companion object {
        private const val LITERAL = 0; private const val WILD = 1; private const val REGEX = 2
        private val spaces = Regex(" {2,}")
        private val token = Regex("[\\p{L}\\d]+")
        private const val PROGRESS_STEP = 2000

        // те же классы, что и в regex-границах: \p{L} и ASCII-цифры
        private fun wordChar(c: Char) = c.isLetter() || c in '0'..'9'
        /** «*» — маска, только если в ключе есть буква или цифра: «***» (разделитель сцен) — текст. */
        private fun hasMask(key: String) = '*' in key && key.any { wordChar(it) }
        private fun isWord(key: String, mask: Boolean): Boolean {
            fun edge(c: Char) = wordChar(c) || (mask && c == '*')
            return edge(key.first()) && edge(key.last())
        }

        /** «*» замены → захват такой же по счёту «*» ключа; лишние «*» — пусто. Без маски в ключе
         * «*» остаётся текстом: «*слово*» — маркер логического ударения для Marks. */
        private fun expand(value: String, m: Matcher): String {
            if ('*' !in value || m.groupCount() == 0) return value
            val out = StringBuilder(value.length + 16); var g = 0
            for (c in value) if (c == '*') { if (++g <= m.groupCount()) out.append(m.group(g) ?: "") } else out.append(c)
            return out.toString()
        }

        /** Замена без «*» при маске в ключе — «*» расставляются в неё явно, один раз при разборе:
         * Говорилка и Демагог подразумевают, что захват остаётся («туник*=туни<к» читает «туни<ка»,
         * «ворот* города=воро<т го<рода» — «воротах города»). По словам, если их в ключе и замене
         * поровну и все «*» ключа по краям слов; иначе краевые «*» ключа — на края замены, а
         * серединные при хвостовой «*» уходят вместе с ней в хвост (нумерация захватов сохраняется).
         * Пустая замена удаляет всё совпадение. */
        fun implicitStars(key: String, value: String): String {
            if ('*' in value || value.isEmpty() || !hasMask(key)) return value
            val kw = key.split(' ').filter { it.isNotEmpty() }
            val vw = value.split(' ').filter { it.isNotEmpty() }
            fun lead(w: String) = w.startsWith("*")
            fun trail(w: String) = w.endsWith("*") && w.length > 1
            if (kw.size == vw.size && kw.all { '*' !in it.trim('*') })
                return kw.indices.joinToString(" ") { i -> (if (lead(kw[i])) "*" else "") + vw[i] + (if (trail(kw[i])) "*" else "") }
            val pre = if (lead(key)) "*" else ""
            val post = if (trail(key)) "*".repeat(key.count { it == '*' } - pre.length) else ""
            return pre + value + post
        }

        /** Слово ключа с числом правил, где оно встречается: один объект на слово, без боксинга. */
        private class Tok(val s: String) { var n = 0 }

        /** onProgress получает число разобранных строк — для индикатора прогрева кэша.
         * На ART каждая аллокация и каждый боксинг Int в HashMap/компараторе на 62k правил
         * стоят заметных миллисекунд, поэтому здесь ручные циклы и никаких compareBy, а
         * построчные фазы (разбор строк, слова ключей) идут параллельно по ядрам. */
        fun parse(lines: List<String>, progress: ((Int) -> Unit)? = null): Replacements {
            // из потоков-работников прогресс приходит вразнобой — наружу только по возрастанию
            var reported = -1
            val onProgress: ((Int) -> Unit)? = progress?.let { cb -> { n -> synchronized(cb) { if (n > reported) { reported = n; cb(n) } } } }
            val pairs = parallel(lines, { chunk ->
                val out = ArrayList<Pair<String, String>>(chunk.size)
                for (line in chunk) {
                    val (key, v) = split(line) ?: continue
                    out += key to if (v.equals("{skip}", ignoreCase = true)) "" else v
                }
                out
            }) { done -> onProgress?.invoke(done / 2) }
            // одинаковый ключ дважды — побеждает последняя строка: правку дописывают в конец списка
            run {
                val seen = HashSet<String>(pairs.size * 2); var w = pairs.size
                for (i in pairs.indices.reversed()) if (seen.add(pairs[i].first)) pairs[--w] = pairs[i]
                pairs.subList(0, w).clear()
            }
            pairs.sortWith { a, b -> b.first.length - a.first.length }
            val canon = ConcurrentHashMap<String, Tok>() // одно слово — один объект на все правила
            // Rule + его слова; regex-правило с битым паттерном — null, выбрасывается ниже
            val built = parallel(pairs, { chunk ->
                chunk.map { (rawKey, value) ->
                    if (rawKey.startsWith("~")) {
                        // regex-правило: замена не экранируется, чтобы работали обратные ссылки
                        val re = try { Regex(rawKey.removePrefix("~"), RegexOption.IGNORE_CASE) }
                            catch (e: PatternSyntaxException) { return@map null }
                        Rule(rawKey, value, REGEX, false, emptyArray(), re) to emptyArray<Tok>()
                    } else {
                        val cs = rawKey.length > 1 && rawKey[0] == '$' && rawKey[1] != '$'
                        val raw = if (cs || rawKey.startsWith("$$") || rawKey.startsWith("##")) rawKey.substring(1) else rawKey
                        val key = raw.lowercase()
                        val mask = hasMask(key)
                        val toks = keyTokens(key, mask, canon)
                        Rule(if (cs) raw else key, implicitStars(key, value), if (mask || ',' in key) WILD else LITERAL, isWord(key, mask),
                            Array(toks.size) { toks[it].s }, caseSensitive = cs) to toks
                    }
                }
            }) { done -> onProgress?.invoke(lines.size / 2 + done * (lines.size - lines.size / 2) / maxOf(1, pairs.size)) }
            val rules = ArrayList<Rule>(built.size)
            val ruleToks = ArrayList<Array<Tok>>(built.size)
            for (b in built) { if (b == null) continue; rules += b.first; ruleToks += b.second; for (t in b.second) t.n++ }
            // якорь — самое редкое по словарю слово ключа (при равной частоте — длиннее): так
            // корзины индекса мельче и на текст пробуется меньше правил
            val buckets = HashMap<String, IntList>()
            val always = IntList()
            for (i in rules.indices) {
                var anchor: Tok? = null
                for (t in ruleToks[i]) if (anchor == null || t.n < anchor.n || (t.n == anchor.n && t.s.length > anchor.s.length)) anchor = t
                rules[i].anchor = anchor?.s
                if (anchor == null) always.add(i) else buckets.getOrPut(anchor.s) { IntList() }.add(i)
            }
            val index = HashMap<String, IntArray>(buckets.size * 2)
            for ((k, v) in buckets) index[k] = v.toArray()
            onProgress?.invoke(lines.size)
            return Replacements(rules, index, always.toArray())
        }

        /** Кусками по ядрам, результат в исходном порядке; onProgress — сколько элементов
         * готово, из потоков-работников. Маленькие списки — в текущем потоке. */
        private fun <T, R> parallel(items: List<T>, work: (List<T>) -> List<R>, onProgress: (Int) -> Unit): ArrayList<R> {
            val threads = minOf(Runtime.getRuntime().availableProcessors(), items.size / PROGRESS_STEP + 1)
            if (threads <= 1) return ArrayList(work(items)).also { onProgress(items.size) }
            val chunk = (items.size + threads - 1) / threads
            val pool = Executors.newFixedThreadPool(threads)
            try {
                val done = AtomicInteger()
                val futures = (0 until threads).map { i ->
                    val part = items.subList(i * chunk, minOf(items.size, (i + 1) * chunk))
                    pool.submit(Callable { work(part).also { onProgress(done.addAndGet(part.size)) } })
                }
                val out = ArrayList<R>(items.size)
                for (f in futures) out += f.get()
                return out
            } finally { pool.shutdown() }
        }

        /** Растущий IntArray вместо ArrayList<Int>: без боксинга. */
        private class IntList {
            private var a = IntArray(4); private var n = 0
            fun add(v: Int) { if (n == a.size) a = a.copyOf(n * 2); a[n++] = v }
            fun toArray(): IntArray = a.copyOf(n)
        }

        /** Слова ключа, не примыкающие к «*»: по ним правило индексируется и отсекается. */
        private fun keyTokens(key: String, mask: Boolean, canon: ConcurrentHashMap<String, Tok>): Array<Tok> {
            val out = ArrayList<Tok>(4)
            var i = 0
            while (i < key.length) {
                if (!wordChar(key[i])) { i++; continue }
                val start = i
                while (i < key.length && wordChar(key[i])) i++
                if (mask && ((start > 0 && key[start - 1] == '*') || (i < key.length && key[i] == '*'))) continue
                val t = key.substring(start, i)
                val c = canon.getOrPut(t) { Tok(t) }
                if (c !in out) out += c
            }
            return out.toTypedArray()
        }

        /** Regex для обычного (не «~») ключа: границы слова, маска «*» как группа, «,» с пробелами. */
        fun toRegex(key: String, caseSensitive: Boolean = false): Regex {
            val k = if (caseSensitive) key else key.lowercase()
            val mask = hasMask(k)
            val body = (if (mask) k.split('*') else listOf(k)).joinToString("([\\p{L}\\d-]*)") { part ->
                part.split(',').joinToString(",\\s*") { if (it.isEmpty()) "" else Regex.escape(it) }
            }
            val src = if (isWord(k, mask)) "(?<![\\p{L}\\d])$body(?![\\p{L}\\d])" else body
            return if (caseSensitive) Regex(src) else Regex(src, RegexOption.IGNORE_CASE)
        }

        /** «ключ = замена» → (ключ с «~», если есть; замена). Для regex-ключа разделитель —
         * первое « = » с пробелами, чтобы «=» внутри (?<=…) не рвал строку; если такого нет —
         * первый «=». Пустые, #-строки (кроме «##» — экранированной решётки Демагога) и строки
         * без разделителя — null. */
        fun split(line: String): Pair<String, String>? {
            val t = line.trim()
            if (t.isEmpty() || (t.startsWith("#") && !t.startsWith("##"))) return null
            val sep = if (t.startsWith("~") && t.contains(" = ")) " = " else "="
            val i = t.indexOf(sep)
            if (i < 0) return null
            val key = t.substring(0, i).trim()
            return key.takeIf { it.isNotEmpty() }?.let { it to t.substring(i + sep.length).trim() }
        }

        /** Почему regex-ключ не скомпилируется; null — всё в порядке. Для диалога редактирования. */
        fun patternError(pattern: String): String? =
            try { Regex(pattern, RegexOption.IGNORE_CASE); null } catch (e: PatternSyntaxException) { e.description }

        /** Почему замена упадёт на подстановке ($N больше числа групп, одинокий «$», «\» в конце);
         * null — всё в порядке или сам regex битый (это уже сказал patternError).
         * Именованные группы ${name} не проверяются. */
        fun replacementError(pattern: String, value: String): String? {
            val groups = try { Regex(pattern).toPattern().matcher("").groupCount() }
                catch (e: PatternSyntaxException) { return null }
            var i = 0
            while (i < value.length) {
                when (value[i]) {
                    '\\' -> { if (i + 1 >= value.length) return "«\\» в конце: нечего экранировать"; i++ }
                    '$' -> {
                        val c = value.getOrNull(i + 1)
                        if (c == null || !(c.isDigit() || c == '{')) return "одинокий «$»: перед ним нужен «\\»"
                        if (c.isDigit() && c.digitToInt() > groups) return "группы \$$c нет: в ключе групп — $groups"
                    }
                }
                i++
            }
            return null
        }
    }
}
