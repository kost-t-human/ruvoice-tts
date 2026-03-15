package ru.kost.ruvoice.text

import ru.kost.ruvoice.SileroData

interface StressModels {
    fun accentor(words: List<String>): Pair<Array<FloatArray>, Array<FloatArray>>
    fun homo(ids: List<LongArray>, starts: LongArray, ends: LongArray): FloatArray
}

class Stress(private val d: SileroData, private val models: StressModels, private val userDict: Map<String, String> = emptyMap()) {
    private val vowels = "аоуыэиеяёю"
    private val tok = BertTokenizer(d)
    private val homoWordRe = Regex("(?=.*[а-яё])[а-яё+]+", RegexOption.IGNORE_CASE)
    // JVM \s матчит только ASCII-пробелы; Python \s матчит любой Unicode-пробел (NBSP и т.п.),
    // поэтому класс явно расширен \p{Zs} и прочими Unicode-разделителями.
    private val splitRe = Regex("([\\s\\p{Zs}\\u0085\\u2028\\u2029\\u001C-\\u001F.,!?;:<>=()/\\\\]+)")
    private val nonCyr = Regex("[^А-Яа-яёЁ]")
    private val wordRe = Regex("[а-яё+]+", RegexOption.IGNORE_CASE)

    fun apply(sentence: String): String = userDictPass(accentorPass(homographPass(sentence)))

    // ---- homosolver ----
    private fun homographPass(sentence: String): String {
        data class Hit(val start: Int, val end: Int, val word: String, val marked: String)
        val hits = homoWordRe.findAll(sentence).mapNotNull { m ->
            val w = m.value.lowercase()
            if (w !in d.homodict) null
            else Hit(m.range.first, m.range.last + 1, m.value,
                sentence.substring(0, m.range.first) + " [HOMO] " + m.value + " [/HOMO] " + sentence.substring(m.range.last + 1))
        }.toList()
        if (hits.isEmpty()) return sentence
        val ids = hits.map { tok.encode(it.marked) }
        val starts = LongArray(hits.size) { ids[it].indexOf(d.bertHomoStart.toLong()).toLong() }
        val ends = LongArray(hits.size) { ids[it].indexOf(d.bertHomoEnd.toLong()).toLong() }
        val probs = models.homo(ids, starts, ends)
        val sb = StringBuilder(sentence)
        var offset = 0
        for ((i, h) in hits.withIndex()) {
            // torch.round: half-to-even, ровно 0.5 округляется в 0.
            val pred = if (probs[i] > 0.5f) 1 else 0
            var variant = d.homodict.getValue(h.word.lowercase()).sorted()[pred]
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
