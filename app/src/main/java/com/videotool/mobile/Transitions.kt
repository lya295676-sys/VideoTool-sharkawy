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
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.opengl.GLES20
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.media3.common.GlEffect
import androidx.media3.common.GlShaderProgram
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.MatrixTransformation
import androidx.media3.effect.RgbMatrix
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

/**
 * مكتبة انتقالات احترافية (200+).
 * كل انتقال = حركة (Matrix) + "شكل" على كارت الشاشة (Shader حقيقي: blur / glitch / pixel / iris / wipe / burn ...).
 * المقطع الخارج بيتأثر في آخر 0.7 ثانية، والداخل في أول 0.7 ثانية.
 * التسارع: الخروج بيسرّع ناحية القطع والدخول بيبطّأ بعده، فالسرعة متصلة (من غير "خطفة").
 */
private class Pre(val name: String, val motion: Int, val look: Int, val a: Float, val b: Float, val col: Int)

object Transitions {
    private const val PIF = 3.14159265f

    // ---- أنواع الـ shader ----
    private const val L_NONE = 0
    private const val L_DIP = 1
    private const val L_MBLUR = 2
    private const val L_ZBLUR = 3
    private const val L_SBLUR = 4
    private const val L_GAUSS = 5
    private const val L_PIXEL = 6
    private const val L_RGB = 7
    private const val L_GLITCH = 8
    private const val L_WAVE = 9
    private const val L_SWIRL = 10
    private const val L_IRIS = 11
    private const val L_WIPE = 12
    private const val L_BLINDS = 13
    private const val L_TILES = 14
    private const val L_DISSOLVE = 15
    private const val L_BURN = 16
    private const val L_LEAK = 17
    private const val L_DOORS = 18
    private const val L_STRETCH = 19
    private const val L_TV = 20
    private const val L_SLICE = 21
    private const val L_KALEI = 22
    private const val L_HALFTONE = 23
    private const val L_VIGNETTE = 24
    private const val L_STATIC = 25

    private val COLS = arrayOf(
        floatArrayOf(0f, 0f, 0f),
        floatArrayOf(1f, 1f, 1f),
        floatArrayOf(0.10f, 0.85f, 1f),
        floatArrayOf(0.95f, 0.20f, 0.90f),
        floatArrayOf(1f, 0.72f, 0.20f),
        floatArrayOf(1f, 0.20f, 0.15f),
        floatArrayOf(0.20f, 0.95f, 0.45f),
        floatArrayOf(0.55f, 0.35f, 1f),
        floatArrayOf(1f, 0.45f, 0.08f),
        floatArrayOf(1f, 0.45f, 0.70f)
    )
    private val COL_N = arrayOf("Black", "White", "Cyan", "Magenta", "Gold", "Red", "Green", "Violet", "Orange", "Pink")
    private val DIR_N = arrayOf("Left", "Right", "Up", "Down", "Up-Left", "Up-Right", "Down-Left", "Down-Right")
    private val DIR_M = intArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
    private val DIR_A = floatArrayOf(0f, 0f, 1.5708f, 1.5708f, 2.3562f, 0.7854f, 0.7854f, 2.3562f)
    private val WIPE_A = floatArrayOf(3.1416f, 0f, 1.5708f, -1.5708f, 2.3562f, 0.7854f, -2.3562f, -0.7854f)

    private val presets = ArrayList<Pre>()
    private fun add(name: String, m: Int, look: Int, a: Float = 0f, b: Float = 1f, col: Int = 0) {
        presets.add(Pre(name, m, look, a, b, col))
    }

    init {
        buildCatalog()
    }

    val count: Int get() = presets.size

