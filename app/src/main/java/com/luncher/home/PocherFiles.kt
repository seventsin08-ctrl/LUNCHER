package com.luncher.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Pocher's Files program: photos and attachments that come in through chat notifications are
 * kept (in his archive folder, "Images"), and every photo is scanned on the phone — the text in
 * it (OCR) and what's in it (labels). You can also scan any image yourself.
 */
object PocherFiles {
    class Item(val id: String, val time: Long, val app: String, val who: String, val kind: String,
               val img: String?, val msg: String, val ocr: String, val labels: String)

    private val exec = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private const val MAX = 300
    /** Called (on the main thread) when a scan finishes, so an open Files window can refresh. */
    var onChanged: (() -> Unit)? = null

    fun dir(ctx: Context) = File(GilbertArchive.dir(ctx), "Images").apply { mkdirs() }
    private fun index(ctx: Context) = File(GilbertArchive.dir(ctx), "files.jsonl")

    fun list(ctx: Context): List<Item> = runCatching {
        index(ctx).takeIf { it.exists() }?.readLines()?.mapNotNull { l ->
            runCatching { JSONObject(l).let { o ->
                Item(o.optString("id"), o.optLong("t"), o.optString("app"), o.optString("who"), o.optString("kind"),
                    o.optString("img").ifBlank { null }, o.optString("msg"), o.optString("ocr"), o.optString("labels"))
            } }.getOrNull()
        }?.asReversed()
    }.getOrNull() ?: emptyList()

    private fun write(ctx: Context, items: List<Item>) {   // items newest first
        val keep = items.take(MAX)
        // Delete pictures that fell off the list.
        val used = keep.mapNotNull { it.img }.toSet()
        dir(ctx).listFiles()?.forEach { if (it.name !in used) it.delete() }
        index(ctx).writeText(keep.asReversed().joinToString("") { i ->
            JSONObject().put("id", i.id).put("t", i.time).put("app", i.app).put("who", i.who).put("kind", i.kind)
                .put("img", i.img ?: "").put("msg", i.msg).put("ocr", i.ocr).put("labels", i.labels).toString() + "\n"
        })
    }

    private fun label(ctx: Context, pkg: String) = runCatching {
        ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)

    /** A chat notification came in: keep any photos / attachments in it (once each). */
    fun capture(ctx: Context, pkg: String, chat: ChatInfo) {
        if (!Settings(ctx).gilbertArchive) return
        val app = ctx.applicationContext
        val title = Gilbert.cleanTitle(chat.title)
        val jobs = chat.messages.indices.mapNotNull { i ->
            val m = chat.media.getOrNull(i) ?: return@mapNotNull null
            val (who0, text) = chat.messages[i]
            val who = Gilbert.cleanTitle(who0.ifBlank { title }).let { if (it == "You") title else it }
            val key = "$pkg|$title|$who0|$text|${m.mime}|${m.image?.width}x${m.image?.height}".hashCode().toString(16)
            Triple(key, who, Pair(text, m))
        }
        if (jobs.isEmpty()) return
        exec.execute {
            val items = list(app).toMutableList()
            val seen = items.map { it.id }.toHashSet()
            val toScan = ArrayList<Pair<String, Bitmap>>()
            for ((key, who, tm) in jobs) {
                if (key in seen) continue
                seen += key
                val (text, m) = tm
                var file: String? = null
                m.image?.let { b ->
                    file = "$key.jpg"
                    runCatching { File(dir(app), file!!).outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 85, it) } }
                        .onFailure { file = null }
                    if (file != null) toScan += key to b
                }
                items.add(0, Item(key, System.currentTimeMillis(), label(app, pkg), who, m.mime, file, text, "", ""))
            }
            write(app, items)
            main.post { onChanged?.invoke() }
            for ((key, b) in toScan) main.post { scan(app, key, b) }
        }
    }

    /** Scan an image you picked yourself. */
    fun scanPicked(ctx: Context, uri: Uri, done: (Boolean) -> Unit) {
        val app = ctx.applicationContext
        exec.execute {
            val b = runCatching {
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                app.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
                var sample = 1
                while (maxOf(o.outWidth, o.outHeight) / sample > 1600) sample *= 2
                app.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
            }.getOrNull()
            if (b == null) { main.post { done(false) }; return@execute }
            val key = "scan" + System.currentTimeMillis().toString(16)
            val file = "$key.jpg"
            runCatching { File(dir(app), file).outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 88, it) } }
            val items = list(app).toMutableList()
            items.add(0, Item(key, System.currentTimeMillis(), "Scanner", "You", "image/*", file, "", "", ""))
            write(app, items)
            main.post { done(true); onChanged?.invoke(); scan(app, key, b) }
        }
    }

    /** Read the text in a picture and name what's in it (on the phone, via Google ML Kit). */
    private fun scan(ctx: Context, key: String, b: Bitmap) {
        val img = runCatching { InputImage.fromBitmap(b, 0) }.getOrNull() ?: return
        var ocr: String? = null; var labels: String? = null
        fun finish() {
            if (ocr == null || labels == null) return
            exec.execute {
                val items = list(ctx).map { i ->
                    if (i.id != key) i else Item(i.id, i.time, i.app, i.who, i.kind, i.img, i.msg,
                        ocr!!.ifBlank { "—" }, labels!!.ifBlank { "—" })
                }
                write(ctx, items)
                main.post { onChanged?.invoke() }
            }
        }
        runCatching {
            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).process(img)
                .addOnSuccessListener { ocr = it.text.trim(); finish() }
                .addOnFailureListener { ocr = ""; finish() }
        }.onFailure { ocr = "" }
        runCatching {
            ImageLabeling.getClient(ImageLabelerOptions.DEFAULT_OPTIONS).process(img)
                .addOnSuccessListener { l -> labels = l.filter { it.confidence > 0.6f }.take(5).joinToString(", ") { it.text }; finish() }
                .addOnFailureListener { labels = ""; finish() }
        }.onFailure { labels = "" }
        finish()
    }

    fun thumb(ctx: Context, item: Item, maxPx: Int): Bitmap? = item.img?.let { name ->
        runCatching {
            val f = File(dir(ctx), name)
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, o)
            var sample = 1
            while (maxOf(o.outWidth, o.outHeight) / sample > maxPx * 2) sample *= 2
            BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
        }.getOrNull()
    }

    fun clear(ctx: Context) { exec.execute { index(ctx).delete(); dir(ctx).listFiles()?.forEach { it.delete() }; main.post { onChanged?.invoke() } } }

    fun timeLabel(t: Long): String {
        val today = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        val d = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(t))
        return SimpleDateFormat(if (d == today) "HH:mm" else "MMM d, HH:mm", Locale.getDefault()).format(Date(t))
    }
}
