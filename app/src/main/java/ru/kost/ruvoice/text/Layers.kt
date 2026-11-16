// SPDX-License-Identifier: CC-BY-NC-SA-4.0
// Белорусский текстовый слой для RuVoice TTS (см. LICENSE репозитория).
package ru.kost.ruvoice.text

import ru.kost.ruvoice.SileroData
import java.util.Locale

/** Слова, по которым [SentenceType] узнаёт вопрос: у каждого языка свои. У штатной русской модели они лежат
 * в данных (stress.bin: wh_forms, leading_fillers, tag_patterns), у второй языка — здесь, в его слое. */
interface QuestionLists {
    val whForms: Set<String>
    val leadingFillers: Set<String>
    val tagRe: Regex
    /** Союз альтернативного вопроса («или», белорусские «ці», «або»); ищется в тексте в нижнем регистре. */
    val altRe: Regex
    /** Куски фразы (нижний регистр), где вопросительное слово не ищется: белорусские «калі ласка» и всё после начального
     * «ці». null — таких нет (русский). */
    val notWhRe: Regex? get() = null
    /** Слово (нижний регистр) похоже на глагол: общий вопрос с глаголом правило question не размечает ([Marks.question]).
     * По умолчанию — русский [verbLike]. */
    fun verb(w: String, morph: Morph?): Boolean = verbLike(w, morph)
}

/**
 * Язык текста: выбирается по полю `lang` пака, которым читается фраза, — а не по сборке приложения.
 * У каждого языка своя подготовка текста (числа, сокращения, латиница) и свои ударения: за ударения
 * отвечает [Stress], которому слой передаётся вместе со словарём (в нём и системный список пака).
 *
 * Новый язык CIS-модели добавляется ещё одной строкой в [Layers]: в таблице `ru`, `be`, `uk`.
 */
interface TextLayer {
    /** Код языка, как в `pack.json`: `ru`, `be`, `uk`. */
    val code: String

    /** Язык и страна голосов в TTS API: ISO-3 из неё система спрашивает язык и выбирает голос. */
    val locale: Locale

    /** Ключи [Rules], которые слой не читает: в «Правилах» на вкладке этого языка их нет. Пусто — читает все. */
    val unsupported: Set<String> get() = emptySet()

    /** Слово словаря (ключ и значение) так, как его увидит слой после [prepare]: белорусский снимает апостроф. */
    fun dictWord(w: String): String = w

    /** Текст запроса к модели: числа, сокращения, латиница, знаки — и фильтр по символам модели [allowed]. */
    fun prepare(text: String, allowed: String, rules: Rules): String

    /** Списки для классификации вопроса (см. [QuestionLists]). */
    fun questions(d: SileroData): QuestionLists

    /** Ударения языка: словари (с системным пака) поверх акцентора пака; акцентор — по правилу accentor из [rules].
     * null — русский конвейер [Stress] (таблицы, омографы, «ё»). */
    fun stress(models: StressModels, userDict: Map<String, String>, rules: Rules): ((String) -> String)? = null

    /** Имя буквы, когда запрос — одна буква (правило letter_name, эхо ввода [LetterEcho]): [lone] — в запросе
     * ничего, кроме буквы, [end] — имя последнее во фразе. null — буквы в таблице языка нет. */
    fun letterName(c: Char, lone: Boolean = false, end: Boolean = true): String?

    /** Имя одиночного знака (клавиша «#», знак под курсором); [punct] — эхо удаления [LetterEcho]: там называем
     * и обычную пунктуацию. null — не называем, знак идёт как есть. */
    fun symbolName(c: Char, punct: Boolean = false): String?

    /** Дефис в конце строки, который правило dehyphen оставляет ([Splitter.paragraphs]): дефисное написание («по-\nрусски»,
     * «кто-\nто»), а не перенос слова. Части — в нижнем регистре; false — склеивать (заглавную после переноса Splitter
     * не склеивает и так). */
    fun keepHyphen(before: String, after: String): Boolean = false

