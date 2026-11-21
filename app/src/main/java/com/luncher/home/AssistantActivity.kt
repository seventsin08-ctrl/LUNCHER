package com.luncher.home

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.telecom.TelecomManager
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The assistant: tap the orb in the Sidebar (or say "voice activation" if the wake word is on).
 * It listens once and can:
 *  - set a timer ("set a timer for 10 minutes")
 *  - open apps ("open Messenger")
 *  - send messages ("message John I'm on my way", "send hi to Mom", "reply to Anna okay")
 *  - make / answer / end calls ("call Mom", "answer the call", "hang up")
 *  - keep notes ("take a note buy milk", "read my notes", "delete my notes")
 *  - give a report: a summary of every app's messages and notifications ("give me the report")
 *
 * It's a see-through screen (not an overlay) so Android lets it use the microphone from any app.
 */
class AssistantActivity : Activity() {
    companion object {
        /** True while it's on screen (the wake-word listener waits until it closes). */
        @Volatile var active = false
        const val EXTRA_SAID = "said"

        fun open(ctx: Context, said: String? = null) {
            runCatching {
                ctx.startActivity(Intent(ctx, AssistantActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                    .apply { if (said != null) putExtra(EXTRA_SAID, said) })
            }
        }

        // ---------------------------------------------------------------- notes (kept on the phone)
        private const val NOTES = "assistant_notes"
        fun notes(ctx: Context): MutableList<Pair<Long, String>> = runCatching {
            val arr = org.json.JSONArray(ctx.getSharedPreferences("luncher", Context.MODE_PRIVATE).getString(NOTES, "[]"))
            (0 until arr.length()).map { arr.getJSONObject(it).let { o -> o.getLong("t") to o.getString("x") } }.toMutableList()
        }.getOrDefault(mutableListOf())
        fun saveNotes(ctx: Context, list: List<Pair<Long, String>>) {
            val arr = org.json.JSONArray()
            for ((t, x) in list) arr.put(org.json.JSONObject().put("t", t).put("x", x))
            ctx.getSharedPreferences("luncher", Context.MODE_PRIVATE).edit().putString(NOTES, arr.toString()).apply()
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var settings: Settings
    private lateinit var root: FrameLayout
    private lateinit var panel: LinearLayout
    private val orb by lazy { OrbView(this) }
    private val title by lazy { TextView(this) }
    private val sub by lazy { TextView(this) }
    private lateinit var answerScroll: ScrollView
    private lateinit var answer: TextView
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var listening = false
        set(v) { field = v; if (v) glow?.start() else glow?.stop() }
    /** Siri Glow (iPhone feature 29): rainbow edge while listening. */
    private var glow: EdgeGlowView? = null
    /** A follow-up question is waiting for its answer ("What should I say to John?"). */
    private var followUp: ((String) -> Unit)? = null
    private val closeLater = Runnable { close() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        active = true
        settings = Settings(this)
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        window.statusBarColor = Color.TRANSPARENT; window.navigationBarColor = Color.TRANSPARENT
        buildUi()
        if (settings.assistantVoice) tts = TextToSpeech(this) { st ->
            ttsReady = st == TextToSpeech.SUCCESS
            if (ttsReady) runCatching { tts?.language = Locale.getDefault() }
        }
        NotificationService.loadHistory(this)
        chime()
        val said = intent.getStringExtra(EXTRA_SAID)
        if (!said.isNullOrBlank()) main.postDelayed({ handle(listOf(said.lowercase())) }, 450)
        else main.postDelayed({ listen() }, 380)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val said = intent.getStringExtra(EXTRA_SAID)
        if (!said.isNullOrBlank()) handle(listOf(said.lowercase())) else listen()
    }

    override fun onDestroy() {
        active = false
        main.removeCallbacksAndMessages(null)
        runCatching { recognizer?.destroy() }
        runCatching { tts?.stop(); tts?.shutdown() }
        super.onDestroy()
    }

    override fun onPause() {
        super.onPause()
        // Went to another app (opened Messenger, a call…): get out of the way.
        if (!isFinishing && !askingPermission) { runCatching { recognizer?.cancel() }; finish() }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = close()

    // ================================================================ screen

    private fun buildUi() {
        root = FrameLayout(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(0xF5000000.toInt(), 0xD9000000.toInt(), 0x66000000, 0x00000000))
            setOnTouchListener { _, e -> if (e.actionMasked == MotionEvent.ACTION_UP && e.y < panel.top) close(); true }
        }
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(18), dp(10), dp(18), dp(30))
            isClickable = true
        }
        panel.addView(orb, LinearLayout.LayoutParams(dp(200), dp(200)))
        orb.setOnClickListener { if (!listening) listen() }
        title.apply {
            text = "Listening…"; textSize = 20f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        sub.apply {
            text = "Try “set a timer for 10 minutes”, “message John…”, “give me the report”"
            textSize = 14f; setTextColor(0x99FFFFFF.toInt()); gravity = Gravity.CENTER
        }
        panel.addView(title, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(2) })
        panel.addView(sub, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(6) })

        // The answer card (report, notes…), scrollable.
        answer = TextView(this).apply { textSize = 15f; setTextColor(Color.WHITE); setLineSpacing(0f, 1.18f) }
        answerScroll = object : ScrollView(this) {
            override fun onMeasure(w: Int, h: Int) = super.onMeasure(w,
                View.MeasureSpec.makeMeasureSpec((resources.displayMetrics.heightPixels * 0.42f).toInt(), View.MeasureSpec.AT_MOST))
        }.apply {
            visibility = View.GONE
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = GradientDrawable().apply { cornerRadius = dp(24).toFloat(); setColor(0xF01C1C1E.toInt()); setStroke(maxOf(1, dp(1)), 0x33FFFFFF) }
            addView(answer)
        }
        panel.addView(answerScroll, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(14) })

