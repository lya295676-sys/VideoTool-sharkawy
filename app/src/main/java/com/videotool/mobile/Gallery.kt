package com.videotool.mobile

import android.app.Activity
import android.app.Dialog
import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.Executors

class GalleryItem(
    val id: Long,
    val uri: Uri,
    val name: String,
    val durMs: Long,
    val size: Long,
    val w: Int,
    val h: Int
)

class SquareFrame(ctx: Context) : FrameLayout(ctx) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, widthMeasureSpec)
    }
}

/** معرض فيديوهات بتحديد مرقّم: نفس الفيديو ممكن يتضاف أكتر من مرة. */
class GalleryDialog(private val act: Activity, private val onDone: (List<GalleryItem>) -> Unit) {

    private class Cell(
        val root: SquareFrame,
        val img: ImageView,
        val dur: TextView,
        val badge: TextView,
        val frame: View
    )

    private val items = ArrayList<GalleryItem>()
    private val picked = ArrayList<GalleryItem>()
    private val cache = object : LruCache<Long, Bitmap>(90) {}
    private val pool = Executors.newFixedThreadPool(3)
    private var adapter: BaseAdapter? = null
    private var countTv: TextView? = null
    private var addBtn: TextView? = null
    private var emptyTv: TextView? = null

    private fun dp(v: Int) = act.dp(v)

