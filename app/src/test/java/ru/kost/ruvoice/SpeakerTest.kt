package ru.kost.ruvoice

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class SpeakerTest {
    init { Speaker.builtin = true }   // тесты считают сборку full, lite включается через lite {}; в testLite… BuildConfig даёт false
    private val d = TestData.data()
    private val pack = Pack(JSONObject(File(TestData.root(), "app/src/test/resources/golden_pack.json").readText()).getJSONObject("pack").toString(), File("."))
    // пак ru = штатная модель паком: та же таблица символов и голоса, что в silero_ru.json
    private val ruPack = Pack(JSONObject(File(TestData.root(), "app/src/main/assets/silero/silero_ru.json").readText()).let { j ->
        JSONObject().put("format", 2).put("id", "ru").put("title", "Штатные голоса").put("license", "CC BY-NC-SA 4.0").put("source", "x")
            .put("symbols", j.getString("symbols")).put("symbol_to_id", j.getJSONObject("symbol_to_id")).put("sos", j.getString("sos")).put("eos", j.getString("eos"))
            .put("alphabet", j.getString("alphabet")).put("types", true).put("speakers", j.getJSONObject("speakers")).toString()
    }, File("."))

    private fun lite(block: () -> Unit) { Speaker.builtin = false; try { block() } finally { Speaker.builtin = true } }

    @Test fun resolvesBuiltinAndPackVoices() {
        val x = Speaker.resolve("xenia", d, listOf(pack))!!
        assertNull(x.pack); assertTrue(x.types); assertSame(d.sym, x.sym); assertEquals(d.speakers.getValue("xenia"), x.id)
        val a = Speaker.resolve("cis_ru/ru_alexandr", d, listOf(pack))!!
        assertSame(pack, a.pack); assertFalse(a.types); assertSame(pack.sym, a.sym); assertEquals(pack.speakers.getValue("ru_alexandr"), a.id)
    }

    @Test fun unknownIsNullAndDefaultIsXenia() {
        assertNull(Speaker.resolve("cis_ru/ru_alexandr", d, emptyList()))   // пак удалён
        assertNull(Speaker.resolve("cis_ru/nobody", d, listOf(pack)))
        assertNull(Speaker.resolve(null, d, listOf(pack)))
        assertEquals("xenia", Speaker.default(d, listOf(pack))!!.name)
    }

    @Test fun namesAndSameEngine() {
        val names = Speaker.names(d, listOf(pack))
        assertEquals(d.speakers.keys.sorted(), names.take(5))
        assertEquals(5 + 29, names.size); assertEquals("cis_ru/ru_aigul", names[5])
        assertEquals(d.speakers.keys.sorted(), Speaker.sameEngine(Speaker.default(d, listOf(pack))!!, d, listOf(pack)))
        assertEquals(names.drop(5), Speaker.sameEngine(Speaker.resolve("cis_ru/ru_vika", d, listOf(pack))!!, d, listOf(pack)))
    }

    @Test fun labels() {
        assertEquals("xenia", Speaker.label("xenia"))
        assertEquals("ru_alexandr (cis_ru)", Speaker.label("cis_ru/ru_alexandr"))
    }

    @Test fun ttsNamesHaveNoSlashesAndRuOnce() {
        assertEquals("xenia-ru", Speaker.ttsName("xenia"))
        assertEquals("xenia-ru", Speaker.ttsName("ru/xenia"))   // lite: то же имя, что в full
        assertEquals("marat-ru-cis", Speaker.ttsName("cis_ru/ru_marat"))
        val packs = listOf(pack)
        assertEquals("cis_ru/ru_marat", Speaker.fromTtsName("marat-ru-cis", d, packs))
        assertEquals("cis_ru/ru_marat", Speaker.fromTtsName("ru-ru-cis_ru/ru_marat", d, packs))   // сохранённое читалкой до 0.15
        assertEquals("xenia", Speaker.fromTtsName("xenia-ru", d, packs))
        assertEquals("xenia", Speaker.fromTtsName("ru-ru-xenia", d, packs))
        assertNull(Speaker.fromTtsName(null, d, packs))
        assertNull(Speaker.resolve(Speaker.fromTtsName("nobody-ru", d, packs), d, packs))
        lite { assertEquals("ru/xenia", Speaker.fromTtsName("xenia-ru", d, listOf(ruPack))) }
    }

    @Test fun liteWithoutPacksHasNoVoices() = lite {
        assertTrue(Speaker.names(d, emptyList()).isEmpty())
        assertNull(Speaker.default(d, emptyList()))
        assertNull(Speaker.resolve("xenia", d, emptyList()))
        assertEquals(listOf("cis_ru/ru_aigul"), Speaker.names(d, listOf(pack)).take(1))
        assertEquals("cis_ru/ru_aigul", Speaker.default(d, listOf(pack))!!.name)
    }

    @Test fun liteBareNameResolvesIntoRuPack() = lite {
        val x = Speaker.resolve("xenia", d, listOf(ruPack))!!
        assertEquals("ru/xenia", x.name); assertSame(ruPack, x.pack); assertTrue(x.types); assertEquals(ruPack.speakers.getValue("xenia"), x.id)
        assertEquals("ru/xenia", Speaker.default(d, listOf(pack, ruPack))!!.name)   // xenia важнее первого пака
        assertEquals(listOf("ru/aidar", "ru/baya", "ru/eugene", "ru/kseniya", "ru/xenia"), Speaker.sameEngine(x, d, listOf(pack, ruPack)))
        assertNull(Speaker.resolve("nobody", d, listOf(ruPack)))
    }

    @Test fun hasVoices() {
        assertTrue(Speaker.hasVoices(emptyList()))
        lite {
            assertFalse(Speaker.hasVoices(emptyList()))
            assertTrue(Speaker.hasVoices(listOf(pack)))
        }
    }

    @Test fun fullKeepsBuiltinEvenWithRuPack() {
        val x = Speaker.resolve("xenia", d, listOf(ruPack))!!
        assertNull(x.pack); assertEquals("xenia", x.name)
        assertEquals(5 + 5, Speaker.names(d, listOf(ruPack)).size)
    }
}