    private fun buildCatalog() {
        // 1) تلاشي وألوان
        for (c in 0..7) add("Fade " + COL_N[c], 0, L_DIP, 0f, 1f, c)
        add("Dreamy Fade", 0, L_GAUSS, 0f, 1.6f, 0)
        add("Soft Dissolve", 0, L_GAUSS, 0f, 0.8f, 0)
        add("Cinematic Dip", 9, L_DIP, 0f, 1f, 0)
        add("Pulse Fade", 20, L_DIP, 0f, 1f, 0)
        // 2) Whip + Slide
        for (i in 0 until 8) add("Whip " + DIR_N[i], DIR_M[i], L_MBLUR, DIR_A[i], 1.6f, 0)
        for (i in 0 until 8) add("Slide Fade " + DIR_N[i], DIR_M[i], L_DIP, 0f, 1f, 0)
        for (i in 0 until 4) add("Push Blur " + DIR_N[i], DIR_M[i], L_GAUSS, 0f, 1.2f, 0)
        for (i in 0 until 4) add("Glide Flash " + DIR_N[i], DIR_M[i], L_DIP, 0f, 1f, 1)
        // 3) Zoom
        add("Zoom In Blur", 9, L_ZBLUR, 0f, 1.5f, 0)
        add("Zoom Out Blur", 10, L_ZBLUR, 0f, 1.5f, 0)
        add("Zoom Punch", 20, L_ZBLUR, 0f, 1.2f, 0)
        add("Zoom Flash White", 9, L_DIP, 0f, 1f, 1)
        add("Zoom Flash Gold", 9, L_DIP, 0f, 1f, 4)
        add("Zoom Out Flash", 10, L_DIP, 0f, 1f, 1)
        add("Zoom Fade", 9, L_DIP, 0f, 1f, 0)
        add("Zoom Spin CW", 18, L_ZBLUR, 0f, 1f, 0)
        add("Zoom Spin CCW", 19, L_ZBLUR, 0f, 1f, 0)
        add("Dolly Blur", 10, L_VIGNETTE, 0f, 1f, 0)
        add("Zoom Glitch", 9, L_GLITCH, 0f, 1f, 2)
        add("Zoom Pixel", 9, L_PIXEL, 0f, 1f, 0)
        // 4) دوران
        add("Spin CW Blur", 11, L_SBLUR, 0f, 1.4f, 0)
        add("Spin CCW Blur", 12, L_SBLUR, 0f, 1.4f, 0)
        add("Roll Fade", 11, L_DIP, 0f, 1f, 0)
        add("Roll Flash", 12, L_DIP, 0f, 1f, 1)
        add("Swing Blur", 17, L_SBLUR, 0f, 1f, 0)
        add("Swing Fade", 17, L_DIP, 0f, 1f, 0)
        add("Tilt Slide Left", 22, L_MBLUR, 0f, 1.2f, 0)
        add("Tilt Slide Right", 21, L_MBLUR, 0f, 1.2f, 0)
        add("Spin Zoom Burn", 18, L_BURN, 0f, 1f, 8)
        add("Spin Zoom Out", 19, L_GAUSS, 0f, 1.3f, 0)
        // 5) قلب
        add("Flip Horizontal", 13, L_DIP, 0f, 1f, 0)
        add("Flip Vertical", 14, L_DIP, 0f, 1f, 0)
        add("Flip Flash H", 13, L_DIP, 0f, 1f, 1)
        add("Flip Flash V", 14, L_DIP, 0f, 1f, 1)
        add("Flip Glitch H", 13, L_GLITCH, 0f, 1f, 2)
        add("Flip Glitch V", 14, L_GLITCH, 0f, 1f, 3)
        add("Flip RGB H", 13, L_RGB, 0f, 1.4f, 0)
        add("Flip RGB V", 14, L_RGB, 1.5708f, 1.4f, 0)
        // 6) اهتزاز
        add("Shake RGB H", 15, L_RGB, 0f, 1.5f, 0)
        add("Shake RGB V", 16, L_RGB, 1.5708f, 1.5f, 0)
        add("Shake Flash H", 15, L_DIP, 0f, 1f, 1)
        add("Shake Flash V", 16, L_DIP, 0f, 1f, 1)
        add("Shake Glitch H", 15, L_GLITCH, 0f, 1.2f, 0)
        add("Shake Glitch V", 16, L_GLITCH, 0f, 1.2f, 3)
        add("Earthquake", 15, L_GAUSS, 0f, 1f, 0)
        add("Impact Shake", 16, L_ZBLUR, 0f, 1f, 0)
        add("Wobble Swing", 17, L_WAVE, 0f, 1f, 0)
        // 7) Glitch و RGB
        for (c in intArrayOf(0, 2, 3, 5)) add("Glitch Slice " + COL_N[c], 0, L_GLITCH, 0f, 1f, c)
        for (i in 0 until 4) add("RGB Split " + DIR_N[i], 0, L_RGB, DIR_A[i], 1.6f, 0)
        add("Signal Error", 0, L_STATIC, 0f, 1f, 0)
        add("VHS Noise", 0, L_GLITCH, 0f, 0.7f, 6)
        add("Digital Distort", 0, L_WAVE, 1f, 1.4f, 0)
        add("Data Corrupt", 15, L_GLITCH, 0f, 1.6f, 5)
        add("Chromatic Pulse", 20, L_RGB, 0f, 1.4f, 0)
        add("Static Out", 0, L_STATIC, 1f, 1f, 0)
        add("Glitch Whip Left", 1, L_GLITCH, 0f, 1.2f, 2)
        add("Glitch Whip Right", 2, L_GLITCH, 0f, 1.2f, 3)
        // 8) بكسل
        add("Pixelate", 0, L_PIXEL, 0f, 1f, 0)
        add("Pixelate Fade", 0, L_PIXEL, 1f, 1f, 0)
        add("Mosaic Spin CW", 11, L_PIXEL, 0f, 1f, 0)
        add("Mosaic Spin CCW", 12, L_PIXEL, 0f, 1f, 0)
        add("Pixel Burst", 9, L_PIXEL, 1f, 1f, 0)
        add("Pixel Slide Left", 1, L_PIXEL, 0f, 1f, 0)
        add("Pixel Slide Right", 2, L_PIXEL, 0f, 1f, 0)
        add("Pixel Shake", 15, L_PIXEL, 0f, 1f, 0)
        // 9) Blur وموجات
        add("Gaussian Out", 0, L_GAUSS, 0f, 2f, 0)
        add("Blur Zoom", 9, L_GAUSS, 0f, 1.6f, 0)
        add("Blur Spin", 11, L_GAUSS, 0f, 1.6f, 0)
        for (i in 0 until 4) add("Blur Slide " + DIR_N[i], DIR_M[i], L_MBLUR, DIR_A[i], 1f, 0)
        add("Heat Haze", 0, L_WAVE, 0f, 0.8f, 0)
        add("Water Ripple", 0, L_WAVE, 1f, 1f, 0)
        add("Water Ripple Deep", 0, L_WAVE, 1f, 1.8f, 0)
        add("Wavy Band", 0, L_WAVE, 0f, 1.6f, 0)
        add("Underwater", 10, L_WAVE, 1f, 1.2f, 0)
        add("Wave Zoom", 9, L_WAVE, 1f, 1f, 0)
        // 10) دوامة
        add("Swirl CW", 0, L_SWIRL, 0f, 1f, 0)
        add("Swirl CCW", 0, L_SWIRL, 1f, 1f, 0)
        add("Whirlpool", 10, L_SWIRL, 0f, 1.4f, 0)
        add("Vortex Zoom", 9, L_SWIRL, 1f, 1.2f, 0)
        add("Twist Fade", 0, L_SWIRL, 0f, 0.7f, 0)
        add("Spiral Flash", 11, L_SWIRL, 0f, 1f, 1)
        // 11) Iris وأقنعة
        for (c in 0..1) {
            add("Circle Iris " + COL_N[c], 0, L_IRIS, 0f, c.toFloat(), 0)
            add("Diamond Iris " + COL_N[c], 0, L_IRIS, 1f, c.toFloat(), 0)
            add("Heart Iris " + COL_N[c], 0, L_IRIS, 2f, c.toFloat(), 0)
            add("Square Iris " + COL_N[c], 0, L_IRIS, 3f, c.toFloat(), 0)
            add("Eye Close " + COL_N[c], 0, L_IRIS, 4f, c.toFloat(), 0)
        }
        add("Circle Iris Rainbow", 0, L_IRIS, 0f, 0f, 2)
        add("Circle Iris Gold", 0, L_IRIS, 0f, 0f, 4)
        add("Heart Iris Pink", 0, L_IRIS, 2f, 0f, 9)
        add("Diamond Iris Neon", 0, L_IRIS, 1f, 0f, 3)
        add("Iris Zoom", 9, L_IRIS, 0f, 0f, 0)
        add("Iris Spin", 11, L_IRIS, 0f, 0f, 0)
        // 12) Wipes
        for (i in 0 until 8) add("Wipe " + DIR_N[i], 0, L_WIPE, WIPE_A[i], 1f, 0)
        for (i in 0 until 4) add("Glow Wipe " + DIR_N[i], 0, L_WIPE, WIPE_A[i], 1f, 2)
        for (i in 0 until 4) add("Slide Wipe " + DIR_N[i], DIR_M[i], L_WIPE, WIPE_A[i], 1f, 0)
        add("Barn Doors H", 0, L_DOORS, 0f, 1f, 0)
        add("Barn Doors V", 0, L_DOORS, 1f, 1f, 0)
        add("Blinds Horizontal", 0, L_BLINDS, 0f, 8f, 0)
        add("Blinds Fine H", 0, L_BLINDS, 0f, 18f, 0)
        add("Blinds Vertical", 0, L_BLINDS, 1f, 8f, 0)
        add("Blinds Fine V", 0, L_BLINDS, 1f, 18f, 0)
        add("Venetian", 0, L_BLINDS, 0f, 5f, 0)
        add("Zebra", 0, L_BLINDS, 1f, 28f, 0)
        add("Shutter Flicker", 15, L_BLINDS, 0f, 12f, 0)
        add("Shutter Spin", 11, L_BLINDS, 1f, 10f, 0)
        // 13) مربعات وبلاط
        add("Grid Break S", 0, L_TILES, 0f, 8f, 0)
        add("Grid Break M", 0, L_TILES, 0f, 14f, 0)
        add("Grid Break L", 0, L_TILES, 0f, 24f, 0)
        add("Block Disperse", 9, L_TILES, 0f, 12f, 0)
        add("Tile Around", 11, L_TILES, 0f, 10f, 0)
        add("Checker Dissolve", 0, L_TILES, 0f, 32f, 0)
        add("Heart Tile Out", 0, L_HALFTONE, 1f, 9f, 0)
        add("Dot Halftone", 0, L_HALFTONE, 0f, 22f, 0)
        add("Dot Halftone Big", 0, L_HALFTONE, 0f, 10f, 0)
        add("Mosaic Dissolve", 0, L_TILES, 0f, 40f, 0)
        add("Tile Break Spin", 12, L_TILES, 0f, 16f, 0)
        // 14) ضوء وأفلام
        for (c in intArrayOf(8, 5, 4, 2)) add("Film Burn " + COL_N[c], 0, L_BURN, 0f, 1f, c)
        for (c in intArrayOf(4, 8, 3, 2, 7)) add("Light Leak " + COL_N[c], 0, L_LEAK, 0f, 1f, c)
        add("Lens Flare Sweep", 10, L_LEAK, 0f, 1f, 1)
        add("Glow Bloom", 9, L_BURN, 0f, 1f, 1)
        add("Flash Strobe", 15, L_DIP, 0f, 1f, 1)
        add("Divine Ray", 9, L_LEAK, 0f, 1f, 4)
        add("Shine Sweep", 1, L_LEAK, 0f, 1f, 1)
        add("Sunset Wash", 0, L_BURN, 0f, 1f, 9)
        add("Neon Burn", 0, L_BURN, 0f, 1f, 3)
        add("Cyber Leak", 2, L_LEAK, 0f, 1f, 2)
        // 15) تبخّر وحرق
        for (c in intArrayOf(0, 8, 2, 3, 6)) add("Dust Gust " + COL_N[c], 0, L_DISSOLVE, 0f, 1f, c)
        add("Liquid Burn", 0, L_DISSOLVE, 0f, 0.6f, 8)
        add("Fire Edge", 0, L_DISSOLVE, 0f, 1.6f, 5)
        add("Ash Fade", 0, L_DISSOLVE, 0f, 0.5f, 0)
        add("Ink Bleed", 10, L_DISSOLVE, 0f, 0.8f, 7)
        add("Smoke Out", 0, L_DISSOLVE, 0f, 0.3f, 1)
        add("Dissolve Zoom", 9, L_DISSOLVE, 0f, 1f, 4)
        // 16) انشقاق وتمزيق
        add("Split Open H", 0, L_DOORS, 0f, 1f, 0)
        add("Split Slide", 1, L_DOORS, 1f, 1f, 0)
        add("Silky Tear", 0, L_STRETCH, 1f, 1.2f, 0)
        add("Stretch Fade", 0, L_STRETCH, 0f, 1f, 0)
        add("Stretch Squeeze", 0, L_STRETCH, 0f, 2f, 0)
        add("Fold Out", 13, L_STRETCH, 0f, 1f, 0)
        for (i in 0 until 2) add("Slice Shift " + (if (i == 0) "H" else "V"), 0, L_SLICE, i.toFloat(), 8f, 0)
        add("Slice Shift Fine", 0, L_SLICE, 0f, 20f, 0)
        add("Slice Spin", 11, L_SLICE, 1f, 10f, 0)
        // 17) تلفاز
        add("TV Off", 0, L_TV, 0f, 1f, 0)
        add("CRT Collapse", 14, L_TV, 0f, 1f, 0)
        add("Old Film Flicker", 15, L_STATIC, 1f, 1f, 0)
        add("Signal Lost", 0, L_STATIC, 0f, 1f, 6)
        add("Scanline Out", 0, L_GLITCH, 0f, 0.5f, 0)
        // 18) مرآة وكاليدوسكوب
        add("Mirror Fold", 0, L_KALEI, 0f, 1f, 0)
        add("Kaleidoscope 3", 0, L_KALEI, 0f, 3f, 0)
        add("Kaleidoscope 5", 0, L_KALEI, 0f, 5f, 0)
        add("Kaleido Spin", 11, L_KALEI, 0f, 4f, 0)
        add("Kaleido Zoom", 9, L_KALEI, 0f, 6f, 0)
        add("Kaleido Flash", 0, L_KALEI, 0f, 4f, 1)
        // 19) كومبو احترافي
        add("Whip Glitch Up", 3, L_GLITCH, 0f, 1f, 2)
        add("Whip Glitch Down", 4, L_GLITCH, 0f, 1f, 3)
        add("Zoom Burn", 9, L_BURN, 0f, 1f, 8)
        add("Spin Burn", 11, L_BURN, 0f, 1f, 5)
        add("Blur Whip Flash", 1, L_MBLUR, 0f, 2f, 1)
        add("Shake Burn", 15, L_BURN, 0f, 1f, 4)
        add("Pixel Whip", 2, L_PIXEL, 1f, 1f, 0)
        add("Wave Whip", 3, L_WAVE, 1f, 1.4f, 0)
        add("Swirl Zoom Flash", 9, L_SWIRL, 0f, 1f, 1)
        add("Iris Flash", 9, L_IRIS, 0f, 1f, 1)
        add("Vignette Close", 10, L_VIGNETTE, 0f, 1f, 0)
        add("Shining Ray", 9, L_VIGNETTE, 1f, 1f, 0)
        add("Whip Pan Fade L", 1, L_MBLUR, 0f, 2.2f, 0)
        add("Whip Pan Fade R", 2, L_MBLUR, 0f, 2.2f, 0)
        add("Rainbow Split Spin", 11, L_RGB, 0.7854f, 2f, 0)
        add("Neon Glitch Zoom", 9, L_GLITCH, 0f, 1.4f, 3)
        add("Mask Wipe Zoom", 9, L_WIPE, 0f, 1f, 2)
        add("Double Slice", 15, L_SLICE, 0f, 6f, 0)
        add("Tile Flash", 0, L_TILES, 0f, 18f, 1)
        add("Cosmic Swirl", 11, L_SWIRL, 1f, 1.3f, 7)
    }

