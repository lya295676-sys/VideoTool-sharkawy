package com.videotool.mobile

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.MediaCodecList
import android.net.Uri
import android.os.Build
import android.view.MotionEvent
import android.view.View
import android.view.animation.OvershootInterpolator
import java.util.Locale
import kotlin.math.max

object Pal {
    val cyan = Color.parseColor("#1FD1FF")
    val blue = Color.parseColor("#4F6BFF")
    val purple = Color.parseColor("#8B5CF6")
    val magenta = Color.parseColor("#E040FB")
    val text2 = Color.parseColor("#9AA7C7")
    val card = Color.parseColor("#F2101733")
}

/** مقطع على التايم لاين. transitionId = الانتقال بين هذا المقطع والمقطع اللي بعده (-1 = بدون). */
data class Clip(
    val id: Long,
    val uri: Uri,
    val name: String,
    val srcDurMs: Long,
    var startMs: Long,
    var endMs: Long,
    val sizeBytes: Long,
    var gain: Float = 1f,
    var denoise: Boolean = false,
    var transitionId: Int = -1,
    var dw: Int = 0,
    var dh: Int = 0,
    /** سرعة التشغيل (1 = عادي). موجودة عشان TimelineView؛ لسه مش متوصّلة بالواجهة ولا بالتصدير. */
    var speed: Float = 1f
) {
    val lenMs: Long get() = if (speed == 1f || speed < 0.01f) endMs - startMs else ((endMs - startMs) / speed).toLong()
    /** نسخة بـ id جديد (للتقسيم والنسخ) */
    fun dup(): Clip = copy(id = Ids.next())
}

class Ratio(val id: String, val label: String, val sub: String, val w: Int, val h: Int)

object Ratios {
    val all = listOf(
        Ratio("orig", "الأصلي", "حسب أول فيديو", 0, 0),
        Ratio("16_9", "16:9", "YouTube • Facebook • TV", 16, 9),
        Ratio("9_16", "9:16", "TikTok • Reels • Shorts • Snapchat • WhatsApp", 9, 16),
        Ratio("1_1", "1:1", "Instagram • Facebook • X", 1, 1),
        Ratio("4_5", "4:5", "Instagram Feed • Facebook", 4, 5),
        Ratio("3_4", "3:4", "TikTok • Instagram", 3, 4),
        Ratio("4_3", "4:3", "Facebook • كلاسيك", 4, 3),
        Ratio("21_9", "21:9", "سينمائي عريض", 21, 9),
        Ratio("2_3", "2:3", "Pinterest", 2, 3),
        Ratio("5_4", "5:4", "صورة عريضة", 5, 4)
    )
}

/**
 * جودة التصدير. لو targetMb > 0 يبقى الوضع "حد أقصى للحجم": التطبيق يحسب البت ريت والدقة من مدة الفيديو.
 * البت ريت للجودات الثابتة مضبوط على HEVC؛ لو الجهاز مفيهوش HEVC هاردوير بيتضرب ×1.4 (H.264).
 */
class Quality(val label: String, val shortSide: Int, val videoBps: Int, val note: String, val targetMb: Int = 0)

object Qualities {
    const val AUDIO_BPS = 64_000
    private const val SAFETY = 0.92          // هامش أمان: المشفّر الهاردوير ساعات بيعدّي البت ريت المطلوب
    private const val H264_FACTOR = 1.4

    /** الأفضلية: false = H.264 (أسرع في التصدير)، true = HEVC (حجم أقل لكن غالبًا أبطأ). بتتطبق بس لو الجهاز فيه HEVC هاردوير. */
    @Volatile
    var preferHevc: Boolean = false
    fun useHevc(): Boolean = hevcOk && preferHevc

    /** هل الجهاز فيه مشفّر HEVC هاردوير؟ (المشفّر السوفتوير بطيء جدًا فمش بنعتمد عليه) */
    val hevcOk: Boolean by lazy {
        var ok = false
        try {
            val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            for (info in list.codecInfos) {
                if (!info.isEncoder) continue
                var has = false
                for (t in info.supportedTypes) if (t.equals("video/hevc", ignoreCase = true)) has = true
                if (!has) continue
                val hw = if (Build.VERSION.SDK_INT >= 29) info.isHardwareAccelerated
                else !(info.name.startsWith("OMX.google.") || info.name.startsWith("c2.android."))
                if (hw) ok = true
            }
        } catch (_: Throwable) {
        }
        ok
    }

    val all = listOf(
        Quality("480p", 480, 250_000, "أصغر حجم"),
        Quality("720p موفّر", 720, 450_000, "موصى به للفيديوهات الطويلة"),
        Quality("720p متوازن", 720, 550_000, "جودة أحسن بحجم معقول"),
        Quality("1080p", 1080, 1_100_000, "جودة عالية"),
        Quality("1080p Ultra", 1080, 2_500_000, "أعلى جودة – حجم كبير"),
        Quality("🎯 حد أقصى 600 MB", 0, 0, "التطبيق يختار الجودة تلقائي", 600),
        Quality("🎯 حد أقصى 1 GB", 0, 0, "التطبيق يختار الجودة تلقائي", 1000)
    )

    /** معدل بت الفيديو الفعلي للتصدير بالـ bps */
    fun videoBps(q: Quality, sec: Double): Int {
        if (q.targetMb > 0) {
            val total = q.targetMb * 8_000_000.0 / max(1.0, sec)
            return ((total - AUDIO_BPS) * SAFETY).toInt().coerceIn(120_000, 6_000_000)
        }
        return if (useHevc()) q.videoBps else (q.videoBps * H264_FACTOR).toInt()
    }

    /** الضلع الأقصر للإخراج */
    fun shortSide(q: Quality, bps: Int): Int {
        if (q.targetMb > 0) return if (bps < 300_000) 480 else if (bps < 1_400_000) 720 else 1080
        return q.shortSide
    }
}

object Ids {
    private var n = 1L
    fun next(): Long {
        n += 1
        return n
    }
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
fun Context.dpf(v: Float): Float = v * resources.displayMetrics.density

fun rounded(ctx: Context, color: Int, radiusDp: Int, strokeColor: Int = 0, strokeDp: Int = 0): GradientDrawable =
    GradientDrawable().apply {
        setColor(color)
        cornerRadius = ctx.dp(radiusDp).toFloat()
        if (strokeDp > 0) setStroke(ctx.dp(strokeDp), strokeColor)
    }

fun View.pressable() {
    setOnTouchListener { v, e ->
        when (e.action) {
            MotionEvent.ACTION_DOWN ->
                v.animate().scaleX(0.94f).scaleY(0.94f).setDuration(110).start()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                v.animate().scaleX(1f).scaleY(1f).setDuration(220)
                    .setInterpolator(OvershootInterpolator(2f)).start()
        }
        false
    }
}

fun fmtTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
    else String.format(Locale.US, "%02d:%02d", m, sec)
}

fun fmtChap(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
    else String.format(Locale.US, "%d:%02d", m, sec)
}

/** بالنظام العشري (زي مدير الملفات في أندرويد): 1 MB = 1,000,000 بايت */
fun fmtSize(b: Long): String =
    if (b >= 1_000_000_000L) String.format(Locale.US, "%.2f GB", b / 1_000_000_000.0)
    else if (b >= 1_000_000L) String.format(Locale.US, "%.0f MB", b / 1_000_000.0)
    else "${b / 1000} KB"
