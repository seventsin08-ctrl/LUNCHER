package com.luncher.home

import android.animation.ValueAnimator
import android.app.Activity
import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.TextUtils
import android.view.DragEvent
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * All Father (landscape), laid out like a control platform:
 *  top bar  — features menu · logo · view switch · zoom · New analysis · search · you
 *  left     — the information orb → category cards → the chosen category fanned out on an arc
 *  right    — glass detail panel: what it is, importance, numbers, his analysis
 *  bottom   — “Ask a question…” (Gilbert answers / does it)
 * Colours follow your wallpaper / theme.
 */
class AllFatherActivity : Activity() {
    companion object {
        val ACC get() = Hud.CYAN
        val INK = 0xFFEDEFF0.toInt(); val DIM = 0xFF8C9396.toInt(); val LINE = 0x22FFFFFF
        val RED = 0xFFFF5A4F.toInt(); val GREEN = 0xFF6BE3A0.toInt(); val AMBER = 0xFFFFC53D.toInt()
        fun color(c: AllFatherBrain.Cat) = when (c) {
            AllFatherBrain.Cat.ANNOUNCEMENT -> 0xFFE7B653.toInt(); AllFatherBrain.Cat.CLIENT -> 0xFF44D6E8.toInt(); AllFatherBrain.Cat.SCHOOL -> 0xFF6BE3A0.toInt()
            AllFatherBrain.Cat.MESSAGE -> 0xFFA77BFF.toInt(); AllFatherBrain.Cat.EMAIL -> 0xFFFF6FA3.toInt(); AllFatherBrain.Cat.MONEY -> 0xFF4F8DFF.toInt()
            AllFatherBrain.Cat.CALENDAR -> 0xFF9FE870.toInt(); AllFatherBrain.Cat.SECURITY -> 0xFFFF5A4F.toInt(); AllFatherBrain.Cat.SOCIAL -> 0xFFC9A0FF.toInt()
            AllFatherBrain.Cat.PROMO -> 0xFF5B5D63.toInt(); AllFatherBrain.Cat.SYSTEM -> 0xFF8A8C92.toInt(); AllFatherBrain.Cat.OTHER -> 0xFF8A8C92.toInt()
        }
        fun glyph(c: AllFatherBrain.Cat) = when (c) {
            AllFatherBrain.Cat.ANNOUNCEMENT -> "📣"; AllFatherBrain.Cat.CLIENT -> "💼"; AllFatherBrain.Cat.SCHOOL -> "🎓"; AllFatherBrain.Cat.MESSAGE -> "💬"
            AllFatherBrain.Cat.EMAIL -> "✉"; AllFatherBrain.Cat.MONEY -> "₱"; AllFatherBrain.Cat.CALENDAR -> "📅"; AllFatherBrain.Cat.SECURITY -> "🔒"
            AllFatherBrain.Cat.SOCIAL -> "♥"; AllFatherBrain.Cat.PROMO -> "🏷"; AllFatherBrain.Cat.SYSTEM -> "⚙"; AllFatherBrain.Cat.OTHER -> "•"
        }
        fun open(ctx: Context) { runCatching { ctx.startActivity(android.content.Intent(ctx, AllFatherActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } }
        /** Open him and have him take these records (dropped from the side island). */
        fun openIngest(ctx: Context, ids: LongArray) {
            runCatching { ctx.startActivity(android.content.Intent(ctx, AllFatherActivity::class.java).putExtra("ingest", ids)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)) }
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()
    private val mono: Typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
    private val monoB: Typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    private val bold: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private lateinit var body: FrameLayout
    private lateinit var panel: LinearLayout
    private lateinit var panelScroll: ScrollView
    private lateinit var viewSwitch: LinearLayout
    private lateinit var answer: TextView
    private lateinit var veil: TextView
    private lateinit var drawer: LinearLayout
    private lateinit var drawerScroll: ScrollView
    private var graph: GraphView? = null
    private var mode = "graph"
    private var query = ""
    private var selected: AllFather.Rec? = null
    private var selCat: AllFatherBrain.Cat? = null

    private fun glass(radius: Int = 16, alpha: Int = 0x2A) = GradientDrawable().apply {
        cornerRadius = dp(radius).toFloat()
        colors = intArrayOf(Hud.a(IconFactory.blend(0xFF0B1A1C.toInt(), ACC, 0.25f), 0xE6), Hud.a(0xFF070B0C.toInt(), 0xEE))
        orientation = GradientDrawable.Orientation.TOP_BOTTOM
        setStroke(maxOf(1, dp(1)), Hud.a(ACC, (alpha + 0x20).coerceAtMost(255)))
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        CrashLog.install(this)
        window.statusBarColor = Color.BLACK; window.navigationBarColor = Color.BLACK
        val root = object : FrameLayout(this) {
            private val p = Paint()
            override fun dispatchDraw(c: Canvas) {
                // Deep background with a soft glow in your theme colour.
                p.shader = RadialGradient(width * .3f, height * .55f, width * .7f, intArrayOf(Hud.a(IconFactory.blend(0xFF0E2A2C.toInt(), ACC, .35f), 255), 0xFF030506.toInt()), null, Shader.TileMode.CLAMP)
                c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p)
                super.dispatchDraw(c)
            }
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(col, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        // ---------------- top bar
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(8), dp(14), dp(6)) }
        bar.addView(iconBtn("☰") { toggleDrawer() })
        bar.addView(RingView(this), LinearLayout.LayoutParams(dp(26), dp(26)).apply { marginStart = dp(10); marginEnd = dp(8) })
        bar.addView(LinearLayout(this).apply { orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply { text = "All Father"; textSize = 13f; setTextColor(INK); typeface = bold })
            addView(TextView(context).apply { text = "Information System"; textSize = 9f; setTextColor(DIM) }) })
        bar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        viewSwitch = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; background = glass(8); setPadding(dp(3), dp(3), dp(3), dp(3)) }
        bar.addView(viewSwitch)
        bar.addView(LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; background = glass(8); setPadding(dp(3), dp(3), dp(3), dp(3))
            addView(iconBtn("+", 30) { graph?.zoomBy(1.2f) }); addView(iconBtn("−", 30) { graph?.zoomBy(1 / 1.2f) }) },
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(8) })
        bar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        bar.addView(TextView(this).apply {
            text = "＋ New analysis"; textSize = 11f; setTextColor(INK); setPadding(dp(12), dp(7), dp(12), dp(7)); background = glass(8, 0x40)
            setOnClickListener { analyzeAll() } })
        bar.addView(iconBtn("⌕") { askSearch() }, LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginStart = dp(8) })
        bar.addView(TextView(this).apply {
            text = (GilbertNarrator.ownerName(context) ?: "J").take(1).uppercase(); textSize = 12f; setTextColor(Color.BLACK); gravity = Gravity.CENTER; typeface = bold
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(ACC) }
            setOnClickListener { selected = null; selCat = null; showYou() }
        }, LinearLayout.LayoutParams(dp(30), dp(30)).apply { marginStart = dp(8) })
        col.addView(bar)

        // ---------------- body: canvas (left) + panel (right)
        val main = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(10), 0, dp(14), dp(10)) }
        body = FrameLayout(this)
        main.addView(body, LinearLayout.LayoutParams(0, MATCH_PARENT, 64f))
        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(12)) }
        panelScroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(panel); background = glass(18) }
        right.addView(panelScroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        answer = TextView(this).apply { textSize = 10.5f; setTextColor(INK); setPadding(dp(12), dp(8), dp(12), dp(8)); background = glass(12); visibility = View.GONE; maxLines = 6; ellipsize = TextUtils.TruncateAt.END }
        right.addView(answer, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(6) })
        right.addView(askBar(), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(6) })
        main.addView(right, LinearLayout.LayoutParams(0, MATCH_PARENT, 36f))
        col.addView(main, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        // Drop information on the panel = give it to All Father.
        right.setOnDragListener { _, e -> dragToFather(e) }

        // ---------------- features drawer (everything the launcher has)
        drawer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(14)) }
        drawerScroll = ScrollView(this).apply { addView(drawer); background = glass(0, 0x40); visibility = View.GONE; isClickable = true }
        root.addView(drawerScroll, FrameLayout.LayoutParams(dp(270), MATCH_PARENT, Gravity.START))
        // ---------------- analysis veil
        veil = TextView(this).apply { setBackgroundColor(0xF2000000.toInt()); typeface = mono; textSize = 11f; gravity = Gravity.CENTER; visibility = View.GONE; letterSpacing = .12f }
        root.addView(veil, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        setContentView(root)

        AllFather.onChange = { runOnUiThread { refresh() } }
        refresh()
        handleIngest(intent)
    }

    override fun onDestroy() { AllFather.onChange = null; super.onDestroy() }
    override fun onResume() { super.onResume(); refresh() }
    override fun onNewIntent(intent: android.content.Intent) { super.onNewIntent(intent); handleIngest(intent) }
    @Deprecated("") override fun onBackPressed() { if (drawerScroll.visibility == View.VISIBLE) toggleDrawer() else super.onBackPressed() }

    // ------------------------------------------------------------------ small parts
    private fun iconBtn(t: String, size: Int = 34, fn: () -> Unit) = TextView(this).apply {
        text = t; textSize = 15f; setTextColor(INK); gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
        setOnClickListener { SoundFX.play(context, "tick"); fn() }
    }
    private fun label(p: LinearLayout, t: String, top: Int = 12) = TextView(this).apply {
        text = t; textSize = 9f; typeface = monoB; letterSpacing = .14f; setTextColor(Hud.SOFT); setPadding(0, dp(top), 0, dp(5)) }.also { p.addView(it) }
    private fun chip(t: String, c: Int) = TextView(this).apply {
        text = t; textSize = 9f; setTextColor(c); setPadding(dp(7), dp(2), dp(7), dp(3))
        background = GradientDrawable().apply { cornerRadius = dp(5).toFloat(); setColor((c and 0x00FFFFFF) or 0x22000000); setStroke(maxOf(1, dp(1)), (c and 0x00FFFFFF) or 0x77000000) } }
    private fun hhmm(t: Long) = java.text.SimpleDateFormat("MMM d, h:mm a", java.util.Locale.getDefault()).format(java.util.Date(t))
    private fun recs() = AllFather.records(this).filter { query.isBlank() || (it.who + " " + it.text + " " + it.app).contains(query, true) }

    private fun refresh() {
        viewSwitch.removeAllViews()
        for ((k, t) in listOf("graph" to "⌬  Graph", "today" to "☀  Today", "board" to "▦  Board", "stats" to "▮▮  Stats")) viewSwitch.addView(TextView(this).apply {
            text = t; textSize = 10.5f; setPadding(dp(10), dp(5), dp(10), dp(5)); setTextColor(if (mode == k) INK else DIM)
            if (mode == k) background = GradientDrawable().apply { cornerRadius = dp(6).toFloat(); setColor(Hud.a(ACC, 0x30)); setStroke(maxOf(1, dp(1)), Hud.a(ACC, 0x88)) }
            setOnClickListener { mode = k; refresh() } })
        body.removeAllViews(); graph = null
        val list = recs()
        when (mode) {
            "graph" -> {
                val g = GraphView(this, list.filter { it.time > System.currentTimeMillis() - 3 * 86_400_000L }.ifEmpty { list.take(60) }, selCat,
                    onCat = { c -> selCat = c; selected = null; showCategory(c) },
                    onItem = { r -> selected = r; showRecord(r) })
                graph = g; body.addView(g, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
                if (selCat == null) selCat = g.cat
            }
            "today" -> body.addView(todayView())
            "board" -> body.addView(boardView())
            else -> body.addView(StatsView(this, list))
        }
        val sel = selected; val sc = selCat
        when {
            sel != null -> showRecord(sel)
            sc != null && mode == "graph" -> showCategory(sc)
            else -> showYou()
        }
        buildDrawer()
    }

    // ------------------------------------------------------------------ right panel
    private fun panelHead(icon: String, kicker: String, title: String, sub: String?) {
        panel.removeAllViews()
        panel.addView(LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(TextView(context).apply { text = icon; textSize = 16f; gravity = Gravity.CENTER; setTextColor(ACC) }, LinearLayout.LayoutParams(dp(30), dp(30)))
            addView(TextView(context).apply { text = kicker; textSize = 11f; setTextColor(DIM) }) })
        panel.addView(TextView(this).apply { text = title; textSize = 17f; setTextColor(INK); typeface = bold; setPadding(0, dp(4), 0, 0); maxLines = 3; ellipsize = TextUtils.TruncateAt.END })
        sub?.let { panel.addView(TextView(this).apply { text = it; textSize = 12f; setTextColor(0xFFC9CDCF.toInt()); setPadding(0, dp(2), 0, 0); maxLines = 4; ellipsize = TextUtils.TruncateAt.END }) }
    }
    private fun statusBox(state: String, stateCol: Int, line: String, btn: String, fn: () -> Unit) {
        panel.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(10), dp(8), dp(8), dp(8)); background = glass(10)
            addView(LinearLayout(context).apply { orientation = LinearLayout.VERTICAL
                addView(chip(state, stateCol), LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
                addView(TextView(context).apply { text = line; textSize = 9.5f; setTextColor(DIM); setPadding(0, dp(4), 0, 0) }) }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(TextView(context).apply { text = "$btn  ›"; textSize = 10.5f; setTextColor(INK); setPadding(dp(10), dp(6), dp(10), dp(6))
                background = GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(0x22FFFFFF) }; setOnClickListener { fn() } })
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(10) })
    }
    private fun meter(title: String, pct: Int, delta: String, level: String, bars: List<Int>) {
        panel.addView(FrameLayout(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF2A0F12.toInt(), 0xFF120808.toInt())).apply { cornerRadius = dp(12).toFloat(); setStroke(maxOf(1, dp(1)), 0x33FF6A5A) }
            setPadding(dp(12), dp(10), dp(12), dp(10))
            addView(LinearLayout(context).apply { orientation = LinearLayout.VERTICAL
                addView(TextView(context).apply { text = title; textSize = 11f; setTextColor(0xFFF0B4A8.toInt()) })
                addView(LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM
                    addView(TextView(context).apply { text = "$pct%"; textSize = 26f; setTextColor(INK); typeface = bold })
                    addView(TextView(context).apply { text = "  $delta"; textSize = 10f; setTextColor(RED); setPadding(0, 0, 0, dp(5)) }) }) })
            addView(chip(level, AMBER), FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.END or Gravity.TOP))
            addView(Bars(context, bars), FrameLayout.LayoutParams(dp(120), dp(30), Gravity.END or Gravity.BOTTOM))
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(10) })
    }
    private fun tiles(vararg t: Triple<String, String, String>) {
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        t.toList().chunked(2).forEach { pair ->
            grid.addView(LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL
                pair.forEachIndexed { i, (icon, n, k) -> addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(8), dp(10), dp(8)); background = glass(10)
                    addView(LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                        addView(TextView(context).apply { text = icon; textSize = 13f; setTextColor(ACC) })
                        addView(TextView(context).apply { text = "  $n"; textSize = 20f; setTextColor(INK); typeface = bold; maxLines = 1 }) })
                    addView(TextView(context).apply { text = k; textSize = 9.5f; setTextColor(DIM); maxLines = 2 })
                }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { if (i == 0) marginEnd = dp(6) }) }
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(6) })
        }
        panel.addView(grid, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(4) })
    }
    private fun analysis(title: String, lines: List<String>, act: Pair<String, () -> Unit>? = null) {
        panel.addView(TextView(this).apply { text = "✦  AF Analysis"; textSize = 11f; setTextColor(INK); typeface = bold; setPadding(0, dp(12), 0, dp(6)) })
        panel.addView(LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(8), dp(10), dp(8)); background = glass(10)
            addView(TextView(context).apply { text = title; textSize = 11.5f; setTextColor(INK); typeface = bold })
            lines.forEach { l -> addView(TextView(context).apply { text = l; textSize = 10.5f; setTextColor(0xFFC9CDCF.toInt()); setPadding(0, dp(3), 0, 0) }) }
            act?.let { (t, fn) -> addView(TextView(context).apply { text = t; textSize = 10.5f; setTextColor(Color.BLACK); setPadding(dp(10), dp(5), dp(10), dp(5))
                background = GradientDrawable().apply { cornerRadius = dp(6).toFloat(); setColor(ACC) }; setOnClickListener { fn() } },
                LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(8) }) }
        })
    }

    private fun showCategory(c: AllFatherBrain.Cat) {
        val l = recs().filter { it.cat == c }
        val imp = l.count { it.imp }
        val avg = if (l.isEmpty()) 0 else l.sumOf { it.score } / l.size
        val old = l.filter { it.time < System.currentTimeMillis() - 86_400_000L }
        val prev = if (old.isEmpty()) avg else old.sumOf { it.score } / old.size
        panelHead(glyph(c), c.label, "${l.size} ${c.label.lowercase()} records", "$imp need you · ${l.size - imp} can wait")
        statusBox(if (imp > 0) "Active" else "Quiet", if (imp > 0) GREEN else DIM, "Latest: ${l.firstOrNull()?.let { hhmm(it.time) } ?: "—"}", "Explore") { mode = "today"; refresh() }
        meter("Importance", avg, "${if (avg >= prev) "+" else ""}${avg - prev}% vs older", if (avg >= 70) "High" else if (avg >= 40) "Moderate" else "Low", l.take(24).map { it.score }.reversed())
        val acted = AllFather.done(this).count { d -> l.any { it.id == d.recId } }
        tiles(Triple("⚑", "$imp", "Need you"), Triple("☰", "${l.size}", "Records"), Triple("✦", "$acted", "Actions taken"), Triple("👤", "${l.map { it.who }.distinct().size}", "People / sources"))
        val top = l.filter { it.imp }.take(3)
        analysis(if (top.isEmpty()) "Nothing here needs you." else "Focus on ${top.size}:", top.map { "• ${it.who}: ${it.text.take(60)}" },
            if (l.any { !it.acted && it.imp }) "Act on all" to { analyze(l.filter { !it.acted }) } else null)
    }

    private fun showRecord(r: AllFather.Rec) {
        panelHead(glyph(r.cat), "${r.cat.label} · ${r.app}", r.who, r.text)
        statusBox(if (r.imp) "Important" else "Can wait", if (r.imp) AMBER else DIM, "Listed on ${hhmm(r.time)}", "Open app") {
            packageManager.getLaunchIntentForPackage(r.pkg)?.let { runCatching { startActivity(it) } } }
        val same = recs().filter { it.who == r.who }
        meter("Importance", r.score, "${same.size} from ${r.who.take(14)}", if (r.score >= 70) "High" else if (r.score >= 40) "Moderate" else "Low", same.take(24).map { it.score }.reversed())
        val acts = AllFatherBrain.actions(r.app, r.who, r.text, AllFatherBrain.Verdict(r.cat, r.imp, r.score, r.why)).filter { it.kind != "FILE" }
        tiles(Triple("☰", "${same.size}", "From this source"), Triple("✦", "${acts.size}", "Actions"), Triple("⚑", "${r.score}", "Score"), Triple(glyph(r.cat), r.cat.label.take(9), "Category"))
        analysis("Why: ${r.why}", acts.map { "• ${it.kind.lowercase().replaceFirstChar { c -> c.uppercase() }}: ${it.text}${if (it.dueText.isNotEmpty()) " — ${it.dueText}" else ""}" }.ifEmpty { listOf("• File it. No action needed.") },
            if (r.acted) null else "Act now" to { analyze(listOf(r)) })
        panel.addView(TextView(this).apply { text = if (r.acted) "✓ Handled" else "Tip: hold an item in Today or Board and drag it onto this panel."; textSize = 9.5f; setTextColor(DIM); setPadding(0, dp(6), 0, 0) })
    }

    private fun showYou() {
        val all = recs(); val today = AllFather.today(this)
        val (inn, out) = AllFather.moneyWeek(this)
        panelHead("◉", "You", GilbertNarrator.ownerName(this) ?: "Your information", AllFather.summary(this))
        statusBox(if (AllFather.autoAct(this)) "Acting by himself" else "Waits for you", if (AllFather.autoAct(this)) GREEN else DIM,
            "${all.size} records · 30 days", "Switch") { AllFather.setAutoAct(this, !AllFather.autoAct(this)); refresh() }
        val hours = (0 until 24).map { h -> today.count { java.util.Calendar.getInstance().apply { timeInMillis = it.time }.get(java.util.Calendar.HOUR_OF_DAY) == h } }
        meter("Today needs you", if (today.isEmpty()) 0 else today.count { it.imp } * 100 / today.size, "${today.size} today", if (today.count { it.imp } > 5) "Busy" else "Calm", hours)
        tiles(Triple("☐", "${GilbertTasks.open(this).size}", "Open tasks"), Triple("⏰", "${GilbertPlanner.reminders(this).size}", "Reminders"),
            Triple("◆", "${AllFather.leads(this).size}", "Leads"), Triple("₱", "%,.0f".format(inn), "In · 7 days (₱${"%,.0f".format(out)} out)"))
        label(panel, "PRIORITIES")
        AllFather.priorities(this, 5).forEachIndexed { i, r -> panel.addView(TextView(this).apply {
            text = "${i + 1}  ${r.who}: ${r.text}"; textSize = 10.5f; setTextColor(if (i < 2) INK else 0xFFC9CDCF.toInt()); maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dp(2), 0, dp(2))
            setOnClickListener { selected = r; showRecord(r) } }) }
        label(panel, "TASKS")
        GilbertTasks.open(this).take(8).forEach { t -> panel.addView(TextView(this).apply { text = "☐  ${t.title}"; textSize = 10.5f; setTextColor(0xFFC9CDCF.toInt()); setPadding(0, dp(2), 0, dp(2))
            setOnClickListener { GilbertTasks.complete(context, t.id); SoundFX.play(context, "success"); showYou() } }) }
        label(panel, "ACTIONS TAKEN")
        AllFather.done(this).take(8).forEach { d -> panel.addView(TextView(this).apply { text = "✓ ${d.kind}  ${d.text}"; textSize = 9.5f; typeface = mono; setTextColor(0xFFC9CDCF.toInt()) }) }
    }

    // ------------------------------------------------------------------ ask (Gilbert)
    private fun askBar(): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(8), dp(8), dp(8)); background = glass(16, 0x50) }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val input = EditText(this).apply { hint = "Ask a question…"; setHintTextColor(DIM); setTextColor(INK); textSize = 12f; background = null; isSingleLine = true; imeOptions = EditorInfo.IME_ACTION_SEND; setPadding(dp(4), dp(4), dp(4), dp(4)) }
        row.addView(input, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        val send = TextView(this).apply { text = "➤"; textSize = 13f; setTextColor(Color.BLACK); gravity = Gravity.CENTER; background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(ACC) } }
        row.addView(send, LinearLayout.LayoutParams(dp(30), dp(30)))
        box.addView(row)
        box.addView(LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(6), 0, 0)
            addView(chip("◉  Gilbert · my agent", ACC).apply { setOnClickListener { GilbertLauncher.open(context) } })
            addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            addView(chip("✦  AF Analysis", INK).apply { setOnClickListener { analyzeAll() } }) })
        val go = { val q = input.text.toString().trim(); input.setText(""); if (q.isNotEmpty()) ask(q) }
        send.setOnClickListener { go() }
        input.setOnEditorActionListener { _, _, _ -> go(); true }
        return box
    }

    /** Gilbert answers here: commands run right away; questions about the data come from All Father; the rest go to Gilbert's brain. */
    private fun ask(q: String) {
        answer.visibility = View.VISIBLE
        (getSystemService(android.view.inputmethod.InputMethodManager::class.java))?.hideSoftInputFromWindow(answer.windowToken, 0)
        val low = q.lowercase()
        if (Regex("\\b(important|priorit|need me|summary|summari[sz]e|what happened|today|update)\\b").containsMatchIn(low) && !low.startsWith("open")) {
            answer.text = AllFather.summary(this) + "\n" + AllFather.priorities(this, 3).joinToString("\n") { "• ${it.who}: ${it.text.take(60)}" }
            GilbertNarrator.say(this, AllFather.summary(this)); return
        }
        val action = runCatching { GilbertControl.parse(this, q) }.getOrNull()
        if (action != null) {
            val r = runCatching { action.run(this) }.getOrElse { "That didn't work: ${it.message?.take(60)}" }
            GilbertMind.log(this, "${action.title} — you asked (All Father)")
            answer.text = "Gilbert · ${action.title}\n${r.ifEmpty { action.title }}"
            if (r.isNotEmpty()) GilbertNarrator.say(this, r.lines().first())
            refresh(); return
        }
        if (!GilbertBrain.installed(this)) { answer.text = "Gilbert: I need a brain for that question — add one in ☰ › Gilbert's brain."; return }
        answer.text = "Gilbert is thinking…"
        GilbertBrain.ask(this, emptyList(), q) { a -> answer.text = "Gilbert\n" + (a ?: GilbertBrain.lastError ?: "No answer this time."); a?.let { GilbertNarrator.say(this, GilbertBrain.spoken(it)) } }
    }

    private fun askSearch() {
        val e = EditText(this).apply { hint = "Search all information"; setText(query) }
        android.app.AlertDialog.Builder(this).setView(e).setPositiveButton("Search") { _, _ -> query = e.text.toString(); refresh() }
            .setNegativeButton("Clear") { _, _ -> query = ""; refresh() }.show()
    }

    // ------------------------------------------------------------------ features (everything the launcher has)
    private fun toggleDrawer() {
        val show = drawerScroll.visibility != View.VISIBLE
        drawerScroll.visibility = if (show) View.VISIBLE else View.GONE
        if (show) { drawerScroll.alpha = 0f; drawerScroll.translationX = -dp(40).toFloat(); drawerScroll.animate().alpha(1f).translationX(0f).setDuration(200).start() }
    }
    private fun buildDrawer() {
        drawer.removeAllViews()
        val s = Settings(this)
        drawer.addView(TextView(this).apply { text = "FEATURES"; textSize = 10f; typeface = monoB; letterSpacing = .2f; setTextColor(Hud.SOFT) })
        fun item(icon: String, t: String, sub: String, fn: () -> Unit) = drawer.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, dp(8))
            addView(TextView(context).apply { text = icon; textSize = 15f; setTextColor(ACC); gravity = Gravity.CENTER }, LinearLayout.LayoutParams(dp(30), WRAP_CONTENT))
            addView(LinearLayout(context).apply { orientation = LinearLayout.VERTICAL
                addView(TextView(context).apply { text = t; textSize = 12.5f; setTextColor(INK) })
                addView(TextView(context).apply { text = sub; textSize = 9.5f; setTextColor(DIM) }) })
            setOnClickListener { SoundFX.play(context, "tick"); fn() } })
        val on = AllFather.modeOn(this)
        item(if (on) "◉" else "○", "All Father mode: ${if (on) "ON" else "OFF"}", if (on) "Island, tools, side island and sidebar live in here" else "Move island, tools, side island and sidebar in here") {
            if (on) AllFather.setMode(this, false) else AllFather.setMode(this, true); buildDrawer()
            android.widget.Toast.makeText(this, if (!on) "All Father mode on — open him from the right edge of the Home Screen" else "All Father mode off — island, tools and side bars are back", android.widget.Toast.LENGTH_LONG).show() }
        drawer.addView(View(this).apply { setBackgroundColor(LINE) }, LinearLayout.LayoutParams(MATCH_PARENT, maxOf(1, dp(1))).apply { topMargin = dp(6); bottomMargin = dp(6) })
        item("◉", "Gilbert", "Your agent — console, voice, commands") { GilbertLauncher.open(this) }
        item("🎙", "Talk to Gilbert", "Opens listening, like double-tapping him") { GilbertLauncher.open(this, null, byVoice = true) }
        item("🧠", "Gilbert's brain", "Brain, calibration, voice, wake word") { GilbertConsole.startWin = 7; GilbertLauncher.open(this) }
        item("🗓", "Agenda", "Calendar, reminders, tasks, follow-ups") { GilbertConsole.startWin = 8; GilbertLauncher.open(this) }
        item("🖼", "Files & scans", "Photos and attachments from chats") { GilbertConsole.startWin = 6; GilbertLauncher.open(this) }
        item("🕘", "Message history", "Everything that came in, today") { mode = "today"; toggleDrawer(); refresh() }
        item("◖", "Island", if (s.island) "On the Home Screen" else "Moved in here (All Father mode)") { startActivity(android.content.Intent(this, SettingsActivity::class.java)) }
        item("🛠", "Tools", "Ask below: flashlight, volume, brightness, lock…") { toggleDrawer(); android.widget.Toast.makeText(this, "Type a tool: “flashlight on”, “volume up”, “brightness 40%”", android.widget.Toast.LENGTH_LONG).show() }
        item("▤", "Smart side island", if (s.sideHud) "On the screen edge" else "Its information is here") { mode = "today"; toggleDrawer(); refresh() }
        item("☰", "Sidebar apps", "Your pinned apps and settings") { startActivity(android.content.Intent(this, SettingsActivity::class.java)) }
        item("⚙", "Settings", "Every switch in the launcher") { startActivity(android.content.Intent(this, SettingsActivity::class.java)) }
        item("⟲", "Forget All Father's data", "Erase his 30 days of records") { AllFather.forget(this); refresh() }
    }

    // ------------------------------------------------------------------ TODAY / BOARD
    private fun todayView(): View {
        val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(6), dp(12), dp(16)) }
        val t = AllFather.today(this).filter { query.isBlank() || (it.who + it.text).contains(query, true) }
        c.addView(TextView(this).apply { text = "Daily update"; textSize = 16f; setTextColor(INK); typeface = bold })
        c.addView(TextView(this).apply { text = AllFather.summary(context); textSize = 11.5f; setTextColor(0xFFC9CDCF.toInt()); setPadding(0, dp(4), 0, dp(6)) })
        AllFather.pins(this).take(4).forEach { p -> c.addView(TextView(this).apply { text = "📌  $p"; textSize = 11f; setTextColor(INK); setPadding(0, dp(2), 0, dp(2)) }) }
        label(c, "TIMELINE · HOLD TO DRAG")
        if (t.isEmpty()) c.addView(TextView(this).apply { text = "Nothing yet today."; textSize = 11f; setTextColor(DIM) })
        t.forEach { r -> c.addView(recCard(r), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(5) }) }
        return ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(c) }
    }
    private fun recCard(r: AllFather.Rec): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(7), dp(10), dp(7)); background = glass(10, if (r.imp) 0x40 else 0x10)
        addView(TextView(context).apply { text = "${glyph(r.cat)}  ${r.app} · ${hhmm(r.time)}${if (r.imp) " · important" else ""}"; textSize = 9f; setTextColor(color(r.cat)) })
        addView(TextView(context).apply { text = r.who; textSize = 12f; setTextColor(INK); typeface = bold; maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        addView(TextView(context).apply { text = r.text; textSize = 11f; setTextColor(0xFFC9CDCF.toInt()); maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
        setOnClickListener { selected = r; showRecord(r) }
        setOnLongClickListener { v -> v.startDragAndDrop(ClipData("allfather", arrayOf(ClipDescription.MIMETYPE_TEXT_PLAIN), ClipData.Item(r.id.toString())), View.DragShadowBuilder(v), r.id, 0)
            v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS); true }
    }
    private fun boardPlaces(): MutableList<Triple<Long, Float, Float>> = runCatching {
        val a = JSONArray(getSharedPreferences("allfather", MODE_PRIVATE).getString("board", "[]"))
        (0 until a.length()).map { a.getJSONObject(it).let { o -> Triple(o.optLong("id"), o.optDouble("x").toFloat(), o.optDouble("y").toFloat()) } }.toMutableList()
    }.getOrDefault(mutableListOf())
    private fun saveBoard(l: List<Triple<Long, Float, Float>>) {
        val a = JSONArray(); l.takeLast(60).forEach { a.put(JSONObject().put("id", it.first).put("x", it.second.toDouble()).put("y", it.third.toDouble())) }
        getSharedPreferences("allfather", MODE_PRIVATE).edit().putString("board", a.toString()).apply()
    }
    private fun boardView(): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, dp(6), dp(10)) }
        recs().take(80).forEach { list.addView(recCard(it), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(5) }) }
        row.addView(ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(list) }, LinearLayout.LayoutParams(0, MATCH_PARENT, 34f))
        val board = FrameLayout(this).apply { background = glass(14, 0x10) }
        val byId = AllFather.records(this).associateBy { it.id }
        val places = boardPlaces().filter { byId.containsKey(it.first) }
        if (places.isEmpty()) board.addView(TextView(this).apply { text = "Hold information on the left and drag it here.\nDrag it onto the panel on the right to give it to All Father."
            textSize = 11f; setTextColor(DIM); gravity = Gravity.CENTER }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        for ((id, x, y) in places) { val r = byId[id] ?: continue
            board.addView(recCard(r), FrameLayout.LayoutParams(dp(170), WRAP_CONTENT).apply { leftMargin = x.toInt(); topMargin = y.toInt() }) }
        board.setOnDragListener { v, e -> when (e.action) {
            DragEvent.ACTION_DRAG_STARTED -> e.clipDescription?.label == "allfather"
            DragEvent.ACTION_DROP -> { val id = (e.localState as? Long) ?: return@setOnDragListener false
                val l = boardPlaces().filter { it.first != id }.toMutableList()
                l += Triple(id, (e.x - dp(85)).coerceIn(0f, (v.width - dp(170)).toFloat().coerceAtLeast(0f)), (e.y - dp(25)).coerceIn(0f, (v.height - dp(60)).toFloat().coerceAtLeast(0f)))
                saveBoard(l); v.post { refresh() }; true }
            else -> true } }
        row.addView(board, LinearLayout.LayoutParams(0, MATCH_PARENT, 66f))
        return row
    }

    // ------------------------------------------------------------------ acting
    private fun dragToFather(e: DragEvent): Boolean {
        when (e.action) {
            DragEvent.ACTION_DRAG_STARTED -> return e.clipDescription?.label == "allfather"
            DragEvent.ACTION_DRAG_ENTERED -> panelScroll.background = glass(18, 0xC0)
            DragEvent.ACTION_DRAG_EXITED, DragEvent.ACTION_DRAG_ENDED -> panelScroll.background = glass(18)
            DragEvent.ACTION_DROP -> { panelScroll.background = glass(18)
                val id = (e.localState as? Long) ?: return false
                AllFather.records(this).firstOrNull { it.id == id }?.let { analyze(listOf(it)) }; return true }
        }
        return true
    }
    private fun analyzeAll() = analyze(AllFather.today(this).filter { !it.acted }.ifEmpty { AllFather.today(this) })
    private fun handleIngest(i: android.content.Intent?) {
        val ids = i?.getLongArrayExtra("ingest") ?: return
        i.removeExtra("ingest")
        val set = ids.toSet()
        analyze(AllFather.records(this).filter { it.id in set })
    }
    /** Black screen: what he sees, what he decides, what he does. */
    private fun analyze(recs: List<AllFather.Rec>) {
        if (recs.isEmpty()) { android.widget.Toast.makeText(this, "Nothing new to analyze", android.widget.Toast.LENGTH_SHORT).show(); return }
        veil.setTextColor(ACC); veil.visibility = View.VISIBLE; veil.alpha = 0f; veil.animate().alpha(1f).setDuration(200).start()
        val out = arrayListOf("ALL FATHER · ANALYZING ${recs.size} RECORD${if (recs.size > 1) "S" else ""}")
        veil.text = out.joinToString("\n")
        veil.postDelayed({
            var n = 0
            for (r in recs) {
                r.acted = false
                val acts = AllFather.act(this, r).filter { it.kind != "FILE" }
                n += acts.size
                if (recs.size <= 4) { out += "> ${r.who.take(26)} · ${r.cat.label.uppercase()} · ${if (r.imp) "IMPORTANT" else "UNIMPORTANT"}"; acts.forEach { out += "  ✓ ${it.kind}  ${it.text.take(44)}" } }
            }
            val imp = recs.count { it.imp }
            out += "> ${recs.size} sorted · $imp need you · $n actions"
            veil.text = out.takeLast(14).joinToString("\n")
            if (imp > 0) SoundFX.play(this, "success")
            veil.postDelayed({ veil.animate().alpha(0f).setDuration(300).withEndAction { veil.visibility = View.GONE; selected = null; refresh() }.start() }, 2000)
        }, 700)
    }

    // ------------------------------------------------------------------ views
    /** The graph: information orb → category cards → the chosen category's items on an arc. Pinch or + − to zoom, drag to pan. */
    class GraphView(ctx: Context, private val recs: List<AllFather.Rec>, start: AllFatherBrain.Cat?,
                    private val onCat: (AllFatherBrain.Cat) -> Unit, private val onItem: (AllFather.Rec) -> Unit) : View(ctx) {
        private val d = resources.displayMetrics.density
        private val p = Paint(Paint.ANTI_ALIAS_FLAG); private val tp = Paint(Paint.ANTI_ALIAS_FLAG)
        private val cats = recs.groupBy { it.cat }.entries.sortedByDescending { e -> e.value.count { it.imp } * 100 + e.value.size }.map { it.key }.take(7)
        var cat: AllFatherBrain.Cat? = start?.takeIf { it in cats } ?: cats.firstOrNull(); private set
        private var zoom = 1f; private var panX = 0f; private var panY = 0f
        private var t = 0f
        private val catRects = HashMap<AllFatherBrain.Cat, RectF>(); private val itemPts = ArrayList<Pair<AllFather.Rec, RectF>>()
        private val anim = ValueAnimator.ofFloat(0f, 1f).apply { duration = 1000; repeatCount = ValueAnimator.INFINITE; addUpdateListener { t += .016f; invalidate() } }
        private val pts = (0 until 140).map { val y = 1 - it / 139f * 2; val r = sqrt(1 - y * y); val th = 2.39996f * it; floatArrayOf(cos(th) * r, y, sin(th) * r) }
        private val scaler = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(det: ScaleGestureDetector): Boolean { zoom = (zoom * det.scaleFactor).coerceIn(.6f, 2.5f); invalidate(); return true } })
        private val path = Path()
        fun zoomBy(k: Float) { zoom = (zoom * k).coerceIn(.6f, 2.5f); invalidate() }
        override fun onAttachedToWindow() { super.onAttachedToWindow(); anim.start() }
        override fun onDetachedFromWindow() { anim.cancel(); super.onDetachedFromWindow() }

        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            if (cats.isEmpty()) { tp.color = DIM; tp.textSize = 12 * d; tp.textAlign = Paint.Align.CENTER; c.drawText("Information appears here as notifications come in.", w / 2, h / 2, tp); return }
            c.save(); c.translate(panX, panY); c.scale(zoom, zoom, w * .4f, h / 2)
            val ox = w * .16f; val oy = h / 2; val R = min(h * .3f, w * .15f)
            // ---- the information orb
            p.style = Paint.Style.FILL
            p.shader = RadialGradient(ox, oy, R * 1.25f, intArrayOf(Hud.a(ACC, 60), Hud.a(ACC, 12), Color.TRANSPARENT), floatArrayOf(0f, .6f, 1f), Shader.TileMode.CLAMP)
            c.drawCircle(ox, oy, R * 1.25f, p); p.shader = null
            p.style = Paint.Style.STROKE; p.strokeWidth = d; p.color = Hud.a(ACC, 60); c.drawCircle(ox, oy, R, p)
            p.pathEffect = android.graphics.DashPathEffect(floatArrayOf(d, 3 * d), t * 6); p.color = Hud.a(Color.WHITE, 60); c.drawCircle(ox, oy, R * .78f, p); p.pathEffect = null
            val rot = t * .5f; val cr = cos(rot); val sr = sin(rot)
            p.style = Paint.Style.FILL
            p.shader = RadialGradient(ox, oy + R * .1f, R * .36f, intArrayOf(IconFactory.blend(ACC, Color.WHITE, .6f), ACC, Hud.a(ACC, 40)), floatArrayOf(0f, .6f, 1f), Shader.TileMode.CLAMP)
            c.drawCircle(ox, oy + R * .1f, R * .3f, p); p.shader = null
            for (q in pts) { val x = q[0] * cr + q[2] * sr; val z = -q[0] * sr + q[2] * cr
                p.color = Hud.a(IconFactory.blend(ACC, Color.WHITE, .4f), (60 + 150 * (z + 1) / 2).toInt()); c.drawCircle(ox + x * R * .62f, oy + R * .1f + q[1] * R * .45f, d * (.6f + .6f * (z + 1) / 2), p) }
            tp.color = INK; tp.textAlign = Paint.Align.CENTER; tp.typeface = Typeface.DEFAULT_BOLD; tp.textSize = 11 * d
            c.drawText("Information", ox, oy - R * .62f, tp); tp.typeface = Typeface.DEFAULT; tp.textSize = 9 * d; tp.color = DIM
            c.drawText("${recs.size} records · ${recs.count { it.imp }} need you", ox, oy - R * .62f + 13 * d, tp)
            // ---- category cards
            catRects.clear(); itemPts.clear()
            val cx = w * .38f; val cw = 118 * d; val ch = 38 * d; val gap = min(52 * d, (h - 40 * d) / cats.size)
            val gold = IconFactory.blend(0xFFE7C36B.toInt(), ACC, .3f)
            cats.forEachIndexed { i, k ->
                val cy = oy + (i - (cats.size - 1) / 2f) * gap
                val sel = k == cat
                p.style = Paint.Style.STROKE; p.strokeWidth = d * (if (sel) 1.4f else .8f); p.color = Hud.a(if (sel) gold else Color.WHITE, if (sel) 200 else 60)
                path.reset(); path.moveTo(ox + R * .9f, oy); path.cubicTo(ox + R * 1.6f, oy, cx - cw / 2 - 40 * d, cy, cx - cw / 2, cy); c.drawPath(path, p)
                val r = RectF(cx - cw / 2, cy - ch / 2, cx + cw / 2, cy + ch / 2); catRects[k] = r
                p.style = Paint.Style.FILL; p.color = if (sel) Hud.a(IconFactory.blend(0xFF2A2410.toInt(), ACC, .3f), 240) else 0xE60B1214.toInt(); c.drawRoundRect(r, 8 * d, 8 * d, p)
                p.style = Paint.Style.STROKE; p.strokeWidth = d; p.color = if (sel) gold else Hud.a(Color.WHITE, 45); c.drawRoundRect(r, 8 * d, 8 * d, p)
                if (sel) { p.color = Hud.a(gold, 70); p.strokeWidth = 4 * d; c.drawRoundRect(r, 8 * d, 8 * d, p) }
                tp.textAlign = Paint.Align.LEFT; tp.color = color(k); tp.textSize = 11 * d; c.drawText(glyph(k), r.left + 9 * d, r.top + 16 * d, tp)
                tp.color = INK; tp.typeface = Typeface.DEFAULT_BOLD; tp.textSize = 14 * d; c.drawText("${recs.count { it.cat == k }}", r.left + 26 * d, r.top + 17 * d, tp)
                tp.typeface = Typeface.DEFAULT; tp.color = DIM; tp.textSize = 8.5f * d; c.drawText("${k.label} · ${recs.count { it.cat == k && it.imp }} need you", r.left + 9 * d, r.bottom - 7 * d, tp)
            }
            // ---- the chosen category's items, fanned out on an arc
            val sel = cat
            val r0 = sel?.let { catRects[it] }
            if (sel != null && r0 != null) {
                val items = recs.filter { it.cat == sel }.take(18)
                val n = items.size
                items.forEachIndexed { i, rec ->
                    val f = if (n == 1) 0f else i / (n - 1f) - .5f
                    val iy = oy + f * (h - 30 * d)
                    val ix = w * .6f + (1 - (f * 2) * (f * 2)) * w * .1f
                    p.style = Paint.Style.STROKE; p.strokeWidth = d * .8f
                    p.shader = LinearGradient(r0.right, r0.centerY(), ix, iy, Hud.a(gold, 200), Hud.a(Color.WHITE, 60), Shader.TileMode.CLAMP)
                    path.reset(); path.moveTo(r0.right, r0.centerY()); path.cubicTo(r0.right + 50 * d, r0.centerY(), ix - 60 * d, iy, ix - 5 * d, iy); c.drawPath(path, p); p.shader = null
                    p.style = Paint.Style.FILL; p.color = if (rec.imp) IconFactory.blend(gold, Color.WHITE, .3f) else 0xFF6B7073.toInt(); c.drawCircle(ix, iy, d * (if (rec.imp) 3.6f else 2.6f), p)
                    tp.textAlign = Paint.Align.LEFT; tp.color = if (rec.imp) INK else DIM; tp.typeface = Typeface.DEFAULT_BOLD; tp.textSize = 10 * d
                    c.drawText(if (rec.who.length > 18) rec.who.take(17) + "…" else rec.who, ix + 7 * d, iy - 1 * d, tp)
                    val bx = ix + 7 * d; val by = iy + 4 * d
                    p.color = 0x33FFFFFF; c.drawRoundRect(bx, by, bx + 30 * d, by + 10 * d, 3 * d, 3 * d, p)
                    tp.typeface = Typeface.DEFAULT; tp.textSize = 7 * d; tp.color = INK; c.drawText("AF ${rec.score}", bx + 3 * d, by + 7.5f * d, tp)
                    p.color = if (rec.imp) Hud.a(RED, 200) else Hud.a(GREEN, 160); c.drawRoundRect(bx + 33 * d, by, bx + 48 * d, by + 10 * d, 3 * d, 3 * d, p)
                    if (rec.acted) { p.color = Hud.a(GREEN, 220); c.drawCircle(bx + 54 * d, by + 5 * d, 2.5f * d, p) }
                    itemPts += rec to RectF(ix - 10 * d, iy - 14 * d, ix + 120 * d, iy + 16 * d)
                }
            }
            c.restore()
        }

        private var dx0 = 0f; private var dy0 = 0f; private var px0 = 0f; private var py0 = 0f; private var moved = false
        override fun onTouchEvent(e: MotionEvent): Boolean {
            scaler.onTouchEvent(e)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { dx0 = e.x; dy0 = e.y; px0 = panX; py0 = panY; moved = false }
                MotionEvent.ACTION_MOVE -> if (!scaler.isInProgress && e.pointerCount == 1) {
                    if (abs(e.x - dx0) + abs(e.y - dy0) > 12 * d) moved = true
                    if (moved) { panX = px0 + e.x - dx0; panY = py0 + e.y - dy0; invalidate() }
                }
                MotionEvent.ACTION_UP -> if (!moved) {
                    // Undo pan/zoom to find what was tapped.
                    val w = width.toFloat(); val h = height.toFloat()
                    val x = (e.x - panX - w * .4f) / zoom + w * .4f; val y = (e.y - panY - h / 2) / zoom + h / 2
                    catRects.entries.firstOrNull { it.value.contains(x, y) }?.let { cat = it.key; SoundFX.play(context, "tick"); onCat(it.key); invalidate(); return true }
                    itemPts.firstOrNull { it.second.contains(x, y) }?.let { SoundFX.play(context, "tick"); onItem(it.first); return true }
                }
            }
            return true
        }
    }

    /** Small bar histogram (like the sentiment bars). */
    class Bars(ctx: Context, private val v: List<Int>) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(c: Canvas) {
            if (v.isEmpty()) return
            val d = resources.displayMetrics.density; val n = v.size; val bw = width / n.toFloat(); val mx = (v.maxOrNull() ?: 1).coerceAtLeast(1)
            v.forEachIndexed { i, x -> val bh = (height * (x.toFloat() / mx)).coerceAtLeast(2 * d)
                p.color = if (i >= n - 3) 0xFFFF8A7A.toInt() else 0x88FFB4A8.toInt(); c.drawRect(i * bw + bw * .2f, height - bh, i * bw + bw * .8f, height.toFloat(), p) }
        }
    }

    /** Small All Father ring (logo). */
    class RingView(ctx: Context) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG); private val r = RectF(); private val path = Path(); private var t = 0f
        private val anim = ValueAnimator.ofFloat(0f, 1f).apply { duration = 1000; repeatCount = ValueAnimator.INFINITE; addUpdateListener { t += .016f; invalidate() } }
        override fun onAttachedToWindow() { super.onAttachedToWindow(); anim.start() }
        override fun onDetachedFromWindow() { anim.cancel(); super.onDetachedFromWindow() }
        override fun onDraw(c: Canvas) {
            val cx = width / 2f; val cy = height / 2f; val R = min(cx, cy) - 1f; val d = resources.displayMetrics.density
            c.save(); c.rotate(t * 12f, cx, cy)
            for (k in 0 until 4) { path.reset(); val a0 = k * 90f + 8f
                r.set(cx - R, cy - R, cx + R, cy + R); path.arcTo(r, a0, 74f); r.set(cx - R * .72f, cy - R * .72f, cx + R * .72f, cy + R * .72f); path.arcTo(r, a0 + 74f, -74f); path.close()
                p.style = Paint.Style.FILL; p.color = Hud.a(ACC, if (k % 2 == 0) 200 else 110); c.drawPath(path, p) }
            c.restore(); p.color = ACC; c.drawCircle(cx, cy, d * 2f, p)
        }
    }

    class StatsView(ctx: Context, private val recs: List<AllFather.Rec>) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG); private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE }
        override fun onDraw(c: Canvas) {
            val d = resources.displayMetrics.density; val pad = 12 * d
            tp.color = ACC; tp.textSize = 8.5f * d; c.drawText("ACTIVITY · LAST 16 WEEKS", pad, pad + 6 * d, tp)
            val day = 86_400_000L; val now = System.currentTimeMillis()
            val counts = IntArray(112); recs.forEach { val i = ((now - it.time) / day).toInt(); if (i in 0..111) counts[111 - i]++ }
            val mx = (counts.maxOrNull() ?: 1).coerceAtLeast(1)
            val cell = ((width - 2 * pad) / 16f).coerceAtMost(18 * d)
            for (i in 0 until 112) {
                val x = pad + (i / 7) * cell; val y = pad + 14 * d + (i % 7) * cell * .62f
                p.color = ACC; p.alpha = (20 + 235 * counts[i] / mx).coerceIn(20, 255)
                c.drawRoundRect(x, y, x + cell - 3 * d, y + cell * .62f - 3 * d, 2 * d, 2 * d, p)
            }
            var y = pad + 14 * d + 7 * cell * .62f + 18 * d
            tp.color = ACC; c.drawText("BY CATEGORY · 7 DAYS", pad, y, tp); y += 8 * d
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
