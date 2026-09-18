package ru.kost.ruvoice

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BookTest {
    @Test fun fb2SkipsNotesBinariesAndDescription() {
        val fb2 = """<?xml version="1.0" encoding="windows-1251"?><FictionBook><description><title-info><author><first-name>Автор</first-name></author></title-info></description>
            <body><title><p>Глава 1</p></title><p>Шёл Хагрид<a type="note">[1]</a> &mdash; и &#1103;.</p><poem><stanza><v>Строка стиха</v></stanza></poem></body>
            <body name="notes"><p>Сноска Рон</p></body><binary id="c.jpg">AAAA</binary></FictionBook>""".toByteArray(charset("windows-1251"))
        val lines = Book.text(fb2).lines().map { it.trim() }.filter { it.isNotEmpty() }
        assertEquals(listOf("Глава 1", "Шёл Хагрид[1] — и я.", "Строка стиха"), lines)
    }

    @Test fun epubTakesHtmlChaptersOnly() {
        val buf = ByteArrayOutputStream()
        ZipOutputStream(buf).use { z ->
            z.putNextEntry(ZipEntry("mimetype")); z.write("application/epub+zip".toByteArray())
            z.putNextEntry(ZipEntry("OEBPS/ch1.xhtml")); z.write("<html><head><title>x</title></head><body><p>Гарри&nbsp;шёл.</p><div>Рон<br/>Джинни</div></body></html>".toByteArray())
            z.putNextEntry(ZipEntry("OEBPS/toc.ncx")); z.write("<text>Оглавление</text>".toByteArray())
        }
        val lines = Book.text(buf.toByteArray()).lines().map { it.trim() }.filter { it.isNotEmpty() }
        assertEquals(listOf("Гарри шёл.", "Рон", "Джинни"), lines)
    }

    @Test fun txtAsIs() {
        assertEquals("Просто Гарри.\n", Book.text("Просто Гарри.\n".toByteArray()))
    }
}