    /** Слово [word] с точкой в конце куска — сокращение языка, и кусок [next] за ним — продолжение того же предложения
     * ([Splitter.sentences]). Общие русские и белорусские сокращения Splitter знает сам; здесь — только свои слоя.
     * [before] — текст куска до сокращения (с открывающей скобкой или кавычкой, если она вплотную). */
    fun keepsSentence(word: String, next: String, before: String): Boolean = false

    /** Минус «−» (U+2212) в пробелах — знак формулы, его читает слой ([Splitter.sentences] не делает из него тире). */
    val mathMinus: Boolean get() = false
}

/** Русский слой — ровно то, что было до появления второго языка: [Normalizer] и данные stress.bin. */
object RuLayer : TextLayer {
    override val code = "ru"
    override val locale = Locale("ru", "RU")
    override fun prepare(text: String, allowed: String, rules: Rules): String = Normalizer.prepare(text, allowed, rules)
    override fun questions(d: SileroData): QuestionLists = d
    override fun letterName(c: Char, lone: Boolean, end: Boolean) = if (lone) Abbrev.loneLetterName(c, end) else Abbrev.letterName(c)
    override fun symbolName(c: Char, punct: Boolean) = (if (punct) LetterEcho.PUNCT_NAMES[c] else null) ?: SymbolNames.of(c)
    // review round 1 п.3: короткая приставка/предлог перед дефисом («по-\nрусски», «из-\nза», «кое-\nкто», «во-\nпервых»)
    // или частица после него («кто-\nто»)
    private val hyphenPrefixes = setOf("по", "кое", "кой", "из", "во", "в", "за", "на", "под", "над",
        "от", "до", "обо", "о")
    private val hyphenParticles = setOf("то", "либо", "нибудь", "таки", "ка", "де")
    override fun keepHyphen(before: String, after: String) = before in hyphenPrefixes || after in hyphenParticles
    // «согласно прил. 2», «20 тыс. чел. и», «(ум. 1945)», «(род. 1890)», «в род. падеже» — не конец фразы перед строчной или
    // цифрой («Пришли 300 чел. Потом…» — конец)
    private val abbrStopsBeforeLowerOrDigit = setOf("прил", "чел", "ум", "род")
    private val yearNomAheadRe = Regex("""^\d+\s+год(?![\p{L}])""")
    // «пос.» перед заглавной — название посёлка («в пос. Лесной»), как однобуквенное «г. Москва», которое не режет sentenceEnd;
    // но перед словом, которым начинают фразу, — конец («Мы приехали в пос. Он был пуст.»)
    private val sentenceStarts = setOf("Он", "Она", "Оно", "Они", "Я", "Мы", "Вы", "Ты", "А", "Но", "И", "Да", "Нет", "Там", "Тут",
        "Здесь", "Дальше", "Потом", "Затем", "Теперь", "Это", "Так", "Вот")
    // перед «ок.» в реплике: «Ну ок. 2 часа», «Хорошо, ок. 10 раз»
    private val okReplyWords = setOf("ну", "да", "ладно", "хорошо", "окей", "ага", "угу", "так", "ок", "отлично", "понятно",
        "конечно", "договорились", "давай", "принято", "супер")
    // после «,», «—», «:» — «около», если за числом единица, год или диапазон лет, как в энциклопедии («Менделя, ок. 2075 г.»,
    // «(431 — ок. 350 до н. э.)», «Высота — ок. 2000 м», «(ок. 45 — ок. 127)», «литография, ок. 1488,»); «Хорошо, ок. 10 раз»,
    // «— Ок. 5 минут» — реплика. Список — по корпусу Leipzig и books/lib (слова «лет», «человек» после «, ок. N» там не
    // встретились, в репликах они вероятнее). «т» нет: «ок.» перед «10 т.» нормализатор не раскрывает (ревью ru-fixes-2 M-4)
    private val okMeasureAheadRe = Regex(
        """^\d[\d\s]*(?:[.,]\d+)?\s*(?:(?:гг?|тыс|чел)\.|года(?![\p{L}])|до\s+н\.|н\.\s*э|(?:км|м|мм|см|кг|г|га|л|млн|млрд)(?![\p{L}])|[%°)])""" +
            // «— ок. 397/400)» — нет: дробь через «/» раскрывается раньше «ок.», и «ок» осталось бы без паузы
            """|^\d{3,4}\s*(?:[—–\-]\s*(?:ок\.\s*)?\d|[,;]|$)""")
    override fun keepsSentence(word: String, next: String, before: String) = word.replaceFirstChar(Char::lowercaseChar).let { w ->
        val c = next.firstOrNull()
        when {
            w == "пос" -> next.takeWhile(Char::isLetter) !in sentenceStarts
            // «ок» перед строчной — одна фраза («Ну ок. давай»), перед заглавной — конец реплики («ок» без условия Splitter
            // не берёт, review t17 п.6). Перед цифрой — «около» («длина ок. 100 км», «(ок. 837 г.)», «М-4 ок. 300 км»), кроме
            // реплики: «ок.» в начале фразы, после «ну/да/ладно…», «,», «—», «:» или открывающей кавычки («— Ок. 5 минут» — не
            // «около пяти»)
            // слово реплики берём и через знак («Хорошо, ок. 10 км», «— Ну, ок. 5 кг»), тире в начале куска — тоже реплика
            // («— Ок. 100 км, — ответил»; ревью ru-fixes-2 I-3)
            w == "ок" -> c?.isLowerCase() == true || c?.isDigit() == true && before.trimEnd().let { b ->
                val core = b.trimEnd(',', '—', '–', '-', ':', ' ')
                // тире в начале куска — реплика, если «Ок.» с заглавной; строчное «— ок. 60 н. э.)» — второй край дат жизни
                // после «до н.э.», где кусок разрезан
                b.isNotEmpty() && (core.trimStart('—', '–', '-', ' ').isNotEmpty() || word[0].isLowerCase()) &&
                    core.takeLastWhile(Char::isLetter).lowercase() !in okReplyWords &&
                    (b.last() !in ",—–-:«\"„" || b.last() in ",—–-:" && okMeasureAheadRe.containsMatchIn(next))
            }
            // «род.» перед цифрой — только в пометке: в скобках, после знака или имени не первым словом фразы; «Древний род. 1200
            // лет истории», «Его род. 5 веков правил» — конец фразы, как было (ревью ru-fixes-2 M-1)
            w == "род" && c?.isDigit() == true -> !yearNomAheadRe.containsMatchIn(next) && before.trimEnd().let { b ->
                b.isEmpty() || b.last() in "(),;:—–-" || b.count { it == '(' } > b.count { it == ')' } ||
                    b.takeLastWhile(Char::isLetter).let { x -> x.isNotEmpty() && x[0].isUpperCase() && b.dropLast(x.length).isNotBlank() }
            }
            // «…знатный род. 1890 год был трудным» — год именительным начинает фразу
            else -> w in abbrStopsBeforeLowerOrDigit && c?.let { it.isLowerCase() || it.isDigit() } == true && !yearNomAheadRe.containsMatchIn(next)
        }
    }
}

