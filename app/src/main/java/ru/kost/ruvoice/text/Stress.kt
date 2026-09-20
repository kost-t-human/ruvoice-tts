package ru.kost.ruvoice.text

import ru.kost.ruvoice.SileroData

interface StressModels {
    fun accentor(words: List<String>): Pair<Array<FloatArray>, Array<FloatArray>>
    fun homo(ids: List<LongArray>, starts: LongArray, ends: LongArray): FloatArray
}

/** morph — таблица морфологии для согласования с прилагательным в gramPass; null — правило выключено. По умолчанию та же,
 * что у нормализатора (SileroModels.data() ставит её раз на процесс; в JVM-тестах без ассета — null). */
class Stress(private val d: SileroData, private val models: StressModels, private val userDict: Map<String, String> = emptyMap(),
             private val rules: Rules = Rules(), private val morph: Morph? = Normalizer.morph, private val yo: YoDict? = YoDict.shared,
             private val hardE: HardE? = HardE.shared) {
    private val vowels = "аоуыэиеяёю"
    private val tok = BertTokenizer(d)
    private val homoWordRe = Regex("(?=.*[а-яё])[а-яё+]+", RegexOption.IGNORE_CASE)
    // JVM \s матчит только ASCII-пробелы; Python \s матчит любой Unicode-пробел (NBSP и т.п.),
    // поэтому класс явно расширен \p{Zs} и прочими Unicode-разделителями.
    // кавычки тоже разделители: у Silero «слово с кавычкой — один токен, и «ё» встаёт не на ту букву («+ёерного»)
    private val splitRe = Regex("([\\s\\p{Zs}\\u0085\\u2028\\u2029\\u001C-\\u001F.,!?;:<>=()/\\\\«»„“”\"'‘’‹›]+)")
    private val nonCyr = Regex("[^А-Яа-яёЁ]")
    private val wordRe = Regex("[а-яё+]+", RegexOption.IGNORE_CASE)

    fun apply(sentence: String): String {
        var s = sentence
        // слова, пришедшие уже с «+» (фраза из словаря замен или ударение в самом тексте): словарь ударений их не трогает,
        // фраза конкретнее слова («обливаясь п+отом» против «потом = пот+ом»)
        val preset = wordRe.findAll(sentence).filter { '+' in it.value }.map { it.value.lowercase() }.toSet()
        if (rules.on("gram")) s = gramPass(s)
        if (rules.on("homo")) s = homographPass(s)
        if (rules.on("accentor")) s = accentorPass(s)
        s = userDictPass(s, sentence, preset)
        // твёрдое [э] в заимствованиях — после всех ударений, чтобы модель и словари видели обычное «е»
        return if (rules.on("hard_e") && hardE != null) hardE.apply(s) else s
    }

    // ---- грамматика: падеж или часть речи по предыдущему слову ----
    private val genGov = setOf("с", "со", "из", "изо", "от", "ото", "у", "до", "без", "безо", "для", "около", "вдоль", "возле",
        "мимо", "после", "кроме", "вокруг", "против", "среди", "из-за", "из-под", "ради", "вместо", "подле", "близ", "накануне",
        "вне", "насчёт", "насчет", "ввиду", "вследствие", "позади", "впереди", "посреди", "сверх", "свыше", "внутри", "внутрь", "вроде",
        "два", "две", "три", "четыре", "полтора", "полторы", "нет")
    /** «мало вод+ы», «много дом+ов»: только род. ед. (без запасного n — «он много сопел» глагол); по narusco 110:5. */
    private val quantGov = setOf("мало", "много", "немного", "немало", "больше", "меньше", "достаточно", "сколько", "столько", "полно")
    /** «воды нет», «времени мало»: количественное слово справа — род. ед. (по narusco 81:16). */
    private val quantNext = setOf("нет", "мало", "много", "немного", "достаточно", "больше", "меньше", "немало", "хватает", "хватало", "хватит")
    /** После глагола эти слова во мн. в ≥93 % (narusco+СинТагРус, n≥6): «заблестели глаз+а», «опустил р+уки». Общее правило
     * «глагол + слово → мн.» держится лишь на 74 % («бояться высот+ы», «дай вод+ы»), поэтому список. */
    private val verbPl = setOf("глаза", "руки", "слова", "ноги", "цены", "голоса", "войска", "слезы", "губы", "звезды", "трубы", "окна",
        "стены", "яйца", "острова", "весла", "колеса", "леса", "ордена", "поля", "свечи", "судьбы")
    private val verbEnd = Regex("[а-яё]+(ет|ит|ут|ют|ат|ят|ешь|ишь|ем|им|ете|ите|л|ла|ло|ли|ть|ти|чь|ай|яй|уй|юй|ой|ей|йте|ся|сь)")
    /** Причастия и прилагательные, которых нет в таблице («сломанной», «кодвусийской»), и местоимения («одной», «той»). */
    private val participleAny = Regex("[а-яё]+((вш|ющ|ущ|ащ|ящ)[а-яё]+|(нн|н|т|ск|ш|щ)(ой|ей|ый|ий))")
    /** Глаголы, управляющие родительным: «достигли л+еса», «лишился гл+аза», «боимся л+еса», «попросил сл+ова». */
    private val genVerb = Regex("[а-яё]*(дости|косн|каса|лиш|бо[иея]|опас|избе|сторон|слуша|проси|спроси|требов|треб|доби|добе|было|прибыло)[а-яё]*")
    private val notVerb = setOf("ли", "или", "бы", "же", "уж", "ль", "ведь", "здесь", "хоть", "чуть", "пусть", "ей", "ней", "ею", "нею", "мной", "мною", "тобой", "тобою",
        "собой", "собою", "ним", "нём", "нем", "тем", "всем", "этим", "одним", "своим", "моим", "твоим", "нашим", "вашим", "каким", "таким",
        "самим", "кем", "чем", "ничем", "никем", "своей", "моей", "твоей", "нашей", "вашей", "всей", "чьей", "самой")
    private val neg = setOf("не", "нет", "ни")
    /** Слово из [verbPl] перед глаголом во мн. ч. — подлежащее: «глаза блестели», «Глаза выглядели» (BERT в начале фразы
     * берёт род. ед.). Не после «не», не после «два/оба» в трёх словах («две костлявые р+уки обняли» — счётная форма) и не
     * после существительного («створки окн+а распахнулись», «у края л+еса вели»). По narusco+Викисловарю+HomographEval 62:2. */
    private val verbPlEnd = Regex("[а-яё]+(ут|ют|ат|ят|ли)(ся|сь)?")
    private val dual = setOf("два", "две", "три", "четыре", "оба", "обе", "полтора")
    /** Глаголов в таблице морфологии нет: слово не из таблицы с глагольным окончанием и не причастие. */
    private fun verbLike(t: String) = t.isNotEmpty() && (morph == null || morph.tags(t) == 0) && verbEnd.matches(t) &&
        !participleAny.matches(t) && !genVerb.matches(t) && t !in notVerb
    private val prepOther = setOf("в", "во", "на", "за", "под", "подо", "через", "про", "сквозь", "о", "об", "обо", "по", "при",
        "к", "ко", "над", "надо", "перед", "передо", "между", "меж")
    /** Причастие в род. п. управляет винительным: «прикрывавшего ворота», «туманящего глаза». */
    private val participle = listOf("вшего", "ющего", "ущего", "ащего", "ящего")
    private val locPrep = setOf("в", "во", "на", "при")
    private val pronouns = setOf("я", "ты", "он", "она", "оно", "мы", "вы", "они")
    private val possessive = setOf("его", "её", "ее", "их")
    /** Местоимения с окончанием прилагательного: «не на чем ча́ю выпить» — не «в чужой крови́». */
    private val pronAdjLike = setOf("чем", "кем", "ничем", "никем", "ним", "нём", "нем", "ней", "ей", "ею", "нею", "мной", "тобой", "собой")
    /** На «-ого/-его» кончаются и местоимения, после которых стоит именительный: «его руки», «у него дела». */
    private val notAdjective = setOf("его", "него", "чего", "кого", "ничего", "никого", "некого", "нечего", "всего", "сего", "много", "немного", "итого",
        "отчего", "оттого")   // «отчего цены» наречие, «отчего дома» прилагательное — BERT прав в обоих, правило нет
    /** «размером с горы», «высотой с дома»: после «с» вин. мн., не родительный. */
    private val sizeWords = setOf("размером", "высотой", "ростом", "величиной", "длиной", "шириной", "толщиной", "весом")
    private val gramWordRe = Regex("[а-яё+-]+", RegexOption.IGNORE_CASE)
    /** После «две/три/четыре» прилагательное во мн., а слово — в род. ед.: «две толстые ноги». */
    private val count = setOf("два", "две", "три", "четыре", "оба", "обе", "полтора", "полторы")
    /** Прилагательное во мн. ч.: цепочка таких перед словом («две короткие толстые ноги») не заслоняет числительное. */
    private val adjPl = Regex("[а-яё]+(ые|ие|ых|их)")
    private val nomPl = Regex("[а-яё]+(ые|ие)")
    /** Причастие во мн. ч. — их нет в таблице морфологии: «почерневшие», «поросшие», «стоящие», «покрытые», «сложенные», «видимые». */
    private val participlePl = Regex("[а-яё]+((вш|ш|щ)ие|(нн|т|м)ые)")
    private val passivePl = Regex("[а-яё]+(нн|т|м)ые")
    /** Прилагательные, управляющие родительным: «лишённые душ+и», «полные вод+ы» (по корпусу 25 из 25). */
    private val genAdjRe = Regex("(лишённ|лишенн|полн)(ый|ая|ое|ые|ого|ой|ых|ым|ыми|ую|ому|ом)")
    /** Фазовые глаголы и «буду»: инфинитив после них только несов. вида («начал обполз+ать», не «обп+олзать»). */
    private val phaseRe = Regex("нач(ал[аио]?|ать|ав|ина[а-яё]+|н[а-яё]+)|ста(л[аио]?|ть|в|н(у|ем|ет|ут|ешь|ете|ь|ьте))|продолж[а-яё]+|" +
        "перест(ал[аио]?|ать|ав|ан[а-яё]+)|прекра[тщ][а-яё]+|конч(ил[аи]?|ать|ай|айте)|" +
        "брос(ил[аи]?|ить|ь|ьте|ив)|приня(лся|лась|лись|ться)|прим(ется|усь|емся|утся)|буд(у|ешь|ет|ем|ете|ут)")

    /** «вдоль стены» → «стен+ы», «за село» → «сел+о», «я ношу» → «нош+у», «вечного города» → «г+орода», «в озера» → «оз+ёра»: слово из
     * таблицы d.gram получает ударение по слову перед ним (между ними только пробелы). Дальше омографы и акцентор
     * его не трогают. Проверено на фразах чужих словарей: по каждой ветке правило право в 85–95 % расхождений с
     * моделью; согласование с прилагательным по окончанию («-ые», «-ой») пробовали — не лучше BERT, по таблице
     * морфологии (agree) — спорит со словарями в 1,5 % сработок. С той же таблицей «все» перед словом, которое
     * бывает только во мн. ч. («все крупные») или согласовано с «все» во мн. («все окна»), становится «вс+е».
     * Ещё: после фазового глагола инфинитив несов. вида («начал обполз+ать»), после «лишённые/полные» род.
     * («полные вод+ы»), после «размером с» вин. мн. («с г+оры»), второй предложный после в/на/при («в глуш+и»).
     * На корпусе GramPassCorpusTest споров со словарями ноль (с поправками gram_pass_overrides.txt). */
    internal fun gramPass(sentence: String): String {
        if (d.gram.isEmpty()) return sentence
        val sb = StringBuilder(sentence)
        var prev = ""; var prev2 = ""; var prev3 = ""; var prev4 = ""; var prevStart = -1; var prevEnd = -1; var offset = 0
        var chainHead = ""   // слово перед цепочкой прилагательных во мн. ч., кончающейся на prev
        val words = gramWordRe.findAll(sentence).toList()
        for ((i, m) in words.withIndex()) {
            val w = m.value.lowercase()
            val e = d.gram[w]
            val adjacent = prevEnd >= 0 && sentence.subSequence(prevEnd, m.range.first).all { it.isWhitespace() }
            val nm = words.getOrNull(i + 1)
            val nxt = if (nm != null && sentence.subSequence(m.range.last + 1, nm.range.first).all { it.isWhitespace() }) nm.value.lowercase() else ""
            var vse = adjacent && prev == "все" && morph != null && Morph.pluralOnly(morph.tags(w.replace("+", "")))
            val prevOffset = offset
            if (e != null) {
                val subjPl = w in verbPl && "p" in e && verbLike(nxt) && verbPlEnd.matches(nxt) && prev !in neg &&
                    prev !in dual && prev2 !in dual && prev3 !in dual && (morph == null || !Morph.isNoun(morph.tags(prev)))
                val pick = when {
                    !adjacent -> if (subjPl) e["p"] else null
                    "i" in e -> if (phaseRe.matches(prev)) e["i"] else null
                    (prev == "под" || prev == "за") && prev2 == "из" -> e["g"] ?: e["n"]   // «из под», «из за» без дефиса
                    prev == "за" && prev2 == "что" -> null                                 // «что за свиньи» — именительный
                    prev == "с" && prev2 in sizeWords -> e["p"]
                    prev in genGov -> e["g"] ?: e["n"]
                    prev in quantGov -> if (prev2 == "не" && (prev == "столько" || prev == "сколько")) null else e["g"]   // «не столько слов+а, сколько…»
                    "l" in e && e["l"] != e["g"] -> loc2Pick(prev, prev2, prev3, e, w)      // «в кров+и» / «ана́лиз кр+ови»
                    prev in locPrep && "l" in e -> e["l"]                                  // «в глуш+и» — второй предложный
                    (prev == "в" || prev == "во") && "g" in e && "p" !in e -> null         // «выйти в учителя» — им. мн.
                    // второй предложный («в пыл+и», «в цвет+у») совпадает с глаголом — омографы из homodict после в/на оставляем BERT
                    prev in prepOther -> e["p"] ?: e["g"] ?: if (prev in locPrep && w in d.homodict) null else e["n"]
                    prev in pronouns -> e["v"]
                    // прилагательное в род. ед. («вечного города», «тёплой стены» не берём: «-ой» и у творительного — «вытер рукой глаза»);
                    // «у самого» — ещё и «у него самого»: «у самог+о глаз+а вылезли», только тут «самого» оставляем BERT
                    prev.endsWith("ого") || prev.endsWith("его") || genAdjRe.matches(prev) ->
                        if (prev in notAdjective || prev.startsWith("сам") && prev2 == "у" || prev.startsWith("котор") || participle.any { prev.endsWith(it) }) null else e["g"] ?: e["n"]
                    prev == "все" && w == "дома" -> null   // «не все дома»: идиома, BERT прав в 7 строках корпуса из 9, согласование в 6
                    w in verbPl && "p" in e && verbLike(prev) && prev2 !in neg && prev3 !in neg && nxt !in neg -> e["p"]   // «не успел сказать сл+ова»
                    subjPl -> e["p"]   // «глаза блестели»
                    nxt in quantNext && "g" in e && "p" in e -> e["g"]
                    morph != null && ("g" in e || "p" in e) -> agree(morph, prev, prev2, prev3, prev4, chainHead, w, e)
                    else -> null
                }
                if (pick != null) {
                    // вариант может быть с «ё» (озера → оз+ёра): переписываем слово, сохраняя регистр букв
                    val raw = m.value; var k = 0
                    val out = StringBuilder(pick.length)
                    for (c in pick) if (c == '+') out.append('+') else { out.append(if (raw[k].isUpperCase()) c.uppercaseChar() else c); k++ }
                    sb.replace(m.range.first + offset, m.range.last + 1 + offset, out.toString()); offset += out.length - raw.length
                    if (prev == "все" && pick == e["p"]) vse = true   // «все окна»: слово согласовано с «все» во мн.
                }
            }
            if (vse) { sb.insert(prevStart + prevOffset + 2, '+'); offset++ }
            chainHead = if (adjacent && adjPl.matches(w) && adjPl.matches(prev)) chainHead else prev
            prev4 = prev3; prev3 = prev2; prev2 = prev; prev = w; prevStart = m.range.first; prevEnd = m.range.last + 1
        }
        return sb.toString()
    }

    /** «высокие стены» → мн., «высокой стены» → род. ед., «эти руки» → мн.: прилагательное (не существительное
     * одновременно — «больной») согласуется со словом в клетке род. ед. (вариант g) или им./вин. мн. (p), но не в
     * обеих; в обеих или ни в одной («вся округа» — им. ед. другой леммы) — молчим.
     * Через слово или предложную группу («покрытые пылью доски», «почерневшие от времени доски», «мокрые от дождя
     * стены») прилагательное или причастие на «-ые/-ие» даёт мн.: BERT рядом с причастием прав, а через слово путает.
     * Причастий в таблице нет — узнаём по суффиксу; рядом с ним молчим («искавшие души» — дополнение). По корпусу
     * GramPassCorpusTest ~100 сработок без споров, «две поросшие лесом горы» — род. ед. по числительному. */
    /** Глаголы движения и превращения: после них «в двери», «в тени» — вин. мн., не второй предложный. */
    private val accVerb = Regex("(преврат|превращ|брос|попа[лдсв]|сун|стуч|стукн|закова|заков|ломи|влет|вбе[жг]|ворв|кинул|швыр|толкн|во(шёл|шел|шла|шли|йти|йд)|заман|улов|пойма|разворач|вгляд|загляд)[а-яё]*")
    /** Прилагательное или причастие в предл. ед. по окончанию — для слов, которых нет в таблице морфологии («потрясённом»). */
    private val prpAdj = Regex("[а-яё]+(ом|ем|ой|ей)(ся)?(-то)?")

    /** Слово со вторым предложным с иным ударением (кр+ови / кров+и): после «в/на/при» — l («в кров+и»), но после глагола
     * движения это вин. мн. («бросилась в дв+ери»); через прилагательное в предл. — l («в чужой кров+и», «в её кров+и»),
     * а без предлога перед таким прилагательным молчим («сосновом лесу» — не знаем, что слева); иначе всегда g
     * («ана́лиз кр+ови»). У слов, совпадающих с глаголом («бреду́»), g нет — без «в/на» молчим. Фразы системного
     * словаря («превратились в т+ени») применяются к тексту раньше и слово с «+» сюда не попадает. */
    private fun loc2Pick(prev: String, prev2: String, prev3: String, e: Map<String, String>, w: String): String? {
        // глагол движения может стоять и через слово: «сунул голову в дв+ери»
        if (prev in locPrep) return if (accVerb.matches(prev2) || accVerb.matches(prev3)) e["g"] else e["l"]
        if (prev == "и" && prev3 in locPrep) return e["l"]   // «в крови и гряз+и»
        val adj = prev in possessive || (if (morph != null && morph.tags(prev) != 0) adjLoc(morph, prev, w) else prev !in pronAdjLike && prpAdj.matches(prev))
        if (!adj) return e["g"]
        if (prev2 in locPrep) return e["l"]
        val adj2 = prev2 in possessive || prpAdj.matches(prev2)
        if (prev3 in locPrep && adj2) return e["l"]
        return if (prev2.isNotEmpty() && !adj2) e["g"] else null   // «у толстой ц+епи»; «толстой цепи» без левого контекста — молчим
    }

    /** Прилагательное согласовано со словом в предл. ед. («густой тени», «самом лесу»), а не в им./вин. мн. («открытые двери»). */
    private fun adjLoc(m: Morph, adj: String, w: String): Boolean {
        val ta = m.tags(adj.replace("+", "")); val tw = m.tags(w)
        if (!Morph.isAdjective(ta) || !Morph.isNoun(tw)) return false
        if (Morph.adjCases(ta, null, true).any { it == Case.NOM || it == Case.ACC }) return false
        return Morph.genders(tw).ifEmpty { Gender.values().toList() }.any { Case.PRE in Morph.adjCases(ta, it, false) }
    }

    private fun agree(m: Morph, prev: String, prev2: String, prev3: String, prev4: String, chainHead: String, w: String, e: Map<String, String>): String? {
        if (prev == "всё") return null   // в таблице «ё» = «е», а «всё» — не «все»
        val tw = m.tags(w)
        if (!Morph.isNoun(tw)) return null
        val ta = m.tags(prev.replace("+", ""))
        if (!Morph.isAdjective(ta) || Morph.isNoun(ta)) {
            // одушевлённое без вин. мн. в таблице (учителя, врача): после существительного — род. ед. («задача уч+ителя»),
            // им. мн. учителя́ после существительного почти не бывает (СинТагРус: 273 против 11, Silero тут ошибается в 18 %)
            // существительное только в им./вин. мн. («маги учителя», «были мастера» — «были» и есть быль) — молчим
            if ("p" !in e) return if (prev != "были" && Morph.isNoun(ta) && !Morph.isAdjective(ta) && Morph.animate(tw) &&
                (Morph.nounCases(ta, false).isNotEmpty() || Morph.nounCases(ta, true).any { it != Case.NOM && it != Case.ACC })) e["g"] else null
            if (prev in genGov || prev in prepOther) return null
            val far = prev2 in genGov || prev2 in prepOther
            val cand = if (far) prev3 else prev2
            // «бревенчатые стены терема»: прилагательное согласовано с соседом во мн. — не наше
            if (!far && Morph.isNoun(ta) && Morph.nounCases(ta, true).any { it == Case.NOM || it == Case.ACC }) return null
            val tc = m.tags(cand)
            // действительное причастие берём только за предлогом: «прослужившие и года», «искавшие души» — дополнение
            val adjPlural = cand != "все" && nomPl.matches(cand) && if (tc == 0) (if (far) participlePl else passivePl).matches(cand) else Morph.isAdjective(tc) && !Morph.isNoun(tc)
            return if (!adjPlural) null else if ((if (far) prev4 else prev3) in count) e["g"] else e["p"]
        }
        if (chainHead in count) return e["g"]
        fun fit(plural: Boolean): Boolean {
            val noun = Morph.nounCases(tw, plural) intersect if (plural) setOf(Case.NOM, Case.ACC) else setOf(Case.GEN)
            val genders: List<Gender?> = if (plural) listOf(null) else Morph.genders(tw).ifEmpty { Gender.values().toList() }
            return genders.any { g -> Morph.adjCases(ta, g, plural).any { it in noun } }
        }
        val sg = fit(false); val pl = fit(true)
        return if (sg && !pl) e["g"] else if (pl && !sg) e["p"] else null
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
            for ((i, h) in neural.withIndex()) {
                h.pred = d.homodict.getValue(h.word.lowercase()).sorted()[if (probs[i] > 0.5f) 1 else 0]
            }
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

    /** Безударные приставки перед дефисом: «в+о-п+ервых» модель тянет «воо…» (о 8 кадров + дефис 7 почти тишины
     * против 5 у «во-п+ервых»), у Silero Stress так же. «кто-нибудь», «кое-как» ударение на первой части держат. */
    private val hyphenPrefix = setOf("во", "по", "из")

    private fun tokenize(sentence: String): Triple<List<String>, List<String>, List<Boolean>> {
        val tokens = ArrayList<String>(); val inputs = ArrayList<String>(); val mask = ArrayList<Boolean>()
        for (word in splitKeep(sentence)) {
            val parts = word.split("-")
            val cur: List<String>; val curMask: List<Boolean>
            if (parts.size == 1) { cur = parts; curMask = listOf(true) }
            else {
                cur = parts.dropLast(1).map { "$it-" } + parts.last()
                curMask = parts.dropLast(1).map { it.lowercase() !in hyphenPrefix } + (parts.last() != "то")
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
            // бесспорная «ё» по словарю раньше модели: «ежик» → «ёжик», ударение на «ё» поставит ветка haveYo ниже;
            // слово из пользовательского словаря не трогаем, иначе он его не найдёт (как и setYo)
            if (rules.on("yo") && yo != null && '+' !in rawWord && 'ё' !in rawWord && 'Ё' !in rawWord && cleanWord !in userDict)
                yo.restore(rawWord.trimEnd('-'))?.let { rawWord = it + rawWord.substring(rawWord.trimEnd('-').length) }
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
            val setYo = yp[yoPred] > 0.5f && cleanWord !in userDict   // «бытие быти+е» в словаре: модель не ставит «ё», иначе словарь слово не найдёт
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
    private fun userDictPass(sentence: String, original: String, preset: Set<String>): String {
        if (userDict.isEmpty()) return sentence
        // ключ — слово из исходного текста: проходы выше могли поставить «ё» («узна+ёт» из homodict, «бёдра» из gram-таблицы),
        // и «узнает» из словаря не нашлось бы; авторское «ё» в тексте так и остаётся другим словом
        val src = wordRe.findAll(original).map { it.value.replace("+", "").lowercase() }.toList()
        var i = -1
        return wordRe.replace(sentence) { m ->
            i++
            if (m.value.lowercase() in preset) return@replace m.value
            val orig = m.value.replace("+", "")
            val key = src.getOrNull(i)?.takeIf { it.replace('ё', 'е') == orig.lowercase().replace('ё', 'е') } ?: orig.lowercase()
            val value = userDict[key] ?: return@replace m.value
            val stressIdx = value.indexOf('+')
            var cased = value.replace("+", "")
                .mapIndexed { k, c -> if (k < orig.length && orig[k].isLowerCase()) c.lowercaseChar() else if (k < orig.length) c.uppercaseChar() else c }
                .joinToString("")
            if (stressIdx >= 0) cased = cased.substring(0, stressIdx) + "+" + cased.substring(stressIdx)
            cased
        }
    }
}
