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
        // Follows your wallpaper / theme colour (changes with it).
        "theme" -> ThemeEngine.accent to IconFactory.blend(ThemeEngine.accent, Color.WHITE, 0.55f)
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
    fun shouldPop(ctx: Context, s: Settings, pkg: String, isMessage: Boolean = false): Boolean {
        if (!s.islandFace) return true
        if (pkg in s.gilbertQuiet) { hold(pkg); return false }
        // Real messages (chats from any app) always show — even on silent, vibrate or Calm Mode.
        if (isMessage && s.alwaysShowMessages) return true
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

    // ---------------------------------------------------------------- notes & mentions
    class Note(val pkg: String, val title: String, val text: String, val time: Long, val mention: Boolean)

    /** Gilbert writes down every message / notification (latest 80), and spots mentions of you. */
    /** "jxhn.mrqs: Jia rxse." → "Jia rxse" (Instagram & co. put your account name in front). */
    fun cleanTitle(t: String): String {
        var x = t.trim()
        Regex("^[\\w.]{2,30}:\\s+(.+)$").find(x)?.let { x = it.groupValues[1] }
        return x.trim().trimEnd('.').trim().ifEmpty { t.trim() }
    }

    fun note(ctx: Context, pkg: String, title0: String, text: String) {
        val title = cleanTitle(title0)
        if (!GilbertTalk.isMe(ctx, title)) GilbertMemory.person(ctx, title)
        GilbertArchive.save(ctx, pkg, title, text)
        val name = GilbertTalk.ownerName(ctx)?.lowercase()
        val low = (title + " " + text).lowercase()
        val mention = low.contains("mentioned you") || low.contains("tagged you") || low.contains("replied to you") ||
            low.contains("@everyone") || (name != null && (low.contains("@$name") || Regex("\\b${Regex.escape(name)}\\b").containsMatchIn(low)))
        val p = ctx.getSharedPreferences("luncher", Context.MODE_PRIVATE)
        val arr = runCatching { org.json.JSONArray(p.getString("gil_notes", "[]")) }.getOrDefault(org.json.JSONArray())
        arr.put(org.json.JSONObject().put("p", pkg).put("t", title).put("x", text).put("w", System.currentTimeMillis()).put("m", mention))
        val keep = org.json.JSONArray(); val from = maxOf(0, arr.length() - 80)
        for (i in from until arr.length()) keep.put(arr.get(i))
        p.edit().putString("gil_notes", keep.toString()).apply()
    }

    fun notes(ctx: Context): List<Note> {
        val arr = runCatching { org.json.JSONArray(ctx.getSharedPreferences("luncher", Context.MODE_PRIVATE).getString("gil_notes", "[]")) }
            .getOrDefault(org.json.JSONArray())
        return (0 until arr.length()).map { arr.getJSONObject(it) }.map {
            Note(it.optString("p"), cleanTitle(it.optString("t")), it.optString("x"), it.optLong("w"), it.optBoolean("m"))
        }.reversed()
    }

    private fun ago(t: Long): String {
        val m = ((System.currentTimeMillis() - t) / 60000).coerceAtLeast(0)
        return when { m < 1 -> "now"; m < 60 -> "${m}m"; m < 1440 -> "${m / 60}h"; else -> "${m / 1440}d" }
    }
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
     * Tap Gilbert: his card, in a dark "brushed metal" flow style — stacked tiles with status
     * chips, segmented meters, and checklists. Status, held notifications (tap to open), mentions
     * and notes, the noisiest apps (tap the chip: Normal → Priority → Quiet), and a phone check.
     */
    /** Closes the card that's open right now (null when none is). */
    var panelClose: (() -> Unit)? = null
    fun isPanelOpen() = panelClose != null

    fun showPanel(act: Activity, root: FrameLayout, s: Settings, appInfo: (String) -> Pair<android.graphics.Bitmap?, String>?,
                  openApp: (String) -> Unit, openInbox: () -> Unit, openTools: () -> Unit, openChat: (() -> Unit)? = null) {
        // Only ever one card: tapping Gilbert again while it's open closes it instead.
        panelClose?.let { it(); return }
        fun dp(v: Int) = (v * act.resources.displayMetrics.density).roundToInt()
        fun dpf(v: Float) = v * act.resources.displayMetrics.density
        // HUD style (same as his console), coloured by your wallpaper / theme.
        val HUD = Hud.CYAN; val SOFT = Hud.SOFT
        val GREEN = HUD; val AMBER = 0xFFFFC53D.toInt(); val RED = 0xFFFF453A.toInt()
        val BLUE = HUD; val DIM = 0x99FFFFFF.toInt(); val FAINT = Hud.a(SOFT, 90)
        val layer = FrameLayout(act).apply { setBackgroundColor(Color.argb(170, 0, 0, 0)); isClickable = true }
        // Theme: the card is tinted with your wallpaper / theme colour (graphite when Mono).
        val tint = if (ThemeEngine.colorful) ThemeEngine.accent else 0xFF8E8E93.toInt()
        fun tinted(c: Int, k: Float) = IconFactory.blend(c, tint, k)
        // The card: a brushed-graphite gradient with a fine light edge.
        val card = HudFrame(act).apply {
            setPadding(dp(16), dp(16), dp(16), dp(14))
            background = GradientDrawable().apply { setColor(Hud.bg(244)) }
            elevation = dpf(20f)
        }
        lateinit var close: () -> Unit
        val h = health(act)
        val allNotes = notes(act)
        val mentions = allNotes.filter { it.mention }.take(5)

        // ---- small builders
        fun text(t: String, size: Float, color: Int, bold: Boolean = false) = TextView(act).apply {
            text = t; textSize = size; setTextColor(color); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            typeface = Typeface.create(if (bold) "sans-serif-medium" else "sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
        }
        fun chip(t: String, color: Int) = TextView(act).apply {
            text = t.uppercase(); textSize = 9.5f; setTextColor(color); typeface = Hud.monoBold; letterSpacing = 0.06f
            setPadding(dp(7), dp(2), dp(7), dp(3))
            background = GradientDrawable().apply { cornerRadius = dpf(3f); setColor((color and 0x00FFFFFF) or 0x1F000000); setStroke(maxOf(1, dp(1)), (color and 0x00FFFFFF) or 0x80000000.toInt()) }
        }
        fun bigPill(t: String, color: Int, fn: () -> Unit) = TextView(act).apply {
            text = t.uppercase(); textSize = 11f; setTextColor(color); typeface = Hud.monoBold; letterSpacing = 0.05f
            setPadding(dp(12), dp(7), dp(12), dp(7))
            background = GradientDrawable().apply { cornerRadius = dpf(4f); setColor((color and 0x00FFFFFF) or 0x26000000)
                setStroke(maxOf(1, dp(1)), (color and 0x00FFFFFF) or 0x40000000) }
            setOnClickListener { SoundFX.play(act, "tick"); fn() }
        }
        fun roundBtn(glyph: String, fn: () -> Unit) = TextView(act).apply {
            text = glyph; textSize = 15f; setTextColor(SOFT); gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Hud.a(HUD, 18)); setStroke(maxOf(1, dp(1)), Hud.a(HUD, 140)) }
            setOnClickListener { SoundFX.play(act, "tick"); fn() }
        }
        fun iconBox(bmp: android.graphics.Bitmap?, glyph: String?, size: Int = 38): View {
            val box = FrameLayout(act).apply {
                background = GradientDrawable().apply { cornerRadius = dpf(5f); setColor(Hud.a(HUD, 16)); setStroke(maxOf(1, dp(1)), Hud.a(HUD, 90)) }
            }
            if (bmp != null) box.addView(android.widget.ImageView(act).apply {
                setImageBitmap(bmp)
                outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(v: View, o: android.graphics.Outline) = o.setRoundRect(0, 0, v.width, v.height, v.width * 0.28f)
                }; clipToOutline = true
            }, FrameLayout.LayoutParams(dp(size - 12), dp(size - 12), Gravity.CENTER))
            else box.addView(TextView(act).apply { text = glyph ?: "•"; textSize = size * 0.4f; gravity = Gravity.CENTER; setTextColor(Color.WHITE) },
                FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            box.layoutParams = LinearLayout.LayoutParams(dp(size), dp(size)).apply { marginEnd = dp(12) }
            return box
        }
        /** A tile: rounded glassy plate inside the card. */
        fun tile(): LinearLayout = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply { cornerRadius = dpf(6f); setColor(0x0FFFFFFF); setStroke(maxOf(1, dp(1)), Hud.a(SOFT, 45)) }
        }
        /** Tile header: icon · title + chip · subtitle · optional trailing view. */
        fun tileHead(icon: View, title: String, chipV: View?, sub: String?, trail: View? = null): LinearLayout {
            val r = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            r.addView(icon)
            val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
            val top = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            top.addView(text(title, 16f, Color.WHITE, true))
            if (chipV != null) top.addView(chipV, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(8) })
            col.addView(top)
            if (sub != null) col.addView(text(sub, 12.5f, DIM))
            r.addView(col, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            if (trail != null) r.addView(trail)
            return r
        }
        /** Segmented meter (the striped bars) + percentage. */
        fun meter(label: String, pct: Int, color: Int, valueText: String = "$pct%"): LinearLayout {
            val r = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(5), 0, dp(5)) }
            r.addView(text(label, 12.5f, DIM), LinearLayout.LayoutParams(dp(84), WRAP_CONTENT))
            r.addView(SegBar(act, pct.coerceIn(0, 100) / 100f, color), LinearLayout.LayoutParams(0, dp(20), 1f))
            r.addView(text(valueText, 12.5f, Color.WHITE, true).apply { gravity = Gravity.END }, LinearLayout.LayoutParams(dp(74), WRAP_CONTENT))
            return r
        }
        /** Checklist row: dashed circle (or ✓), line of text, trailing time/count. */
        fun checkRow(done: Boolean, icon: android.graphics.Bitmap?, line: String, trail: String, onClick: (() -> Unit)?): LinearLayout {
            val r = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(7), 0, dp(7))
                if (onClick != null) setOnClickListener { SoundFX.play(act, "tick"); onClick() }
            }
            r.addView(TextView(act).apply {
                gravity = Gravity.CENTER; textSize = 11f; typeface = Typeface.DEFAULT_BOLD
                if (done) { text = "✓"; setTextColor(Color.BLACK); background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(HUD) } }
                else background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.TRANSPARENT)
                    setStroke(maxOf(1, dp(1)), FAINT, dpf(2.5f), dpf(2.5f)) }
            }, LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(10) })
            if (icon != null) r.addView(android.widget.ImageView(act).apply {
                setImageBitmap(icon)
                outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(v: View, o: android.graphics.Outline) = o.setRoundRect(0, 0, v.width, v.height, v.width * 0.28f)
                }; clipToOutline = true
            }, LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(10) })
            r.addView(text(line, 14f, Color.WHITE), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            r.addView(text(trail, 12.5f, DIM).apply { setPadding(dp(8), 0, 0, 0) })
            return r
        }
        fun divider() = View(act).apply { setBackgroundColor(Hud.a(SOFT, 40)) }
        val tiles = ArrayList<View>()
        val body = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        fun addTile(v: View) { tiles += v; body.addView(v, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(10) }) }
        fun caption(t: String) = body.addView(text("// " + t.uppercase(), 10.5f, SOFT).apply {
            typeface = Hud.monoBold; letterSpacing = 0.1f; setPadding(dp(4), dp(16), 0, 0) })

        // ---- Header: orb · "Gilbert" · menu + close
        val head = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), 0, 0, dp(2)) }
        head.addView(FrameLayout(act).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.BLACK); setStroke(maxOf(1, dp(1)), Hud.a(HUD, 170)) }
            addView(IslandFace(act).apply { configure(s) }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }, LinearLayout.LayoutParams(dp(46), dp(46)).apply { marginEnd = dp(12) })
        head.addView(LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            addView(text("GILBERT", 18f, Color.WHITE, true).apply { typeface = Hud.monoBold; letterSpacing = 0.22f })
            addView(text("ALLY · ${java.text.SimpleDateFormat("EEE, MMM d", java.util.Locale.getDefault()).format(java.util.Date()).uppercase()}", 9.5f, SOFT).apply {
                typeface = Hud.mono; letterSpacing = 0.08f })
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        if (openChat != null) head.addView(roundBtn("▤") { close(); openChat() }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(8) })
        head.addView(roundBtn("☰") { close(); openTools() }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(8) })
        head.addView(roundBtn("✕") { close() }, LinearLayout.LayoutParams(dp(40), dp(40)))
        card.addView(head)

        // ---- Status tile (like "Flux · Ready"): mood + two meters
        caption("Active now")
        val hc = heldCount()
        addTile(tile().apply {
            val (cTxt, cCol) = when {
                mentions.isNotEmpty() -> "Mentions" to RED
                hc > 0 -> "Holding $hc" to AMBER
                (h.tempC ?: 0f) >= 42f || (h.battery in 0..15 && !h.charging) -> "Check" to AMBER
                else -> "Ready" to GREEN
            }
            addView(tileHead(iconBox(null, "✦"), "Status", chip(cTxt, cCol), mood(act, h)))
            addView(divider(), LinearLayout.LayoutParams(MATCH_PARENT, maxOf(1, dp(1))).apply { topMargin = dp(10); bottomMargin = dp(4) })
            val bat = h.battery.coerceAtLeast(0)
            addView(meter("Battery", bat, if (bat <= 20) RED else if (bat <= 45) AMBER else GREEN, if (h.battery >= 0) "$bat%${if (h.charging) " ⚡" else ""}" else "—"))
            val calm = (100 - hc * 12).coerceIn(0, 100)
            addView(meter("Calm", calm, if (calm < 40) AMBER else GREEN))
        })

        // ---- Held notifications: expandable checklist (like the task list)
        caption("Held for you")
        addTile(tile().apply {
            val list = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
            val chev = roundBtn("⌃") {}.apply { textSize = 14f }
            var open = true
            chev.setOnClickListener {
                SoundFX.play(act, "tick"); open = !open
                list.visibility = if (open) View.VISIBLE else View.GONE
                chev.animate().rotation(if (open) 0f else 180f).setDuration(220).start()
            }
            addView(tileHead(iconBox(null, "📬"), "Notifications",
                chip(if (hc > 0) "$hc waiting" else "Clear", if (hc > 0) AMBER else GREEN),
                if (held.isEmpty()) "Nothing waiting — all quiet" else "${held.size} app${if (held.size > 1) "s" else ""} · tap one to see what it sent",
                chev.apply { layoutParams = LinearLayout.LayoutParams(dp(34), dp(34)) }))
            if (held.isNotEmpty()) {
                // The "1.5 of 4"-style progress bar: how much of the day's noise he kept away from you.
                val total = countsToday(act).sumOf { it.second }.coerceAtLeast(hc)
                list.addView(meter("Kept quiet", if (total > 0) hc * 100 / total else 0, AMBER, "$hc of $total"))
                for ((pkg, n) in held.entries.reversed()) {
                    val info = appInfo(pkg)
                    list.addView(checkRow(false, info?.first, info?.second ?: pkg, "$n", { close(); openApp(pkg) }))
                }
                list.addView(LinearLayout(act).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END; setPadding(0, dp(8), 0, 0)
                    addView(bigPill("◐ Open Inbox", AMBER) { close(); openInbox() })
                    addView(View(act), LinearLayout.LayoutParams(dp(8), 1))
                    addView(bigPill("!!! Clear", RED) { clearHeld(); close() })
                })
            }
            addView(list)
        })

        // ---- Mentions + notes
        if (mentions.isNotEmpty() || allNotes.isNotEmpty()) {
            caption("Notes")
            addTile(tile().apply {
                addView(tileHead(iconBox(null, "✎"), "Gilbert's notes",
                    if (mentions.isNotEmpty()) chip("${mentions.size} mention${if (mentions.size > 1) "s" else ""}", RED) else chip("Live", GREEN),
                    "Every message, one line each"))
                for (n in mentions) {
                    val info = appInfo(n.pkg)
                    addView(checkRow(false, info?.first, "@ ${n.title}: ${n.text}", ago(n.time), { close(); openApp(n.pkg) }))
                }
                for (n in allNotes.filter { !it.mention }.take(6)) {
                    val info = appInfo(n.pkg)
                    addView(checkRow(true, info?.first, listOf(n.title, n.text).filter { it.isNotBlank() }.joinToString(": "), ago(n.time), { close(); openApp(n.pkg) }))
                }
            })
        }

        // ---- Noisiest apps: chip cycles Normal → Priority → Quiet
        val counts = countsToday(act).take(4)
        if (counts.isNotEmpty()) {
            caption("Noisiest today")
            addTile(tile().apply {
                val max = counts.maxOf { it.second }.coerceAtLeast(1)
                counts.forEachIndexed { i, (pkg, n) ->
                    if (i > 0) addView(divider(), LinearLayout.LayoutParams(MATCH_PARENT, maxOf(1, dp(1))).apply { topMargin = dp(6); bottomMargin = dp(6) })
                    val info = appInfo(pkg)
                    fun label() = when { pkg in s.gilbertPriority -> "★ Priority"; pkg in s.gilbertQuiet -> "Quiet"; else -> "Normal" }
                    fun col() = when { pkg in s.gilbertPriority -> AMBER; pkg in s.gilbertQuiet -> 0xFF8E8E93.toInt(); else -> GREEN }
                    val c = chip(label(), col())
                    c.setOnClickListener {
                        SoundFX.play(act, "pop")
                        when {
                            pkg in s.gilbertPriority -> { s.gilbertPriority = s.gilbertPriority - pkg; s.gilbertQuiet = s.gilbertQuiet + pkg }
                            pkg in s.gilbertQuiet -> s.gilbertQuiet = s.gilbertQuiet - pkg
                            else -> s.gilbertPriority = s.gilbertPriority + pkg
                        }
                        c.text = label(); c.setTextColor(col())
                        (c.background as GradientDrawable).setColor((col() and 0x00FFFFFF) or 0x2E000000)
                        c.scaleX = 0.85f; c.scaleY = 0.85f; Motion.scaleTo(c, 1f, Motion.BOUNCY)
                    }
                    addView(tileHead(iconBox(info?.first, null, 36), info?.second ?: pkg, c, "$n notification${if (n > 1) "s" else ""} today"))
                    addView(SegBar(act, n / max.toFloat(), col()), LinearLayout.LayoutParams(MATCH_PARENT, dp(14)).apply { topMargin = dp(6) })
                }
            })
        }

        // ---- Phone check: segmented meters
        caption("Phone check")
        addTile(tile().apply {
            addView(tileHead(iconBox(null, "⚙︎"), "System", chip(if ((h.tempC ?: 0f) >= 42f) "Hot" else "Healthy",
                if ((h.tempC ?: 0f) >= 42f) RED else GREEN), "Battery, heat, storage and memory"))
            addView(divider(), LinearLayout.LayoutParams(MATCH_PARENT, maxOf(1, dp(1))).apply { topMargin = dp(10); bottomMargin = dp(4) })
            val t = h.tempC
            addView(meter("Temperature", t?.let { ((it - 20f) / 30f * 100).roundToInt() } ?: 0,
                if ((t ?: 0f) >= 42f) RED else if ((t ?: 0f) >= 37f) AMBER else GREEN, t?.let { "${it.roundToInt()}°C" } ?: "—"))
            val stPct = if (h.storageTotalGb > 0) (h.storageFreeGb / h.storageTotalGb * 100).roundToInt() else 0
            addView(meter("Storage free", stPct, if (stPct < 10) RED else if (stPct < 25) AMBER else GREEN,
                if (h.storageTotalGb > 0) "${"%.0f".format(h.storageFreeGb)} GB" else "—"))
            addView(meter("Memory free", h.ramFreePct.coerceAtLeast(0), if (h.ramFreePct in 0..15) AMBER else GREEN,
                if (h.ramFreePct >= 0) "${h.ramFreePct}%" else "—"))
        })
        body.addView(View(act), LinearLayout.LayoutParams(1, dp(4)))

        val scroll = object : ScrollView(act) {
            override fun onMeasure(w: Int, hs: Int) = super.onMeasure(w,
                View.MeasureSpec.makeMeasureSpec((act.resources.displayMetrics.heightPixels * 0.66f).toInt(), View.MeasureSpec.AT_MOST))
        }.apply { isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; addView(body) }
        card.addView(scroll)
        layer.setOnClickListener { close() }
        card.isClickable = true
        layer.addView(card, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP).apply {
            leftMargin = dp(12); rightMargin = dp(12); topMargin = dp(64)
        })
        var closing = false
        close = {
            if (!closing) {
                closing = true
                panelClose = null
                SoundFX.play(act, "close")
                card.animate().alpha(0f).scaleX(0.92f).scaleY(0.6f).translationY(-dp(24).toFloat()).setDuration(200)
                    .setInterpolator(android.view.animation.AccelerateInterpolator()).start()
                layer.animate().alpha(0f).setDuration(240).withEndAction { root.removeView(layer) }.start()
            }
        }
        // Clear out any card left behind (e.g. from an older open) before showing this one.
        for (i in root.childCount - 1 downTo 0) root.getChildAt(i).takeIf { it.tag == "gilbert_card" }?.let { root.removeView(it) }
        layer.tag = "gilbert_card"
        root.addView(layer, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        panelClose = close
        SoundFX.play(act, "open")
        // Grows out of the island at the top, then the tiles rise in one after another.
        layer.alpha = 0f; layer.animate().alpha(1f).setDuration(220).start()
        card.pivotX = act.resources.displayMetrics.widthPixels / 2f; card.pivotY = 0f
        card.scaleX = 0.3f; card.scaleY = 0.15f; card.alpha = 0f
        card.animate().alpha(1f).setDuration(200).start()
        card.play()   // HUD frame draws in + scan line
        Motion.scaleTo(card, 1f, Motion.FLUID)
        tiles.forEachIndexed { i, v ->
            v.alpha = 0f; v.translationY = dpf(26f)
            v.animate().alpha(1f).translationY(0f).setStartDelay(90L + i * 55L).setDuration(380)
                .setInterpolator(android.view.animation.DecelerateInterpolator(2.2f)).start()
        }
    }

    /** The striped meter: rounded vertical sticks, lit up to [frac] and growing in when shown. */
    class SegBar(ctx: Context, private val frac: Float, private val color: Int) : View(ctx) {
        private val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        private var shown = 0f
        private val rect = android.graphics.RectF()
        init {
            android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 700; startDelay = 250; interpolator = android.view.animation.DecelerateInterpolator(1.8f)
                addUpdateListener { shown = it.animatedValue as Float; invalidate() }
            }.start()
        }
        override fun onDraw(c: android.graphics.Canvas) {
            val d = resources.displayMetrics.density
            val w = 3.2f * d; val gap = 3f * d
            val n = ((width + gap) / (w + gap)).toInt().coerceAtLeast(1)
            val lit = (n * frac * shown).roundToInt()
            for (i in 0 until n) {
                val x = i * (w + gap)
                rect.set(x, 0f, x + w, height.toFloat())
                p.color = if (i < lit) color else 0x2EFFFFFF
                c.drawRoundRect(rect, w / 2, w / 2, p)
            }
        }
    }
}
