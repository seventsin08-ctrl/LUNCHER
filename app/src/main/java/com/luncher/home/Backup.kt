package com.luncher.home

import android.content.Context
import android.net.Uri
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Export / import the whole launcher setup in one .zip file, like moving to a new iPhone:
 * Home Screen layout and pages, folders, dock(s), widgets and their settings, photos used in
 * widgets, the 3D wallpaper image, icon style, theme, island, Focus modes and every setting.
 */
object Backup {
    private const val PREFS = "luncher"
    /** Temporary state that shouldn't move to another phone. */
    private val skip = setOf("clip_stamp", "timer_end", "timer_total", "s_version", "asked_bt_permission",
        "hello_done", "hello_seen_version", "seen_launcher_version", "hello_force")

    class Result(val ok: Boolean, val message: String)

    fun export(ctx: Context, out: Uri): Result = runCatching {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val all = prefs.all
        val values = JSONObject()
        val media = JSONObject()
        var mediaCount = 0
        ctx.contentResolver.openOutputStream(out)!!.use { os ->
            ZipOutputStream(os.buffered()).use { zip ->
                for ((k, v) in all) {
                    if (k in skip || v == null) continue
                    val o = JSONObject()
                    when (v) {
                        is String -> o.put("t", "s").put("v", v)
                        is Int -> o.put("t", "i").put("v", v)
                        is Long -> o.put("t", "l").put("v", v)
                        is Float -> o.put("t", "f").put("v", v.toDouble())
                        is Boolean -> o.put("t", "b").put("v", v)
                        is Set<*> -> o.put("t", "set").put("v", JSONArray(v.map { it.toString() }))
                        else -> continue
                    }
                    values.put(k, o)
                    // Photos and pictures are links into your gallery; copy the actual image in.
                    if (v is String && (v.startsWith("content://") || v.startsWith("file://"))) {
                        val name = "media/" + k.replace(Regex("[^A-Za-z0-9_.-]"), "_")
                        val copied = runCatching {
                            ctx.contentResolver.openInputStream(Uri.parse(v))!!.use { input ->
                                zip.putNextEntry(ZipEntry(name))
                                input.copyTo(zip)
                                zip.closeEntry()
                            }
                        }.isSuccess
                        if (copied) { media.put(k, name); mediaCount++ }
                    }
                }
                val meta = JSONObject()
                    .put("app", "Apple Launcher")
                    .put("format", 1)
                    .put("created", System.currentTimeMillis())
                    .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
                zip.putNextEntry(ZipEntry("meta.json")); zip.write(meta.toString(2).toByteArray()); zip.closeEntry()
                zip.putNextEntry(ZipEntry("settings.json")); zip.write(values.toString().toByteArray()); zip.closeEntry()
                zip.putNextEntry(ZipEntry("media.json")); zip.write(media.toString().toByteArray()); zip.closeEntry()
            }
        }
        Result(true, "Exported ${values.length()} settings and $mediaCount photos")
    }.getOrElse { Result(false, "Export failed: ${it.message ?: it.javaClass.simpleName}") }

    fun import(ctx: Context, input: Uri): Result = runCatching {
        var settings: JSONObject? = null
        var media = JSONObject()
        val files = HashMap<String, ByteArray>()
        ctx.contentResolver.openInputStream(input)!!.use { ins ->
            ZipInputStream(ins.buffered()).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    val bytes = zip.readBytes()
                    when {
                        e.name == "settings.json" -> settings = JSONObject(String(bytes))
                        e.name == "media.json" -> media = JSONObject(String(bytes))
                        e.name.startsWith("media/") && !e.name.contains("..") -> files[e.name] = bytes
                    }
                }
            }
        }
        val values = settings ?: return Result(false, "This file isn't an Apple Launcher setup")
        // Put the photos into the launcher's own storage so they work on any phone.
        val dir = File(ctx.filesDir, "restored").apply { mkdirs() }
        val restoredMedia = HashMap<String, String>()
        for (k in media.keys()) {
            val name = media.getString(k)
            val bytes = files[name] ?: continue
            val f = File(dir, name.removePrefix("media/"))
            f.writeBytes(bytes)
            restoredMedia[k] = Uri.fromFile(f).toString()
        }
        val ed = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear()
        for (k in values.keys()) {
            if (k in skip) continue
            val o = values.getJSONObject(k)
            when (o.getString("t")) {
                "s" -> ed.putString(k, restoredMedia[k] ?: o.getString("v"))
                "i" -> ed.putInt(k, o.getInt("v"))
                "l" -> ed.putLong(k, o.getLong("v"))
                "f" -> ed.putFloat(k, o.getDouble("v").toFloat())
                "b" -> ed.putBoolean(k, o.getBoolean("v"))
                "set" -> {
                    val arr = o.getJSONArray("v")
                    ed.putStringSet(k, (0 until arr.length()).map { arr.getString(it) }.toSet())
                }
            }
        }
        ed.putInt("s_version", (System.currentTimeMillis() / 1000).toInt())
        ed.commit()
        Result(true, "Imported ${values.length()} settings and ${restoredMedia.size} photos")
    }.getOrElse { Result(false, "Import failed: ${it.message ?: it.javaClass.simpleName}") }
}
