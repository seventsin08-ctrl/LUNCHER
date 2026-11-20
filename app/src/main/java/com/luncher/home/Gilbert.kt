package com.luncher.home

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Environment
import android.os.StatFs
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * Gilbert — the living orb in the Dynamic Island. He looks after your notifications:
 * priority apps pop up right away, everything else waits quietly with him (so the screen is
 * never flooded), he keeps an eye on the phone (battery, heat, storage, memory) and tells you
 * which apps are the noisiest. Tap him to see what he's holding and how the phone is doing.
 */
object Gilbert {
    private val popTimes = ArrayDeque<Long>()
    /** Notifications he's holding for you (newest last): package → count. */
    val held = LinkedHashMap<String, Int>()
    var onHeldChanged: (() -> Unit)? = null

    /** Colour presets for the orb: (top, bottom). */
    fun colors(s: Settings): Pair<Int, Int> = when (s.gilbertColors) {
        "ocean" -> 0xFF4FC3F7.toInt() to 0xFF2962FF.toInt()
        "aurora" -> 0xFF69F0AE.toInt() to 0xFFB388FF.toInt()
        "rose" -> 0xFFFF80AB.toInt() to 0xFFFFFFFF.toInt()
        "mono" -> 0xFFFFFFFF.toInt() to 0xFF9E9E9E.toInt()
        "theme" -> ThemeEngine.accent to Color.WHITE
        else -> 0xFFFFA24C.toInt() to 0xFFFFFFFF.toInt()      // ember (default)
    }

