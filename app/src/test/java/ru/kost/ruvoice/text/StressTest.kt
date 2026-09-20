package ru.kost.ruvoice.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.kost.ruvoice.TestData
import java.io.File

class StressTest {
    private val d = TestData.data()

    /** ударение на первую гласную, ё нет */
    private val firstVowel = object : StressModels {
        override fun accentor(words: List<String>) = Pair(
            Array(words.size) { FloatArray(10).also { it[0] = 1f } },
            Array(words.size) { FloatArray(7).also { it[0] = 1f } })
        override fun homo(ids: List<LongArray>, starts: LongArray, ends: LongArray) = FloatArray(ids.size) { 0.9f }
    }

    @Test fun exceptionsWinOverModel() {
        // «его» → [2,-1]: ударение перед индексом 2
        assertEquals("ег+о", Stress(d, firstVowel).apply("его"))
    }

    @Test fun modelStressAndSingleVowel() {
        assertEquals("+а м+ама", Stress(d, firstVowel).apply("а мама"))
    }

    @Test fun userStressIsKept() {
        assertEquals("мам+а", Stress(d, firstVowel).apply("мам+а"))
    }

    @Test fun homographPicksSortedVariantByPrediction() {
        // sigmoid 0.9 → round = 1 → sorted(homodict["замок"])[1]
        val variants = d.homodict.getValue("замок").sorted()
        val out = Stress(d, firstVowel).apply("замок")
        assertEquals(variants[1], out)
    }

    @Test fun userDictAppliedLast() {
        val s = Stress(d, firstVowel, mapOf("мама" to "мам+а"))
        assertEquals("мам+а", s.apply("мама"))
    }

    @Test fun userDictSkipsPresetStress() {
        // слово пришло уже с «+» (фраза словаря замен или ударение в тексте) — словарь ударений его не переписывает
        val s = Stress(d, firstVowel, mapOf("потом" to "пот+ом"))
        assertEquals("+обливаясь п+отом, +а пот+ом", s.apply("обливаясь п+отом, а потом"))
    }

    @Test fun userDictWinsOverHomographYo() {
        // homodict «узнает» → узна+ёт по BERT (0.9 → второй вариант): словарь с «е» должен найти слово и после подмены буквы;
        // «узнаёт» в самом тексте — другое слово, словарь его не трогает
        val s = Stress(d, firstVowel, mapOf("узнает" to "узн+ает"))
        assertEquals("+он узн+ает, чт+о", s.apply("он узнает, что"))
        assertEquals("+Он узн+ает", s.apply("Он узнает"))
        assertEquals("+он узна+ёт", s.apply("он узнаёт"))
        assertEquals("узна+ёт", Stress(d, firstVowel).apply("узнает"))
    }

    @Test fun userDictKeepsOriginalCapitalization() {
        // словарное значение хранится строчными, регистр восстанавливаем по исходному слову
        val s = Stress(d, firstVowel, mapOf("мама" to "мам+а"))
        assertEquals("Мам+а", s.apply("Мама"))
    }

    @Test fun nbspTreatedAsWordSeparator() {
        // NBSP (U+00A0): JVM \s его не матчит, Python \s матчит. «в» без гласной остаётся как есть,
        // у «доме» firstVowel-заглушка ставит ударение перед первой гласной («о»): «д+оме».
        assertEquals("в\u00A0д+оме", Stress(d, firstVowel).apply("в\u00A0доме"))
    }

    @Test fun quotesAreSeparators() {
        assertEquals("з+аписи «м+ама» \"п+апа\"", Stress(d, firstVowel).apply("записи «мама» \"папа\""))
    }

    @Test fun punctuationAndHyphenPreserved() {
        assertEquals("кт+о-то, +а т+ы?", Stress(d, firstVowel).apply("кто-то, а ты?"))
    }

