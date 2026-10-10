package ru.kost.ruvoice

import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import java.io.File

/**
 * Замер запроса для журнала, когда синтез не успевает (RTF ≥ [SLOW_RTF]) или включён «Подробный журнал».
 * Отличает причины «модель вдруг считает в 30 раз медленнее» (жалоба 10.10, Galaxy S20 FE: RTF 0,05 → 1,4–1,8
 * через полминуты после запуска и дальше до конца): forward моделей против остального, процессорное время
 * процесса (мало при долгом forward — потокам не дают ядер: cpuset, Restricted), сборки мусора, чтения
 * с флеш-памяти (major faults — выкинутые страницы отображённых файлов), cpuset и нагрев.
 */
object Perf {
    const val SLOW_RTF = 0.5

    class Snap(val cpuMs: Long, val gcCount: Long, val gcMs: Long, val majFlt: Long, val liteMs: Long, val backboneMs: Long)

    fun snap() = Snap(android.os.Process.getElapsedCpuTime(), gcStat("art.gc.gc-count"), gcStat("art.gc.gc-time"), majFlt(),
        SileroModels.liteMs.get(), SileroModels.backboneMs.get())

    private fun gcStat(name: String) = runCatching { Debug.getRuntimeStat(name)?.toLong() }.getOrNull() ?: -1L

    /** majflt — 10-е поле после «(comm)» в /proc/self/stat; имя процесса может содержать пробелы, режем по «)». */
    private fun majFlt() = runCatching {
        File("/proc/self/stat").readText().substringAfterLast(')').trim().split(' ')[9].toLong()
    }.getOrDefault(-1L)

    /** Хвост строки журнала: «forward 9800 мс (бэкбон 7600), ЦП 11200 мс, GC 3/40 мс, с диска 0 стр., cpuset /foreground 0-7, нагрев 0». */
    fun describe(ctx: Context, a: Snap, b: Snap = snap()): String {
        val sb = StringBuilder()
        sb.append(", forward ${b.liteMs - a.liteMs + b.backboneMs - a.backboneMs} мс (бэкбон ${b.backboneMs - a.backboneMs})")
        sb.append(", ЦП ${b.cpuMs - a.cpuMs} мс")
        if (a.gcCount >= 0 && b.gcCount >= 0) sb.append(", GC ${b.gcCount - a.gcCount}/${b.gcMs - a.gcMs} мс")
        if (a.majFlt >= 0 && b.majFlt >= 0) sb.append(", с диска ${b.majFlt - a.majFlt} стр.")
        val cpuset = runCatching { File("/proc/self/cpuset").readText().trim() }.getOrNull()
        val allowed = runCatching { File("/proc/self/status").readLines().firstOrNull { it.startsWith("Cpus_allowed_list:") }
            ?.substringAfter(':')?.trim() }.getOrNull()
        if (cpuset != null || allowed != null) sb.append(", cpuset ${cpuset ?: "?"} ${allowed ?: ""}".trimEnd())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            runCatching { sb.append(", нагрев ${ctx.getSystemService(PowerManager::class.java).currentThermalStatus}") }
        return sb.toString()
    }
}
