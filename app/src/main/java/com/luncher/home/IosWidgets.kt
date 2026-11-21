package com.luncher.home

import android.app.Activity
import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * The new iPhone widgets (15, 17–20). They only appear when you add them from the widget gallery,
 * so nothing new crowds the Home Screen. Slow work (usage stats, prices) runs off the main thread.
 */
object IosWidgets {
    private val bg = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private fun dp(c: Context, v: Int) = (v * c.resources.displayMetrics.density).roundToInt()

    private fun text(c: Context, s: String, size: Float, color: Int = Color.WHITE, bold: Boolean = false) = TextView(c).apply {
        text = s; textSize = size; setTextColor(color); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        typeface = Typeface.create(if (bold) "sans-serif-medium" else "sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun column(c: Context) = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(c, 16), dp(c, 14), dp(c, 16), dp(c, 14))
    }

    // ------------------------------------------------------------------ 18. Screen Time

    fun hasUsageAccess(c: Context): Boolean = runCatching {
        val ops = c.getSystemService(AppOpsManager::class.java)
        ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.packageName) == AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    fun screenTime(act: Activity, labelOf: (String) -> String?): View {
        val col = column(act)
        col.addView(text(act, "SCREEN TIME", 11f, 0xFF64D2FF.toInt(), true))
        val total = text(act, "—", 28f, Color.WHITE, true)
        col.addView(total)
        val rows = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        col.addView(rows, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(act, 4) })
        if (!hasUsageAccess(act)) {
            total.text = "Tap to allow"
            total.textSize = 17f
            rows.addView(text(act, "Shows today's phone time", 13f, 0x99FFFFFF.toInt()))
            col.setOnClickListener {
                runCatching { act.startActivity(Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            return col
        }
        bg.execute {
            val usage = runCatching {
                val um = act.getSystemService(UsageStatsManager::class.java)
                val start = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0) }.timeInMillis
                um.queryAndAggregateUsageStats(start, System.currentTimeMillis())
                    .filter { it.key != act.packageName && it.value.totalTimeInForeground > 60_000 }
                    .map { it.key to it.value.totalTimeInForeground }.sortedByDescending { it.second }
            }.getOrDefault(emptyList())
            main.post {
                if (!col.isAttachedToWindow && col.parent == null) return@post
                total.text = Ios.duration(usage.sumOf { it.second })
                rows.removeAllViews()
                val top = usage.firstOrNull()?.second?.coerceAtLeast(1) ?: 1
                for ((pkg, ms) in usage.take(3)) {
                    val r = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                    r.addView(text(act, labelOf(pkg) ?: pkg.substringAfterLast('.'), 12f, 0xCCFFFFFF.toInt()),
                        LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                    r.addView(text(act, Ios.duration(ms), 12f, 0x99FFFFFF.toInt()))
                    rows.addView(r)
                    rows.addView(View(act).apply { background = Glass.pill(0xFF64D2FF.toInt(), dp(act, 2).toFloat()) },
                        LinearLayout.LayoutParams((dp(act, 120) * ms / top.toFloat()).toInt().coerceAtLeast(dp(act, 6)), dp(act, 3))
                            .apply { bottomMargin = dp(act, 3) })
                }
                if (usage.isEmpty()) rows.addView(text(act, "Nothing yet today", 13f, 0x99FFFFFF.toInt()))
            }
        }
        col.setOnClickListener {
            runCatching { act.startActivity(Intent("android.settings.DIGITAL_WELLBEING_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                .onFailure { runCatching { act.startActivity(Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
        }
        return col
    }

    // ------------------------------------------------------------------ 19. Stocks

    private const val STOCK_TTL = 15 * 60_000L

    fun stocks(act: Activity, tickers: List<String>, onEdit: () -> Unit): View {
        val col = column(act)
        val head = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        head.addView(text(act, "STOCKS", 11f, 0xFF30D158.toInt(), true), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        head.addView(text(act, "Edit", 11f, 0x99FFFFFF.toInt()).apply { setOnClickListener { onEdit() } })
        col.addView(head)
        val prefs = act.getSharedPreferences("luncher", Context.MODE_PRIVATE)
        val rows = HashMap<String, Pair<TextView, TextView>>()
        for (t in tickers.ifEmpty { listOf("AAPL") }) {
            val r = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(act, 3), 0, dp(act, 3)) }
            r.addView(text(act, t, 15f, Color.WHITE, true), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            val price = text(act, prefs.getString("stk_p_$t", "…") ?: "…", 14f, Color.WHITE)
            val chg = text(act, prefs.getString("stk_c_$t", "") ?: "", 12f, Color.WHITE, true).apply {
                gravity = Gravity.CENTER; setPadding(dp(act, 6), dp(act, 2), dp(act, 6), dp(act, 2))
                minWidth = dp(act, 62)
            }
            paintChange(act, chg)
            r.addView(price, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = dp(act, 8) })
            r.addView(chg)
            col.addView(r)
            rows[t] = price to chg
        }
        // Refresh at most every 15 minutes; between refreshes the last prices show instantly.
        if (System.currentTimeMillis() - prefs.getLong("stk_time", 0) > STOCK_TTL) bg.execute {
            for (t in rows.keys) {
                val q = runCatching {
                    val con = URL("https://query1.finance.yahoo.com/v8/finance/chart/$t?range=1d&interval=1d").openConnection() as HttpURLConnection
                    con.connectTimeout = 6000; con.readTimeout = 6000
                    con.setRequestProperty("User-Agent", "Mozilla/5.0")
                    con.inputStream.bufferedReader().use { it.readText() }.also { con.disconnect() }
                }.getOrNull()?.let { Ios.parseQuote(it) } ?: continue
                val p = String.format(java.util.Locale.US, "%.2f", q.first)
                val c = Ios.change(q.first, q.second)
                prefs.edit().putString("stk_p_$t", p).putString("stk_c_$t", c).apply()
                main.post { rows[t]?.let { (pv, cv) -> pv.text = p; cv.text = c; paintChange(act, cv) } }
            }
            prefs.edit().putLong("stk_time", System.currentTimeMillis()).apply()
        }
        return col
    }

    private fun paintChange(c: Context, v: TextView) {
        val t = v.text.toString()
        v.background = if (t.isEmpty()) null else Glass.pill(if (t.startsWith("−")) 0xFFFF453A.toInt() else 0xFF30D158.toInt(), dp(c, 6).toFloat())
    }

    // ------------------------------------------------------------------ 20. Shortcuts grid

    class Shortcut(val id: String, val emoji: String, val label: String, val color: Int)

    val SHORTCUTS = listOf(
        Shortcut("torch", "🔦", "Torch", 0xFFFFD60A.toInt()),
        Shortcut("timer", "⏱", "Timer", 0xFFFF9F0A.toInt()),
        Shortcut("alarm", "⏰", "Alarm", 0xFFFF9F0A.toInt()),
        Shortcut("camera", "📷", "Camera", 0xFF8E8E93.toInt()),
        Shortcut("calc", "🧮", "Calculator", 0xFF636366.toInt()),
        Shortcut("wifi", "📶", "Wi-Fi", 0xFF0A84FF.toInt()),
        Shortcut("bt", "ᛒ", "Bluetooth", 0xFF0A84FF.toInt()),
        Shortcut("record", "🎙", "Record", 0xFFFF453A.toInt()),
        Shortcut("cc", "🎛", "Controls", 0xFF5E5CE6.toInt()),
        Shortcut("focus", "🌙", "Focus", 0xFF5E5CE6.toInt()),
        Shortcut("search", "🔍", "Search", 0xFF8E8E93.toInt()),
        Shortcut("lock", "🔒", "Lock", 0xFF30D158.toInt()),
        Shortcut("notes", "📝", "Note", 0xFFFFD60A.toInt()),
        Shortcut("today", "📅", "Today", 0xFFFF453A.toInt()),
        Shortcut("standby", "🕰", "StandBy", 0xFFFF9F0A.toInt()),
        Shortcut("settings", "⚙", "Settings", 0xFF8E8E93.toInt()),
        Shortcut("qr", "▣", "Scan Code", 0xFF8E8E93.toInt()),
        Shortcut("screen", "⌛", "Screen Time", 0xFF5E5CE6.toInt())
    )

    fun shortcuts(act: Activity, ids: List<String>, run: (String, View) -> Unit): View {
        val list = ids.mapNotNull { id -> SHORTCUTS.firstOrNull { it.id == id } }.take(16)
        val (cols, rows) = Ios.shortcutGrid(list.size)
        val grid = GridLayout(act).apply {
            columnCount = cols; rowCount = rows
            setPadding(dp(act, 10), dp(act, 10), dp(act, 10), dp(act, 10))
        }
        for (s in list) {
            val b = TextView(act).apply {
                text = s.emoji; textSize = 18f; gravity = Gravity.CENTER
                background = GradientDrawable().apply { cornerRadius = dp(act, 14).toFloat(); setColor(Color.argb(90, Color.red(s.color), Color.green(s.color), Color.blue(s.color))) }
                contentDescription = s.label
                setOnClickListener { v -> v.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK); Motion.scaleTo(v, 1f, Motion.BOUNCY, start = 0.85f); run(s.id, v) }
            }
            grid.addView(b, GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED, 1f), GridLayout.spec(GridLayout.UNDEFINED, 1f)).apply {
                width = 0; height = 0; setMargins(dp(act, 4), dp(act, 4), dp(act, 4), dp(act, 4))
            })
        }
        if (list.isEmpty()) return column(act).apply { addView(text(act, "No shortcuts — tap Edit in Settings", 13f, 0x99FFFFFF.toInt())) }
        return grid
    }

    // ------------------------------------------------------------------ 17. Interactive reminders

    private const val KEY_REM = "ios_reminders"

    fun loadReminders(c: Context): MutableList<Pair<String, Boolean>> =
        (c.getSharedPreferences("luncher", Context.MODE_PRIVATE).getString(KEY_REM, null) ?: "0|Drink water\n0|Call back\n0|Plan tomorrow")
            .split('\n').filter { it.length > 2 }.map { it.substring(2) to (it[0] == '1') }.toMutableList()

    fun saveReminders(c: Context, items: List<Pair<String, Boolean>>) =
        c.getSharedPreferences("luncher", Context.MODE_PRIVATE).edit()
            .putString(KEY_REM, items.take(20).joinToString("\n") { (if (it.second) "1|" else "0|") + it.first.replace('\n', ' ') }).apply()

    fun reminders(act: Activity, maxRows: Int, onAdd: () -> Unit): View {
        val col = column(act)
        val head = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(text(act, "REMINDERS", 11f, 0xFFFF9F0A.toInt(), true), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        head.addView(text(act, "＋", 18f, 0xFFFF9F0A.toInt(), true).apply { setPadding(dp(act, 8), 0, 0, 0); setOnClickListener { onAdd() } })
        col.addView(head)
        val items = loadReminders(act)
        // Open items first; ticked ones stay (struck through) until you tick them again or they're cleared.
        val shown = (items.filter { !it.second } + items.filter { it.second }).take(maxRows)
        for (item in shown) {
            val r = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(act, 4), 0, dp(act, 4)) }
            val box = TextView(act).apply {
                gravity = Gravity.CENTER; textSize = 11f; setTextColor(Color.BLACK)
                text = if (item.second) "✓" else ""
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    if (item.second) setColor(0xFFFF9F0A.toInt()) else setStroke(dp(act, 2), 0xFFFF9F0A.toInt())
                }
            }
            val label = text(act, item.first, 14f, if (item.second) 0x77FFFFFF else Color.WHITE)
            if (item.second) label.paintFlags = label.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            r.addView(box, LinearLayout.LayoutParams(dp(act, 20), dp(act, 20)).apply { marginEnd = dp(act, 10) })
            r.addView(label, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            r.setOnClickListener { v ->
                // Tick right on the Home Screen — no app opens.
                v.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                val all = loadReminders(act)
                val i = all.indexOfFirst { it.first == item.first && it.second == item.second }
                if (i >= 0) { all[i] = all[i].first to !all[i].second; saveReminders(act, all) }
                val done = !item.second
                box.text = if (done) "✓" else ""
                box.background = GradientDrawable().apply { shape = GradientDrawable.OVAL
                    if (done) setColor(0xFFFF9F0A.toInt()) else setStroke(dp(act, 2), 0xFFFF9F0A.toInt()) }
                label.setTextColor(if (done) 0x77FFFFFF else Color.WHITE)
                label.paintFlags = if (done) label.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG else label.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
                Motion.scaleTo(box, 1f, Motion.BOUNCY, start = 0.6f)
            }
            col.addView(r)
        }
        if (shown.isEmpty()) col.addView(text(act, "All done 🎉", 14f, 0x99FFFFFF.toInt()))
        return col
    }

    // ------------------------------------------------------------------ 15. Extra Large

    /** A whole-page dashboard: big clock & date, then a 2×2 of the other cards. */
    fun extraLarge(act: Activity, cards: List<View>): View {
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(act, 6), dp(act, 6), dp(act, 6), dp(act, 6)) }
        val head = column(act)
        val clock = android.widget.TextClock(act).apply {
            format12Hour = "h:mm"; format24Hour = "H:mm"; textSize = 52f; setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        }
        val date = android.widget.TextClock(act).apply {
            format12Hour = "EEEE, MMMM d"; format24Hour = "EEEE, d MMMM"; textSize = 16f; setTextColor(0xCCFFFFFF.toInt())
        }
        head.addView(date); head.addView(clock)
        col.addView(head, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        for (pair in cards.take(4).chunked(2)) {
            val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
            for (card in pair) {
                val f = FrameLayout(act).apply {
                    background = GradientDrawable().apply { cornerRadius = dp(act, 18).toFloat(); setColor(0x22FFFFFF) }
                    addView(card, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
                }
                row.addView(f, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f).apply { setMargins(dp(act, 4), dp(act, 4), dp(act, 4), dp(act, 4)) })
            }
            col.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        }
        return col
    }
}