    @Test fun hyphenPrefixNotStressed() {
        // «в+о-п+ервых» модель тянет «воо…»: приставка перед дефисом без принудительного ударения одной гласной
        assertEquals("во-п+ервых, по-м+оему, из-з+а", Stress(d, firstVowel).apply("во-первых, по-моему, из-за"))
        assertEquals("кт+о-н+ибудь", Stress(d, firstVowel).apply("кто-нибудь"))
    }

    @Test fun goldenHomographContexts() {
        // контекст с [HOMO]-маркерами (окно 150 символов, чистка HomoSolver._clean_text) — как в Python
        val g = TestData.golden()
        var checked = 0
        for (i in 0 until g.length()) {
            val o = g.getJSONObject(i)
            if (!o.has("bert")) continue
            val exp = o.getJSONArray("bert").let { a -> List(a.length()) { a.getJSONObject(it).getString("marked") } }
            assertEquals(o.getString("prepared"), exp, Stress(d, firstVowel).tagHomos(o.getString("prepared")).map { it.marked })
            checked += exp.size
        }
        assert(checked >= 5) { "мало омографов в golden: $checked" }
    }

    @Test fun phraseBeatsModel() {
        // «замок казался очень тихим» есть во фразах Silero Stress → з+амок, хотя заглушка-BERT даёт зам+ок
        val neural = Stress(d, firstVowel).apply("замок")
        assertEquals("зам+ок", neural)
        assertEquals("З+амок к+азался +очень т+ихим.", Stress(d, firstVowel).apply("Замок казался очень тихим."))
    }

    @Test fun phraseOnlyWordWithoutPhraseIsLeftToAccentor() {
        // «толстая» есть только во фразах («людмила толстая»); без фразы слово идёт в accentor, не в BERT
        assertEquals("т+олстая", Stress(d, firstVowel).apply("толстая"))
        assertEquals("л+юдмила толст+ая", Stress(d, firstVowel).apply("людмила толстая"))
    }

    @Test fun gramPassPicksCaseByPrecedingWord() {
        val s = Stress(d, firstVowel)
        // род. ед. после предлога родительного и числительного; вин. мн. после «в»; сущ. после предлога; глагол после местоимения
        assertEquals("вдоль стен+ы", s.gramPass("вдоль стены"))
        assertEquals("из-за стен+ы", s.gramPass("из-за стены"))
        assertEquals("из за стен+ы", s.gramPass("из за стены"))
        assertEquals("две рук+и", s.gramPass("две руки"))
        assertEquals("в ст+ены", s.gramPass("в стены"))
        assertEquals("за сел+о", s.gramPass("за село"))
        assertEquals("в пыл+и", s.gramPass("в пыли"))
        assertEquals("я нош+у", s.gramPass("я ношу"))
        assertEquals("вечного г+орода", s.gramPass("вечного города"))
        assertEquals("его руки", s.gramPass("его руки"))
        assertEquals("Вдоль Стен+ы", s.gramPass("Вдоль Стены"))
        assertEquals("в Оз+ёра, у +озера", s.gramPass("в Озера, у озера"))
        // одушевлённые: после «за» — вин. ед., после «в» — не решаем («выйти в учителя»); запятая рвёт связь
        assertEquals("за уч+ителя", s.gramPass("за учителя"))
        assertEquals("в учителя", s.gramPass("в учителя"))
        assertEquals("что за свиньи", s.gramPass("что за свиньи"))
        assertEquals("вдоль, стены", s.gramPass("вдоль, стены"))
        assertEquals("ст+ены", s.gramPass("стены"))   // одно слово — начало предложения, мн.
        assertEquals("стены", s.gramPass("стены", start = false))
        // «самого» — прилагательное, кроме «у самого» («у него самого глаза вылезли»)
        assertEquals("до самого яйц+а", s.gramPass("до самого яйца"))
        assertEquals("у самого яйца", s.gramPass("у самого яйца"))
        assertEquals("лишённые душ+и, полные вод+ы", s.gramPass("лишённые души, полные воды"))
        assertEquals("отчего цены", s.gramPass("отчего цены"))
        assertEquals("размером с г+оры", s.gramPass("размером с горы"))
        // второй дательный «к утр+у» и второй предложный «в глуш+и»: в таблице нет / нет вин. мн.
        assertEquals("к утру", s.gramPass("к утру"))
        assertEquals("в глуш+и", s.gramPass("в глуши"))
        // после фазового глагола инфинитив несов. вида
        assertEquals("начал обполз+ать", s.gramPass("начал обползать"))
        assertEquals("будет разрез+ать", s.gramPass("будет разрезать"))
        assertEquals("обползать", s.gramPass("обползать"))
        // дальше омографы и акцентор слово не трогают
        assertEquals("з+а сел+о", s.apply("за село"))
    }

