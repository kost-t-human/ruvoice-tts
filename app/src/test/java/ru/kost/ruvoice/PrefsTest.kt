package ru.kost.ruvoice

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class PrefsTest {
    private val json = """{"format":1,"id":"test","title":"Тест","license":"MIT","source":"x",
        "symbols":"_~|абв ","symbol_to_id":{"_":0,"~":1,"|":2,"а":3,"б":4,"в":5," ":6},"sos":"|","eos":"~",
        "alphabet":"абв","languages":{"tat":{"name":"Татарский","speakers":{"tat_0":0}}}}"""
    private val pack = Pack(json, File("."))

    // Импорт настроек (или удалённый пак) мог оставить в SharedPreferences код языка, для
    // которого сейчас нет установленного пака — getter обязан отдать "rus", а не мусор.
    @Test fun langWithoutInstalledPackFallsBackToRus() {
        assertEquals("rus", Prefs.validLang("tat", emptyList()))
    }

    @Test fun langOfInstalledPackPassesThrough() {
        assertEquals("tat", Prefs.validLang("tat", listOf(pack)))
    }

    @Test fun rusIsAlwaysValid() {
        assertEquals("rus", Prefs.validLang("rus", emptyList()))
    }
}
