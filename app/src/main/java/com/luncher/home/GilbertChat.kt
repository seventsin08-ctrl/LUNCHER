package com.luncher.home

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * Talk to Gilbert. With his brain file he chats freely (on the phone, offline); without it he
 * still answers from his memory: what you missed, your music, your apps, the phone, and more.
 */
object GilbertChat {
    private val history = ArrayList<Pair<Boolean, String>>()   // (fromUser, text)

    /** Answers without the brain: memory + rules. */
    /** Questions about facts are answered instantly from memory (fast and always correct). */
    fun isFactQuestion(q: String): Boolean {
        val t = q.lowercase()
        if (t.startsWith("search") || t.startsWith("find ") || t.startsWith("look for") || t.startsWith("remember") || t.startsWith("forget ")) return true
        return listOf("what do you know", "report", "brief", "summary", "summar", "rundown", "time", "date", "day is", "miss", "recap", "notif", "message", "mention", "song", "music", "listen",
            "artist", "app", "who am i", "about me", "know me", "battery", "storage", "temperature", "who texted", "talk to").any { t.contains(it) }
    }

    fun ruleAnswer(act: Activity, s: Settings, q: String): String {
        val t = q.lowercase()
        fun has(vararg w: String) = w.any { t.contains(it) }
        return when {
            t.startsWith("search the web") || t.startsWith("google ") -> {
                val q2 = q.substringAfter(" ").removePrefix("the web for").removePrefix("web for").trim()
                runCatching { act.startActivity(android.content.Intent(android.content.Intent.ACTION_WEB_SEARCH)
                    .putExtra(android.app.SearchManager.QUERY, q2).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                "Opening a web search for “$q2” 🔎"
            }
            t.startsWith("search") || t.startsWith("find ") || t.startsWith("look for") ->
                GilbertMemory.search(act, q.replaceFirst(Regex("^(search( for)?|find|look for)\\s*", RegexOption.IGNORE_CASE), ""))
            t.startsWith("remember") -> GilbertMemory.learnFrom(act, q)?.let { "Got it — I'll remember: $it 📝" } ?: "Tell me what to remember, like “remember that my exam is Friday”."
            t.startsWith("forget ") -> { val k = q.substringAfter(" ").lowercase()
                val gone = GilbertMemory.facts(act).filter { it.lowercase().contains(k) }
                gone.forEach { GilbertMemory.removeFact(act, it) }
                if (gone.isEmpty()) "I don't have anything about “$k”." else "Forgotten ✓ (${gone.size})" }
            has("what do you know") -> GilbertMemory.profile(act)
            has("report", "brief", "summary", "summar", "rundown") -> GilbertMemory.report(act)
            has("time", "date", "day is") -> "It's " + java.text.SimpleDateFormat("h:mm a, EEEE MMM d", java.util.Locale.getDefault()).format(java.util.Date()) + " ⏰"
            has("miss", "recap", "notif", "message", "what happened", "update") -> GilbertMemory.recap(act)
            has("mention", "tag") -> Gilbert.notes(act).filter { it.mention }.take(3).takeIf { it.isNotEmpty() }
                ?.joinToString("\n") { "• ${it.title}: ${it.text.take(60)}" } ?: "No one mentioned you today 🙂"
            has("song", "music", "listen", "artist") -> GilbertMemory.topSongs(act, 3).takeIf { it.isNotEmpty() }
                ?.let { "You've been playing ${it.joinToString { s -> s.first }} 🎶" } ?: "Play something and I'll remember it 🎧"
            has("app", "use most", "screen time") -> GilbertMemory.topApps(act, 4).takeIf { it.isNotEmpty() }
                ?.let { "Your most opened apps: ${it.joinToString { a -> a.first }} 📱" } ?: "I'm still learning which apps you love."
            has("who am i", "about me", "know me", "my profile") -> GilbertMemory.profile(act)
            has("who", "friend", "talk to") -> GilbertMemory.topPeople(act, 4).takeIf { it.isNotEmpty() }
                ?.let { "You talk most with ${it.joinToString { p -> p.first }} 💬" } ?: "I haven't seen many messages yet."
            has("battery", "phone", "storage", "hot", "temperature") -> Gilbert.health(act).let { h -> Gilbert.mood(act, h) +
                " (battery ${h.battery}%, ${h.tempC?.roundToInt() ?: "?"}°C)" }
            has("hi", "hello", "hey", "yo", "sup") -> "Hey ${GilbertTalk.ownerName(act) ?: "there"}, good to see you. Want your briefing? Just say “report” 🙂"
            has("thank") -> "Anytime 🤍"
            has("how are you", "how r u") -> "I'm glowing ✨ How about you?"
            has("sad", "tired", "stress", "bad day") -> "I'm here with you 🤍 Take a breath, drink some water, and maybe put on your favourite song."
            has("eat", "hungry", "food") -> "Have you eaten yet? Go get something yummy 🍜"
            else -> GilbertTalk.line(act, s, "curious") +
                if (!GilbertBrain.installed(act)) "\n\n(Give me my brain in Settings › Gilbert › Brain and I can chat about anything.)" else ""
        }
    }

    fun show(act: Activity, root: FrameLayout, s: Settings, onClosed: (() -> Unit)? = null) {
        fun dp(v: Int) = (v * act.resources.displayMetrics.density).roundToInt()
        val tint = if (ThemeEngine.colorful) ThemeEngine.accent else 0xFF8E8E93.toInt()
        val layer = FrameLayout(act).apply { setBackgroundColor(Color.argb(150, 0, 0, 0)); isClickable = true }
        val card = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(12))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(
                IconFactory.blend(0xFF3A3A3E.toInt(), tint, 0.2f), IconFactory.blend(0xFF161618.toInt(), tint, 0.08f))).apply {
                cornerRadius = dp(30).toFloat(); setStroke(maxOf(1, dp(1)), ThemeEngine.alpha(tint, 90)) }
            elevation = dp(16).toFloat(); isClickable = true
        }
        lateinit var close: () -> Unit
        // Header
        card.addView(LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(FrameLayout(act).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.BLACK); setStroke(dp(2), ThemeEngine.alpha(tint, 170)) }
                addView(IslandFace(act).apply { configure(s) }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            }, LinearLayout.LayoutParams(dp(38), dp(38)).apply { marginEnd = dp(10) })
            addView(LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(act).apply { text = "Gilbert"; textSize = 18f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD })
                addView(TextView(act).apply {
                    text = if (GilbertBrain.installed(act)) "Brain on · private, on this phone" else "Memory mode · add his brain in Settings"
                    textSize = 11.5f; setTextColor(0x99FFFFFF.toInt())
                })
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(TextView(act).apply {
                text = "✕"; textSize = 14f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x33FFFFFF) }
                setOnClickListener { close() }
            }, LinearLayout.LayoutParams(dp(34), dp(34)))
        })
        // Messages
        val list = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, dp(8)) }
        val scroll = object : ScrollView(act) {
            override fun onMeasure(w: Int, h: Int) = super.onMeasure(w,
                View.MeasureSpec.makeMeasureSpec((act.resources.displayMetrics.heightPixels * 0.45f).toInt(), View.MeasureSpec.AT_MOST))
        }.apply { isVerticalScrollBarEnabled = false; addView(list) }
        card.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        fun bubble(user: Boolean, text: String): TextView {
            val tv = TextView(act).apply {
                this.text = text; textSize = 15f; setTextColor(if (user) Color.WHITE else Color.WHITE)
                setPadding(dp(13), dp(9), dp(13), dp(10)); maxWidth = (act.resources.displayMetrics.widthPixels * 0.7f).toInt()
                background = GradientDrawable().apply { cornerRadius = dp(18).toFloat()
                    setColor(if (user) IconFactory.blend(0xFF0A84FF.toInt(), tint, 0.5f) else 0x2EFFFFFF) }
            }
            list.addView(tv, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                gravity = if (user) Gravity.END else Gravity.START; topMargin = dp(6) })
            tv.alpha = 0f; tv.translationY = dp(8).toFloat()
            tv.animate().alpha(1f).translationY(0f).setDuration(220).start()
            scroll.post { scroll.smoothScrollTo(0, list.height) }
            return tv
        }
        if (history.isEmpty()) history += false to ("Hi${GilbertTalk.ownerName(act)?.let { " $it" } ?: ""}, I'm here. " + GilbertMemory.recap(act) +
            "\nSay “report” for your full briefing, or just talk to me 🙂")
        for ((u, t) in history) bubble(u, t)
        GilbertBrain.warmUp(act)   // start loading his brain now so he answers sooner

        // Input
        val input = EditText(act).apply {
            hint = "Message Gilbert"; setHintTextColor(0x80FFFFFF.toInt()); setTextColor(Color.WHITE); textSize = 15f
            setPadding(dp(14), dp(10), dp(14), dp(10)); maxLines = 4
            background = GradientDrawable().apply { cornerRadius = dp(20).toFloat(); setColor(0x26FFFFFF) }
            imeOptions = EditorInfo.IME_ACTION_SEND; setSingleLine(false)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        val send = TextView(act).apply {
            text = "↑"; textSize = 18f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFF0A84FF.toInt()) }
        }
        var spoken = false   // the last question was said out loud → he answers out loud too
        fun submit() {
            val talkBack = spoken && s.gilbertSpeaks; spoken = false
            val q = input.text.toString().trim()
            if (q.isEmpty() || GilbertBrain.busy) return
            input.setText(""); SoundFX.play(act, "tick")
            history += true to q; bubble(true, q)
            // Learn from what you say ("I'm a BSIT student", "I love NF"…) — quietly, all on the phone.
            if (!q.lowercase().startsWith("remember")) GilbertMemory.learnFrom(act, q)
            if (GilbertBrain.installed(act) && !isFactQuestion(q)) {
                val thinking = bubble(false, "•")
                // Typing dots while he thinks.
                val dots = object : Runnable { var n = 0
                    override fun run() { if (thinking.text.length <= 3 && thinking.text.all { it == '•' }) { n = n % 3 + 1; thinking.text = "•".repeat(n); thinking.postDelayed(this, 350) } } }
                thinking.post(dots)
                GilbertBrain.ask(act, history.dropLast(1), q, partial = { soFar ->
                    // Words appear as he thinks.
                    thinking.text = soFar
                    scroll.post { scroll.smoothScrollTo(0, list.height) }
                }) { ans ->
                    val a = ans ?: (ruleAnswer(act, s, q) + (GilbertBrain.lastError?.let { "\n\n⚠️ $it" } ?: ""))
                    history += false to a
                    thinking.text = a; SoundFX.play(act, "chirp")
                    if (talkBack) GilbertVoice.speak(act, a)
                    scroll.post { scroll.smoothScrollTo(0, list.height) }
                }
            } else {
                val a = ruleAnswer(act, s, q)
                history += false to a
                list.postDelayed({ bubble(false, a); SoundFX.play(act, "chirp"); if (talkBack) GilbertVoice.speak(act, a) }, 350)
            }
            while (history.size > 30) history.removeAt(0)
        }
        send.setOnClickListener { submit() }
        input.setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_SEND) { submit(); true } else false }
        // 🎤 Talk to him: tap, speak, and it sends by itself.
        val mic = TextView(act).apply {
            text = "🎤"; textSize = 17f; gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x33FFFFFF) }
        }
        var listening = false
        fun micIdle() { listening = false; mic.animate().cancel(); mic.scaleX = 1f; mic.scaleY = 1f
            (mic.background as GradientDrawable).setColor(0x33FFFFFF); input.hint = "Message Gilbert" }
        mic.setOnClickListener {
            if (listening) { GilbertVoice.stopListening(); micIdle(); return@setOnClickListener }
            if (act.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                act.requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 7711); return@setOnClickListener
            }
            if (!GilbertVoice.available(act)) { bubble(false, "Speech recognition isn't available on this phone (install/enable Google app voice typing)."); return@setOnClickListener }
            GilbertVoice.shutUp()
            listening = true; SoundFX.play(act, "pop")
            (mic.background as GradientDrawable).setColor(0xFFFF453A.toInt()); input.hint = "Listening…"
            mic.animate().scaleX(1.15f).scaleY(1.15f).setDuration(500).withEndAction { mic.animate().scaleX(1f).scaleY(1f).setDuration(500).start() }.start()
            GilbertVoice.listen(act, partial = { input.setText(it) }) { said ->
                micIdle()
                if (!said.isNullOrBlank()) { input.setText(said); spoken = true; submit() }
            }
        }
        card.addView(LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(mic, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(8) })
            addView(input, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = dp(8) })
            addView(send, LinearLayout.LayoutParams(dp(40), dp(40)))
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(6) })

        layer.setOnClickListener { close() }
        layer.addView(card, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP).apply {
            leftMargin = dp(12); rightMargin = dp(12); topMargin = dp(64) })
        close = {
            GilbertVoice.stopListening(); GilbertVoice.shutUp()
            SoundFX.play(act, "close")
            runCatching { act.getSystemService(android.view.inputmethod.InputMethodManager::class.java).hideSoftInputFromWindow(input.windowToken, 0) }
            layer.animate().alpha(0f).setDuration(200).withEndAction { root.removeView(layer); onClosed?.invoke() }.start()
        }
        root.addView(layer, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        SoundFX.play(act, "open")
        layer.alpha = 0f; layer.animate().alpha(1f).setDuration(200).start()
        card.pivotY = 0f; card.scaleX = 0.4f; card.scaleY = 0.3f
        Motion.scaleTo(card, 1f, Motion.FLUID)
    }
}