    @Test fun gramPassSecondPrepositional() {
        val morph = Morph.open(File(TestData.root(), "app/src/main/assets/morph.bin"))
        val s = Stress(d, firstVowel, morph = morph)
        // второй предложный после «в/на» и через прилагательное; иначе всегда основа; после глагола движения — вин. мн.
        assertEquals("в кров+и", s.gramPass("в крови"))
        assertEquals("анализ кр+ови", s.gramPass("анализ крови"))
        assertEquals("до кр+ови", s.gramPass("до крови"))
        assertEquals("в чужой кров+и", s.gramPass("в чужой крови"))
        assertEquals("в её кров+и", s.gramPass("в её крови"))
        assertEquals("в потрясённом мозг+у", s.gramPass("в потрясённом мозгу"))
        assertEquals("в открытые дв+ери", s.gramPass("в открытые двери"))
        assertEquals("бросилась в дв+ери", s.gramPass("бросилась в двери"))
        assertEquals("у толстой ц+епи", s.gramPass("у толстой цепи"))
        assertEquals("колеблющемся дыму", s.gramPass("колеблющемся дыму"))
        assertEquals("в бред+у", s.gramPass("в бреду"))
        assertEquals("в тен+и", s.gramPass("в тени"))
        // одушевлённое после существительного — род. ед.
        assertEquals("задача уч+ителя", s.gramPass("задача учителя"))
        assertEquals("пришли учителя", s.gramPass("пришли учителя"))
        assertEquals("я бреду", s.gramPass("я бреду"))
    }

    @Test fun gramPassVerbPluralAndQuantity() {
        val morph = Morph.open(File(TestData.root(), "app/src/main/assets/morph.bin"))
        val s = Stress(d, firstVowel, morph = morph)
        // после глагола слово из списка — мн.; не после причастия, не рядом с «не»; вне списка молчим
        assertEquals("заблестели глаз+а", s.gramPass("заблестели глаза"))
        assertEquals("опустил р+уки", s.gramPass("опустил руки"))
        assertEquals("поднявший глаза", s.gramPass("поднявший глаза"))
        assertEquals("лишился глаза", s.gramPass("лишился глаза"))
        assertEquals("руки или ноги", s.gramPass("руки или ноги", start = false))
        assertEquals("не поднимал глаза", s.gramPass("не поднимал глаза"))
        assertEquals("боялся высоты", s.gramPass("боялся высоты"))
        // слово из списка перед глаголом во мн. — подлежащее, и в начале фразы, и после запятой; не после «оба/два» в трёх
        // словах, существительного и «не»
        assertEquals("Глаз+а выглядели уставшими", s.gramPass("Глаза выглядели уставшими"))
        assertEquals("устал, глаз+а слипались", s.gramPass("устал, глаза слипались"))
        assertEquals("оба глаза блестели", s.gramPass("оба глаза блестели"))
        assertEquals("две костлявые рук+и обняли", s.gramPass("две костлявые руки обняли"))
        assertEquals("створки окна распахнулись", s.gramPass("створки окна распахнулись"))
        assertEquals("глаза не видели", s.gramPass("глаза не видели"))
        assertEquals("глаза боятся", s.gramPass("глаза боятся"))
        // количественное слово справа или слева — род. ед.
        assertEquals("а вод+ы нет", s.gramPass("а воды нет"))
        assertEquals("мало вод+ы", s.gramPass("мало воды"))
        assertEquals("много глаз", s.gramPass("много глаз"))
    }

