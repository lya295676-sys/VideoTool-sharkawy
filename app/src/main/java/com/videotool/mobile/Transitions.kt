package com.videotool.mobile

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.media3.effect.MatrixTransformation
import androidx.media3.effect.RgbMatrix
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** 20 حركة × 5 أنماط لون = 100 انتقال. id = حركة*5 + نمط */
object Transitions {
    private const val PIF = 3.14159265f

    val motions = listOf(
        "تلاشي", "انزلاق يسار", "انزلاق يمين", "انزلاق لأعلى", "انزلاق لأسفل",
        "قطري ↖", "قطري ↗", "قطري ↙", "قطري ↘",
        "تكبير", "تصغير", "دوران ↻", "دوران ↺",
        "قلب أفقي", "قلب رأسي", "اهتزاز أفقي", "اهتزاز رأسي", "تأرجح",
        "دوران + تكبير", "دوران + تصغير"
    )
    val fades = listOf("أسود", "وميض", "سماوي", "فوشيا", "بدون")
    val count = motions.size * fades.size

    /** مدة الانتقال عند كل جانب (0.5 ثانية، وأقل لو المقطع قصير) */
    fun durUs(aLenMs: Long, bLenMs: Long): Long {
        val ms = minOf(500L, minOf(aLenMs, bLenMs) / 3L)
        return maxOf(0L, ms) * 1000L
    }

    private val bag = ArrayList<Int>()
    private var lastPicked = -1

    /** انتقال عشوائي بدون تكرار لحد ما الـ 100 كلهم يخلصوا، وبعدها بيخلط من جديد */
    fun nextRandom(): Int {
        if (bag.isEmpty()) {
            val all = (0 until count).shuffled().toMutableList()
            if (all.size > 1 && all[0] == lastPicked) {
                val t = all[0]; all[0] = all[1]; all[1] = t
            }
            bag.addAll(all)
        }
        val v = bag.removeAt(0)
        lastPicked = v
        return v
    }

    fun name(id: Int): String {
        val m = id / 5
        val f = id % 5
        return if (f == 4) motions[m] else motions[m] + " • " + fades[f]
    }

    fun ease(p: Float, id: Int): Float {
        val x = p.coerceIn(0f, 1f)
        return when ((id / 5 + id) % 3) {
            0 -> x * x * (3f - 2f * x)
            1 -> 1f - (1f - x) * (1f - x) * (1f - x)
            else -> x * x * x
        }
    }

    fun progress(relUs: Long, outgoing: Boolean, lenUs: Long, durUs: Long, id: Int): Float {
        if (durUs <= 0L) return 0f
        val raw = if (outgoing) (relUs - (lenUs - durUs)).toFloat() / durUs
        else 1f - relUs.toFloat() / durUs
        return ease(raw.coerceIn(0f, 1f), id)
    }

    private fun rot(m: Matrix, deg: Float, ar: Float) {
        m.postScale(ar, 1f)
        m.postRotate(-deg) // الإحداثيات y لفوق، فنعكس الإشارة عشان ↻ يبقى مع عقارب الساعة فعلاً
        m.postScale(1f / ar, 1f)
    }

    /** p: 0 = شكل طبيعي ... 1 = أقصى تأثير. d: 1 للمقطع الخارج، -1 للداخل */
    fun matrix(motion: Int, p: Float, d: Int, ar: Float): Matrix {
        val m = Matrix()
        val k = 2f * p * d
        when (motion) {
            1 -> m.postTranslate(-k, 0f)
            2 -> m.postTranslate(k, 0f)
            3 -> m.postTranslate(0f, k)
            4 -> m.postTranslate(0f, -k)
            5 -> m.postTranslate(-k, k)
            6 -> m.postTranslate(k, k)
            7 -> m.postTranslate(-k, -k)
            8 -> m.postTranslate(k, -k)
            9 -> {
                val s = if (d > 0) 1f + 2f * p else 1f / (1f + 2f * p)
                m.postScale(s, s)
            }
            10 -> {
                val s = if (d > 0) 1f / (1f + 2f * p) else 1f + 2f * p
                m.postScale(s, s)
            }
            11 -> rot(m, 120f * p * d, ar)
            12 -> rot(m, -120f * p * d, ar)
            13 -> m.postScale(max(0.02f, cos(p * PIF / 2f)), 1f)
            14 -> m.postScale(1f, max(0.02f, cos(p * PIF / 2f)))
            15 -> m.postTranslate(sin(p * 18f) * 0.12f * p, 0f)
            16 -> m.postTranslate(0f, sin(p * 18f) * 0.12f * p)
            17 -> rot(m, sin(p * 12f) * 18f * p, ar)
            18 -> {
                rot(m, 90f * p * d, ar)
                val s = if (d > 0) 1f + 1.5f * p else 1f / (1f + 1.5f * p)
                m.postScale(s, s)
            }
            19 -> {
                rot(m, -90f * p * d, ar)
                val s = if (d > 0) 1f / (1f + 1.5f * p) else 1f + 1.5f * p
                m.postScale(s, s)
            }
            else -> {}
        }
        return m
    }

