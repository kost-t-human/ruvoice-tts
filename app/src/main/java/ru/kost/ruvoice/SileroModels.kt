package ru.kost.ruvoice

import android.content.Context
import android.util.Log
import org.pytorch.IValue
import org.pytorch.LiteModuleLoader
import org.pytorch.LitePyTorchAndroid
import org.pytorch.Module
import org.pytorch.Tensor
import org.pytorch.executorch.EValue
import ru.kost.ruvoice.text.Emoji
import ru.kost.ruvoice.text.Morph
import ru.kost.ruvoice.text.Normalizer
import ru.kost.ruvoice.text.HardE
import ru.kost.ruvoice.text.YoDict
import ru.kost.ruvoice.text.StressModels
import ru.kost.ruvoice.text.SymbolNames
import java.io.File
import kotlin.math.exp

class SileroModels(private val context: Context) : StressModels {
    val data: SileroData get() = data(context)
    // tts_mel (текст → мел) и head (голова вокодера, iSTFT) — TorchScript Lite; backbone (ConvNeXt вокодера,
    // 77 % времени forward) — ExecuTorch/XNNPACK, в 1,4 раза быстрее lite на A32 без потери точности.
    // Тройка либо штатная из assets, либо из папки пака (Pack).
    private var mel: Module? = null
    private var head: Module? = null
    private var backbone: org.pytorch.executorch.Module? = null
    private var acc: Module? = null
    private var homo: Module? = null
    /** Папка тройки mel/backbone/head: null — штатная из assets, иначе каталог пака. */
    private var packDir: File? = null
    /** id загруженного пака, null — штатная модель (или ничего не загружено). */
    @Volatile var loadedPack: String? = null; private set
    val isLoaded get() = mel != null && head != null && backbone != null && acc != null && homo != null

    /** Тройка синтеза — штатная (pack == null) или из папки пака — плюс акцентор и BERT. В памяти одна
     * тройка: другой пак или переход на штатную выгружает прежнюю, акцентор/BERT живут до release(). */
    @Synchronized fun ensureLoaded(pack: Pack? = null) {
        val t = System.currentTimeMillis()
        try {
            if (mel != null && loadedPack != pack?.id) releaseTts()
            if (mel == null) {
                packDir = pack?.dir
                mel = loadLite(pack, "tts_mel.ptl")
                head = loadLite(pack, "head.ptl")
                backbone = loadBackbone()
                loadedPack = pack?.id
                Log.i(TAG, "модель ${pack?.id ?: "v5_5_ru"} загружена за ${System.currentTimeMillis() - t} мс")
            }
            ensureStress()
        } catch (e: Exception) {
            release()
            throw e
        }
    }

    /** Акцентор и BERT — общие для штатной модели и паков; accentor()/homo() грузят только их,
     * чтобы не трогать загруженную тройку синтеза. «Книга с ударениями» зовёт заранее, пока в окне «загрузка». */
    @Synchronized fun ensureStress() {
        if (acc == null) acc = LiteModuleLoader.loadModuleFromAsset(context.assets, "silero/accentor.ptl")
        if (homo == null) homo = LiteModuleLoader.loadModuleFromAsset(context.assets, "silero/homo.ptl")
    }

    private fun loadLite(pack: Pack?, name: String): Module =
        if (pack == null) LiteModuleLoader.loadModuleFromAsset(context.assets, "silero/$name") else LiteModuleLoader.load(File(pack.dir, name).path)

    /** ExecuTorch грузит только с пути: ассет копируем в filesDir один раз (длина — признак той же версии),
     * файл пака берём из его папки. */
    private fun loadBackbone(): org.pytorch.executorch.Module {
        val f = packDir?.let { File(it, "backbone.pte") } ?: File(context.filesDir, "backbone.pte").also { f ->
            val len = context.assets.openFd("silero/backbone.pte").use { it.length }
            if (f.length() != len) context.assets.open("silero/backbone.pte").use { i -> f.outputStream().use { i.copyTo(it) } }
        }
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

    private fun releaseTts() {
        mel?.destroy(); head?.destroy(); backbone?.destroy()
        mel = null; head = null; backbone = null; packDir = null; loadedPack = null
    }

    @Synchronized fun release() {
        releaseTts()
        acc?.destroy(); homo?.destroy(); acc = null; homo = null
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
        ensureStress()
        val out = acc!!.forward(IValue.listFrom(*words.map { IValue.from(it) }.toTypedArray())).toTuple()
        return Pair(softmaxRows(out[0].toTensor()), softmaxRows(out[1].toTensor()))
    }

    override fun homo(ids: List<LongArray>, starts: LongArray, ends: LongArray): FloatArray {
        ensureStress()
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
                   focus: LongArray, symbDurs: Map<Long, Long>, types: Boolean = true): Synth {
        check(mel != null) { "модели не загружены" }   // ensureLoaded(pack) зовёт сервис — какой пак, знает только он
        val n = seq.size.toLong()
        val t = System.nanoTime()
        // 11 аргументов как у v5_ru; type_ids и focus_mask (12-й и 13-й) есть только у v5_5_ru
        val args = arrayListOf(
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
            IValue.from(false))
        if (types) {
            args += IValue.from(Tensor.fromBlob(typeIds, longArrayOf(1, n)))
            args += if (focus.all { it == 0L }) IValue.optionalNull() else IValue.from(Tensor.fromBlob(focus, longArrayOf(1, n)))
        }
        val out = mel!!.forward(*args.toTypedArray()).toTuple()
        val melT = out[0].toTensor()
        val hid = backbone!!.forward(EValue.from(org.pytorch.executorch.Tensor.fromBlob(melT.dataAsFloatArray, melT.shape())))[0].toTensor()
        val audio = head!!.forward(IValue.from(Tensor.fromBlob(hid.dataAsFloatArray, hid.shape())), IValue.from(sampleRate.toLong()),
            IValue.from(0.0), IValue.from(true)).toTensor().dataAsFloatArray
        Log.i(TAG, "forward ${(System.nanoTime() - t) / 1_000_000} мс, звук ${audio.size * 1000L / sampleRate} мс, $n симв.")
        return Synth(audio, out[1].toTensor().dataAsFloatArray)
    }

    companion object {
        /** Один экземпляр на процесс: сервис и настройки живут в одном процессе, и «Разбор» берёт те же
         * акцентор и BERT, что синтез, а не вторую копию на 50 МБ. Жизнью управляет сервис (выгрузка по
         * простою, onDestroy); release() лишь снимает модули, следующий ensure* грузит заново. */
        @Volatile private var instance: SileroModels? = null
        fun shared(context: Context): SileroModels =
            instance ?: synchronized(this) { instance ?: SileroModels(context.applicationContext).also { instance = it } }

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
                YoDict.shared = runCatching { YoDict.open(context.applicationContext) }.onFailure { e -> Log.e(TAG, "eyo_safe.txt не открылся", e) }.getOrNull()
                HardE.shared = runCatching { HardE.open(context.applicationContext) }.onFailure { e -> Log.e(TAG, "hard_e.txt не открылся", e) }.getOrNull()
                Emoji.shared = runCatching { Emoji.open(context.applicationContext) }.onFailure { e -> Log.e(TAG, "emoji_ru.tsv не открылся", e) }.getOrNull()
                SymbolNames.cldr = runCatching { SymbolNames.open(context.applicationContext) }.onFailure { e -> Log.e(TAG, "symbols_ru.tsv не открылся", e) }.getOrNull()
            }
        }
    }
}