    /** Count every notification (for "noisiest apps"), per day. */
    fun record(ctx: Context, pkg: String) {
        val p = ctx.getSharedPreferences("luncher", Context.MODE_PRIVATE)
        val day = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_YEAR)
        if (p.getInt("gil_day", -1) != day) p.edit().putInt("gil_day", day).putString("gil_counts", "{}").apply()
        val o = runCatching { org.json.JSONObject(p.getString("gil_counts", "{}")!!) }.getOrDefault(org.json.JSONObject())
        o.put(pkg, o.optInt(pkg, 0) + 1)
        p.edit().putString("gil_counts", o.toString()).apply()
    }

    fun countsToday(ctx: Context): List<Pair<String, Int>> {
        val p = ctx.getSharedPreferences("luncher", Context.MODE_PRIVATE)
        val day = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_YEAR)
        if (p.getInt("gil_day", -1) != day) return emptyList()
        val o = runCatching { org.json.JSONObject(p.getString("gil_counts", "{}")!!) }.getOrDefault(org.json.JSONObject())
        return o.keys().asSequence().map { it to o.optInt(it) }.sortedByDescending { it.second }.toList()
    }

    /**
     * May this notification pop up on screen now? Priority apps: yes. Others: only when Calm Mode
     * is off and the pop-up limit for the last minute isn't reached. Otherwise Gilbert holds it.
     */
    fun shouldPop(ctx: Context, s: Settings, pkg: String): Boolean {
        if (!s.islandFace) return true
        if (pkg in s.gilbertQuiet) { hold(pkg); return false }
        val priority = pkg in s.gilbertPriority
        val now = System.currentTimeMillis()
        while (popTimes.isNotEmpty() && now - popTimes.first() > 60_000) popTimes.removeFirst()
        val ok = priority || (!s.gilbertCalm && popTimes.size < s.gilbertMaxPerMin) ||
            (s.gilbertCalm && s.gilbertPriority.isEmpty() && popTimes.size < s.gilbertMaxPerMin)
        if (ok) popTimes.addLast(now) else hold(pkg)
        return ok
    }

    private fun hold(pkg: String) {
        held[pkg] = (held.remove(pkg) ?: 0) + 1
        onHeldChanged?.invoke()
    }

    fun heldCount() = held.values.sum()
    fun clearHeld() { held.clear(); onHeldChanged?.invoke() }

    // ---------------------------------------------------------------- system check
    class Health(val battery: Int, val charging: Boolean, val tempC: Float?, val storageFreeGb: Float, val storageTotalGb: Float,
                 val ramFreePct: Int)

    fun health(ctx: Context): Health {
        val bi = ctx.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        val level = bi?.let { it.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) * 100 / it.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1) } ?: -1
        val status = bi?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1
        val temp = bi?.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)?.takeIf { it != Int.MIN_VALUE }?.let { it / 10f }
        val st = runCatching { StatFs(Environment.getDataDirectory().path) }.getOrNull()
        val free = st?.let { it.availableBytes / 1e9f } ?: 0f
        val total = st?.let { it.totalBytes / 1e9f } ?: 0f
        val am = ctx.getSystemService(android.app.ActivityManager::class.java)
        val mi = android.app.ActivityManager.MemoryInfo(); runCatching { am.getMemoryInfo(mi) }
        val ram = if (mi.totalMem > 0) (mi.availMem * 100 / mi.totalMem).toInt() else -1
        return Health(level, status == android.os.BatteryManager.BATTERY_STATUS_CHARGING || status == android.os.BatteryManager.BATTERY_STATUS_FULL,
            temp, free, total, ram)
    }

    /** One line Gilbert says about how things are. */
    fun mood(ctx: Context, h: Health): String = when {
        (h.tempC ?: 0f) >= 42f -> "Your phone is hot (${h.tempC!!.roundToInt()}°). Maybe take a break 🥵"
        h.battery in 0..15 && !h.charging -> "Battery is low (${h.battery}%). Time to charge 🔋"
        h.storageTotalGb > 0 && h.storageFreeGb / h.storageTotalGb < 0.08f -> "Storage is almost full — only ${"%.1f".format(h.storageFreeGb)} GB left"
        heldCount() > 0 -> "I kept ${heldCount()} notifications quiet for you"
        else -> "All calm. Nothing needs you right now ✨"
    }

    // ---------------------------------------------------------------- Gilbert's panel
    /**
     * Tap Gilbert: his card — what he's holding (tap an app to open it), today's noisiest apps
     * (make them Priority or Quiet), and a quick check of the phone.
     */
    fun showPanel(act: Activity, root: FrameLayout, s: Settings, appInfo: (String) -> Pair<android.graphics.Bitmap?, String>?,
                  openApp: (String) -> Unit, openInbox: () -> Unit, openTools: () -> Unit) {
        fun dp(v: Int) = (v * act.resources.displayMetrics.density).roundToInt()
        val layer = FrameLayout(act).apply { setBackgroundColor(Color.argb(140, 0, 0, 0)); isClickable = true }
        val card = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(16))
            background = GradientDrawable().apply { cornerRadius = dp(30).toFloat(); setColor(0xF7161618.toInt()); setStroke(maxOf(1, dp(1)), 0x30FFFFFF) }
            elevation = dp(16).toFloat()
        }
        lateinit var close: () -> Unit
        // Header: Gilbert himself + what he thinks.
        val head = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(FrameLayout(act).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.BLACK) }
            addView(IslandFace(act).apply { configure(s) }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }, LinearLayout.LayoutParams(dp(58), dp(58)))
        val h = health(act)
        val titles = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, 0, 0) }
        titles.addView(TextView(act).apply { text = "Gilbert"; textSize = 22f; setTextColor(Color.WHITE); typeface = Typeface.create("sans-serif", Typeface.BOLD) })
        titles.addView(TextView(act).apply { text = mood(act, h); textSize = 13.5f; setTextColor(0xB3FFFFFF.toInt()) })
        head.addView(titles, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        card.addView(head)

        val body = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        fun section(t: String) = body.addView(TextView(act).apply {
            text = t; textSize = 12f; setTextColor(0x80FFFFFF.toInt()); letterSpacing = 0.06f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD); setPadding(0, dp(16), 0, dp(6))
        })
        fun row(icon: android.graphics.Bitmap?, label: String, trail: String, onClick: (() -> Unit)?, extra: View? = null) {
            val r = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(6), 0, dp(6))
                if (onClick != null) setOnClickListener { onClick() }
            }
            if (icon != null) r.addView(android.widget.ImageView(act).apply {
                setImageBitmap(icon)
                outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(v: View, o: android.graphics.Outline) = o.setRoundRect(0, 0, v.width, v.height, v.width * 0.26f)
                }; clipToOutline = true
            }, LinearLayout.LayoutParams(dp(30), dp(30)).apply { marginEnd = dp(12) })
            r.addView(TextView(act).apply { text = label; textSize = 15f; setTextColor(Color.WHITE); maxLines = 1 },
                LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            if (extra != null) r.addView(extra)
            r.addView(TextView(act).apply { text = trail; textSize = 14f; setTextColor(0x99FFFFFF.toInt()); setPadding(dp(8), 0, 0, 0) })
            body.addView(r)
        }
        fun pill(t: String, color: Int, fn: () -> Unit) = TextView(act).apply {
            text = t; textSize = 11.5f; setTextColor(color); typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(9), dp(4), dp(9), dp(4))
            background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(0x22FFFFFF) }
            setOnClickListener { fn() }
        }

        // What he's holding.
        section("HELD QUIETLY FOR YOU")
        if (held.isEmpty()) body.addView(TextView(act).apply { text = "Nothing waiting."; textSize = 14f; setTextColor(0x80FFFFFF.toInt()) })
        for ((pkg, n) in held.entries.reversed()) {
            val info = appInfo(pkg)
            row(info?.first, info?.second ?: pkg, "$n", { close(); openApp(pkg) })
        }
        if (held.isNotEmpty()) body.addView(LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(6), 0, 0)
            addView(pill("Open Inbox", 0xFF0A84FF.toInt()) { close(); openInbox() })
            addView(View(act), LinearLayout.LayoutParams(dp(8), 1))
            addView(pill("Clear", 0xFFFF453A.toInt()) { clearHeld(); close() })
        })

        // Noisiest apps today — make them Priority (always show) or Quiet (always hold).
        val counts = countsToday(act).take(4)
        if (counts.isNotEmpty()) {
            section("NOISIEST TODAY")
            for ((pkg, n) in counts) {
                val info = appInfo(pkg)
                val pri = pkg in s.gilbertPriority; val quiet = pkg in s.gilbertQuiet
                val tag = pill(when { pri -> "★ Priority"; quiet -> "Quiet"; else -> "Normal" },
                    when { pri -> 0xFFFFD60A.toInt(); quiet -> 0xFF8E8E93.toInt(); else -> Color.WHITE }) {}
                tag.setOnClickListener {
                    // Normal → Priority → Quiet → Normal
                    when {
                        pkg in s.gilbertPriority -> { s.gilbertPriority = s.gilbertPriority - pkg; s.gilbertQuiet = s.gilbertQuiet + pkg }
                        pkg in s.gilbertQuiet -> s.gilbertQuiet = s.gilbertQuiet - pkg
                        else -> s.gilbertPriority = s.gilbertPriority + pkg
                    }
                    tag.text = when { pkg in s.gilbertPriority -> "★ Priority"; pkg in s.gilbertQuiet -> "Quiet"; else -> "Normal" }
                    tag.setTextColor(when { pkg in s.gilbertPriority -> 0xFFFFD60A.toInt(); pkg in s.gilbertQuiet -> 0xFF8E8E93.toInt(); else -> Color.WHITE })
                }
                row(info?.first, info?.second ?: pkg, "$n", null, tag)
            }
        }

        // The phone.
        section("PHONE CHECK")
        row(null, "🔋  Battery", if (h.battery >= 0) "${h.battery}%${if (h.charging) " ⚡" else ""}" else "—", null)
        row(null, "🌡️  Temperature", h.tempC?.let { "${it.roundToInt()}°C" } ?: "—", null)
        row(null, "💾  Storage free", if (h.storageTotalGb > 0) "${"%.1f".format(h.storageFreeGb)} of ${"%.0f".format(h.storageTotalGb)} GB" else "—", null)
        row(null, "🧠  Memory free", if (h.ramFreePct >= 0) "${h.ramFreePct}%" else "—", null)

        val scroll = object : ScrollView(act) {
            override fun onMeasure(w: Int, hs: Int) = super.onMeasure(w,
                View.MeasureSpec.makeMeasureSpec((act.resources.displayMetrics.heightPixels * 0.55f).toInt(), View.MeasureSpec.AT_MOST))
        }.apply { isVerticalScrollBarEnabled = false; addView(body) }
        card.addView(scroll)
        card.addView(LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(14), 0, 0)
            addView(pill("Tools", Color.WHITE) { close(); openTools() })
            addView(View(act), LinearLayout.LayoutParams(0, 1, 1f))
            addView(pill("Done", 0xFF0A84FF.toInt()) { close() })
        })
        layer.setOnClickListener { close() }
        card.isClickable = true
        layer.addView(card, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP).apply {
            leftMargin = dp(14); rightMargin = dp(14); topMargin = dp(70)
        })
        close = {
            card.animate().alpha(0f).translationY(-dp(20).toFloat()).setDuration(180).start()
            layer.animate().alpha(0f).setDuration(220).withEndAction { root.removeView(layer) }.start()
        }
        root.addView(layer, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        // Grows out of the island at the top.
        layer.alpha = 0f; layer.animate().alpha(1f).setDuration(200).start()
        card.pivotX = act.resources.displayMetrics.widthPixels / 2f; card.pivotY = 0f
        card.scaleX = 0.3f; card.scaleY = 0.2f; card.alpha = 0f
        card.animate().alpha(1f).setDuration(200).start()
        Motion.scaleTo(card, 1f, Motion.FLUID)
    }
}
