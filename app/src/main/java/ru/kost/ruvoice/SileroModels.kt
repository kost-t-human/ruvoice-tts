package ru.kost.ruvoice

import android.content.Context
import android.util.Log
import org.pytorch.IValue
import org.pytorch.LiteModuleLoader
import org.pytorch.Module
import org.pytorch.Tensor
import ru.kost.ruvoice.text.StressModels
import java.io.File
import kotlin.math.exp

class SileroModels(private val context: Context) : StressModels {
    val data: SileroData by lazy { SileroData(context.assets.open("silero/silero_ru.json").bufferedReader().readText()) }
    private var tts: Module? = null
    private var acc: Module? = null
    private var homo: Module? = null
    val isLoaded get() = tts != null

    private fun unpack(name: String): File {
        val dir = File(context.filesDir, "silero").apply { mkdirs() }
        val dst = File(dir, name)
        val expected = context.assets.openFd("silero/$name").use { it.length }
        if (dst.exists() && dst.length() == expected) return dst
        val part = File(dir, "$name.part")
        context.assets.open("silero/$name").use { i -> part.outputStream().use { o -> i.copyTo(o, 1 shl 20) } }
        if (!part.renameTo(dst)) throw java.io.IOException("не удалось сохранить $name")
        return dst
    }

    @Synchronized fun ensureLoaded() {
        if (tts != null) return
        val t = System.currentTimeMillis()
        tts = LiteModuleLoader.load(unpack("tts.ptl").path)
        acc = LiteModuleLoader.load(unpack("accentor.ptl").path)
        homo = LiteModuleLoader.load(unpack("homo.ptl").path)
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

    companion object { const val TAG = "RuVoice" }
}
