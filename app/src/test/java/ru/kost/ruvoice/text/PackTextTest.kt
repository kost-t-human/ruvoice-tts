package ru.kost.ruvoice.text

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Test
import ru.kost.ruvoice.Pack
import ru.kost.ruvoice.TestData
import java.io.File

class PackTextTest {
    private val golden = JSONArray(File(TestData.root(), "app/src/test/resources/golden_packs.json").readText())

    @Test fun matchesPythonPreprocessingForEveryPack() {
        for (i in 0 until golden.length()) {
            val g = golden.getJSONObject(i)
            val pack = Pack(g.getJSONObject("pack").toString(), File("."))
            val prepared = PackText.prepare(g.getString("text"), pack, g.getString("lang"))
            assertEquals(pack.id, g.getString("prepared"), prepared)
            val ids = g.getJSONArray("ids").let { a -> LongArray(a.length()) { a.getLong(it) } }
            assertEquals(pack.id, ids.toList(), pack.sym.sequence(prepared).toList())
        }
    }

    private fun cis(): Pack = (0 until golden.length()).map { golden.getJSONObject(it) }.first { it.getJSONObject("pack").getString("id") == "cis_base_nostress" }
        .let { Pack(it.getJSONObject("pack").toString(), File(".")) }

    @Test fun transliteratesLongestKeyFirst() {
        // узбекская латиница: «sh» → «ш», а не «с»+«ҳ»; «o'» → «ў»
        assertEquals("шаҳар ўзбэк", PackText.prepare("Shahar o'zbek", cis(), "uzb")) // в таблице e → э
    }

    @Test fun transliterationOnlyWhenForeignLetters() {
        // кириллический узбекский текст таблица не трогает
        assertEquals("шаҳар", PackText.prepare("шаҳар", cis(), "uzb"))
    }

    @Test fun georgianGoesThroughTable() {
        assertEquals("сакартвело", PackText.prepare("საქართველო", cis(), "kat"))
    }

    @Test fun dropsSymbolsOutsideModelAndCollapsesSpaces() {
        // тире выпадает (в символах нет «–»), цифры и латиница без таблицы выпадают, пробелы схлопываются
        assertEquals("мин сине", PackText.prepare("Мин — 12 сине  abc", cis(), "tat"))
    }

    @Test fun translitKeyMatchesKeyFromPreparedText() {
        // ключ источника (Marks.key на исходном грузинском слове) после таблицы должен совпасть
        // с ключом того же слова в prepared-тексте (апостроф из "к'" Marks.key уже вычищает)
        val pack = cis()
        val prepared = PackText.prepare("საქართველო", pack, "kat")
        val srcKey = Marks.key("საქართველო")
        assertEquals(Marks.key(prepared), PackText.translitKey(srcKey, pack, "kat"))
    }

    @Test fun translitKeyIsIdentityWithoutTable() {
        // язык без таблицы транслитерации (не в pack.translit) — ключ не меняется
        assertEquals("сюйем", PackText.translitKey("сюйем", cis(), "tat"))
    }

    @Test fun alignFindsSecondTransliteratedWordForItsOwnMark() {
        // без сопоставления по транслитерированному ключу Matcher никогда не продвигается (все
        // токены ловят пометку первого слова) — {prosody} второго слова терялся
        val pack = cis()
        val p = Marks.parse("საქართველო {prosody:150:100}საქართველო{prosody}")
        val prepared = PackText.prepare(p.text, pack, "kat")
        val words = p.words.map { (w, m) -> PackText.translitKey(w, pack, "kat") to m }
        val seq = pack.sym.sequence(prepared)
        val a = Marks.align(words, prepared, seq.size, pack.sym)
        val t = Marks.tokens(prepared, pack.sym)
        assertEquals(2, t.size)
        assertEquals(1.0f, a.rates[t[0].seqStart], 0f)
        assertEquals(1.5f, a.rates[t[1].seqStart], 0f)
    }
}
