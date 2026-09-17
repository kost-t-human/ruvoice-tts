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
        assertEquals("в пыли", s.gramPass("в пыли"))
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
        assertEquals("стены", s.gramPass("стены"))
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
