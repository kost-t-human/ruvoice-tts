package ru.kost.ruvoice

import android.content.Context
import android.util.Log
import org.pytorch.IValue
import org.pytorch.LiteModuleLoader
import org.pytorch.Module
import org.pytorch.Tensor
import ru.kost.ruvoice.text.StressModels
import kotlin.math.exp

class SileroModels(private val context: Context) : StressModels {
    val data: SileroData get() = data(context)
    private var tts: Module? = null
    private var acc: Module? = null
    private var homo: Module? = null
    val isLoaded get() = tts != null && acc != null && homo != null

    @Synchronized fun ensureLoaded() {
        if (isLoaded) return
        val t = System.currentTimeMillis()
        try {
            tts = LiteModuleLoader.loadModuleFromAsset(context.assets, "silero/tts.ptl")
            acc = LiteModuleLoader.loadModuleFromAsset(context.assets, "silero/accentor.ptl")
            homo = LiteModuleLoader.loadModuleFromAsset(context.assets, "silero/homo.ptl")
        } catch (e: Exception) {
            tts = null; acc = null; homo = null
            throw e
        }
        Log.i(TAG, "модели загружены за ${System.currentTimeMillis() - t} мс")
    }

    @Synchronized fun release() {
        tts?.destroy(); acc?.destroy(); homo?.destroy()
        tts = null; acc = null; homo = null
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

    fun synthesize(seq: LongArray, speakerId: Int, sampleRate: Int, rates: FloatArray, pitches: FloatArray, typeIds: LongArray): FloatArray {
        ensureLoaded()
        val n = seq.size.toLong()
        val out = tts!!.forward(
            IValue.from(Tensor.fromBlob(seq, longArrayOf(1, n))),
            IValue.from(Tensor.fromBlob(longArrayOf(speakerId.toLong()), longArrayOf(1))),
            IValue.from(sampleRate.toLong()),
            IValue.optionalNull(),
            IValue.from(Tensor.fromBlob(rates, longArrayOf(1, n))),
            IValue.from(Tensor.fromBlob(pitches, longArrayOf(1, n))),
            IValue.optionalNull(),
            IValue.optionalNull(),
            IValue.from("cpu"),
            IValue.from(-1L),
            IValue.from(false),
            IValue.from(Tensor.fromBlob(typeIds, longArrayOf(1, n))),
            IValue.optionalNull()
        ).toTuple()
        return out[0].toTensor().dataAsFloatArray
    }

    companion object {
        const val TAG = "RuVoice"
        // json (2.5 МБ) разбирается один раз на процесс — не на каждый SileroModels(context).
        @Volatile private var shared: SileroData? = null
        fun data(context: Context): SileroData = shared ?: synchronized(this) {
            shared ?: SileroData(context.applicationContext.assets.open("silero/silero_ru.json").bufferedReader().readText()).also { shared = it }
        }
    }
}
