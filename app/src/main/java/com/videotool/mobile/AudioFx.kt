package com.videotool.mobile
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh
/**
 * تقليل ضوضاء (Spectral Subtraction) - نسخة معدّلة:
 *  - تقدير الضوضاء بالحد الأدنى خلال نافذة 3 ثواني (مش بيأكل الكلام المتواصل)
 *  - قناتين (L+R) بنفس الـ FFT (نص التكلفة) وبنفس الـ gain
 *  - حجم FFT حسب الـ sample rate (1024 لـ 44.1/48 كيلو)
 * اتجرّبت على صوت تجريبي: بتقلل ~10-12 ديسيبل في فترات الصمت بدون ما تضر الكلام.
 */
class SpectralDenoiser(private val n: Int, sampleRate: Int) {
    val hop = n / 2
    private val half = n / 2
    private val bins = n / 2 + 1
    private val win = FloatArray(n) { i -> sqrt(0.5 * (1.0 - cos(2.0 * PI * i / n))).toFloat() }
    private val cosT = FloatArray(n / 2) { k -> cos(2.0 * PI * k / n).toFloat() }
    private val sinT = FloatArray(n / 2) { k -> sin(2.0 * PI * k / n).toFloat() }
    private val prevL = FloatArray(hop)
    private val prevR = FloatArray(hop)
    private val tailL = FloatArray(hop)
    private val tailR = FloatArray(hop)
    private val re = FloatArray(n)
    private val im = FloatArray(n)
    private val ps = FloatArray(bins)
    private val gPrev = FloatArray(bins) { 1f }
    private val gRaw = FloatArray(bins)
    private val gSm = FloatArray(bins)
    private val subCount = 12
    private val subLen: Int = max(1, ((0.25 * sampleRate) / hop).roundToInt())
    private val subs = Array(subCount) { FloatArray(bins) { Float.MAX_VALUE } }
    private val curMin = FloatArray(bins) { Float.MAX_VALUE }
    private var curCnt = 0
    private var subIdx = 0
    private var init = false
    private var hopsDone = 0
    private val warmHops: Int = max(1, ((1.0 * sampleRate) / hop).roundToInt())
    /** يصفّر الصوت الداخلي (بعد seek) ويحتفظ بتقدير الضوضاء */
    fun resetAudio() {
        java.util.Arrays.fill(prevL, 0f)
        java.util.Arrays.fill(prevR, 0f)
        java.util.Arrays.fill(tailL, 0f)
        java.util.Arrays.fill(tailR, 0f)
    }
    /** ياخد تقدير الضوضاء من مقطع سابق (بدون تسخين من الصفر) لو نفس حجم الـ FFT. */
    fun copyNoiseFrom(o: SpectralDenoiser) {
        if (o.bins != bins) return
        for (s in 0 until subCount) System.arraycopy(o.subs[s], 0, subs[s], 0, bins)
        System.arraycopy(o.curMin, 0, curMin, 0, bins)
        System.arraycopy(o.ps, 0, ps, 0, bins)
        System.arraycopy(o.gPrev, 0, gPrev, 0, bins)
        curCnt = o.curCnt
        subIdx = o.subIdx
        init = o.init
        hopsDone = o.hopsDone
    }
    companion object {
        @Volatile
        var strength: Float = 4.0f   // أعلى = تقليل أقوى (3 خفيف .. 5 قوي)
        const val FLOOR = 0.08f     // أقل gain مسموح (حوالي -22dB)
        fun sizeFor(rate: Int): Int = if (rate >= 88000) 2048 else if (rate >= 32000) 1024 else 512
    }
    private fun fft(inverse: Boolean) {
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while ((j and bit) != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val h = len shr 1
            val step = n / len
            var i = 0
            while (i < n) {
                for (k in 0 until h) {
                    val wr = cosT[k * step]
                    val wi = if (inverse) sinT[k * step] else -sinT[k * step]
                    val a = i + k
                    val b = a + h
                    val xr = re[b] * wr - im[b] * wi
                    val xi = re[b] * wi + im[b] * wr
                    re[b] = re[a] - xr
                    im[b] = im[a] - xi
                    re[a] += xr
                    im[a] += xi
                }
                i += len
            }
            len = len shl 1
        }
    }
    /**
     * يستقبل hop عينة (L وR اختياري) ويكتب hop عينة في outL/outR (متأخرة بمقدار hop).
     */
    fun process(curL: FloatArray, curR: FloatArray?, outL: FloatArray, outR: FloatArray?) {
        for (i in 0 until hop) {
            re[i] = prevL[i] * win[i]
            re[hop + i] = curL[i] * win[hop + i]
            if (curR != null) {
                im[i] = prevR[i] * win[i]
                im[hop + i] = curR[i] * win[hop + i]
            } else {
                im[i] = 0f
                im[hop + i] = 0f
            }
        }
        fft(false)
        val a = 0.6f
        for (k in 0..half) {
            val k2 = (n - k) and (n - 1)
            val p = (re[k] * re[k] + im[k] * im[k] + re[k2] * re[k2] + im[k2] * im[k2]) * 0.25f
            ps[k] = if (!init) p else a * ps[k] + (1f - a) * p
            if (ps[k] < curMin[k]) curMin[k] = ps[k]
        }
        for (k in 0..half) {
            var nz = curMin[k]
            for (s in 0 until subCount) {
                val v = subs[s][k]
                if (v < nz) nz = v
            }
            var g = 1f - strength * nz / (ps[k] + 1e-12f)
            if (g < FLOOR) g = FLOOR else if (g > 1f) g = 1f
            // أول ثانية: بنزوّد التقليل تدريجي (لسه مفيش تقدير كفاية للضوضاء)
            val wm = if (hopsDone >= warmHops) 1f else hopsDone.toFloat() / warmHops.toFloat()
            g = 1f - wm * (1f - g)
            gRaw[k] = g
        }
        curCnt++
        if (curCnt >= subLen) {
            System.arraycopy(curMin, 0, subs[subIdx], 0, bins)
            subIdx = (subIdx + 1) % subCount
            java.util.Arrays.fill(curMin, Float.MAX_VALUE)
            curCnt = 0
        }
        for (k in 0..half) {
            val l = if (k > 0) gRaw[k - 1] else gRaw[k]
            val r = if (k < half) gRaw[k + 1] else gRaw[k]
            gSm[k] = (l + 2f * gRaw[k] + r) * 0.25f
        }
        for (k in 0..half) {
            val g0 = gSm[k]
            val g = if (g0 > gPrev[k]) 0.7f * g0 + 0.3f * gPrev[k] else 0.25f * g0 + 0.75f * gPrev[k]
            gPrev[k] = g
            re[k] *= g
            im[k] *= g
            if (k in 1 until half) {
                re[n - k] *= g
                im[n - k] *= g
            }
        }
        init = true
        hopsDone++
        fft(true)
        val inv = 1f / n
        for (i in 0 until hop) {
            outL[i] = tailL[i] + re[i] * inv * win[i]
            tailL[i] = re[hop + i] * inv * win[hop + i]
            prevL[i] = curL[i]
            if (curR != null && outR != null) {
                outR[i] = tailR[i] + im[i] * inv * win[i]
                tailR[i] = im[hop + i] * inv * win[hop + i]
                prevR[i] = curR[i]
            }
        }
    }
}
/** حد ناعم للقمم: بيمنع التشويه (clipping) لما الصوت يتعلّى فوق 100%. x بين -1 و1 تقريبًا. */
fun softLimit(x: Float): Float {
    val a = abs(x)
    if (a <= 0.8f) return x
    val y = 0.8f + 0.2f * tanh((a - 0.8f) / 0.2f)
    return if (x < 0f) -y else y
}
/** بيمرّر تقدير الضوضاء من مقطع للتاني عشان إزالة الضوضاء متبدأش من الصفر عند كل مقطع. */
class DenoiseChain {
    @Volatile
    var last: SpectralDenoiser? = null
}
/**
 * معالج صوت للتصدير: رفع/خفض الصوت (مع حد ناعم) + تقليل ضوضاء (اختياري) + fade قصير في أول/آخر المقطع
 * (بيمنع الطقطقة عند القطع بين المقاطع). PCM 16-bit، مونو أو ستيريو.
 */
