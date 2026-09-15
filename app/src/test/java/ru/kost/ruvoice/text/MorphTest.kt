package ru.kost.ruvoice.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.kost.ruvoice.TestData
import java.io.File

/** Настоящий morph.bin из ассетов (tools/aot_morph.py): разборы, хэш как в Python, скорость поиска. */
class MorphTest {
    private val morph = Morph.open(File(TestData.root(), "app/src/main/assets/morph.bin"))

    @Test fun hashMatchesPython() {
        // python3 -c "from tools.aot_morph import fnv1a; print(hex(fnv1a('...')))"
        assertEquals(-0x238390829d77bc1dL, Morph.hash("службы"))
        assertEquals(-0x32ccb7d2b33e76fbL, Morph.hash("ночь"))
        assertEquals(Morph.hash("еж"), Morph.hash("Ёж"))
    }

    @Test fun nouns() {
        val t = morph.tags("службы")
        assertTrue(Morph.isNoun(t)); assertFalse(Morph.isAdjective(t))
        assertEquals(Gender.F, Morph.gender(t))
        assertEquals(setOf(Case.GEN), Morph.nounCases(t, plural = false))
        assertEquals(setOf(Case.NOM, Case.ACC), Morph.nounCases(t, plural = true))
        assertEquals(Gender.F, Morph.gender(morph.tags("ночь")))
        val stol = morph.tags("стол")
        assertEquals(Gender.M, Morph.gender(stol))
        assertEquals(setOf(Case.NOM, Case.ACC), Morph.nounCases(stol, plural = false))
        assertEquals(emptySet<Case>(), Morph.nounCases(stol, plural = true))
        val druz = morph.tags("друзьями")
        assertTrue(Morph.animate(druz))
        assertEquals(setOf(Case.INS), Morph.nounCases(druz, plural = true))
        assertNull(Morph.gender(morph.tags("сирота")))
        assertEquals(Gender.N, Morph.gender(morph.tags("такси")))
    }

    @Test fun adjectives() {
        val t = morph.tags("последних")
        assertTrue(Morph.isAdjective(t)); assertFalse(Morph.isNoun(t))
        // одушевлённый винительный («последних друзей») в таблице не хранится — он совпадает с родительным
        assertEquals(setOf(Case.GEN, Case.PRE), Morph.adjCases(t, null, plural = true))
        assertEquals(setOf(Case.NOM, Case.ACC), Morph.adjCases(morph.tags("последние"), null, plural = true))
        val gos = morph.tags("государственной")
        assertEquals(setOf(Case.GEN, Case.DAT, Case.INS, Case.PRE), Morph.adjCases(gos, Gender.F, plural = false))
        assertEquals(emptySet<Case>(), Morph.adjCases(gos, Gender.M, plural = false))
        assertTrue(Morph.isAdjective(morph.tags("первых")))
    }

    @Test fun unknown() {
        assertEquals(0, morph.tags("нет"))
        assertEquals(0, morph.tags("абракадабрище"))
    }

    @Test fun speed() {
        val words = listOf("службы", "ночь", "стол", "последних", "друзьями", "абракадабрище", "государственной", "минут", "лет", "такси")
        var acc = 0
        val t = System.nanoTime()
        for (i in 0 until 100_000) acc += morph.tags(words[i % words.size])
        println("Morph: 100 тыс. поисков за ${(System.nanoTime() - t) / 1_000_000} мс ($acc)")
    }
}
