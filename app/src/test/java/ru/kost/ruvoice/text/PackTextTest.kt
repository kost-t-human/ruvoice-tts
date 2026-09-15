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
}
