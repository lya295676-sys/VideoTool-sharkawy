package com.videotool.mobile

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

class TimelineView(ctx: Context) : View(ctx) {
    var clips: List<Clip> = emptyList()
    var selectedId: Long = -1L
        set(v) {
            field = v
            invalidate()
        }
    var timeMs: Long = 0L
        private set
    var framesOf: (Uri) -> List<Pair<Long, Bitmap>>? = { null }
    var onScrub: (Long) -> Unit = {}
    var onSelect: (Long) -> Unit = {}
    var onJunction: (Int) -> Unit = {}
    var onAdd: () -> Unit = {}
    var onTrimStart: () -> Unit = {}
    var onTrim: () -> Unit = {}
    var onTrimEnd: () -> Unit = {}

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokeP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val textP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.DEFAULT_BOLD
    }
    private val bmpP = Paint(Paint.FILTER_BITMAP_FLAG)
    private val path = Path()
    private val src = Rect()
    private var pxPerSec = ctx.dpf(40f)
    private var scaling = false
    private var flingAnim: ValueAnimator? = null
    private var trimSide = 0          // 1 = حافة يسار، 2 = حافة يمين
    private var trimStarted = false

    private fun dp(v: Float): Float = context.dpf(v)
    private fun pxPerMs(): Float = pxPerSec / 1000f
    private fun xOf(tMs: Long, cx: Float): Float = cx + (tMs - timeMs) * pxPerMs()
    fun totalMs(): Long = clips.sumOf { it.lenMs }

    fun moveTo(t: Long) {
        timeMs = t.coerceIn(0L, totalMs())
        invalidate()
    }

    private fun scrubTo(t: Long) {
        moveTo(t)
        onScrub(timeMs)
    }

    private val trackTop get() = dp(36f)
    private val trackH get() = dp(66f)

    private fun handleHit(x: Float, y: Float): Int {
        val sel = clips.firstOrNull { it.id == selectedId } ?: return 0
        if (y < trackTop - dp(8f) || y > trackTop + trackH + dp(8f)) return 0
        val cx = width / 2f
        var off = 0L
        for (c in clips) {
            if (c.id == sel.id) break
            off += c.lenMs
        }
        val x0 = xOf(off, cx)
        val x1 = xOf(off + sel.lenMs, cx)
        if (x1 - x0 < dp(36f)) return 0
        val d0 = abs(x - (x0 + dp(6f)))
        val d1 = abs(x - (x1 - dp(6f)))
        if (d0 <= dp(18f) && d0 <= d1) return 1
        if (d1 <= dp(18f)) return 2
        return 0
    }

    private fun doTrim(dxPx: Float) {
        val sel = clips.firstOrNull { it.id == selectedId } ?: return
        if (!trimStarted) {
            trimStarted = true
            onTrimStart()
        }
        val dEff = dxPx / pxPerMs()
        val minSrc = 300L
        if (trimSide == 1) {
            val ns = (sel.startMs + dEff * sel.speed).toLong().coerceIn(0L, sel.endMs - minSrc)
            val appliedEff = ((ns - sel.startMs) / sel.speed).toLong()
            sel.startMs = ns
            timeMs = (timeMs - appliedEff).coerceIn(0L, totalMs())
        } else {
            sel.endMs = (sel.endMs + dEff * sel.speed).toLong().coerceIn(sel.startMs + minSrc, sel.srcDurMs)
            timeMs = timeMs.coerceIn(0L, totalMs())
        }
        invalidate()
        onTrim()
    }

    private val gd = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            flingAnim?.cancel()
            trimSide = if (scaling) 0 else handleHit(e.x, e.y)
            trimStarted = false
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (scaling) return true
            if (trimSide != 0) {
                doTrim(-distanceX)
                return true
            }
            scrubTo(timeMs + (distanceX / pxPerMs()).toLong())
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            if (scaling || trimSide != 0) return true
            val dist = -velocityX * 0.25f / pxPerMs()
            val start = timeMs
            flingAnim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 550
                interpolator = DecelerateInterpolator(2f)
                addUpdateListener { scrubTo(start + (dist * it.animatedFraction).toLong()) }
                start()
            }
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (!trimStarted) handleTap(e.x, e.y)
            return true
        }
    })

    private val sd = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            scaling = true
            trimSide = 0
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            pxPerSec = (pxPerSec * detector.scaleFactor).coerceIn(dp(5f), dp(260f))
            invalidate()
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            scaling = false
        }
    })

    override fun onTouchEvent(e: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        sd.onTouchEvent(e)
        if (!sd.isInProgress) gd.onTouchEvent(e)
        if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) {
            if (trimStarted) onTrimEnd()
            trimSide = 0
            trimStarted = false
        }
        return true
    }

    private fun handleTap(x: Float, y: Float) {
        val cx = width / 2f
        val cy = trackTop + trackH / 2f
        val total = totalMs()
        if (clips.isEmpty()) {
            onAdd()
            return
        }
        val ax = xOf(total, cx) + dp(10f)
        if (x >= ax && x <= ax + dp(44f) && abs(y - cy) < dp(30f)) {
            onAdd()
            return
        }
        var off = 0L
        for (i in 0 until clips.size - 1) {
            off += clips[i].lenMs
            val jx = xOf(off, cx)
            if (abs(x - jx) < dp(18f) && abs(y - cy) < dp(34f)) {
                onJunction(i)
                return
            }
        }
        if (y >= trackTop - dp(6f) && y <= trackTop + trackH + dp(6f)) {
            val t = timeMs + ((x - cx) / pxPerMs()).toLong()
            var o = 0L
            for (c in clips) {
                if (t >= o && t < o + c.lenMs) {
                    onSelect(c.id)
                    return
                }
                o += c.lenMs
            }
        }
        onSelect(-1L)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val cx = w / 2f
        drawRuler(c, cx, w)
        val top = trackTop
        val bottom = top + trackH
        if (clips.isEmpty()) {
            textP.textSize = dp(13f)
            textP.color = Pal.text2
            textP.textAlign = Paint.Align.CENTER
            c.drawText("اضغط هنا لإضافة فيديوهات", cx, top + trackH / 2f + dp(5f), textP)
        }
        var off = 0L
        for ((i, cl) in clips.withIndex()) {
            val x0 = xOf(off, cx)
            val x1 = xOf(off + cl.lenMs, cx)
            if (x1 >= -dp(30f) && x0 <= w + dp(30f)) {
                drawClip(c, cl, x0 + dp(1f), top, x1 - dp(1f), bottom, cl.id == selectedId)
            }
            off += cl.lenMs
            if (i < clips.size - 1 && x1 >= -dp(20f) && x1 <= w + dp(20f)) {
                drawJunction(c, x1, top + trackH / 2f, cl.transitionId >= 0)
            }
        }
        if (clips.isNotEmpty()) {
            val ax = xOf(off, cx) + dp(10f)
            if (ax < w + dp(50f)) {
                fill.shader = null
                fill.color = Color.parseColor("#E6FFFFFF")
                c.drawRoundRect(RectF(ax, top + dp(11f), ax + dp(44f), bottom - dp(11f)), dp(10f), dp(10f), fill)
                textP.color = Color.parseColor("#1B2547")
                textP.textSize = dp(22f)
                textP.textAlign = Paint.Align.CENTER
                c.drawText("+", ax + dp(22f), top + trackH / 2f + dp(8f), textP)
            }
        }
        drawPlayhead(c, cx)
    }

    private fun drawRuler(c: Canvas, cx: Float, w: Float) {
        val steps = longArrayOf(1, 2, 5, 10, 15, 30, 60, 120, 300, 600, 1800, 3600, 7200)
        var stepS = steps.last()
        for (s in steps) {
            if (s * pxPerSec >= dp(70f)) {
                stepS = s
                break
            }
        }
        val stepMs = stepS * 1000L
        val t0 = timeMs - (cx / pxPerMs()).toLong()
        val t1 = timeMs + ((w - cx) / pxPerMs()).toLong()
        var t = (ceil(max(0L, t0).toDouble() / stepMs) * stepMs).toLong()
        textP.textSize = dp(10f)
        textP.color = Pal.text2
        textP.textAlign = Paint.Align.CENTER
        strokeP.shader = null
        strokeP.color = Color.parseColor("#55FFFFFF")
        strokeP.strokeWidth = dp(1f)
        while (t <= t1) {
            val x = xOf(t, cx)
            c.drawText(fmtTime(t), x, dp(14f), textP)
            c.drawLine(x, dp(20f), x, dp(27f), strokeP)
            val xm = xOf(t + stepMs / 2, cx)
            c.drawLine(xm, dp(23f), xm, dp(27f), strokeP)
            t += stepMs
        }
    }

    private fun drawClip(c: Canvas, cl: Clip, x0: Float, y0: Float, x1: Float, y1: Float, sel: Boolean) {
        val r = dp(8f)
        val rect = RectF(x0, y0, x1, y1)
        path.reset()
        path.addRoundRect(rect, r, r, Path.Direction.CW)
        c.save()
        c.clipPath(path)
        fill.shader = null
        fill.color = Color.parseColor("#1B2547")
        c.drawRect(rect, fill)
        val fr = framesOf(cl.uri)
        if (fr != null && fr.isNotEmpty()) {
            val tileW = (y1 - y0) * 16f / 9f
            var tx = x0
            if (tx < -tileW) tx += floor((-tileW - tx) / tileW) * tileW
            while (tx < x1 && tx < width + tileW) {
                val srcT = cl.startMs + (((tx + tileW / 2f - x0) / pxPerMs()) * cl.speed).toLong()
                var best = fr[0]
                for (f in fr) if (abs(f.first - srcT) < abs(best.first - srcT)) best = f
                val dst = RectF(tx, y0, tx + tileW, y1)
                val bmp = best.second
                val dstAr = dst.width() / dst.height()
                val srcAr = bmp.width.toFloat() / bmp.height
                if (srcAr > dstAr) {
                    val nw = (bmp.height * dstAr).toInt()
                    val sx = (bmp.width - nw) / 2
                    src.set(sx, 0, sx + nw, bmp.height)
                } else {
                    val nh = (bmp.width / dstAr).toInt()
                    val sy = (bmp.height - nh) / 2
                    src.set(0, sy, bmp.width, sy + nh)
                }
                c.drawBitmap(bmp, src, dst, bmpP)
                tx += tileW
            }
        }
        fill.color = Color.parseColor("#99000000")
        c.drawRect(x0, y1 - dp(18f), x1, y1, fill)
        textP.textSize = dp(10f)
        textP.color = Color.WHITE
        textP.textAlign = Paint.Align.LEFT
        var label = fmtTime(cl.lenMs)
        if (cl.denoise) label += "  🎧"
        if (abs(cl.gain - 1f) > 0.01f) label += "  🔊" + (cl.gain * 100).toInt() + "%"
        if (abs(cl.speed - 1f) > 0.01f) label += "  ⏩" + String.format(java.util.Locale.US, "%.2f", cl.speed).trimEnd('0').trimEnd('.') + "x"
        c.drawText(label, max(x0, 0f) + dp(if (sel) 18f else 6f), y1 - dp(5f), textP)
        c.restore()
        if (sel) {
            strokeP.strokeWidth = dp(3f)
            strokeP.shader = LinearGradient(
                x0, 0f, x1, 0f,
                intArrayOf(Pal.cyan, Pal.purple, Pal.magenta), null, Shader.TileMode.CLAMP
            )
            val half = dp(1.5f)
            c.drawRoundRect(RectF(x0 + half, y0 + half, x1 - half, y1 - half), r, r, strokeP)
            strokeP.shader = null
            if (x1 - x0 > dp(36f)) drawHandles(c, x0, y0, x1, y1, r)
        }
    }

    private fun drawHandles(c: Canvas, x0: Float, y0: Float, x1: Float, y1: Float, r: Float) {
        val hw = dp(12f)
        fill.shader = null
        fill.color = Color.WHITE
        c.drawRoundRect(RectF(x0, y0, x0 + hw, y1), r, r, fill)
        c.drawRoundRect(RectF(x1 - hw, y0, x1, y1), r, r, fill)
        strokeP.shader = null
        strokeP.color = Color.parseColor("#1B2547")
        strokeP.strokeWidth = dp(2f)
        val gy0 = y0 + (y1 - y0) / 2f - dp(8f)
        val gy1 = y0 + (y1 - y0) / 2f + dp(8f)
        c.drawLine(x0 + hw / 2f, gy0, x0 + hw / 2f, gy1, strokeP)
        c.drawLine(x1 - hw / 2f, gy0, x1 - hw / 2f, gy1, strokeP)
    }

    private fun drawJunction(c: Canvas, x: Float, y: Float, has: Boolean) {
        fill.shader = null
        fill.color = if (has) Pal.purple else Color.parseColor("#E6FFFFFF")
        c.drawCircle(x, y, dp(11f), fill)
        textP.color = if (has) Color.WHITE else Color.parseColor("#1B2547")
        textP.textSize = dp(13f)
        textP.textAlign = Paint.Align.CENTER
        c.drawText(if (has) "◈" else "+", x, y + dp(4.5f), textP)
    }

    private fun drawPlayhead(c: Canvas, cx: Float) {
        val h = height.toFloat()
        strokeP.shader = null
        strokeP.color = Color.parseColor("#4D1FD1FF")
        strokeP.strokeWidth = dp(8f)
        c.drawLine(cx, dp(26f), cx, h - dp(6f), strokeP)
        strokeP.color = Color.WHITE
        strokeP.strokeWidth = dp(2.5f)
        c.drawLine(cx, dp(26f), cx, h - dp(6f), strokeP)
        path.reset()
        path.moveTo(cx - dp(7f), dp(22f))
        path.lineTo(cx + dp(7f), dp(22f))
        path.lineTo(cx, dp(32f))
        path.close()
        fill.shader = null
        fill.color = Color.WHITE
        c.drawPath(path, fill)
    }
}
