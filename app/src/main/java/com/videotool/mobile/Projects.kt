package com.videotool.mobile

import android.content.Context
import org.json.JSONObject
import java.io.File

/** تخزين المشاريع المحفوظة (ملف JSON لكل مشروع) جوه ذاكرة التطبيق. */
object Projects {
    class Info(val id: String, val name: String, val modified: Long, val clipCount: Int, val totalMs: Long)

    private fun dir(ctx: Context): File {
        val d = File(ctx.filesDir, "projects")
        d.mkdirs()
        return d
    }

    fun list(ctx: Context): List<Info> {
        val out = ArrayList<Info>()
        val files = dir(ctx).listFiles() ?: return out
        for (f in files) {
            if (!f.name.endsWith(".json")) continue
            try {
                val o = JSONObject(f.readText())
                out.add(
                    Info(
                        f.name.removeSuffix(".json"),
                        o.optString("name", f.name),
                        o.optLong("modified", f.lastModified()),
                        o.getJSONArray("clips").length(),
                        o.optLong("totalMs", 0L)
                    )
                )
            } catch (_: Exception) {
            }
        }
        out.sortByDescending { it.modified }
        return out
    }

    /** بيرجّع رقم المشروع (id) أو null لو الحفظ فشل */
    fun save(ctx: Context, id: String?, json: JSONObject): String? {
        return try {
            val pid = id ?: ("p" + System.currentTimeMillis())
            val f = File(dir(ctx), "$pid.json")
            val tmp = File(dir(ctx), "$pid.tmp")
            tmp.writeText(json.toString())
            if (f.exists()) f.delete()
            if (!tmp.renameTo(f)) {
                f.writeText(json.toString())
                tmp.delete()
            }
            pid
        } catch (_: Exception) {
            null
        }
    }

    fun load(ctx: Context, id: String): JSONObject? = try {
        JSONObject(File(dir(ctx), "$id.json").readText())
    } catch (_: Exception) {
        null
    }

    fun delete(ctx: Context, id: String) {
        try {
            File(dir(ctx), "$id.json").delete()
        } catch (_: Exception) {
        }
    }
}