    // ---------- بيانات الانتقال ----------
    private fun pre(id: Int): Pre = presets[id.coerceIn(0, presets.size - 1)]
    fun name(id: Int): String = pre(id).name
    fun lookOf(id: Int): Int = pre(id).look
    fun motionOf(id: Int): Int = pre(id).motion
    fun aOf(id: Int): Float = pre(id).a
    fun bOf(id: Int): Float = pre(id).b
    fun colOf(id: Int): FloatArray = COLS[pre(id).col]

    /** مدة الانتقال عند كل جانب (0.7 ثانية، وأقل لو المقطع قصير) */
    fun durUs(aLenMs: Long, bLenMs: Long): Long {
        val ms = minOf(700L, minOf(aLenMs, bLenMs) / 3L)
        return maxOf(0L, ms) * 1000L
    }

    private val bag = ArrayList<Int>()
    private var lastPicked = -1

    /** انتقال عشوائي بدون تكرار لحد ما كل الانتقالات تخلص، وبعدها بيخلط من جديد */
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

    /** منحنى التسارع: u = 1 → أقصى تأثير. نفس المعادلة للخروج وللدخول عشان السرعة تتصل عند القطع. */
    fun shape(u: Float, id: Int): Float {
        val x = u.coerceIn(0f, 1f)
        val k = when (id % 3) {
            0 -> 2.0f
            1 -> 1.7f
            else -> 2.4f
        }
        return x.pow(k)
    }