    @Test fun gramPassPossessiveAndSubjectThroughAdverb() {
        val morph = Morph.open(File(TestData.root(), "app/src/main/assets/morph.bin"))
        val s = Stress(d, firstVowel, morph = morph)
        // притяжательное перед словом из короткого списка — мн.; после род. предлога или «оба/два» — род. ед.; вне списка молчим
        assertEquals("её глаз+а снова сверкнули", s.gramPass("её глаза снова сверкнули"))
        assertEquals("Его Глаз+а", s.gramPass("Его Глаза"))
        assertEquals("мои слов+а", s.gramPass("мои слова"))
        assertEquals("в его глаз+а", s.gramPass("в его глаза"))
        assertEquals("оба его гл+аза", s.gramPass("оба его глаза"))
        assertEquals("из его гл+аза", s.gramPass("из его глаза"))
        assertEquals("его руки", s.gramPass("его руки"))
        assertEquals("его голоса", s.gramPass("его голоса"))
        // подлежащее через наречие перед глаголом во мн.; не через существительное, союз или местоимение
        assertEquals("Глаз+а снова сверкнули", s.gramPass("Глаза снова сверкнули"))
        assertEquals("р+уки его дрожали", s.gramPass("руки его дрожали"))
        assertEquals("глаз+а мальчика блестели", s.gramPass("глаза мальчика блестели"))   // первое слово предложения
        assertEquals("глаза и уши болели", s.gramPass("глаза и уши болели", start = false))
        assertEquals("стены снова дрожали", s.gramPass("стены снова дрожали", start = false))
        // первое слово предложения из списка — мн.; не перед «не/нет», не вне списка, не в середине
        assertEquals("стр+елы, прочертив дугу, упали", s.gramPass("стрелы, прочертив дугу, упали"))
        assertEquals("стрелы, прочертив дугу, упали", s.gramPass("стрелы, прочертив дугу, упали", start = false))
        assertEquals("— Стр+елы!", s.gramPass("— Стрелы!"))
        assertEquals("Ушёл. Стр+елы летели", s.gramPass("Ушёл. Стрелы летели"))
        assertEquals("Стрелы не было", s.gramPass("Стрелы не было"))
        assertEquals("Дома, улицы", s.gramPass("Дома, улицы"))
        assertEquals("Наконечник стрелы", s.gramPass("Наконечник стрелы"))
        // родительный в начале — молчим: отрицание дальше, глагол с родительным, прилагательное в ед., существительное без род.
        assertEquals("Слова сочувствия от них не дождёшься", s.gramPass("Слова сочувствия от них не дождёшься"))
        assertEquals("Губы коснулся ветерок", s.gramPass("Губы коснулся ветерок"))
        assertEquals("Леса густого тень", s.gramPass("Леса густого тень"))
        assertEquals("Свечи огарок", s.gramPass("Свечи огарок"))
        assertEquals("Ст+ены домов украсили", s.gramPass("Стены домов украсили"))
        // «самого» перед словом с заглавной — «сам», кроме «до/от/у самого» и «того/этого самого»
        assertEquals("атаковать самог+о зарецкого", s.gramPass("атаковать самого зарецкого", source = "Атаковать самого Зарецкого"))
        assertEquals("атаковать самого зарецкого", s.gramPass("атаковать самого зарецкого"))
        assertEquals("до самого парижа", s.gramPass("до самого парижа", source = "до самого Парижа"))
        assertEquals("того самого зарецкого", s.gramPass("того самого зарецкого", source = "того самого Зарецкого"))
        assertEquals("самого лучшего", s.gramPass("самого лучшего"))
    }