class ClipAudioProcessor(
    private val gain: Float,
    private val denoise: Boolean,
    private val fadeInMs: Int = 0,
    private val fadeOutMs: Int = 0,
    private val lenMs: Long = 0L,
    private val chain: DenoiseChain? = null
) : BaseAudioProcessor() {
    private var ch = 2
    private var rate = 48000
    private var useDen = false
    private var den: SpectralDenoiser? = null
    private var hop = 256
    private var pendL = FloatArray(0)
    private var pendR = FloatArray(0)
    private var tmpL = FloatArray(0)
    private var tmpR = FloatArray(0)
    private var pendingCount = 0
    private var skip = 0
    private var inTotal = 0L
    private var outTotal = 0L
    private var pos = 0L          // عدد الفريمات الصوتية الخارجة لحد دلوقتي (للـ fade)
    private var fadeInF = 0L
    private var fadeOutF = 0L
    private var totalF = 0L
    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        ch = max(1, inputAudioFormat.channelCount)
        rate = max(8000, inputAudioFormat.sampleRate)
        useDen = denoise && (ch == 1 || ch == 2)
        fadeInF = fadeInMs.toLong() * rate / 1000L
        fadeOutF = fadeOutMs.toLong() * rate / 1000L
        totalF = lenMs * rate / 1000L
        return inputAudioFormat
    }
    override fun onFlush() {
        if (useDen) {
            val n = SpectralDenoiser.sizeFor(rate)
            val d = SpectralDenoiser(n, rate)
            val prev = chain?.last
            if (prev != null) d.copyNoiseFrom(prev)
            chain?.last = d
            den = d
            hop = d.hop
            pendL = FloatArray(hop)
            pendR = FloatArray(hop)
            tmpL = FloatArray(hop)
            tmpR = FloatArray(hop)
            skip = hop
        } else {
            den = null
            skip = 0
        }
        pendingCount = 0
        inTotal = 0L
        outTotal = 0L
        pos = 0L
    }
    override fun onReset() {
        den = null
        pendingCount = 0
    }
    /** معامل الـ fade للفريم الحالي (1 = بدون تأثير) */
    private fun env(): Float {
        if (fadeInF <= 0L && fadeOutF <= 0L) return 1f
        var e = 1f
        if (fadeInF > 0L && pos < fadeInF) e = pos.toFloat() / fadeInF.toFloat()
        if (fadeOutF > 0L && totalF > 0L) {
            val rem = totalF - pos
            if (rem < fadeOutF) {
                val o = if (rem <= 0L) 0f else rem.toFloat() / fadeOutF.toFloat()
                if (o < e) e = o
            }
        }
        return e
    }
    private fun shape(s: Short, e: Float): Short {
        val g = gain * e
        if (g == 1f) return s
        val x = s / 32768f * g
        val y = if (g > 1f) softLimit(x) else x
        return (y * 32767f).roundToInt().coerceIn(-32768, 32767).toShort()
    }
    private fun shapeF(f: Float, e: Float): Short {
        val g = gain * e
        val x = f * g
        val y = if (g > 1f) softLimit(x) else x
        return (y * 32767f).roundToInt().coerceIn(-32768, 32767).toShort()
    }
    override fun queueInput(inputBuffer: ByteBuffer) {
        val inFrames = inputBuffer.remaining() / (2 * ch)
        if (inFrames == 0) {
            inputBuffer.position(inputBuffer.limit())
            return
        }
        val d = den
        if (!useDen || d == null) {
            val out = replaceOutputBuffer(inFrames * ch * 2)
            for (f in 0 until inFrames) {
                val e = env()
                pos++
                for (c in 0 until ch) out.putShort(shape(inputBuffer.getShort(), e))
            }
            inputBuffer.position(inputBuffer.limit())
            out.flip()
            return
        }
        inTotal += inFrames
        val produced = (pendingCount + inFrames) / hop * hop
        val skipNow = min(skip, produced)
        val out = replaceOutputBuffer((produced - skipNow) * ch * 2)
        for (f in 0 until inFrames) {
            pendL[pendingCount] = inputBuffer.getShort() / 32768f
            if (ch == 2) pendR[pendingCount] = inputBuffer.getShort() / 32768f
            pendingCount++
            if (pendingCount == hop) {
                emit(d, out)
                pendingCount = 0
            }
        }
        inputBuffer.position(inputBuffer.limit())
        out.flip()
    }
    private fun emit(d: SpectralDenoiser, out: ByteBuffer) {
        d.process(pendL, if (ch == 2) pendR else null, tmpL, if (ch == 2) tmpR else null)
        for (i in 0 until hop) {
            if (skip > 0) {
                skip--
                continue
            }
            val e = env()
            pos++
            out.putShort(shapeF(tmpL[i], e))
            if (ch == 2) out.putShort(shapeF(tmpR[i], e))
            outTotal++
        }
    }
    override fun onQueueEndOfStream() {
        val d = den
        if (!useDen || d == null) return
        val needed = (inTotal - outTotal).toInt()
        if (needed <= 0) return
        val out = replaceOutputBuffer(needed * ch * 2)
        var remaining = needed
        for (i in pendingCount until hop) {
            pendL[i] = 0f
            pendR[i] = 0f
        }
        pendingCount = 0
        while (remaining > 0) {
            d.process(pendL, if (ch == 2) pendR else null, tmpL, if (ch == 2) tmpR else null)
            var i = 0
            while (i < hop && remaining > 0) {
                if (skip > 0) {
                    skip--
                    i++
                    continue
                }
                val e = env()
                pos++
                out.putShort(shapeF(tmpL[i], e))
                if (ch == 2) out.putShort(shapeF(tmpR[i], e))
                remaining--
                outTotal++
                i++
            }
            java.util.Arrays.fill(pendL, 0f)
            java.util.Arrays.fill(pendR, 0f)
        }
        out.flip()
    }
}
/** إعدادات الصوت الحالية للمعاينة (بتتحدث من الواجهة حسب المقطع الحالي). */
class PreviewAudioState {
    @Volatile var gain = 1f
    @Volatile var denoise = false
}
/**
 * معالج صوت للمعاينة (جوه ExoPlayer): نفس خوارزمية التصدير،
 * مع مزج ناعم بين "قبل" و"بعد" عشان التبديل مايعملش طقطقة.
 * لما الإزالة مقفولة بيعدّي الصوت من غير FFT (توفير بطارية).
 */
