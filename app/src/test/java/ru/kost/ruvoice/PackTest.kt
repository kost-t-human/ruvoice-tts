package ru.kost.ruvoice

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PackTest {
    private val packJson = JSONObject(File(TestData.root(), "app/src/test/resources/golden_pack.json").readText()).getJSONObject("pack")
    private val filesDir = Files.createTempDirectory("ruvoice").toFile()

    private fun zip(json: String?, vararg models: String): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { z ->
            if (json != null) { z.putNextEntry(ZipEntry("pack.json")); z.write(json.toByteArray()); z.closeEntry() }
            for (m in models) { z.putNextEntry(ZipEntry(m)); z.write(byteArrayOf(1)); z.closeEntry() }
        }
        return bytes.toByteArray()
    }
    private val full get() = zip(packJson.toString(), "tts_mel.ptl", "backbone.pte", "head.ptl")

    @Test fun parsesPackJson() {
        val p = Pack(packJson.toString(), File("."))
        assertEquals("cis_ru", p.id); assertEquals("MIT", p.license); assertFalse(p.types)
        assertEquals(29, p.speakers.size); assertTrue("ru_alexandr" in p.speakers)
        assertFalse('!' in p.sym.allowed)
    }

    @Test fun rejectsOtherFormat() {
        val e = assertThrows(IllegalArgumentException::class.java) { Pack(packJson.put("format", 1).toString(), File(".")) }
        assertTrue(e.message!!, e.message!!.contains("другой версии"))
    }

    @Test fun installsListsDeletes() {
        val p = Packs.install(ByteArrayInputStream(full), filesDir)
        assertEquals("cis_ru", p.id)
        assertEquals(listOf("cis_ru"), Packs.installed(filesDir).map { it.id })
        assertTrue(File(filesDir, "packs/cis_ru/head.ptl").isFile)
        assertEquals(3L, Packs.installed(filesDir).single().size)
        assertNotNull(Packs.find(filesDir, "cis_ru")); assertNull(Packs.find(filesDir, "nope"))
        Packs.delete(filesDir, "cis_ru")
        assertTrue(Packs.installed(filesDir).isEmpty()); assertFalse(File(filesDir, "packs/cis_ru").exists())
    }

    @Test fun rejectsZipWithoutModelFile() {
        val e = assertThrows(IllegalArgumentException::class.java) { Packs.install(ByteArrayInputStream(zip(packJson.toString(), "tts_mel.ptl", "backbone.pte")), filesDir) }
        assertEquals(Packs.NOT_A_PACK, e.message)
        assertTrue((File(filesDir, "packs").listFiles() ?: emptyArray()).none { it.name.startsWith(".tmp") })
        assertTrue(Packs.installed(filesDir).isEmpty())
    }

    @Test fun deleteRejectsBadId() {
        assertThrows(IllegalArgumentException::class.java) { Packs.delete(filesDir, "..") }
    }

    @Test fun rejectsZipWithoutJsonAndBrokenJson() {
        assertEquals(Packs.NOT_A_PACK, assertThrows(IllegalArgumentException::class.java) { Packs.install(ByteArrayInputStream(zip(null, "tts_mel.ptl", "backbone.pte", "head.ptl")), filesDir) }.message)
        assertEquals(Packs.NOT_A_PACK, assertThrows(IllegalArgumentException::class.java) { Packs.install(ByteArrayInputStream(zip("{oops", "tts_mel.ptl", "backbone.pte", "head.ptl")), filesDir) }.message)
    }

    @Test fun rejectsTruncatedZip() {
        val bytes = full
        val truncated = bytes.copyOfRange(0, bytes.size * 6 / 10)
        val e = assertThrows(IllegalArgumentException::class.java) { Packs.install(ByteArrayInputStream(truncated), filesDir) }
        assertEquals(Packs.NOT_A_PACK, e.message)
        assertTrue((File(filesDir, "packs").listFiles() ?: emptyArray()).none { it.name.startsWith(".tmp") })
    }

    @Test fun reinstallReplaces() {
        Packs.install(ByteArrayInputStream(full), filesDir)
        File(filesDir, "packs/cis_ru/stray.txt").writeText("x")
        Packs.install(ByteArrayInputStream(full), filesDir)
        assertFalse(File(filesDir, "packs/cis_ru/stray.txt").exists())
        assertEquals(1, Packs.installed(filesDir).size)
    }
}
