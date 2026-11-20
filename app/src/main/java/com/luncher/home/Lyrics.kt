package com.luncher.home

import android.os.Handler
import android.os.Looper
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

/**
 * Song lyrics for the Now Playing widget. Music apps don't share lyrics with launchers, so we
 * look the song up on LRCLIB (a free, open lyrics library) by title + artist and get time-synced
 * lines when available. Results are cached for the session.
 */
object Lyrics {
    class Line(val ms: Long, val text: String)
    class Song(val synced: List<Line>, val plain: List<String>)

    /** Settings › Widgets › Lyrics (player widget and island). */
    @Volatile var enabled = true
    /** Lyrics run this far ahead of the song, so you can read the next words before they're sung. */
    @Volatile var LEAD_MS = 900L
    private val exec = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val cache = HashMap<String, Song?>()
    private val pending = HashSet<String>()

    private fun key(title: String, artist: String) = "${title.lowercase().trim()}|${artist.lowercase().trim()}"

    /** Cached lyrics (null = none / not fetched yet). */
    fun cached(title: String, artist: String): Song? = cache[key(title, artist)]

    /** Look the song up (once); [done] runs on the main thread with the result. */
    fun fetch(title: String, artist: String, durationMs: Long, done: (Song?) -> Unit) {
        val k = key(title, artist)
        if (cache.containsKey(k)) { done(cache[k]); return }
        if (title.isBlank() || !pending.add(k)) return
        // "Katy Perry • Recommended for you" → "Katy Perry"
        val cleanArtist = artist.split(" • ", " · ", ",", " feat", " ft.", " & ").first().trim()
        val cleanTitle = title.replace(Regex("\\s*[(\\[].*?(remaster|live|version|edit|mix).*?[)\\]]", RegexOption.IGNORE_CASE), "").trim()
        exec.execute {
            val song = runCatching { get(cleanTitle, cleanArtist, durationMs) ?: search(cleanTitle, cleanArtist) }.getOrNull()
            main.post { cache[k] = song; pending.remove(k); done(song) }
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun http(url: String): String? {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 6000; readTimeout = 8000
            setRequestProperty("User-Agent", "AppleLauncher/1.0 (Android launcher)")
        }
        return try { if (c.responseCode == 200) c.inputStream.bufferedReader().readText() else null } finally { c.disconnect() }
    }

    private fun get(title: String, artist: String, durationMs: Long): Song? {
        var url = "https://lrclib.net/api/get?track_name=${enc(title)}&artist_name=${enc(artist)}"
        if (durationMs > 0) url += "&duration=${durationMs / 1000}"
        val body = http(url) ?: return null
        return parse(org.json.JSONObject(body))
    }

    private fun search(title: String, artist: String): Song? {
        val body = http("https://lrclib.net/api/search?track_name=${enc(title)}&artist_name=${enc(artist)}") ?: return null
        val arr = org.json.JSONArray(body)
        for (i in 0 until arr.length()) parse(arr.getJSONObject(i))?.let { return it }
        return null
    }

    private fun parse(o: org.json.JSONObject): Song? {
        val syncedText = o.optString("syncedLyrics", "").takeIf { it.isNotBlank() && it != "null" }
        val plainText = o.optString("plainLyrics", "").takeIf { it.isNotBlank() && it != "null" }
        val synced = ArrayList<Line>()
        syncedText?.lines()?.forEach { l ->
            val m = Regex("^\\[(\\d+):(\\d+(?:\\.\\d+)?)]\\s*(.*)$").find(l.trim()) ?: return@forEach
            val ms = (m.groupValues[1].toLong() * 60_000 + (m.groupValues[2].toDouble() * 1000).toLong())
            synced += Line(ms, m.groupValues[3])
        }
        val plain = plainText?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        if (synced.isEmpty() && plain.isEmpty()) return null
        return Song(synced, plain)
    }

    /** The line being sung at [posMs] (or the first plain line when there is no timing). */
    fun lineAt(song: Song, posMs: Long): String {
        if (song.synced.isNotEmpty()) {
            var cur = ""
            for (l in song.synced) { if (l.ms <= posMs) cur = l.text else break }
            return cur.ifBlank { "♪" }
        }
        return song.plain.firstOrNull() ?: ""
    }
}

/**
 * Three lyric lines like Spotify / ArchiveTune: the line before (soft and blurred), the line being
 * sung (bright, slides up when it changes) and the next line (blurred). Shows slightly early so
 * the words never feel late.
 */
class LyricsView(ctx: android.content.Context, private val big: Boolean = false) : android.widget.LinearLayout(ctx) {
    private val d = ctx.resources.displayMetrics.density
    private fun mk(size: Float, alpha: Float, blur: Float) = android.widget.TextView(context).apply {
        textSize = size; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
        this.alpha = alpha
        if (blur > 0f && android.os.Build.VERSION.SDK_INT >= 31)
            setRenderEffect(android.graphics.RenderEffect.createBlurEffect(blur * d, blur * d, android.graphics.Shader.TileMode.DECAL))
    }
    private val prev = mk(if (big) 13f else 11f, 0.35f, 2.2f)
    private val cur = mk(if (big) 17f else 13.5f, 1f, 0f).apply {
        maxLines = if (big) 3 else 2
        // In the Home Screen player the line always fits: long lines shrink a little instead of being cut.
        if (!big) setAutoSizeTextTypeUniformWithConfiguration(10, 14, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
    }
    private val next = mk(if (big) 13f else 11f, 0.45f, 1.6f)
    private var lastIdx = -2
    var color: Int = android.graphics.Color.WHITE
        set(v) { field = v; for (t in listOf(prev, cur, next)) t.setTextColor(v) }

    init {
        orientation = VERTICAL
        // Home Screen player (small): the current line (2 lines tall, fixed) + the next line, blurred.
        // Island (big): previous, current and next.
        if (big) addView(prev)
        addView(cur, LayoutParams(LayoutParams.MATCH_PARENT, if (big) LayoutParams.WRAP_CONTENT else (36 * d).toInt())
            .apply { topMargin = (if (big) 3 else 0).times(d).toInt(); bottomMargin = (2 * d).toInt() })
        next.maxLines = 1
        addView(next)
        color = android.graphics.Color.WHITE
    }

    fun clear(text: String = "") { prev.text = ""; cur.text = text; next.text = ""; lastIdx = -2 }

    /** Show the lines around [posMs]. */
    fun show(song: Lyrics.Song, posMs: Long) {
        if (song.synced.isEmpty()) {
            if (lastIdx != -1) { lastIdx = -1; prev.text = ""; cur.text = song.plain.firstOrNull() ?: ""; next.text = song.plain.getOrNull(1) ?: "" }
            return
        }
        val t = posMs + Lyrics.LEAD_MS   // a little ahead, so you can read what's coming
        var i = -1
        for ((k, l) in song.synced.withIndex()) { if (l.ms <= t) i = k else break }
        if (i == lastIdx) return
        lastIdx = i
        val p = song.synced.getOrNull(i - 1)?.text ?: ""
        val c = song.synced.getOrNull(i)?.text?.ifBlank { "♪" } ?: "♪"
        val n = song.synced.getOrNull(i + 1)?.text ?: ""
        prev.text = p; next.text = n
        if (cur.text.toString() == c) return
        cur.animate().cancel()
        cur.text = c
        cur.alpha = 0.3f; cur.translationY = 8 * d; cur.scaleX = 0.97f; cur.scaleY = 0.97f
        cur.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f).setDuration(240)
            .setInterpolator(android.view.animation.DecelerateInterpolator(2f)).start()
        next.translationY = 6 * d; next.animate().translationY(0f).setDuration(240).start()
    }
}

/** A soft, very blurred copy of the album art for player backgrounds (works on every Android version). */
fun blurredArt(src: android.graphics.Bitmap?): android.graphics.Bitmap? = src?.let {
    runCatching {
        val small = android.graphics.Bitmap.createScaledBitmap(it, 12, 12, true)
        val mid = android.graphics.Bitmap.createScaledBitmap(small, 48, 48, true)
        android.graphics.Bitmap.createScaledBitmap(mid, 160, 160, true)
    }.getOrNull()
}
