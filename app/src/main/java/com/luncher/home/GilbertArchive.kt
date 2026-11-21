package com.luncher.home

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Gilbert's archive: every notification and chat message is saved, one text file per day, in
 * "Android/data/<launcher>/files/Gilbert Archive/" (you can open it with a file manager).
 * Gilbert searches it to answer questions about anything that came in. Kept for 60 days.
 */
object GilbertArchive {
    private val day = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val time = SimpleDateFormat("HH:mm", Locale.US)
    private val labels = HashMap<String, String>()

    fun dir(ctx: Context): File =
        (ctx.getExternalFilesDir("Gilbert Archive") ?: File(ctx.filesDir, "Gilbert Archive")).apply { mkdirs() }

    private fun label(ctx: Context, pkg: String) = labels.getOrPut(pkg) {
        runCatching { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
    }

    /** Save one notification / message. */
    fun save(ctx: Context, pkg: String, title: String, text: String) {
        if (!Settings(ctx).gilbertArchive) return
        if (title.isBlank() && text.isBlank()) return
        val now = Date()
        // "HH:mm | App | Name<TAB>message" (the tab keeps names with ":" in them intact).
        val line = "${time.format(now)} | ${label(ctx, pkg)} | ${title.replace('\n', ' ').replace('\t', ' ').take(80)}\t${text.replace('\n', ' ').replace('\t', ' ').take(400)}\n"
        runCatching { File(dir(ctx), "${day.format(now)}.txt").appendText(line) }
        if (Math.random() < 0.02) prune(ctx)
    }

    /** Delete days older than 60. */
    fun prune(ctx: Context) {
        val cutoff = System.currentTimeMillis() - 60L * 86_400_000
        dir(ctx).listFiles()?.forEach { f -> runCatching { if ((day.parse(f.nameWithoutExtension)?.time ?: Long.MAX_VALUE) < cutoff) f.delete() } }
    }

    fun days(ctx: Context): List<File> = dir(ctx).listFiles()?.filter { it.extension == "txt" }?.sortedByDescending { it.name } ?: emptyList()
    fun count(ctx: Context): Int = days(ctx).sumOf { f -> runCatching { f.readLines().size }.getOrDefault(0) }

    /** Lines that mention any of the words (newest first). */
    fun search(ctx: Context, query: String, limit: Int = 6): List<String> {
        val words = query.lowercase().split(Regex("\\W+")).filter { it.length >= 3 && it !in STOP }
        if (words.isEmpty()) return emptyList()
        val out = ArrayList<Pair<Int, String>>()
        for (f in days(ctx).take(30)) {
            val lines = runCatching { f.readLines() }.getOrDefault(emptyList())
            for (l in lines.asReversed()) {
                val low = l.lowercase()
                val score = words.count { low.contains(it) }
                if (score > 0) out += score to "${f.nameWithoutExtension} $l"
            }
            if (out.size > 200) break
        }
        return out.sortedByDescending { it.first }.take(limit).map { it.second }
    }

    /** One archived message. */
    class Entry(val day: String, val time: String, val app: String, val who: String, val text: String) {
        val today get() = day == SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val stamp get() = if (today) time else "${day.substring(5)} $time"
    }

    private fun parse(day: String, line: String): Entry? {
        val p = line.split(" | ", limit = 3)
        if (p.size < 3) return null
        val rest = p[2]
        var who: String; var text: String
        if ('\t' in rest) { who = rest.substringBefore('\t'); text = rest.substringAfter('\t') }
        else {
            // Older lines: "Name: message" — and Instagram's "account: Name: message".
            who = rest.substringBefore(": "); text = rest.substringAfter(": ", "")
            if (Regex("^[\\w.]{2,30}$").matches(who) && (who.contains('.') || who.contains('_')) && text.contains(": ")) {
                who = text.substringBefore(": "); text = text.substringAfter(": ")
            }
        }
        who = Gilbert.cleanTitle(who)
        if (who.isBlank() && text.isBlank()) return null
        return Entry(day, p[0].trim(), p[1].trim(), who.trim(), text.trim())
    }

    /** Everything from the last [days] days, newest first. */
    fun entries(ctx: Context, days: Int = 2): List<Entry> {
        val out = ArrayList<Entry>()
        for (f in days(ctx).take(days)) {
            val lines = runCatching { f.readLines() }.getOrDefault(emptyList())
            for (l in lines.asReversed()) parse(f.nameWithoutExtension, l)?.let { out += it }
        }
        return out
    }

    fun clear(ctx: Context) { dir(ctx).listFiles()?.forEach { it.delete() } }

    private val STOP = setOf("the", "and", "what", "who", "did", "does", "about", "from", "with", "have", "that", "this", "you",
        "your", "say", "said", "tell", "any", "are", "was", "were", "when", "where", "how", "for", "can", "know", "message", "messages")
}
