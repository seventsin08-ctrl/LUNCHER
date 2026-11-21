package com.luncher.home

import android.animation.ValueAnimator
import android.app.Activity
import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.TextUtils
import android.view.DragEvent
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * All Father's Data Center (landscape): Information on the left, the Data Center in the middle
 * (Today · Board · Map · Stats), All Father on the right. Drag information anywhere: onto the
 * Board to lay it out, onto All Father to have him act on it.
 */
class AllFatherActivity : Activity() {
    companion object {
        val GOLD = 0xFFD8A640.toInt(); val INK = 0xFFEBE8E1.toInt(); val DIM = 0xFF8A8C92.toInt(); val LINE = 0xFF24262B.toInt()
        val RAISE = 0xFF111317.toInt(); val RED = 0xFFFF5A4F.toInt(); val GREEN = 0xFF6BE3A0.toInt()
        fun color(c: AllFatherBrain.Cat) = when (c) {
            AllFatherBrain.Cat.ANNOUNCEMENT -> 0xFFD8A640.toInt(); AllFatherBrain.Cat.CLIENT -> 0xFF44D6E8.toInt(); AllFatherBrain.Cat.SCHOOL -> 0xFF6BE3A0.toInt()
            AllFatherBrain.Cat.MESSAGE -> 0xFFA77BFF.toInt(); AllFatherBrain.Cat.EMAIL -> 0xFFFF6FA3.toInt(); AllFatherBrain.Cat.MONEY -> 0xFF4F8DFF.toInt()
            AllFatherBrain.Cat.CALENDAR -> 0xFF9FE870.toInt(); AllFatherBrain.Cat.SECURITY -> 0xFFFF5A4F.toInt(); AllFatherBrain.Cat.SOCIAL -> 0xFFC9A0FF.toInt()
            AllFatherBrain.Cat.PROMO -> 0xFF5B5D63.toInt(); AllFatherBrain.Cat.SYSTEM -> 0xFF8A8C92.toInt(); AllFatherBrain.Cat.OTHER -> 0xFF8A8C92.toInt()
        }
        /** Open the Data Center and have him take these records (dropped from the side island). */
        fun openIngest(ctx: Context, ids: LongArray) {
            runCatching { ctx.startActivity(android.content.Intent(ctx, AllFatherActivity::class.java).putExtra("ingest", ids)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)) }
        }
        fun open(ctx: Context) { runCatching { ctx.startActivity(android.content.Intent(ctx, AllFatherActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()
    private val mono: Typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
    private val monoB: Typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    private lateinit var railList: LinearLayout
    private lateinit var railHead: TextView
    private lateinit var filters: LinearLayout
    private lateinit var center: FrameLayout
    private lateinit var tabs: LinearLayout
    private lateinit var side: LinearLayout
    private lateinit var ring: RingView
    private lateinit var port: TextView
    private lateinit var veil: TextView
    private var filter: AllFatherBrain.Cat? = null
    private var tab = "TODAY"

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        CrashLog.install(this)
        window.statusBarColor = Color.BLACK; window.navigationBarColor = Color.BLACK
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(row, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        // ---- left: information
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(10), dp(8), 0); setBackgroundColor(0xFF07080A.toInt()) }
        railHead = mono(left, "", 10f, INK, bold = true)
        filters = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        left.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(filters) }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(6); bottomMargin = dp(6) })
        railList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        left.addView(ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(railList) }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        row.addView(left, LinearLayout.LayoutParams(0, MATCH_PARENT, 26f))
        row.addView(View(this).apply { setBackgroundColor(LINE) }, LinearLayout.LayoutParams(maxOf(1, dp(1)), MATCH_PARENT))

