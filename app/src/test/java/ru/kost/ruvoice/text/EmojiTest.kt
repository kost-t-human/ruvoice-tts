package ru.kost.ruvoice.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.kost.ruvoice.TestData
import java.io.File

/** Эмодзи по имени (assets/emoji_ru.tsv, правило emoji). */
class EmojiTest {
    private val emoji = File(TestData.root(), "app/src/main/assets/emoji_ru.tsv").bufferedReader().useLines { Emoji(it) }

    @Test fun table() = assertTrue(emoji.size > 3000)

    @Test fun inText() {
        assertEquals("Привет, широко улыбается", emoji.apply("Привет 😀"))
        assertEquals("Ок, алое сердце, спасибо", emoji.apply("Ок ❤️ спасибо"))
        assertEquals("большой палец вверх", emoji.apply("👍"))
        assertEquals("Готово, большой палец вверх!", emoji.apply("Готово 👍!"))
        assertEquals("широко улыбается, большой палец вверх", emoji.apply("😀👍"))
    }

    @Test fun sequences() {
        // флаг, тон кожи (без тона в имени), вариант начертания U+FE0F и без него, клавиша
        assertEquals("флаг: Россия", emoji.apply("🇷🇺"))
        assertEquals("машет рукой", emoji.apply("👋🏽"))
        assertEquals(emoji.apply("❤️"), emoji.apply("❤"))
        assertEquals("клавиша 1", emoji.apply("1️⃣"))
        // семья держится на U+200D — одно имя, не три человека
        assertTrue(emoji.apply("👨‍👩‍👧").split(",").size == 1)
    }

    @Test fun repeatsOnce() {
        assertEquals("Ха, смеется до слез", emoji.apply("Ха 😂😂😂"))
        assertEquals("смеется до слез, широко улыбается", emoji.apply("😂😂😀"))
    }

    @Test fun plainTextUntouched() {
        for (t in listOf("Цена 100 ₽, № 5, «цитата» — 1", "# заголовок", "5 * 3"))
            assertEquals(t, emoji.apply(t))
    }

    @Test fun rule() {
        val allowed = "абвгдеёжзийклмнопрстуфхцчшщъыьэюяАБВГДЕЁЖЗИЙКЛМНОПРСТУФХЦЧШЩЪЫЬЭЮЯ .,!?-–:;+«»()"
        Emoji.shared = emoji
        try {
            assertEquals("привет, широко улыбается", Normalizer.prepare("Привет 😀", allowed))
            assertEquals("привет", Normalizer.prepare("Привет 😀", allowed, Rules(off = setOf("emoji"))))
        } finally { Emoji.shared = null }
    }
}
