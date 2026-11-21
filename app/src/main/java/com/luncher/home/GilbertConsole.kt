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
 * Gilbert's console (HUD). He doesn't chat — he gives you data, split into windows. Each window is
 * one of his programs:
 *   01 DIGEST  – today's summary (counts, top chats, by app, a written digest with the brain)
 *   02 CHATS   – every conversation, tap to read it
 *   03 NAMES   – who messages you, ranked
 *   04 NOTES   – your notes + things he flagged (mentions, dates, tasks)
 *   05 FILES   – photos & attachments from chats, scanned (text + what's in them)
 *   06 SYSTEM  – his brain and storage
 */
object GilbertConsole {
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

    private var rings: HudRings? = null
    private var face: IslandFace? = null
    private var ticker: TextView? = null
    private var frame: HudFrame? = null
    private var flashWho: String? = null
    private var prevHeld: (() -> Unit)? = null
    /** Which window to open first (e.g. 5 = Files after sharing a photo to Gilbert). */
    var startWin = 0
    const val REQ_PHOTOS = 5142

    fun show(a: Activity, root: FrameLayout, s: Settings, onClosed: (() -> Unit)? = null) {
        act = a
        tint = Hud.CYAN
        digestText = null; openChat = null; flashWho = null
        val sw = a.resources.displayMetrics.widthPixels
        val sh = a.resources.displayMetrics.heightPixels
        // Full-screen HUD on black.
        val layer = FrameLayout(a).apply { setBackgroundColor(Hud.bg(240)); isClickable = true }
        val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(62), dp(14), dp(18)) }
        lateinit var close: () -> Unit

        // ---- Top bar: name · status · close
        col.addView(LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(a).apply { text = "GILBERT"; textSize = 17f; setTextColor(Color.WHITE); typeface = monoBold; letterSpacing = 0.25f })
                addView(TextView(a).apply {
                    text = "DATA SYSTEM · ${if (GilbertBrain.installed(a)) "BRAIN ON" else "MEMORY MODE"} · LOCAL"
                    textSize = 9.5f; typeface = mono; letterSpacing = 0.08f; setTextColor(Hud.a(Hud.SOFT, 190))
                })
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            // Mute: shows while he talks (and while he's muted, to turn him back on).
            val mb = TextView(a).apply {
                textSize = 14f; gravity = Gravity.CENTER
                setOnClickListener { SoundFX.play(a, "tick"); toggleMute() }
            }
            muteBtn = mb
            addView(mb, LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginEnd = dp(8) })
            addView(TextView(a).apply {
                text = "✕"; textSize = 14f; setTextColor(Hud.SOFT); gravity = Gravity.CENTER
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x14FFFFFF); setStroke(maxOf(1, dp(1)), Hud.a(Hud.CYAN, 120)) }
                setOnClickListener { close() }
            }, LinearLayout.LayoutParams(dp(34), dp(34)))
        })

        // ---- The HUD: rings + brackets + his globe face in the middle
        val hudH = (sh * 0.24f).toInt().coerceIn(dp(150), dp(230))
        val hud = FrameLayout(a)
        val rg = HudRings(a); rings = rg
        hud.addView(rg, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        val fc = IslandFace(a).apply { configure(s) }; face = fc
        val fs = (hudH * 0.58f).toInt()
        hud.addView(fc, FrameLayout.LayoutParams(fs, fs, Gravity.CENTER))
        // Tap his globe: hush him if he's talking, otherwise he tells you about this window.
        fc.setOnClickListener {
            SoundFX.play(a, "tick"); rg.ping()
            when {
                GilbertNarrator.speaking() -> GilbertNarrator.stop()
                !s.gilbertSpeaks -> { type("VOICE MUTED · TAP 🔇 TO TURN ON"); face?.react("sleepy", 1200) }
                else -> {
                    val u = updates()
                    val (talk, why) = GilbertMind.decideToSpeak(a, u.any { it.level == 3 }, !briefedThisOpen && u.isNotEmpty())
                    if (!talk) { type("GILBERT · $why"); face?.react(GilbertMind.faceMood(a), 1500) }
                    else {
                        GilbertMind.spoke()
                        if (!briefedThisOpen) brief() else chatter()?.let { GilbertNarrator.say(a, it) }
                    }
                }
            }
        }
        // Tapping anywhere on the HUD does the same.
        rg.setOnClickListener { fc.performClick() }
        col.addView(hud, LinearLayout.LayoutParams(MATCH_PARENT, hudH).apply { topMargin = dp(4) })
        // ticker: what he's doing / what just came in
        ticker = TextView(a).apply {
            textSize = 10.5f; typeface = mono; letterSpacing = 0.06f; setTextColor(Hud.SOFT); gravity = Gravity.CENTER
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }
        col.addView(ticker, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(2) })

        wins = listOf(
            Win("00", "MIND", "MIND") { buildMind(it) },
            Win("01", "NOW", "NOW") { buildNow(it, s) },
            Win("02", "DIGEST", "SUMMARY") { buildSummary(it) },
            Win("03", "CHAT.LOG", "CHATS") { buildChats(it) },
            Win("04", "PEOPLE.IDX", "NAMES") { buildNames(it) },
            Win("05", "NOTES", "NOTES") { buildNotes(it) },
            Win("06", "SCAN", "FILES") { buildFiles(it) },
            Win("07", "SYSTEM", "SYSTEM") { buildSystem(it, s) })

        // ---- Window tabs (HUD labels)
        tabs = ArrayList()
        val strip = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(10), 0, dp(8)) }
        wins.forEachIndexed { i, w ->
            val t = TextView(a).apply {
                text = "${w.num} ${w.tab}"; textSize = 11.5f; typeface = monoBold; letterSpacing = 0.06f; gravity = Gravity.CENTER
                setPadding(dp(11), dp(7), dp(11), dp(7))
                setOnClickListener {
                    SoundFX.play(a, "tick")
                    val now = System.currentTimeMillis(); if (lastTabTap > 0) GilbertMind.recordTap(a, now - lastTabTap); lastTabTap = now
                    select(i)
                }
            }
            tabs += t
            strip.addView(t, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = dp(6) })
        }
        col.addView(HorizontalScrollView(a).apply { isHorizontalScrollBarEnabled = false; addView(strip) })

        // ---- The window (HUD panel)
        val fr = HudFrame(a).apply { setPadding(dp(12), dp(10), dp(12), dp(10)) }; frame = fr
        val bar = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        winTitle = TextView(a).apply { textSize = 11f; typeface = monoBold; letterSpacing = 0.1f; setTextColor(Hud.CYAN) }
        winInfo = TextView(a).apply { textSize = 10f; typeface = mono; setTextColor(0x99FFFFFF.toInt()); gravity = Gravity.END }
        bar.addView(winTitle, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        bar.addView(winInfo)
        fr.addView(bar)
        fr.addView(View(a).apply { setBackgroundColor(Hud.a(Hud.SOFT, 50)) }, LinearLayout.LayoutParams(MATCH_PARENT, maxOf(1, dp(1))).apply { topMargin = dp(8); bottomMargin = dp(6) })
        val body = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        content = body
        fr.addView(ScrollView(a).apply { isVerticalScrollBarEnabled = false; addView(body) }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        col.addView(fr, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        layer.addView(col, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.addView(layer, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        closeConsole = { close() }
        startMotion(a)
        close = {
            closeConsole = null
            GilbertNarrator.onSpeaking = null; GilbertNarrator.stop()
            motion?.let { l -> runCatching { a.getSystemService(android.hardware.SensorManager::class.java)?.unregisterListener(l) } }; motion = null
            announceJob?.let { uiHandler.removeCallbacks(it) }; announceJob = null; heard.clear(); heardCount = 0
            PocherFiles.onChanged = null; GilbertArchive.onSaved = null; Gilbert.onHeldChanged = prevHeld
            rings = null; face = null; ticker = null; frame = null
            layer.animate().alpha(0f).scaleX(1.04f).scaleY(1.04f).setDuration(180).withEndAction { root.removeView(layer); onClosed?.invoke() }.start()
        }

        // ---- Boot sequence: rings draw in, the globe forms, the ticker types, then the window opens.
        layer.alpha = 0f; layer.animate().alpha(1f).setDuration(160).start()
        rg.startBoot(1100)
        fc.alpha = 0f; fc.scaleX = 0.3f; fc.scaleY = 0.3f
        fc.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(250).setDuration(700).setInterpolator(android.view.animation.OvershootInterpolator(1.2f)).start()
        strip.alpha = 0f; fr.alpha = 0f
        type("INITIALIZING · READING ARCHIVE · ${GilbertArchive.count(a)} RECORDS") {
            type("GILBERT ONLINE · ${java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())}")
        }
        layer.postDelayed({
            strip.animate().alpha(1f).setDuration(220).start()
            fr.animate().alpha(1f).setDuration(200).start()
            booting = true
            select(startWin.coerceIn(0, wins.size - 1)); startWin = 0
            booting = false
            SoundFX.play(a, "success")
            // He greets you and asks — the briefing only comes when you tap him or the screen.
            briefedThisOpen = false; hiddenThisOpen.clear()
            // Rule 1: he only thinks now, while you're here.
            val u = updates(); val h = runCatching { Gilbert.health(a) }.getOrNull()
            GilbertMind.sense(a, u.count { it.level == 2 }, u.count { it.level == 3 }, h?.battery ?: 100, h?.charging ?: true)
            GilbertMind.recordOpen(a)
            face?.baseMood = GilbertMind.faceMood(a)
            face?.wave()   // he waves hello
            val q = openingQuestion()
            // His own decision: usually he greets you aloud, sometimes just on screen.
            if (u.any { it.level == 3 } || Math.random() > 0.15) GilbertNarrator.say(a, q) else type(q.uppercase())
        }, 900)

        // While he talks: the globe "listens"/pulses and his words show as subtitles in the ticker.
        GilbertNarrator.start(a)
        GilbertNarrator.onSpeaking = { line ->
            if (line != null) { face?.react("listening", 60_000); type(line.uppercase()) }
            else face?.react("happy", 600)
            updateMute()
        }
        updateMute()

        // ---- Live updates: new data → ping, ticker, refresh the open window.
        PocherFiles.onChanged = { update("FILE CAPTURED · SCANNING", null, 6) }
        GilbertArchive.onSaved = { app, who -> update("NEW · ${who.uppercase()} · ${app.uppercase()}", who, -1,
            "$who\u0000$app") }
        val prev = Gilbert.onHeldChanged
        prevHeld = prev
        Gilbert.onHeldChanged = { prev?.invoke(); if (current == 1 || current == 0) layer.post { select(current, animate = false, scanOnly = true) } }
    }

    private var booting = false
    private var lastTabTap = 0L
    private var motion: android.hardware.SensorEventListener? = null

    /** How much the phone moves while he's open (Rule 1: only now, never in the background). */
    private fun startMotion(a: Activity) {
        val sm = a.getSystemService(android.hardware.SensorManager::class.java) ?: return
        val acc = sm.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER) ?: return
        var sum = 0f; var n = 0; var start = System.currentTimeMillis()
        val l = object : android.hardware.SensorEventListener {
            override fun onSensorChanged(e: android.hardware.SensorEvent) {
                val m = kotlin.math.sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2])
                sum += kotlin.math.abs(m - 9.81f); n++
                if (System.currentTimeMillis() - start > 4000 && n > 0) { GilbertMind.recordMotion(a, (sum / n) / 3f); sum = 0f; n = 0; start = System.currentTimeMillis() }
            }
            override fun onAccuracyChanged(s: android.hardware.Sensor?, x: Int) {}
        }
        motion = l
        runCatching { sm.registerListener(l, acc, android.hardware.SensorManager.SENSOR_DELAY_NORMAL) }
    }

    /** How fast you type in his console fields. */
    private fun watchTyping(et: EditText) {
        var burstStart = 0L; var last = 0L; var chars = 0
        et.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, af: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, before: Int, count: Int) {
                val now = System.currentTimeMillis()
                if (count != 1 || before != 0) return   // only single keystrokes (not paste)
                if (now - last > 2000) { if (chars >= 8 && last > burstStart) act?.let { GilbertMind.recordTyping(it, chars * 1000f / (last - burstStart)) }; burstStart = now; chars = 0 }
                chars++; last = now
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
    }
    private var muteBtn: TextView? = null

    /** Show the mute button while he speaks (or while muted); hide it otherwise. */
    private fun updateMute() {
        val a = act ?: return
        val b = muteBtn ?: return
        val muted = !Settings(a).gilbertSpeaks
        val talking = GilbertNarrator.speaking()
        b.text = if (muted) "🔇" else "🔊"
        b.background = GradientDrawable().apply { shape = GradientDrawable.OVAL
            setColor(if (muted) 0x14FFFFFF else Hud.a(Hud.CYAN, 50)); setStroke(maxOf(1, dp(1)), Hud.a(Hud.CYAN, if (talking) 230 else 120)) }
        val show = muted || talking
        if (show && b.visibility != View.VISIBLE) { b.visibility = View.VISIBLE; b.scaleX = 0.4f; b.scaleY = 0.4f; b.animate().scaleX(1f).scaleY(1f).setDuration(180).start() }
        else if (!show && b.visibility == View.VISIBLE) b.animate().scaleX(0.4f).scaleY(0.4f).setDuration(150).withEndAction { b.visibility = View.INVISIBLE }.start()
    }

    private fun toggleMute() {
        val a = act ?: return
        val s = Settings(a)
        if (s.gilbertSpeaks) { GilbertNarrator.stop(); s.gilbertSpeaks = false; type("VOICE MUTED") }
        else { s.gilbertSpeaks = true; type("VOICE ON"); GilbertNarrator.say(a, "Voice on, ${GilbertNarrator.title(a)}.") }
        updateMute()
    }

    // ---- New-message announcements, with a random cool-down so he doesn't keep interrupting.
    private val uiHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var lastAnnounce = 0L
    private var gap = 0L
    private val heard = LinkedHashMap<String, String>()   // spoken name → app
    private var heardCount = 0
    private var announceJob: Runnable? = null

    private fun announce(whoApp: String) {
        val who = whoApp.substringBefore('\u0000'); val app = whoApp.substringAfter('\u0000')
        heard[spokenName(who)] = app; heardCount++
        if (announceJob != null) return
        val wait = maxOf(2500L, lastAnnounce + gap - System.currentTimeMillis())   // groups bursts together
        val r = Runnable { announceJob = null; speakHeard() }
        announceJob = r
        uiHandler.postDelayed(r, wait)
    }

    private fun speakHeard() {
        val a = act ?: return
        if (heard.isEmpty()) return
        val names = heard.keys.toList(); val apps = heard.values.distinct()
        val line = if (heardCount == 1) "New message from ${names[0]}, on ${apps[0]}."
            else if (names.size == 1) "${heardCount} new messages from ${names[0]}."
            else "${heardCount} new messages, from ${names.take(2).joinToString(" and ")}${if (names.size > 2) " and ${names.size - 2} more" else ""}."
        heard.clear(); heardCount = 0
        GilbertNarrator.say(a, line, flush = false)
        lastAnnounce = System.currentTimeMillis()
        gap = (40_000L..150_000L).random()   // next one waits 40 s – 2½ min
    }

    // ------------------------------------------------------------------ what Gilbert says
    private val ENDINGS = listOf("What's the task for today?", "What shall we tackle first?", "What's on the agenda?",
        "Where would you like to start?", "What do you need?", "Shall I pull anything up?")

    /** Greeting + a short briefing from the data (spoken when the console opens). */
    private fun briefing(): String {
        val a = act ?: return ""
        val name = GilbertNarrator.title(a)
        val u = updates()
        if (u.isEmpty()) return listOf("Nothing new since your last briefing, $name.", "All quiet, $name. Nothing needs you.",
            "No updates. I'll let you know if that changes.").random()
        val intro = listOf("Here's the update, $name.", "Right. The short version, $name.", "Briefing, $name.", "Here's where things stand.").random()
        val high = u.filter { it.level == 3 }
        val main = (high + u.filter { it.level < 3 }).take(3).joinToString(" ") { it.spoken }
        val more = u.size - minOf(3, u.size)
        val rest = if (more > 0) " ${GilbertNarrator.plural(more, "more item")} on screen." else ""
        val quip = when {
            u.count { it.level == 2 } >= 5 -> listOf(" Popular today.", " A busy afternoon, then.", "").random()
            high.isEmpty() -> listOf(" Nothing urgent.", "").random()
            else -> ""
        }
        val end = listOf(" What's the task for today?", " Where would you like to start?", " Shall I pull anything up?", "", "").random()
        return "$intro $main$rest$quip$end"
    }
    private val newSaid = HashSet<String>()

    /** "BSIT 1-C (2026-2027): John Cedrick Culia" → "John Cedrick Culia in BSIT 1-C". */
    private fun spokenName(who: String): String {
        val parts = who.split(": ", limit = 2)
        val group = parts[0].replace(Regex("\\(\\d{4}-\\d{4}\\)"), "").trim()
        return if (parts.size == 2) "${parts[1].trim()} in $group" else group
    }

    /** What he says when you open a window. */
    private fun narrate(a: Activity, i: Int): String? {
        val sir = GilbertNarrator.title(a)
        return when (i) {
            0 -> updatesLine()
            1 -> {
                val h = Gilbert.health(a); val held = Gilbert.heldCount()
                "Status. " + (if (held > 0) "${GilbertNarrator.plural(held, "notification")} waiting. " else "Nothing waiting. ") +
                    "Battery ${h.battery} percent${if (h.charging) " and charging" else ""}."
            }
            2 -> {
                val today = messages(1).filter { it.today }
                if (today.isEmpty()) "Nothing to summarise yet, $sir."
                else {
                    val top = today.groupBy { it.who }.entries.sortedByDescending { it.value.size }.take(2).map { spokenName(it.key) }
                    "Today's summary. ${GilbertNarrator.plural(today.size, "message")}. Most active: ${top.joinToString(" and ")}."
                }
            }
            3 -> messages(3).firstOrNull()?.let { "Your chats. The latest is from ${spokenName(it.who)}: ${it.text.take(80)}" } ?: "No chats yet."
            4 -> messages(7).groupBy { it.who }.maxByOrNull { it.value.size }?.let {
                "Your people. Top contact this week is ${spokenName(it.key)}, with ${GilbertNarrator.plural(it.value.size, "message")}." } ?: "No names yet."
            5 -> {
                val notes = GilbertMemory.facts(a).size
                val flagged = messages(3).count { FLAG.containsMatchIn(it.text) }
                "Notes. You have ${GilbertNarrator.plural(notes, "note")}, and I flagged ${GilbertNarrator.plural(flagged, "item")} that look like tasks or dates."
            }
            6 -> {
                val f = PocherFiles.list(a)
                "Files. ${GilbertNarrator.plural(f.size, "file")}, ${f.count { it.img != null }} scanned."
            }
            7 -> if (GilbertBrain.installed(a)) "System. Brain ${if (GilbertBrain.loaded) "online" else "standing by"}: ${GilbertBrain.modelName(a) ?: "installed"}."
                else "System. Running on memory, no brain installed."
            else -> null
        }
    }

    /** Redraw the open window. */
    fun refresh() { if (act != null) select(current, animate = false, scanOnly = true) }

    /** New data arrived while the console is open. */
    private fun update(msg: String, who: String?, onlyWin: Int, spoken: String? = null) {
        rings?.ping(); face?.ping()
        // New messages are shown (ticker, ring, glow) but he doesn't speak unless you tap him.
        if (current == 0 && onlyWin < 0) Unit
        type("▲ $msg")
        flashWho = who
        if (onlyWin < 0 || current == onlyWin) {
            if (current != 5) select(current, animate = false, scanOnly = true)   // don't wipe a note you're typing
        }
    }

    private var typeJob: Runnable? = null
    /** Type a line into the ticker, letter by letter. */
    private fun type(text: String, then: (() -> Unit)? = null) {
        val tv = ticker ?: return
        typeJob?.let { tv.removeCallbacks(it) }
        var n = 0
        val r = object : Runnable { override fun run() {
            n = minOf(text.length, n + 2)
            tv.text = text.substring(0, n) + if (n < text.length) "▌" else ""
            if (n < text.length) tv.postDelayed(this, 18) else then?.let { tv.postDelayed({ it() }, 500) }
        } }
        typeJob = r; tv.post(r)
    }

    private fun select(i: Int, animate: Boolean = true, scanOnly: Boolean = false) {
        act ?: return
        current = i
        tabs.forEachIndexed { k, t ->
            t.background = GradientDrawable().apply {
                setColor(if (k == i) Hud.a(Hud.CYAN, 40) else 0x0DFFFFFF)
                setStroke(maxOf(1, dp(1)), if (k == i) Hud.CYAN else Hud.a(Color.WHITE, 40))
            }
            t.setTextColor(if (k == i) Hud.CYAN else 0xB3FFFFFF.toInt())
        }
        val w = wins[i]
        winTitle?.text = "▣ WIN ${w.num} · ${w.prog}"
        winInfo?.text = "SYNC " + java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        val body = content ?: return
        body.removeAllViews()
        runCatching { w.build(body) }.onFailure { line(body, "⚠️ This window couldn't load: ${it.message?.take(80)}", 13f, 0xFFFF9F0A.toInt()) }
        frame?.play()
        if (animate && !scanOnly) {
            // Rows come in one after another.
            for (k in 0 until body.childCount) {
                val v = body.getChildAt(k)
                v.alpha = 0f; v.translationX = dp(18).toFloat()
                v.animate().alpha(1f).translationX(0f).setStartDelay(minOf(k, 12) * 35L).setDuration(220).start()
            }
            if (!scanOnly) type("OPEN · WIN ${w.num} · ${w.prog}")
            // He talks you through the window you opened (not during start-up — that's the briefing).
            // (He stays quiet when you switch windows — tap his globe to hear about this one.)
        }
        tabs.getOrNull(i)?.let { t -> (t.parent?.parent as? HorizontalScrollView)?.smoothScrollTo(maxOf(0, t.left - dp(40)), 0) }
    }

    /** Flash a freshly updated row (cyan glow that fades). */
    private fun flash(v: View) {
        val bg = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(Hud.a(Hud.CYAN, 90)) }
        val old = v.background
        v.background = bg
        android.animation.ValueAnimator.ofInt(90, 0).apply {
            duration = 1400; startDelay = 200
            addUpdateListener { bg.setColor(Hud.a(Hud.CYAN, it.animatedValue as Int)) }
            addListener(object : android.animation.AnimatorListenerAdapter() { override fun onAnimationEnd(an: android.animation.Animator) { v.background = old } })
        }.start()
    }

    // ------------------------------------------------------------------ small building blocks
    private fun line(p: LinearLayout, t: CharSequence, size: Float = 14f, color: Int = Color.WHITE, bold: Boolean = false, top: Int = 0): TextView =
        TextView(act).apply {
            text = t; textSize = size; setTextColor(color); if (bold) typeface = Typeface.DEFAULT_BOLD
            setLineSpacing(0f, 1.1f)
        }.also { p.addView(it, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(top) }) }

    private fun section(p: LinearLayout, t: String) {
        TextView(act).apply { text = "// $t"; textSize = 10.5f; typeface = monoBold; letterSpacing = 0.1f; setTextColor(Hud.a(Hud.SOFT, 200)) }
            .also { p.addView(it, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(14); bottomMargin = dp(6) }) }
    }

    private fun box(p: LinearLayout, top: Int = 6): LinearLayout = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply { cornerRadius = dp(6).toFloat(); setColor(0x0FFFFFFF); setStroke(maxOf(1, dp(1)), Hud.a(Hud.SOFT, 38)) }
        setPadding(dp(12), dp(10), dp(12), dp(10))
    }.also { p.addView(it, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(top) }) }

    private fun empty(p: LinearLayout, t: String) = line(p, t, 13.5f, 0x99FFFFFF.toInt(), top = 10).apply { gravity = Gravity.CENTER }

    private fun avatar(name: String): TextView = TextView(act).apply {
        text = name.trim().firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "•"
        textSize = 14f; setTextColor(Hud.CYAN); typeface = monoBold; gravity = Gravity.CENTER
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Hud.a(Hud.CYAN, 22)); setStroke(maxOf(1, dp(1)), Hud.a(Hud.CYAN, 160)) }
    }

    private fun pill(p: LinearLayout, t: String, @Suppress("UNUSED_PARAMETER") color: Int = 0, fn: () -> Unit): TextView = TextView(act).apply {
        text = t.uppercase(); textSize = 12f; setTextColor(Hud.CYAN); typeface = monoBold; letterSpacing = 0.06f; gravity = Gravity.CENTER
        setPadding(dp(16), dp(9), dp(16), dp(9))
        background = GradientDrawable().apply { cornerRadius = dp(4).toFloat(); setColor(Hud.a(Hud.CYAN, 28)); setStroke(maxOf(1, dp(1)), Hud.CYAN) }
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

    // ------------------------------------------------------------------ 00 MIND
    private val TASK_W = Regex("\\b(need to|have to|must|todo|to-do|deadline|due|submit|pasa|ipasa|gawin|assignment|activity|report|exam|quiz|bukas|tomorrow)\\b", RegexOption.IGNORE_CASE)
    private val PROBLEM_W = Regex("\\b(problem|issue|error|bug|crash|broken|not working|help|sira|ayaw|hindi gumagana|di gumagana|failed|urgent|asap|emergency)\\b", RegexOption.IGNORE_CASE)
    private val IDEA_W = Regex("\\b(idea|what if|plan|maybe we|let's|lets|pwede natin|gawa tayo|project|concept|business)\\b", RegexOption.IGNORE_CASE)
    private val hiddenThisOpen = HashSet<String>()

    private class MindItem(val id: String, val kind: String, val title: String, val detail: String, val who: String?, val score: Float)

    private fun mindItems(): Map<String, List<MindItem>> {
        val a = act ?: return emptyMap()
        fun id(k: String, t: String) = k + (t.hashCode() and 0x7fffffff).toString(16)
        val out = LinkedHashMap<String, ArrayList<MindItem>>()
        fun add(kind: String, title: String, detail: String, who: String?, base: Float) {
            val i = id(kind, title + detail)
            if (GilbertMind.dismissed(a, i)) return
            val w = GilbertMind.weight(a, kind, who) * base
            if (w < 0.25f) return
            out.getOrPut(kind) { ArrayList() } += MindItem(i, kind, title, detail, who, w)
        }
        val u = updates()
        u.filter { it.level == 3 }.forEach { add("PRIORITY", it.title, it.detail, it.who, 1f) }
        val recent = messages(3)
        recent.filter { TASK_W.containsMatchIn(it.text) }.distinctBy { it.text }.take(6)
            .forEach { add("TASK", TASK_W.find(it.text)!!.value.uppercase() + " · " + it.who, it.text.take(120), it.who, 0.9f) }
        GilbertMemory.facts(a).filter { TASK_W.containsMatchIn(it) }.take(4).forEach { add("TASK", "Your note", it, null, 1f) }
        recent.filter { PROBLEM_W.containsMatchIn(it.text) }.distinctBy { it.text }.take(5)
            .forEach { add("PROBLEM", PROBLEM_W.find(it.text)!!.value.uppercase() + " · " + it.who, it.text.take(120), it.who, 0.9f) }
        GilbertMemory.facts(a).filter { IDEA_W.containsMatchIn(it) }.take(4).forEach { add("IDEA", "Your note", it, null, 1f) }
        recent.filter { IDEA_W.containsMatchIn(it.text) }.distinctBy { it.text }.take(4)
            .forEach { add("IDEA", "From ${it.who}", it.text.take(120), it.who, 0.7f) }
        u.filter { it.level == 2 }.take(5).forEach { add("CHAT", it.title, it.detail, it.who, 0.8f) }
        u.filter { it.level == 1 }.forEach { add("UPDATE", it.title, it.detail, it.who, 0.6f) }
        return out.mapValues { (_, v) -> v.sortedByDescending { it.score } }
    }

    // ---- suggestions he needs your permission for
    private class Proposal(val id: String, val title: String, val why: String, val run: () -> Unit)

    private fun pkgFor(label: String): String? = runCatching {
        val pm = act!!.packageManager
        pm.queryIntentActivities(android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER), 0)
            .firstOrNull { it.loadLabel(pm).toString().equals(label, true) }?.activityInfo?.packageName
    }.getOrNull()

    private fun openApp(pkg: String, label: String) {
        val a = act ?: return
        a.packageManager.getLaunchIntentForPackage(pkg)?.let { runCatching { a.startActivity(it) } }
        GilbertMind.log(a, "Opened $label — you allowed it")
        closeConsole?.invoke()
    }

    private fun proposals(): List<Proposal> {
        val a = act ?: return emptyList()
        val out = ArrayList<Proposal>()
        val fresh = updates().filter { it.level == 2 && it.who != null }
        messages(1).firstOrNull()?.let { e ->
            val pkg = pkgFor(e.app)
            if (pkg != null && fresh.isNotEmpty()) out += Proposal("open:${e.app}", "Open ${e.app}?", "${spokenName(e.who)} wrote last: “${e.text.take(50)}”") { openApp(pkg, e.app) }
        }
        GilbertMind.usualAppNow(a)?.let { app ->
            val pkg = pkgFor(app)
            if (pkg != null && out.none { it.id == "open:$app" }) out += Proposal("habit:$app", "Open $app?", "You usually open it around this time.") { openApp(pkg, app) }
        }
        val h = runCatching { Gilbert.health(a) }.getOrNull()
        if (h != null && h.battery in 0..20 && !h.charging) out += Proposal("saver", "Open Battery Saver?", "Battery is at ${h.battery}%.") {
            runCatching { a.startActivity(android.content.Intent(android.provider.Settings.ACTION_BATTERY_SAVER_SETTINGS)) }
            GilbertMind.log(a, "Opened Battery Saver — you allowed it"); closeConsole?.invoke()
        }
        if (Gilbert.heldCount() > 0) out += Proposal("inbox", "Show what I'm holding?", "${Gilbert.heldCount()} quiet notifications.") {
            GilbertMind.log(a, "Opened the inbox — you allowed it"); thenHome(openInbox)
        }
        // Rule 2: changes to himself, only with your permission.
        GilbertMemory.topPeople(a, 20).map { Gilbert.cleanTitle(it.first) }.firstOrNull { GilbertMind.dismissCount(a, it) >= 3 && !GilbertMind.muted(a, it) }?.let { who ->
            out += Proposal("mute:$who", "Stop reporting ${spokenName(who)}?", "You removed them ${GilbertMind.dismissCount(a, who)} times. This changes how I work.") {
                GilbertMind.mute(a, who); GilbertMind.log(a, "Stopped reporting $who — you allowed it")
                GilbertNarrator.say(a, "Understood. I'll leave ${spokenName(who)} out."); select(0, animate = false)
            }
        }
        return out.filter { it.id !in hiddenThisOpen && !GilbertMind.dismissed(a, "prop:" + it.id) }
    }

    private fun buildMind(p: LinearLayout) {
        val a = act ?: return
        val RED = 0xFFFF453A.toInt()
        // ---- STATE: how he feels and how well he knows you
        section(p, "STATE · ${GilbertMind.dominant(a).uppercase()} · ${GilbertMind.stageName(a)} · DAY ${maxOf(1, GilbertMind.daysKnown(a))}")
        val st = box(p, 2)
        for ((e, v) in GilbertMind.emotions(a)) {
            val r = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(2), 0, dp(2)) }
            r.addView(TextView(a).apply { text = e.uppercase(); textSize = 10f; typeface = mono; setTextColor(0x99FFFFFF.toInt()) }, LinearLayout.LayoutParams(dp(84), WRAP_CONTENT))
            r.addView(Gilbert.SegBar(a, v, if (e == GilbertMind.dominant(a)) Hud.CYAN else Hud.a(Hud.SOFT, 150)), LinearLayout.LayoutParams(0, dp(12), 1f))
            st.addView(r)
        }
        // ---- what's on his mind, by kind
        val items = mindItems()
        val labels = linkedMapOf("PRIORITY" to "PRIORITIES", "TASK" to "TASKS", "PROBLEM" to "PROBLEMS", "IDEA" to "IDEAS", "CHAT" to "CHATS", "UPDATE" to "UPDATES")
        winInfo?.text = "${items.values.sumOf { it.size }} items"
        if (items.isEmpty()) empty(p, "Nothing on my mind right now. Remarkably peaceful.")
        for ((k, label) in labels) {
            val list = items[k]?.filter { it.id !in hiddenThisOpen } ?: continue
            if (list.isEmpty()) continue
            section(p, "$label · ${list.size}")
            for (it in list.take(6)) {
                val b = box(p, 4)
                val top = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                top.addView(TextView(a).apply { text = it.title; textSize = 13.5f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD; maxLines = 1; ellipsize = TextUtils.TruncateAt.END },
                    LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                val item = it
                top.addView(TextView(a).apply {
                    text = "✕"; textSize = 13f; setTextColor(0x99FFFFFF.toInt()); setPadding(dp(10), dp(2), dp(2), dp(2))
                    setOnClickListener {
                        // Remove it, and learn: less of this kind / person from now on.
                        SoundFX.play(a, "tick")
                        GilbertMind.dismiss(a, item.id, item.kind, item.who)
                        hiddenThisOpen += item.id
                        b.animate().alpha(0f).translationX(dp(40).toFloat()).setDuration(180).withEndAction { select(0, animate = false, scanOnly = true) }.start()
                    }
                })
                b.addView(top)
                b.addView(TextView(a).apply { text = item.detail; textSize = 12.5f; setTextColor(0xB3FFFFFF.toInt()); maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
                if (k == "PRIORITY" || k == "PROBLEM") b.background = GradientDrawable().apply { cornerRadius = dp(6).toFloat(); setColor(0x10FF453A); setStroke(maxOf(1, dp(1)), (RED and 0x00FFFFFF) or 0x66000000) }
                item.who?.let { w -> b.setOnClickListener { openChat = w; select(3) } }
            }
        }
        // ---- suggestions that need your permission
        val props = proposals()
        if (props.isNotEmpty()) {
            section(p, "SUGGESTIONS · NEED YOUR PERMISSION")
            for (pr in props) {
                val b = box(p, 4)
                b.addView(TextView(a).apply { text = pr.title; textSize = 14f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD })
                b.addView(TextView(a).apply { text = pr.why; textSize = 12.5f; setTextColor(0xB3FFFFFF.toInt()) })
                val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
                b.addView(row)
                pill(row, "Allow") { pr.run() }
                row.addView(View(a), LinearLayout.LayoutParams(dp(6), 1))
                pill(row, "Not now") { hiddenThisOpen += pr.id; select(0, animate = false, scanOnly = true) }
                row.addView(View(a), LinearLayout.LayoutParams(dp(6), 1))
                pill(row, "Never") { GilbertMind.dismiss(a, "prop:" + pr.id, "SUGGESTION", null); select(0, animate = false, scanOnly = true) }
            }
        }
        // ---- what he has learned about you
        section(p, "WHAT I'VE LEARNED ABOUT YOU")
        val lb = box(p, 2)
        val traits = GilbertMind.traits(a)
        if (traits.isEmpty()) lb.addView(TextView(a).apply { text = "Still observing. Give me a few days with you."; textSize = 13f; setTextColor(0xB3FFFFFF.toInt()) })
        for ((k, v) in traits) lb.addView(TextView(a).apply {
            text = "${k.uppercase()} · $v"; textSize = 12.5f; setTextColor(Color.WHITE); setPadding(0, dp(3), 0, dp(3)) })
        lb.addView(TextView(a).apply {
            text = "I only learn while you have me open: how you type here, how the phone moves, when you check in, what you remove. Nothing leaves this phone."
            textSize = 11f; setTextColor(0x80FFFFFF.toInt()); setPadding(0, dp(6), 0, 0) })
        val fr = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        lb.addView(fr)
        pill(fr, "Forget what you learned") { GilbertMind.forget(a); GilbertMind.log(a, "Forgot everything learned — you asked"); select(0, animate = false) }
        // ---- his rules and what he has done
        section(p, "RULES")
        val rb = box(p, 2)
        GilbertMind.RULES.forEach { r -> rb.addView(TextView(a).apply { text = r; textSize = 12f; setTextColor(0xCCFFFFFF.toInt()); setPadding(0, dp(2), 0, dp(2)) }) }
        val logs = GilbertMind.logs(a)
        if (logs.isNotEmpty()) {
            section(p, "ACTION LOG")
            val lg = box(p, 2)
            val f = java.text.SimpleDateFormat("MMM d HH:mm", java.util.Locale.getDefault())
            logs.take(10).forEach { (t, w) -> lg.addView(TextView(a).apply { text = "${f.format(java.util.Date(t))}  $w"; textSize = 11.5f; typeface = mono; setTextColor(0xB3FFFFFF.toInt()) }) }
        }
    }

    /** Small talk once he knows you (from what he has actually seen). */
    private fun chatter(): String? {
        val a = act ?: return null
        val name = GilbertNarrator.title(a)
        if (GilbertMind.stage(a) == 0 && Math.random() < 0.5) return listOf("I'm still learning how you work, $name. Give me a few days.",
            "Day ${maxOf(1, GilbertMind.daysKnown(a))} of getting to know you. So far, so interesting.").random()
        val c = ArrayList<String>()
        val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val pr = a.getSharedPreferences("gil_mind", android.content.Context.MODE_PRIVATE)
        if (pr.getFloat("typing", 0f) > 6f && (h >= 22 || h < 4)) c += "You type fast at this hour. Deadline, or inspiration?"
        GilbertMind.usualAppNow(a)?.let { c += "You usually open $it around now. Want it?" }
        messages(1).filter { it.today }.groupBy { it.who }.maxByOrNull { it.value.size }?.let { (w, l) -> if (l.size >= 4) c += "${spokenName(w)} has been busy today. ${l.size} messages." }
        mindItems()["PROBLEM"]?.firstOrNull()?.let { c += "One thing bothers me: ${it.title.substringAfter(" · ")} mentioned a problem." }
        mindItems()["TASK"]?.firstOrNull()?.let { c += "Don't forget: ${it.title.lowercase().substringBefore(" · ")}, from ${it.title.substringAfter(" · ")}." }
        when (GilbertMind.dominant(a)) {
            "amused" -> c += "You do enjoy tapping me, $name."
            "tired" -> c += "It's late, $name. Even I'm running on low power."
            "happy" -> c += "Good to have you back, $name."
            else -> {}
        }
        runCatching { narrate(a, current) }.getOrNull()?.let { c += it }
        return c.randomOrNull()
    }

    // ------------------------------------------------------------------ 00 UPDATES
    /** One thing that changed since your last briefing. level: 3 high, 2 medium, 1 low. */
    private class Upd(val level: Int, val title: String, val detail: String, val time: String, val spoken: String, val who: String? = null)

    private fun briefPrefs(a: Activity) = a.getSharedPreferences("gil_brief", android.content.Context.MODE_PRIVATE)
    private var briefedThisOpen = false

    /** Everything new since the last briefing, most important first. */
    private fun updates(): List<Upd> {
        val a = act ?: return emptyList()
        val now = System.currentTimeMillis()
        val last = briefPrefs(a).getLong("brief", 0L)
        val since = if (last > 0) last else now - 12 * 3_600_000L
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
        fun at(e: GilbertArchive.Entry) = runCatching { fmt.parse("${e.day} ${e.time}")?.time }.getOrNull() ?: 0L
        val fresh = messages(2).filter { at(it) >= since - 60_000 }
        val out = ArrayList<Upd>()
        // HIGH: mentions, things that look like tasks / dates, battery, heat
        Gilbert.notes(a).filter { it.mention && it.time >= since }.take(3).forEach { n ->
            out += Upd(3, "Mention · ${n.title}", n.text.take(120), java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(n.time)),
                "${spokenName(Gilbert.cleanTitle(n.title))} mentioned you.", n.title)
        }
        fresh.filter { FLAG.containsMatchIn(it.text) }.distinctBy { it.who }.take(4).forEach { e ->
            val w = FLAG.find(e.text)?.value?.lowercase() ?: "a date"
            out += Upd(3, "Possible task · ${w.uppercase()}", "${e.who}: ${e.text.take(110)}", e.stamp,
                "${spokenName(e.who)} brought up $w.", e.who)
        }
        val h = runCatching { Gilbert.health(a) }.getOrNull()
        h?.let {
            if (it.battery in 0..20 && !it.charging) out += Upd(3, "Battery ${it.battery}%", "Not charging", "now",
                "Battery is at ${it.battery} percent. I'd suggest a charger before it makes the decision for us.")
            if ((it.tempC ?: 0f) >= 42f) out += Upd(3, "Phone running hot", "${it.tempC?.roundToInt()}°C", "now", "The phone is running warm.")
        }
        // MEDIUM: who messaged, held notifications
        fresh.groupBy { it.who }.entries.sortedByDescending { it.value.size }.take(6).forEach { (who, list) ->
            out += Upd(2, "$who · ${list.size}", list.first().text.take(110), list.first().stamp,
                "${GilbertNarrator.plural(list.size, "message")} from ${spokenName(who)}.", who)
        }
        if (Gilbert.heldCount() > 0) out += Upd(2, "Held quietly · ${Gilbert.heldCount()}", "Notifications I kept off your screen", "now",
            "I'm holding ${GilbertNarrator.plural(Gilbert.heldCount(), "notification")} for you.")
        // LOW: files, storage
        val files = PocherFiles.list(a).filter { it.time >= since }
        if (files.isNotEmpty()) out += Upd(1, "Files · ${files.size}", files.take(3).joinToString(", ") { "${it.who} (${it.app})" }, "",
            "${GilbertNarrator.plural(files.size, "new file")} in your files.")
        h?.let { if (it.storageTotalGb > 0 && it.storageFreeGb / it.storageTotalGb < 0.1f) out += Upd(1, "Storage low", "${"%.0f".format(it.storageFreeGb)} GB free", "", "Storage is getting low.") }
        return out.sortedByDescending { it.level }
    }

    private fun updatesLine(): String {
        val u = updates()
        return if (u.isEmpty()) "No updates since your last briefing." else "${GilbertNarrator.plural(u.size, "update")}. ${u.take(2).joinToString(" ") { it.spoken }}"
    }

    private fun buildUpdates(p: LinearLayout) {
        val a = act ?: return
        val last = briefPrefs(a).getLong("brief", 0L)
        val u = updates()
        winInfo?.text = "${u.size} updates"
        line(p, if (last > 0) "SINCE LAST BRIEFING · " + java.text.SimpleDateFormat("EEE HH:mm", java.util.Locale.getDefault()).format(java.util.Date(last)).uppercase()
            else "LAST 12 HOURS", 10.5f, Hud.a(Hud.SOFT, 200)).typeface = monoBold
        val ctl = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        p.addView(ctl)
        pill(ctl, "▶ Brief me") { brief() }
        ctl.addView(View(a), LinearLayout.LayoutParams(dp(8), 1))
        pill(ctl, "✓ Mark as seen") { briefPrefs(a).edit().putLong("brief", System.currentTimeMillis()).apply(); select(0, animate = false) }
        if (u.isEmpty()) { empty(p, "Nothing new since your last briefing. Remarkably quiet."); return }
        val RED = 0xFFFF453A.toInt(); val AMBER = 0xFFFFC53D.toInt()
        for ((lvl, name) in listOf(3 to "PRIORITY", 2 to "MESSAGES & ACTIVITY", 1 to "OTHER")) {
            val group = u.filter { it.level == lvl }
            if (group.isEmpty()) continue
            section(p, name)
            for (x in group) {
                val b = box(p, 4)
                val top = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                val col = when (lvl) { 3 -> RED; 2 -> Hud.CYAN; else -> 0xFF8E8E93.toInt() }
                top.addView(View(a).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(col) } },
                    LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(8) })
                top.addView(TextView(a).apply { text = x.title; textSize = 14f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD; maxLines = 1; ellipsize = TextUtils.TruncateAt.END },
                    LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                if (x.time.isNotEmpty()) top.addView(TextView(a).apply { text = x.time; textSize = 10.5f; typeface = mono; setTextColor(0x80FFFFFF.toInt()) })
                b.addView(top)
                b.addView(TextView(a).apply { text = x.detail; textSize = 12.5f; setTextColor(0xB3FFFFFF.toInt()); maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setPadding(dp(16), dp(2), 0, 0) })
                if (lvl == 3) b.background = GradientDrawable().apply { cornerRadius = dp(6).toFloat(); setColor((AMBER and 0x00FFFFFF) or 0x10000000); setStroke(maxOf(1, dp(1)), (RED and 0x00FFFFFF) or 0x66000000) }
                x.who?.let { w -> b.setOnClickListener { openChat = w; select(3) } }
            }
        }
    }

    /** Speak the briefing (only when you ask: tap him, the HUD, or "Brief me"). */
    private fun brief() {
        val a = act ?: return
        GilbertNarrator.say(a, briefing())
        briefedThisOpen = true
        briefPrefs(a).edit().putLong("brief", System.currentTimeMillis()).apply()
        if (current == 0) a.window?.decorView?.postDelayed({ if (act != null && current == 0) select(0, animate = false, scanOnly = true) }, 600)
    }

    /** What he says when the console opens: a greeting and a question — nothing more until you tap. */
    private fun openingQuestion(): String {
        val a = act ?: return ""
        val n = updates().size
        val name = GilbertNarrator.title(a)
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val part = when (hour) { in 5..11 -> "Good morning"; in 12..17 -> "Good afternoon"; in 18..21 -> "Good evening"; else -> "Still up" }
        val p = briefPrefs(a)
        val away = System.currentTimeMillis() - p.getLong("open", 0L)
        p.edit().putLong("open", System.currentTimeMillis()).apply()
        val hello = when {
            away < 10 * 60_000L -> listOf("Welcome back, $name.", "Back already, $name.", "Yes, $name?").random()
            hour >= 23 || hour < 4 -> listOf("Still up, $name? Bold choice.", "Working late again, $name.").random()
            else -> listOf("$part, $name.", "$part, $name. Systems are online.", "$part, $name. Good to see you.").random()
        }
        val ask = when {
            n == 0 -> listOf("Nothing new to report.", "All quiet. Suspiciously so.", "No updates. Enjoy it while it lasts.").random()
            n >= 8 -> listOf("A busy stretch, then. $n updates. Want the briefing?", "$n updates waiting. Shall I brief you?").random()
            else -> listOf("Would you like the update?", "Want to know the update, $name?", "$n ${if (n == 1) "update" else "updates"}. Shall I brief you?",
                "I have ${GilbertNarrator.plural(n, "update")} for you. Shall I?").random()
        }
        return "$hello $ask"
    }

    // ------------------------------------------------------------------ 00 NOW (what his card used to show)
    /** Set by the launcher: open the message history / the tools. */
    var openInbox: (() -> Unit)? = null
    var openTools: (() -> Unit)? = null
    private var closeConsole: (() -> Unit)? = null

    /** Close the console, then run [fn] on the home screen. */
    private fun thenHome(fn: (() -> Unit)?) {
        val a = act ?: return
        closeConsole?.invoke()
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ fn?.invoke() }, 260)
        if (a.isFinishing) Unit
    }

    private fun appIcon(pkg: String): android.graphics.drawable.Drawable? =
        runCatching { act!!.packageManager.getApplicationIcon(pkg) }.getOrNull()
    private fun appName(pkg: String): String = runCatching {
        act!!.packageManager.getApplicationLabel(act!!.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)

    private fun tag(t: String, color: Int) = TextView(act).apply {
        text = t.uppercase(); textSize = 9.5f; setTextColor(color); typeface = monoBold; letterSpacing = 0.06f
        setPadding(dp(7), dp(2), dp(7), dp(3))
        background = GradientDrawable().apply { cornerRadius = dp(3).toFloat(); setColor((color and 0x00FFFFFF) or 0x1F000000)
            setStroke(maxOf(1, dp(1)), (color and 0x00FFFFFF) or 0x80000000.toInt()) }
    }

    private fun meter(p: LinearLayout, label: String, pct: Int, color: Int, value: String) {
        val a = act ?: return
        val r = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(5), 0, dp(5)) }
        r.addView(TextView(a).apply { text = label; textSize = 12.5f; setTextColor(0x99FFFFFF.toInt()) }, LinearLayout.LayoutParams(dp(92), WRAP_CONTENT))
        r.addView(Gilbert.SegBar(a, pct.coerceIn(0, 100) / 100f, color), LinearLayout.LayoutParams(0, dp(18), 1f))
        r.addView(TextView(a).apply { text = value; textSize = 12.5f; setTextColor(Color.WHITE); typeface = monoBold; gravity = Gravity.END },
            LinearLayout.LayoutParams(dp(70), WRAP_CONTENT))
        p.addView(r)
    }

    private fun appRow(p: LinearLayout, pkg: String, title: String, trail: View?, onClick: (() -> Unit)?) {
        val a = act ?: return
        val r = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(6), 0, dp(6)) }
        r.addView(ImageView(a).apply { setImageDrawable(appIcon(pkg)) }, LinearLayout.LayoutParams(dp(26), dp(26)).apply { marginEnd = dp(10) })
        r.addView(TextView(a).apply { text = title; textSize = 14f; setTextColor(Color.WHITE); maxLines = 1; ellipsize = TextUtils.TruncateAt.END },
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        trail?.let { r.addView(it) }
        onClick?.let { c -> r.setOnClickListener { SoundFX.play(act!!, "tick"); c() } }
        p.addView(r)
    }

    private fun buildNow(p: LinearLayout, s: Settings) {
        val a = act ?: return
        val GOOD = Hud.CYAN; val AMBER = 0xFFFFC53D.toInt(); val RED = 0xFFFF453A.toInt()
        val h = Gilbert.health(a)
        val hc = Gilbert.heldCount()
        val mentions = Gilbert.notes(a).filter { it.mention && android.text.format.DateUtils.isToday(it.time) }

        // Status
        section(p, "STATUS")
        val st = box(p, 2)
        val (cTxt, cCol) = when {
            mentions.isNotEmpty() -> "Mentions" to RED
            hc > 0 -> "Holding $hc" to AMBER
            (h.tempC ?: 0f) >= 42f || (h.battery in 0..15 && !h.charging) -> "Check" to AMBER
            else -> "Ready" to GOOD
        }
        st.addView(LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(TextView(a).apply { text = Gilbert.mood(a, h); textSize = 14f; setTextColor(Color.WHITE) }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(tag(cTxt, cCol))
        })
        val bat = h.battery.coerceAtLeast(0)
        meter(st, "Battery", bat, if (bat <= 20) RED else if (bat <= 45) AMBER else GOOD, if (h.battery >= 0) "$bat%${if (h.charging) " ⚡" else ""}" else "—")
        val calm = (100 - hc * 12).coerceIn(0, 100)
        meter(st, "Calm", calm, if (calm < 40) AMBER else GOOD, "$calm%")

        // Held for you
        section(p, "HELD FOR YOU")
        val hb = box(p, 2)
        hb.addView(LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(TextView(a).apply {
                text = if (Gilbert.held.isEmpty()) "Nothing waiting — all quiet" else "${Gilbert.held.size} app${if (Gilbert.held.size > 1) "s" else ""} waiting · tap one to open it"
                textSize = 13.5f; setTextColor(0xCCFFFFFF.toInt()) }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(tag(if (hc > 0) "$hc waiting" else "Clear", if (hc > 0) AMBER else GOOD))
        })
        if (Gilbert.held.isNotEmpty()) {
            val total = Gilbert.countsToday(a).sumOf { it.second }.coerceAtLeast(hc)
            meter(hb, "Kept quiet", if (total > 0) hc * 100 / total else 0, AMBER, "$hc of $total")
            for ((pkg, n) in Gilbert.held.entries.reversed().toList()) {
                appRow(hb, pkg, appName(pkg), tag("$n", AMBER)) {
                    Gilbert.held.remove(pkg); Gilbert.onHeldChanged?.invoke()
                    a.packageManager.getLaunchIntentForPackage(pkg)?.let { runCatching { a.startActivity(it) } }
                    closeConsole?.invoke()
                }
            }
            val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
            hb.addView(row)
            pill(row, "◐ Open inbox") { thenHome(openInbox) }
            row.addView(View(a), LinearLayout.LayoutParams(dp(8), 1))
            pill(row, "Clear all") { Gilbert.clearHeld(); select(1, animate = false) }
        }

        // Noisiest apps: tap the tag to cycle Normal → Priority → Quiet
        val counts = Gilbert.countsToday(a).take(5)
        if (counts.isNotEmpty()) {
            section(p, "NOISIEST TODAY · TAP TAG: NORMAL › PRIORITY › QUIET")
            val nb = box(p, 2)
            for ((pkg, n) in counts) {
                fun label() = when { pkg in s.gilbertPriority -> "★ Priority"; pkg in s.gilbertQuiet -> "Quiet"; else -> "Normal" }
                fun col() = when { pkg in s.gilbertPriority -> AMBER; pkg in s.gilbertQuiet -> 0xFF8E8E93.toInt(); else -> GOOD }
                val t = tag(label(), col())
                t.setOnClickListener {
                    SoundFX.play(a, "pop")
                    when {
                        pkg in s.gilbertPriority -> { s.gilbertPriority = s.gilbertPriority - pkg; s.gilbertQuiet = s.gilbertQuiet + pkg }
                        pkg in s.gilbertQuiet -> s.gilbertQuiet = s.gilbertQuiet - pkg
                        else -> s.gilbertPriority = s.gilbertPriority + pkg
                    }
                    select(1, animate = false)
                }
                appRow(nb, pkg, "${appName(pkg)} · $n", t, null)
            }
        }

        // Phone check
        section(p, "PHONE CHECK")
        val pb = box(p, 2)
        val tc = h.tempC
        meter(pb, "Temperature", tc?.let { ((it - 20f) / 30f * 100).roundToInt() } ?: 0,
            if ((tc ?: 0f) >= 42f) RED else if ((tc ?: 0f) >= 37f) AMBER else GOOD, tc?.let { "${it.roundToInt()}°C" } ?: "—")
        val stPct = if (h.storageTotalGb > 0) (h.storageFreeGb / h.storageTotalGb * 100).roundToInt() else 0
        meter(pb, "Storage free", stPct, if (stPct < 10) RED else if (stPct < 25) AMBER else GOOD,
            if (h.storageTotalGb > 0) "${"%.0f".format(h.storageFreeGb)} GB" else "—")
        meter(pb, "Memory free", h.ramFreePct.coerceAtLeast(0), if (h.ramFreePct in 0..15) AMBER else GOOD,
            if (h.ramFreePct >= 0) "${h.ramFreePct}%" else "—")

        // Quick actions
        section(p, "QUICK ACTIONS")
        val qa = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        p.addView(qa)
        pill(qa, "☰ Tools") { thenHome(openTools) }
        qa.addView(View(a), LinearLayout.LayoutParams(dp(8), 1))
        pill(qa, "◐ Inbox") { thenHome(openInbox) }
        qa.addView(View(a), LinearLayout.LayoutParams(dp(8), 1))
        pill(qa, "▣ Scan image") {
            val i = android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(android.content.Intent.CATEGORY_OPENABLE).setType("image/*")
            runCatching { a.startActivityForResult(i, REQ_SCAN) }
        }
    }

    // ------------------------------------------------------------------ 01 DIGEST
    private fun buildSummary(p: LinearLayout) {
        val a = act ?: return
        val today = messages(1).filter { it.today }
        val files = PocherFiles.list(a).filter { PocherFiles.timeLabel(it.time).length <= 5 }
        val mentions = Gilbert.notes(a).count { it.mention && android.text.format.DateUtils.isToday(it.time) }
        // Gauges: each ring fills up while its number counts.
        val tiles = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        fun gauge(n: Int, max: Int, cap: String) =
            tiles.addView(HudGauge(a, cap, n, max), LinearLayout.LayoutParams(0, dp(92), 1f))
        val people = today.map { it.who }.distinct().size
        gauge(today.size, maxOf(50, today.size), "MESSAGES")
        gauge(people, maxOf(10, people), "PEOPLE")
        gauge(mentions, maxOf(5, mentions), "MENTIONS")
        gauge(files.size, maxOf(10, files.size), "FILES")
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
            b.setOnClickListener { openChat = who; select(3) }
            if (who == flashWho) flash(b)
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
            !GilbertBrain.installed(a) -> dt.text = "Add a brain in 🧠 System and Gilbert writes a short digest of your messages here."
            else -> {
                dt.text = "Gilbert can read today's messages and write the key points."
                pill(d, "⚡ Make digest") {
                    if (today.isEmpty()) { dt.text = "Nothing to digest yet."; return@pill }
                    dt.text = "PROCESSING ${today.size} MESSAGES…"; dt.typeface = mono
                    GilbertBrain.digest(a, today.take(40).reversed().map { "${it.time} ${it.who}: ${it.text}" }) { r ->
                        digestText = r ?: ("Couldn't write the digest — " + (GilbertBrain.lastError ?: "the brain didn't answer") + ".")
                        GilbertNarrator.say(a, if (r != null) "Here's your digest. " + r.replace("•", ". ") else "I couldn't write the digest this time.")
                        if (current == 2) select(2, animate = false)
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
            if (who == flashWho) flash(b)
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
        p.addView(HudOrbit(a, people.take(8).map { it.key to it.value.size }), LinearLayout.LayoutParams(MATCH_PARENT, dp(210)))
        section(p, "RANKED · 7 DAYS")
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
            row.setOnClickListener { openChat = who; select(3) }
            p.addView(row)
        }
    }

    // ------------------------------------------------------------------ 04 NOTES
    private fun buildNotes(p: LinearLayout) {
        val a = act ?: return
        // About you: your name and profile, kept only on this phone. Gilbert reads it (and his brain does).
        section(p, "ABOUT YOU · PRIVATE, ON THIS PHONE")
        val prof = a.getSharedPreferences("gil_profile", android.content.Context.MODE_PRIVATE)
        val ab = box(p, 2)
        fun field(hint: String, value: String, lines: Int) = EditText(a).apply {
            this.hint = hint; setText(value); setHintTextColor(0x66FFFFFF); setTextColor(Color.WHITE); textSize = 13.5f
            background = GradientDrawable().apply { cornerRadius = dp(6).toFloat(); setColor(0x14FFFFFF); setStroke(maxOf(1, dp(1)), Hud.a(Hud.SOFT, 50)) }
            setPadding(dp(10), dp(8), dp(10), dp(8))
            if (lines > 1) { minLines = 3; maxLines = lines; gravity = Gravity.TOP or Gravity.START; isSingleLine = false } else isSingleLine = true
        }
        val nameF = field("What should I call you? (e.g. John)", prof.getString("name", "") ?: "", 1)
        ab.addView(nameF)
        val aboutF = field("Paste anything about you: school, work, projects, goals, how you like help…", prof.getString("about", "") ?: "", 8)
        ab.addView(aboutF, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(6) })
        watchTyping(nameF); watchTyping(aboutF)
        val saveRow = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        ab.addView(saveRow)
        pill(saveRow, "Save") {
            prof.edit().putString("name", nameF.text.toString().trim()).putString("about", aboutF.text.toString().trim().take(20_000)).apply()
            SoundFX.play(a, "success"); type("PROFILE SAVED · ${aboutF.text.length} CHARACTERS")
            GilbertNarrator.say(a, "Noted, ${GilbertNarrator.title(a)}. I'll keep that in mind.")
        }
        saveRow.addView(TextView(a).apply {
            text = "  ${(prof.getString("about", "") ?: "").length} saved"; textSize = 11f; typeface = mono; setTextColor(0x80FFFFFF.toInt()) })

        val inputRow = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val input = EditText(a).apply {
            hint = "Write a note…"; setHintTextColor(0x80FFFFFF.toInt()); setTextColor(Color.WHITE); textSize = 14.5f
            background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(0x22FFFFFF) }
            setPadding(dp(14), dp(9), dp(14), dp(9)); imeOptions = EditorInfo.IME_ACTION_DONE; isSingleLine = true
        }
        inputRow.addView(input, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        fun add() {
            val t = input.text.toString().trim()
            if (t.isNotEmpty()) { GilbertMemory.addFact(a, t); input.setText(""); SoundFX.play(a, "success"); select(5, animate = false) }
        }
        inputRow.addView(TextView(a).apply {
            text = "＋"; textSize = 18f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFF0A84FF.toInt()) }
            setOnClickListener { add() }
        }, LinearLayout.LayoutParams(dp(38), dp(38)).apply { marginStart = dp(8) })
        input.setOnEditorActionListener { _, _, _ -> add(); true }
        watchTyping(input)
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
                setOnClickListener { GilbertMemory.removeFact(a, f); select(5, animate = false) }
            })
            b.addView(r)
        }

        section(p, "FLAGGED BY GILBERT")
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
            b.setOnClickListener { openChat = e.who; select(3) }
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
        if (!PhotoWatcher.allowed(a)) {
            val wb = box(p, 8)
            wb.addView(TextView(a).apply {
                text = "Some apps (like Messenger) don't put the photo in their notification — only “Sent a photo”. " +
                    "Allow Gilbert to see photos SAVED to your phone and he'll match them to the chat automatically."
                textSize = 12.5f; setTextColor(0xCCFFFFFF.toInt()) })
            pill(wb, "Allow saved photos") {
                GilbertChatActivity.picking = true
                runCatching { a.requestPermissions(arrayOf(PhotoWatcher.permission()), REQ_PHOTOS) }
            }
        }
        line(p, "Tip: in Messenger turn on Settings › Photos & media › Save photos — every photo you receive then shows up here. " +
            "Or tap Share → Gilbert on any photo.", 11.5f, Hud.a(Hud.SOFT, 200), top = 6)
        line(p, "Photos and attachments from your chats are kept and scanned here: the text inside them and what's in them.",
            11.5f, 0x80FFFFFF.toInt(), top = 6)
        if (items.isEmpty()) { empty(p, "No files yet."); return }
        val px = dp(64)
        for (it in items.take(60)) { // it = one file
            val b = box(p, 6)
            val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
            val thumb = PocherFiles.thumb(a, it, px)
            val av = if (thumb == null) PocherFiles.avatar(a, it) else null
            if (thumb == null && av != null) row.addView(FrameLayout(a).apply {
                // The sender's picture with a 📷 tag: the photo itself hasn't reached Gilbert yet.
                addView(ImageView(a).apply {
                    setImageBitmap(av); scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0.85f
                    clipToOutline = true; outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x22FFFFFF) }
                }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
                addView(TextView(a).apply { text = "📷"; textSize = 13f; gravity = Gravity.CENTER
                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xE6000000.toInt()); setStroke(maxOf(1, dp(1)), Hud.CYAN) } },
                    FrameLayout.LayoutParams(dp(26), dp(26), Gravity.BOTTOM or Gravity.END))
            }, LinearLayout.LayoutParams(px, px).apply { marginEnd = dp(10) })
            else if (thumb != null) row.addView(ImageView(a).apply {
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
                !scanned && it.kind.startsWith("image") -> "${it.msg.ifBlank { "Sent a photo" }} · waiting for the photo (save it in ${it.app} or share it to Gilbert)"
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
            select(6, animate = false)
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

        section(p, "VOICE")
        GilbertNarrator.start(a)
        val vb = box(p, 2)
        val voices = GilbertNarrator.voices()
        if (voices.isEmpty()) {
            vb.addView(TextView(a).apply { text = "Loading voices… (open this window again in a moment)"; textSize = 12.5f; setTextColor(0x99FFFFFF.toInt()) })
        } else {
            vb.addView(TextView(a).apply { text = "Tap a voice to hear it. British voices are first."; textSize = 12f; setTextColor(0x99FFFFFF.toInt()) })
            val chosen = s.gilbertVoice
            val wrap = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
            var row: LinearLayout? = null
            voices.forEachIndexed { i, (name, label) ->
                if (i % 3 == 0) { row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }; wrap.addView(row) }
                row!!.addView(TextView(a).apply {
                    text = label; textSize = 11f; typeface = monoBold; gravity = Gravity.CENTER; maxLines = 1
                    val on = name == chosen
                    setTextColor(if (on) Color.BLACK else Hud.CYAN)
                    setPadding(dp(6), dp(8), dp(6), dp(8))
                    background = GradientDrawable().apply { cornerRadius = dp(4).toFloat(); setColor(if (on) Hud.CYAN else Hud.a(Hud.CYAN, 22)); setStroke(maxOf(1, dp(1)), Hud.a(Hud.CYAN, 150)) }
                    setOnClickListener { GilbertNarrator.useVoice(a, name); select(7, animate = false) }
                }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = dp(6); topMargin = dp(6) })
            }
            vb.addView(wrap)
        }
        val ctl = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        vb.addView(ctl)
        pill(ctl, if (s.gilbertEcho) "AI echo: on" else "AI echo: off") {
            s.gilbertEcho = !s.gilbertEcho; select(7, animate = false)
            GilbertNarrator.say(a, if (s.gilbertEcho) "Echo on. I sound like I'm in the room." else "Echo off.")
        }
        ctl.addView(View(a), LinearLayout.LayoutParams(dp(8), 1))
        pill(ctl, "▶ Test") { GilbertNarrator.say(a, "${GilbertNarrator.greeting(a)} All systems are online. What do you need?") }
        vb.addView(TextView(a).apply {
            text = "More voices: install “Speech Services by Google”, then Settings › Accessibility › Text-to-speech › English (UK) › download voices."
            textSize = 11f; setTextColor(0x80FFFFFF.toInt()); setPadding(0, dp(8), 0, 0) })
        if (GilbertBrain.installed(a) && !GilbertBrain.loaded && !GilbertBrain.crashed(a)) room.calibrate()
    }
}