        // Quick suggestions (tap instead of saying it).
        val chipScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((label, cmd) in listOf("📋 Report" to "give me the report", "⏱ Timer 10 min" to "set a timer for 10 minutes",
                "📝 My notes" to "read my notes", "💬 Message…" to "send a message", "📞 Call…" to "make a call")) {
            chips.addView(TextView(this).apply {
                text = label; textSize = 13.5f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setPadding(dp(14), dp(8), dp(14), dp(8))
                background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(0x33FFFFFF) }
                setOnClickListener { stopListening(); followUp = null; handle(listOf(cmd)) }
            }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = dp(8) })
        }
        chipScroll.addView(chips)
        panel.addView(chipScroll, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(16) })

        root.addView(panel, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
        if (Settings(this).ios("glow")) glow = EdgeGlowView(this).apply { visibility = View.GONE }
            .also { root.addView(it, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)) }
        setContentView(root)
        root.alpha = 0f
        root.animate().alpha(1f).setDuration(300).start()
        panel.translationY = dp(80).toFloat()
        panel.animate().translationY(0f).setDuration(480).setInterpolator(android.view.animation.PathInterpolator(0.2f, 0.9f, 0.1f, 1f)).start()
        orb.scaleX = 0.4f; orb.scaleY = 0.4f
        orb.animate().scaleX(1f).scaleY(1f).setDuration(650).setInterpolator(android.view.animation.OvershootInterpolator(1.2f)).start()
    }

    private fun close() {
        if (isFinishing) return
        stopListening()
        root.animate().alpha(0f).setDuration(220).withEndAction { finish(); overridePendingTransition(0, 0) }.start()
    }

    /** Show (and say) a reply. [keep] = stay open so it can be read; otherwise close soon. */
    private fun reply(text: String, speak: String? = text, card: String? = null, keep: Boolean = card != null) {
        title.text = text
        if (card != null) {
            answer.text = card
            if (answerScroll.visibility != View.VISIBLE) {
                answerScroll.visibility = View.VISIBLE
                answerScroll.alpha = 0f; answerScroll.translationY = dp(20).toFloat()
                answerScroll.animate().alpha(1f).translationY(0f).setDuration(320).start()
            }
            answerScroll.scrollTo(0, 0)
        }
        if (speak != null && ttsReady && settings.assistantVoice) runCatching { tts?.speak(speak, TextToSpeech.QUEUE_FLUSH, null, "a") }
        main.removeCallbacks(closeLater)
        main.postDelayed(closeLater, if (keep) 60_000L else 2600L)
    }

    /** Ask something and listen for the answer. */
    private fun ask(question: String, then: (String) -> Unit) {
        title.text = question
        if (ttsReady && settings.assistantVoice) runCatching { tts?.speak(question, TextToSpeech.QUEUE_FLUSH, null, "q") }
        followUp = then
        main.removeCallbacks(closeLater)
        main.postDelayed({ listen() }, if (ttsReady && settings.assistantVoice) 1500L else 300L)
    }

    // ================================================================ listening

    private fun listen() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            askingPermission = true
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1); return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { reply("Speech recognition isn't available on this phone", keep = true); return }
        main.removeCallbacks(closeLater)
        runCatching { tts?.stop() }
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(this).also { recognizer = it }
        listening = true
        if (followUp == null) title.text = "Listening…"
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) { orb.level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f); glow?.level = orb.level }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { orb.level = 0f }
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onPartialResults(partial: Bundle?) {
                partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { sub.text = "“$it”" }
            }
            override fun onError(error: Int) {
                listening = false; orb.level = 0f
                followUp = null
                reply("Didn't catch that — tap the orb to try again", speak = null, keep = true)
            }
            override fun onResults(results: Bundle?) {
                listening = false; orb.level = 0f
                val all = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                all.firstOrNull()?.let { sub.text = "“$it”" }
                val f = followUp
                if (f != null) { followUp = null; f(all.firstOrNull().orEmpty()); return }
                handle(all.map { it.lowercase(Locale.getDefault()) })
            }
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        runCatching { r.startListening(i) }.onFailure { listening = false; reply("Couldn't start listening", keep = true) }
    }

    private fun stopListening() {
        if (listening) runCatching { recognizer?.cancel() }
        listening = false
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        askingPermission = false
        if (requestCode == 1 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) listen()
        else if (requestCode == 1) reply("I need the microphone to hear you", keep = true)
        else if (requestCode == 2) pendingAfterPermission?.invoke()
        pendingAfterPermission = null
    }
    private var pendingAfterPermission: (() -> Unit)? = null
    private fun withPermission(p: String, then: () -> Unit) {
        if (checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED) then()
        else { pendingAfterPermission = then; askingPermission = true; requestPermissions(arrayOf(p), 2) }
    }
    private var askingPermission = false

    // ================================================================ understanding

    private fun handle(all: List<String>) {
        if (all.isEmpty()) { reply("Didn't catch that — tap the orb to try again", speak = null, keep = true); return }
        val t = all.first().trim()
        when {
            Regex("\\b(report|summary|summarize|summarise|brief me|briefing|what did i miss|catch me up)\\b").containsMatchIn(t) -> report()
            Regex("\\btimer\\b").containsMatchIn(t) -> timer(t)
            Regex("\\b(answer|accept|pick up|take)\\b.*\\bcall\\b|^pick up").containsMatchIn(t) -> answerCall()
            Regex("\\b(hang up|end|decline|reject)\\b.*\\bcall\\b|^hang up").containsMatchIn(t) -> endCall()
            Regex("\\bnotes?\\b|^remember\\b|^remind me\\b").containsMatchIn(t) -> note(t)
            Regex("^(call|phone|dial|ring)\\b|make a call").containsMatchIn(t) -> call(t)
            Regex("\\b(message|text|reply|tell|send)\\b").containsMatchIn(t) && !t.startsWith("open ") -> message(t)
            Regex("^(open|launch|start|go to)\\s+").containsMatchIn(t) -> openApp(t)
            else -> {
                // Maybe it's just an app name.
                val app = findApp(t)
                if (app != null) openApp("open $t")
                else reply("I can set timers, open apps, send messages, make calls, take notes and give you a report",
                    speak = "Sorry, I didn't get that", keep = true)
            }
        }
    }

    // ---------------------------------------------------------------- timer

    private val numberWords = mapOf("a" to 1, "an" to 1, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
        "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12, "thirteen" to 13,
        "fourteen" to 14, "fifteen" to 15, "sixteen" to 16, "seventeen" to 17, "eighteen" to 18, "nineteen" to 19,
        "twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50, "sixty" to 60, "ninety" to 90)

    private fun parseNum(s: String): Double? {
        s.toDoubleOrNull()?.let { return it }
        val parts = s.split(" ", "-").filter { it.isNotBlank() }
        if (parts.isEmpty()) return null
        var sum = 0
        for (p in parts) sum += numberWords[p] ?: return null
        return sum.toDouble()
    }

    private fun timer(t: String) {
        if (Regex("\\b(cancel|stop|delete|clear)\\b").containsMatchIn(t)) {
            TimerState.cancel(this); reply("Timer cancelled"); return
        }
        if (Regex("how (much|long)|time left|remaining").containsMatchIn(t)) {
            if (!TimerState.running) { reply("No timer is running"); return }
            val s = TimerState.remaining / 1000
            reply("${s / 60} min ${s % 60} s left", speak = "${s / 60} minutes and ${s % 60} seconds left"); return
        }
        var minutes = 0.0
        // Walk the words: each unit ("minutes", "hour", "10k"…) takes the number just before it.
        val words = t.replace(Regex("half an? hour"), "30 minutes").replace(Regex("([0-9])([a-z])"), "$1 $2").split(Regex("[\\s-]+")).filter { it.isNotBlank() }
        for ((i, w) in words.withIndex()) {
            val mult = when {
                w.startsWith("hour") || w == "hr" || w == "hrs" || w == "h" -> 60.0
                w.startsWith("min") || w == "k" || w == "m" -> 1.0
                w.startsWith("sec") || w == "s" -> 1.0 / 60
                else -> continue
            }
            val n = (if (i >= 2) parseNum(words[i - 2] + " " + words[i - 1]) else null)
                ?: (if (i >= 1) parseNum(words[i - 1]) else null) ?: continue
            minutes += n * mult
        }
        // Just a number ("timer 15"): minutes.
        if (minutes <= 0) words.firstNotNullOfOrNull { it.toDoubleOrNull() }?.let { minutes = it }
        if (minutes <= 0) {
            ask("For how long?") { ans -> timer("timer " + ans.lowercase()) }
            return
        }
        val mins = minutes.roundToInt().coerceAtLeast(1)
        TimerState.start(this, mins)
        val h = mins / 60; val m = mins % 60
        val say = listOfNotNull(if (h > 0) "$h hour${if (h > 1) "s" else ""}" else null, if (m > 0) "$m minute${if (m > 1) "s" else ""}" else null).joinToString(" ")
        reply("⏱ Timer set for $say", speak = "Timer set for $say")
    }

    // ---------------------------------------------------------------- open apps

    private fun openApp(t: String) {
        val name = Regex("(?:open|launch|start|go to)\\s+(.+)").find(t)?.groupValues?.get(1)?.trim()
            ?.removeSuffix(" app")?.removePrefix("the ") ?: t
        val app = findApp(name) ?: run { reply("Couldn't find “$name”", keep = true); return }
        reply("Opening ${app.second}", speak = null)
        main.postDelayed({
            runCatching {
                startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(app.first)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
            }
            finish(); overridePendingTransition(0, 0)
        }, 350)
    }

    private fun appsList(): List<Pair<android.content.ComponentName, String>> {
        val pm = packageManager
        return pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { android.content.ComponentName(it.activityInfo.packageName, it.activityInfo.name) to it.loadLabel(pm).toString() }
            .filter { it.first.packageName != packageName }
    }

    private fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9]"), "")

    private fun findApp(spoken: String): Pair<android.content.ComponentName, String>? {
        val apps = appsList()
        val q = norm(spoken)
        if (q.length < 2) return null
        apps.firstOrNull { norm(it.second) == q }?.let { return it }
        apps.firstOrNull { norm(it.second).startsWith(q) || q.startsWith(norm(it.second)) }?.let { return it }
        apps.firstOrNull { norm(it.second).contains(q) }?.let { return it }
        return apps.map { it to distance(norm(it.second), q) }.filter { it.second <= maxOf(1, q.length / 4) }
            .minByOrNull { it.second }?.first
    }

    private fun distance(a: String, b: String): Int {
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length)
            d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
        return d[a.length][b.length]
    }

    private fun appLabel(pkg: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg.substringAfterLast('.'))

    // ---------------------------------------------------------------- people (chats + contacts)

    /** A chat you can answer from here (has a Reply action), matched by name. */
    private fun chatFor(name: String): ChatInfo? {
        val q = norm(name); if (q.isEmpty()) return null
        val chats = (NotificationService.lastChat.values + NotificationService.chatHistory.values)
            .filter { it.reply != null }.sortedByDescending { it.time }
        return chats.firstOrNull { norm(it.title) == q } ?: chats.firstOrNull { norm(it.title).startsWith(q) }
            ?: chats.firstOrNull { norm(it.title).contains(q) }
    }

    /** Phone number for a contact name ("Mom", "John Smith"), or the digits if a number was said. */
    private fun contactNumber(name: String): Pair<String, String>? {
        val digits = name.replace(Regex("[^0-9+]"), "")
        if (digits.length >= 3 && name.none { it.isLetter() }) return name to digits
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        return runCatching {
            contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?", arrayOf("%$name%"), null)?.use { c ->
                var best: Pair<String, String>? = null
                while (c.moveToNext()) {
                    val n = c.getString(0) ?: continue; val num = c.getString(1) ?: continue
                    if (n.equals(name, true)) return@use n to num
                    if (best == null || (n.startsWith(name, true) && !best.first.startsWith(name, true))) best = n to num
                }
                best
            }
        }.getOrNull()
    }

    /** Split "john i'm on my way" into ("john", "i'm on my way") by trying the longest known name first. */
    private fun splitNameAndText(rest: String): Pair<String, String> {
        val words = rest.split(" ").filter { it.isNotBlank() }
        for (k in minOf(3, words.size) downTo 1) {
            val cand = words.take(k).joinToString(" ")
            if (chatFor(cand) != null || contactNumber(cand) != null) return cand to words.drop(k).joinToString(" ")
        }
        return (words.firstOrNull() ?: "") to words.drop(1).joinToString(" ")
    }

    // ---------------------------------------------------------------- messages

    private fun message(t: String) {
        val s = t.removePrefix("please ").trim()
        var name: String? = null; var text: String? = null
        Regex("(?:send )?(?:a )?(?:message|text|reply)(?: to)? (.+?) (?:saying|that says|that|says) (.+)").find(s)?.let { name = it.groupValues[1]; text = it.groupValues[2] }
        if (name == null) Regex("^tell (.+?) (?:that )?(.+)").find(s)?.let { val (n, x) = splitNameAndText(it.groupValues[1] + " " + it.groupValues[2]); name = n; text = x }
        if (name == null) Regex("^send (.+) to (.+)$").find(s)?.let { if (!it.groupValues[1].contains("message")) { text = it.groupValues[1]; name = it.groupValues[2] } }
        if (name == null) Regex("(?:send )?(?:a )?(?:message|text|reply)(?: to)?\\s*(.*)").find(s)?.let { m ->
            val rest = m.groupValues[1].trim()
            if (rest.isNotEmpty()) { val (n, x) = splitNameAndText(rest); name = n; text = x }
        }
        val who = name?.trim()?.removePrefix("to ")?.takeIf { it.isNotEmpty() && it != "a message" }
        if (who == null) { ask("Who should I message?") { ans -> if (ans.isBlank()) reply("Okay, never mind") else message("message ${ans.lowercase()}") }; return }
        val body = text?.trim().orEmpty()
        if (body.isEmpty()) { ask("What should I say to ${who.replaceFirstChar { it.uppercase() }}?") { ans -> if (ans.isBlank()) reply("Okay, never mind") else sendTo(who, ans) }; return }
        sendTo(who, body)
    }

    private fun sendTo(who: String, body: String) {
        val text = body.replaceFirstChar { it.uppercase() }
        val chat = chatFor(who)
        if (chat != null && chat.sendReply(this, text)) {
            reply("✓ Sent to ${chat.title} on ${appLabel(chat.pkg)}", speak = "Sent to ${chat.title}",
                card = "To ${chat.title} · ${appLabel(chat.pkg)}\n\n$text", keep = false)
            return
        }
        val c = contactNumber(who)
        if (c != null) {
            reply("Opening Messages to ${c.first} — tap send", speak = "Opening messages to ${c.first}")
            main.postDelayed({
                runCatching {
                    startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(c.second)))
                        .putExtra("sms_body", text).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }, 500)
            return
        }
        reply("Couldn't find “$who” in your chats or contacts",
            card = "I can reply in apps like Messenger when a chat with them has notified you recently, " +
                "or text anyone saved in your contacts (allow Contacts in Settings › Permissions & Setup).")
    }

    // ---------------------------------------------------------------- calls

    private fun call(t: String) {
        val name = Regex("^(?:call|phone|dial|ring)\\s+(.+)").find(t)?.groupValues?.get(1)?.removeSuffix(" please")?.trim()
        if (name.isNullOrBlank() || t.contains("make a call")) { ask("Who should I call?") { ans -> if (ans.isBlank()) reply("Okay") else call("call " + ans.lowercase()) }; return }
        val c = contactNumber(name) ?: run {
            reply("Couldn't find “$name” in your contacts", card = "Allow Contacts in Settings › Permissions & Setup, or say the number.")
            return
        }
        reply("📞 Calling ${c.first}", speak = "Calling ${c.first}")
        main.postDelayed({
            val uri = Uri.parse("tel:" + Uri.encode(c.second))
            val canCall = checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
            runCatching { startActivity(Intent(if (canCall) Intent.ACTION_CALL else Intent.ACTION_DIAL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }, 600)
    }

    private fun answerCall() {
        if (Build.VERSION.SDK_INT < 26) { reply("Not supported on this phone"); return }
        withPermission(Manifest.permission.ANSWER_PHONE_CALLS) {
            val tm = getSystemService(TelecomManager::class.java)
            @Suppress("MissingPermission")
            val ok = runCatching { if (tm.isInCall) { tm.acceptRingingCall(); true } else false }.getOrDefault(false)
            reply(if (ok) "📞 Answered" else "No call is ringing")
        }
    }

    private fun endCall() {
        if (Build.VERSION.SDK_INT < 28) { reply("Not supported on this phone"); return }
        withPermission(Manifest.permission.ANSWER_PHONE_CALLS) {
            val tm = getSystemService(TelecomManager::class.java)
            @Suppress("MissingPermission", "DEPRECATION")
            val ok = runCatching { tm.endCall() }.getOrDefault(false)
            reply(if (ok) "Call ended" else "No call to end")
        }
    }

    // ---------------------------------------------------------------- notes

    private fun note(t: String) {
        val list = notes(this)
        when {
            Regex("\\b(delete|clear|remove|erase)\\b.*\\b(all|notes)\\b").containsMatchIn(t) && !t.contains("last") -> {
                saveNotes(this, emptyList()); reply("🗑 All notes deleted")
            }
            Regex("\\b(delete|remove|erase)\\b.*\\blast\\b").containsMatchIn(t) -> {
                if (list.isEmpty()) reply("You have no notes") else {
                    val gone = list.removeAt(list.size - 1); saveNotes(this, list); reply("Deleted “${gone.second}”")
                }
            }
            Regex("\\b(read|show|list|what are|what's in|open|see|check)\\b.*\\bnotes?\\b|^(my )?notes$").containsMatchIn(t) -> {
                if (list.isEmpty()) { reply("You have no notes yet", card = "Say “take a note” and what to remember."); return }
                val fmt = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                val card = list.asReversed().joinToString("\n\n") { (time, x) -> "📝 $x\n   ${fmt.format(Date(time))}" }
                reply("📝 ${list.size} note${if (list.size > 1) "s" else ""}",
                    speak = "You have ${list.size} notes. " + list.takeLast(3).asReversed().joinToString(". ") { it.second }, card = card)
            }
            else -> {
                val text = Regex("(?:take|make|create|add|new|write|save)?\\s*(?:a |an )?(?:new )?note(?:s)?(?: that| saying| to| :)?\\s*(.*)|^remember(?: that| to)?\\s*(.*)|^remind me(?: to)?\\s*(.*)")
                    .find(t)?.groupValues?.drop(1)?.firstOrNull { it.isNotBlank() }?.trim()
                if (text.isNullOrBlank()) { ask("What should the note say?") { ans -> if (ans.isBlank()) reply("Okay") else addNote(ans) }; return }
                addNote(text)
            }
        }
    }

    private fun addNote(text: String) {
        val list = notes(this)
        val x = text.trim().replaceFirstChar { it.uppercase() }
        list += System.currentTimeMillis() to x
        saveNotes(this, list)
        reply("📝 Note saved", speak = "Saved", card = "📝 $x\n\nSay “read my notes” to hear them all.", keep = false)
    }

    // ---------------------------------------------------------------- report

    private val chatApps = listOf("messenger", "orca", "whatsapp", "telegram", "instagram", "viber", "messaging", "mms",
        "discord", "signal", "line", "skype", "snapchat", "slack", "teams", "wechat", "kakao", "imo", "messages")

    private fun report() {
        val since = System.currentTimeMillis() - 24 * 3600_000L
        val all = (NotificationService.chatHistory.values).filter { it.time >= since }.sortedByDescending { it.time }
        val isChat = { c: ChatInfo -> chatApps.any { c.pkg.lowercase().contains(it) } || c.messages.map { it.first }.any { it.isNotBlank() } }
        val chats = all.filter(isChat)
        val others = all.filterNot(isChat)
        val msgCount = chats.sumOf { maxOf(1, it.messages.size) }
        val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())
        val sb = StringBuilder()
        sb.append("📋 Report · $time\n")
        sb.append("💬 $msgCount message${if (msgCount == 1) "" else "s"} in ${chats.groupBy { it.pkg }.size} app${if (chats.groupBy { it.pkg }.size == 1) "" else "s"}")
        sb.append(" · 🔔 ${others.size} notification${if (others.size == 1) "" else "s"}\n")
        if (all.isEmpty()) sb.append("\nNothing new in the last 24 hours. 🎉\n")
        for ((pkg, list) in chats.groupBy { it.pkg }) {
            sb.append("\n").append(appLabel(pkg).uppercase()).append(" — ")
                .append(list.sumOf { maxOf(1, it.messages.size) }).append(" from ").append(list.size)
                .append(if (list.size == 1) " chat\n" else " chats\n")
            for (c in list.take(5)) {
                val last = c.messages.lastOrNull()
                val lm = last?.second?.takeIf { it.isNotBlank() } ?: c.media.lastOrNull()?.let { "${it.emoji} ${it.label}" } ?: ""
                sb.append("  • ").append(c.title)
                if (c.messages.size > 1) sb.append(" (").append(c.messages.size).append(")")
                sb.append(": ").append(lm.take(90)).append("  · ").append(ago(c.time)).append("\n")
            }
            if (list.size > 5) sb.append("  … and ${list.size - 5} more\n")
        }
        if (others.isNotEmpty()) {
            sb.append("\nNOTIFICATIONS\n")
            for ((pkg, list) in others.groupBy { it.pkg }) {
                sb.append("  • ").append(appLabel(pkg))
                if (list.size > 1) sb.append(" (").append(list.size).append(")")
                val c = list.first()
                val line = listOf(c.title, c.messages.lastOrNull()?.second.orEmpty()).filter { it.isNotBlank() }.joinToString(" — ")
                sb.append(": ").append(line.take(90)).append("\n")
            }
        }
        val notes = notes(this)
        if (notes.isNotEmpty()) sb.append("\n📝 ${notes.size} note${if (notes.size > 1) "s" else ""} — latest: ${notes.last().second}\n")
        if (TimerState.running) { val s = TimerState.remaining / 1000; sb.append("⏱ Timer: ${s / 60}:${"%02d".format(s % 60)} left\n") }

        // What it says out loud: the short version.
        val speak = buildString {
            if (all.isEmpty()) { append("You're all caught up. Nothing new today."); return@buildString }
            append("You have $msgCount messages")
            val top = chats.groupBy { it.pkg }.entries.sortedByDescending { e -> e.value.sumOf { maxOf(1, it.messages.size) } }
            if (top.isNotEmpty()) append(", mostly on ${appLabel(top.first().key)}")
            append(". ")
            for (c in chats.take(3)) {
                val last = c.messages.lastOrNull()?.second?.takeIf { it.isNotBlank() } ?: continue
                append("${c.title} said: ${last.take(80)}. ")
            }
            if (others.isNotEmpty()) append("And ${others.size} other notifications.")
        }
        reply("📋 Here's your report", speak = speak, card = sb.toString().trimEnd())
    }

    private fun ago(t: Long): String {
        val m = ((System.currentTimeMillis() - t) / 60000).coerceAtLeast(0)
        return when { m < 1 -> "now"; m < 60 -> "${m}m"; m < 1440 -> "${m / 60}h"; else -> "${m / 1440}d" }
    }

    // ---------------------------------------------------------------- chime

    private fun chime() {
        Thread {
            runCatching {
                val rate = 44100
                val notes = listOf(880.0 to 0.12, 1318.5 to 0.22)
                val pcm = ShortArray(notes.sumOf { (rate * it.second).toInt() })
                var i = 0
                for ((f, d) in notes) {
                    val n = (rate * d).toInt()
                    for (k in 0 until n) {
                        val env = kotlin.math.min(1.0, k / (rate * 0.01)) * kotlin.math.exp(-3.0 * k / n)
                        pcm[i++] = (kotlin.math.sin(2 * Math.PI * f * k / rate) * env * 9000).toInt().toShort()
                    }
                }
                val track = android.media.AudioTrack.Builder()
                    .setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    .setAudioFormat(android.media.AudioFormat.Builder().setSampleRate(rate).setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(pcm.size * 2).setTransferMode(android.media.AudioTrack.MODE_STATIC).build()
                track.write(pcm, 0, pcm.size); track.play()
                Thread.sleep(600); track.release()
            }
        }.start()
    }
}
