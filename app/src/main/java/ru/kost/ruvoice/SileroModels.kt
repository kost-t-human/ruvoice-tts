package ru.kost.ruvoice

import android.content.Context
import android.util.Log
import org.pytorch.IValue
import org.pytorch.LiteModuleLoader
import org.pytorch.LitePyTorchAndroid
import org.pytorch.Module
import org.pytorch.Tensor
import org.pytorch.executorch.EValue
import ru.kost.ruvoice.text.Morph
import ru.kost.ruvoice.text.Normalizer
import ru.kost.ruvoice.text.StressModels
import java.io.File
import kotlin.math.exp

class SileroModels(private val context: Context) : StressModels {
    val data: SileroData get() = data(context)
    // tts_mel (текст → мел) и head (голова вокодера, iSTFT) — TorchScript Lite; backbone (ConvNeXt вокодера,
    // 77 % времени forward) — ExecuTorch/XNNPACK, в 1,4 раза быстрее lite на A32 без потери точности.
    private var mel: Module? = null
    private var head: Module? = null
    private var backbone: org.pytorch.executorch.Module? = null
    private var acc: Module? = null
    private var homo: Module? = null
    val isLoaded get() = mel != null && head != null && backbone != null && acc != null && homo != null

    @Synchronized fun ensureLoaded() {
        if (isLoaded) return
        val t = System.currentTimeMillis()
        try {
            mel = LiteModuleLoader.loadModuleFromAsset(context.assets, "silero/tts_mel.ptl")
            head = LiteModuleLoader.loadModuleFromAsset(context.assets, "silero/head.ptl")
            backbone = loadBackbone()
            acc = LiteModuleLoader.loadModuleFromAsset(context.assets, "silero/accentor.ptl")
            homo = LiteModuleLoader.loadModuleFromAsset(context.assets, "silero/homo.ptl")
        } catch (e: Exception) {
            release()
            throw e
        }
        Log.i(TAG, "модели загружены за ${System.currentTimeMillis() - t} мс")
    }

    /** ExecuTorch грузит только с пути, ассет копируем в filesDir один раз (длина — признак той же версии). */
    private fun loadBackbone(): org.pytorch.executorch.Module {
        val f = File(context.filesDir, "backbone.pte")
        val len = context.assets.openFd("silero/backbone.pte").use { it.length }
        if (f.length() != len) context.assets.open("silero/backbone.pte").use { i -> f.outputStream().use { i.copyTo(it) } }
        return org.pytorch.executorch.Module.load(f.absolutePath, org.pytorch.executorch.Module.LOAD_MODE_MMAP, threads.takeIf { it > 0 } ?: fastCores)
    }

    /** Потоки forward. Правило fast_cores (вкл. по умолчанию) — по числу быстрых ядер, выключено — все.
     * Redmi Note 13 5G (Dimensity 6080, 2×A76 + 6×A55, sysfs показывает 4 быстрых): forward на 1/2/4/6/8
     * потоках = 416/269/207/257/562 мс при 2,3/2,7/3,7/6,4/17 с процессорного времени на запрос —
     * медленные ядра только вредят. Galaxy A32 (2×A75 + 6×A55): 2 потока 1009 мс против 776 мс на всех,
     * процессорного времени 1,9 с против 4,7 с. Mobile-сборка PyTorch пересоздаёт pthreadpool на каждый
     * setNumThreads, менять можно между запросами; у ExecuTorch потоки задаются при загрузке — перегружаем backbone. */
    var threads = 0
        @Synchronized set(n) {
            if (n == field) return
            field = n; LitePyTorchAndroid.setNumThreads(n); Log.i(TAG, "потоков синтеза: $n")
            if (backbone != null) { backbone?.destroy(); backbone = loadBackbone() }
        }

    @Synchronized fun release() {
        mel?.destroy(); head?.destroy(); backbone?.destroy(); acc?.destroy(); homo?.destroy()
        mel = null; head = null; backbone = null; acc = null; homo = null
    }

    private fun softmaxRows(t: Tensor): Array<FloatArray> {
        val rows = t.shape()[0].toInt(); val cols = t.shape()[1].toInt(); val d = t.dataAsFloatArray
        return Array(rows) { r ->
            val row = FloatArray(cols) { d[r * cols + it] }
            val max = row.max(); val e = row.map { exp((it - max).toDouble()) }; val s = e.sum()
            FloatArray(cols) { (e[it] / s).toFloat() }
        }
    }

    override fun accentor(words: List<String>): Pair<Array<FloatArray>, Array<FloatArray>> {
        ensureLoaded()
        val out = acc!!.forward(IValue.listFrom(*words.map { IValue.from(it) }.toTypedArray())).toTuple()
        return Pair(softmaxRows(out[0].toTensor()), softmaxRows(out[1].toTensor()))
    }