    fun ease(p: Float, id: Int): Float = shape(p, id)

    fun progress(relUs: Long, outgoing: Boolean, lenUs: Long, durUs: Long, id: Int): Float {
        if (durUs <= 0L) return 0f
        val raw = if (outgoing) (relUs - (lenUs - durUs)).toFloat() / durUs
        else 1f - relUs.toFloat() / durUs
        return shape(raw, id)
    }

    private fun rot(m: Matrix, deg: Float, ar: Float) {
        m.postScale(ar, 1f)
        m.postRotate(-deg) // الإحداثيات y لفوق، فنعكس الإشارة عشان ↻ يبقى مع عقارب الساعة فعلاً
        m.postScale(1f / ar, 1f)
    }

    fun matrixFor(id: Int, p: Float, d: Int, ar: Float): Matrix = matrix(motionOf(id), p, d, ar)

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
            20 -> {
                val s = if (d > 0) 1f + 0.45f * p else 1f / (1f + 0.45f * p)
                m.postScale(s, s)
            }
            21 -> {
                m.postTranslate(0f, -k * 0.6f)
                rot(m, 10f * p * d, ar)
            }
            22 -> {
                m.postTranslate(-k * 0.8f, 0f)
                rot(m, 12f * p * d, ar)
            }
            else -> {}
        }
        return m
    }

    /**
     * تقريب لون الشكل للمعاينة السريعة (الـ shader الحقيقي بيشتغل في التصدير).
     * بيرجّع معامل ضرب للـ RGB.
     */
    fun rgbDiag(id: Int, p: Float): FloatArray {
        val pr = pre(id)
        val c = COLS[pr.col]
        var r = 1f
        var g = 1f
        var b = 1f
        when (pr.look) {
            L_DIP -> {
                r = 1f - p + p * (0.25f + 3.5f * c[0])
                g = 1f - p + p * (0.25f + 3.5f * c[1])
                b = 1f - p + p * (0.25f + 3.5f * c[2])
            }
            L_BURN, L_LEAK -> {
                r = 1f + 2.2f * p * (0.3f + c[0])
                g = 1f + 2.2f * p * (0.3f + c[1])
                b = 1f + 2.2f * p * (0.3f + c[2])
            }
            L_IRIS, L_WIPE, L_BLINDS, L_TILES, L_DISSOLVE, L_DOORS, L_TV, L_SLICE, L_HALFTONE, L_STRETCH, L_VIGNETTE -> {
                val k = 1f - 0.85f * p
                r = k; g = k; b = k
            }
            L_NONE -> {}
            else -> {
                val k = 1f - 0.30f * p
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

/** جزء الحركة (انزلاق/تكبير/دوران/قلب/اهتزاز...) */
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
        return Transitions.matrixFor(id, p, if (outgoing) 1 else -1, ar)
    }
}

/**
 * جزء الشكل: Shader حقيقي على كارت الشاشة (blur / glitch / pixel / iris / wipe / burn ...).
 * (الاسم TrRgbFx اتسمّى كده زمان؛ سيبناه عشان باقي الكود مايتغيّرش.)
 */
class TrRgbFx(
    private val id: Int,
    private val outgoing: Boolean,
    private val lenUs: Long,
    private val durUs: Long,
    private val clock: TrClock
) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        return TrShader(id, outgoing, lenUs, durUs, clock, useHdr)
    }
}

private object TrGl {
    const val VERTEX = """
attribute vec4 aFramePosition;
varying vec2 vTexSamplingCoord;
void main() {
  gl_Position = aFramePosition;
  vTexSamplingCoord = aFramePosition.xy * 0.5 + 0.5;
}
"""

