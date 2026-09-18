package ru.kost.ruvoice

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class SpeakerTest {
    private val d = TestData.data()
    private val pack = Pack(JSONObject(File(TestData.root(), "app/src/test/resources/golden_pack.json").readText()).getJSONObject("pack").toString(), File("."))

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
        assertEquals("xenia", Speaker.default(d).name)
    }

    @Test fun namesAndSameEngine() {
        val names = Speaker.names(d, listOf(pack))
        assertEquals(d.speakers.keys.sorted(), names.take(5))
        assertEquals(5 + 29, names.size); assertEquals("cis_ru/ru_aigul", names[5])
        assertEquals(d.speakers.keys.sorted(), Speaker.sameEngine(Speaker.default(d), d, listOf(pack)))
        assertEquals(names.drop(5), Speaker.sameEngine(Speaker.resolve("cis_ru/ru_vika", d, listOf(pack))!!, d, listOf(pack)))
    }

    @Test fun labels() {
        assertEquals("xenia", Speaker.label("xenia"))
        assertEquals("ru_alexandr (cis_ru)", Speaker.label("cis_ru/ru_alexandr"))
    }
}