/** Белорусский слой: [BelNormalizer] (числа словами, «!» → «.», апостроф, фильтр по символам пака). */
object BeLayer : TextLayer {
    override val code = "be"
    override val locale = Locale("be", "BY")
    /** Русские правила без белорусского аналога: русские ударения ([Stress]: таблицы, омографы, «ё», твёрдое «э», приставка
     * через пробел).
     * Логическое ударение (focus, exclaim, question) — тоже: у белорусских голосов (cis_be, Pack.types = false) входа
     * focus_mask нет, метка `*слово*` до модели не доходит ([ru.kost.ruvoice.SileroModels.synthesize]). */
    override val unsupported = setOf(
        "gram", "first_pl", "homo", "yo", "hard_e", "prefix_space", "focus", "exclaim", "question", "class_letter")
    override fun prepare(text: String, allowed: String, rules: Rules): String = BelNormalizer.prepare(text, allowed, rules)
    override fun questions(d: SileroData): QuestionLists = BE_QUESTIONS
    override fun dictWord(w: String): String = w.replace(BelNormalizer.APOSTROPHE, "")
    override fun stress(models: StressModels, userDict: Map<String, String>, rules: Rules) =
        LangStress(models, userDict, rules.on("accentor"))::apply
    /** Белорусские имена букв, латиница — как в аббревиатурах [Latin]; «+» перед первой гласной, как у русских.
     * Второе слово («нескладовае») ударение получает от акцентора. */
    override fun letterName(c: Char, lone: Boolean, end: Boolean) = (BE_LETTERS[c.lowercaseChar()] ?: Latin.letterName(c))?.let { n ->
        val i = n.indexOfFirst { it in "аеёіоуыэюя" }
        if (i < 0) n else n.substring(0, i) + "+" + n.substring(i)
    }
    /** Как у русского: пунктуация — только в эхе ([punct]), одиночный знак без эха — пауза; прочие (стрелки, валюты) —
     * белорусскими именами [SymbolNames.ofBe] (своя таблица и CLDR). */
    override fun symbolName(c: Char, punct: Boolean) = (if (punct) BE_SYMBOLS[c] else null) ?: SymbolNames.ofBe(c)
    override fun keepHyphen(before: String, after: String) = BE_HYPHEN.matches("$before-$after")
}