    const val FRAG = """
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
uniform sampler2D uTexSampler;
uniform float uP;
uniform float uA;
uniform float uB;
uniform float uAspect;
uniform float uTime;
uniform float uOut;
uniform vec3 uCol;
varying vec2 vTexSamplingCoord;

float hash21(vec2 p) { return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }
float vnoise(vec2 p) {
  vec2 i = floor(p);
  vec2 f = fract(p);
  f = f * f * (3.0 - 2.0 * f);
  float a = hash21(i);
  float b = hash21(i + vec2(1.0, 0.0));
  float c = hash21(i + vec2(0.0, 1.0));
  float d = hash21(i + vec2(1.0, 1.0));
  return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}
vec4 T(vec2 uv) { return texture2D(uTexSampler, clamp(uv, 0.0, 1.0)); }
vec4 TB(vec2 uv) {
  if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0) return vec4(0.0, 0.0, 0.0, 1.0);
  return texture2D(uTexSampler, uv);
}
float heartMask(vec2 q) {
  float a = q.x * q.x + q.y * q.y - 1.0;
  float v = a * a * a - q.x * q.x * q.y * q.y * q.y;
  return 1.0 - smoothstep(-0.06, 0.06, v);
}

void main() {
  vec2 uv = vTexSamplingCoord;
  float p = clamp(uP, 0.0, 1.0);
  vec2 cc = (uv - 0.5) * vec2(uAspect, 1.0);
  vec4 c0 = T(uv);
  vec3 col = c0.rgb;
#if LOOK == 0
  gl_FragColor = c0;
  return;
#endif
  if (p <= 0.0001) {
    gl_FragColor = c0;
    return;
  }
#if LOOK == 1
  col = mix(col, uCol, p);
#elif LOOK == 2
  vec2 d = vec2(cos(uA) / uAspect, sin(uA)) * (p * 0.09 * uB);
  vec4 s = vec4(0.0);
  for (int i = 0; i < 14; i++) {
    float t = float(i) / 13.0 - 0.5;
    s += T(uv + d * t);
  }
  col = (s / 14.0).rgb;
#elif LOOK == 3
  vec2 dr = (uv - 0.5) * (p * 0.22 * uB);
  vec4 s = vec4(0.0);
  for (int i = 0; i < 14; i++) {
    float t = float(i) / 13.0;
    s += T(uv - dr * t);
  }
  col = (s / 14.0).rgb;
#elif LOOK == 4
  vec4 s = vec4(0.0);
  for (int i = 0; i < 14; i++) {
    float t = (float(i) / 13.0 - 0.5) * p * 0.25 * uB;
    float cs = cos(t);
    float sn = sin(t);
    vec2 r = vec2(cs * cc.x - sn * cc.y, sn * cc.x + cs * cc.y);
    s += T(vec2(r.x / uAspect, r.y) + 0.5);
  }
  col = (s / 14.0).rgb;
#elif LOOK == 5
  float rad = p * 0.035 * uB;
  vec4 s = c0;
  for (int i = 0; i < 12; i++) {
    float a = float(i) * 0.5236;
    vec2 o = vec2(cos(a) / uAspect, sin(a)) * rad;
    s += T(uv + o) + T(uv + o * 0.5);
  }
  col = (s / 25.0).rgb;
  col = mix(col, uCol, p * p * 0.45 * step(0.01, uB));
#elif LOOK == 6
  float n = mix(700.0, 10.0, sqrt(p));
  vec2 g = vec2(n, n / uAspect);
  vec2 q = (floor(uv * g) + 0.5) / g;
  col = T(q).rgb;
  col = mix(col, uCol, p * p * 0.25 * uA);
#elif LOOK == 7
  vec2 o = vec2(cos(uA) / uAspect, sin(uA)) * (p * 0.04 * uB);
  col = vec3(T(uv + o).r, T(uv).g, T(uv - o).b);
#elif LOOK == 8
  float bi = floor(uv.y * 22.0);
  float tk = floor(uTime * 14.0);
  float h = hash21(vec2(bi, tk));
  float on = step(0.55 - 0.35 * p, h);
  float sh = (hash21(vec2(bi + 7.0, tk)) - 0.5) * 0.35 * p * uB * on;
  vec2 gq = vec2(uv.x + sh, uv.y);
  float sp = 0.03 * p * uB * on + 0.006 * p;
  col = vec3(T(gq + vec2(sp, 0.0)).r, T(gq).g, T(gq - vec2(sp, 0.0)).b);
  col = col * (1.0 - 0.12 * p * step(0.5, fract(uv.y * 240.0)));
  col = mix(col, uCol, p * p * 0.35);
#elif LOOK == 9
  float amp = p * 0.045 * uB;
  vec2 w = uv;
  w.x += sin(uv.y * 16.0 + uTime * 11.0) * amp;
  if (uA > 0.5) { w.y += sin(uv.x * 14.0 - uTime * 9.0) * amp * 0.8; }
  col = T(w).rgb;
  col = mix(col, uCol, p * p * 0.3);
#elif LOOK == 10
  float rr = length(cc);
  float ang = p * 8.0 * uB * (1.0 - smoothstep(0.0, 0.95, rr));
  if (uA > 0.5) { ang = -ang; }
  float cs = cos(ang);
  float sn = sin(ang);
  vec2 q = vec2(cs * cc.x - sn * cc.y, sn * cc.x + cs * cc.y);
  col = T(vec2(q.x / uAspect, q.y) + 0.5).rgb;
  col = mix(col, uCol, p * p * 0.5);
#elif LOOK == 11
  float R = (1.0 - p) * 0.95;
  float d = 0.0;
  float mk = 1.0;
  float rim = 0.0;
  if (uA < 0.5) {
    d = length(cc);
    mk = 1.0 - smoothstep(R - 0.015, R + 0.015, d);
    rim = exp(-pow((d - R) / 0.03, 2.0));
  } else if (uA < 1.5) {
    d = (abs(cc.x) + abs(cc.y)) * 0.85;
    mk = 1.0 - smoothstep(R - 0.015, R + 0.015, d);
    rim = exp(-pow((d - R) / 0.03, 2.0));
  } else if (uA < 2.5) {
    vec2 hq = vec2(cc.x, cc.y + 0.04) / max(R, 0.001) * 1.1;
    mk = heartMask(hq);
  } else if (uA < 3.5) {
    d = max(abs(cc.x), abs(cc.y)) * 1.15;
    mk = 1.0 - smoothstep(R - 0.015, R + 0.015, d);
    rim = exp(-pow((d - R) / 0.03, 2.0));
  } else {
    d = length(cc * vec2(0.55, 1.6)) * 1.1;
    mk = 1.0 - smoothstep(R - 0.015, R + 0.015, d);
    rim = exp(-pow((d - R) / 0.03, 2.0));
  }
  vec3 bg = vec3(uB);
  col = mix(bg, col, mk) + uCol * rim * 0.9;
#elif LOOK == 12
  vec2 dv = vec2(cos(uA), sin(uA));
  float ext = 0.5 * (abs(dv.x) * uAspect + abs(dv.y));
  float tpos = dot(cc, dv) / max(ext, 0.001);
  float v = 1.0 - p;
  float m = 1.0;
  if (uOut > 0.5) {
    float L = -1.1 + 2.2 * v;
    m = 1.0 - smoothstep(L - 0.08, L + 0.08, tpos);
  } else {
    float Rr = 1.1 - 2.2 * v;
    m = smoothstep(Rr - 0.08, Rr + 0.08, tpos);
  }
  float ed = m * (1.0 - m) * 4.0;
  col = col * m + uCol * ed * 0.7;
#elif LOOK == 13
  float n = max(uB, 2.0);
  float coord = uv.y;
  if (uA > 0.5) { coord = uv.x; }
  float f = fract(coord * n);
  float vis = 1.0 - smoothstep(1.0 - p - 0.02, 1.0 - p + 0.02, f);
  col = col * vis;
#elif LOOK == 14
  float n = max(uB, 3.0);
  vec2 g = vec2(n * uAspect, n);
  vec2 cid = floor(uv * g);
  vec2 f = fract(uv * g);
  float r = hash21(cid + 3.7);
  float q = clamp((p - r * 0.55) / 0.45, 0.0, 1.0);
  float s = 1.0 - q;
  vec2 lc = (f - 0.5) / max(s, 0.001) + 0.5;
  if (s <= 0.001 || lc.x < 0.0 || lc.x > 1.0 || lc.y < 0.0 || lc.y > 1.0) {
    col = uCol * step(0.5, uCol.r + uCol.g + uCol.b);
  } else {
    col = T((cid + lc) / g).rgb;
  }
#elif LOOK == 15
  float nz = vnoise(uv * vec2(uAspect, 1.0) * (4.0 + uB * 3.0) + vec2(0.0, uTime * 0.3));
  nz = nz * 0.8 + 0.2 * vnoise(uv * 20.0);
  float th = p * 1.25 - 0.125;
  float vis = smoothstep(th, th + 0.03, nz);
  float edge = smoothstep(th - 0.09, th, nz) * (1.0 - smoothstep(th, th + 0.05, nz));
  col = col * vis + uCol * edge * 1.6;
#elif LOOK == 16
  float gr = hash21(uv * 800.0 + uTime);
  float nb = vnoise(uv * vec2(uAspect, 1.0) * 3.0 + uTime * 0.7);
  float k = p * 2.4 * (0.45 + 0.8 * nb);
  col = col + uCol * k + (gr - 0.5) * 0.08 * p;
  col = mix(col, uCol, smoothstep(0.75, 1.0, p));
#elif LOOK == 17
  float mpos = mix(-0.4, 1.4, p);
  float xx = uv.x + uv.y * 0.3;
  float glow = exp(-pow((xx - mpos) * 2.6, 2.0));
  float k2 = sin(p * 3.14159);
  col = col + uCol * glow * 1.6 * (0.6 + k2);
  col = mix(col, uCol, p * p * p * 0.6);
#elif LOOK == 18
  float gp = p * 0.5;
  if (uA < 0.5) {
    if (uv.x < 0.5 - gp) { col = TB(vec2(uv.x + gp, uv.y)).rgb; }
    else if (uv.x > 0.5 + gp) { col = TB(vec2(uv.x - gp, uv.y)).rgb; }
    else { col = vec3(0.0); }
  } else {
    if (uv.y < 0.5 - gp) { col = TB(vec2(uv.x, uv.y + gp)).rgb; }
    else if (uv.y > 0.5 + gp) { col = TB(vec2(uv.x, uv.y - gp)).rgb; }
    else { col = vec3(0.0); }
  }
#elif LOOK == 19
  float sc = 1.0 + p * 7.0 * uB;
  vec2 q = uv;
  if (uA < 0.5) { q.x = 0.5 + (uv.x - 0.5) * sc; } else { q.y = 0.5 + (uv.y - 0.5) * sc; }
  col = T(q).rgb * (1.0 - 0.7 * p);
#elif LOOK == 20
  float hh = max(1.0 - p, 0.003);
  float dy = abs(uv.y - 0.5);
  if (dy > hh * 0.5) {
    col = vec3(0.0);
  } else {
    col = T(vec2(uv.x, 0.5 + (uv.y - 0.5) / hh)).rgb * (1.0 + 2.5 * p);
  }
#elif LOOK == 21
  float n = max(uB, 3.0);
  float cd = uv.y;
  if (uA > 0.5) { cd = uv.x; }
  float idx = floor(cd * n);
  float sgn = 1.0;
  if (mod(idx, 2.0) > 0.5) { sgn = -1.0; }
  vec2 q = uv;
  if (uA < 0.5) { q.x = uv.x + sgn * p * 1.05; } else { q.y = uv.y + sgn * p * 1.05; }
  col = TB(q).rgb;
#elif LOOK == 22
  float segs = max(uB, 2.0) * 2.0;
  float ang = atan(cc.y, cc.x);
  float rad = length(cc);
  float seg = 6.2831853 / segs;
  float a2 = abs(mod(ang + 3.14159265, seg) - seg * 0.5);
  vec2 kq = vec2(cos(a2), sin(a2)) * rad;
  vec2 q = mix(cc, kq, clamp(p * 1.4, 0.0, 1.0));
  col = T(vec2(q.x / uAspect, q.y) + 0.5).rgb * (1.0 - 0.5 * p);
  col = mix(col, uCol, p * p * 0.5 * step(0.5, uCol.r + uCol.g + uCol.b));
#elif LOOK == 23
  float n = max(uB, 4.0);
  vec2 g = vec2(n * uAspect, n);
  vec2 f = fract(uv * g) - 0.5;
  float R = (1.0 - p) * 0.75;
  float vis = 1.0;
  if (uA < 0.5) {
    vis = 1.0 - smoothstep(R - 0.03, R + 0.03, length(f));
  } else {
    vis = heartMask(vec2(f.x, f.y + 0.05) / max(R, 0.001) * 0.62);
  }
  col = col * vis;
#elif LOOK == 24
  float rr = length(cc);
  vec2 dr = (uv - 0.5) * (p * 0.10);
  vec4 s = vec4(0.0);
  for (int i = 0; i < 10; i++) {
    float t = float(i) / 9.0;
    s += T(uv - dr * t);
  }
  col = (s / 10.0).rgb;
  col = col * (1.0 - p * smoothstep(0.15, 0.9, rr) * 1.2);
  col = col * (1.0 - 0.5 * p);
  col = col + uCol * p * 0.0;
#elif LOOK == 25
  float ns = hash21(uv * vec2(uAspect, 1.0) * 400.0 + floor(uTime * 30.0));
  float ln = 0.5 + 0.5 * sin(uv.y * 600.0 + uTime * 40.0);
  vec3 st = vec3(ns * (0.6 + 0.4 * ln));
  st = st * mix(vec3(1.0), uCol + vec3(0.4), step(0.01, uCol.r + uCol.g + uCol.b));
  col = mix(col, st, p * p);
#endif
  gl_FragColor = vec4(clamp(col, 0.0, 1.0), 1.0);
}
"""
}