    private fun makeCell(): Cell {
        val root = SquareFrame(act)
        val img = ImageView(act).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Color.parseColor("#1B2547"))
        }
        val frame = View(act).apply {
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                setStroke(dp(3), Pal.cyan)
            }
            visibility = View.GONE
        }
        val dur = TextView(act).apply {
            textSize = 11f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#99000000"))
            setPadding(dp(5), dp(1), dp(5), dp(1))
        }
        val badge = TextView(act).apply {
            textSize = 13f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(2), dp(8), dp(2))
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Pal.cyan, Pal.purple, Pal.magenta)
            ).apply { cornerRadius = dp(12).toFloat() }
            visibility = View.GONE
        }
        root.addView(img, FrameLayout.LayoutParams(-1, -1))
        root.addView(frame, FrameLayout.LayoutParams(-1, -1))
        root.addView(dur, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END))
        root.addView(badge, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
            setMargins(dp(5), dp(5), 0, 0)
        })
        val cell = Cell(root, img, dur, badge, frame)
        root.tag = cell
        return cell
    }

    @Suppress("DEPRECATION")
    private fun loadThumb(item: GalleryItem, iv: ImageView) {
        val cached = cache.get(item.id)
        if (cached != null) {
            iv.setImageBitmap(cached)
            return
        }
        iv.setImageDrawable(null)
        try {
            pool.execute {
                var b: Bitmap? = null
                try {
                    b = if (Build.VERSION.SDK_INT >= 29)
                        act.contentResolver.loadThumbnail(item.uri, Size(256, 256), null)
                    else
                        MediaStore.Video.Thumbnails.getThumbnail(
                            act.contentResolver, item.id, MediaStore.Video.Thumbnails.MINI_KIND, null
                        )
                } catch (_: Exception) {
                }
                val fb = b
                if (fb != null) {
                    cache.put(item.id, fb)
                    act.runOnUiThread { if (iv.tag == item.id) iv.setImageBitmap(fb) }
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun refresh() {
        adapter?.notifyDataSetChanged()
        countTv?.text = "المحدد: ${picked.size}"
        addBtn?.text = if (picked.isEmpty()) "اختر فيديوهات" else "إضافة (${picked.size})"
        addBtn?.alpha = if (picked.isEmpty()) 0.5f else 1f
    }

    fun show() {
        val d = Dialog(act, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val root = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.parseColor("#050816"), Color.parseColor("#0A1030"), Color.parseColor("#150B35"))
            )
        }
        val close = TextView(act).apply {
            text = "✕"
            textSize = 22f
            setTextColor(Color.WHITE)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            pressable()
            setOnClickListener { d.dismiss() }
        }
        val title = TextView(act).apply {
            text = "اختر الفيديوهات"
            textSize = 18f
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        val count = TextView(act).apply {
            textSize = 13f
            setTextColor(Pal.text2)
        }
        countTv = count
        val top = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(16), dp(6))
            addView(close, LinearLayout.LayoutParams(-2, -2))
            addView(title, LinearLayout.LayoutParams(0, -2, 1f))
            addView(count, LinearLayout.LayoutParams(-2, -2))
        }
        val hint = TextView(act).apply {
            text = "اضغط على الفيديو لإضافته • اضغط على نفس الفيديو تاني لإضافته مرة أخرى • اضغط مطولًا للتراجع"
            textSize = 12f
            setTextColor(Pal.text2)
            setPadding(dp(16), 0, dp(16), dp(8))
        }
        val grid = GridView(act).apply {
            numColumns = 3
            horizontalSpacing = dp(4)
            verticalSpacing = dp(4)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            selector = ColorDrawable(Color.TRANSPARENT)
            setPadding(dp(4), 0, dp(4), 0)
            clipToPadding = false
        }
        val ad = object : BaseAdapter() {
            override fun getCount(): Int = items.size
            override fun getItem(position: Int): Any = items[position]
            override fun getItemId(position: Int): Long = items[position].id
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val cell = (convertView?.tag as? Cell) ?: makeCell()
                val item = items[position]
                cell.img.tag = item.id
                loadThumb(item, cell.img)
                cell.dur.text = fmtTime(item.durMs)
                val occ = ArrayList<String>()
                for (k in picked.indices) if (picked[k] === item) occ.add((k + 1).toString())
                if (occ.isEmpty()) {
                    cell.badge.visibility = View.GONE
                    cell.frame.visibility = View.GONE
                } else {
                    cell.badge.text = occ.joinToString(" ، ")
                    cell.badge.visibility = View.VISIBLE
                    cell.frame.visibility = View.VISIBLE
                }
                return cell.root
            }
        }
        adapter = ad
        grid.adapter = ad
        grid.setOnItemClickListener { _, _, pos, _ ->
            picked.add(items[pos])
            refresh()
        }
        grid.setOnItemLongClickListener { _, _, pos, _ ->
            val idx = picked.lastIndexOf(items[pos])
            if (idx >= 0) picked.removeAt(idx)
            refresh()
            true
        }
        val empty = TextView(act).apply {
            text = "جاري تحميل الفيديوهات…"
            textSize = 14f
            setTextColor(Pal.text2)
            gravity = Gravity.CENTER
        }
        emptyTv = empty
        val area = FrameLayout(act).apply {
            addView(grid, FrameLayout.LayoutParams(-1, -1))
            addView(empty, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        }
        val undo = TextView(act).apply {
            text = "↶ تراجع"
            textSize = 14f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = rounded(act, Color.TRANSPARENT, 26, Pal.purple, 2)
            pressable()
            setOnClickListener {
                if (picked.isNotEmpty()) {
                    picked.removeAt(picked.size - 1)
                    refresh()
                }
            }
        }
        val add = TextView(act).apply {
            textSize = 16f
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Pal.cyan, Pal.blue, Pal.purple, Pal.magenta)
            ).apply { cornerRadius = dp(26).toFloat() }
            pressable()
            setOnClickListener {
                if (picked.isNotEmpty()) {
                    val result = ArrayList(picked)
                    d.dismiss()
                    onDone(result)
                }
            }
        }
        addBtn = add
        val bottom = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(8), dp(12), dp(10))
            addView(undo, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(10) })
            addView(add, LinearLayout.LayoutParams(0, -2, 1f))
        }
        root.addView(top, LinearLayout.LayoutParams(-1, -2))
        root.addView(hint, LinearLayout.LayoutParams(-1, -2))
        root.addView(area, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(bottom, LinearLayout.LayoutParams(-1, -2))
        d.setContentView(root)
        d.setOnDismissListener { pool.shutdownNow() }
        refresh()
        d.show()

        Thread {
            val list = ArrayList<GalleryItem>()
            try {
                val proj = arrayOf(
                    MediaStore.Video.Media._ID,
                    MediaStore.Video.Media.DISPLAY_NAME,
                    MediaStore.Video.Media.DURATION,
                    MediaStore.Video.Media.SIZE,
                    MediaStore.Video.Media.WIDTH,
                    MediaStore.Video.Media.HEIGHT
                )
                act.contentResolver.query(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI, proj, null, null,
                    MediaStore.Video.Media.DATE_ADDED + " DESC"
                )?.use { c ->
                    val iId = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                    val iName = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                    val iDur = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                    val iSize = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                    val iW = c.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH)
                    val iH = c.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT)
                    while (c.moveToNext()) {
                        val dur = c.getLong(iDur)
                        if (dur <= 0L) continue
                        val id = c.getLong(iId)
                        list.add(
                            GalleryItem(
                                id,
                                ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id),
                                c.getString(iName) ?: "video",
                                dur, c.getLong(iSize), c.getInt(iW), c.getInt(iH)
                            )
                        )
                    }
                }
            } catch (_: Exception) {
            }
            act.runOnUiThread {
                items.clear()
                items.addAll(list)
                empty.text = "مفيش فيديوهات على الجهاز"
                empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
                refresh()
            }
        }.start()
    }
}
