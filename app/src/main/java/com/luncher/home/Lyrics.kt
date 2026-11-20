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