private class TrShader(
    private val id: Int,
    private val outgoing: Boolean,
    private val lenUs: Long,
    private val durUs: Long,
    private val clock: TrClock,
    useHdr: Boolean
) : BaseGlShaderProgram(useHdr, 1) {

    private val program: GlProgram = try {
        GlProgram(TrGl.VERTEX, "#define LOOK " + Transitions.lookOf(id) + "\n" + TrGl.FRAG)
    } catch (e: GlUtil.GlException) {
        throw VideoFrameProcessingException(e)
    }
    private var aspect = 1f
    private val col = Transitions.colOf(id)
    private val ua = Transitions.aOf(id)
    private val ub = Transitions.bOf(id)

    init {
        program.setBufferAttribute(
            "aFramePosition",
            GlUtil.getNormalizedCoordinateBounds(),
            GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE
        )
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        aspect = inputWidth.toFloat() / inputHeight.toFloat()
        return Size(inputWidth, inputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            val rel = clock.rel(presentationTimeUs)
            val p = Transitions.progress(rel, outgoing, lenUs, durUs, id)
            program.use()
            program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
            program.setFloatUniform("uP", p)
            program.setFloatUniform("uA", ua)
            program.setFloatUniform("uB", ub)
            program.setFloatUniform("uAspect", aspect)
            program.setFloatUniform("uTime", rel / 1_000_000f)
            program.setFloatUniform("uOut", if (outgoing) 1f else 0f)
            program.setFloatsUniform("uCol", col)
            program.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GlUtil.checkGlError()
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
    }

    override fun release() {
        super.release()
        try {
            program.delete()
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
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
        return Transitions.matrixFor(id, p, if (st.outgoing) 1 else -1, ar)
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

/** ساعة واحدة مشتركة لكل مربعات المعاينة (بدل animator لكل مربع) */
private object PreviewClock {
    @Volatile
    var t = 0f
    private val views = ArrayList<View>()
    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2000
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            t = it.animatedValue as Float
            for (k in views.indices) views[k].invalidate()
        }
    }

    fun attach(v: View) {
        if (!views.contains(v)) views.add(v)
        if (!anim.isStarted) anim.start()
    }

    fun detach(v: View) {
        views.remove(v)
        if (views.isEmpty()) anim.cancel()
    }
}

/**
 * معاينة متحركة صغيرة للانتقال (مقطع A ثم مقطع B) بنفس الحركة والشكل التقريبي لكل نوع.
 * كل الكائنات متخزنة عشان مفيش allocation في onDraw.
 */
class TransitionPreview(ctx: Context) : View(ctx) {
    private var id = 0
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private var shaderA: LinearGradient? = null
    private var shaderB: LinearGradient? = null
    private val toNdc = Matrix()
    private val fromNdc = Matrix()
    private val mm = Matrix()
    private val cm = ColorMatrix()
    private val cmArr = FloatArray(20)
    private val rect = RectF()
    private val path = Path()

    fun setTransition(i: Int) {
        id = i
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        shaderA = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), Pal.cyan, Pal.blue, Shader.TileMode.CLAMP)
        shaderB = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), Pal.magenta, Pal.purple, Shader.TileMode.CLAMP)
        txt.textSize = h * 0.5f
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        PreviewClock.attach(this)
    }

    override fun onDetachedFromWindow() {
        PreviewClock.detach(this)
        super.onDetachedFromWindow()
    }

    private fun heart(cx: Float, cy: Float, s: Float) {
        path.reset()
        path.moveTo(cx, cy + 0.9f * s)
        path.cubicTo(cx - 1.25f * s, cy + 0.15f * s, cx - 0.95f * s, cy - 0.95f * s, cx, cy - 0.35f * s)
        path.cubicTo(cx + 0.95f * s, cy - 0.95f * s, cx + 1.25f * s, cy + 0.15f * s, cx, cy + 0.9f * s)
        path.close()
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val t = PreviewClock.t
        val outgoing = t < 0.5f
        val u = if (outgoing) t / 0.5f else 1f - (t - 0.5f) / 0.5f
        val p = Transitions.shape(u, id)
        val look = Transitions.lookOf(id)
        val a = Transitions.aOf(id)
        val b = Transitions.bOf(id)

        toNdc.reset()
        toNdc.postTranslate(-w / 2f, -h / 2f)
        toNdc.postScale(2f / w, -2f / h)
        toNdc.invert(fromNdc)
        mm.set(toNdc)
        mm.postConcat(Transitions.matrixFor(id, p, if (outgoing) 1 else -1, w / h))
        mm.postConcat(fromNdc)

        val d = Transitions.rgbDiag(id, p)
        java.util.Arrays.fill(cmArr, 0f)
        cmArr[0] = d[0]
        cmArr[6] = d[1]
        cmArr[12] = d[2]
        cmArr[18] = 1f
        cm.set(cmArr)
        val filter = ColorMatrixColorFilter(cm)
        paint.colorFilter = filter
        txt.colorFilter = filter
        paint.shader = if (outgoing) shaderA else shaderB
        val v = 1f - p   // الجزء الظاهر

        c.save()
        // أقنعة الشاشة (قبل الحركة)
        when (look) {
            11 -> {
                val r = v * 0.62f * maxOf(w, h)
                path.reset()
                when {
                    a < 0.5f -> path.addCircle(w / 2f, h / 2f, r, Path.Direction.CW)
                    a < 1.5f -> {
                        path.moveTo(w / 2f, h / 2f - r); path.lineTo(w / 2f + r, h / 2f)
                        path.lineTo(w / 2f, h / 2f + r); path.lineTo(w / 2f - r, h / 2f); path.close()
                    }
                    a < 2.5f -> heart(w / 2f, h / 2f, r * 0.62f)
                    a < 3.5f -> path.addRect(w / 2f - r * 0.7f, h / 2f - r * 0.7f, w / 2f + r * 0.7f, h / 2f + r * 0.7f, Path.Direction.CW)
                    else -> path.addOval(w / 2f - r, h / 2f - r * 0.4f, w / 2f + r, h / 2f + r * 0.4f, Path.Direction.CW)
                }
                c.clipPath(path)
            }
            12 -> {
                val horizontal = Math.abs(Math.cos(a.toDouble())) > 0.5
                if (horizontal) c.clipRect(0f, 0f, w * v, h) else c.clipRect(0f, 0f, w, h * v)
            }
            18 -> {
                val gap = p * 0.5f
                if (a < 0.5f) {
                    path.reset()
                    path.addRect(0f, 0f, w * (0.5f - gap), h, Path.Direction.CW)
                    path.addRect(w * (0.5f + gap), 0f, w, h, Path.Direction.CW)
                } else {
                    path.reset()
                    path.addRect(0f, 0f, w, h * (0.5f - gap), Path.Direction.CW)
                    path.addRect(0f, h * (0.5f + gap), w, h, Path.Direction.CW)
                }
                c.clipPath(path)
            }
            20 -> {
                val hh = maxOf(v, 0.02f) * h
                c.clipRect(0f, h / 2f - hh / 2f, w, h / 2f + hh / 2f)
            }
            13 -> {
                val n = minOf(b, 14f).toInt().coerceAtLeast(3)
                path.reset()
                for (k in 0 until n) {
                    if (a < 0.5f) {
                        val y0 = h * k / n
                        path.addRect(0f, y0, w, y0 + (h / n) * v, Path.Direction.CW)
                    } else {
                        val x0 = w * k / n
                        path.addRect(x0, 0f, x0 + (w / n) * v, h, Path.Direction.CW)
                    }
                }
                c.clipPath(path)
            }
            14, 23 -> {
                val n = minOf(b, 9f).toInt().coerceAtLeast(3)
                path.reset()
                val cw = w / n
                val ch = h / n
                for (gy in 0 until n) for (gx in 0 until n) {
                    val rr = ((gx * 7 + gy * 13 + id) % 10) / 10f
                    val q = ((p - rr * 0.55f) / 0.45f).coerceIn(0f, 1f)
                    val s = (1f - q) * 0.5f
                    if (s > 0.01f) {
                        val cx = gx * cw + cw / 2f
                        val cy = gy * ch + ch / 2f
                        if (look == 23) {
                            if (a < 0.5f) path.addCircle(cx, cy, cw * s, Path.Direction.CW)
                            else {
                                path.addRect(cx - cw * s, cy - ch * s, cx + cw * s, cy + ch * s, Path.Direction.CW)
                            }
                        } else path.addRect(cx - cw * s, cy - ch * s, cx + cw * s, cy + ch * s, Path.Direction.CW)
                    }
                }
                c.clipPath(path)
            }
            15 -> {
                // تبخّر: نقط عشوائية بتختفي
                val n = 10
                path.reset()
                val cw = w / n
                val ch = h / n
                for (gy in 0 until n) for (gx in 0 until n) {
                    val rr = (((gx * 31 + gy * 17 + id * 3) % 11) / 11f)
                    if (rr > p) path.addRect(gx * cw, gy * ch, (gx + 1) * cw, (gy + 1) * ch, Path.Direction.CW)
                }
                c.clipPath(path)
            }
            21 -> {
                val n = 8
                path.reset()
                for (k in 0 until n) {
                    val sgn = if (k % 2 == 0) 1f else -1f
                    if (a < 0.5f) {
                        val y0 = h * k / n
                        path.addRect(sgn * p * w, y0, sgn * p * w + w, y0 + h / n, Path.Direction.CW)
                    } else {
                        val x0 = w * k / n
                        path.addRect(x0, sgn * p * h, x0 + w / n, sgn * p * h + h, Path.Direction.CW)
                    }
                }
                c.clipPath(path)
            }
        }
        c.concat(mm)
        when (look) {
            6 -> {
                // بكسلة: مربعات كبيرة
                val n = (mix(40f, 4f, Math.sqrt(p.toDouble()).toFloat())).toInt().coerceAtLeast(3)
                val cw = w / n
                val ch = h / n
                val c1 = if (outgoing) Pal.cyan else Pal.magenta
                val c2 = if (outgoing) Pal.blue else Pal.purple
                paint.shader = null
                for (gy in 0 until n) for (gx in 0 until n) {
                    val f = ((gx + 0.5f) / n + (gy + 0.5f) / n) / 2f
                    paint.color = lerpColor(c1, c2, f)
                    paint.colorFilter = filter
                    rect.set(gx * cw, gy * ch, (gx + 1) * cw + 1f, (gy + 1) * ch + 1f)
                    c.drawRect(rect, paint)
                }
            }
            8 -> {
                // Glitch: شرائح أفقية مزاحة
                val n = 9
                val tk = (t * 14f).toInt()
                for (k in 0 until n) {
                    val rr = (((k * 37 + tk * 11 + id) % 9) / 9f - 0.5f)
                    val off = rr * w * 0.5f * p
                    rect.set(0f, h * k / n, w, h * (k + 1) / n)
                    c.save()
                    c.clipRect(rect)
                    c.translate(off, 0f)
                    c.drawRect(0f, 0f, w, h, paint)
                    c.restore()
                }
            }
            2, 3, 4, 5, 9 -> {
                // Blur: نسخ شبحية
                val ghosts = 5
                val oldAlpha = paint.alpha
                for (g in 0 until ghosts) {
                    val f = (g / (ghosts - 1f) - 0.5f) * p * w * 0.16f
                    paint.alpha = (255 / ghosts) + 20
                    c.save()
                    c.translate(if (look == 9) 0f else f, if (look == 9) f * 0.3f else 0f)
                    c.drawRect(0f, 0f, w, h, paint)
                    c.restore()
                }
                paint.alpha = oldAlpha
            }
            else -> c.drawRect(0f, 0f, w, h, paint)
        }
        c.drawText(if (outgoing) "A" else "B", w / 2f, h / 2f + h * 0.18f, txt)
        c.restore()
    }

    private fun mix(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    private fun lerpColor(a: Int, b: Int, t: Float): Int {
        val r = (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt()
        val g = (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt()
        val bl = (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt()
        return Color.rgb(r, g, bl)
    }
}