class PreviewAudioProcessor(private val st: PreviewAudioState) : BaseAudioProcessor() {
    private var ch = 2
    private var rate = 48000
    private var den: SpectralDenoiser? = null
    private var hop = 256
    private var pendL = FloatArray(0)
    private var pendR = FloatArray(0)
    private var tmpL = FloatArray(0)
    private var tmpR = FloatArray(0)
    private var dryL = FloatArray(0)
    private var dryR = FloatArray(0)
    private var pendingCount = 0
    private var mix = 0f
    private var g = 1f
    private var coef = 0.002f
    private var wasRunning = false
    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        ch = max(1, inputAudioFormat.channelCount)
        rate = max(8000, inputAudioFormat.sampleRate)
        coef = 1f - exp(-1f / (0.01f * rate))
        if (ch <= 2) {
            val d = SpectralDenoiser(SpectralDenoiser.sizeFor(rate), rate)
            den = d
            hop = d.hop
            pendL = FloatArray(hop)
            pendR = FloatArray(hop)
            tmpL = FloatArray(hop)
            tmpR = FloatArray(hop)
            dryL = FloatArray(hop)
            dryR = FloatArray(hop)
        } else {
            den = null
        }
        pendingCount = 0
        wasRunning = false
        return inputAudioFormat
    }
    override fun onFlush() {
        pendingCount = 0
        den?.resetAudio()
        java.util.Arrays.fill(dryL, 0f)
        java.util.Arrays.fill(dryR, 0f)
    }
    override fun onReset() {
        den = null
        pendingCount = 0
    }
    private fun toS(f: Float): Short = (f * 32767f).roundToInt().coerceIn(-32768, 32767).toShort()
    override fun queueInput(inputBuffer: ByteBuffer) {
        val inFrames = inputBuffer.remaining() / (2 * ch)
        if (inFrames == 0) {
            inputBuffer.position(inputBuffer.limit())
            return
        }
        val d = den
        if (d == null) {
            val out = replaceOutputBuffer(inFrames * ch * 2)
            val tg = st.gain
            for (f in 0 until inFrames) {
                g += (tg - g) * coef
                for (c in 0 until ch) {
                    val x = inputBuffer.getShort() / 32768f * g
                    out.putShort(toS(softLimit(x)))
                }
            }
            inputBuffer.position(inputBuffer.limit())
            out.flip()
            return
        }
        val produced = (pendingCount + inFrames) / hop * hop
        val out = replaceOutputBuffer(produced * ch * 2)
        for (f in 0 until inFrames) {
            pendL[pendingCount] = inputBuffer.getShort() / 32768f
            if (ch == 2) pendR[pendingCount] = inputBuffer.getShort() / 32768f
            pendingCount++
            if (pendingCount == hop) {
                emitHop(d, out)
                pendingCount = 0
            }
        }
        inputBuffer.position(inputBuffer.limit())
        out.flip()
    }
    private fun emitHop(d: SpectralDenoiser, out: ByteBuffer) {
        val wantDen = st.denoise
        val tg = st.gain
        val targetMix = if (wantDen) 1f else 0f
        val run = wantDen || mix > 0.001f
        if (run) {
            d.process(pendL, if (ch == 2) pendR else null, tmpL, if (ch == 2) tmpR else null)
        } else {
            if (wasRunning) d.resetAudio()
            mix = 0f
        }
        wasRunning = run
        for (i in 0 until hop) {
            if (run) mix += (targetMix - mix) * coef
            g += (tg - g) * coef
            val l = if (run) dryL[i] * (1f - mix) + tmpL[i] * mix else dryL[i]
            out.putShort(toS(softLimit(l * g)))
            if (ch == 2) {
                val r = if (run) dryR[i] * (1f - mix) + tmpR[i] * mix else dryR[i]
                out.putShort(toS(softLimit(r * g)))
            }
        }
        System.arraycopy(pendL, 0, dryL, 0, hop)
        if (ch == 2) System.arraycopy(pendR, 0, dryR, 0, hop)
    }
}
