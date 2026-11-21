package com.luncher.home

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * Pocher's console. He doesn't chat — he gives you data, split into windows. Each window is
 * one of his programs:
 *   01 DIGEST  – today's summary (counts, top chats, by app, a written digest with the brain)
 *   02 CHATS   – every conversation, tap to read it
 *   03 NAMES   – who messages you, ranked
 *   04 NOTES   – your notes + things he flagged (mentions, dates, tasks)
 *   05 FILES   – photos & attachments from chats, scanned (text + what's in them)
 *   06 SYSTEM  – his brain and storage
 */
object PocherConsole {
    const val REQ_SCAN = 5141

    private class Win(val num: String, val prog: String, val tab: String, val build: (LinearLayout) -> Unit)

    private var act: Activity? = null
    private var content: LinearLayout? = null
    private var winTitle: TextView? = null
    private var winInfo: TextView? = null
    private var tabs = ArrayList<TextView>()
    private var wins = emptyList<Win>()
    private var current = 0
    private var openChat: String? = null
    private var tint = 0xFF8E8E93.toInt()
    private var digestText: String? = null

    private fun dp(v: Int) = (v * (act?.resources?.displayMetrics?.density ?: 3f)).roundToInt()
    private val mono: Typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
    private val monoBold: Typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)

    fun show(a: Activity, root: FrameLayout, s: Settings, onClosed: (() -> Unit)? = null) {
        act = a
        tint = if (ThemeEngine.colorful) ThemeEngine.accent else 0xFF8E8E93.toInt()
        digestText = null; openChat = null
        val layer = FrameLayout(a).apply { setBackgroundColor(Color.argb(160, 0, 0, 0)); isClickable = true }
        val card = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(
                IconFactory.blend(0xFF2E2E32.toInt(), tint, 0.16f), IconFactory.blend(0xFF111113.toInt(), tint, 0.06f))).apply {
                cornerRadius = dp(28).toFloat(); setStroke(maxOf(1, dp(1)), ThemeEngine.alpha(tint, 100)) }
            elevation = dp(16).toFloat(); isClickable = true
        }
        lateinit var close: () -> Unit
        // ---- Header: face · Pocher · close
        card.addView(LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(FrameLayout(a).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.BLACK); setStroke(dp(2), ThemeEngine.alpha(tint, 170)) }
                addView(IslandFace(a).apply { configure(s) }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            }, LinearLayout.LayoutParams(dp(38), dp(38)).apply { marginEnd = dp(10) })
            addView(LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(a).apply { text = "Pocher"; textSize = 18f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD })
                addView(TextView(a).apply {
                    text = "DATA CONSOLE · ${if (GilbertBrain.installed(a)) "BRAIN ON" else "MEMORY MODE"} · LOCAL"
                    textSize = 10.5f; typeface = mono; letterSpacing = 0.06f; setTextColor(ThemeEngine.alpha(IconFactory.blend(tint, Color.WHITE, 0.5f), 200))
                })
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(TextView(a).apply {
                text = "✕"; textSize = 14f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x33FFFFFF) }
                setOnClickListener { close() }
            }, LinearLayout.LayoutParams(dp(34), dp(34)))
        })

        wins = listOf(
            Win("01", "DIGEST", "◉ Summary") { buildSummary(it) },
            Win("02", "CHAT.LOG", "💬 Chats") { buildChats(it) },
            Win("03", "PEOPLE.IDX", "👤 Names") { buildNames(it) },
            Win("04", "NOTES", "📝 Notes") { buildNotes(it) },
            Win("05", "SCAN", "🖼 Files") { buildFiles(it) },
            Win("06", "SYSTEM", "🧠 System") { buildSystem(it, s) })

        // ---- Window tabs
        tabs = ArrayList()
        val strip = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(12), 0, dp(10)) }
        wins.forEachIndexed { i, w ->
            val t = TextView(a).apply {
                text = w.tab; textSize = 13f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
                setPadding(dp(13), dp(7), dp(13), dp(7))
                setOnClickListener { SoundFX.play(a, "tick"); select(i) }
            }
            tabs += t
            strip.addView(t, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = dp(6) })
        }
        card.addView(HorizontalScrollView(a).apply { isHorizontalScrollBarEnabled = false; addView(strip) })

        // ---- The window
        val frame = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(0x55000000); setStroke(maxOf(1, dp(1)), 0x22FFFFFF) }
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        val bar = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        winTitle = TextView(a).apply { textSize = 11f; typeface = monoBold; letterSpacing = 0.08f; setTextColor(IconFactory.blend(tint, Color.WHITE, 0.45f)) }
        winInfo = TextView(a).apply { textSize = 10.5f; typeface = mono; setTextColor(0x80FFFFFF.toInt()); gravity = Gravity.END }
        bar.addView(winTitle, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        bar.addView(winInfo)
        frame.addView(bar)
        frame.addView(View(a).apply { setBackgroundColor(0x22FFFFFF) }, LinearLayout.LayoutParams(MATCH_PARENT, maxOf(1, dp(1))).apply { topMargin = dp(8); bottomMargin = dp(6) })
        val body = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        content = body
        val h = (a.resources.displayMetrics.heightPixels * 0.58f).toInt()
        frame.addView(ScrollView(a).apply { isVerticalScrollBarEnabled = false; addView(body) }, LinearLayout.LayoutParams(MATCH_PARENT, h))
        card.addView(frame)

        layer.addView(card, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP).apply {
            leftMargin = dp(12); rightMargin = dp(12); topMargin = dp(52) })
        root.addView(layer, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        close = {
            PocherFiles.onChanged = null
            layer.animate().alpha(0f).setDuration(160).withEndAction { root.removeView(layer); onClosed?.invoke() }.start()
        }
        layer.setOnClickListener { close() }
        card.alpha = 0f; card.translationY = -dp(20).toFloat()
        card.animate().alpha(1f).translationY(0f).setDuration(220).start()
        PocherFiles.onChanged = { if (current == 4) select(4, animate = false) }
        select(0, animate = false)
    }

    private fun select(i: Int, animate: Boolean = true) {
        val a = act ?: return
        current = i
        tabs.forEachIndexed { k, t ->
            t.background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(if (k == i) ThemeEngine.alpha(tint, 150) else 0x1FFFFFFF) }
            t.typeface = if (k == i) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        val w = wins[i]
        winTitle?.text = "WIN ${w.num} · ${w.prog}"
        winInfo?.text = "updated " + java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
        val body = content ?: return
        body.removeAllViews()
        runCatching { w.build(body) }.onFailure { line(body, "⚠️ This window couldn't load: ${it.message?.take(80)}", 13f, 0xFFFF9F0A.toInt()) }
        if (animate) { body.alpha = 0f; body.translationX = dp(14).toFloat(); body.animate().alpha(1f).translationX(0f).setDuration(180).start() }
        tabs.getOrNull(i)?.let { t -> (t.parent?.parent as? HorizontalScrollView)?.smoothScrollTo(maxOf(0, t.left - dp(40)), 0) }
    }

    // ------------------------------------------------------------------ small building blocks
    private fun line(p: LinearLayout, t: CharSequence, size: Float = 14f, color: Int = Color.WHITE, bold: Boolean = false, top: Int = 0): TextView =
        TextView(act).apply {
            text = t; textSize = size; setTextColor(color); if (bold) typeface = Typeface.DEFAULT_BOLD
            setLineSpacing(0f, 1.1f)
        }.also { p.addView(it, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(top) }) }

    private fun section(p: LinearLayout, t: String) {
        TextView(act).apply { text = t; textSize = 10.5f; typeface = monoBold; letterSpacing = 0.1f; setTextColor(0x99FFFFFF.toInt()) }
            .also { p.addView(it, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(14); bottomMargin = dp(6) }) }
    }

    private fun box(p: LinearLayout, top: Int = 6): LinearLayout = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(0x14FFFFFF) }
        setPadding(dp(12), dp(10), dp(12), dp(10))
    }.also { p.addView(it, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(top) }) }

    private fun empty(p: LinearLayout, t: String) = line(p, t, 13.5f, 0x99FFFFFF.toInt(), top = 10).apply { gravity = Gravity.CENTER }

    private fun avatar(name: String): TextView = TextView(act).apply {
        text = name.trim().firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "•"
        textSize = 15f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        val hue = (name.hashCode() and 0x7fffffff) % 360
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL
            setColor(IconFactory.blend(Color.HSVToColor(floatArrayOf(hue.toFloat(), 0.45f, 0.6f)), tint, 0.25f)) }
    }

    private fun pill(p: LinearLayout, t: String, color: Int = 0xFF0A84FF.toInt(), fn: () -> Unit): TextView = TextView(act).apply {
        text = t; textSize = 13.5f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        setPadding(dp(16), dp(9), dp(16), dp(9))
        background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(color) }
        setOnClickListener { SoundFX.play(act!!, "tick"); fn() }
    }.also { p.addView(it, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(8) }) }

    // ------------------------------------------------------------------ data
    private val STOP = setOf("the", "and", "you", "your", "for", "that", "this", "with", "are", "was", "have", "has", "not", "but",
        "its", "it's", "just", "what", "when", "can", "all", "too", "lol", "haha", "hahaha", "okay", "ang", "mga", "naman", "lang",
        "yung", "ako", "ikaw", "sa", "na", "ng", "ko", "mo", "ka", "ba", "pa", "din", "rin", "kasi", "nga", "message", "messages",
        "sent", "photo", "new", "from", "will", "like", "get", "got", "now", "then", "there", "they", "them", "who", "how", "why",
        "kayo", "siya", "kami", "tayo", "wala", "meron", "dito", "doon", "yan", "yun", "ito", "nyo", "niyo", "talaga", "sige")

    private fun messages(days: Int): List<GilbertArchive.Entry> {
        val a = act ?: return emptyList()
        return GilbertArchive.entries(a, days).filter { e ->
            e.who.isNotBlank() && !GilbertTalk.isMe(a, e.who) &&
                !Regex("\\.(apk|task|pdf|zip|jpg|png|mp4|mp3|docx?)\\b", RegexOption.IGNORE_CASE).containsMatchIn(e.who) &&
                !e.app.contains("System", true) && !e.app.contains("Download", true) && !e.app.contains("Play Store", true)
        }
    }

    private fun keywords(texts: List<String>, n: Int = 3): List<String> =
        texts.flatMap { it.lowercase().split(Regex("[^\\p{L}\\p{N}']+")) }
            .filter { it.length >= 4 && it !in STOP && !it.all { c -> c.isDigit() } }
            .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(n).map { it.key }

    private val FLAG = Regex("\\b(tomorrow|tonight|later|deadline|due|exam|quiz|meeting|class|schedule|bukas|mamaya|pay|bayad|send|submit|call me|asap|urgent|\\d{1,2}(:\\d{2})?\\s?(am|pm))\\b", RegexOption.IGNORE_CASE)

    // ------------------------------------------------------------------ 01 DIGEST
    private fun buildSummary(p: LinearLayout) {
        val a = act ?: return
        val today = messages(1).filter { it.today }
        val files = PocherFiles.list(a).filter { PocherFiles.timeLabel(it.time).length <= 5 }
        val mentions = Gilbert.notes(a).count { it.mention && android.text.format.DateUtils.isToday(it.time) }
        // Tiles
        val tiles = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        fun tile(n: String, cap: String) = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(0x14FFFFFF) }
            setPadding(0, dp(10), 0, dp(10))
            addView(TextView(a).apply { text = n; textSize = 22f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER })
            addView(TextView(a).apply { text = cap; textSize = 9.5f; typeface = mono; letterSpacing = 0.05f; setTextColor(0x99FFFFFF.toInt()); gravity = Gravity.CENTER })
        }.also { tiles.addView(it, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = dp(6) }) }
        tile("${today.size}", "MESSAGES")
        tile("${today.map { it.who }.distinct().size}", "PEOPLE")
        tile("$mentions", "MENTIONS")
        tile("${files.size}", "FILES")
        p.addView(tiles)

        section(p, "TOP CHATS · TODAY")
        val byWho = today.groupBy { it.who }.entries.sortedByDescending { it.value.size }.take(5)
        if (byWho.isEmpty()) empty(p, "No messages yet today.")
        for ((who, list) in byWho) {
            val b = box(p, 4)
            val kw = keywords(list.map { it.text })
            b.addView(TextView(a).apply { text = "$who  ·  ${list.size}"; textSize = 14.5f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD })
            b.addView(TextView(a).apply {
                text = "${list.first().app} · last ${list.first().time}" + if (kw.isNotEmpty()) " · about: ${kw.joinToString(", ")}" else ""
                textSize = 12f; setTextColor(0x99FFFFFF.toInt()) })
            b.addView(TextView(a).apply { text = "“${list.first().text.take(90)}”"; textSize = 13f; setTextColor(0xCCFFFFFF.toInt()); maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
            b.setOnClickListener { openChat = who; select(1) }
        }

        section(p, "BY APP")
        val byApp = today.groupingBy { it.app }.eachCount().entries.sortedByDescending { it.value }
        if (byApp.isEmpty()) empty(p, "—")
        val max = byApp.firstOrNull()?.value ?: 1
        for ((app, n) in byApp.take(6)) bar(p, app, n, max)

        section(p, "WRITTEN DIGEST")
        val d = box(p, 2)
        val dt = TextView(a).apply { textSize = 13.5f; setTextColor(Color.WHITE); setLineSpacing(0f, 1.15f) }
        d.addView(dt)
        when {
            digestText != null -> dt.text = digestText
            !GilbertBrain.installed(a) -> dt.text = "Add a brain in 🧠 System and Pocher writes a short digest of your messages here."
            else -> {
                dt.text = "Pocher can read today's messages and write the key points."
                pill(d, "⚡ Make digest") {
                    if (today.isEmpty()) { dt.text = "Nothing to digest yet."; return@pill }
                    dt.text = "PROCESSING ${today.size} MESSAGES…"; dt.typeface = mono
                    GilbertBrain.digest(a, today.take(40).reversed().map { "${it.time} ${it.who}: ${it.text}" }) { r ->
                        digestText = r ?: ("Couldn't write the digest — " + (GilbertBrain.lastError ?: "the brain didn't answer") + ".")
                        if (current == 0) select(0, animate = false)
                    }
                }
            }
        }

        section(p, "PHONE")
        runCatching { Gilbert.health(a) }.getOrNull()?.let { h ->
            line(p, "Battery ${h.battery}%${if (h.charging) " ⚡" else ""} · ${h.tempC?.roundToInt() ?: "?"}°C · " +
                "${"%.0f".format(h.storageFreeGb)} GB free · RAM ${h.ramFreePct}% free", 12.5f, 0xCCFFFFFF.toInt()).typeface = mono
        }
        if (Gilbert.heldCount() > 0) line(p, "Holding ${Gilbert.heldCount()} quiet notification(s)", 12.5f, 0xCCFFFFFF.toInt(), top = 4).typeface = mono
    }

    private fun bar(p: LinearLayout, name: String, n: Int, max: Int, onClick: (() -> Unit)? = null) {
        val a = act ?: return
        val row = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(3), 0, dp(5)) }
        val top = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        top.addView(TextView(a).apply { text = name; textSize = 13.5f; setTextColor(Color.WHITE); maxLines = 1; ellipsize = TextUtils.TruncateAt.END },
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        top.addView(TextView(a).apply { text = "$n"; textSize = 12.5f; typeface = monoBold; setTextColor(0xCCFFFFFF.toInt()) })
        row.addView(top)
        val track = FrameLayout(a).apply { background = GradientDrawable().apply { cornerRadius = dp(3).toFloat(); setColor(0x1FFFFFFF) } }
        val fill = View(a).apply { background = GradientDrawable().apply { cornerRadius = dp(3).toFloat(); setColor(IconFactory.blend(tint, Color.WHITE, 0.25f)) } }
        track.addView(fill, FrameLayout.LayoutParams(0, MATCH_PARENT))
        row.addView(track, LinearLayout.LayoutParams(MATCH_PARENT, dp(5)).apply { topMargin = dp(4) })
        track.post { fill.layoutParams = fill.layoutParams.apply { width = (track.width * n / max.coerceAtLeast(1).toFloat()).toInt().coerceAtLeast(dp(4)) }; fill.requestLayout() }
        onClick?.let { c -> row.setOnClickListener { c() } }
        p.addView(row)
    }

    // ------------------------------------------------------------------ 02 CHATS
    private fun buildChats(p: LinearLayout) {
        val a = act ?: return
        val all = messages(3)
        val convos = all.groupBy { it.who }.entries.sortedByDescending { it.value.first().day + it.value.first().time }
        winInfo?.text = "${convos.size} chats · ${all.size} msgs"
        if (convos.isEmpty()) { empty(p, "No chats saved yet. They appear here as messages come in."); return }
        for ((who, list) in convos.take(40)) {
            val b = box(p, 6)
            val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(avatar(who), LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(10) })
            val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
            col.addView(TextView(a).apply { text = who; textSize = 14.5f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD; maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
            col.addView(TextView(a).apply { text = list.first().text; textSize = 12.5f; setTextColor(0xB3FFFFFF.toInt()); maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
            row.addView(col, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            row.addView(TextView(a).apply {
                text = "${list.first().stamp}\n${list.size} · ${list.first().app}"; textSize = 10.5f; typeface = mono
                setTextColor(0x80FFFFFF.toInt()); gravity = Gravity.END })
            b.addView(row)
            val log = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(46), dp(8), 0, 0) }
            fun fill() {
                log.removeAllViews()
                for (e in list.take(30).reversed()) {
                    log.addView(TextView(a).apply {
                        text = "${e.stamp}  ${e.text}"; textSize = 13f; setTextColor(0xE6FFFFFF.toInt()); setLineSpacing(0f, 1.08f)
                        setPadding(0, dp(2), 0, dp(2))
                    })
                }
                val kw = keywords(list.map { it.text }, 4)
                if (kw.isNotEmpty()) log.addView(TextView(a).apply {
                    text = "TOPICS: ${kw.joinToString(" · ")}"; textSize = 10.5f; typeface = mono; setTextColor(IconFactory.blend(tint, Color.WHITE, 0.5f))
                    setPadding(0, dp(6), 0, 0) })
            }
            log.visibility = View.GONE
            b.addView(log)
            if (who == openChat) { fill(); log.visibility = View.VISIBLE; b.post { (p.parent as? ScrollView)?.smoothScrollTo(0, b.top) } }
            b.setOnClickListener {
                if (log.visibility == View.VISIBLE) log.visibility = View.GONE else { fill(); log.visibility = View.VISIBLE }
            }
        }
        openChat = null
    }

    // ------------------------------------------------------------------ 03 NAMES
    private fun buildNames(p: LinearLayout) {
        val a = act ?: return
        val week = messages(7)
        val people = week.groupBy { it.who }.entries.sortedByDescending { it.value.size }
        winInfo?.text = "${people.size} names · 7 days"
        if (people.isEmpty()) { empty(p, "No names yet."); return }
        val max = people.first().value.size
        for ((who, list) in people.take(30)) {
            val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(4), 0, dp(4)) }
            row.addView(avatar(who), LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginEnd = dp(10) })
            val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
            bar(col, who, list.size, max)
            val apps = list.map { it.app }.distinct().take(3).joinToString(", ")
            val today = list.count { it.today }
            col.addView(TextView(a).apply {
                text = "$apps · today $today · last ${list.first().stamp}"; textSize = 10.5f; typeface = mono; setTextColor(0x80FFFFFF.toInt()) })
            row.addView(col, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            row.setOnClickListener { openChat = who; select(1) }
            p.addView(row)
        }
    }

    // ------------------------------------------------------------------ 04 NOTES
    private fun buildNotes(p: LinearLayout) {
        val a = act ?: return
        val inputRow = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val input = EditText(a).apply {
            hint = "Write a note…"; setHintTextColor(0x80FFFFFF.toInt()); setTextColor(Color.WHITE); textSize = 14.5f
            background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(0x22FFFFFF) }
            setPadding(dp(14), dp(9), dp(14), dp(9)); imeOptions = EditorInfo.IME_ACTION_DONE; isSingleLine = true
        }
        inputRow.addView(input, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        fun add() {
            val t = input.text.toString().trim()
            if (t.isNotEmpty()) { GilbertMemory.addFact(a, t); input.setText(""); SoundFX.play(a, "success"); select(3, animate = false) }
        }
        inputRow.addView(TextView(a).apply {
            text = "＋"; textSize = 18f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFF0A84FF.toInt()) }
            setOnClickListener { add() }
        }, LinearLayout.LayoutParams(dp(38), dp(38)).apply { marginStart = dp(8) })
        input.setOnEditorActionListener { _, _, _ -> add(); true }
        p.addView(inputRow)

        section(p, "YOUR NOTES")
        val facts = GilbertMemory.facts(a)
        if (facts.isEmpty()) empty(p, "No notes yet.")
        for (f in facts) {
            val b = box(p, 4)
            val r = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            r.addView(TextView(a).apply { text = f; textSize = 14f; setTextColor(Color.WHITE) }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            r.addView(TextView(a).apply {
                text = "✕"; textSize = 13f; setTextColor(0x99FFFFFF.toInt()); setPadding(dp(10), dp(4), dp(4), dp(4))
                setOnClickListener { GilbertMemory.removeFact(a, f); select(3, animate = false) }
            })
            b.addView(r)
        }

        section(p, "FLAGGED BY POCHER")
        val mentions = Gilbert.notes(a).filter { it.mention }.take(5)
        val flagged = messages(3).filter { FLAG.containsMatchIn(it.text) }.take(12)
        if (mentions.isEmpty() && flagged.isEmpty()) empty(p, "Nothing flagged — no mentions, dates or tasks found.")
        for (n in mentions) {
            val b = box(p, 4)
            b.addView(TextView(a).apply { text = "@ MENTION · ${n.title}"; textSize = 10.5f; typeface = monoBold; setTextColor(0xFFFF9F0A.toInt()) })
            b.addView(TextView(a).apply { text = n.text.take(140); textSize = 13.5f; setTextColor(Color.WHITE) })
        }
        for (e in flagged) {
            val b = box(p, 4)
            val hit = FLAG.find(e.text)?.value?.uppercase() ?: ""
            b.addView(TextView(a).apply { text = "⚑ $hit · ${e.who} · ${e.stamp}"; textSize = 10.5f; typeface = monoBold; setTextColor(IconFactory.blend(tint, Color.WHITE, 0.4f)) })
            b.addView(TextView(a).apply { text = e.text.take(160); textSize = 13.5f; setTextColor(Color.WHITE) })
            b.setOnClickListener { openChat = e.who; select(1) }
        }
    }

    // ------------------------------------------------------------------ 05 FILES
    private fun buildFiles(p: LinearLayout) {
        val a = act ?: return
        val items = PocherFiles.list(a)
        winInfo?.text = "${items.size} files"
        val top = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        p.addView(top)
        pill(top, "⊕ Scan an image") {
            val i = android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(android.content.Intent.CATEGORY_OPENABLE).setType("image/*")
            runCatching { a.startActivityForResult(i, REQ_SCAN) }
        }
        line(p, "Photos and attachments from your chats are kept and scanned here: the text inside them and what's in them.",
            11.5f, 0x80FFFFFF.toInt(), top = 6)
        if (items.isEmpty()) { empty(p, "No files yet."); return }
        val px = dp(64)
        for (it in items.take(60)) { // it = one file
            val b = box(p, 6)
            val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
            val thumb = PocherFiles.thumb(a, it, px)
            if (thumb != null) row.addView(ImageView(a).apply {
                setImageBitmap(thumb); scaleType = ImageView.ScaleType.CENTER_CROP
                clipToOutline = true; outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
                background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(0x22FFFFFF) }
            }, LinearLayout.LayoutParams(px, px).apply { marginEnd = dp(10) })
            else row.addView(TextView(a).apply {
                text = when { it.kind.startsWith("video") -> "🎬"; it.kind.startsWith("audio") -> "🎤"; it.kind.contains("sticker") -> "🙂"; it.kind.startsWith("image") -> "📷"; else -> "📄" }
                textSize = 24f; gravity = Gravity.CENTER
                background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(0x22FFFFFF) }
            }, LinearLayout.LayoutParams(px, px).apply { marginEnd = dp(10) })
            val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
            col.addView(TextView(a).apply { text = it.who; textSize = 14f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD; maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
            val kind = when { it.kind.startsWith("video") -> "VIDEO"; it.kind.startsWith("audio") -> "VOICE"; it.kind.startsWith("image") -> "IMAGE"; it.kind.contains("sticker") -> "STICKER"; else -> "FILE" }
            col.addView(TextView(a).apply { text = "$kind · ${it.app} · ${PocherFiles.timeLabel(it.time)}"; textSize = 10.5f; typeface = mono; setTextColor(0x80FFFFFF.toInt()) })
            val scanned = it.img != null
            val status = when {
                !scanned -> it.msg.ifBlank { "Attachment (the app keeps the file)" }
                it.ocr.isEmpty() && it.labels.isEmpty() -> "SCANNING…"
                else -> null
            }
            if (status != null) col.addView(TextView(a).apply { text = status; textSize = 12.5f; setTextColor(0xB3FFFFFF.toInt()); maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
            if (scanned && it.labels.isNotEmpty() && it.labels != "—")
                col.addView(TextView(a).apply { text = "SEEN: ${it.labels}"; textSize = 11f; typeface = mono; setTextColor(IconFactory.blend(tint, Color.WHITE, 0.5f)) })
            val ocr = it.ocr.takeIf { o -> o.isNotEmpty() && o != "—" }
            val ocrView = ocr?.let { o -> TextView(a).apply {
                text = "TEXT: " + o.replace('\n', ' '); textSize = 12.5f; setTextColor(0xE6FFFFFF.toInt()); maxLines = 3; ellipsize = TextUtils.TruncateAt.END } }
            ocrView?.let { v -> col.addView(v) }
            if (scanned && it.ocr == "—" && it.labels == "—") col.addView(TextView(a).apply { text = "No text found"; textSize = 12f; setTextColor(0x80FFFFFF.toInt()) })
            row.addView(col, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            b.addView(row)
            // Tap: show the full picture and all the text.
            val big = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE; setPadding(0, dp(8), 0, 0) }
            b.addView(big)
            val item = it
            b.setOnClickListener {
                if (big.visibility == View.VISIBLE) { big.visibility = View.GONE; ocrView?.maxLines = 3; return@setOnClickListener }
                big.removeAllViews()
                PocherFiles.thumb(a, item, dp(320))?.let { bm ->
                    big.addView(ImageView(a).apply { setImageBitmap(bm); adjustViewBounds = true
                        clipToOutline = true; outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
                        background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(0x22FFFFFF) } },
                        LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
                }
                ocrView?.maxLines = Int.MAX_VALUE
                big.visibility = View.VISIBLE
            }
            b.setOnLongClickListener {
                ocr?.let { o ->
                    (a.getSystemService(android.content.ClipboardManager::class.java))
                        ?.setPrimaryClip(android.content.ClipData.newPlainText("Scanned text", o))
                    android.widget.Toast.makeText(a, "Scanned text copied", android.widget.Toast.LENGTH_SHORT).show()
                }
                true
            }
        }
    }

    fun onScanPicked(a: Activity, uri: android.net.Uri) {
        android.widget.Toast.makeText(a, "Scanning…", android.widget.Toast.LENGTH_SHORT).show()
        PocherFiles.scanPicked(a, uri) { ok ->
            if (!ok) android.widget.Toast.makeText(a, "Couldn't open that image", android.widget.Toast.LENGTH_SHORT).show()
            select(4, animate = false)
        }
    }

    // ------------------------------------------------------------------ 06 SYSTEM
    private fun buildSystem(p: LinearLayout, s: Settings) {
        val a = act ?: return
        val room = GilbertBrainRoom(a, s) {
            val i = android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(android.content.Intent.CATEGORY_OPENABLE).setType("*/*")
            runCatching { a.startActivityForResult(i, GilbertChat.REQ_BRAIN) }
        }
        GilbertChat.brainRoom = room
        p.addView(room.view)
        section(p, "STORAGE")
        val files = PocherFiles.list(a)
        line(p, "Archive: ${GilbertArchive.count(a)} messages · ${GilbertArchive.days(a).size} days\n" +
            "Files: ${files.size} (${files.count { it.img != null }} images scanned)\n" +
            "Notes: ${GilbertMemory.facts(a).size}\n" +
            "Folder: Android/data/…/files/Gilbert Archive", 12f, 0xCCFFFFFF.toInt()).typeface = mono
        if (GilbertBrain.installed(a) && !GilbertBrain.loaded && !GilbertBrain.crashed(a)) room.calibrate()
    }
}
