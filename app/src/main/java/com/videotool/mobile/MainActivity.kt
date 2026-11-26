package com.videotool.mobile
import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.pm.ServiceInfo
import android.media.MediaCodecInfo
import android.media.MediaScannerConnection
import android.os.IBinder
import androidx.media3.effect.FrameDropEffect
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
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
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.LruCache
import android.view.Gravity
import android.view.MotionEvent
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.effect.Presentation
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.transformer.AudioEncoderSettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
/** شريط المقطع في التايم لاين: صور مصغّرة متتابعة + إطار متدرّج للمقطع المحدد */
class ClipView(
    ctx: Context,
    val clip: Clip,
    private val framesOf: () -> List<Bitmap>?
) : View(ctx) {
    var label: String = ""
    var chosen: Boolean = false
        set(v) {
            field = v
            invalidate()
        }
    private val dens = ctx.resources.displayMetrics.density
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#1B2547") }
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * dens
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 11f * dens
        typeface = Typeface.DEFAULT_BOLD
        setShadowLayer(3f, 0f, 1f, Color.BLACK)
    }
    private val path = Path()
    private val rect = RectF()
    private val inset = RectF()
    private val src = Rect()
    private val dst = RectF()
    private val vis = Rect()
    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        strokePaint.shader = LinearGradient(
            0f, 0f, w.toFloat(), 0f,
            intArrayOf(Pal.cyan, Pal.purple, Pal.magenta), null, Shader.TileMode.CLAMP
        )
    }
    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val r = 8f * dens
        rect.set(0f, 0f, w, h)
        path.reset()
        path.addRoundRect(rect, r, r, Path.Direction.CW)
        val save = canvas.save()
        canvas.clipPath(path)
        canvas.drawRect(rect, bgPaint)
        val fr = framesOf()
        if (fr != null && fr.isNotEmpty()) {
            val f0 = fr[0]
            val tileW = h * f0.width.toFloat() / f0.height.toFloat()
            if (tileW >= 4f) {
                getLocalVisibleRect(vis)
                val dur = if (clip.srcDurMs > 0L) clip.srcDurMs.toFloat() else 1f
                val span = (clip.endMs - clip.startMs).toFloat()
                var i = (vis.left / tileW).toInt() - 1
                if (i < 0) i = 0
                val last = (vis.right / tileW).toInt() + 1
                while (i <= last && i * tileW < w) {
                    val x = i * tileW
                    val frac = ((x + tileW / 2f) / w).coerceIn(0f, 1f)
                    val srcT = clip.startMs.toFloat() + frac * span
                    var idx = ((srcT / dur) * (fr.size - 1) + 0.5f).toInt()
                    if (idx < 0) idx = 0
                    if (idx > fr.size - 1) idx = fr.size - 1
                    val b = fr[idx]
                    src.set(0, 0, b.width, b.height)
                    dst.set(x, 0f, x + tileW, h)
                    canvas.drawBitmap(b, src, dst, bmpPaint)
                    i++
                }
            }
        }
        canvas.restoreToCount(save)
        if (w > 46f * dens) canvas.drawText(label, 6f * dens, h - 6f * dens, labelPaint)
        if (chosen) {
            val sw = strokePaint.strokeWidth / 2f
            inset.set(sw, sw, w - sw, h - sw)
            canvas.drawRoundRect(inset, r, r, strokePaint)
        }
    }
}
private class Meta(val name: String, val size: Long, val dur: Long, val w: Int, val h: Int)
private class Probe(val fps: Int, val hasAudio: Boolean)
private class GItem(val id: Long, val uri: Uri, val name: String, val durMs: Long, val sizeBytes: Long)
private class PCell(val root: FrameLayout, val iv: ImageView, val dur: TextView, val badge: TextView)
private class Tool(val icon: String, val label: String, val onClick: () -> Unit)
class MainActivity : Activity() {
    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    // ---------- الحالة ----------
    private val clips = ArrayList<Clip>()
    private var sel = -1
    private var playheadMs = 0L
    private val zoomSteps = floatArrayOf(3f, 6f, 12f, 24f, 48f, 96f)
    private var zoomIdx = 2
    private var ratioIdx = 0
    private var fillMode = false
    private var qualityIdx = 2
    private var manualTrans = false
    private var ignoreScroll = false
    private var screenW = 1080
    private val handler = Handler(Looper.getMainLooper())
    private val framesCache = ConcurrentHashMap<Uri, List<Bitmap>>()
    private val framesLoading = HashSet<Uri>()
    private val framesPool = Executors.newFixedThreadPool(2)
    private val thumbPool = Executors.newFixedThreadPool(3)
    private val thumbCache = LruCache<Long, Bitmap>(150)
    private val undoStack = ArrayList<List<Clip>>()
    private val redoStack = ArrayList<List<Clip>>()
    private val clipViews = ArrayList<ClipView>()
    private var cx = IntArray(0)
    private var cw = IntArray(0)
    private var ct = LongArray(0)
    private var player: ExoPlayer? = null
    private var activeTransformer: Transformer? = null
    private lateinit var rootFrame: FrameLayout
    private lateinit var previewHost: FrameLayout
    private lateinit var previewBox: FrameLayout
    private lateinit var tv: TextureView
    private val fxPaint = Paint()
    private var lastFxKey = -1f
    private var currentProjectId: String? = null
    private var currentProjectName: String = ""
    private var skipAutosave = false
    private var lastErrorAt = 0L
    private var badgeKey = ""
    private var previewStopAt = 0L
    private lateinit var abBadge: TextView
    private var fxPreview = true
    private var fxKey = ""
    private var noisePreview = 0 // 0 = حسب إعدادات المقطع، 1 = قبل (الأصلي)، 2 = بعد (بعد التقليل)
    private val pvTrans = PreviewTrState()
    private val pvAudio = PreviewAudioState()
    private lateinit var emptyView: TextView
    private lateinit var timeText: TextView
    private lateinit var playBtn: TextView
    private lateinit var hsv: HorizontalScrollView
    private lateinit var row: LinearLayout
    private lateinit var toolbar: LinearLayout
    private lateinit var toolScroll: HorizontalScrollView
    private lateinit var panelHost: FrameLayout
    private lateinit var overlayHost: FrameLayout
    private val density: Float get() = resources.displayMetrics.density
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun gradBtn(label: String, size: Float = 17f): TextView = TextView(this).apply {
        text = label
        textSize = size
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        setPadding(dp(16), dp(14), dp(16), dp(14))
        background = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(Pal.cyan, Pal.blue, Pal.purple, Pal.magenta)
        ).apply { cornerRadius = dp(28).toFloat() }
        isClickable = true
        pressable()
    }
    private fun darkDialog() = AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
    // ---------- دورة الحياة ----------
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.parseColor("#050816")
        window.navigationBarColor = Color.parseColor("#0A1030")
        screenW = resources.displayMetrics.widthPixels
        cleanOldExports()
        buildUi()
        initPlayer()
        loadProject()
        handler.post(ticker)
        refreshAll()
        validateClips()
        warmProbe()
    }
    override fun onPause() {
        player?.pause()
        if (!skipAutosave) saveProject()
        super.onPause()
    }
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        ExportService.stop(applicationContext)
        try {
            activeTransformer?.cancel()
        } catch (_: Exception) {
        }
        player?.release()
        player = null
        framesPool.shutdownNow()
        thumbPool.shutdownNow()
        super.onDestroy()
    }
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (overlayHost.visibility == View.VISIBLE) {
            closeOverlay()
        } else if (panelHost.visibility == View.VISIBLE) {
            hidePanel()
        } else {
            confirmExit()
        }
    }
    // ---------- بناء الواجهة ----------
    private fun buildUi() {
        rootFrame = FrameLayout(this).apply {
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            fitsSystemWindows = true
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.parseColor("#050816"), Color.parseColor("#0A1030"), Color.parseColor("#150B35"))
            )
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        // الشريط العلوي
        val title = TextView(this).apply {
            text = "Video Tool - Sharkawy"
            textSize = 19f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            val w = paint.measureText(text.toString())
            paint.shader = LinearGradient(
                0f, 0f, w, 0f, intArrayOf(Pal.cyan, Pal.purple, Pal.magenta), null, Shader.TileMode.CLAMP
            )
        }
        val exportTop = gradBtn("تصدير", 14f).apply {
            setPadding(dp(20), dp(8), dp(20), dp(8))
            setOnClickListener { showExportPanel() }
        }
        fun topIcon(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
            text = label
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            isClickable = true
            pressable()
            setOnClickListener { onClick() }
        }
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(8), dp(10), dp(4))
            addView(title, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(topIcon("📂") { showProjects() }, LinearLayout.LayoutParams(WRAP, WRAP))
            addView(topIcon("💾") { quickSave() }, LinearLayout.LayoutParams(WRAP, WRAP))
            addView(exportTop, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(4) })
        }
        // المعاينة
        previewHost = FrameLayout(this)
        previewBox = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        tv = TextureView(this)
        tv.setLayerType(View.LAYER_TYPE_HARDWARE, fxPaint)
        previewBox.addView(tv, FrameLayout.LayoutParams(MATCH, MATCH, Gravity.CENTER))
        emptyView = TextView(this).apply {
            text = "🎬\nضيف فيديوهات وابدأ المونتاج"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Pal.text2)
        }
        previewBox.addView(emptyView, FrameLayout.LayoutParams(MATCH, MATCH))
        abBadge = TextView(this).apply {
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = rounded(this@MainActivity, Color.parseColor("#CC8B5CF6"), 10)
            visibility = View.GONE
        }
        previewBox.addView(
            abBadge,
            FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.START).apply { setMargins(dp(8), dp(8), 0, 0) }
        )
        previewHost.addView(previewBox, FrameLayout.LayoutParams(dp(220), dp(220), Gravity.CENTER))
        previewHost.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, oR, ob ->
            if (r - l != oR - ol || b - t != ob - ot) previewHost.post { layoutPreview() }
        }
        // أزرار التحكم
        timeText = TextView(this).apply {
            text = "00:00 / 00:00"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Pal.text2)
        }
        fun ctl(label: String, sz: Float, onClick: () -> Unit): TextView = TextView(this).apply {
            text = label
            textSize = sz
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(dp(10), dp(4), dp(10), dp(4))
            isClickable = true
            pressable()
            setOnClickListener { onClick() }
        }
        playBtn = ctl("▶", 24f) { togglePlay() }
        val btnStart = ctl("⏮", 20f) { player?.pause(); goTo(0L) }
        val btnEnd = ctl("⏭", 20f) { player?.pause(); goTo(max(0L, totalMs() - 1)) }
        val btnUndo = ctl("↶", 20f) { doUndo() }
        val btnRedo = ctl("↷", 20f) { doRedo() }
        val mid = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(btnStart)
            addView(playBtn)
            addView(btnEnd)
        }
        val rightBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            addView(btnUndo)
            addView(btnRedo)
        }
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(8), 0)
            addView(timeText, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(mid, LinearLayout.LayoutParams(WRAP, WRAP))
            addView(rightBox, LinearLayout.LayoutParams(0, WRAP, 1f))
        }
        // التايم لاين
        row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        hsv = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(row, FrameLayout.LayoutParams(WRAP, MATCH))
            setOnScrollChangeListener { _, sx, _, _, _ ->
                if (!ignoreScroll) onScrub(xToTime(sx + screenW / 2))
            }
            setOnTouchListener { _, e ->
                if (e.action == MotionEvent.ACTION_DOWN) player?.pause()
                false
            }
        }
        val playLine = View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.WHITE, Pal.cyan)
            )
        }
        val playDot = View(this).apply { background = rounded(this@MainActivity, Color.WHITE, 6) }
        val zoomOut = ctl("−", 18f) { zoomBy(-1) }
        val zoomIn = ctl("+", 18f) { zoomBy(1) }
        val zoomBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(this@MainActivity, Color.parseColor("#99101733"), 12)
            addView(zoomOut)
            addView(zoomIn)
        }
        val timeline = FrameLayout(this).apply {
            addView(hsv, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(
                playLine,
                FrameLayout.LayoutParams(dp(3), MATCH, Gravity.CENTER_HORIZONTAL).apply {
                    topMargin = dp(6); bottomMargin = dp(4)
                }
            )
            addView(playDot, FrameLayout.LayoutParams(dp(12), dp(12), Gravity.CENTER_HORIZONTAL or Gravity.TOP))
            addView(
                zoomBox,
                FrameLayout.LayoutParams(WRAP, WRAP, Gravity.END or Gravity.TOP).apply { marginEnd = dp(6) }
            )
        }
        // شريط الأدوات
        toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), 0, dp(6), 0)
        }
        toolScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(toolbar, FrameLayout.LayoutParams(WRAP, MATCH))
        }
        col.addView(topBar, LinearLayout.LayoutParams(MATCH, WRAP))
        col.addView(previewHost, LinearLayout.LayoutParams(MATCH, 0, 1f))
        col.addView(controls, LinearLayout.LayoutParams(MATCH, dp(44)))
        col.addView(timeline, LinearLayout.LayoutParams(MATCH, dp(104)))
        col.addView(toolScroll, LinearLayout.LayoutParams(MATCH, dp(78)).apply { topMargin = dp(4) })
        rootFrame.addView(col, FrameLayout.LayoutParams(MATCH, MATCH))
        panelHost = FrameLayout(this).apply { visibility = View.GONE }
        rootFrame.addView(panelHost, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))
        overlayHost = FrameLayout(this).apply { visibility = View.GONE }
        rootFrame.addView(overlayHost, FrameLayout.LayoutParams(MATCH, MATCH))
        setContentView(rootFrame)
    }
    // ---------- المشغّل ----------
    private fun initPlayer() {
        val rf = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink? {
                return DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(false)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessors(arrayOf<AudioProcessor>(PreviewAudioProcessor(pvAudio)))
                    .build()
            }
        }
        val p = ExoPlayer.Builder(this, rf).build()
        p.setVideoTextureView(tv)
        p.setSeekParameters(SeekParameters.CLOSEST_SYNC)
        p.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playBtn.text = if (isPlaying) "⏸" else "▶"
            }
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                layoutPreview()
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                applyVolume()
                layoutPreview()
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    playheadMs = totalMs()
                    updateTime()
                    scrollToTime(playheadMs)
                    playBtn.text = "▶"
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                val now = System.currentTimeMillis()
                toast("تعذّر تشغيل أحد المقاطع في المعاينة – برجّعها")
                if (now - lastErrorAt > 4000L) {
                    lastErrorAt = now
                    handler.postDelayed({ syncPlayer() }, 300)
                }
            }
        })
        player = p
        applyPreviewEffects()
    }
    private val ticker = object : Runnable {
        override fun run() {
            val p = player
            if (p != null && p.isPlaying && clips.isNotEmpty()) {
                val i = p.currentMediaItemIndex
                if (i in clips.indices) {
                    playheadMs = clipStart(i) + p.currentPosition
                    updatePreviewState()
                    updateTime()
                    scrollToTime(playheadMs)
                    if (previewStopAt > 0L && playheadMs >= previewStopAt) {
                        p.pause()
                        previewStopAt = 0L
                    }
                }
            }
            handler.postDelayed(this, 33)
        }
    }
    /** بيشغّل منطقة الانتقال بين المقطع i والتالي لمدة ثانية ونص وبعدين يقف */
    private fun previewTransition(i: Int) {
        if (i < 0 || i >= clips.size - 1) return
        val p = player ?: return
        val dMs = Transitions.durUs(clips[i].lenMs, clips[i + 1].lenMs) / 1000L
        val boundary = clipStart(i + 1)
        val start = max(0L, boundary - dMs - 500L)
        p.pause()
        playheadMs = start
        updateTime()
        scrollToTime(start)
        seekPlayer(start)
        previewStopAt = boundary + dMs + 400L
        p.play()
    }
    private val settle = Runnable {
        player?.setSeekParameters(SeekParameters.EXACT)
        seekPlayer(playheadMs)
    }
    // ---------- الوقت والمواضع ----------
    private fun totalMs(): Long {
        var t = 0L
        for (c in clips) t += c.lenMs
        return t
    }
    private fun clipStart(index: Int): Long {
        var t = 0L
        for (i in 0 until min(index, clips.size)) t += clips[i].lenMs
        return t
    }
    /** يرجّع (رقم المقطع، الإزاحة جوّاه بالميلي ثانية) لوقت معيّن على التايم لاين */
    private fun locate(tMs: Long): Pair<Int, Long> {
        var acc = 0L
        for (i in clips.indices) {
            val d = clips[i].lenMs
            if (tMs < acc + d || i == clips.lastIndex) {
                return Pair(i, (tMs - acc).coerceIn(0L, max(0L, d - 1L)))
            }
            acc += d
        }
        return Pair(0, 0L)
    }
    private fun updateTime() {
        timeText.text = fmtTime(playheadMs) + " / " + fmtTime(totalMs())
    }
    private fun labelFor(c: Clip): String {
        var s = fmtTime(c.lenMs)
        if (c.denoise) s += " 🎙"
        if (abs(c.gain - 1f) > 0.01f) s += " 🔊" + (c.gain * 100f).roundToInt() + "%"
        return s
    }
    // ---------- التراجع / الإعادة ----------
    private fun snapshot(): List<Clip> = clips.map { it.copy() }
    private fun pushUndo() {
        undoStack.add(snapshot())
        if (undoStack.size > 30) undoStack.removeAt(0)
        redoStack.clear()
    }
    private fun doUndo() {
        if (undoStack.isEmpty()) {
            toast("مفيش حاجة للتراجع عنها")
            return
        }
        hidePanel()
        redoStack.add(snapshot())
        val s = undoStack.removeAt(undoStack.size - 1)
        clips.clear()
        clips.addAll(s)
        sel = -1
        refreshAll()
    }
    private fun doRedo() {
        if (redoStack.isEmpty()) {
            toast("مفيش حاجة للإعادة")
            return
        }
        hidePanel()
        undoStack.add(snapshot())
        val s = redoStack.removeAt(redoStack.size - 1)
        clips.clear()
        clips.addAll(s)
        sel = -1
        refreshAll()
    }
    // ---------- تحديث الواجهة ----------
    private fun refreshAll() {
        if (sel >= clips.size) sel = -1
        playheadMs = playheadMs.coerceIn(0L, totalMs())
        rebuildTimeline()
        syncPlayer()
        setToolsForState()
        updateEmptyState()
        layoutPreview()
        updateTime()
    }
    /** تحديث خفيف بدون إعادة تحميل المعاينة (للصوت والانتقالات والتصدير) */
    private fun refreshLight() {
        rebuildTimeline()
        setToolsForState()
        layoutPreview()
        applyVolume()
        updateTime()
        // نعيد رسم الفريم الحالي عشان المعاينة تعكس التغيير (انتقال/مقاس)
        if (player?.isPlaying != true) seekPlayer(playheadMs)
    }
    private fun updateEmptyState() {
        val empty = clips.isEmpty()
        emptyView.visibility = if (empty) View.VISIBLE else View.GONE
        tv.visibility = if (empty) View.INVISIBLE else View.VISIBLE
    }
    // ---------- التايم لاين ----------
    private fun pxPerMs(): Float = zoomSteps[zoomIdx] * density / 1000f
    private fun zoomBy(d: Int) {
        val ni = (zoomIdx + d).coerceIn(0, zoomSteps.size - 1)
        if (ni == zoomIdx) return
        zoomIdx = ni
        rebuildTimeline()
    }
    private fun rebuildTimeline() {
        ignoreScroll = true
        row.removeAllViews()
        clipViews.clear()
        val n = clips.size
        cx = IntArray(n)
        cw = IntArray(n)
        ct = LongArray(n)
        val half = screenW / 2
        row.setPadding(half, 0, half, 0)
        var x = half
        var t = 0L
        val ppm = pxPerMs()
        val gap = dp(28)
        for (i in 0 until n) {
            val c = clips[i]
            ensureFrames(c.uri, c.srcDurMs)
            val w = max(dp(44), (c.lenMs * ppm).toInt())
            cx[i] = x
            cw[i] = w
            ct[i] = t
            val v = ClipView(this, c) { framesCache[c.uri] }
            v.label = labelFor(c)
            v.chosen = (i == sel)
            v.alpha = if (sel < 0 || i == sel) 1f else 0.72f
            v.setOnClickListener { selectClip(i) }
            clipViews.add(v)
            row.addView(v, LinearLayout.LayoutParams(w, dp(64)))
            x += w
            t += c.lenMs
            if (i < n - 1) {
                row.addView(junctionView(i), LinearLayout.LayoutParams(gap, dp(64)))
                x += gap
            }
        }
        val addB = TextView(this).apply {
            text = "+"
            textSize = 26f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR, intArrayOf(Pal.cyan, Pal.purple, Pal.magenta)
            ).apply { cornerRadius = dp(10).toFloat() }
            isClickable = true
            pressable()
            setOnClickListener { openPicker() }
        }
        row.addView(addB, LinearLayout.LayoutParams(dp(52), dp(64)).apply { marginStart = dp(10) })
        hsv.post {
            scrollToTime(playheadMs)
            ignoreScroll = false
        }
    }
    private fun junctionView(i: Int): View {
        val c = clips[i]
        val has = c.transitionId >= 0
        val tv = TextView(this).apply {
            text = if (has) (c.transitionId + 1).toString() else "⇄"
            textSize = if (has) 10f else 12f
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = if (has) {
                GradientDrawable(
                    GradientDrawable.Orientation.TL_BR, intArrayOf(Pal.cyan, Pal.purple, Pal.magenta)
                ).apply { shape = GradientDrawable.OVAL }
            } else {
                rounded(
                    this@MainActivity, Color.parseColor("#2A3560"), 12,
                    if (manualTrans) Pal.cyan else Color.parseColor("#55FFFFFF"),
                    if (manualTrans) 2 else 1
                )
            }
            isClickable = true
            pressable()
            setOnClickListener { showTransitionLibrary(i) }
        }
        val fl = FrameLayout(this)
        fl.addView(tv, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
        return fl
    }
    private fun timeToX(t: Long): Int {
        val n = clips.size
        if (n == 0 || ct.size != n) return screenW / 2
        for (i in 0 until n) {
            val d = clips[i].lenMs
            if (t < ct[i] + d || i == n - 1) {
                val inClip = (t - ct[i]).coerceIn(0L, max(0L, d))
                val frac = if (d > 0L) inClip.toFloat() / d.toFloat() else 0f
                return cx[i] + (frac * cw[i]).toInt()
            }
        }
        return screenW / 2
    }
    private fun xToTime(x: Int): Long {
        val n = clips.size
        if (n == 0 || ct.size != n) return 0L
        if (x <= cx[0]) return 0L
        for (i in 0 until n) {
            val end = cx[i] + cw[i]
            if (x < end) {
                val frac = ((x - cx[i]).toFloat() / cw[i].toFloat()).coerceIn(0f, 1f)
                return ct[i] + (frac * clips[i].lenMs).toLong()
            }
            if (i < n - 1 && x < cx[i + 1]) return ct[i + 1]
        }
        return totalMs()
    }
    private fun scrollToTime(t: Long) {
        val x = timeToX(t) - screenW / 2
        ignoreScroll = true
        hsv.scrollTo(max(0, x), 0)
        ignoreScroll = false
    }
    private fun onScrub(t: Long) {
        playheadMs = t
        updateTime()
        val p = player ?: return
        if (clips.isEmpty()) return
        p.setSeekParameters(SeekParameters.CLOSEST_SYNC)
        seekPlayer(t)
        handler.removeCallbacks(settle)
        handler.postDelayed(settle, 250)
    }
    private fun goTo(t: Long) {
        playheadMs = t.coerceIn(0L, totalMs())
        updateTime()
        scrollToTime(playheadMs)
        seekPlayer(playheadMs)
    }
    private fun selectClip(i: Int) {
        sel = if (sel == i) -1 else i
        for (k in clipViews.indices) {
            clipViews[k].chosen = (k == sel)
            clipViews[k].alpha = if (sel < 0 || k == sel) 1f else 0.72f
        }
        setToolsForState()
        layoutPreview()
    }
    // ---------- المعاينة ----------
    private fun syncPlayer() {
        val p = player ?: return
        applyPreviewEffects()
        if (clips.isEmpty()) {
            p.stop()
            p.clearMediaItems()
            return
        }
        val items = ArrayList<MediaItem>()
        for (c in clips) {
            val cc = MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs(c.startMs)
                .setEndPositionMs(c.endMs)
                .build()
            items.add(MediaItem.Builder().setUri(c.uri).setClippingConfiguration(cc).build())
        }
        p.setMediaItems(items, false)
        p.prepare()
        seekPlayer(playheadMs)
    }
    private fun seekPlayer(tMs: Long) {
        val p = player ?: return
        if (clips.isEmpty()) return
        val loc = locate(tMs)
        p.seekTo(loc.first, loc.second)
        applyVolume()
        layoutPreview()
    }
    /** الصوت بيتطبّق جوه معالج الصوت (يوصل لـ 200%)، فمستوى المشغّل ثابت 100% */
    private fun applyVolume() {
        player?.volume = 1f
        updatePreviewState()
    }
    private fun previewDims(): Pair<Int, Int> {
        val r = canvasRatio()
        val sd = 540
        var w: Int
        var h: Int
        if (r >= 1f) {
            h = sd
            w = (sd * r).roundToInt()
        } else {
            w = sd
            h = (sd / r).roundToInt()
        }
        return Pair(max(2, w / 2 * 2), max(2, h / 2 * 2))
    }
    private fun applyPreviewEffects() {
        applyPreviewFx()
    }
    /** شكل الفيديو جوه القماش (احتواء/ملء) + تأثير الانتقال الحي، على مستوى الـ View (خفيف ومابيهنجش) */
    private fun applyPreviewFx() {
        val lp = previewBox.layoutParams ?: return
        val cw = lp.width.toFloat()
        val ch = lp.height.toFloat()
        if (cw <= 1f || ch <= 1f || clips.isEmpty()) return
        var vw = 0
        var vh = 0
        val vs = player?.videoSize
        if (vs != null && vs.width > 0 && vs.height > 0) {
            vw = vs.width
            vh = vs.height
        } else {
            val c = clips[locate(playheadMs).first]
            vw = c.dw
            vh = c.dh
        }
        val m = Matrix()
        if (vw > 0 && vh > 0) {
            val sx = cw / vw
            val sy = ch / vh
            val s = if (fillMode) max(sx, sy) else min(sx, sy)
            m.setScale(vw * s / cw, vh * s / ch, cw / 2f, ch / 2f)
        }
        var diag: FloatArray? = null
        val id = pvTrans.id
        val p = pvTrans.p
        if (id >= 0 && p > 0f) {
            val toNdc = Matrix()
            toNdc.postTranslate(-cw / 2f, -ch / 2f)
            toNdc.postScale(2f / cw, -2f / ch)
            val fromNdc = Matrix()
            toNdc.invert(fromNdc)
            val px = Matrix(toNdc)
            px.postConcat(Transitions.matrix(id / 5, p, if (pvTrans.outgoing) 1 else -1, cw / ch))
            px.postConcat(fromNdc)
            m.postConcat(px)
            diag = Transitions.rgbDiag(id, p)
        }
        tv.setTransform(m)
        val key = if (diag == null) 0f else diag[0] * 7f + diag[1] * 13f + diag[2] * 17f + 1f
        if (key != lastFxKey) {
            lastFxKey = key
            fxPaint.colorFilter = if (diag == null) null else ColorMatrixColorFilter(
                ColorMatrix(
                    floatArrayOf(
                        diag[0], 0f, 0f, 0f, 0f,
                        0f, diag[1], 0f, 0f, 0f,
                        0f, 0f, diag[2], 0f, 0f,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
            )
            tv.setLayerPaint(fxPaint)
        }
    }
    /** بيحسب حالة الانتقال والصوت عند مكان المؤشر الحالي */
    private fun updatePreviewState() {
        if (clips.isEmpty()) {
            pvTrans.set(-1, false, 0f)
            return
        }
        val loc = locate(playheadMs)
        val i = loc.first
        val relUs = loc.second * 1000L
        val c = clips[i]
        val lenUs = c.lenMs * 1000L
        var id = -1
        var outg = false
        var pr = 0f
        if (i > 0 && clips[i - 1].transitionId >= 0) {
            val dUs = Transitions.durUs(clips[i - 1].lenMs, c.lenMs)
            if (dUs > 0L && relUs < dUs) {
                id = clips[i - 1].transitionId
                pr = Transitions.progress(relUs, false, lenUs, dUs, id)
            }
        }
        if (id < 0 && i < clips.size - 1 && c.transitionId >= 0) {
            val dUs = Transitions.durUs(c.lenMs, clips[i + 1].lenMs)
            if (dUs > 0L && relUs > lenUs - dUs) {
                id = c.transitionId
                outg = true
                pr = Transitions.progress(relUs, true, lenUs, dUs, id)
            }
        }
        pvTrans.set(id, outg, pr)
        val pl = player
        val ta = if (pl != null && pl.isPlaying) playheadMs + 300L else playheadMs
        val ca = clips[locate(ta).first]
        pvAudio.gain = ca.gain
        pvAudio.denoise = when (noisePreview) {
            1 -> false
            2 -> true
            else -> ca.denoise
        }
        updateAbBadge()
        applyPreviewFx()
    }
    private fun updateAbBadge() {
        var txt = ""
        when (noisePreview) {
            1 -> txt = "🎧 قبل – الأصلي"
            2 -> txt = "🎧 بعد – بعد التقليل"
            else -> if (clips.isNotEmpty()) {
                val c = clips[locate(playheadMs).first]
                if (c.denoise) txt = "🎧 تقليل الضوضاء شغال"
            }
        }
        if (txt == badgeKey) return
        badgeKey = txt
        if (txt.isEmpty()) {
            abBadge.visibility = View.GONE
        } else {
            abBadge.text = txt
            abBadge.visibility = View.VISIBLE
        }
    }
    private fun toggleFx() {
        fxPreview = !fxPreview
        applyPreviewEffects()
        syncPlayer()
        layoutPreview()
        setToolsForState()
        toast(if (fxPreview) "المعاينة الكاملة شغالة (انتقالات + مقاس)" else "المعاينة الخفيفة (بدون انتقالات)")
    }
    private fun togglePlay() {
        val p = player ?: return
        if (clips.isEmpty()) {
            toast("ضيف فيديو الأول")
            return
        }
        previewStopAt = 0L
        if (p.isPlaying) {
            p.pause()
        } else {
            if (p.playbackState == Player.STATE_IDLE) p.prepare()
            if (playheadMs >= totalMs() - 50L) playheadMs = 0L
            seekPlayer(playheadMs)
            p.play()
        }
    }
    private fun canvasRatio(): Float {
        val r = Ratios.all[ratioIdx]
        if (r.w > 0) return r.w.toFloat() / r.h.toFloat()
        val c = clips.firstOrNull()
        if (c != null && c.dw > 0 && c.dh > 0) return c.dw.toFloat() / c.dh.toFloat()
        return 16f / 9f
    }
    private fun layoutPreview() {
        val aw = previewHost.width - dp(16)
        val ah = previewHost.height - dp(8)
        if (aw <= 0 || ah <= 0) return
        val r = canvasRatio()
        var bw = aw
        var bh = (aw / r).toInt()
        if (bh > ah) {
            bh = ah
            bw = (ah * r).toInt()
        }
        val cur = previewBox.layoutParams
        if (cur == null || cur.width != bw || cur.height != bh) {
            previewBox.layoutParams = FrameLayout.LayoutParams(bw, bh, Gravity.CENTER)
        }
        applyPreviewFx()
    }
    // ---------- شريط الأدوات ----------
    private fun mainTools(): List<Tool> = listOf(
        Tool("➕", "إضافة") { openPicker() },
        Tool("✂", "تقسيم") { splitAtPlayhead() },
        Tool("📐", "المقاس") { showCanvasPanel() },
        Tool("🔀", "انتقال") { showTransitionChoice() },
        Tool("🎙", "الضوضاء") { showNoisePanel() },
        Tool("📋", "فصول") { showChapters("انسخ الفصول وحطها في وصف الفيديو على يوتيوب", null) },
        Tool("🗑", "مسح الكل") { confirmClearAll() }
    )
    private fun clipTools(): List<Tool> = listOf(
        Tool("✂", "تقسيم") { splitAtPlayhead() },
        Tool("🔊", "الصوت") { showVolumePanel() },
        Tool("🎙", "الضوضاء") { showNoisePanel() },
        Tool("⏮", "بداية المقطع") { gotoClipStart() },
        Tool("⏭", "نهاية المقطع") { gotoClipEnd() },
        Tool("✂", "قص") { showTrim() },
        Tool("⧉", "نسخ") { duplicateSelected() },
        Tool("◀", "يسار") { moveSelected(-1) },
        Tool("▶", "يمين") { moveSelected(1) },
        Tool("🗑", "حذف") { deleteSelected() },
        Tool("⬅", "رجوع") { selectClip(sel) }
    )
    private fun setToolsForState() {
        setTools(if (sel in clips.indices) clipTools() else mainTools())
    }
    private fun toolBtn(t: Tool): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = true
            pressable()
            setOnClickListener { t.onClick() }
        }
        val ic = TextView(this).apply {
            text = t.icon
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
        }
        val lb = TextView(this).apply {
            text = t.label
            textSize = 10.5f
            gravity = Gravity.CENTER
            setTextColor(Pal.text2)
            maxLines = 1
        }
        box.addView(ic, LinearLayout.LayoutParams(WRAP, WRAP))
        box.addView(lb, LinearLayout.LayoutParams(WRAP, WRAP))
        return box
    }
    private fun setTools(list: List<Tool>) {
        toolbar.removeAllViews()
        for (t in list) toolbar.addView(toolBtn(t), LinearLayout.LayoutParams(dp(68), MATCH))
        toolbar.alpha = 0f
        toolbar.animate().alpha(1f).setDuration(180).start()
        toolScroll.scrollTo(0, 0)
    }
    // ---------- عمليات المقاطع ----------
    private fun splitAtPlayhead() {
        if (clips.isEmpty()) {
            toast("ضيف فيديو الأول")
            return
        }
        val loc = locate(playheadMs)
        val idx = loc.first
        val off = loc.second
        val c = clips[idx]
        if (off < 300L || c.lenMs - off < 300L) {
            toast("حرّك المؤشر لنقطة جوه المقطع عشان تقسّم")
            return
        }
        pushUndo()
        val c2 = c.dup()
        c2.startMs = c.startMs + off
        c.endMs = c.startMs + off
        c.transitionId = -1
        clips.add(idx + 1, c2)
        sel = idx
        refreshAll()
        toast("تم التقسيم ✂")
    }
    private fun deleteSelected() {
        if (sel !in clips.indices) return
        pushUndo()
        val st = clipStart(sel)
        clips.removeAt(sel)
        sel = -1
        playheadMs = min(st, totalMs())
        refreshAll()
        scrollToTime(playheadMs)
    }
    private fun duplicateSelected() {
        if (sel !in clips.indices) return
        pushUndo()
        val c = clips[sel]
        val d = c.dup()
        d.transitionId = c.transitionId
        c.transitionId = -1
        clips.add(sel + 1, d)
        sel = sel + 1
        refreshAll()
    }
    private fun moveSelected(d: Int) {
        val j = sel + d
        if (sel !in clips.indices || j < 0 || j >= clips.size) return
        pushUndo()
        val c = clips.removeAt(sel)
        clips.add(j, c)
        sel = j
        refreshAll()
    }
    private fun gotoClipStart() {
        if (sel !in clips.indices) return
        player?.pause()
        goTo(clipStart(sel))
    }
    private fun gotoClipEnd() {
        if (sel !in clips.indices) return
        player?.pause()
        goTo(clipStart(sel) + clips[sel].lenMs - 1L)
    }
    private fun confirmClearAll() {
        if (clips.isEmpty()) return
        darkDialog()
            .setTitle("مسح كل المقاطع؟")
            .setMessage("هيتشال كل اللي على التايم لاين (الفيديوهات الأصلية مش هتتمسح).")
            .setPositiveButton("مسح") { _, _ ->
                pushUndo()
                clips.clear()
                sel = -1
                playheadMs = 0L
                refreshAll()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }
    // ---------- القص ----------
    private fun showTrim() {
        if (sel !in clips.indices) return
        val c = clips[sel]
        val steps = (c.srcDurMs / 100).toInt().coerceAtLeast(10)
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
        fun refreshInfo() {
            info.text = "من ${fmtTime(s * 100L)}   إلى ${fmtTime(e * 100L)}   •   المدة ${fmtTime((e - s) * 100L)}"
        }
        fun frameAt(ms: Long) {
            Thread {
                var b: Bitmap? = null
                val r = MediaMetadataRetriever()
                try {
                    r.setDataSource(this@MainActivity, c.uri)
                    val f = r.getFrameAtTime(ms * 1000L, MediaMetadataRetriever.OPTION_CLOSEST)
                    if (f != null) b = scaledW(f, 480)
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
                    refreshInfo()
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
            setTextColor(Pal.text2)
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
        refreshInfo()
        frameAt(s * 100L)
        darkDialog()
            .setTitle("قص المقطع")
            .setView(box)
            .setPositiveButton("تم") { _, _ ->
                pushUndo()
                c.startMs = s * 100L
                c.endMs = if (e >= steps) c.srcDurMs else e * 100L
                refreshAll()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }
    private fun scaledW(f: Bitmap, w: Int): Bitmap {
        val h = (w.toFloat() * f.height / f.width).toInt().coerceAtLeast(1)
        val s = Bitmap.createScaledBitmap(f, w, h, true)
        if (s !== f) f.recycle()
        return s
    }
    private fun scaledH(f: Bitmap, h: Int): Bitmap {
        val w = (h.toFloat() * f.width / f.height).toInt().coerceAtLeast(1)
        val s = Bitmap.createScaledBitmap(f, w, h, true)
        if (s !== f) f.recycle()
        return s
    }
    // ---------- اللوحات السفلية ----------
    private fun resetNoisePreview() {
        if (noisePreview != 0) {
            noisePreview = 0
            updateAbBadge()
            applyVolume()
        }
    }
    private fun showPanel(title: String, content: View) {
        resetNoisePreview()
        panelHost.removeAllViews()
        val r = dp(22).toFloat()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F2101A3E"))
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
                setStroke(dp(1), Color.parseColor("#448B5CF6"))
            }
            setPadding(dp(16), dp(12), dp(16), dp(14))
            isClickable = true
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val t = TextView(this).apply {
            text = title
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }
        val done = TextView(this).apply {
            text = "✓"
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Pal.cyan)
            setPadding(dp(12), 0, dp(4), 0)
            isClickable = true
            pressable()
            setOnClickListener { hidePanel() }
        }
        head.addView(t, LinearLayout.LayoutParams(0, WRAP, 1f))
        head.addView(done, LinearLayout.LayoutParams(WRAP, WRAP))
        box.addView(head, LinearLayout.LayoutParams(MATCH, WRAP))
        box.addView(content, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })
        panelHost.addView(box, FrameLayout.LayoutParams(MATCH, WRAP))
        panelHost.visibility = View.VISIBLE
        panelHost.alpha = 0f
        panelHost.translationY = dp(40).toFloat()
        panelHost.animate().alpha(1f).translationY(0f).setDuration(220).start()
    }
    private fun hidePanel() {
        resetNoisePreview()
        panelHost.visibility = View.GONE
        panelHost.removeAllViews()
    }
    private fun closeOverlay() {
        overlayHost.removeAllViews()
        overlayHost.visibility = View.GONE
    }
    // ---------- اختيار الفيديوهات (بأرقام الترتيب) ----------
    private fun mediaPerms(): Array<String> =
        if (Build.VERSION.SDK_INT >= 34)
            arrayOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        else if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_VIDEO)
        else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    /** كامل أو جزئي (أندرويد 14: "صور مختارة فقط") */
    private fun hasMediaPerm(): Boolean {
        for (p in mediaPerms()) if (checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED) return true
        return false
    }
    private fun openPicker() {
        hidePanel()
        if (hasMediaPerm()) {
            showPicker()
        } else {
            requestPermissions(mediaPerms(), 2001)
        }
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 2001) {
            var granted = false
            for (gr in grantResults) if (gr == PackageManager.PERMISSION_GRANTED) granted = true
            if (granted) {
                showPicker()
            } else {
                toast("مفيش إذن للفيديوهات – هفتحلك ملفات الجهاز")
                pickSystem()
            }
        }
    }
    private fun pickSystem() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "video/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addCategory(Intent.CATEGORY_OPENABLE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(i, 1001)
    }
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 1001 || resultCode != RESULT_OK || data == null) return
        val list = ArrayList<Uri>()
        val cd = data.clipData
        if (cd != null) {
            for (n in 0 until cd.itemCount) list.add(cd.getItemAt(n).uri)
        } else {
            val one = data.data
            if (one != null) list.add(one)
        }
        for (u in list) {
            try {
                contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {
            }
        }
        addUris(list)
    }
    private fun showPicker() {
        Thread {
            val list = ArrayList<GItem>()
            try {
                val proj = arrayOf(
                    MediaStore.Video.Media._ID,
                    MediaStore.Video.Media.DISPLAY_NAME,
                    MediaStore.Video.Media.DURATION,
                    MediaStore.Video.Media.SIZE
                )
                val cur = contentResolver.query(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI, proj, null, null,
                    MediaStore.Video.Media.DATE_ADDED + " DESC"
                )
                if (cur != null) {
                    cur.use { c ->
                        val ii = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                        val ni = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                        val di = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                        val si = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                        while (c.moveToNext()) {
                            val id = c.getLong(ii)
                            list.add(
                                GItem(
                                    id,
                                    ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id),
                                    c.getString(ni) ?: "video",
                                    c.getLong(di),
                                    c.getLong(si)
                                )
                            )
                        }
                    }
                }
            } catch (_: Exception) {
            }
            runOnUiThread { if (!isDestroyed) buildPicker(list) }
        }.start()
    }
    @Suppress("DEPRECATION")
    private fun loadThumb(g: GItem): Bitmap? {
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                contentResolver.loadThumbnail(g.uri, android.util.Size(240, 240), null)
            } else {
                MediaStore.Video.Thumbnails.getThumbnail(
                    contentResolver, g.id, MediaStore.Video.Thumbnails.MINI_KIND, null
                )
            }
        } catch (e: Exception) {
            null
        }
    }
    private fun buildPicker(items: List<GItem>) {
        if (items.isEmpty()) {
            toast("مفيش فيديوهات ظاهرة – هفتحلك ملفات الجهاز")
            pickSystem()
            return
        }
        val picks = ArrayList<Int>()
        val cell = (screenW - dp(4) * 4) / 3
        overlayHost.removeAllViews()
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F2050816"))
            isClickable = true
        }
        fun hbtn(t: String, onClick: () -> Unit): TextView = TextView(this).apply {
            text = t
            textSize = 20f
            setTextColor(Color.WHITE)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            isClickable = true
            pressable()
            setOnClickListener { onClick() }
        }
        val titleTv = TextView(this).apply {
            text = "اختر الفيديوهات"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        val hint = TextView(this).apply {
            text = "اضغط على الفيديو لإضافته (ممكن أكتر من مرة) • ضغطة مطوّلة لإلغاء آخر اختيار"
            textSize = 11f
            setTextColor(Pal.text2)
            gravity = Gravity.CENTER
            setPadding(dp(12), 0, dp(12), dp(6))
        }
        val grid = GridView(this).apply {
            numColumns = 3
            horizontalSpacing = dp(4)
            verticalSpacing = dp(4)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            clipToPadding = false
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            setSelector(android.R.color.transparent)
        }
        val doneBtn = gradBtn("إضافة", 16f)
        val adapter = object : BaseAdapter() {
            override fun getCount(): Int = items.size
            override fun getItem(position: Int): Any = items[position]
            override fun getItemId(position: Int): Long = items[position].id
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val h: PCell
                val v: View
                if (convertView == null) {
                    val root = FrameLayout(this@MainActivity).apply {
                        layoutParams = AbsListView.LayoutParams(cell, cell)
                        background = rounded(this@MainActivity, Color.parseColor("#1B2547"), 8)
                        outlineProvider = ViewOutlineProvider.BACKGROUND
                        clipToOutline = true
                    }
                    val iv = ImageView(this@MainActivity).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
                    val dur = TextView(this@MainActivity).apply {
                        textSize = 11f
                        setTextColor(Color.WHITE)
                        setShadowLayer(3f, 0f, 1f, Color.BLACK)
                        setPadding(dp(5), 0, dp(5), dp(3))
                    }
                    val badge = TextView(this@MainActivity).apply {
                        textSize = 12f
                        typeface = Typeface.DEFAULT_BOLD
                        setTextColor(Color.WHITE)
                        setPadding(dp(8), dp(3), dp(8), dp(3))
                        background = GradientDrawable(
                            GradientDrawable.Orientation.TL_BR, intArrayOf(Pal.cyan, Pal.purple, Pal.magenta)
                        ).apply { cornerRadius = dp(10).toFloat() }
                        visibility = View.GONE
                    }
                    root.addView(iv, FrameLayout.LayoutParams(MATCH, MATCH))
                    root.addView(dur, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.START))
                    root.addView(
                        badge,
                        FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.END).apply {
                            setMargins(dp(4), dp(4), dp(4), dp(4))
                        }
                    )
                    h = PCell(root, iv, dur, badge)
                    root.tag = h
                    v = root
                } else {
                    v = convertView
                    h = convertView.tag as PCell
                }
                val gi = items[position]
                h.iv.tag = gi.id
                h.dur.text = fmtTime(gi.durMs)
                val bm = thumbCache.get(gi.id)
                if (bm != null) {
                    h.iv.setImageBitmap(bm)
                } else {
                    h.iv.setImageDrawable(null)
                    thumbPool.execute {
                        val b = loadThumb(gi)
                        if (b != null) {
                            thumbCache.put(gi.id, b)
                            runOnUiThread { if (h.iv.tag == gi.id) h.iv.setImageBitmap(b) }
                        }
                    }
                }
                val nums = ArrayList<String>()
                for (k in picks.indices) if (picks[k] == position) nums.add((k + 1).toString())
                if (nums.isEmpty()) {
                    h.badge.visibility = View.GONE
                    h.root.background = rounded(this@MainActivity, Color.parseColor("#1B2547"), 8)
                } else {
                    h.badge.visibility = View.VISIBLE
                    h.badge.text = nums.joinToString(" · ")
                    h.root.background = rounded(this@MainActivity, Color.parseColor("#1B2547"), 8, Pal.magenta, 2)
                }
                return v
            }
        }
        grid.adapter = adapter
        fun refresh() {
            adapter.notifyDataSetChanged()
            titleTv.text = "اختر الفيديوهات • " + picks.size
            doneBtn.text = "إضافة (" + picks.size + ")"
            doneBtn.alpha = if (picks.isEmpty()) 0.5f else 1f
        }
        grid.setOnItemClickListener { _, _, pos, _ ->
            picks.add(pos)
            refresh()
        }
        grid.setOnItemLongClickListener { _, _, pos, _ ->
            val k = picks.lastIndexOf(pos)
            if (k >= 0) picks.removeAt(k)
            refresh()
            true
        }
        doneBtn.setOnClickListener {
            if (picks.isEmpty()) {
                toast("اختار فيديو واحد على الأقل")
            } else {
                val uris = ArrayList<Uri>()
                for (p in picks) uris.add(items[p].uri)
                closeOverlay()
                addUris(uris)
            }
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(6), dp(4), 0)
            addView(hbtn("✕") { closeOverlay() })
            addView(titleTv, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(hbtn("↶") {
                if (picks.isNotEmpty()) {
                    picks.removeAt(picks.size - 1)
                    refresh()
                }
            })
            addView(hbtn("📁") {
                closeOverlay()
                pickSystem()
            })
        }
        page.addView(head, LinearLayout.LayoutParams(MATCH, WRAP))
        page.addView(hint, LinearLayout.LayoutParams(MATCH, WRAP))
        page.addView(grid, LinearLayout.LayoutParams(MATCH, 0, 1f))
        page.addView(doneBtn, LinearLayout.LayoutParams(MATCH, WRAP).apply {
            setMargins(dp(14), dp(6), dp(14), dp(10))
        })
        overlayHost.addView(page, FrameLayout.LayoutParams(MATCH, MATCH))
        overlayHost.visibility = View.VISIBLE
        refresh()
    }
    // ---------- إضافة المقاطع للتايم لاين ----------
    private fun queryInfo(uri: Uri): Pair<String, Long> {
        var name = "فيديو"
        var size = 0L
        try {
            val c = contentResolver.query(uri, null, null, null, null)
            if (c != null) {
                c.use {
                    if (it.moveToFirst()) {
                        val ni = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val si = it.getColumnIndex(OpenableColumns.SIZE)
                        if (ni >= 0) name = it.getString(ni) ?: name
                        if (si >= 0 && !it.isNull(si)) size = it.getLong(si)
                    }
                }
            }
        } catch (_: Exception) {
        }
        return Pair(name, size)
    }
    private fun readMeta(u: Uri): Meta {
        val q = queryInfo(u)
        var dur = 0L
        var dw = 0
        var dh = 0
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(this, u)
            dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rot == 90 || rot == 270) {
                dw = h
                dh = w
            } else {
                dw = w
                dh = h
            }
        } catch (_: Exception) {
        } finally {
            try {
                r.release()
            } catch (_: Exception) {
            }
        }
        return Meta(q.first, q.second, dur, dw, dh)
    }
    private fun addUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        toast("جاري إضافة " + uris.size + " مقطع…")
        Thread {
            val map = HashMap<Uri, Meta>()
            for (u in uris) if (!map.containsKey(u)) {
                map[u] = readMeta(u)
                probeSrc(u)
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                pushUndo()
                var added = 0
                for (u in uris) {
                    val m = map[u] ?: continue
                    if (m.dur <= 0L) continue
                    clips.add(Clip(Ids.next(), u, m.name, m.dur, 0L, m.dur, m.size, 1f, false, -1, m.w, m.h))
                    added++
                }
                if (added == 0) {
                    if (undoStack.isNotEmpty()) undoStack.removeAt(undoStack.size - 1)
                    toast("تعذّر قراءة الفيديوهات دي")
                } else {
                    refreshAll()
                }
            }
        }.start()
    }
    private fun ensureFrames(uri: Uri, durMs: Long) {
        if (framesCache.containsKey(uri) || !framesLoading.add(uri)) return
        try {
            framesPool.execute {
                val list = ArrayList<Bitmap>()
                val r = MediaMetadataRetriever()
                try {
                    r.setDataSource(this@MainActivity, uri)
                    val n = (durMs / 6000L).toInt().coerceIn(5, 24)
                    for (k in 0 until n) {
                        var tUs = durMs * 1000L * k / n
                        val maxUs = max(0L, durMs - 1L) * 1000L
                        if (tUs > maxUs) tUs = maxUs
                        val f = r.getFrameAtTime(tUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        if (f != null) list.add(scaledH(f, 120))
                    }
                } catch (_: Exception) {
                } finally {
                    try {
                        r.release()
                    } catch (_: Exception) {
                    }
                }
                runOnUiThread {
                    framesLoading.remove(uri)
                    if (list.isNotEmpty()) framesCache[uri] = list
                    for (cv in clipViews) if (cv.clip.uri == uri) cv.invalidate()
                }
            }
        } catch (_: Exception) {
            framesLoading.remove(uri)
        }
    }
    // ---------- المقاس ----------
    private fun showCanvasPanel() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val modeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val fitPill = TextView(this)
        val fillPill = TextView(this)
        fun paintMode() {
            val on = rounded(this, Color.parseColor("#4F6BFF"), 14, Pal.cyan, 2)
            val off = rounded(this, Color.parseColor("#2A3560"), 14)
            fitPill.background = if (!fillMode) on else off
            fillPill.background = if (fillMode) on else off
        }
        for (p in listOf(fitPill, fillPill)) {
            p.textSize = 13f
            p.gravity = Gravity.CENTER
            p.setTextColor(Color.WHITE)
            p.setPadding(dp(8), dp(10), dp(8), dp(10))
            p.isClickable = true
            p.pressable()
        }
        fitPill.text = "احتواء (بدون قص)"
        fillPill.text = "ملء الشاشة (قص)"
        fitPill.setOnClickListener {
            fillMode = false
            paintMode()
            layoutPreview()
        }
        fillPill.setOnClickListener {
            fillMode = true
            paintMode()
            layoutPreview()
        }
        paintMode()
        modeRow.addView(fitPill, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginEnd = dp(6) })
        modeRow.addView(fillPill, LinearLayout.LayoutParams(0, WRAP, 1f))
        box.addView(modeRow, LinearLayout.LayoutParams(MATCH, WRAP))
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val rows = ArrayList<LinearLayout>()
        fun paintRows() {
            for (k in rows.indices) {
                rows[k].background = if (k == ratioIdx) {
                    rounded(this, Color.parseColor("#334F6BFF"), 14, Pal.cyan, 2)
                } else {
                    rounded(this, Color.parseColor("#1B2547"), 14)
                }
            }
        }
        for ((idx, ro) in Ratios.all.withIndex()) {
            val iconBox = FrameLayout(this)
            val icon = View(this).apply { background = rounded(this@MainActivity, Color.TRANSPARENT, 4, Color.WHITE, 2) }
            var iw = 28
            var ih = 28
            if (ro.w > 0) {
                val rr = ro.w.toFloat() / ro.h.toFloat()
                if (rr >= 1f) {
                    iw = 36
                    ih = max(12, (36f / rr).toInt())
                } else {
                    ih = 36
                    iw = max(12, (36f * rr).toInt())
                }
            }
            iconBox.addView(icon, FrameLayout.LayoutParams(dp(iw), dp(ih), Gravity.CENTER))
            val t1 = TextView(this).apply {
                text = ro.label
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
            }
            val t2 = TextView(this).apply {
                text = ro.sub
                textSize = 11f
                setTextColor(Pal.text2)
            }
            val txt = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(t1)
                addView(t2)
            }
            val rw = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(8), dp(10), dp(8))
                isClickable = true
                pressable()
                setOnClickListener {
                    ratioIdx = idx
                    paintRows()
                    layoutPreview()
                }
            }
            rw.addView(iconBox, LinearLayout.LayoutParams(dp(48), dp(44)))
            rw.addView(txt, LinearLayout.LayoutParams(0, WRAP, 1f))
            rows.add(rw)
            list.addView(rw, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(6) })
        }
        paintRows()
        val sc = ScrollView(this).apply { addView(list) }
        box.addView(sc, LinearLayout.LayoutParams(MATCH, dp(300)))
        showPanel("مقاس الفيديو", box)
    }
    // ---------- الانتقالات ----------
    private fun showTransitionChoice() {
        if (clips.size < 2) {
            toast("ضيف مقطعين على الأقل عشان تحط انتقال")
            return
        }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val desc = TextView(this).apply {
            text = "100 انتقال احترافي. اختار يدوي عشان تحدد لكل مقطع، أو تلقائي عشان يوزّعهم عشوائي بدون تكرار."
            textSize = 13f
            setTextColor(Pal.text2)
        }
        val manual = gradBtn("✋  يدوي – أنا أختار", 15f).apply {
            setOnClickListener {
                hidePanel()
                manualTrans = true
                rebuildTimeline()
                toast("اضغط على علامة ⇄ بين أي مقطعين واختار الانتقال")
            }
        }
        val auto = gradBtn("🎲  تلقائي – عشوائي بدون تكرار", 15f).apply {
            setOnClickListener {
                hidePanel()
                autoTransitions()
            }
        }
        val clear = TextView(this).apply {
            text = "إزالة كل الانتقالات"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Pal.text2)
            setPadding(0, dp(12), 0, dp(4))
            isClickable = true
            pressable()
            setOnClickListener {
                pushUndo()
                for (c in clips) c.transitionId = -1
                manualTrans = false
                hidePanel()
                refreshLight()
            }
        }
        box.addView(desc, LinearLayout.LayoutParams(MATCH, WRAP))
        box.addView(manual, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) })
        box.addView(auto, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) })
        box.addView(clear, LinearLayout.LayoutParams(MATCH, WRAP))
        showPanel("الانتقالات", box)
    }
    private fun autoTransitions() {
        if (clips.size < 2) return
        pushUndo()
        for (i in 0 until clips.size - 1) clips[i].transitionId = Transitions.nextRandom()
        clips[clips.size - 1].transitionId = -1
        manualTrans = false
        refreshLight()
        toast("تم توزيع " + (clips.size - 1) + " انتقال عشوائي 🎲")
    }
    private fun showTransitionLibrary(i: Int) {
        if (i < 0 || i >= clips.size - 1) return
        val c = clips[i]
        val cellH = dp(84)
        val grid = GridView(this).apply {
            numColumns = 3
            horizontalSpacing = dp(6)
            verticalSpacing = dp(6)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            setSelector(android.R.color.transparent)
        }
        val adapter = object : BaseAdapter() {
            override fun getCount(): Int = Transitions.count + 1
            override fun getItem(position: Int): Any = position
            override fun getItemId(position: Int): Long = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val id = position - 1
                val isSel = (c.transitionId == id)
                val b = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = AbsListView.LayoutParams(MATCH, cellH)
                    setPadding(dp(5), dp(5), dp(5), dp(4))
                    background = if (isSel) {
                        rounded(this@MainActivity, Color.parseColor("#1B2547"), 10, Pal.magenta, 2)
                    } else {
                        rounded(this@MainActivity, Color.parseColor("#1B2547"), 10)
                    }
                }
                if (id >= 0) {
                    val pv = TransitionPreview(this@MainActivity)
                    pv.setTransition(id)
                    b.addView(pv, LinearLayout.LayoutParams(MATCH, 0, 1f))
                } else {
                    val none = TextView(this@MainActivity).apply {
                        text = "⊘"
                        textSize = 26f
                        gravity = Gravity.CENTER
                        setTextColor(Pal.text2)
                    }
                    b.addView(none, LinearLayout.LayoutParams(MATCH, 0, 1f))
                }
                val nm = TextView(this@MainActivity).apply {
                    text = if (id >= 0) (id + 1).toString() + ". " + Transitions.name(id) else "بدون انتقال"
                    textSize = 9.5f
                    setTextColor(Pal.text2)
                    maxLines = 1
                    gravity = Gravity.CENTER
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(0, dp(3), 0, 0)
                }
                b.addView(nm, LinearLayout.LayoutParams(MATCH, WRAP))
                return b
            }
        }
        grid.adapter = adapter
        grid.setOnItemClickListener { _, _, pos, _ ->
            pushUndo()
            c.transitionId = pos - 1
            adapter.notifyDataSetChanged()
            refreshLight()
            if (pos - 1 >= 0) previewTransition(i)
        }
        val applyAll = gradBtn("تطبيق الانتقال المختار على كل المقاطع", 13f).apply {
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setOnClickListener {
                if (c.transitionId < 0) {
                    toast("اختار انتقال الأول")
                } else {
                    pushUndo()
                    for (k in 0 until clips.size - 1) clips[k].transitionId = c.transitionId
                    adapter.notifyDataSetChanged()
                    refreshLight()
                    toast("تم التطبيق على كل المقاطع")
                }
            }
        }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val sub = TextView(this).apply {
            text = "بين المقطع " + (i + 1) + " والمقطع " + (i + 2)
            textSize = 12f
            setTextColor(Pal.text2)
            setPadding(0, 0, 0, dp(6))
        }
        box.addView(sub, LinearLayout.LayoutParams(MATCH, WRAP))
        box.addView(grid, LinearLayout.LayoutParams(MATCH, dp(300)))
        box.addView(applyAll, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })
        showPanel("اختر الانتقال (100)", box)
    }
    // ---------- الضوضاء والصوت ----------
    private fun showNoisePanel() {
        if (clips.isEmpty()) {
            toast("ضيف فيديو الأول")
            return
        }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val desc = TextView(this).apply {
            text = "بيقلل الهمهمة وصوت الخلفية والهسهسة مع الحفاظ على الكلام. بيشتغل على طول في المعاينة وانت بتشتغل (هتسمعه)، وبيتطبق نفسه في التصدير."
            textSize = 13f
            setTextColor(Pal.text2)
        }
        box.addView(desc, LinearLayout.LayoutParams(MATCH, WRAP))
        // مقارنة قبل / بعد (للمعاينة بس)
        resetNoisePreview()
        val pCur = TextView(this)
        val pBefore = TextView(this)
        val pAfter = TextView(this)
        fun paintAB() {
            val on = rounded(this, Color.parseColor("#4F6BFF"), 14, Pal.cyan, 2)
            val off = rounded(this, Color.parseColor("#2A3560"), 14)
            pCur.background = if (noisePreview == 0) on else off
            pBefore.background = if (noisePreview == 1) on else off
            pAfter.background = if (noisePreview == 2) on else off
        }
        fun setMode(m: Int) {
            noisePreview = m
            paintAB()
            updateAbBadge()
            applyVolume()
        }
        pBefore.text = "🎧 قبل"
        pAfter.text = "🎧 بعد"
        pCur.text = "الحالي"
        for (pp in listOf(pBefore, pAfter, pCur)) {
            pp.textSize = 13f
            pp.gravity = Gravity.CENTER
            pp.setTextColor(Color.WHITE)
            pp.setPadding(dp(8), dp(10), dp(8), dp(10))
            pp.isClickable = true
            pp.pressable()
        }
        pBefore.setOnClickListener { setMode(1) }
        pAfter.setOnClickListener { setMode(2) }
        pCur.setOnClickListener { setMode(0) }
        paintAB()
        val abRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(pBefore, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginEnd = dp(6) })
            addView(pAfter, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginEnd = dp(6) })
            addView(pCur, LinearLayout.LayoutParams(0, WRAP, 1f))
        }
        val abHint = TextView(this).apply {
            text = "شغّل الفيديو واضغط قبل/بعد وانت سامع. ده للمعاينة بس، والتطبيق الفعلي بزرار Apply."
            textSize = 11f
            setTextColor(Pal.text2)
            setPadding(0, dp(6), 0, 0)
        }
        box.addView(abRow, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) })
        box.addView(abHint, LinearLayout.LayoutParams(MATCH, WRAP))
        val strTv = TextView(this).apply {
            text = "قوة التقليل: " + String.format(Locale.US, "%.1f", SpectralDenoiser.strength)
            textSize = 12f
            setTextColor(Pal.text2)
            setPadding(0, dp(10), 0, 0)
        }
        val strBar = SeekBar(this).apply {
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            max = 45
            progress = ((SpectralDenoiser.strength - 2.5f) * 10f).roundToInt().coerceIn(0, 45)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    SpectralDenoiser.strength = 2.5f + p / 10f
                    strTv.text = "قوة التقليل: " + String.format(Locale.US, "%.1f", SpectralDenoiser.strength)
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }
        box.addView(strTv, LinearLayout.LayoutParams(MATCH, WRAP))
        box.addView(strBar, LinearLayout.LayoutParams(MATCH, WRAP))
        if (sel in clips.indices) {
            val c = clips[sel]
            val sw = Switch(this).apply {
                text = "تقليل الضوضاء للمقطع المحدد"
                setTextColor(Color.WHITE)
                isChecked = c.denoise
                setPadding(0, dp(12), 0, dp(12))
                setOnCheckedChangeListener { _, b ->
                    pushUndo()
                    c.denoise = b
                    val cv = clipViews.getOrNull(sel)
                    if (cv != null) {
                        cv.label = labelFor(c)
                        cv.invalidate()
                    }
                }
            }
            box.addView(sw, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        val all = gradBtn("🎙  تطبيق على كل الفيديوهات (Apply to all)", 15f).apply {
            setOnClickListener { applyNoiseAll(true) }
        }
        val off = TextView(this).apply {
            text = "إلغاء تقليل الضوضاء من الكل"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Pal.text2)
            setPadding(0, dp(12), 0, dp(4))
            isClickable = true
            pressable()
            setOnClickListener { applyNoiseAll(false) }
        }
        box.addView(all, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) })
        box.addView(off, LinearLayout.LayoutParams(MATCH, WRAP))
        showPanel("تقليل الضوضاء", box)
    }
    private fun applyNoiseAll(on: Boolean) {
        pushUndo()
        for (c in clips) c.denoise = on
        hidePanel()
        refreshLight()
        if (on) showGreen("reduce noise for all video ✅") else toast("تم إلغاء تقليل الضوضاء من كل المقاطع")
    }
    /** رسالة خضراء: مش بتقفل غير لما تضغط عليها */
    private fun showGreen(text: String) {
        val card = TextView(this).apply {
            this.text = text
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(dp(24), dp(36), dp(24), dp(36))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.parseColor("#16A34A"), Color.parseColor("#22C55E"))
            ).apply { cornerRadius = dp(22).toFloat() }
            isClickable = true
            pressable()
        }
        val hint = TextView(this).apply {
            this.text = "اضغط على الرسالة للمتابعة"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(Pal.text2)
            setPadding(0, dp(10), 0, 0)
        }
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(14))
            addView(card, LinearLayout.LayoutParams(MATCH, WRAP))
            addView(hint, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        val dlg = darkDialog().setView(wrap).setCancelable(false).create()
        card.setOnClickListener { dlg.dismiss() }
        dlg.setCanceledOnTouchOutside(false)
        dlg.show()
    }
    private fun showVolumePanel() {
        if (sel !in clips.indices) return
        val c = clips[sel]
        var pushed = false
        var busy = false
        val big = TextView(this).apply {
            textSize = 30f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
        }
        val seek = SeekBar(this).apply {
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            max = 200
            progress = (c.gain * 100f).roundToInt()
        }
        val et = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 16f
            setText((c.gain * 100f).roundToInt().toString())
        }
        fun setVol(v: Int) {
            val vv = v.coerceIn(0, 200)
            if (!pushed) {
                pushUndo()
                pushed = true
            }
            c.gain = vv / 100f
            busy = true
            big.text = "$vv%"
            if (seek.progress != vv) seek.progress = vv
            if (et.text.toString() != vv.toString()) {
                et.setText(vv.toString())
                et.setSelection(et.text.length)
            }
            busy = false
            applyVolume()
            val cv = clipViews.getOrNull(sel)
            if (cv != null) {
                cv.label = labelFor(c)
                cv.invalidate()
            }
        }
        big.text = (c.gain * 100f).roundToInt().toString() + "%"
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                if (fromUser) setVol(p)
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
        et.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c1: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c1: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (busy) return
                val v = s?.toString()?.toIntOrNull()
                if (v != null) setVol(v)
            }
        })
        fun stepBtn(t: String, d: Int): TextView = TextView(this).apply {
            text = t
            textSize = 24f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = rounded(this@MainActivity, Color.parseColor("#2A3560"), 14)
            isClickable = true
            pressable()
            setOnClickListener { setVol((c.gain * 100f).roundToInt() + d) }
        }
        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(stepBtn("−", -5), LinearLayout.LayoutParams(dp(56), dp(48)))
            addView(et, LinearLayout.LayoutParams(0, WRAP, 1f).apply {
                marginStart = dp(8); marginEnd = dp(8)
            })
            addView(stepBtn("+", 5), LinearLayout.LayoutParams(dp(56), dp(48)))
        }
        val mute = TextView(this).apply {
            text = "🔇 كتم"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(dp(8), dp(10), dp(8), dp(10))
            background = rounded(this@MainActivity, Color.parseColor("#2A3560"), 14)
            isClickable = true
            pressable()
            setOnClickListener { setVol(0) }
        }
        val reset = TextView(this).apply {
            text = "100% (الأصلي)"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(dp(8), dp(10), dp(8), dp(10))
            background = rounded(this@MainActivity, Color.parseColor("#2A3560"), 14)
            isClickable = true
            pressable()
            setOnClickListener { setVol(100) }
        }
        val quick = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(mute, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginEnd = dp(6) })
            addView(reset, LinearLayout.LayoutParams(0, WRAP, 1f))
        }
        val note = TextView(this).apply {
            text = "المعاينة بتسمّعك نفس الصوت اللي هيطلع في التصدير (لحد 200%)."
            textSize = 11f
            setTextColor(Pal.text2)
            setPadding(0, dp(8), 0, 0)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(big, LinearLayout.LayoutParams(MATCH, WRAP))
            addView(seek, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(6) })
            addView(row2, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) })
            addView(quick, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) })
            addView(note, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        showPanel("مستوى الصوت", box)
    }
    // ---------- التصدير ----------
    private var exportPoll: Runnable? = null
    private val probeCache = ConcurrentHashMap<Uri, Probe>()
    /** بيقرا من الملف: معدل الفريمات + هل فيه مسار صوت (بيتخزّن عشان مايتكررش) */
    private fun probeSrc(u: Uri): Probe {
        val cached = probeCache[u]
        if (cached != null) return cached
        var fps = 0
        var audio = false
        val ex = android.media.MediaExtractor()
        try {
            ex.setDataSource(this, u, null)
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val mime = f.getString(android.media.MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) audio = true
                if (mime.startsWith("video/") && fps == 0 && f.containsKey(android.media.MediaFormat.KEY_FRAME_RATE)) {
                    fps = try {
                        f.getInteger(android.media.MediaFormat.KEY_FRAME_RATE)
                    } catch (_: Exception) {
                        try {
                            f.getFloat(android.media.MediaFormat.KEY_FRAME_RATE).toInt()
                        } catch (_: Exception) {
                            0
                        }
                    }
                }
            }
        } catch (_: Exception) {
            audio = true   // مش عارفين نقرا الملف: نفترض إنه سليم
        } finally {
            try {
                ex.release()
            } catch (_: Exception) {
            }
        }
        val p = Probe(fps, audio)
        probeCache[u] = p
        return p
    }
    /** فحص قبل التصدير: مساحة فاضية كفاية + مقاطع من غير صوت وسط مقاطع فيها صوت */
    private fun preExport(q: Quality, go: () -> Unit) {
        val sec = totalMs() / 1000.0
        val bytes = ((Qualities.videoBps(q, sec) + Qualities.AUDIO_BPS) * sec / 8.0).toLong()
        val need = (bytes * 2.2).toLong() + 50_000_000L
        val free = try {
            cacheDir.usableSpace
        } catch (_: Exception) {
            Long.MAX_VALUE
        }
        if (free < need) {
            darkDialog()
                .setTitle("المساحة مش كفاية")
                .setMessage("التصدير محتاج حوالي " + fmtSize(need) + " فاضية (ملف مؤقت + النسخة النهائية)، والمتاح " + fmtSize(free) + ". فضّي مساحة وجرّب تاني.")
                .setPositiveButton("تمام", null)
                .show()
            return
        }
        val silent = ArrayList<Int>()
        var withAudio = 0
        for (i in clips.indices) {
            if (probeSrc(clips[i].uri).hasAudio) withAudio++ else silent.add(i + 1)
        }
        if (withAudio > 0 && silent.isNotEmpty()) {
            darkDialog()
                .setTitle("مقاطع من غير صوت")
                .setMessage("المقاطع رقم " + silent.joinToString("، ") + " مفيهاش مسار صوت وباقي المقاطع فيها صوت. ممكن التصدير يفشل أو الصوت يتلخبط. الأفضل تشيلها أو تستبدلها.")
                .setPositiveButton("كمّل برضه") { _, _ -> go() }
                .setNegativeButton("إلغاء", null)
                .show()
            return
        }
        go()
    }
    /** بيمسح ملفات التصدير المؤقتة المتبقية من مرات سابقة (بتبقى كبيرة) */
    private fun cleanOldExports() {
        try {
            val fs = cacheDir.listFiles() ?: return
            for (f in fs) if (f.name.startsWith("export_") && f.name.endsWith(".mp4")) f.delete()
        } catch (_: Exception) {
        }
    }
    private fun outDims(q: Quality): Pair<Int, Int> {
        val r = canvasRatio()
        var s = Qualities.shortSide(q, Qualities.videoBps(q, totalMs() / 1000.0))
        if (Ratios.all[ratioIdx].w == 0) {
            val c = clips.firstOrNull()
            if (c != null && c.dw > 0 && c.dh > 0) s = min(s, min(c.dw, c.dh))
        }
        var w: Int
        var h: Int
        if (r >= 1f) {
            h = s
            w = (s * r).roundToInt()
        } else {
            w = s
            h = (s / r).roundToInt()
        }
        w = max(2, w / 2 * 2)
        h = max(2, h / 2 * 2)
        return Pair(w, h)
    }
    private fun showExportPanel() {
        if (clips.isEmpty()) {
            toast("ضيف فيديو الأول")
            return
        }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val sec = totalMs() / 1000.0
        val info = TextView(this).apply {
            text = "المقاس: " + Ratios.all[ratioIdx].label + "  •  المدة: " + fmtTime(totalMs()) +
                "  •  " + (if (Qualities.hevcOk) "HEVC" else "H.264")
            textSize = 12f
            setTextColor(Pal.text2)
        }
        box.addView(info, LinearLayout.LayoutParams(MATCH, WRAP))
        val rows = ArrayList<LinearLayout>()
        fun paintRows() {
            for (k in rows.indices) {
                rows[k].background = if (k == qualityIdx) {
                    rounded(this, Color.parseColor("#334F6BFF"), 14, Pal.cyan, 2)
                } else {
                    rounded(this, Color.parseColor("#1B2547"), 14)
                }
            }
        }
        for ((idx, q) in Qualities.all.withIndex()) {
            val d = outDims(q)
            val bytes = (Qualities.videoBps(q, sec) + Qualities.AUDIO_BPS) * sec / 8.0
            val t1 = TextView(this).apply {
                text = q.label + "  •  " + d.first + "×" + d.second
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
            }
            val t2 = TextView(this).apply {
                text = q.note + "  •  حتى ≈ " + fmtSize(bytes.toLong())
                textSize = 12f
                setTextColor(Pal.text2)
            }
            val rw = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(10), dp(14), dp(10))
                isClickable = true
                pressable()
                setOnClickListener {
                    qualityIdx = idx
                    paintRows()
                }
                addView(t1)
                addView(t2)
            }
            rows.add(rw)
            box.addView(rw, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })
        }
        paintRows()
        val start = gradBtn("ابدأ التصدير 🚀", 16f).apply {
            setOnClickListener {
                hidePanel()
                val qq = Qualities.all[qualityIdx]
                preExport(qq) { runExport(qq) }
            }
        }
        box.addView(start, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) })
        val sv = ScrollView(this)
        sv.addView(box)
        showPanel("تصدير الفيديو", sv)
    }
    private fun runExport(q: Quality) {
        if (clips.isEmpty()) return
        player?.pause()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2002)
        }
        val totalSec = totalMs() / 1000.0
        val vBps = Qualities.videoBps(q, totalSec)
        val useHevc = Qualities.hevcOk
        val chain = DenoiseChain()
        val dims = outDims(q)
        val ow = dims.first
        val oh = dims.second
        val layout = if (fillMode) Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP else Presentation.LAYOUT_SCALE_TO_FIT
        val ar = ow.toFloat() / oh.toFloat()
        val out = File(cacheDir, "export_" + System.currentTimeMillis() + ".mp4")
        val items = ArrayList<EditedMediaItem>()
        for (i in clips.indices) {
            val c = clips[i]
            val clipping = MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs(c.startMs)
                .setEndPositionMs(c.endMs)
                .build()
            val mi = MediaItem.Builder().setUri(c.uri).setClippingConfiguration(clipping).build()
            var inId = -1
            var inUs = 0L
            var outId = -1
            var outUs = 0L
            if (i > 0 && clips[i - 1].transitionId >= 0) {
                inId = clips[i - 1].transitionId
                inUs = Transitions.durUs(clips[i - 1].lenMs, c.lenMs)
            }
            if (i < clips.size - 1 && c.transitionId >= 0) {
                outId = c.transitionId
                outUs = Transitions.durUs(c.lenMs, clips[i + 1].lenMs)
            }
            val lenUs = c.lenMs * 1000L
            val clock = TrClock()
            val vfx = ArrayList<Effect>()
            // تسجيلات 60fps: نقلل لـ 30 (نفس الجودة تقريبًا في شرح الشاشة وحجم أقل بكتير)
            if (probeSrc(c.uri).fps > 40) vfx.add(FrameDropEffect.createDefaultFrameDropEffect(30f))
            vfx.add(Presentation.createForWidthAndHeight(ow, oh, layout))
            if (inId >= 0) vfx.add(TrMatrixFx(inId, false, lenUs, inUs, ar, clock))
            if (outId >= 0) vfx.add(TrMatrixFx(outId, true, lenUs, outUs, ar, clock))
            if (inId >= 0) vfx.add(TrRgbFx(inId, false, lenUs, inUs, clock))
            if (outId >= 0) vfx.add(TrRgbFx(outId, true, lenUs, outUs, clock))
            val afx = ArrayList<AudioProcessor>()
            val fadeIn = if (i > 0) (if (inId >= 0) 150 else 15) else 0
            val fadeOut = if (i < clips.size - 1) (if (outId >= 0) 150 else 15) else 0
            if (c.denoise || abs(c.gain - 1f) > 0.01f || fadeIn > 0 || fadeOut > 0) {
                afx.add(ClipAudioProcessor(c.gain, c.denoise, fadeIn, fadeOut, c.lenMs, chain))
            }
            val b = EditedMediaItem.Builder(mi)
            b.setEffects(Effects(afx, vfx))
            items.add(b.build())
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
        val speedTv = TextView(this).apply {
            text = "ابقى سايب التطبيق مفتوح لحد ما يخلص"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(Pal.text2)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(8))
            addView(pct, LinearLayout.LayoutParams(MATCH, WRAP))
            addView(bar, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) })
            addView(speedTv, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })
        }
        var dlg: AlertDialog? = null
        val holder = ProgressHolder()
        val startTs = System.currentTimeMillis()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val stopPoll = {
            val r = exportPoll
            if (r != null) handler.removeCallbacks(r)
            exportPoll = null
            val appCtx = applicationContext
            handler.postDelayed({ ExportService.stop(appCtx) }, 1500)
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        val encFactory = DefaultEncoderFactory.Builder(this)
            .setRequestedVideoEncoderSettings(
                VideoEncoderSettings.Builder()
                    .setBitrate(vBps)
                    .setBitrateMode(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                    .setiFrameIntervalSeconds(3f)
                    .build()
            )
            .setRequestedAudioEncoderSettings(AudioEncoderSettings.Builder().setBitrate(Qualities.AUDIO_BPS).build())
            .setEnableFallback(true)
            .build()
        val t = Transformer.Builder(this)
            .setVideoMimeType(if (useHevc) MimeTypes.VIDEO_H265 else MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .setEncoderFactory(encFactory)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    stopPoll()
                    activeTransformer = null
                    dlg?.dismiss()
                    saveToMovies(out)
                }
                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException
                ) {
                    stopPoll()
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
                stopPoll()
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
        ExportService.start(applicationContext)
        t.start(composition, out.absolutePath)
        var lastP = -1
        val poll = object : Runnable {
            override fun run() {
                val tr = activeTransformer ?: return
                if (tr.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                    bar.progress = holder.progress
                    pct.text = holder.progress.toString() + "%"
                    if (holder.progress != lastP) {
                        lastP = holder.progress
                        ExportService.progress(applicationContext, holder.progress)
                    }
                    if (holder.progress >= 3) {
                        val el = (System.currentTimeMillis() - startTs) / 1000.0
                        val done = totalMs() / 1000.0 * holder.progress / 100.0
                        if (el > 0.5) {
                            speedTv.text = "السرعة: " + String.format(Locale.US, "%.1f", done / el) + "x"
                        }
                    }
                }
                handler.postDelayed(this, 400)
            }
        }
        exportPoll = poll
        handler.post(poll)
    }
    private fun saveToMovies(src: File) {
        Thread {
            var ok = false
            var viewUri: Uri? = null
            var where = ""
            try {
                val name = "VideoTool_" + System.currentTimeMillis() + ".mp4"
                if (Build.VERSION.SDK_INT >= 29) {
                    val v = ContentValues().apply {
                        put(MediaStore.Video.Media.DISPLAY_NAME, name)
                        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                        put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/VideoTool")
                        put(MediaStore.Video.Media.IS_PENDING, 1)
                    }
                    val dest = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v)
                    if (dest != null) {
                        try {
                            val os = contentResolver.openOutputStream(dest)
                            if (os == null) throw java.io.IOException("no stream")
                            os.use { o -> src.inputStream().use { it.copyTo(o, 1 shl 20) } }
                            val done = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                            contentResolver.update(dest, done, null, null)
                            ok = true
                            viewUri = dest
                            where = "Movies/VideoTool"
                        } catch (_: Exception) {
                            try {
                                contentResolver.delete(dest, null, null)
                            } catch (_: Exception) {
                            }
                        }
                    }
                } else {
                    val dir = getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: filesDir
                    dir.mkdirs()
                    val f = File(dir, name)
                    try {
                        src.copyTo(f, true)
                        if (f.length() == src.length()) {
                            ok = true
                            where = f.absolutePath
                        } else {
                            f.delete()
                        }
                    } catch (_: Exception) {
                        f.delete()
                    }
                    if (ok) {
                        // نفهرسه عشان يظهر في المعرض ونجيب Uri صالح للفتح (file:// مش مسموح)
                        val box = arrayOfNulls<Uri>(1)
                        val latch = java.util.concurrent.CountDownLatch(1)
                        try {
                            MediaScannerConnection.scanFile(this, arrayOf(f.absolutePath), arrayOf("video/mp4")) { _, u ->
                                box[0] = u
                                latch.countDown()
                            }
                            latch.await(4, java.util.concurrent.TimeUnit.SECONDS)
                        } catch (_: Exception) {
                        }
                        viewUri = box[0]
                    }
                }
            } catch (_: Exception) {
                ok = false
            }
            // الملف المؤقت بيتمسح بس لو الحفظ نجح (عشان تصدير ساعتين مايضيعش لو المساحة خلصت)
            if (ok) src.delete()
            val success = ok
            val vu = viewUri
            val w = where
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (!success) {
                    darkDialog()
                        .setTitle("تعذّر حفظ الفيديو")
                        .setMessage("حصلت مشكلة وإحنا بنحفظ الملف النهائي (غالبًا المساحة مش كفاية). الفيديو لسه محفوظ مؤقتًا — فضّي مساحة وجرّب تاني.")
                        .setCancelable(false)
                        .setPositiveButton("جرّب تاني") { _, _ -> saveToMovies(src) }
                        .setNegativeButton("إغلاق") { _, _ -> src.delete() }
                        .show()
                } else {
                    showChapters("تم حفظ الفيديو في $w ✅\nدي فصول يوتيوب جاهزة، عدّل العناوين لو حابب:", vu)
                }
            }
        }.start()
    }
    // ---------- فصول يوتيوب ----------
    private fun chaptersText(): String {
        var t = 0L
        val sb = StringBuilder()
        for (i in clips.indices) {
            sb.append(fmtChap(t)).append(" السؤال ").append(i + 1).append('\n')
            t += clips[i].lenMs
        }
        return sb.toString().trim()
    }
    private fun showChapters(message: String, videoUri: Uri?) {
        if (clips.isEmpty()) {
            toast("ضيف فيديو الأول")
            return
        }
        val msg = TextView(this).apply {
            text = message
            textSize = 13f
            setTextColor(Pal.text2)
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
    // ---------- حفظ/استرجاع المشاريع ----------
    private fun clipsToJson(): org.json.JSONArray {
        val arr = org.json.JSONArray()
        for (c in clips) {
            val o = org.json.JSONObject()
            o.put("u", c.uri.toString())
            o.put("n", c.name)
            o.put("s", c.sizeBytes)
            o.put("d", c.srcDurMs)
            o.put("a", c.startMs)
            o.put("b", c.endMs)
            o.put("w", c.dw)
            o.put("h", c.dh)
            o.put("v", c.gain.toDouble())
            o.put("dn", c.denoise)
            o.put("t", c.transitionId)
            arr.put(o)
        }
        return arr
    }
    private fun clipsFromJson(arr: org.json.JSONArray): List<Clip> {
        val out = ArrayList<Clip>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(
                Clip(
                    Ids.next(),
                    Uri.parse(o.getString("u")),
                    o.getString("n"),
                    o.getLong("d"),
                    o.getLong("a"),
                    o.getLong("b"),
                    o.getLong("s"),
                    o.getDouble("v").toFloat(),
                    o.getBoolean("dn"),
                    o.getInt("t"),
                    o.getInt("w"),
                    o.getInt("h")
                )
            )
        }
        return out
    }
    /** حفظ تلقائي (بيرجّع آخر حالة لما تفتح التطبيق تاني) */
    private fun saveProject() {
        try {
            getSharedPreferences("proj", MODE_PRIVATE).edit()
                .putString("clips", clipsToJson().toString())
                .putInt("ratio", ratioIdx)
                .putBoolean("fill", fillMode)
                .putInt("q", qualityIdx)
                .putFloat("dns", SpectralDenoiser.strength)
                .putString("pid", currentProjectId)
                .putString("pname", currentProjectName)
                .apply()
        } catch (_: Exception) {
        }
    }
    private fun clearAutosave() {
        try {
            getSharedPreferences("proj", MODE_PRIVATE).edit().remove("clips").remove("pid").remove("pname").apply()
        } catch (_: Exception) {
        }
    }
    private fun loadProject() {
        try {
            val sp = getSharedPreferences("proj", MODE_PRIVATE)
            ratioIdx = sp.getInt("ratio", 0).coerceIn(0, Ratios.all.size - 1)
            fillMode = sp.getBoolean("fill", false)
            qualityIdx = sp.getInt("q", 2).coerceIn(0, Qualities.all.size - 1)
            SpectralDenoiser.strength = sp.getFloat("dns", 4f).coerceIn(2.5f, 7f)
            currentProjectId = sp.getString("pid", null)
            currentProjectName = sp.getString("pname", "") ?: ""
            val s = sp.getString("clips", null) ?: return
            clips.addAll(clipsFromJson(org.json.JSONArray(s)))
        } catch (_: Exception) {
            clips.clear()
        }
    }
    private fun defaultProjectName(): String =
        "مشروع " + java.text.SimpleDateFormat("dd-MM HH:mm", Locale.US).format(java.util.Date())
    private fun saveNamedProject(name: String): Boolean {
        val nm = if (name.trim().isEmpty()) defaultProjectName() else name.trim()
        val o = org.json.JSONObject()
        o.put("name", nm)
        o.put("modified", System.currentTimeMillis())
        o.put("ratio", ratioIdx)
        o.put("fill", fillMode)
        o.put("totalMs", totalMs())
        o.put("clips", clipsToJson())
        val id = Projects.save(this, currentProjectId, o)
        if (id == null) return false
        currentProjectId = id
        currentProjectName = nm
        return true
    }
    private fun showSaveDialog(onSaved: () -> Unit) {
        val et = EditText(this).apply {
            setText(if (currentProjectName.isNotEmpty()) currentProjectName else defaultProjectName())
            setTextColor(Color.WHITE)
            textSize = 15f
            setSingleLine()
            setSelectAllOnFocus(true)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), dp(4))
            addView(et, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        darkDialog()
            .setTitle("اسم المشروع")
            .setView(box)
            .setPositiveButton("حفظ") { _, _ ->
                if (saveNamedProject(et.text.toString())) {
                    toast("تم حفظ المشروع ✅")
                    onSaved()
                } else {
                    toast("تعذّر حفظ المشروع")
                }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }
    private fun quickSave() {
        if (clips.isEmpty()) {
            toast("مفيش حاجة تتحفظ")
            return
        }
        val id = currentProjectId
        if (id != null && currentProjectName.isNotEmpty()) {
            if (saveNamedProject(currentProjectName)) toast("تم حفظ \"" + currentProjectName + "\" ✅") else toast("تعذّر الحفظ")
        } else {
            showSaveDialog { }
        }
    }
    private fun confirmExit() {
        if (clips.isEmpty()) {
            skipAutosave = true
            clearAutosave()
            finish()
            return
        }
        darkDialog()
            .setTitle("الخروج من المشروع")
            .setMessage("تحب تحفظ المشروع قبل ما تخرج؟")
            .setPositiveButton("💾 حفظ وخروج") { _, _ -> showSaveDialog { finish() } }
            .setNegativeButton("خروج بدون حفظ") { _, _ ->
                skipAutosave = true
                clearAutosave()
                finish()
            }
            .setNeutralButton("إكمال التعديل", null)
            .show()
    }
    private fun newProject() {
        if (clips.isEmpty()) {
            currentProjectId = null
            currentProjectName = ""
            return
        }
        darkDialog()
            .setTitle("مشروع جديد؟")
            .setMessage("هيتشال كل اللي على التايم لاين. احفظ المشروع الحالي الأول لو عايزه.")
            .setPositiveButton("مشروع جديد") { _, _ ->
                pushUndo()
                clips.clear()
                sel = -1
                playheadMs = 0L
                currentProjectId = null
                currentProjectName = ""
                refreshAll()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }
    private fun openProject(id: String) {
        val o = Projects.load(this, id)
        if (o == null) {
            toast("تعذّر فتح المشروع")
            return
        }
        try {
            val list = clipsFromJson(o.getJSONArray("clips"))
            pushUndo()
            clips.clear()
            clips.addAll(list)
            ratioIdx = o.optInt("ratio", 0).coerceIn(0, Ratios.all.size - 1)
            fillMode = o.optBoolean("fill", false)
            currentProjectId = id
            currentProjectName = o.optString("name", "")
            sel = -1
            playheadMs = 0L
            hidePanel()
            refreshAll()
            validateClips()
            warmProbe()
            toast("تم فتح \"" + currentProjectName + "\"")
        } catch (_: Exception) {
            toast("ملف المشروع تالف")
        }
    }
    private fun showProjects() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val newBtn = gradBtn("➕  مشروع جديد", 14f).apply {
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setOnClickListener {
                hidePanel()
                newProject()
            }
        }
        val saveBtn = TextView(this).apply {
            text = "💾  حفظ المشروع الحالي"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(dp(12), dp(11), dp(12), dp(11))
            background = rounded(this@MainActivity, Color.parseColor("#2A3560"), 14)
            isClickable = true
            pressable()
            setOnClickListener {
                if (clips.isEmpty()) {
                    toast("مفيش حاجة تتحفظ")
                } else {
                    hidePanel()
                    showSaveDialog { }
                }
            }
        }
        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(newBtn, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginEnd = dp(6) })
            addView(saveBtn, LinearLayout.LayoutParams(0, WRAP, 1f))
        }
        box.addView(topRow, LinearLayout.LayoutParams(MATCH, WRAP))
        val items = Projects.list(this)
        if (items.isEmpty()) {
            box.addView(TextView(this).apply {
                text = "مفيش مشاريع محفوظة لسه. اضغط حفظ المشروع الحالي."
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(Pal.text2)
                setPadding(0, dp(18), 0, dp(10))
            }, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        for (info in items) {
            val t1 = TextView(this).apply {
                text = info.name
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            val dateStr = java.text.SimpleDateFormat("dd/MM HH:mm", Locale.US).format(java.util.Date(info.modified))
            val t2 = TextView(this).apply {
                text = info.clipCount.toString() + " مقطع  •  " + fmtTime(info.totalMs) + "  •  " + dateStr
                textSize = 11f
                setTextColor(Pal.text2)
            }
            val txt = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(t1)
                addView(t2)
            }
            val del = TextView(this).apply {
                text = "🗑"
                textSize = 20f
                setPadding(dp(12), dp(8), dp(6), dp(8))
                isClickable = true
                pressable()
                setOnClickListener {
                    darkDialog()
                        .setTitle("مسح المشروع؟")
                        .setMessage("\"" + info.name + "\" هيتمسح نهائي (الفيديوهات الأصلية مش هتتأثر).")
                        .setPositiveButton("مسح") { _, _ ->
                            Projects.delete(this@MainActivity, info.id)
                            if (info.id == currentProjectId) {
                                currentProjectId = null
                                currentProjectName = ""
                            }
                            showProjects()
                        }
                        .setNegativeButton("إلغاء", null)
                        .show()
                }
            }
            val rw = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(8), dp(6), dp(8))
                background = if (info.id == currentProjectId) {
                    rounded(this@MainActivity, Color.parseColor("#334F6BFF"), 14, Pal.cyan, 2)
                } else {
                    rounded(this@MainActivity, Color.parseColor("#1B2547"), 14)
                }
                isClickable = true
                pressable()
                setOnClickListener { openProject(info.id) }
                addView(txt, LinearLayout.LayoutParams(0, WRAP, 1f))
                addView(del, LinearLayout.LayoutParams(WRAP, WRAP))
            }
            box.addView(rw, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })
        }
        val sv = ScrollView(this)
        sv.addView(box)
        val h = min(dp(400), dp(120) + items.size * dp(70))
        val holder = FrameLayout(this)
        holder.addView(sv, FrameLayout.LayoutParams(MATCH, h))
        showPanel("مشاريعي (" + items.size + ")", holder)
    }
    /** لو فيديو أصلي اتمسح، بنشيل مقاطعه من المشروع بدل ما المعاينة تقف */
    private fun validateClips() {
        val uris = clips.map { it.uri }.distinct()
        if (uris.isEmpty()) return
        Thread {
            val bad = HashSet<Uri>()
            for (u in uris) {
                try {
                    val pfd = contentResolver.openFileDescriptor(u, "r")
                    if (pfd == null) bad.add(u) else pfd.close()
                } catch (_: java.io.FileNotFoundException) {
                    bad.add(u)
                } catch (_: Exception) {
                }
            }
            if (bad.isNotEmpty()) {
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    val n = clips.count { it.uri in bad }
                    clips.removeAll { it.uri in bad }
                    sel = -1
                    refreshAll()
                    toast("اتشال $n مقطع لأن الفيديو الأصلي اتمسح أو مش متاح")
                }
            }
        }.start()
    }
    private fun warmProbe() {
        val uris = clips.map { it.uri }.distinct()
        if (uris.isEmpty()) return
        Thread {
            for (u in uris) probeSrc(u)
        }.start()
    }
}
/** خدمة Foreground بسيطة: بتخلي النظام مايقتلش العملية أثناء تصدير طويل (التصدير نفسه شغال في الـ Activity). */
class ExportService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            val n = build(this, 0)
            if (Build.VERSION.SDK_INT >= 35) {
                startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
            } else if (Build.VERSION.SDK_INT >= 29) {
                startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(ID, n)
            }
        } catch (_: Exception) {
            stopSelf()
        }
        return START_NOT_STICKY
    }
    companion object {
        private const val CH = "export"
        private const val ID = 4711
        fun build(ctx: Context, pct: Int): Notification {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel(CH, "تصدير الفيديو", NotificationManager.IMPORTANCE_LOW))
            val li = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName) ?: Intent()
            val open = PendingIntent.getActivity(ctx, 0, li, PendingIntent.FLAG_IMMUTABLE)
            val b = Notification.Builder(ctx, CH)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("جاري تصدير الفيديو…")
                .setContentText(if (pct > 0) "$pct%" else "بدأ التصدير")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(open)
            if (pct > 0) b.setProgress(100, pct, false) else b.setProgress(100, 0, true)
            return b.build()
        }
        fun start(ctx: Context) {
            try {
                ctx.startForegroundService(Intent(ctx, ExportService::class.java))
            } catch (_: Exception) {
            }
        }
        fun stop(ctx: Context) {
            try {
                ctx.stopService(Intent(ctx, ExportService::class.java))
            } catch (_: Exception) {
            }
            try {
                (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(ID)
            } catch (_: Exception) {
            }
        }
        fun progress(ctx: Context, pct: Int) {
            try {
                val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(ID, build(ctx, pct))
            } catch (_: Exception) {
            }
        }
    }
}