// Дефисные написания по GrammarDB (slovy-2008): «па-» — только у наречий на -ску, -цку, -му, -чы («па-беларуску», «па-мойму»,
// «па-воўчы») и «па-першае», «па-за», «па-над»: иначе «па-\nмяць», «па-\nчатак» — перенос (русское «по» оставляет любое);
// «абы-» — у местоимений («абы-хто», но «абыякавы», «абыход»); «з-за», «з-пад»; частицы «-небудзь», «-колечы», «-кольвек»,
// «-такі». «-ка», «-тка», «-та» нет: «рэч-\nка», «ні-\nтка», «ха-\nта» — перенос
private val BE_HYPHEN = Regex("з-.+|па-(?:.*(?:ску|цку|му|чы)|першае|другое|трэцяе|чацвёртае|пятае|шостае|сёмае|восьмае|дзявятае|" +
    "дзясятае|за|над)|абы-(?:хто|каго|каму|кім|што|чаго|чаму|чым|дзе|куды|калі|адкуль|колькі|як|які|якая|якое|якія|якога|якой|" +
    "якую|якім|якіх|чый|чыя|чыё|чые)|.+-(?:небудзь|колечы|кольвек|кольвечы|такі)")

// формы не проверены носителем; апостроф в «двукроп'е» BelNormalizer снимает, слово остаётся целым: «двукропе»
private val BE_SYMBOLS = mapOf(
    ',' to "коска", '.' to "кропка", '!' to "клічнік", '?' to "пытальнік", ':' to "двукроп'е", ';' to "кропка з коскай",
    '—' to "працяжнік", '–' to "кароткі працяжнік", '-' to "злучок", '…' to "шматкроп'е", '"' to "двукоссе",
    '«' to "адкрываецца двукоссе", '»' to "закрываецца двукоссе", '(' to "адкрываецца дужка", ')' to "закрываецца дужка")

private val BE_LETTERS = mapOf(
    'а' to "а", 'б' to "бэ", 'в' to "вэ", 'г' to "гэ", 'д' to "дэ", 'е' to "е", 'ё' to "ё", 'ж' to "жэ", 'з' to "зэ",
    'і' to "і", 'й' to "і нескладовае", 'к' to "ка", 'л' to "эль", 'м' to "эм", 'н' to "эн", 'о' to "о", 'п' to "пэ",
    'р' to "эр", 'с' to "эс", 'т' to "тэ", 'у' to "у", 'ў' to "у нескладовае", 'ф' to "эф", 'х' to "ха", 'ц' to "цэ",
    'ч' to "чэ", 'ш' to "ша", 'ы' to "ы", 'ь' to "мяккі знак", 'э' to "э", 'ю' to "ю", 'я' to "я")

/** Списки белорусского слоя: те же ключи, что у русского в silero_be.json, — перенесены сюда, потому что
 * вторая языка нет в stress.bin, и данные модели на него не распространяются. */