    @Test fun gramPassAgreesWithAdjective() {
        val morph = Morph.open(File(TestData.root(), "app/src/main/assets/morph.bin"))
        val s = Stress(d, firstVowel, morph = morph)
        // прилагательное согласуется со словом только в одном числе: мн. или род. ед.; после «две» — род. ед.
        assertEquals("высокие ст+ены", s.gramPass("высокие стены"))
        assertEquals("высокой стен+ы", s.gramPass("высокой стены"))
        assertEquals("эти р+уки", s.gramPass("эти руки"))
        assertEquals("крупные оз+ёра", s.gramPass("крупные озера"))
        assertEquals("крупного +озера", s.gramPass("крупного озера"))
        assertEquals("две толстые ног+и", s.gramPass("две толстые ноги"))
        assertEquals("две короткие толстые ног+и", s.gramPass("две короткие толстые ноги"))
        assertEquals("четыре кривые тощие ног+и", s.gramPass("четыре кривые тощие ноги"))
        // «все» + слово только мн. ч. → «вс+е»; перед ед. ч. и не по таблице — молчим
        assertEquals("вс+е +окна", s.gramPass("все окна"))
        assertEquals("Вс+е крупные оз+ёра", s.gramPass("Все крупные озера"))
        assertEquals("все время", s.gramPass("все время"))
        assertEquals("не все дома", s.gramPass("не все дома"))
        assertEquals("все они", s.gramPass("все они"))
        assertEquals("все новых", s.gramPass("все новых"))
        assertEquals("всё дома", s.gramPass("всё дома"))
        // прилагательное или причастие во мн. через слово или предложную группу: «почерневшие от времени доски» → мн.
        assertEquals("Я остановился около стен+ы...", s.gramPass("Я остановился около стены..."))
        assertEquals("увидел почерневшие от вр+емени д+оски", s.gramPass("увидел почерневшие от времени доски"))
        assertEquals("покрытые пылью д+оски", s.gramPass("покрытые пылью доски"))
        assertEquals("мокрые от дождя ст+ены", s.gramPass("мокрые от дождя стены"))
        assertEquals("стоящие вдоль дороги дом+а", s.gramPass("стоящие вдоль дороги дома"))
        assertEquals("две покрытые лесом гор+ы", s.gramPass("две покрытые лесом горы"))
        assertEquals("поросшие лесом горы", s.gramPass("поросшие лесом горы"))              // действительное причастие через слово — BERT
        assertEquals("бревенчатые ст+ены терема", s.gramPass("бревенчатые стены терема"))   // «бревенчатые» про стены, «терема» — BERT
        assertEquals("искавшие души", s.gramPass("искавшие души"))                         // рядом — BERT
        assertEquals("все население страны", s.gramPass("все население страны"))
        // без таблицы — как раньше
        assertEquals("высокие стены", Stress(d, firstVowel, morph = null).gramPass("высокие стены"))
        assertEquals("все окна", Stress(d, firstVowel, morph = null).gramPass("все окна"))
    }

    @Test fun userDictForbidsModelYo() {
        // акцентор: ударение на вторую гласную и «ё» на первую «е» — «афера» → «аф+ёра»; словарь с «е» это отменяет
        val yo = object : StressModels {
            override fun accentor(words: List<String>) = Pair(
                Array(words.size) { FloatArray(10).also { it[1] = 1f } }, Array(words.size) { FloatArray(7).also { it[1] = 1f } })
            override fun homo(ids: List<LongArray>, starts: LongArray, ends: LongArray) = FloatArray(ids.size) { 0.9f }
        }
        assertEquals("аф+ёра", Stress(d, yo).apply("афера"))
        assertEquals("аф+ера", Stress(d, yo, mapOf("афера" to "аф+ера")).apply("афера"))
        // исключение модели «истекший» = [3, 3] (ударение и «ё»); «ист+екший» из замены оставляет «е»
        assertEquals("ист+ёкший +и ист+екший", Stress(d, yo).apply("истекший и ист+екший"))
    }
}
