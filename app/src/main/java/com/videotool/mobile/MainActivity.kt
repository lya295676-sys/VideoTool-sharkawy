package com.videotool.mobile
import android.app.Activity
import android.os.Bundle
import android.content.Intent
import android.net.Uri
import android.widget.*
import android.view.ViewGroup
import android.graphics.Color

class MainActivity : Activity() {
 private val videos = mutableListOf<Uri>()
 private lateinit var list: LinearLayout
 override fun onCreate(savedInstanceState: Bundle?) {
  super.onCreate(savedInstanceState)
  val page = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(28,36,28,24); setBackgroundColor(Color.rgb(17,24,39)) }
  page.addView(TextView(this).apply { text="VideoTool Mobile"; textSize=28f; setTextColor(Color.WHITE) })
  page.addView(TextView(this).apply { text="استوديو مونتاج فيديوهات حل الأسئلة"; textSize=16f; setTextColor(Color.WHITE); setPadding(0,12,0,12) })
  page.addView(Button(this).apply { text="إضافة الفيديوهات"; setOnClickListener {
   val i=Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type="video/*"; putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true); addCategory(Intent.CATEGORY_OPENABLE) }
   startActivityForResult(i,1001)
  } })
  page.addView(TextView(this).apply { text="المقاطع المضافة"; setTextColor(Color.WHITE); setPadding(0,16,0,8) })
  list=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
  page.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,0,1f))
  page.addView(TextView(this).apply { text="نسخة تأسيسية: اختيار الفيديوهات فقط. خصائص المونتاج ستضاف لاحقاً."; textSize=12f; setTextColor(Color.LTGRAY) })
  setContentView(page)
 }
 @Deprecated("Deprecated")
 override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) {
  super.onActivityResult(requestCode,resultCode,data)
  if(requestCode!=1001 || resultCode!=RESULT_OK || data==null) return
  if(data.clipData!=null) { val c=data.clipData!!; for(n in 0 until c.itemCount) add(c.getItemAt(n).uri) } else data.data?.let { add(it) }
 }
 private fun add(uri:Uri) {
  if(videos.contains(uri)) return
  videos.add(uri)
  try { contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_:Exception) {}
  list.addView(TextView(this).apply { text="${videos.size}. ${uri.lastPathSegment ?: "فيديو"}"; textSize=14f; setTextColor(Color.WHITE); setPadding(0,8,0,8) })
 }
}