private object BE_QUESTIONS : QuestionLists {
    // SentenceType сравнивает слова после своей нормализации (ё → е): списки — в том же виде, иначе «чыё» не находилось
    private fun norm(s: Set<String>) = s.map { it.replace('ё', 'е') }.toSet()
    override val whForms = norm(setOf(
        "хто", "што", "дзе", "калі", "чаму", "навошта", "як", "які", "якая", "якое", "якія", "чый", "чыя",
        "чыё", "чыі", "колькі", "куды", "адкуль", "каторы", "чым", "каго", "чаго"))
    override val leadingFillers = norm(setOf(
        "ну", "вось", "а", "дык", "мо", "мабыць", "нават", "проста", "значыць", "так", "але", "ці"))
    // хвост — в конце фразы, как у русских tag_patterns, но только после запятой: «Ты так жа думаеш?», «Гэта праўда?»,
    // «Гэта не праўда?» — общие вопросы
    override val tagRe = Regex(",\\s*(?:праўда|не\\s+праўда|ня\\s+праўд[аы]|ці\\s+не\\s+так|так\\s+жа)\\s*\\?$", RegexOption.IGNORE_CASE)
    // вводные перед частицей «ці» в начале: «А ці ведаеш?», «Ну, ці прыйдзеш?»; «Так ці не?», «Проста ці складана?» —
    // альтернатива
    private val starters = (leadingFillers - "ці" - "так" - "проста").joinToString("|")
    // «або» — альтернатива; «ці» — только между словами («чай ці каву?»): в начале (и после вводного), после тире и запятой
    // это частица общего вопроса («— Ці прыйдзеш?», «А ці ведаеш?», «Скажы, ці прыйдзеш?»). Границы — lookaround: \b в
    // JDK 19+ кириллицу не видит; просмотр назад ограниченной длины и без захвата — ICU на Android
    override val altRe = Regex("(?<![а-яёіў])або(?![а-яёіў])|" +
        "(?<=[а-яёіў]\\s)(?<!^[^а-яёіў]{0,3}(?:(?:$starters)[^а-яёіў]{1,3})?(?:$starters)\\s)ці(?![а-яёіў])")
    // «калі ласка» — не вопрос «калі?»; фраза с «ці» в начале — общий вопрос, «што», «дзе» дальше — придаточное:
    // «Ці ведаеш ты, што заўтра свята?». Повтор вводных — притяжательный «*+»: без него JVM на «ну »×200 падала
    // StackOverflowError (повтор группы — рекурсия), ICU его знает
    override val notWhRe = Regex("(?<![а-яёіў])калі\\s+ласка(?![а-яёіў])|^[^а-яёіў]*(?:(?:$starters)[^а-яёіў]+)*+ці(?![а-яёіў]).*")
    // Глагольные окончания (сверены по GrammarDB): 2 л. -еш, -аш, -іш; 1 мн. -аем, -уем, -дзем; 2 мн. -аеце, -іце, -йце; прош. -ў,
    // -ла, -лі; инфинитив и 3 л. -ць; 3 ед. -уе, -ее; возвратные -ся, -цца. Нет 3 ед. на -е, -ае, -а («ідзе», «чытае», «можа»)
    // и 1 мн. на -ім, -ам: совпадают с падежами («сіняе», «новым», «братам»). Род. мн. на -аў («студэнтаў») — ложный глагол:
    // прошедшее на -аў («сказаў», «ведаў») в вопросе чаще. «-ццаць» — числительные
    private val verbRe = Regex("ёсць|[а-яёіў]+(?:[аяеэіыуш]л[аоі]|[аяеэіыу]ў|шоў|[еэаіы]ш|[аеуюяэ]ем|дзём|[уй]дзем|" +
        "[аеуюяэ]еце|яце|[іы]це|йце|(?<!цц)аць|[яеэіыуюо]ць|[уеюэ]е|йсці|гчы|ся|цца)")
    private val notVerb = setOf("наш", "ваш", "уся", "ўся", "хоць", "калі", "маць", "памяць", "мала", "пяць", "дзевяць", "дзесяць")
    // морфология русская, но то же написание — то же существительное: «з чаем», «канала»
    override fun verb(w: String, morph: Morph?) = (morph == null || morph.tags(w) == 0) && verbRe.matches(w) && w !in notVerb
}

