package ru.kost.ruvoice.audio

import java.nio.ByteBuffer
import kotlin.math.abs
import java.nio.ByteOrder

/** Единственное место, где float становится short. Насыщение, не приведение типа: заворот знака и есть щелчок. */
object Pcm {
    fun toPcm16(samples: FloatArray): ShortArray = ShortArray(samples.size) { i ->
        val v = Math.round(samples[i] * 32767f)
        v.coerceIn(-32767, 32767).toShort()
    }

    /** Потолок усиления: и ползунка «Громкость», и всей суммы поправок английского. */
    const val MAX_GAIN = 3f

    /** Громкость голоса: множитель поверх того, что даёт модель. Если пик и после множителя под [CEIL] — ровно в g раз
     * (при ×1 без изменений). Иначе, при любом g, — ограничитель
     * с упреждением: там, где пик вылез бы за [CEIL], плавно (за ~[LIMIT_MS] мс до и после) убавляется усиление
     * всего звука, форма волны не гнётся. Изгиб каждого сэмпла (tanh) при ×3 уже слышен как хрип. */
    fun gain(samples: FloatArray, g: Float, sampleRate: Int) {
        if (samples.isEmpty()) return
        // и при ×1: крик («Стой!») модель выдаёт с пиком 1,0 — без потолка он срезается в toPcm16
        var peak = 0f; for (s in samples) peak = maxOf(peak, Math.abs(s))
        if (peak * g <= CEIL) { if (g != 1f) for (i in samples.indices) samples[i] *= g; return }
        val n = samples.size
        // сколько можно дать каждому сэмплу, чтобы он остался под потолком
        val need = FloatArray(n) { val a = Math.abs(samples[it]) * g; if (a > CEIL) CEIL / a else 1f }
        val half = maxOf(1, sampleRate * LIMIT_MS / 1000)
        // минимум по окну ±half, затем среднее по окну ±half/2: каждое окно среднего целиком внутри окна минимума,
        // поэтому огибающая нигде не выше need — пики не вылезают, а усиление меняется без ступенек
        val env = boxMean(slidingMin(need, half), half / 2)
        for (i in 0 until n) samples[i] = (samples[i] * g * env[i]).coerceIn(-CEIL, CEIL)
    }
    private const val CEIL = 0.97f
    private const val LIMIT_MS = 10

    /** Минимум по окну [i-r, i+r], монотонная очередь — O(n). */
    private fun slidingMin(a: FloatArray, r: Int): FloatArray {
        val n = a.size
        val out = FloatArray(n)
        val q = IntArray(n); var head = 0; var tail = 0
        var j = 0
        for (i in 0 until n) {
            while (j < n && j <= i + r) {
                while (tail > head && a[q[tail - 1]] >= a[j]) tail--
                q[tail++] = j; j++
            }
            while (q[head] < i - r) head++
            out[i] = a[q[head]]
        }
        return out
    }

    /** Среднее по окну [i-r, i+r] (у краёв — по той части, что есть). */
    private fun boxMean(a: FloatArray, r: Int): FloatArray {
        val n = a.size
        val pre = DoubleArray(n + 1)
        for (i in 0 until n) pre[i + 1] = pre[i] + a[i]
        return FloatArray(n) { i ->
            val lo = maxOf(0, i - r); val hi = minOf(n, i + r + 1)
            ((pre[hi] - pre[lo]) / (hi - lo)).toFloat()
        }
    }

    fun fadeEdges(samples: FloatArray, sampleRate: Int, ms: Int = 5) {
        val n = minOf(sampleRate * ms / 1000, samples.size / 2)
        if (n <= 0) return
        for (i in 0 until n) {
            val g = i.toFloat() / n
            samples[i] *= g
            samples[samples.size - 1 - i] *= g
        }
    }

    /** Частота чужого движка (EnglishProxy, обычно 22050 или 24000) → наша. Линейная интерполяция:
     * речь почти вся ниже 8 кГц, на слух разницы с полифазным фильтром нет. */
    fun resample(pcm: ShortArray, from: Int, to: Int): ShortArray {
        if (from == to || pcm.isEmpty() || from <= 0 || to <= 0) return pcm
        val n = (pcm.size.toLong() * to / from).toInt().coerceAtLeast(1)
        val step = from.toDouble() / to
        return ShortArray(n) { i ->
            val x = i * step
            val a = x.toInt().coerceAtMost(pcm.size - 1)
            val b = (a + 1).coerceAtMost(pcm.size - 1)
            val t = x - a
            Math.round(pcm[a] * (1 - t) + pcm[b] * t).toInt().toShort()
        }
    }

    /** Громкость речи без пауз: RMS по кадрам 20 мс, в счёт — кадры не тише 1/10 самого громкого.
     * Тишина между словами и до фразы у разных движков разная и среднее по всему звуку врёт. 0 — тишина. */
    fun voicedRms(samples: FloatArray, sampleRate: Int): Float {
        val frame = maxOf(1, sampleRate / 50)
        val rms = ArrayList<Double>()
        var i = 0
        while (i < samples.size) {
            val end = minOf(samples.size, i + frame)
            var s = 0.0
            for (k in i until end) s += samples[k].toDouble() * samples[k]
            rms += Math.sqrt(s / (end - i))
            i = end
        }
        val max = rms.maxOrNull() ?: return 0f
        if (max <= 1e-4) return 0f
        val voiced = rms.filter { it >= max / 10 }
        return Math.sqrt(voiced.sumOf { it * it } / voiced.size).toFloat()
    }

    /** Усиление, которое подтягивает [other] к громкости [reference] (voicedRms), в пределах ×0,5…×2:
     * дальше — уже не выравнивание, а порча звука. 1 — если мерить нечем. */
    fun matchGain(reference: Float, other: Float): Float =
        if (reference <= 0f || other <= 0f) 1f else (reference / other).coerceIn(0.5f, 2f)

    /** Итог для английского куска: выравнивание × громкость английского × общая, не больше [MAX_GAIN].
     * Без потолка три множителя по ×2 дают ×8 — ограничитель такое уже не вытягивает. */
    fun englishGain(match: Float, enVolume: Float, volume: Float): Float = (match * enVolume * volume).coerceIn(0.125f, MAX_GAIN)

    fun toFloat(pcm: ShortArray): FloatArray = FloatArray(pcm.size) { pcm[it] / 32767f }

    /** Тишина по краям звука, в сэмплах: до первого и после последнего сэмпла громче [floor] (−40 дБ). Весь звук тихий — (size, 0). */
    fun silentEdges(pcm: ShortArray, floor: Int = 328): Pair<Int, Int> {
        val a = pcm.indexOfFirst { abs(it.toInt()) > floor }
        if (a < 0) return pcm.size to 0
        return a to pcm.size - 1 - pcm.indexOfLast { abs(it.toInt()) > floor }
    }

    fun silence(sampleRate: Int, ms: Int): ShortArray = ShortArray(maxOf(0, sampleRate * ms / 1000))

    fun toBytes(pcm: ShortArray): ByteArray {
        val buf = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        buf.asShortBuffer().put(pcm)
        return buf.array()
    }
}