        // ---- centre: data center
        val mid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(8), dp(10), dp(4)) }
        top.addView(TextView(this).apply { text = "◁"; textSize = 16f; setTextColor(DIM); setPadding(dp(6), dp(2), dp(10), dp(2)); setOnClickListener { finish() } })
        tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        top.addView(tabs, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        top.addView(TextView(this).apply { text = java.text.SimpleDateFormat("EEE MMM d", java.util.Locale.getDefault()).format(java.util.Date()).uppercase(); textSize = 9f; typeface = mono; setTextColor(DIM) })
        mid.addView(top)
        mid.addView(View(this).apply { setBackgroundColor(LINE) }, LinearLayout.LayoutParams(MATCH_PARENT, maxOf(1, dp(1))))
        center = FrameLayout(this)
        mid.addView(center, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        row.addView(mid, LinearLayout.LayoutParams(0, MATCH_PARENT, 46f))
        row.addView(View(this).apply { setBackgroundColor(LINE) }, LinearLayout.LayoutParams(maxOf(1, dp(1)), MATCH_PARENT))

        // ---- right: All Father
        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(6), dp(14), 0); setBackgroundColor(0xFF080705.toInt()) }
        val rbox = FrameLayout(this)
        ring = RingView(this)
        rbox.addView(ring, FrameLayout.LayoutParams(dp(104), dp(104), Gravity.CENTER))
        port = TextView(this).apply {
            textSize = 9f; typeface = mono; setTextColor(0xFFCDBF95.toInt()); setPadding(dp(8), dp(6), dp(8), dp(6)); visibility = View.GONE
            background = GradientDrawable().apply { setColor(0xF4070604.toInt()); setStroke(maxOf(1, dp(1)), GOLD); cornerRadius = dp(3).toFloat() }
        }
        rbox.addView(port, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.CENTER))
        right.addView(rbox, LinearLayout.LayoutParams(MATCH_PARENT, dp(112)))
        right.addView(TextView(this).apply { text = "ALL FATHER"; textSize = 10f; typeface = monoB; letterSpacing = .3f; setTextColor(GOLD); gravity = Gravity.CENTER })
        side = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        right.addView(ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(side) }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        row.addView(right, LinearLayout.LayoutParams(0, MATCH_PARENT, 28f))
        // All Father takes dropped information.
        right.setOnDragListener { _, e -> dragToFather(e) }

        // ---- ingest veil
        veil = TextView(this).apply { setBackgroundColor(0xF2000000.toInt()); setTextColor(GOLD); typeface = mono; textSize = 11f; gravity = Gravity.CENTER; visibility = View.GONE; letterSpacing = .2f }
        root.addView(veil, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        setContentView(root)

        AllFather.onChange = { runOnUiThread { refresh() } }
        refresh()
        handleIngest(intent)
    }

    override fun onNewIntent(intent: android.content.Intent) { super.onNewIntent(intent); handleIngest(intent) }

    /** Records dropped on All Father from the side island: black screen, his verdicts, his actions. */
    private fun handleIngest(i: android.content.Intent?) {
        val ids = i?.getLongArrayExtra("ingest") ?: return
        i.removeExtra("ingest")
        val recs = AllFather.records(this).filter { it.id in ids.toSet() }
        if (recs.isEmpty()) return
        veil.visibility = View.VISIBLE; veil.alpha = 1f
        val out = ArrayList<String>()
        out += "ALL FATHER · INGESTING ${recs.size} RECORD${if (recs.size > 1) "S" else ""}"
        veil.text = out.joinToString("\n")
        veil.postDelayed({
            var n = 0
            for (r in recs) {
                r.acted = false
                val acts = AllFather.act(this, r).filter { it.kind != "FILE" }
                n += acts.size
                if (recs.size <= 4) { out += "> ${r.who.take(24)} · ${r.cat.label.uppercase()} · ${if (r.imp) "IMPORTANT" else "UNIMPORTANT"}"; acts.forEach { out += "  ✓ ${it.kind}  ${it.text.take(40)}" } }
            }
            val imp = recs.count { it.imp }
            out += "> ${recs.size} sorted · $imp need you · $n actions"
            veil.text = out.takeLast(14).joinToString("\n")
            if (imp > 0) SoundFX.play(this, "success")
            veil.postDelayed({ veil.animate().alpha(0f).setDuration(300).withEndAction { veil.visibility = View.GONE; tab = "TODAY"; refresh() }.start() }, 2200)
        }, 700)
    }

    override fun onDestroy() { AllFather.onChange = null; super.onDestroy() }
    override fun onResume() { super.onResume(); refresh() }

    // ------------------------------------------------------------------ helpers
    private fun mono(p: LinearLayout, t: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = size; typeface = if (bold) monoB else mono; setTextColor(color); letterSpacing = .08f
    }.also { p.addView(it) }
    private fun label(p: LinearLayout, t: String) = TextView(this).apply { text = t; textSize = 8.5f; typeface = monoB; letterSpacing = .16f; setTextColor(GOLD); setPadding(0, dp(10), 0, dp(4)) }.also { p.addView(it) }
    private fun hhmm(t: Long) = java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault()).format(java.util.Date(t))

    private fun card(r: AllFather.Rec, compact: Boolean = false): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(6), dp(8), dp(6))
        background = GradientDrawable().apply { cornerRadius = dp(6).toFloat(); setColor(RAISE); setStroke(maxOf(1, dp(1)), if (r.imp) 0xFF4A3C1D.toInt() else LINE) }
        addView(TextView(context).apply {
            text = android.text.SpannableString("● ${r.app.uppercase()} · ${hhmm(r.time)}${if (r.imp) " · !" else ""}").apply {
                setSpan(android.text.style.ForegroundColorSpan(color(r.cat)), 0, 1, 0) }
            textSize = 7.5f; typeface = mono; setTextColor(DIM); letterSpacing = .08f
        })
        addView(TextView(context).apply { text = r.who; textSize = 10.5f; setTextColor(INK); typeface = Typeface.DEFAULT_BOLD; maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        addView(TextView(context).apply { text = r.text; textSize = 9.5f; setTextColor(0xFFB9B8B3.toInt()); maxLines = if (compact) 2 else 3; ellipsize = TextUtils.TruncateAt.END })
        // Hold to pick it up as a data file.
        setOnLongClickListener { v ->
            val clip = ClipData("allfather", arrayOf(ClipDescription.MIMETYPE_TEXT_PLAIN), ClipData.Item(r.id.toString()))
            v.startDragAndDrop(clip, View.DragShadowBuilder(v), r.id, 0)
            v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            true
        }
    }

    // ------------------------------------------------------------------ refresh everything
    private fun refresh() {
        val recs = AllFather.records(this)
        railHead.text = "INFORMATION · ${recs.size} RECORDS"
        // filters
        filters.removeAllViews()
        val cats = listOf<AllFatherBrain.Cat?>(null) + AllFatherBrain.Cat.values().filter { c -> recs.any { it.cat == c } }
        for (c in cats) filters.addView(TextView(this).apply {
            text = c?.label?.uppercase() ?: "ALL"; textSize = 8f; typeface = mono; setPadding(dp(6), dp(3), dp(6), dp(3))
            val on = filter == c
            setTextColor(if (on) INK else DIM)
            background = GradientDrawable().apply { cornerRadius = dp(2).toFloat(); setStroke(maxOf(1, dp(1)), if (on) INK else 0xFF3D4047.toInt()) }
            setOnClickListener { filter = c; refresh() }
        }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = dp(4) })
        // rail
        railList.removeAllViews()
        val shown = recs.filter { filter == null || it.cat == filter }.take(150)
        if (shown.isEmpty()) railList.addView(TextView(this).apply { text = "Nothing yet. New notifications land here."; textSize = 10f; setTextColor(DIM); setPadding(0, dp(20), 0, 0) })
        shown.forEach { railList.addView(card(it, compact = true), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(5) }) }
        // tabs
        tabs.removeAllViews()
        for (t in listOf("TODAY", "BOARD", "MAP", "STATS")) tabs.addView(TextView(this).apply {
            text = t; textSize = 10f; typeface = monoB; letterSpacing = .18f; setPadding(dp(8), dp(4), dp(8), dp(6))
            setTextColor(if (t == tab) INK else DIM)
            if (t == tab) setCompoundDrawablesWithIntrinsicBounds(null, null, null, GradientDrawable().apply { setColor(GOLD); setSize(dp(20), dp(2)) })
            setOnClickListener { tab = t; refresh() }
        })
        center.removeAllViews()
        when (tab) {
            "TODAY" -> center.addView(todayView())
            "BOARD" -> center.addView(boardView())
            "MAP" -> center.addView(MapView(this, recs.filter { it.time > System.currentTimeMillis() - 2 * 86_400_000L }.take(40)))
            else -> center.addView(StatsView(this, recs))
        }
        sideView()
    }

    // ---- TODAY: counts, summary, pinned, timeline
    private fun todayView(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(16)) }
        val t = AllFather.today(this)
        val (inn, _) = AllFather.moneyWeek(this)
        val kp = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun kpi(v: String, k: String) = kp.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(6), dp(8), dp(6))
            background = GradientDrawable().apply { cornerRadius = dp(4).toFloat(); setStroke(maxOf(1, dp(1)), LINE) }
            addView(TextView(context).apply { text = v; textSize = 18f; setTextColor(INK); typeface = Typeface.DEFAULT_BOLD })
            addView(TextView(context).apply { text = k; textSize = 7.5f; typeface = mono; setTextColor(DIM); letterSpacing = .12f })
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
        kpi("${t.size}", "NEW TODAY"); kpi("${t.count { it.imp }}", "NEED YOU"); kpi("${GilbertTasks.open(this).size}", "OPEN TASKS"); kpi(if (inn > 0) "₱${"%,.0f".format(inn)}" else "₱0", "IN · 7 DAYS")
        col.addView(kp)
        label(col, "DAILY UPDATE")
        col.addView(TextView(this).apply { text = AllFather.summary(context); textSize = 11f; setTextColor(0xFFD4D1CA.toInt()); setLineSpacing(0f, 1.15f) })
        val pins = AllFather.pins(this)
        if (pins.isNotEmpty()) {
            label(col, "ANNOUNCEMENTS · PINNED")
            pins.take(5).forEach { p -> col.addView(TextView(this).apply {
                text = "📌  $p"; textSize = 10.5f; setTextColor(INK); setPadding(0, dp(2), 0, dp(2))
                setOnLongClickListener { AllFather.unpin(context, p); refresh(); true } }) }
        }
        label(col, "TIMELINE · TODAY")
        if (t.isEmpty()) col.addView(TextView(this).apply { text = "Nothing has come in yet today."; textSize = 10f; setTextColor(DIM) })
        t.sortedBy { it.time }.takeLast(60).forEach { r -> col.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(2), 0, dp(2))
            addView(TextView(context).apply { text = hhmm(r.time); textSize = 8.5f; typeface = mono; setTextColor(DIM) }, LinearLayout.LayoutParams(dp(54), WRAP_CONTENT))
            addView(View(context).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color(r.cat)) } }, LinearLayout.LayoutParams(dp(6), dp(6)).apply { topMargin = dp(4); marginEnd = dp(6) })
            addView(TextView(context).apply { text = "${r.who} — ${r.text}"; textSize = 10f; setTextColor(if (r.imp) INK else 0xFF9C9B97.toInt()); maxLines = 2; ellipsize = TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }) }
        return ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(col) }
    }

    // ---- BOARD: drag cards anywhere
    private fun boardPlaces(): MutableList<Triple<Long, Float, Float>> = runCatching {
        val a = JSONArray(getSharedPreferences("allfather", MODE_PRIVATE).getString("board", "[]"))
        (0 until a.length()).map { a.getJSONObject(it).let { o -> Triple(o.optLong("id"), o.optDouble("x").toFloat(), o.optDouble("y").toFloat()) } }.toMutableList()
    }.getOrDefault(mutableListOf())
    private fun saveBoard(l: List<Triple<Long, Float, Float>>) {
        val a = JSONArray(); l.takeLast(60).forEach { a.put(JSONObject().put("id", it.first).put("x", it.second.toDouble()).put("y", it.third.toDouble())) }
        getSharedPreferences("allfather", MODE_PRIVATE).edit().putString("board", a.toString()).apply()
    }
    private fun boardView(): View {
        val board = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val byId = AllFather.records(this).associateBy { it.id }
        val places = boardPlaces().filter { byId.containsKey(it.first) }
        if (places.isEmpty()) board.addView(TextView(this).apply {
            text = "Hold a card on the left and drag it here to lay it out.\nDrag it onto All Father to have him act on it."
            textSize = 10f; typeface = mono; setTextColor(0xFF5B5D63.toInt()); gravity = Gravity.CENTER }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        for ((id, x, y) in places) {
            val r = byId[id] ?: continue
            val c = card(r, compact = true)
            (c.background as GradientDrawable).setStroke(maxOf(1, dp(1)), color(r.cat))
            board.addView(c, FrameLayout.LayoutParams(dp(150), WRAP_CONTENT).apply { leftMargin = x.toInt(); topMargin = y.toInt() })
        }
        board.setOnDragListener { v, e ->
            when (e.action) {
                DragEvent.ACTION_DRAG_STARTED -> e.clipDescription?.label == "allfather"
                DragEvent.ACTION_DROP -> {
                    val id = (e.localState as? Long) ?: return@setOnDragListener false
                    val l = boardPlaces().filter { it.first != id }.toMutableList()
                    l += Triple(id, (e.x - dp(75)).coerceIn(0f, (v.width - dp(150)).toFloat().coerceAtLeast(0f)), (e.y - dp(24)).coerceIn(0f, (v.height - dp(60)).toFloat().coerceAtLeast(0f)))
                    saveBoard(l); v.post { refresh() }; true
                }
                else -> true
            }
        }
        return board
    }

    // ---- All Father side
    private fun sideView() {
        side.removeAllViews()
        label(side, "PRIORITIES")
        val pr = AllFather.priorities(this, 6)
        if (pr.isEmpty()) side.addView(TextView(this).apply { text = "Nothing needs you right now."; textSize = 10f; setTextColor(DIM) })
        pr.forEachIndexed { i, r -> side.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(2), 0, dp(2))
            addView(TextView(context).apply { text = "${i + 1}"; textSize = 9f; typeface = monoB; setTextColor(if (i < 2) RED else GOLD) }, LinearLayout.LayoutParams(dp(14), WRAP_CONTENT))
            addView(TextView(context).apply { text = "${r.who}: ${r.text}"; textSize = 10f; setTextColor(0xFFD9D6CF.toInt()); maxLines = 2; ellipsize = TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }) }
        label(side, "TASKS")
        val tasks = GilbertTasks.open(this)
        if (tasks.isEmpty()) side.addView(TextView(this).apply { text = "No open tasks."; textSize = 10f; setTextColor(DIM) })
        tasks.take(10).forEach { t -> side.addView(TextView(this).apply {
            text = "☐  ${t.title}"; textSize = 10f; setTextColor(0xFFD9D6CF.toInt()); setPadding(0, dp(2), 0, dp(2))
            setOnClickListener { GilbertTasks.complete(context, t.id); SoundFX.play(context, "success"); refresh() } }) }
        val rem = GilbertPlanner.reminders(this)
        if (rem.isNotEmpty()) { label(side, "REMINDERS"); rem.take(5).forEach { r -> side.addView(TextView(this).apply { text = "⏰  ${r.text} · ${GilbertPlanner.label(r.at)}"; textSize = 9.5f; setTextColor(0xFFCFCAB9.toInt()) }) } }
        val leads = AllFather.leads(this)
        if (leads.isNotEmpty()) { label(side, "LEADS"); leads.take(5).forEach { l -> side.addView(TextView(this).apply { text = "◆  $l"; textSize = 9.5f; setTextColor(0xFF44D6E8.toInt()) }) } }
        label(side, "ACTIONS TAKEN")
        val done = AllFather.done(this)
        if (done.isEmpty()) side.addView(TextView(this).apply { text = "Nothing yet. Drag information to me."; textSize = 9.5f; setTextColor(DIM) })
        done.take(10).forEach { d -> side.addView(TextView(this).apply {
            text = "✓ ${d.kind}  ${d.text}"; textSize = 9f; typeface = mono; setTextColor(0xFFCFCAB9.toInt()); setPadding(0, dp(1), 0, dp(1)) }) }
        label(side, "YOUR INFORMATION")
        val recs = AllFather.records(this)
        val (inn, out) = AllFather.moneyWeek(this)
        val top = recs.filter { it.cat in setOf(AllFatherBrain.Cat.MESSAGE, AllFatherBrain.Cat.SCHOOL, AllFatherBrain.Cat.CLIENT) }.groupBy { it.who }.maxByOrNull { it.value.size }?.key
        val me = listOfNotNull(GilbertNarrator.ownerName(this) ?: "You", "${recs.size} records · 30 days", "${recs.count { it.imp }} important",
            "${leads.size} leads", if (inn > 0 || out > 0) "₱${"%,.0f".format(inn)} in · ₱${"%,.0f".format(out)} out (7d)" else null, top?.let { "Most active: $it" },
            AllFather.flags(this).firstOrNull()?.let { "⚑ $it" })
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        me.forEach { m -> wrap.addView(TextView(this).apply { text = m; textSize = 9f; typeface = mono; setTextColor(0xFFCFCAB9.toInt()); setPadding(dp(6), dp(2), dp(6), dp(2))
            background = GradientDrawable().apply { cornerRadius = dp(2).toFloat(); setStroke(maxOf(1, dp(1)), 0xFF3D4047.toInt()) } },
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { bottomMargin = dp(3) }) }
        side.addView(wrap)
        label(side, "MODE")
        side.addView(TextView(this).apply {
            val on = AllFather.autoAct(context)
            text = if (on) "● ACTS BY HIMSELF ON NEW INFORMATION" else "○ ONLY ACTS ON WHAT YOU DRAG TO HIM"
            textSize = 8.5f; typeface = monoB; setTextColor(if (on) GREEN else DIM); setPadding(0, dp(2), 0, dp(14))
            setOnClickListener { AllFather.setAutoAct(context, !on); refresh() } })
    }

    // ------------------------------------------------------------------ dropping on All Father
    private fun dragToFather(e: DragEvent): Boolean {
        when (e.action) {
            DragEvent.ACTION_DRAG_STARTED -> return e.clipDescription?.label == "allfather"
            DragEvent.ACTION_DRAG_ENTERED -> {
                port.visibility = View.VISIBLE; ring.hot = true
                port.text = "> allfather.ingest(file)\n> classify → analyze → act\n> DROP DATA HERE_"
            }
            DragEvent.ACTION_DRAG_EXITED, DragEvent.ACTION_DRAG_ENDED -> { port.visibility = View.GONE; ring.hot = false }
            DragEvent.ACTION_DROP -> {
                port.visibility = View.GONE; ring.hot = false
                val id = (e.localState as? Long) ?: return false
                val r = AllFather.records(this).firstOrNull { it.id == id } ?: return false
                ingest(r)
                return true
            }
        }
        return true
    }

    private fun ingest(r: AllFather.Rec) {
        veil.visibility = View.VISIBLE; veil.alpha = 0f; veil.animate().alpha(1f).setDuration(200).start()
        val v = AllFatherBrain.Verdict(r.cat, r.imp, r.score, r.why)
        val lines = listOf("ALL FATHER · INGESTING", "> ${r.cat.label.uppercase()} · ${if (r.imp) "IMPORTANT" else "UNIMPORTANT"} (${r.score}) · ${r.why.uppercase()}", "> ACTING…")
        var i = 0
        val step = object : Runnable { override fun run() { veil.text = lines.take(i + 1).joinToString("\n"); if (++i < lines.size) veil.postDelayed(this, 300) } }
        veil.post(step)
        veil.postDelayed({
            r.acted = false
            val acts = AllFather.act(this, r)
            veil.text = (lines + acts.map { "✓ ${it.kind}  ${it.text}" }).joinToString("\n")
            veil.postDelayed({ veil.animate().alpha(0f).setDuration(250).withEndAction { veil.visibility = View.GONE; refresh() }.start() }, 900)
            if (v.important) SoundFX.play(this, "success")
        }, 1000)
    }

    // ------------------------------------------------------------------ views
    /** All Father: segmented ring with gold triangles, slowly turning. */
    class RingView(ctx: Context) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG); private val r = RectF(); private val path = Path()
        var hot = false; set(v) { field = v; invalidate() }
        private var t = 0f
        private val anim = ValueAnimator.ofFloat(0f, 1f).apply { duration = 1000; repeatCount = ValueAnimator.INFINITE; addUpdateListener { t += 0.016f; invalidate() } }
        override fun onAttachedToWindow() { super.onAttachedToWindow(); anim.start() }
        override fun onDetachedFromWindow() { anim.cancel(); super.onDetachedFromWindow() }
        override fun onDraw(c: Canvas) {
            val cx = width / 2f; val cy = height / 2f; val R = min(cx, cy) - 4f; val d = resources.displayMetrics.density
            c.save(); c.rotate(t * 9f, cx, cy)
            for (k in 0 until 4) {
                path.reset(); val a0 = k * 90f + 6f; val sw = 78f
                r.set(cx - R, cy - R, cx + R, cy + R); path.arcTo(r, a0, sw)
                r.set(cx - R + 7 * d, cy - R + 7 * d, cx + R - 7 * d, cy + R - 7 * d); path.arcTo(r, a0 + sw, -sw); path.close()
                p.style = Paint.Style.FILL; p.color = if (k % 2 == 0) 0xFF1A1B1E.toInt() else 0xFF26272B.toInt(); c.drawPath(path, p)
                p.style = Paint.Style.STROKE; p.strokeWidth = d * .6f; p.color = 0xFF3D4047.toInt(); c.drawPath(path, p)
            }
            c.restore()
            p.style = Paint.Style.STROKE; p.color = 0xFF3D4047.toInt(); p.strokeWidth = d * .6f
            p.pathEffect = android.graphics.DashPathEffect(floatArrayOf(d, 4 * d), t * 4); c.drawCircle(cx, cy, R * .74f, p); p.pathEffect = null
            p.color = 0xFF24262B.toInt(); c.drawCircle(cx, cy, R * .44f, p)
            p.style = Paint.Style.FILL; p.color = GOLD
            val tri = R * .09f; val dd = R * .58f
            for ((dx, dy) in listOf(0f to -1f, 1f to 0f, 0f to 1f, -1f to 0f)) {
                path.reset(); val x = cx + dx * dd; val y = cy + dy * dd
                if (dy != 0f) { path.moveTo(x - tri, y - dy * tri); path.lineTo(x + tri, y - dy * tri); path.lineTo(x, y + dy * tri) }
                else { path.moveTo(x - dx * tri, y - tri); path.lineTo(x - dx * tri, y + tri); path.lineTo(x + dx * tri, y) }
                path.close(); c.drawPath(path, p)
            }
            c.drawCircle(cx, cy, d * (2.5f + if (hot) 2f else 0f), p)
            if (hot) { p.style = Paint.Style.STROKE; p.strokeWidth = d * 2; p.color = (0x66 shl 24) or (GOLD and 0xFFFFFF); c.drawCircle(cx, cy, R + 2 * d, p) }
        }
    }

    /** Mind map: you in the middle, categories around, each record off its category. */
    class MapView(ctx: Context, private val recs: List<AllFather.Rec>) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG); private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE }
        override fun onDraw(c: Canvas) {
            val d = resources.displayMetrics.density; val cx = width / 2f; val cy = height / 2f
            val cats = recs.map { it.cat }.distinct()
            if (cats.isEmpty()) { tp.color = DIM; tp.textSize = 10 * d; tp.textAlign = Paint.Align.CENTER; c.drawText("The map grows as information comes in.", cx, cy, tp); return }
            cats.forEachIndexed { i, cat ->
                val a = (i.toFloat() / cats.size) * 6.283f - 1.571f
                val x = cx + cos(a) * width * .2f; val y = cy + sin(a) * height * .26f
                val its = recs.filter { it.cat == cat }.take(6)
                p.color = color(cat); p.strokeWidth = d; p.alpha = 170; p.style = Paint.Style.STROKE; c.drawLine(cx, cy, x, y, p)
                its.forEachIndexed { k, r ->
                    val b = a + (k - (its.size - 1) / 2f) * .3f
                    val lx = cx + cos(b) * width * .37f; val ly = cy + sin(b) * height * .42f
                    p.alpha = 120; p.strokeWidth = d * .6f; c.drawLine(x, y, lx, ly, p)
                    p.style = Paint.Style.FILL; p.color = if (r.imp) Color.WHITE else 0xFF55575D.toInt(); c.drawCircle(lx, ly, d * (if (r.imp) 3f else 2f), p); p.style = Paint.Style.STROKE; p.color = color(cat)
                    tp.color = if (r.imp) INK else DIM; tp.textSize = 7.5f * d; tp.textAlign = if (lx > cx) Paint.Align.LEFT else Paint.Align.RIGHT
                    c.drawText(r.who.take(16), lx + (if (lx > cx) 5 else -5) * d, ly + 3 * d, tp)
                }
                p.style = Paint.Style.FILL; p.color = Color.BLACK; p.alpha = 255; c.drawCircle(x, y, 9 * d, p)
                p.style = Paint.Style.STROKE; p.color = color(cat); c.drawCircle(x, y, 9 * d, p)
                tp.color = INK; tp.textSize = 8 * d; tp.textAlign = Paint.Align.CENTER; c.drawText(cat.label.uppercase(), x, y - 13 * d, tp)
            }
            p.style = Paint.Style.FILL; p.color = Color.BLACK; c.drawCircle(cx, cy, 18 * d, p)
            p.style = Paint.Style.STROKE; p.color = GOLD; p.strokeWidth = d; c.drawCircle(cx, cy, 18 * d, p)
            tp.color = INK; tp.textSize = 9 * d; tp.textAlign = Paint.Align.CENTER
            c.drawText((GilbertNarrator.ownerName(context) ?: "YOU").uppercase().take(8), cx, cy + 3 * d, tp)
        }
    }

    /** 16-week activity grid and today's categories. */
    class StatsView(ctx: Context, private val recs: List<AllFather.Rec>) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG); private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE }
        override fun onDraw(c: Canvas) {
            val d = resources.displayMetrics.density; val pad = 12 * d
            tp.color = GOLD; tp.textSize = 8.5f * d; c.drawText("ACTIVITY · LAST 16 WEEKS", pad, pad + 6 * d, tp)
            val day = 86_400_000L; val now = System.currentTimeMillis()
            val counts = IntArray(112); recs.forEach { val i = ((now - it.time) / day).toInt(); if (i in 0..111) counts[111 - i]++ }
            val mx = (counts.maxOrNull() ?: 1).coerceAtLeast(1)
            val cell = ((width - 2 * pad) / 16f).coerceAtMost(18 * d)
            for (i in 0 until 112) {
                val x = pad + (i / 7) * cell; val y = pad + 14 * d + (i % 7) * cell * .62f
                p.color = GOLD; p.alpha = (20 + 235 * counts[i] / mx).coerceIn(20, 255)
                c.drawRoundRect(x, y, x + cell - 3 * d, y + cell * .62f - 3 * d, 2 * d, 2 * d, p)
            }
            var y = pad + 14 * d + 7 * cell * .62f + 18 * d
            tp.color = GOLD; c.drawText("BY CATEGORY · 7 DAYS", pad, y, tp); y += 8 * d
            val week = recs.filter { it.time > now - 7 * day }.groupBy { it.cat }.entries.sortedByDescending { it.value.size }
            val m2 = (week.maxOfOrNull { it.value.size } ?: 1).coerceAtLeast(1)
            for ((cat, l) in week) {
                tp.color = DIM; tp.textSize = 8 * d; c.drawText(cat.label.uppercase(), pad, y + 8 * d, tp)
                val w = (width - pad * 2 - 110 * d) * l.size / m2
                p.color = color(cat); p.alpha = 220; c.drawRoundRect(pad + 92 * d, y + 1 * d, pad + 92 * d + w, y + 9 * d, 2 * d, 2 * d, p)
                tp.color = INK; c.drawText("${l.size} · ${l.count { it.imp }}!", pad + 96 * d + w, y + 8 * d, tp)
                y += 14 * d; if (y > height - 10 * d) break
            }
        }
    }
}