/** Украинский слой: [UkNormalizer], ударения [LangStress] с [UK_STRESS]. */
object UkLayer : TextLayer {
    override val code = "uk"
    override val locale = Locale("uk", "UA")
    /** Те же, что у белорусского: русских ударений нет, у cis-голосов нет входа focus (Pack.types = false). */
    override val unsupported = BeLayer.unsupported
    override fun prepare(text: String, allowed: String, rules: Rules): String = UkNormalizer.prepare(text, allowed, rules)
    override fun questions(d: SileroData): QuestionLists = UK_QUESTIONS
    /** «м'ясо» в словаре — «мясо»: так слово ищет [LangStress] (нормализатор ставит «ъ», поиск идёт без него). */
    override fun dictWord(w: String): String = w.replace(UkNormalizer.APOSTROPHE, "")
    override fun stress(models: StressModels, userDict: Map<String, String>, rules: Rules) =
        LangStress(models, userDict, rules.on("accentor"), UK_STRESS)::apply
    /** Украинские имена букв; латиница — по-украински ([UK_LATIN]). «+» перед первой гласной, если в имени его нет. */
    override fun letterName(c: Char, lone: Boolean, end: Boolean) =
        (UK_LETTERS[c.lowercaseChar()] ?: UK_LATIN[c.lowercaseChar()])?.let { n ->
            val i = n.indexOfFirst { it in UK_STRESS.vowels }
            if ('+' in n || i < 0) n else n.substring(0, i) + "+" + n.substring(i)
        }
    /** Пунктуация — только в эхе ([punct]), прочие знаки — [SymbolNames.ofUk] (своя таблица и CLDR uk). */
    override fun symbolName(c: Char, punct: Boolean) = (if (punct) UK_SYMBOLS[c] else null) ?: SymbolNames.ofUk(c)
    override fun keepHyphen(before: String, after: String) = UK_HYPHEN.matches("$before-$after")
    /** Украинские сокращения ([UkNormalizer]): строчными и с заглавной в начале фразы («Див. також»), не капсом. */
    override fun keepsSentence(word: String, next: String, before: String) = word.replaceFirstChar(Char::lowercaseChar).let { w ->
        val c = next.firstOrNull()
        w in UK_ABBR_STOPS || c?.isLowerCase() == true && w in UK_ABBR_STOPS_BEFORE_LOWER ||
            // «до 18 год. 30 хв.» — перед цифрой тоже
            w == "год" && c?.isDigit() == true ||
            // «тел.» — перед номером («тел. 044…», «тел. +380…», «тел. (044)…») или строчной; перед заглавной — конец фразы
            w == "тел" && c != null && (c.isLowerCase() || c.isDigit() || c in "+(")
    }
    /** «x − y» — «ікс мінус ігрек»; минус, не съеденный формулой, [UkNormalizer] возвращает в тире. */
    override val mathMinus = true
}

// перед номером, назвою, именем: «просп. Перемоги», «буд. 5», «доц. Петренко», «ім. Шевченка», «Див. Додаток 2», «та ін. Потім»
// (точку сокращения в конце фразы сохраняет UkNormalizer)
private val UK_ABBR_STOPS = setOf("просп", "буд", "смт", "розд", "доц", "пров", "дод", "вип", "ім", "підп", "стор", "мкрн",
    "ін", "див")
// и концом предложения бывают («Зібрали 5 тис. Потім…», «Ціна 100 грн. Тому…», «о 5 год. Потім»): не режем только перед
// строчной — «1,2 тис. км.», «о 5 год. ранку», «5 млн куб. м.», «на 5 пов. будинку» («Квартира на 5 пов. Ціна…» — две фразы)
private val UK_ABBR_STOPS_BEFORE_LOWER = setOf("тис", "грн", "рр", "год", "ос", "чол", "екз", "куб", "пог", "пов")

