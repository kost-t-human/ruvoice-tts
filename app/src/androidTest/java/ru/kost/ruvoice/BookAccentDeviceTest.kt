package ru.kost.ruvoice

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** «Книга с ударениями» на живых настройках: книга из cache/book_in.fb2 → cache/book_out.fb2 (класть и забирать через run-as). */
@RunWith(AndroidJUnit4::class)
class BookAccentDeviceTest {
    @Test fun book() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        // -e abbr false — без раскрытия аббревиатур на этот прогон, настройка потом возвращается
        val prefs = Prefs(ctx)
        val oldAbbr = prefs.accentBookAbbr
        InstrumentationRegistry.getArguments().getString("abbr")?.let { prefs.accentBookAbbr = it.toBoolean() }
        val trace = InstrumentationRegistry.getArguments().getString("trace") != null
        val t0 = System.currentTimeMillis()
        val out = try { BookAccent.make(ctx, File(ctx.cacheDir, "book_in.fb2").readBytes(), "book") { i, n ->
            if (i % 50 == 0 || trace) Log.i("BookAccent", "$i / $n, ${(System.currentTimeMillis() - t0) / 1000} с, native ${android.os.Debug.getNativeHeapAllocatedSize() shr 20} МБ"); true
        } } finally { prefs.accentBookAbbr = oldAbbr }
        assertNotNull(out)
        File(ctx.cacheDir, "book_out.fb2").writeText(out!!)
        Log.i("BookAccent", "готово за ${(System.currentTimeMillis() - t0) / 1000} с")
    }

    /** Разбор без моделей: фейковый акцентор (абзац как есть), чтобы отделить конвейер от fb2-части. */
    @Test fun parseOnly() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val t0 = System.currentTimeMillis()
        val xml = BookAccent.source(File(ctx.cacheDir, "book_in.fb2").readBytes(), "book")
        Log.i("BookAccent", "source ${xml.length} за ${System.currentTimeMillis() - t0} мс, native ${android.os.Debug.getNativeHeapAllocatedSize() shr 20} МБ")
        val out = BookAccent.fb2(xml, BookAccent.Mode.ACUTE, false, true, { i, n ->
            if (i % 200 == 0) Log.i("BookAccent", "$i / $n, native ${android.os.Debug.getNativeHeapAllocatedSize() shr 20} МБ"); true
        }) { listOf(it.lowercase()) }
        Log.i("BookAccent", "fb2 ${out?.length} за ${System.currentTimeMillis() - t0} мс")
    }
}
