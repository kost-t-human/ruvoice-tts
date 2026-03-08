package ru.kost.ruvoice

import org.json.JSONArray
import java.io.File

object TestData {
    private fun root(): File {
        var f = File(System.getProperty("user.dir"))
        while (!File(f, "app/src/main/assets/silero/silero_ru.json").exists()) f = f.parentFile ?: error("нет silero_ru.json")
        return f
    }
    fun data(): SileroData = SileroData(File(root(), "app/src/main/assets/silero/silero_ru.json").readText())
    fun golden(): JSONArray = JSONArray(File(root(), "app/src/test/resources/golden.json").readText())
}
