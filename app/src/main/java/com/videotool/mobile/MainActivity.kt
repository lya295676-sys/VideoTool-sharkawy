package com.videotool.mobile

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
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
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
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
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class MainActivity : Activity() {

    private class Clip(
        val uri: Uri,
        val name: String,
        val sizeBytes: Long,
        var durMs: Long = 0L,
        var startMs: Long = 0L,
        var endMs: Long = 0L,
        var dw: Int = 0,
        var dh: Int = 0,
        var loaded: Boolean = false
    )

    private class Row(val clip: Clip, val view: View, val thumb: ImageView, val meta: TextView)

    private val cCyan = Color.parseColor("#1FD1FF")
    private val cBlue = Color.parseColor("#4F6BFF")
    private val cPurple = Color.parseColor("#8B5CF6")
    private val cMagenta = Color.parseColor("#E040FB")
    private val cText2 = Color.parseColor("#9AA7C7")
    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    private val clips = mutableListOf<Clip>()
    private val rows = mutableListOf<Row>()
    private val thumbs = ConcurrentHashMap<Uri, Bitmap>()
    private val anims = mutableListOf<ObjectAnimator>()
    private val handler = Handler(Looper.getMainLooper())
    private var activeTransformer: Transformer? = null
    private lateinit var list: LinearLayout
    private lateinit var sectionTitle: TextView
    private lateinit var emptyView: View
    private lateinit var bottomBar: LinearLayout

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

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

    private fun gradBtn(label: String, size: Float = 17f): TextView = TextView(this).apply {
        text = label
        textSize = size
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        setPadding(dp(16), dp(16), dp(16), dp(16))
        background = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(cCyan, cBlue, cPurple, cMagenta)
        ).apply { cornerRadius = dp(28).toFloat() }
        isClickable = true
        pressable()
    }

    private fun darkDialog() = AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)

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

        val addBtn = gradBtn("+  إضافة الفيديوهات").apply { setOnClickListener { pickVideos() } }
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

        val exportBtn = gradBtn("تصدير الفيديو", 16f).apply { setOnClickListener { startExport() } }
        val chapBtn = TextView(this).apply {
            text = "فصول يوتيوب"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = rounded(Color.TRANSPARENT, 28, cPurple, 2)
            isClickable = true
            pressable()
            setOnClickListener {
                showChapters("انسخ الفصول وحطها في وصف الفيديو على يوتيوب", null)
            }
        }
        bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
            addView(exportBtn, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(chapBtn, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(10) })
        }

        content.addView(title, LinearLayout.LayoutParams(WRAP, WRAP))
        content.addView(sub, LinearLayout.LayoutParams(WRAP, WRAP))
        content.addView(tag, LinearLayout.LayoutParams(WRAP, WRAP))
        content.addView(btnWrap, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(18) })
        content.addView(sectionTitle, LinearLayout.LayoutParams(WRAP, WRAP).apply {
            topMargin = dp(24); bottomMargin = dp(8)
        })
        content.addView(area, LinearLayout.LayoutParams(MATCH, 0, 1f))
        content.addView(bottomBar, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })
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
        handler.removeCallbacksAndMessages(null)
        try {
            activeTransformer?.cancel()
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    private fun sectionText() = "المقاطع المضافة  •  ${clips.size}"

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

    private fun metaText(c: Clip): String {
        if (!c.loaded) return "جاري التحميل…"
        val parts = mutableListOf<String>()
        if (c.durMs > 0) parts.add(fmtDur(c.endMs - c.startMs))
        if (c.sizeBytes > 0) parts.add(fmtSize(c.sizeBytes))
        if (c.startMs > 0 || c.endMs < c.durMs) parts.add("✂ ${fmtDur(c.startMs)}–${fmtDur(c.endMs)}")
        return parts.joinToString("  •  ")
    }

    // ---------- اختيار الفيديوهات ----------

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
        val start = clips.size
        val clip = data.clipData
        if (clip != null) {
            for (n in 0 until clip.itemCount) addClip(clip.getItemAt(n).uri)
        } else {
            data.data?.let { addClip(it) }
        }
        if (clips.size > start) {
            render(start, clips.size - 1)
            for (k in start until clips.size) loadInfo(clips[k])
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

    private fun addClip(uri: Uri) {
        if (clips.any { it.uri == uri }) return
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {
        }
        val (name, size) = queryInfo(uri)
        clips.add(Clip(uri, name, size))
    }

    private fun scaled(f: Bitmap, w: Int): Bitmap {
        val h = (w.toFloat() * f.height / f.width).toInt().coerceAtLeast(1)
        val s = Bitmap.createScaledBitmap(f, w, h, true)
        if (s !== f) f.recycle()
        return s
    }

    private fun loadInfo(c: Clip) {
        Thread {
            var bmp: Bitmap? = null
            var ms = 0L
            var dw = 0
            var dh = 0
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(this@MainActivity, c.uri)
                ms = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                if (rot == 90 || rot == 270) {
                    dw = h; dh = w
                } else {
                    dw = w; dh = h
                }
                val f = r.getFrameAtTime(1000000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                if (f != null) bmp = scaled(f, 256)
            } catch (_: Exception) {
            } finally {
                try {
                    r.release()
                } catch (_: Exception) {
                }
            }
            val fb = bmp
            if (fb != null) thumbs[c.uri] = fb
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                c.durMs = ms
                c.dw = dw
                c.dh = dh
                if (c.endMs == 0L) c.endMs = ms
                c.loaded = true
                rows.filter { it.clip.uri == c.uri }.forEach { row ->
                    row.meta.text = metaText(row.clip)
                    if (fb != null) {
                        row.thumb.setImageBitmap(fb)
                        row.thumb.alpha = 0f
                        row.thumb.animate().alpha(1f).setDuration(300).start()
                    }
                }
            }
        }.start()
    }

    // ---------- القائمة ----------

    private fun pill(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = 13f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        setPadding(dp(4), dp(8), dp(4), dp(8))
        background = rounded(Color.parseColor("#2A3560"), 14)
        isClickable = true
        pressable()
        setOnClickListener { onClick() }
    }

    private fun pillLp() = LinearLayout.LayoutParams(0, WRAP, 1f).apply {
        marginStart = dp(3); marginEnd = dp(3)
    }

    private fun buildCard(c: Clip, i: Int): Row {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Color.parseColor("#CC0F1733"), 18, Color.parseColor("#448B5CF6"), 1)
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val badge = TextView(this).apply {
            text = (i + 1).toString()
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
        thumbs[c.uri]?.let { thumb.setImageBitmap(it) }
        val nameTv = TextView(this).apply {
            text = c.name
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val meta = TextView(this).apply {
            text = metaText(c)
            textSize = 12f
            setTextColor(cText2)
            setPadding(0, dp(3), 0, 0)
        }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(nameTv, LinearLayout.LayoutParams(MATCH, WRAP))
            addView(meta, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        top.addView(badge, LinearLayout.LayoutParams(dp(26), dp(26)))
        top.addView(thumb, LinearLayout.LayoutParams(dp(88), dp(56)).apply {
            marginStart = dp(10); marginEnd = dp(12)
        })
        top.addView(texts, LinearLayout.LayoutParams(0, WRAP, 1f))

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
            addView(pill("✂ قص") { showTrim(c) }, pillLp())
            addView(pill("⧉ نسخ") { duplicate(c) }, pillLp())
            addView(pill("↑") { move(c, -1) }, pillLp())
            addView(pill("↓") { move(c, 1) }, pillLp())
            addView(pill("✕") { remove(c) }, pillLp())
        }
        card.addView(top, LinearLayout.LayoutParams(MATCH, WRAP))
        card.addView(actions, LinearLayout.LayoutParams(MATCH, WRAP))
        return Row(c, card, thumb, meta)
    }

    private fun render(animFrom: Int = -1, animTo: Int = -1) {
        list.removeAllViews()
        rows.clear()
        clips.forEachIndexed { i, c ->
            val row = buildCard(c, i)
            rows.add(row)
            list.addView(row.view, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(10) })
            if (i in animFrom..animTo) {
                row.view.translationY = dp(30).toFloat()
                row.view.alpha = 0f
                row.view.animate().translationY(0f).alpha(1f)
                    .setStartDelay((i - animFrom) * 70L).setDuration(420)
                    .setInterpolator(DecelerateInterpolator(1.6f)).start()
            }
        }
        sectionTitle.text = sectionText()
        emptyView.animate().alpha(if (clips.isEmpty()) 1f else 0f).setDuration(300).start()
        if (clips.isNotEmpty()) {
            if (bottomBar.visibility != View.VISIBLE) {
                bottomBar.visibility = View.VISIBLE
                bottomBar.alpha = 0f
                bottomBar.animate().alpha(1f).setDuration(300).start()
            }
        } else {
            bottomBar.visibility = View.GONE
        }
    }

    private fun move(c: Clip, d: Int) {
        val i = clips.indexOf(c)
        val j = i + d
        if (i < 0 || j < 0 || j >= clips.size) return
        clips.removeAt(i)
        clips.add(j, c)
        render()
        rows.getOrNull(j)?.view?.let {
            it.alpha = 0.4f
            it.animate().alpha(1f).setDuration(300).start()
        }
    }

    private fun remove(c: Clip) {
        val v = rows.firstOrNull { it.clip === c }?.view
        val done: () -> Unit = {
            clips.remove(c)
            render()
        }
        if (v == null) {
            done()
            return
        }
        v.animate().alpha(0f).translationX(-dp(70).toFloat()).setDuration(220)
            .withEndAction { done() }.start()
    }

    private fun duplicate(c: Clip) {
        if (!c.loaded) {
            toast("استنى لحظة لحد ما المقطع يتحمّل")
            return
        }
        val i = clips.indexOf(c)
        clips.add(i + 1, Clip(c.uri, c.name, c.sizeBytes, c.durMs, c.startMs, c.endMs, c.dw, c.dh, true))
        render(i + 1, i + 1)
    }

    // ---------- القص ----------

    private fun showTrim(c: Clip) {
        if (!c.loaded || c.durMs <= 0) {
            toast("استنى لحظة لحد ما المقطع يتحمّل")
            return
        }
        val steps = (c.durMs / 100).toInt().coerceAtLeast(10)
        val minGap = 10
        var s = (c.startMs / 100).toInt()
        var e = (c.endMs / 100).toInt().coerceAtMost(steps)

        val preview = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Color.parseColor("#1B2547"))
        }
        val info = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(6))
        }

        fun refresh() {
            info.text = "من ${fmtDur(s * 100L)}   إلى ${fmtDur(e * 100L)}   •   المدة ${fmtDur((e - s) * 100L)}"
        }

        fun frameAt(ms: Long) {
            Thread {
                var b: Bitmap? = null
                val r = MediaMetadataRetriever()
                try {
                    r.setDataSource(this@MainActivity, c.uri)
                    val f = r.getFrameAtTime(ms * 1000L, MediaMetadataRetriever.OPTION_CLOSEST)
                    if (f != null) b = scaled(f, 480)
                } catch (_: Exception) {
                } finally {
                    try {
                        r.release()
                    } catch (_: Exception) {
                    }
                }
                val fb = b
                runOnUiThread { if (!isDestroyed && fb != null) preview.setImageBitmap(fb) }
            }.start()
        }

        fun seek(initial: Int, change: (Int) -> Int, stop: () -> Unit): SeekBar = SeekBar(this).apply {
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            max = steps
            progress = initial
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val v = change(p)
                    if (v != p) sb.progress = v
                    refresh()
                }

                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {
                    stop()
                }
            })
        }

        val sbStart = seek(s, { p ->
            s = p.coerceAtMost(e - minGap).coerceAtLeast(0)
            s
        }, { frameAt(s * 100L) })
        val sbEnd = seek(e, { p ->
            e = p.coerceAtLeast(s + minGap).coerceAtMost(steps)
            e
        }, { frameAt(e * 100L) })

        fun label(t: String) = TextView(this).apply {
            text = t
            textSize = 13f
            setTextColor(cText2)
            setPadding(0, dp(8), 0, 0)
        }

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(20), dp(10), dp(20), dp(4))
            addView(preview, LinearLayout.LayoutParams(MATCH, dp(170)))
            addView(info, LinearLayout.LayoutParams(MATCH, WRAP))
            addView(label("بداية المقطع"), LinearLayout.LayoutParams(MATCH, WRAP))
            addView(sbStart, LinearLayout.LayoutParams(MATCH, WRAP))
            addView(label("نهاية المقطع"), LinearLayout.LayoutParams(MATCH, WRAP))
            addView(sbEnd, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        refresh()
        frameAt(s * 100L)

        darkDialog()
            .setTitle("قص المقطع")
            .setView(box)
            .setPositiveButton("تم") { _, _ ->
                c.startMs = s * 100L
                c.endMs = if (e >= steps) c.durMs else e * 100L
                render()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    // ---------- التصدير ----------

    private fun startExport() {
        if (clips.isEmpty()) return
        if (clips.any { !it.loaded || it.durMs <= 0 }) {
            toast("استنى لحظة لحد ما كل المقاطع تتحمّل")
            return
        }
        val labels = arrayOf("الجودة الأصلية", "1080p", "720p (حجم أصغر)", "480p (أصغر حجم)")
        val presets = intArrayOf(0, 1080, 720, 480)
        darkDialog()
            .setTitle("اختار جودة التصدير")
            .setItems(labels) { _, w -> runExport(presets[w]) }
            .show()
    }

    private fun runExport(preset: Int) {
        val out = File(cacheDir, "export_${System.currentTimeMillis()}.mp4")
        val items = clips.map { c ->
            val clipping = MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs(c.startMs)
                .setEndPositionMs(c.endMs)
                .build()
            val mi = MediaItem.Builder().setUri(c.uri).setClippingConfiguration(clipping).build()
            val b = EditedMediaItem.Builder(mi)
            val short = minOf(c.dw, c.dh)
            if (preset > 0 && short > preset) {
                val th = (c.dh.toFloat() * preset / short).toInt() / 2 * 2
                b.setEffects(
                    Effects(
                        listOf<AudioProcessor>(),
                        listOf<Effect>(Presentation.createForHeight(th))
                    )
                )
            }
            b.build()
        }
        val composition = Composition.Builder(EditedMediaItemSequence(items)).build()

        val pct = TextView(this).apply {
            text = "0%"
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
        }
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(8))
            addView(pct, LinearLayout.LayoutParams(MATCH, WRAP))
            addView(bar, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) })
        }

        var dlg: AlertDialog? = null
        var t: Transformer? = null
        val holder = ProgressHolder()

        t = Transformer.Builder(this).addListener(object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                handler.removeCallbacksAndMessages(null)
                activeTransformer = null
                dlg?.dismiss()
                saveToMovies(out)
            }

            override fun onError(
                composition: Composition,
                exportResult: ExportResult,
                exportException: ExportException
            ) {
                handler.removeCallbacksAndMessages(null)
                activeTransformer = null
                dlg?.dismiss()
                out.delete()
                darkDialog()
                    .setTitle("فشل التصدير")
                    .setMessage(exportException.message ?: "خطأ غير معروف")
                    .setPositiveButton("تمام", null)
                    .show()
            }
        }).build()
        activeTransformer = t

        val d = darkDialog()
            .setTitle("جاري التصدير…")
            .setView(box)
            .setCancelable(false)
            .setNegativeButton("إلغاء") { _, _ ->
                handler.removeCallbacksAndMessages(null)
                try {
                    activeTransformer?.cancel()
                } catch (_: Exception) {
                }
                activeTransformer = null
                out.delete()
            }
            .create()
        dlg = d
        d.show()

        t.start(composition, out.absolutePath)

        val poll = object : Runnable {
            override fun run() {
                val tr = activeTransformer ?: return
                if (tr.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                    bar.progress = holder.progress
                    pct.text = "${holder.progress}%"
                }
                handler.postDelayed(this, 400)
            }
        }
        handler.post(poll)
    }

    private fun saveToMovies(src: File) {
        Thread {
            var savedUri: Uri? = null
            var where = ""
            try {
                val name = "VideoTool_${System.currentTimeMillis()}.mp4"
                if (Build.VERSION.SDK_INT >= 29) {
                    val v = ContentValues().apply {
                        put(MediaStore.Video.Media.DISPLAY_NAME, name)
                        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                        put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/VideoTool")
                    }
                    val dest = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v)
                    if (dest != null) {
                        contentResolver.openOutputStream(dest)?.use { o ->
                            src.inputStream().use { it.copyTo(o) }
                        }
                        savedUri = dest
                        where = "Movies/VideoTool"
                    }
                } else {
                    val dir = getExternalFilesDir(Environment.DIRECTORY_MOVIES)!!
                    val f = File(dir, name)
                    src.copyTo(f, true)
                    savedUri = Uri.fromFile(f)
                    where = f.absolutePath
                }
            } catch (_: Exception) {
            }
            src.delete()
            val u = savedUri
            val w = where
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (u == null) {
                    darkDialog().setTitle("تعذّر حفظ الفيديو")
                        .setMessage("حصلت مشكلة وإحنا بنحفظ الملف النهائي.")
                        .setPositiveButton("تمام", null).show()
                } else {
                    showChapters("تم حفظ الفيديو في $w ✅\nدي فصول يوتيوب جاهزة، عدّل العناوين لو حابب:", u)
                }
            }
        }.start()
    }

    // ---------- فصول يوتيوب ----------

    private fun chaptersText(): String {
        var t = 0L
        val sb = StringBuilder()
        clips.forEachIndexed { i, c ->
            sb.append(fmtDur(t)).append(" السؤال ").append(i + 1).append('\n')
            t += (c.endMs - c.startMs)
        }
        return sb.toString().trim()
    }

    private fun showChapters(message: String, videoUri: Uri?) {
        if (clips.any { !it.loaded }) {
            toast("استنى لحظة لحد ما كل المقاطع تتحمّل")
            return
        }
        val msg = TextView(this).apply {
            text = message
            textSize = 13f
            setTextColor(cText2)
        }
        val et = EditText(this).apply {
            setText(chaptersText())
            setTextColor(Color.WHITE)
            textSize = 14f
            minLines = 4
            gravity = Gravity.TOP or Gravity.START
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(20), dp(10), dp(20), dp(4))
            addView(msg, LinearLayout.LayoutParams(MATCH, WRAP))
            addView(et, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) })
        }
        val b = darkDialog()
            .setTitle("فصول يوتيوب")
            .setView(box)
            .setPositiveButton("نسخ الفصول") { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("chapters", et.text.toString()))
                toast("تم النسخ")
            }
            .setNegativeButton("إغلاق", null)
        if (videoUri != null) {
            b.setNeutralButton("فتح الفيديو") { _, _ ->
                try {
                    startActivity(
                        Intent(Intent.ACTION_VIEW)
                            .setDataAndType(videoUri, "video/mp4")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    )
                } catch (_: Exception) {
                    toast("مفيش تطبيق يفتح الفيديو")
                }
            }
        }
        b.show()
    }
}
