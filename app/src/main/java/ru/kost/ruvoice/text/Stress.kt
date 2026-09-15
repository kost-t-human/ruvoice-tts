package ru.kost.ruvoice.text

import ru.kost.ruvoice.SileroData

interface StressModels {
    fun accentor(words: List<String>): Pair<Array<FloatArray>, Array<FloatArray>>
    fun homo(ids: List<LongArray>, starts: LongArray, ends: LongArray): FloatArray
}

class Stress(private val d: SileroData, private val models: StressModels, private val userDict: Map<String, String> = emptyMap(),
             private val rules: Rules = Rules()) {
    private val vowels = "аоуыэиеяёю"
    private val tok = BertTokenizer(d)
    private val homoWordRe = Regex("(?=.*[а-яё])[а-яё+]+", RegexOption.IGNORE_CASE)
    // JVM \s матчит только ASCII-пробелы; Python \s матчит любой Unicode-пробел (NBSP и т.п.),
    // поэтому класс явно расширен \p{Zs} и прочими Unicode-разделителями.
    private val splitRe = Regex("([\\s\\p{Zs}\\u0085\\u2028\\u2029\\u001C-\\u001F.,!?;:<>=()/\\\\]+)")
    private val nonCyr = Regex("[^А-Яа-яёЁ]")
    private val wordRe = Regex("[а-яё+]+", RegexOption.IGNORE_CASE)

    fun apply(sentence: String): String {
        var s = sentence
        if (rules.on("gram")) s = gramPass(s)
        if (rules.on("homo")) s = homographPass(s)
        if (rules.on("accentor")) s = accentorPass(s)
        return userDictPass(s)
    }

    // ---- грамматика: падеж или часть речи по предыдущему слову ----
    private val genGov = setOf("с", "со", "из", "изо", "от", "ото", "у", "до", "без", "безо", "для", "около", "вдоль", "возле",
        "мимо", "после", "кроме", "вокруг", "против", "среди", "из-за", "из-под", "ради", "вместо", "подле", "близ", "накануне",
        "вне", "насчёт", "ввиду", "вследствие", "позади", "впереди", "посреди", "сверх", "свыше", "внутри", "внутрь", "вроде",
        "два", "две", "три", "четыре", "полтора", "полторы", "нет")
    private val prepOther = setOf("в", "во", "на", "за", "под", "подо", "через", "про", "сквозь", "о", "об", "обо", "по", "при",
        "к", "ко", "над", "надо", "перед", "передо", "между", "меж")
    /** Причастие в род. п. управляет винительным: «прикрывавшего ворота», «туманящего глаза». */
    private val participle = listOf("вшего", "ющего", "ущего", "ащего", "ящего")
    private val locPrep = setOf("в", "во", "на", "при")
    private val pronouns = setOf("я", "ты", "он", "она", "оно", "мы", "вы", "они")
    /** На «-ого/-его» кончаются и местоимения, после которых стоит именительный: «его руки», «у него дела». */
    private val notAdjective = setOf("его", "него", "чего", "кого", "ничего", "никого", "некого", "нечего", "всего", "сего", "много", "немного", "итого")
    private val gramWordRe = Regex("[а-яё+-]+", RegexOption.IGNORE_CASE)

    /** «вдоль стены» → «стен+ы», «за село» → «сел+о», «я ношу» → «нош+у», «вечного города» → «г+орода»: слово из
     * таблицы d.gram получает ударение по слову перед ним (между ними только пробелы). Дальше омографы и акцентор
     * его не трогают. Проверено на фразах чужих словарей: по каждой ветке правило право в 85–95 % расхождений с
     * моделью; согласование с прилагательным на «-ые», «-ой» и через числительное пробовали — не лучше BERT. */
    internal fun gramPass(sentence: String): String {
        if (d.gram.isEmpty()) return sentence
        val sb = StringBuilder(sentence)
        var prev = ""; var prev2 = ""; var prevEnd = -1; var offset = 0
        for (m in gramWordRe.findAll(sentence)) {
            val w = m.value.lowercase()
            val e = d.gram[w]
            if (e != null && prevEnd >= 0 && sentence.subSequence(prevEnd, m.range.first).all { it.isWhitespace() }) {
                val pick = when {
                    (prev == "под" || prev == "за") && prev2 == "из" -> e["g"] ?: e["n"]   // «из под», «из за» без дефиса
                    prev == "за" && prev2 == "что" -> null                                 // «что за свиньи» — именительный
                    prev in genGov -> e["g"] ?: e["n"]
                    (prev == "в" || prev == "во") && "g" in e && "p" !in e -> null         // «выйти в учителя» — им. мн.
                    // второй предложный («в пыл+и», «в цвет+у») совпадает с глаголом — омографы из homodict после в/на оставляем BERT
                    prev in prepOther -> e["p"] ?: e["g"] ?: if (prev in locPrep && w in d.homodict) null else e["n"]
                    prev in pronouns -> e["v"]
                    // прилагательное в род. ед. («вечного города», «тёплой стены» не берём: «-ой» и у творительного — «вытер рукой глаза»)
                    prev.endsWith("ого") || prev.endsWith("его") ->
                        if (prev in notAdjective || prev.startsWith("сам") || prev.startsWith("котор") || participle.any { prev.endsWith(it) }) null else e["g"] ?: e["n"]
                    else -> null
                }
                if (pick != null) {
                    val i = pick.indexOf('+')
                    sb.insert(m.range.first + offset + i, '+'); offset++
                }
            }
            prev2 = prev; prev = w; prevEnd = m.range.last + 1
        }
        return sb.toString()
    }

    // ---- homosolver (Silero Stress) ----
    /** Окно контекста вокруг омографа: по 150 символов очищенного текста с каждой стороны. */
    private val window = 300
    private val reExtra = Regex("[^a-zA-Zа-яА-ЯёЁ0-9\\s\\p{Z}.!?,\\-]")
    private val reSpaces = Regex("[\\s\\p{Z}]+")
    private val reDoubleDash = Regex("-{2,}")
    private val reRepeatPunct = Regex("([.!?])\\1+")
    private val reRepeatComma = Regex(",{2,}")
    private val reSpaceBeforePunct = Regex("[\\s\\p{Z}]+([.,!?])")
    private val rePunctAddSpace = Regex("([.,!?])(?=[^\\s\\p{Z}])")

    /** HomoSolver._clean_text: контекст без лишних символов, с одной заглавной в начале и точкой в конце. */
    private fun cleanText(text: String, isStart: Boolean): String {
        if (text.isEmpty()) return ""
        var t = reExtra.replace(text, "")
        t = reSpaces.replace(t, " ")
        t = reDoubleDash.replace(t, " - ")
        t = reRepeatPunct.replace(t, "$1"); t = reRepeatComma.replace(t, ",")
        t = reSpaceBeforePunct.replace(t, "$1")
        t = reRepeatPunct.replace(t, "$1"); t = reRepeatComma.replace(t, ",")
        t = rePunctAddSpace.replace(t, "$1 ")
        t = reSpaces.replace(t, " ").trim()
        if (isStart) {
            t = t.trimStart(' ', '.', ',', '!', '?', '-')
            if (t.isNotEmpty()) t = t[0].uppercase() + t.substring(1).lowercase()
        } else {
            if (t.isNotEmpty()) t = t[0] + t.substring(1).lowercase()
            if (t.isNotEmpty() && t.last() !in ".!?") t += "."
        }
        return t
    }

    /** Regex фраз слова, как в HomoSolver._load_phrases_dict: группа на вариант, внутри фразы через «|»,
     * слово в них обёрнуто в [HOMO] … [/HOMO]. Группы нумерованные (Java не даёт кириллицу в именах),
     * номер группы → вариант. Компилируется при первом омографе этого слова. */
    private class Phrases(val re: Regex, val variants: List<String>)
    private val phrasesCache = HashMap<String, Phrases>()
    private fun phrases(word: String): Phrases? {
        val list = d.phrases[word] ?: return null
        return phrasesCache.getOrPut(word) {
            val variants = ArrayList<String>()
            val byVariant = LinkedHashMap<String, ArrayList<String>>()
            for ((phrase, v) in list) byVariant.getOrPut(v) { ArrayList() } += phrase
            val body = byVariant.entries.joinToString("|") { (v, ps) ->
                variants += v
                val alts = ps.joinToString("|") { Regex.escape(it.replace(word, "[HOMO] $word [/HOMO]").trim()) }
                "((?<![а-яА-ЯёЁ\\-])(?:$alts)(?![а-яА-ЯёЁ\\-]))"
            }
            Phrases(Regex(body, RegexOption.IGNORE_CASE), variants)
        }
    }

    /** Омограф в предложении: marked — контекст с [HOMO]-маркерами для BERT, pred — вариант по фразам
     * (null — решает BERT). */
    class Hit(val start: Int, val end: Int, val word: String, var pred: String?, val marked: String)

    /** HomoSolver._find_and_tag_homos + фразы: слова из homodict или из списка фраз; слово только из
     * фраз без совпадения фразы пропускается. */
    internal fun tagHomos(sentence: String): List<Hit> {
        val hits = ArrayList<Hit>()
        for (m in homoWordRe.findAll(sentence)) {
            val w = m.value.lowercase()
            val inDict = w in d.homodict; val ph = phrases(w)
            if (!inDict && ph == null) continue
            val startText = cleanText(sentence.substring(0, m.range.first), true).takeLast(window / 2)
            val endText = cleanText(sentence.substring(m.range.last + 1), false).take(window / 2)
            val marked = "$startText [HOMO] $w [/HOMO] $endText".trim()
            // сначала фразы, при промахе — BERT (только для слов из homodict)
            var pred: String? = null
            if (ph != null) ph.re.find(marked)?.let { mm -> pred = ph.variants[mm.groups.indices.drop(1).first { mm.groups[it] != null } - 1] }
            if (pred == null && !inDict) continue
            hits += Hit(m.range.first, m.range.last + 1, m.value, pred, marked)
        }
        return hits
    }

    private fun homographPass(sentence: String): String {
        val hits = tagHomos(sentence)
        if (hits.isEmpty()) return sentence
        val neural = hits.filter { it.pred == null }
        if (neural.isNotEmpty()) {
            val ids = neural.map { tok.encode(it.marked) }
            val starts = LongArray(neural.size) { ids[it].indexOf(d.bertHomoStart.toLong()).toLong() }
            val ends = LongArray(neural.size) { ids[it].indexOf(d.bertHomoEnd.toLong()).toLong() }
            val probs = models.homo(ids, starts, ends)
            // torch.round: half-to-even, ровно 0.5 округляется в 0.
            for ((i, h) in neural.withIndex()) h.pred = d.homodict.getValue(h.word.lowercase()).sorted()[if (probs[i] > 0.5f) 1 else 0]
        }
        val sb = StringBuilder(sentence)
        var offset = 0
        for (h in hits) {
            var variant = h.pred!!
            val stressIdx = variant.indexOf('+')
            variant = variant.replace("+", "")
            variant = variant.mapIndexed { k, c -> if (k < h.word.length && h.word[k].isLowerCase()) c.lowercaseChar() else if (k < h.word.length) c.uppercaseChar() else c }.joinToString("")
            // stress_single_vowel=True, put_stress=True в Python — ударение ставится всегда
            variant = variant.substring(0, stressIdx) + "+" + variant.substring(stressIdx)
            sb.replace(h.start + offset, h.end + offset, variant)
            offset += variant.length - (h.end - h.start)
        }
        return sb.toString()
    }

    // ---- accentor ----
    private class Positions(val stress: List<Int>, val yo: List<Int>, val numVowels: Int, val firstVowel: Int)

    private fun positions(word: String, stressedVowelIds: List<Int>, yoVowelIds: List<Int>): Positions {
        val vowelIds = word.indices.filter { word[it] in vowels }
        val yeIds = word.indices.filter { word[it] == 'е' }
        val stress = stressedVowelIds.filter { it < vowelIds.size && vowelIds.isNotEmpty() }.map { vowelIds[it] }
        val yo = yoVowelIds.filter { it > 0 && it - 1 < yeIds.size && yeIds.isNotEmpty() }.map { yeIds[it - 1] }
        return Positions(stress, yo, vowelIds.size, vowelIds.firstOrNull() ?: -1)
    }

    private fun tokenize(sentence: String): Triple<List<String>, List<String>, List<Boolean>> {
        val tokens = ArrayList<String>(); val inputs = ArrayList<String>(); val mask = ArrayList<Boolean>()
        for (word in splitKeep(sentence)) {
            val parts = word.split("-")
            val cur: List<String>; val curMask: List<Boolean>
            if (parts.size == 1) { cur = parts; curMask = listOf(true) }
            else {
                cur = parts.dropLast(1).map { "$it-" } + parts.last()
                curMask = parts.dropLast(1).map { true } + (parts.last() != "то")
            }
            val curInputs = cur.map { nonCyr.replace(it.lowercase(), "") }
            tokens += cur; inputs += curInputs
            mask += curInputs.zip(curMask).map { (x, m) -> x.isNotEmpty() && m }
        }
        return Triple(tokens, inputs, mask)
    }

    /** re.split с захватывающей группой: слова и разделители по очереди. */
    private fun splitKeep(s: String): List<String> {
        val out = ArrayList<String>(); var last = 0
        for (m in splitRe.findAll(s)) { out += s.substring(last, m.range.first); out += m.value; last = m.range.last + 1 }
        out += s.substring(last)
        return out
    }

    private fun accentuateException(clean: String, raw: String, haveStress: Boolean): String {
        val exc = d.exceptions.getValue(clean); val excStress = exc[0]; val excYo = exc[1]
        if (haveStress) {
            val userPos = raw.indices.filter { raw[it] == '+' }
            var w = raw.replace("+", "")
            if (excYo != -1 && (excYo + 1) in userPos) w = w.substring(0, excYo) + (if (w[excYo].isLowerCase()) 'ё' else 'Ё') + w.substring(excYo + 1)
            for (p in userPos) w = w.substring(0, p) + "+" + w.substring(p)
            return w
        }
        var w = raw
        if (excYo != -1) w = w.substring(0, excYo) + (if (w[excYo].isLowerCase()) 'ё' else 'Ё') + w.substring(excYo + 1)
        return w.substring(0, excStress) + "+" + w.substring(excStress)
    }

    private fun accentorPass(sentence: String): String {
        val (raw, clean, mask) = tokenize(sentence)
        val batch = clean.filterIndexed { i, _ -> mask[i] }
        val (stressProbs, yoProbs) = if (batch.isEmpty()) Pair(emptyArray(), emptyArray()) else models.accentor(batch)
        val out = StringBuilder()
        var bi = 0
        for (i in raw.indices) {
            var rawWord = raw[i]; val cleanWord = clean[i]
            if (!mask[i]) { out.append(rawWord); continue }
            val sp = stressProbs[bi]; val yp = yoProbs[bi]; bi++
            val lower = rawWord.lowercase()
            val haveStress = '+' in lower; val haveYo = 'ё' in lower
            if (haveStress && haveYo) { out.append(rawWord); continue }
            if (!haveStress && haveYo) {
                val yoPos = lower.indices.filter { lower[it] == 'ё' }
                for ((k, p) in yoPos.withIndex()) rawWord = rawWord.substring(0, p + k) + "+" + rawWord.substring(p + k)
                out.append(rawWord); continue
            }
            if (cleanWord in d.exceptions) { out.append(accentuateException(cleanWord, rawWord, haveStress)); continue }
            val stressPred = sp.indices.maxByOrNull { sp[it] } ?: 0
            var stressedVowelIds = listOf(stressPred)
            val passedStress = sp[stressPred] > 0.5f
            var setStress = passedStress && !haveStress
            val yoPred = yp.indices.maxByOrNull { yp[it] } ?: 0
            val yoVowelIds = listOf(yoPred)
            val setYo = yp[yoPred] > 0.5f
            if (haveStress) stressedVowelIds = lower.split("+").map { part -> part.count { it in vowels } }
            val pos = positions(lower, stressedVowelIds, yoVowelIds)
            if (pos.numVowels == 0) { out.append(rawWord); continue }
            for (yoPos in pos.yo) if (yoPos in pos.stress && setYo && lower[yoPos] == 'е')
                rawWord = rawWord.substring(0, yoPos) + (if (rawWord[yoPos].isLowerCase()) 'ё' else 'Ё') + rawWord.substring(yoPos + 1)
            var stressPositions = pos.stress
            if (pos.numVowels == 1) { stressPositions = listOf(pos.firstVowel); setStress = true }
            if (!haveStress && setStress) for ((k, p) in stressPositions.withIndex())
                rawWord = rawWord.substring(0, p + k) + "+" + rawWord.substring(p + k)
            out.append(rawWord)
        }
        return out.toString()
    }

    // ---- user dictionary ----
    private fun userDictPass(sentence: String): String {
        if (userDict.isEmpty()) return sentence
        return wordRe.replace(sentence) { m ->
            val orig = m.value.replace("+", "")
            val value = userDict[orig.lowercase()] ?: return@replace m.value
            val stressIdx = value.indexOf('+')
            var cased = value.replace("+", "")
                .mapIndexed { k, c -> if (k < orig.length && orig[k].isLowerCase()) c.lowercaseChar() else if (k < orig.length) c.uppercaseChar() else c }
                .joinToString("")
            if (stressIdx >= 0) cased = cased.substring(0, stressIdx) + "+" + cased.substring(stressIdx)
            cased
        }
    }
}
