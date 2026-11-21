package com.luncher.home

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * All Father — the system. Every notification becomes a record in his Data Center (kept 30 days,
 * on this phone only). He sorts it ([AllFatherBrain]), and acts on what needs acting on: tasks,
 * reminders, pins, money, leads, flags. He doesn't talk; he shows his work in the Data Center.
 *
 * He only acts inside the launcher (tasks, reminders, pins, notes, his ledger). He never sends,
 * replies, pays or deletes anything outside it.
 */
object AllFather {
    class Rec(val id: Long, val time: Long, val pkg: String, val app: String, val who: String, val text: String,
              val cat: AllFatherBrain.Cat, val imp: Boolean, val score: Int, val why: String, var acted: Boolean)
    class Done(val time: Long, val kind: String, val text: String, val recId: Long)

    private const val KEEP_DAYS = 30
    private fun dir(ctx: Context) = File(ctx.filesDir, "allfather").apply { mkdirs() }
    private fun recFile(ctx: Context) = File(dir(ctx), "records.jsonl")
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("allfather", Context.MODE_PRIVATE)
    private var cache: MutableList<Rec>? = null
    /** Told (main thread) when new information arrives or he acted, so an open Data Center refreshes. */
    var onChange: (() -> Unit)? = null

    /** Should he act by himself on new information? (Otherwise only on what you drag to him.) */
    fun autoAct(ctx: Context) = prefs(ctx).getBoolean("auto", true)
    fun setAutoAct(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("auto", on).apply()

    // ------------------------------------------------------------------ records
    fun records(ctx: Context): List<Rec> {
        cache?.let { return it }
        val cutoff = System.currentTimeMillis() - KEEP_DAYS * 86_400_000L
        val out = ArrayList<Rec>()
        runCatching {
            recFile(ctx).takeIf { it.exists() }?.forEachLine { l ->
                runCatching { JSONObject(l).let { o ->
                    if (o.optLong("t") >= cutoff) out += Rec(o.optLong("id"), o.optLong("t"), o.optString("p"), o.optString("a"), o.optString("w"), o.optString("x"),
                        runCatching { AllFatherBrain.Cat.valueOf(o.optString("c")) }.getOrDefault(AllFatherBrain.Cat.OTHER), o.optBoolean("i"), o.optInt("s"), o.optString("y"), o.optBoolean("d"))
                } }
            }
        }
        out.sortByDescending { it.time }
        cache = out
        return out
    }
    private fun line(r: Rec) = JSONObject().put("id", r.id).put("t", r.time).put("p", r.pkg).put("a", r.app).put("w", r.who).put("x", r.text)
        .put("c", r.cat.name).put("i", r.imp).put("s", r.score).put("y", r.why).put("d", r.acted).toString()
    private fun rewrite(ctx: Context) { runCatching { recFile(ctx).writeText(records(ctx).asReversed().joinToString("") { line(it) + "\n" }) } }

    private val recent = ArrayDeque<String>()
    private var seq = 0

    /** A notification came in. */
    /** Is All Father collecting information at all? */
    fun enabled(ctx: Context) = Features.GILBERT && prefs(ctx).getBoolean("on", true)
    fun setEnabled(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("on", on).apply()

    fun collect(ctx: Context, pkg: String, app: String, who0: String, text0: String) {
        if (pkg == ctx.packageName || !enabled(ctx)) return
        val who = Gilbert.cleanTitle(who0).take(80); var text = text0.replace('\n', ' ').trim().take(400)
        if (who.isBlank() && text.isBlank()) return
        // Apps re-post the same notification often: keep it once.
        val key = "$pkg|$who|$text"
        if (key in recent) return
        recent.addLast(key); while (recent.size > 60) recent.removeFirst()
        val v = AllFatherBrain.classify(app, who, text)
        // Never keep login codes readable.
        if (v.cat == AllFatherBrain.Cat.SECURITY) text = text.replace(Regex("\\b\\d{4,8}\\b"), "••••")
        val r = Rec(System.currentTimeMillis() * 100 + (seq++ % 100), System.currentTimeMillis(), pkg, app, who, text, v.cat, v.important, v.score, v.why, false)
        val list = records(ctx).toMutableList(); list.add(0, r); cache = list
        runCatching { recFile(ctx).appendText(line(r) + "\n") }
        if (Math.random() < 0.03) rewrite(ctx)   // drop records older than 30 days now and then
        if (autoAct(ctx)) act(ctx, r, auto = true)
        onChange?.invoke()
    }

    // ------------------------------------------------------------------ acting
    fun done(ctx: Context): List<Done> = runCatching {
        val a = JSONArray(prefs(ctx).getString("done", "[]"))
        (0 until a.length()).map { a.getJSONObject(it).let { o -> Done(o.optLong("t"), o.optString("k"), o.optString("x"), o.optLong("r")) } }.reversed()
    }.getOrDefault(emptyList())
    private fun log(ctx: Context, kind: String, text: String, recId: Long) {
        val a = runCatching { JSONArray(prefs(ctx).getString("done", "[]")) }.getOrDefault(JSONArray())
        a.put(JSONObject().put("t", System.currentTimeMillis()).put("k", kind).put("x", text).put("r", recId))
        val keep = JSONArray(); for (i in maxOf(0, a.length() - 200) until a.length()) keep.put(a.get(i))
        prefs(ctx).edit().putString("done", keep.toString()).apply()
    }

    fun pins(ctx: Context): List<String> = list(ctx, "pins")
    fun leads(ctx: Context): List<String> = list(ctx, "leads")
    fun flags(ctx: Context): List<String> = list(ctx, "flags")
    private fun list(ctx: Context, k: String) = runCatching { JSONArray(prefs(ctx).getString(k, "[]")).let { a -> (0 until a.length()).map { a.getString(it) } } }.getOrDefault(emptyList()).reversed()
    private fun push(ctx: Context, k: String, v: String, max: Int = 30) {
        val l = list(ctx, k).reversed().filter { it != v } + v
        val a = JSONArray(); l.takeLast(max).forEach { a.put(it) }; prefs(ctx).edit().putString(k, a.toString()).apply()
    }
    fun unpin(ctx: Context, v: String) { val a = JSONArray(); list(ctx, "pins").reversed().filter { it != v }.forEach { a.put(it) }; prefs(ctx).edit().putString("pins", a.toString()).apply() }

    /** Money this week (in / out) from what he logged. */
    fun moneyWeek(ctx: Context): Pair<Double, Double> {
        val since = System.currentTimeMillis() - 7 * 86_400_000L
        var inn = 0.0; var out = 0.0
        runCatching { val a = JSONArray(prefs(ctx).getString("money", "[]"))
            for (i in 0 until a.length()) a.getJSONObject(i).let { if (it.optLong("t") >= since) { if (it.optDouble("v") >= 0) inn += it.optDouble("v") else out -= it.optDouble("v") } } }
        return inn to out
    }
    private fun money(ctx: Context, v: Double) {
        val a = runCatching { JSONArray(prefs(ctx).getString("money", "[]")) }.getOrDefault(JSONArray())
        a.put(JSONObject().put("t", System.currentTimeMillis()).put("v", v))
        prefs(ctx).edit().putString("money", a.toString()).apply()
    }

    /** Run his actions for one record (once). Returns what he did. */
    fun act(ctx: Context, r: Rec, auto: Boolean = false): List<AllFatherBrain.Act> {
        if (r.acted) return emptyList()
        val v = AllFatherBrain.Verdict(r.cat, r.imp, r.score, r.why)
        val acts = AllFatherBrain.actions(r.app, r.who, r.text, v)
        for (a in acts) {
            when (a.kind) {
                "TASK" -> {
                    val title = a.text + if (a.dueText.isNotBlank()) " — ${a.dueText}" else ""
                    if (GilbertTasks.open(ctx).none { it.title.equals(title, true) }) GilbertTasks.add(ctx, title)
                    // A real deadline also gets a reminder (an hour before when there's a time, 9 AM otherwise).
                    a.due?.let { w -> val at = GilbertPlanner.resolve(w) - (if (w.hour != null) 3_600_000L else 0L)
                        if (at > System.currentTimeMillis()) GilbertPlanner.remind(ctx, a.text, at) }
                }
                "REMINDER" -> a.due?.let { w -> val at = GilbertPlanner.resolve(w) - 30 * 60_000L
                    if (at > System.currentTimeMillis()) GilbertPlanner.remind(ctx, a.text, at) }
                "PIN" -> push(ctx, "pins", a.text)
                "LEAD" -> push(ctx, "leads", a.text, 50)
                "FLAG" -> push(ctx, "flags", a.text, 20)
                "MONEY_IN" -> a.amount?.let { money(ctx, it) }
                "MONEY_OUT" -> a.amount?.let { money(ctx, -it) }
                "SCHEDULE", "MUTE", "FILE", "REPLY" -> {}
            }
            if (a.kind != "FILE") log(ctx, a.kind, a.text + if (a.dueText.isNotBlank()) " — ${a.dueText}" else "", r.id)
        }
        r.acted = true
        rewrite(ctx)
        if (!auto) onChange?.invoke()
        return acts
    }

    /** What needs John, ranked. */
    fun priorities(ctx: Context, n: Int = 8): List<Rec> {
        val day = System.currentTimeMillis() - 2 * 86_400_000L
        val order = listOf(AllFatherBrain.Cat.SECURITY, AllFatherBrain.Cat.ANNOUNCEMENT, AllFatherBrain.Cat.CLIENT, AllFatherBrain.Cat.SCHOOL,
            AllFatherBrain.Cat.MONEY, AllFatherBrain.Cat.CALENDAR, AllFatherBrain.Cat.EMAIL, AllFatherBrain.Cat.MESSAGE)
        return records(ctx).filter { it.imp && it.time >= day }
            .sortedWith(compareByDescending<Rec> { it.score + (order.size - order.indexOf(it.cat).let { i -> if (i < 0) order.size else i }) * 3 }.thenByDescending { it.time })
            .distinctBy { it.who + it.text.take(30) }.take(n)
    }

    fun today(ctx: Context): List<Rec> {
        val c = java.util.Calendar.getInstance().apply { set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0) }
        return records(ctx).filter { it.time >= c.timeInMillis }
    }

    /** One paragraph: what happened today. */
    fun summary(ctx: Context): String {
        val t = today(ctx)
        if (t.isEmpty()) return "Nothing has come in today."
        val imp = t.count { it.imp }
        val byCat = t.groupBy { it.cat }.entries.sortedByDescending { it.value.size }.joinToString(" · ") { "${it.key.label} ${it.value.size}" }
        val (inn, _) = moneyWeek(ctx)
        return "${t.size} records today. $imp need you, ${t.size - imp} can wait. $byCat." +
            (if (inn > 0) " ₱${"%,.0f".format(inn)} came in this week." else "")
    }

    // ------------------------------------------------------------------ All Father mode
    /** When on, the island, tools tab, side island and sidebar step aside and live inside All Father. */
    fun modeOn(ctx: Context) = Features.GILBERT && prefs(ctx).getBoolean("mode", false)
    /** Classic build: if All Father mode had moved the island / tools / side bars away, put them back. */
    fun restoreClassic(ctx: Context) {
        val p = prefs(ctx); if (!p.getBoolean("mode", false)) return
        val s = Settings(ctx)
        s.island = p.getBoolean("was_island", true); s.islandTools = p.getBoolean("was_tools", s.islandTools)
        s.sideHud = p.getBoolean("was_side", true); s.sidebarOn = p.getBoolean("was_sidebar", true)
        p.edit().putBoolean("mode", false).apply()
    }
    fun setMode(ctx: Context, on: Boolean) {
        val s = Settings(ctx); val p = prefs(ctx)
        if (on && !modeOn(ctx)) {
            // Remember how they were, so turning the mode off brings them back exactly.
            p.edit().putBoolean("mode", true).putBoolean("was_island", s.island).putBoolean("was_tools", s.islandTools)
                .putBoolean("was_side", s.sideHud).putBoolean("was_sidebar", s.sidebarOn).apply()
            s.island = false; s.islandTools = false; s.sideHud = false; s.sidebarOn = false
        } else if (!on && modeOn(ctx)) {
            s.island = p.getBoolean("was_island", true); s.islandTools = p.getBoolean("was_tools", s.islandTools)
            s.sideHud = p.getBoolean("was_side", true); s.sidebarOn = p.getBoolean("was_sidebar", true)
            p.edit().putBoolean("mode", false).apply()
        }
    }

    fun forget(ctx: Context) { recFile(ctx).delete(); prefs(ctx).edit().clear().apply(); cache = null; onChange?.invoke() }
}
