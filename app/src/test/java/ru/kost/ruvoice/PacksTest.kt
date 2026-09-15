package ru.kost.ruvoice

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PacksTest {
    @get:Rule val tmp = TemporaryFolder()

    private val json = """{"format":1,"id":"test","title":"Тест","license":"MIT","source":"x",
        "symbols":"_~|абв ","symbol_to_id":{"_":0,"~":1,"|":2,"а":3,"б":4,"в":5," ":6},"sos":"|","eos":"~",
        "alphabet":"абв","languages":{"tat":{"name":"Татарский","speakers":{"tat_0":0,"tat_1":1}}},
        "translit":{"uzb":{"sh":"ш","a":"а"}}}"""

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val bo = ByteArrayOutputStream()
        ZipOutputStream(bo).use { z -> for ((n, b) in entries) { z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() } }
        return bo.toByteArray()
    }

    @Test fun parsesPackJson() {
        val p = Pack(json, tmp.root)
        assertEquals("test", p.id)
        assertEquals("абв ", p.sym.allowed)
        assertEquals(listOf(2L, 3L, 6L, 6L, 4L, 1L), p.sym.sequence("а x б").toList())
        assertEquals(mapOf("tat_0" to 0, "tat_1" to 1), p.languages.getValue("tat").speakers)
        assertEquals(1, p.speakerId("tat", "tat_1"))
        assertNull(p.speakerId("tat", "nope"))
        assertEquals("ш", p.translit.getValue("uzb").getValue("sh"))
    }

    @Test fun installsFromZipInAnyEntryOrder() {
        val root = tmp.root
        val p = Packs.install(ByteArrayInputStream(zip("tts.ptl" to "model".toByteArray(), "pack.json" to json.toByteArray())), root)
        assertEquals("test", p.id)
        assertEquals("model", root.resolve("packs/test/tts.ptl").readText())
        assertTrue(root.resolve("packs/test/pack.json").exists())
        assertEquals(listOf("test"), Packs.installed(root).map { it.id })
        // временных каталогов не осталось
        assertEquals(listOf("test"), root.resolve("packs").list()!!.toList())
        // повторная установка того же id перезаписывает
        Packs.install(ByteArrayInputStream(zip("pack.json" to json.toByteArray(), "tts.ptl" to "model2".toByteArray())), root)
        assertEquals("model2", root.resolve("packs/test/tts.ptl").readText())
        assertEquals(1, Packs.installed(root).size)
    }

    @Test fun rejectsBadZipsWithoutTraces() {
        val root = tmp.root
        val bad = listOf(
            zip("readme.txt" to "hi".toByteArray()),                                    // нет pack.json
            zip("pack.json" to json.toByteArray()),                                     // нет tts.ptl
            zip("tts.ptl" to "m".toByteArray(), "pack.json" to json.replace("\"format\":1", "\"format\":2").toByteArray()),
            zip("tts.ptl" to "m".toByteArray(), "pack.json" to json.replace("\"id\":\"test\"", "\"id\":\"../x\"").toByteArray()),
            zip("tts.ptl" to "m".toByteArray(), "pack.json" to json.replace("\"languages\":{\"tat\":{\"name\":\"Татарский\",\"speakers\":{\"tat_0\":0,\"tat_1\":1}}}", "\"languages\":{}").toByteArray()),
            "not a zip".toByteArray(),
        )
        for (b in bad) {
            try { Packs.install(ByteArrayInputStream(b), root); fail("должен отклонить") } catch (e: Exception) { assertTrue(e.message!!.isNotBlank()) }
            assertTrue(root.resolve("packs").list().isNullOrEmpty())
        }
    }

    @Test fun deleteAndHelpers() {
        val root = tmp.root
        Packs.install(ByteArrayInputStream(zip("tts.ptl" to "m".toByteArray(), "pack.json" to json.toByteArray())), root)
        val packs = Packs.installed(root)
        assertEquals(mapOf("tat" to "Татарский"), Packs.langs(packs))
        assertEquals("test", Packs.byLang(packs, "tat").single().id)
        assertEquals("test", Packs.forSpeaker(packs, "tat", "tat_1")!!.id)
        assertNull(Packs.forSpeaker(packs, "tat", "x"))
        Packs.delete(root, "test")
        assertTrue(Packs.installed(root).isEmpty())
        assertFalse(root.resolve("packs/test").exists())
    }
}