    override fun homo(ids: List<LongArray>, starts: LongArray, ends: LongArray): FloatArray {
        ensureLoaded()
        if (ids.isEmpty()) return FloatArray(0)
        val b = ids.size; val len = ids.maxOf { it.size }
        val flat = LongArray(b * len) { data.bertPad.toLong() }
        for ((i, row) in ids.withIndex()) row.copyInto(flat, i * len)
        val logits = homo!!.forward(
            IValue.from(Tensor.fromBlob(flat, longArrayOf(b.toLong(), len.toLong()))),
            IValue.from(Tensor.fromBlob(starts, longArrayOf(b.toLong()))),
            IValue.from(Tensor.fromBlob(ends, longArrayOf(b.toLong())))
        ).toTensor().dataAsFloatArray
        return FloatArray(b) { (1.0 / (1.0 + exp(-logits[it].toDouble()))).toFloat() }
    }

    /** durs[i] — число фреймов, занятых i-м входным символом seq (включая sos/eos). */
    data class Synth(val audio: FloatArray, val durs: FloatArray)

    /**
     * rates/pitches — коэффициенты темпа и высоты по символам (durs_rate, pitch_coefs; высота 0 —
     * «робот»), focus — логическое ударение по символам (focus_mask, 0..3), symbDurs — явная
     * длительность символа в кадрах (symb_durs: индекс в seq → кадры по 12.5 мс), паузы модели.
     */
    fun synthesize(seq: LongArray, speakerId: Int, sampleRate: Int, rates: FloatArray, pitches: FloatArray, typeIds: LongArray,
                   focus: LongArray, symbDurs: Map<Long, Long>): Synth {
        ensureLoaded()
        val n = seq.size.toLong()
        val t = System.nanoTime()
        val out = mel!!.forward(
            IValue.from(Tensor.fromBlob(seq, longArrayOf(1, n))),
            IValue.from(Tensor.fromBlob(longArrayOf(speakerId.toLong()), longArrayOf(1))),
            IValue.from(sampleRate.toLong()),
            if (symbDurs.isEmpty()) IValue.optionalNull() else IValue.dictLongKeyFrom(symbDurs.mapValues { IValue.from(it.value) }),
            IValue.from(Tensor.fromBlob(rates, longArrayOf(1, n))),
            IValue.from(Tensor.fromBlob(pitches, longArrayOf(1, n))),
            IValue.optionalNull(),
            IValue.optionalNull(),
            IValue.from("cpu"),
            IValue.from(-1L),
            IValue.from(false),
            IValue.from(Tensor.fromBlob(typeIds, longArrayOf(1, n))),
            if (focus.all { it == 0L }) IValue.optionalNull() else IValue.from(Tensor.fromBlob(focus, longArrayOf(1, n)))
        ).toTuple()
        val melT = out[0].toTensor()
        val hid = backbone!!.forward(EValue.from(org.pytorch.executorch.Tensor.fromBlob(melT.dataAsFloatArray, melT.shape())))[0].toTensor()
        val audio = head!!.forward(IValue.from(Tensor.fromBlob(hid.dataAsFloatArray, hid.shape())), IValue.from(sampleRate.toLong()),
            IValue.from(0.0), IValue.from(true)).toTensor().dataAsFloatArray
        Log.i(TAG, "forward ${(System.nanoTime() - t) / 1_000_000} мс, звук ${audio.size * 1000L / sampleRate} мс, $n симв.")
        return Synth(audio, out[1].toTensor().dataAsFloatArray)
    }

    companion object {
        /** Ядра быстрее самого медленного кластера (по cpuinfo_max_freq): 2 на A32, 4 на Dimensity 6080.
         * Все ядра, если кластер один или sysfs закрыт. Прибить потоки к ядрам из Java нельзя,
         * планировщик сам сажает тяжёлые потоки на быстрые. */
        val fastCores: Int by lazy {
            val freqs = File("/sys/devices/system/cpu").listFiles { f -> f.name.matches(Regex("cpu\\d+")) }
                ?.mapNotNull { runCatching { File(it, "cpufreq/cpuinfo_max_freq").readText().trim().toLong() }.getOrNull() }
                ?: emptyList()
            val fast = freqs.minOrNull()?.let { m -> freqs.count { it > m } } ?: 0
            if (fast > 0) fast else Runtime.getRuntime().availableProcessors()
        }
        const val TAG = "RuVoice"
        // json (2.5 МБ) разбирается один раз на процесс — не на каждый SileroModels(context).
        @Volatile private var shared: SileroData? = null
        fun data(context: Context): SileroData = shared ?: synchronized(this) {
            shared ?: SileroData(context.applicationContext.assets.open("silero/silero_ru.json").bufferedReader().readText()).also {
                shared = it
                // Морфология для нормализатора — mmap ассета, один раз на процесс.
                Normalizer.morph = runCatching { Morph.open(context.applicationContext) }.onFailure { e -> Log.e(TAG, "morph.bin не открылся", e) }.getOrNull()
            }
        }
    }
}