    fun rgbDiag(id: Int, p: Float): FloatArray {
        val f = id % 5
        var r = 1f
        var g = 1f
        var b = 1f
        when (f) {
            0 -> {
                val k = 1f - p
                r = k; g = k; b = k
            }
            1 -> {
                val k = 1f + 5f * p
                r = k; g = k; b = k
            }
            2 -> {
                r = 1f - 0.9f * p; g = 1f + 3f * p; b = 1f + 4f * p
            }
            3 -> {
                r = 1f + 4f * p; g = 1f - 0.9f * p; b = 1f + 3f * p
            }
            else -> if (id / 5 == 0) {
                val k = 1f - 0.7f * p
                r = k; g = k; b = k
            }
        }
        return floatArrayOf(r, g, b)
    }
}

/** ساعة مشتركة للمقطع: بتحوّل وقت الفريم لوقت نسبي لأول فريم (ومحمية لو جه نداء وهمي عند 0) */
class TrClock {
    private var base = Long.MIN_VALUE
    private var calls = 0
    fun rel(ptsUs: Long): Long {
        calls++
        if (base == Long.MIN_VALUE) base = ptsUs
        else if (calls == 2 && ptsUs - base > 1_000_000L) base = ptsUs
        return ptsUs - base
    }
}

class TrMatrixFx(
    private val id: Int,
    private val outgoing: Boolean,
    private val lenUs: Long,
    private val durUs: Long,
    private val ar: Float,
    private val clock: TrClock
) : MatrixTransformation {
    override fun getMatrix(presentationTimeUs: Long): Matrix {
        val rel = clock.rel(presentationTimeUs)
        val p = Transitions.progress(rel, outgoing, lenUs, durUs, id)
        if (p <= 0f) return Matrix()
        return Transitions.matrix(id / 5, p, if (outgoing) 1 else -1, ar)
    }
}

class TrRgbFx(
    private val id: Int,
    private val outgoing: Boolean,
    private val lenUs: Long,
    private val durUs: Long,
    private val clock: TrClock
) : RgbMatrix {
    override fun getMatrix(presentationTimeUs: Long, useHdr: Boolean): FloatArray {
        val rel = clock.rel(presentationTimeUs)
        val p = Transitions.progress(rel, outgoing, lenUs, durUs, id)
        val c = Transitions.rgbDiag(id, p)
        return floatArrayOf(
            c[0], 0f, 0f, 0f,
            0f, c[1], 0f, 0f,
            0f, 0f, c[2], 0f,
            0f, 0f, 0f, 1f
        )
    }
}

/** حالة الانتقال الحالية للمعاينة الحية (بتتحدث من الواجهة حسب مكان المؤشر) */
class PreviewTrState {
    @Volatile var id = -1
    @Volatile var outgoing = false
    @Volatile var p = 0f
    fun set(i: Int, o: Boolean, pr: Float) {
        p = pr
        outgoing = o
        id = i
    }
}

class PvMatrixFx(private val st: PreviewTrState, private val ar: Float) : MatrixTransformation {
    override fun getMatrix(presentationTimeUs: Long): Matrix {
        val id = st.id
        val p = st.p
        if (id < 0 || p <= 0f) return Matrix()
        return Transitions.matrix(id / 5, p, if (st.outgoing) 1 else -1, ar)
    }
}

class PvRgbFx(private val st: PreviewTrState) : RgbMatrix {
    override fun getMatrix(presentationTimeUs: Long, useHdr: Boolean): FloatArray {
        val id = st.id
        val c = if (id < 0) floatArrayOf(1f, 1f, 1f) else Transitions.rgbDiag(id, st.p)
        return floatArrayOf(
            c[0], 0f, 0f, 0f,
            0f, c[1], 0f, 0f,
            0f, 0f, c[2], 0f,
            0f, 0f, 0f, 1f
        )
    }
}

/** معاينة متحركة صغيرة للانتقال (مقطع A ثم مقطع B) */
class TransitionPreview(ctx: Context) : View(ctx) {
    private var id = 0
    private var t = 0f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1800
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            t = it.animatedValue as Float
            invalidate()
        }
    }

    fun setTransition(i: Int) {
        id = i
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        anim.start()
    }

    override fun onDetachedFromWindow() {
        anim.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val outgoing = t < 0.5f
        val p = if (outgoing) Transitions.ease(t / 0.5f, id)
        else Transitions.ease(1f - (t - 0.5f) / 0.5f, id)

        val toNdc = Matrix()
        toNdc.postTranslate(-w / 2f, -h / 2f)
        toNdc.postScale(2f / w, -2f / h)
        val fromNdc = Matrix()
        toNdc.invert(fromNdc)
        val mm = Matrix(toNdc)
        mm.postConcat(Transitions.matrix(id / 5, p, if (outgoing) 1 else -1, w / h))
        mm.postConcat(fromNdc)

        val d = Transitions.rgbDiag(id, p)
        val cm = ColorMatrix(
            floatArrayOf(
                d[0], 0f, 0f, 0f, 0f,
                0f, d[1], 0f, 0f, 0f,
                0f, 0f, d[2], 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            )
        )
        val filter = ColorMatrixColorFilter(cm)
        paint.colorFilter = filter
        txt.colorFilter = filter
        paint.shader = if (outgoing)
            LinearGradient(0f, 0f, w, h, Pal.cyan, Pal.blue, Shader.TileMode.CLAMP)
        else
            LinearGradient(0f, 0f, w, h, Pal.magenta, Pal.purple, Shader.TileMode.CLAMP)
        txt.textSize = h * 0.5f

        c.save()
        c.concat(mm)
        c.drawRect(0f, 0f, w, h, paint)
        c.drawText(if (outgoing) "A" else "B", w / 2f, h / 2f + h * 0.18f, txt)
        c.restore()
    }
}