// Дефисные написания по правопису 2019: «по-» — у наречий на -ськи, -цьки, -зьки и -ому/-єму («по-українськи»,
// «по-моєму», «по-новому») и «по-перше…по-десяте»; «будь-», «казна-», «хтозна-», «бозна-»; «з-за», «з-під», «з-поміж»,
// «з-поза», «з-понад», «з-посеред», «з-попід», «з-проміж»; частицы «-небудь», «-таки», «-от» («як-от»). «аби…» пишется слитно
// («абихто», «абиякий»). «-то» — только после списка слов («тому-то», «якби-то»): слов на «-то» много («міс-\nто», «бага-\nто»,
// «жи-\nто»), неопределённое «-сь» пишется слитно. Голого «-ки» нет: «по-\nмилки», «по-\nкупки» — перенос. Не проверено носителем.
// ponytail: «по-…му» ловит и перенос («по-\nвному», «по-\nчому»), как у белорусского и русского; пополнять по жалобам
private val UK_HYPHEN = Regex("по-(?:.*(?:ськи|цьки|зьки|му)|перше|друге|третє|четверте|п[’'`´ʼ‘]?яте|шосте|сьоме|восьме|" +
    "дев[’'`´ʼ‘]?яте|десяте)|(?:будь|казна|хтозна|бозна)-.+|з-(?:за|під|поміж|поза|понад|посеред|попід|проміж)|" +
    ".+-(?:небудь|таки|от)|(?:тому|якби|отож|отже|що|ось|оце|так|тим|як)-то", RegexOption.IGNORE_CASE)

// не проверено носителем
private val UK_SYMBOLS = mapOf(
    ',' to "кома", '.' to "крапка", '!' to "знак оклику", '?' to "знак питання", ':' to "двокрапка", ';' to "крапка з комою",
    '—' to "тире", '–' to "коротке тире", '-' to "дефіс", '…' to "три крапки", '"' to "лапки",
    '«' to "відкривні лапки", '»' to "закривні лапки", '(' to "відкривна дужка", ')' to "закривна дужка")

// «м'як+ий»: ударение не на первом слоге — в имени сразу; апостроф UkNormalizer делает «ъ»
internal val UK_LETTERS = mapOf(
    'а' to "а", 'б' to "бе", 'в' to "ве", 'г' to "ге", 'ґ' to "ґе", 'д' to "де", 'е' to "е", 'є' to "є", 'ж' to "же",
    'з' to "зе", 'и' to "и", 'і' to "і", 'ї' to "ї", 'й' to "йот", 'к' to "ка", 'л' to "ел", 'м' to "ем", 'н' to "ен",
    'о' to "о", 'п' to "пе", 'р' to "ер", 'с' to "ес", 'т' to "те", 'у' to "у", 'ф' to "еф", 'х' to "ха", 'ц' to "це",
    'ч' to "че", 'ш' to "ша", 'щ' to "ща", 'ь' to "м'як+ий знак", 'ю' to "ю", 'я' to "я")

/** Латинские буквы, как их произносят по-украински в аббревиатурах (английские имена: «USB» — «ю-ес-бі»). Не проверено
 * носителем. Та же таблица — имена букв в капсе и одной буквой у [UkLatin]. */
internal val UK_LATIN = mapOf(
    'a' to "ей", 'b' to "бі", 'c' to "сі", 'd' to "ді", 'e' to "і", 'f' to "еф", 'g' to "джі", 'h' to "ейч", 'i' to "ай",
    'j' to "джей", 'k' to "кей", 'l' to "ел", 'm' to "ем", 'n' to "ен", 'o' to "оу", 'p' to "пі", 'q' to "к'ю", 'r' to "ар",
    's' to "ес", 't' to "ті", 'u' to "ю", 'v' to "ві", 'w' to "д+абл-ю", 'x' to "екс", 'y' to "вай", 'z' to "зед")

