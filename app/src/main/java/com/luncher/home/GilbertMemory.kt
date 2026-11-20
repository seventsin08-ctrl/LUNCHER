package com.luncher.home

import android.content.Context
import org.json.JSONObject

/**
 * Gilbert's memory: small facts he learns from your day — songs you replay, apps you open,
 * who messages you most. It all stays on this phone (Settings › Gilbert › Memory shows it and
 * "Forget everything" wipes it). The brain and the rule-based chat both read from it.
 */
object GilbertMemory {
    private const val PREF = "gil_memory"

    private fun obj(ctx: Context, key: String): JSONObject =
        runCatching { JSONObject(ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(key, "{}")!!) }.getOrDefault(JSONObject())

    private fun save(ctx: Context, key: String, o: JSONObject) =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(key, o.toString()).apply()

    private fun bump(ctx: Context, key: String, name: String, by: Int = 1) {
        if (name.isBlank()) return
        val o = obj(ctx, key)
        o.put(name, o.optInt(name, 0) + by)
        // Keep it small: drop the least-used entries.
        if (o.length() > 120) {
            val keep = top(o, 80).map { it.first }.toSet()
            val keys = o.keys().asSequence().toList()
            for (k in keys) if (k !in keep) o.remove(k)
        }
        save(ctx, key, o)
    }

    private fun top(o: JSONObject, n: Int): List<Pair<String, Int>> =
        o.keys().asSequence().map { it to o.optInt(it) }.sortedByDescending { it.second }.take(n).toList()

    // ---- learning
    private var lastSong = ""
    fun song(ctx: Context, title: String, artist: String) {
        val k = "$title — $artist"
        if (title.isBlank() || k == lastSong) return
        lastSong = k
        bump(ctx, "songs", k); bump(ctx, "artists", artist)
    }

    private var lastApp = ""
    fun app(ctx: Context, label: String) {
        if (label.isBlank() || label == lastApp) return
        lastApp = label
        bump(ctx, "apps", label)
    }

    fun person(ctx: Context, name: String) {
        val n = name.trim()
        if (n.isEmpty() || n.length > 40) return
        bump(ctx, "people", n)
    }

    // ---- reading
    fun topSongs(ctx: Context, n: Int = 5) = top(obj(ctx, "songs"), n)
    fun topArtists(ctx: Context, n: Int = 5) = top(obj(ctx, "artists"), n).filter { it.first.isNotBlank() }
    fun topApps(ctx: Context, n: Int = 5) = top(obj(ctx, "apps"), n)
    fun topPeople(ctx: Context, n: Int = 5) = top(obj(ctx, "people"), n)

    fun forget(ctx: Context) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().clear().apply()
        lastSong = ""; lastApp = ""
    }

    /** A short, readable profile ("what Gilbert knows about you"). */
    fun profile(ctx: Context): String {
        val sb = StringBuilder()
        GilbertTalk.ownerName(ctx)?.let { sb.append("Name: $it\n") }
        topArtists(ctx, 5).takeIf { it.isNotEmpty() }?.let { sb.append("Favourite artists: ${it.joinToString { a -> a.first }}\n") }
        topSongs(ctx, 3).takeIf { it.isNotEmpty() }?.let { sb.append("Most played: ${it.joinToString { s -> "${s.first} (${s.second}×)" }}\n") }
        topApps(ctx, 5).takeIf { it.isNotEmpty() }?.let { sb.append("Most used apps: ${it.joinToString { a -> a.first }}\n") }
        topPeople(ctx, 5).takeIf { it.isNotEmpty() }?.let { sb.append("Talks most with: ${it.joinToString { p -> p.first }}\n") }
        return sb.toString().trim().ifEmpty { "I'm still getting to know you — give me a day or two 🙂" }
    }

    /** Real messages only — not downloads, system or progress notifications. */
    fun isMessage(n: Gilbert.Note): Boolean {
        val p = n.pkg.lowercase(); val t = n.title.lowercase()
        if (p == "android" || p.contains("systemui") || p.contains("download") || p.contains("providers") ||
            p.contains("vending") || p.contains("packageinstaller") || p.startsWith("com.vivo.") || p.startsWith("com.bbk.")) return false
        if (Regex("\\.(apk|task|pdf|zip|jpg|png|mp4|mp3|docx?)\\b").containsMatchIn(t)) return false
        return n.title.isNotBlank()
    }

    /** Professional daily briefing: messages, mentions, music, phone. */
    fun report(ctx: Context): String {
        val name = GilbertTalk.ownerName(ctx)
        val h = runCatching { Gilbert.health(ctx) }.getOrNull()
        val lines = ArrayList<String>()
        lines += (if (name != null) "Here's your briefing, $name 📋" else "Here's your briefing 📋")
        lines += "• Messages: " + recap(ctx)
        Gilbert.notes(ctx).filter { it.mention }.take(2).forEach { lines += "• Mention — ${it.title}: “${it.text.take(50)}”" }
        if (Gilbert.heldCount() > 0) lines += "• Waiting quietly: ${Gilbert.heldCount()} notification${if (Gilbert.heldCount() > 1) "s" else ""}"
        topSongs(ctx, 1).firstOrNull()?.let { lines += "• On repeat: ${it.first}" }
        h?.let { lines += "• Phone: battery ${it.battery}%${if (it.charging) " (charging)" else ""}, ${it.tempC?.toInt() ?: "?"}°C, ${"%.0f".format(it.storageFreeGb)} GB free" }
        lines += "Anything you'd like me to look into?"
        return lines.joinToString("\n")
    }

    /** Today's recap: what came in, who, mentions. */
    fun recap(ctx: Context): String {
        val notes = Gilbert.notes(ctx)
        val dayStart = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0)
        }.timeInMillis
        val today = notes.filter { it.time >= dayStart && isMessage(it) }
        if (today.isEmpty()) {
            val last = notes.firstOrNull() ?: return "Nothing came in today yet — all quiet ✨"
            return "Nothing new today. Last one was from ${last.title}: “${last.text.take(50)}”"
        }
        val who = today.groupingBy { it.title }.eachCount().entries.sortedByDescending { it.value }.take(4)
        val mentions = today.count { it.mention }
        val sb = StringBuilder("Today: ${today.size} message${if (today.size > 1) "s" else ""}")
        if (who.isNotEmpty()) sb.append(", mostly from ${who.joinToString { "${it.key} (${it.value})" }}")
        sb.append(".")
        if (mentions > 0) sb.append(" $mentions mentioned you 👀")
        return sb.toString()
    }
}
