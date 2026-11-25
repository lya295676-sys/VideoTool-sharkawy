package com.videotool.mobile

import android.animation.LayoutTransition
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale

class MainActivity : Activity() {

    private class Row(val uri: Uri, val view: View, val badge: TextView)

    private val cCyan = Color.parseColor("#1FD1FF")
    private val cBlue = Color.parseColor("#4F6BFF")
    private val cPurple = Color.parseColor("#8B5CF6")
    private val cMagenta = Color.parseColor("#E040FB")
    private val cText2 = Color.parseColor("#9AA7C7")
    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    private val videos = mutableListOf<Uri>()
    private val rows = mutableListOf<Row>()
    private val anims = mutableListOf<ObjectAnimator>()
    private var batchIdx = 0
    private lateinit var list: LinearLayout
    private lateinit var sectionTitle: TextView
    private lateinit var emptyView: View

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun View.pressable() {
        setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN ->
                    v.animate().scaleX(0.95f).scaleY(0.95f).setDuration(110).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    v.animate().scaleX(1f).scaleY(1f).setDuration(220)
                        .setInterpolator(OvershootInterpolator(2f)).start()
            }
            false
        }
    }

    private fun View.enter(delay: Long, fromY: Int = 28) {
        alpha = 0f
        translationY = dp(fromY).toFloat()
        animate().alpha(1f).translationY(0f).setStartDelay(delay).setDuration(480)
            .setInterpolator(DecelerateInterpolator(1.6f)).start()
    }

    private fun loop(a: ObjectAnimator, ms: Long) {
        a.duration = ms
        a.repeatCount = ValueAnimator.INFINITE
        a.repeatMode = ValueAnimator.REVERSE
        a.interpolator = AccelerateDecelerateInterpolator()
        a.start()
        anims.add(a)
    }

    private fun rounded(color: Int, radiusDp: Int, strokeColor: Int = 0, strokeDp: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
            if (strokeDp > 0) setStroke(dp(strokeDp), strokeColor)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.parseColor("#050816")
        window.navigationBarColor = Color.parseColor("#150B35")

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.parseColor("#050816"), Color.parseColor("#0A1030"), Color.parseColor("#150B35"))
            )
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(20), dp(14), dp(20), dp(10))
        }

        val title = TextView(this).apply {
            text = "Video Tool"
            textSize = 32f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            val w = paint.measureText(text.toString())
            paint.shader = LinearGradient(0f, 0f, w, 0f, intArrayOf(cCyan, cPurple, cMagenta), null, Shader.TileMode.CLAMP)
        }
        val sub = TextView(this).apply {
            text = "sharkawy"
            textSize = 16f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD_ITALIC)
            letterSpacing = 0.12f
            setTextColor(cCyan)
        }
        val tag = TextView(this).apply {
            text = "استوديو مونتاج فيديوهات حل الأسئلة"
            textSize = 14f
            setTextColor(cText2)
            setPadding(0, dp(4), 0, 0)
        }

        val addBtn = TextView(this).apply {
            text = "+  إضافة الفيديوهات"
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(dp(16), dp(17), dp(16), dp(17))
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(cCyan, cBlue, cPurple, cMagenta)
            ).apply { cornerRadius = dp(28).toFloat() }
            isClickable = true
            pressable()
            setOnClickListener { pickVideos() }
        }
        val btnWrap = FrameLayout(this).apply {
            addView(addBtn, FrameLayout.LayoutParams(MATCH, WRAP))
        }
        loop(
            ObjectAnimator.ofPropertyValuesHolder(
                btnWrap,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.025f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.025f)
            ), 1400
        )

        sectionTitle = TextView(this).apply {
            text = sectionText()
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }

        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(20))
            layoutTransition = LayoutTransition()
        }
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(list)
        }

        val emptyIcon = TextView(this).apply {
            text = "🎬"
            textSize = 54f
            gravity = Gravity.CENTER
        }
        val emptyText = TextView(this).apply {
            text = "لسه مفيش مقاطع\nاضغط على إضافة الفيديوهات وابدأ"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(cText2)
            setPadding(0, dp(10), 0, 0)
        }
        emptyView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(emptyIcon)
            addView(emptyText)
        }
        loop(ObjectAnimator.ofFloat(emptyIcon, View.TRANSLATION_Y, 0f, -dp(10).toFloat()), 1600)

        val area = FrameLayout(this).apply {
            addView(emptyView, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(scroll, FrameLayout.LayoutParams(MATCH, MATCH))
        }

        val footer = TextView(this).apply {
            text = "نسخة تجريبية • ميزات المونتاج جاية قريب"
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#6B7699"))
        }

        content.addView(title, LinearLayout.LayoutParams(WRAP, WRAP))
        content.addView(sub, LinearLayout.LayoutParams(WRAP, WRAP))
        content.addView(tag, LinearLayout.LayoutParams(WRAP, WRAP))
        content.addView(btnWrap, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(18) })
        content.addView(sectionTitle, LinearLayout.LayoutParams(WRAP, WRAP).apply {
            topMargin = dp(24); bottomMargin = dp(8)
        })
        content.addView(area, LinearLayout.LayoutParams(MATCH, 0, 1f))
        content.addView(footer, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(6) })
        root.addView(content, LinearLayout.LayoutParams(MATCH, MATCH))
        setContentView(root)

        title.enter(0)
        sub.enter(60)
        tag.enter(120)
        btnWrap.enter(200)
        sectionTitle.enter(280)
        area.enter(340, 16)
    }

    override fun onDestroy() {
        anims.forEach { it.cancel() }
        super.onDestroy()
    }

    private fun sectionText() = "المقاطع المضافة  •  ${rows.size}"

    private fun pickVideos() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "video/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addCategory(Intent.CATEGORY_OPENABLE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(i, 1001)
    }

    @Deprecated("Deprecated")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 1001 || resultCode != RESULT_OK || data == null) return
        batchIdx = 0
        val clip = data.clipData
        if (clip != null) {
            for (n in 0 until clip.itemCount) add(clip.getItemAt(n).uri)
        } else {
            data.data?.let { add(it) }
        }
    }

    private fun queryInfo(uri: Uri): Pair<String, Long> {
        var name = "فيديو"
        var size = 0L
        try {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val si = c.getColumnIndex(OpenableColumns.SIZE)
                    if (ni >= 0) name = c.getString(ni) ?: name
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                }
            }
        } catch (_: Exception) {
        }
        return Pair(name, size)
    }

    private fun fmtDur(ms: Long): String {
        val s = ms / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
        else String.format(Locale.US, "%d:%02d", m, sec)
    }

    private fun fmtSize(b: Long): String =
        if (b >= 1048576L) String.format(Locale.US, "%.1f MB", b / 1048576.0)
        else "${b / 1024} KB"

    private fun add(uri: Uri) {
        if (videos.contains(uri)) return
        videos.add(uri)
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {
        }
        val (name, size) = queryInfo(uri)

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(Color.parseColor("#CC0F1733"), 18, Color.parseColor("#448B5CF6"), 1)
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        val badge = TextView(this).apply {
            text = (rows.size + 1).toString()
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(cCyan, cPurple))
                .apply { shape = GradientDrawable.OVAL }
        }
        val thumb = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Color.parseColor("#1B2547"))
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(10).toFloat())
                }
            }
            clipToOutline = true
        }
        val nameTv = TextView(this).apply {
            text = name
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val meta = TextView(this).apply {
            text = "جاري التحميل…"
            textSize = 12f
            setTextColor(cText2)
            setPadding(0, dp(3), 0, 0)
        }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(nameTv, LinearLayout.LayoutParams(MATCH, WRAP))
            addView(meta, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        val del = TextView(this).apply {
            text = "✕"
            textSize = 18f
            setTextColor(cText2)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            isClickable = true
            pressable()
        }

        card.addView(badge, LinearLayout.LayoutParams(dp(26), dp(26)))
        card.addView(thumb, LinearLayout.LayoutParams(dp(88), dp(56)).apply {
            marginStart = dp(10); marginEnd = dp(12)
        })
        card.addView(texts, LinearLayout.LayoutParams(0, WRAP, 1f))
        card.addView(del, LinearLayout.LayoutParams(WRAP, WRAP))

        val row = Row(uri, card, badge)
        del.setOnClickListener { removeRow(row) }
        rows.add(row)

        list.addView(card, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(10) })
        card.translationY = dp(30).toFloat()
        card.animate().translationY(0f).setStartDelay(batchIdx * 70L).setDuration(420)
            .setInterpolator(DecelerateInterpolator(1.6f)).start()
        batchIdx++

        refreshState()
        loadInfo(uri, size, thumb, meta)
    }

    private fun loadInfo(uri: Uri, size: Long, thumb: ImageView, meta: TextView) {
        Thread {
            var bmp: Bitmap? = null
            var dur = ""
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(this@MainActivity, uri)
                val ms = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                if (ms > 0) dur = fmtDur(ms)
                val f = r.getFrameAtTime(1000000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                if (f != null) {
                    val h = (256f * f.height / f.width).toInt().coerceAtLeast(1)
                    val s = Bitmap.createScaledBitmap(f, 256, h, true)
                    if (s !== f) f.recycle()
                    bmp = s
                }
            } catch (_: Exception) {
            } finally {
                try {
                    r.release()
                } catch (_: Exception) {
                }
            }
            val parts = mutableListOf<String>()
            if (dur.isNotEmpty()) parts.add(dur)
            if (size > 0) parts.add(fmtSize(size))
            val metaText = parts.joinToString("  •  ")
            val finalBmp = bmp
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (finalBmp != null) {
                    thumb.setImageBitmap(finalBmp)
                    thumb.alpha = 0f
                    thumb.animate().alpha(1f).setDuration(300).start()
                }
                meta.text = metaText
            }
        }.start()
    }

    private fun removeRow(row: Row) {
        rows.remove(row)
        videos.remove(row.uri)
        list.removeView(row.view)
        rows.forEachIndexed { i, r -> r.badge.text = (i + 1).toString() }
        refreshState()
    }

    private fun refreshState() {
        sectionTitle.text = sectionText()
        emptyView.animate().alpha(if (rows.isEmpty()) 1f else 0f).setDuration(300).start()
    }
}