/** Списки украинского слоя, как [BE_QUESTIONS]. «ё» в них нет — нормализация [SentenceType] (ё → е) их не меняет. */
private object UK_QUESTIONS : QuestionLists {
    /** Буквы слова для границ: украинские, русские и апостроф внутри слова («п'ять»). */
    private const val L = "а-яёєіїґ’'`´ʼ‘"
    override val whForms = setOf(
        "хто", "що", "де", "коли", "чому", "навіщо", "нащо", "як", "який", "яка", "яке", "які", "чий", "чия", "чиє", "чиї",
        "скільки", "куди", "звідки", "котрий", "котра", "котре", "котрі", "кого", "чого", "кому", "чим")
    override val leadingFillers = setOf("ну", "ось", "а", "так", "то", "може", "хіба", "невже", "адже", "отже", "чи")
    // хвост — в конце после запятой: «Ти прийдеш, правда?», «…, так?»; «Це правда?», «Так?» — общий вопрос
    override val tagRe = Regex(",\\s*(?:правда(?:\\s+ж)?|так|чи\\s+не\\s+так|так\\s+же|адже\\s+так)\\s*\\?$", RegexOption.IGNORE_CASE)
    // «Так чи ні?» — альтернатива: «так» не вводное перед «чи»
    private val starters = (leadingFillers - "чи" - "так").joinToString("|")
    // «або» — альтернатива; «чи» — только между словами («Чай чи каву?»): в начале, после вводного, тире и запятой — частица
    // общего вопроса («— Чи прийдеш?», «А чи знаєш?», «Скажи, чи прийдеш?»), как белорусское «ці»
    override val altRe = Regex("(?<![$L])або(?![$L])|" +
        "(?<=[$L]\\s)(?<!^[^$L]{0,3}(?:(?:$starters)[^$L]{1,3})?(?:$starters)\\s)чи(?![$L])", RegexOption.IGNORE_CASE)
    // «будь ласка» — не вопрос «як?»; после начального «чи» — придаточное («Чи знаєш ти, що завтра свято?»); «що», «як» за
    // глаголом 2 лица или прошедшего и запятой — союз («Ти знаєш, що він прийде?», «Ви чули, як він співає?»), а «Скажи, як
    // пройти?» — вопрос. Повтор вводных — притяжательный «*+»: без него JVM на «ну »×200 падала StackOverflowError
    override val notWhRe = Regex("(?<![$L])будь\\s+ласка(?![$L])|(?<=[$L](?:єш|еш|иш|їш|ете|ите|єте|ли))\\s*,\\s*(?:що|як)(?![$L]).*|" +
        "^[^$L]*(?:(?:$starters)[^$L]+)*+чи(?![$L]).*", RegexOption.IGNORE_CASE)
    // Окончания глагола: инфинитив -ти, -ть; 2 л. -еш, -иш, -єш, -їш; 1 мн. -емо, -имо, -ємо; 2 мн. -ете, -ите, -єте; прош.
    // -в, -ла, -ло, -ли; возвратные -ся, -сь. Без морфологии, как у белорусского (правило question у uk скрыто). Известные
    // ложные: «Київ», «Львів», «любов» (-в), «школа», «сила» (-ла), «мить», «путь» (-ть); числительные — в [notVerb]
    private val verbRe = Regex("[$L]+(?:ти|ть|еш|иш|єш|їш|емо|имо|ємо|ете|ите|єте|ла|ло|ли|в|ся|сь)", RegexOption.IGNORE_CASE)
    // слова [Marks.question] режет по апострофу: «п'ять», «дев'ять», «пам'ять» доходят частями «ять» и «дев» (на «-в»)
    private val notVerb = setOf("ять", "дев", "шість", "десять", "коли", "мати")
    override fun verb(w: String, morph: Morph?) = (morph == null || morph.tags(w) == 0) && verbRe.matches(w) && w !in notVerb
}

/** Таблица языков: язык берётся по коду пака, остальной код о языках не знает. */
object Layers {
    private val all = listOf(RuLayer, BeLayer, UkLayer)
    private val table: Map<String, TextLayer> = all.associateBy { it.code }

    /** Русский — умолчание: так читаются паки без поля `lang` и встроенная модель. */
    val RU: TextLayer get() = RuLayer

    fun of(code: String?): TextLayer = table[code?.lowercase()] ?: RuLayer

    /** Есть ли слой для кода пака: пак на языке, которого приложение не знает, не ставится. */
    fun has(code: String) = code in table

    /** Слой по языку из TTS API (ISO-3: «rus», «bel», «ukr»); null — такого языка нет. */
    fun ofIso3(lang: String?): TextLayer? = all.firstOrNull { it.locale.isO3Language == lang }
}
