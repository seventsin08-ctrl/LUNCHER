package com.luncher.home

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * Gilbert's mind: emotions, what he has learned about you, the decisions he makes and the
 * rules he lives by. Everything stays on this phone and can be wiped from his console.
 *
 * RULE 1 — He never decides or does anything while you're away: he only thinks while his
 *          console is open in front of you.
 * RULE 2 — He may suggest changing his own behaviour, but nothing changes without your "Allow".
 */
object GilbertMind {
    val RULES = listOf(
        "RULE 1 · I only think, suggest or act while you're here with me. Nothing happens in the background.",
        "RULE 2 · I can suggest changes to how I work, but I never change anything without your permission.",
        "RULE 3 · I never open, send or change anything unless you tap Allow — and I log every action.")

    private const val PREF = "gil_mind"
    private fun p(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
    private fun json(ctx: Context, key: String) = runCatching { JSONObject(p(ctx).getString(key, "{}")!!) }.getOrDefault(JSONObject())
    private fun put(ctx: Context, key: String, o: Any) = p(ctx).edit().putString(key, o.toString()).apply()

    // ------------------------------------------------------------------ emotions
    val EMOTIONS = listOf("calm", "curious", "happy", "concerned", "amused", "tired")
    private val emo = HashMap<String, Float>()
    private var emoLoaded = false

    private fun loadEmo(ctx: Context) {
        if (emoLoaded) return
        emoLoaded = true
        val o = json(ctx, "emo")
        for (e in EMOTIONS) emo[e] = o.optDouble(e, if (e == "calm") 0.6 else 0.15).toFloat()
        // Feelings fade while you're away (back towards calm).
        val away = (System.currentTimeMillis() - p(ctx).getLong("emoAt", 0L)) / 3_600_000f
        if (away > 0) for (e in EMOTIONS) { val base = if (e == "calm") 0.6f else 0.1f; emo[e] = base + (emo[e]!! - base) * Math.pow(0.6, away.toDouble()).toFloat() }
    }

    fun feel(ctx: Context, e: String, by: Float) {
        loadEmo(ctx)
        emo[e] = ((emo[e] ?: 0f) + by).coerceIn(0f, 1f)
        if (by > 0 && e != "calm") emo["calm"] = ((emo["calm"] ?: 0.5f) - by * 0.3f).coerceIn(0f, 1f)
        val o = JSONObject(); emo.forEach { (k, v) -> o.put(k, v.toDouble()) }
        put(ctx, "emo", o); p(ctx).edit().putLong("emoAt", System.currentTimeMillis()).apply()
    }

    /** His emotion colour (for his globe): calm = your theme colour. Null until his mind has loaded. */
    fun color(): Int? {
        if (!emoLoaded) return null
        return when (emo.maxByOrNull { it.value }?.key) {
            "curious" -> 0xFF3DFFB8.toInt(); "happy" -> 0xFFFFC94D.toInt(); "concerned" -> 0xFFFF6A3D.toInt()
            "amused" -> 0xFFD07CFF.toInt(); "tired" -> 0xFF4A6FA5.toInt(); else -> null
        }
    }

    fun emotions(ctx: Context): Map<String, Float> { loadEmo(ctx); return EMOTIONS.associateWith { emo[it] ?: 0f } }
    fun dominant(ctx: Context): String = emotions(ctx).maxByOrNull { it.value }?.key ?: "calm"

    /** The face that goes with how he feels. */
    fun faceMood(ctx: Context) = when (dominant(ctx)) {
        "curious" -> "curious"; "happy" -> "happy"; "concerned" -> "sad"; "amused" -> "excited"; "tired" -> "sleepy"; else -> "neutral"
    }

    /** React to what's in front of him (called when the console opens — Rule 1). */
    fun sense(ctx: Context, newMsgs: Int, high: Int, battery: Int, charging: Boolean) {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        if (newMsgs > 0) feel(ctx, "curious", minOf(0.4f, newMsgs * 0.03f))
        if (high > 0) feel(ctx, "concerned", minOf(0.5f, high * 0.15f))
        if (battery in 0..20 && !charging) feel(ctx, "concerned", 0.2f)
        if (h >= 23 || h < 5) feel(ctx, "tired", 0.25f)
        val away = System.currentTimeMillis() - p(ctx).getLong("lastOpen", 0L)
        if (away > 6 * 3_600_000L) feel(ctx, "happy", 0.25f)
        if (newMsgs == 0 && high == 0) feel(ctx, "calm", 0.2f)
    }

    // ------------------------------------------------------------------ learning you
    /** Called each time you open him. */
    fun recordOpen(ctx: Context) {
        val now = System.currentTimeMillis()
        val pr = p(ctx)
        if (pr.getLong("firstSeen", 0L) == 0L) pr.edit().putLong("firstSeen", now).apply()
        val hours = json(ctx, "hours"); val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY).toString()
        hours.put(h, hours.optInt(h) + 1); put(ctx, "hours", hours)
        val day = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date())
        val days = runCatching { JSONArray(pr.getString("days", "[]")) }.getOrDefault(JSONArray())
        if ((0 until days.length()).none { days.getString(it) == day }) { days.put(day); pr.edit().putString("days", days.toString()).apply() }
        pr.edit().putLong("lastOpen", now).putInt("opens", pr.getInt("opens", 0) + 1).apply()
    }

    private fun avg(ctx: Context, key: String, v: Float, k: Float = 0.2f) {
        val pr = p(ctx)
        val old = pr.getFloat(key, -1f)
        pr.edit().putFloat(key, if (old < 0) v else old + (v - old) * k).putInt("${key}N", pr.getInt("${key}N", 0) + 1).apply()
    }
    /** How fast you type in his console (characters per second). */
    fun recordTyping(ctx: Context, cps: Float) { if (cps in 0.3f..25f) avg(ctx, "typing", cps) }
    /** How much the phone moves while you use him (0 still … 1 lots). */
    fun recordMotion(ctx: Context, level: Float) = avg(ctx, "motion", level.coerceIn(0f, 1f), 0.1f)
    /** Time between your taps (impatient vs. deliberate). */
    fun recordTap(ctx: Context, gapMs: Long) { if (gapMs in 80..6000) avg(ctx, "tapGap", gapMs.toFloat()) }
    /** Which app you open at which hour (for "you usually open … around now"). */
    fun recordApp(ctx: Context, label: String) {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val o = json(ctx, "appHours"); val k = "$h|$label"
        o.put(k, o.optInt(k) + 1)
        if (o.length() > 400) { val keys = o.keys().asSequence().toList(); keys.take(100).forEach { o.remove(it) } }
        put(ctx, "appHours", o)
    }
    fun usualAppNow(ctx: Context): String? {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val o = json(ctx, "appHours")
        return o.keys().asSequence().filter { it.startsWith("$h|") }.map { it.substringAfter('|') to o.optInt(it) }
            .filter { it.second >= 3 }.maxByOrNull { it.second }?.first
    }

    fun daysKnown(ctx: Context) = runCatching { JSONArray(p(ctx).getString("days", "[]")).length() }.getOrDefault(0)
    /** 0 observing (days 1-2) · 1 learning (3-4) · 2 knows you (5+). */
    fun stage(ctx: Context) = when (daysKnown(ctx)) { in 0..2 -> 0; in 3..4 -> 1; else -> 2 }
    fun stageName(ctx: Context) = listOf("OBSERVING", "LEARNING", "KNOWS YOU")[stage(ctx)]

    /** What he has worked out about you (only what he can actually see). */
    fun traits(ctx: Context): List<Pair<String, String>> {
        val pr = p(ctx); val out = ArrayList<Pair<String, String>>()
        val hours = json(ctx, "hours")
        val hs = hours.keys().asSequence().map { it.toInt() to hours.optInt(it) }.toList()
        if (hs.sumOf { it.second } >= 4) {
            val late = hs.filter { it.first >= 22 || it.first < 4 }.sumOf { it.second }
            val early = hs.filter { it.first in 5..9 }.sumOf { it.second }
            val peak = hs.maxByOrNull { it.second }!!.first
            out += "Rhythm" to when { late > early * 2 -> "Night owl — most active late"; early > late * 2 -> "Early riser"; else -> "Steady through the day" } +
                " (peak around ${if (peak % 12 == 0) 12 else peak % 12}${if (peak < 12) "am" else "pm"})"
        }
        val ty = pr.getFloat("typing", -1f)
        if (ty > 0 && pr.getInt("typingN", 0) >= 3) out += "Typing" to when { ty > 6 -> "Fast and decisive (${"%.1f".format(ty)} chars/s)"; ty > 3 -> "Steady (${"%.1f".format(ty)} chars/s)"; else -> "Careful, takes time to think (${"%.1f".format(ty)} chars/s)" }
        val tg = pr.getFloat("tapGap", -1f)
        if (tg > 0 && pr.getInt("tapGapN", 0) >= 5) out += "Pace" to if (tg < 700) "Quick — moves fast between things" else if (tg < 1800) "Balanced" else "Deliberate — reads before moving on"
        val mo = pr.getFloat("motion", -1f)
        if (mo >= 0 && pr.getInt("motionN", 0) >= 3) out += "On the go" to when { mo > 0.45f -> "Often moving while using the phone"; mo > 0.15f -> "Sometimes on the move"; else -> "Usually still — at a desk or in bed" }
        GilbertMemory.topPeople(ctx, 2).takeIf { it.isNotEmpty() }?.let { out += "Circle" to "Talks most with ${it.joinToString(" and ") { p -> Gilbert.cleanTitle(p.first) }}" }
        GilbertMemory.topApps(ctx, 3).takeIf { it.isNotEmpty() }?.let { out += "Habits" to "Lives in ${it.joinToString(", ") { a -> a.first }}" }
        GilbertMemory.topArtists(ctx, 2).takeIf { it.isNotEmpty() }?.let { out += "Music" to it.joinToString(", ") { a -> a.first } }
        val dis = json(ctx, "dismissKinds")
        dis.keys().asSequence().maxByOrNull { dis.optInt(it) }?.takeIf { dis.optInt(it) >= 3 }?.let { out += "Not interested in" to it.lowercase() + " items" }
        return out
    }

    // ------------------------------------------------------------------ things you remove
    fun dismissed(ctx: Context, id: String) = json(ctx, "dismissed").has(id)
    fun dismiss(ctx: Context, id: String, kind: String, who: String?) {
        val d = json(ctx, "dismissed"); d.put(id, System.currentTimeMillis())
        if (d.length() > 300) d.keys().asSequence().toList().take(60).forEach { d.remove(it) }
        put(ctx, "dismissed", d)
        val k = json(ctx, "dismissKinds"); k.put(kind, k.optInt(kind) + 1); put(ctx, "dismissKinds", k)
        if (who != null) { val w = json(ctx, "dismissWho"); w.put(who, w.optInt(who) + 1); put(ctx, "dismissWho", w) }
    }
    /** How much he should care about this kind / person (you keep removing it → less). */
    fun weight(ctx: Context, kind: String, who: String?): Float {
        if (who != null && muted(ctx, who)) return 0f
        val k = json(ctx, "dismissKinds").optInt(kind); val w = if (who != null) json(ctx, "dismissWho").optInt(who) else 0
        return (1f / (1f + k * 0.15f + w * 0.5f))
    }
    /** People you've told him to stop reporting (Rule 2: only with your Allow). */
    fun muted(ctx: Context, who: String) = json(ctx, "mutedWho").has(who)
    fun mute(ctx: Context, who: String) { val o = json(ctx, "mutedWho"); o.put(who, true); put(ctx, "mutedWho", o) }
    fun dismissCount(ctx: Context, who: String) = json(ctx, "dismissWho").optInt(who)
    fun unmuteAll(ctx: Context) = put(ctx, "mutedWho", JSONObject())

    // ------------------------------------------------------------------ deciding to talk
    private var lastSpoke = 0L
    private val taps = ArrayList<Long>()

    /** Will he answer this tap? Returns (talk, reason when he doesn't). */
    fun decideToSpeak(ctx: Context, hasHigh: Boolean, hasNew: Boolean): Pair<Boolean, String> {
        val now = System.currentTimeMillis()
        taps.add(now); taps.removeAll { now - it > 20_000 }
        loadEmo(ctx)
        if (hasHigh && hasNew) return true to ""
        if (taps.size >= 4) { feel(ctx, "amused", 0.15f); if (Math.random() < 0.6) return false to "AMUSED · YOU KEEP TAPPING" }
        if (now - lastSpoke < 15_000 && !hasNew) return false to "NOTHING NEW TO ADD"
        if ((emo["tired"] ?: 0f) > 0.6f && Math.random() < 0.3) return false to "QUIET MOOD · LATE HOUR"
        if (!hasNew && Math.random() < 0.15) return false to "LISTENING · CHOSE SILENCE"
        return true to ""
    }
    fun spoke() { lastSpoke = System.currentTimeMillis() }

    // ------------------------------------------------------------------ permission log
    fun log(ctx: Context, what: String) {
        val a = runCatching { JSONArray(p(ctx).getString("log", "[]")) }.getOrDefault(JSONArray())
        a.put(JSONObject().put("t", System.currentTimeMillis()).put("w", what))
        val keep = JSONArray(); for (i in maxOf(0, a.length() - 40) until a.length()) keep.put(a.get(i))
        p(ctx).edit().putString("log", keep.toString()).apply()
    }
    fun logs(ctx: Context): List<Pair<Long, String>> = runCatching {
        val a = JSONArray(p(ctx).getString("log", "[]"))
        (0 until a.length()).map { a.getJSONObject(it).let { o -> o.optLong("t") to o.optString("w") } }.reversed()
    }.getOrDefault(emptyList())

    /** Wipe everything he has learned (emotions, habits, dismissals, log). */
    fun forget(ctx: Context) { p(ctx).edit().clear().apply(); emo.clear(); emoLoaded = false }
}
