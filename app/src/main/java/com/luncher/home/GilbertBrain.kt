package com.luncher.home

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import java.io.File
import java.util.concurrent.Executors

/**
 * Gilbert's offline brain: a small language model that runs entirely on the phone (Google
 * MediaPipe). You add the brain file once (Settings › Gilbert › Brain); nothing is sent to the
 * internet. Without it, Gilbert answers from his memory and rules instead.
 */
object GilbertBrain {
    private val exec = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    @Volatile var busy = false; private set

    /** Where to get a brain file (a .task model for MediaPipe). */
    const val DOWNLOAD_URL = "https://huggingface.co/litert-community"

    fun file(ctx: Context) = File(ctx.filesDir, "gilbert_brain.task")
    fun installed(ctx: Context) = file(ctx).let { it.exists() && it.length() > 10_000_000 }
    fun sizeMb(ctx: Context) = (file(ctx).length() / 1_000_000)
    /** The name of the brain file you picked (e.g. "Qwen2.5-1.5B-Instruct"). */
    fun modelName(ctx: Context): String? = prefs(ctx).getString("name", null)
        ?.substringBefore("_multi")?.substringBefore("_seq")?.substringBefore(".task")

    /** Biggest brain we allow — larger files (like the 2 GB "f32" ones) crash phones. */
    private const val MAX_BYTES = 1_800_000_000L

    /** Last problem with the brain (shown in the chat / Settings). */
    @Volatile var lastError: String? = null

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("gil_brain", Context.MODE_PRIVATE)

    /** Copy the chosen brain file into the app (runs in the background). Your old brain stays until the new one is fully copied. */
    /** Name and size of a picked file (for the "too heavy?" warning). */
    fun describe(ctx: Context, uri: Uri): Pair<String, Long> {
        var name = ""; var size = -1L
        runCatching {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    val si = c.getColumnIndex(android.provider.OpenableColumns.SIZE)
                    if (ni >= 0) name = c.getString(ni) ?: ""
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                }
            }
        }
        return name to size
    }
    fun isHeavy(name: String, size: Long) = name.lowercase().contains("f32") || size > MAX_BYTES

    fun install(ctx: Context, uri: Uri, force: Boolean = false, done: (Boolean, String) -> Unit) {
        val app = ctx.applicationContext
        exec.execute {
            val r = runCatching {
                var name = ""; var size = -1L
                app.contentResolver.query(uri, null, null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val ni = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        val si = c.getColumnIndex(android.provider.OpenableColumns.SIZE)
                        if (ni >= 0) name = c.getString(ni) ?: ""
                        if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                    }
                }
                val low = name.lowercase()
                if (name.isNotEmpty() && !low.endsWith(".task"))
                    error("That's not a brain file — pick one that ends in “.task”")
                if (!force && (low.contains("f32") || size > MAX_BYTES))
                    error("That brain is too heavy for this phone (${if (size > 0) "${size / 1_000_000} MB" else "f32"}) and would crash it. Pick the “q8” .task file (about 0.5–1.6 GB).")
                val free = android.os.StatFs(app.filesDir.path).availableBytes
                if (size > 0 && free < size + 300_000_000L)
                    error("Not enough free storage — need about ${(size + 300_000_000L) / 1_000_000} MB free")
                val tmp = File(app.filesDir, "gilbert_brain.tmp")
                app.contentResolver.openInputStream(uri)!!.use { input -> tmp.outputStream().use { input.copyTo(it, 1 shl 20) } }
                if (tmp.length() < 10_000_000) { tmp.delete(); error("That file is too small to be a brain (.task model)") }
                if (!force && tmp.length() > MAX_BYTES) { tmp.delete(); error("That brain is too heavy for this phone and would crash it. Pick a smaller “q8” .task file.") }
                // Only now swap the old brain for the new one.
                release()
                file(app).delete(); tmp.renameTo(file(app))
                prefs(app).edit().putBoolean("crashed", false).putInt("crashes", 0).putBoolean("heavy", force)
                    .putString("name", name.ifBlank { null }).apply()
                lastError = null
            }
            if (r.isFailure) File(app.filesDir, "gilbert_brain.tmp").delete()
            main.post { done(r.isSuccess, r.exceptionOrNull()?.message ?: "Gilbert's brain is ready 🧠") }
        }
    }

    /** Is the brain switched off because it kept closing? (A new launcher version starts fresh.) */
    fun crashed(ctx: Context): Boolean {
        val p = prefs(ctx)
        val updated = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime }.getOrDefault(0L)
        if (p.getLong("seenUpdate", 0L) != updated)
            p.edit().putLong("seenUpdate", updated).putBoolean("crashed", false).putInt("crashes", 0).apply()
        return p.getBoolean("crashed", false)
    }

    /** Give a switched-off brain another go. */
    fun retry(ctx: Context) {
        prefs(ctx).edit().putBoolean("crashed", false).putInt("crashes", 0).apply()
        lastError = null
    }

    fun remove(ctx: Context) {
        prefs(ctx).edit().putBoolean("crashed", false).putInt("crashes", 0).apply()
        lastError = null
        release()
        exec.execute { file(ctx).delete() }
    }

    // ---------------------------------------------------------------- the brain's own process
    // The brain runs in BrainService (":brain"). If the phone runs out of memory, only that
    // process closes — never the launcher.
    private var conn: ServiceConnection? = null
    private var svc: Messenger? = null
    private val waiting = ArrayList<Message>()
    private val callbacks = HashMap<Int, (String?, String?) -> Unit>()
    private var nextId = 1
    @Volatile private var ready = false
    private var warming = false
    private var appCtx: Context? = null

    private val replies by lazy { Messenger(Handler(Looper.getMainLooper()) { m ->
        val d = m.data
        val err = d?.getString("err")
        if (err == null) { ready = true; lastError = null; appCtx?.let { prefs(it).edit().putInt("crashes", 0).apply() } }
        else lastError = err
        callbacks.remove(m.arg1)?.invoke(d?.getString("text"), err)
        true
    }) }

    /** Send a job to the brain process (main thread only). */
    private fun send(ctx: Context, what: Int, extra: Bundle, cb: (String?, String?) -> Unit) {
        val app = ctx.applicationContext; appCtx = app
        val id = nextId++; callbacks[id] = cb
        extra.putString("path", file(app).absolutePath)
        extra.putBoolean("heavy", prefs(app).getBoolean("heavy", false))
        val m = Message.obtain(null, what, id, 0).apply { data = extra; replyTo = replies }
        val s = svc
        if (s != null) { if (runCatching { s.send(m) }.isFailure) died(app); return }
        waiting += m
        if (conn == null) bind(app)
    }

    private fun bind(app: Context) {
        val c = object : ServiceConnection {
            override fun onServiceConnected(n: ComponentName?, b: IBinder?) {
                if (b == null) return
                svc = Messenger(b)
                runCatching { b.linkToDeath({ main.post { died(app) } }, 0) }
                val q = waiting.toList(); waiting.clear()
                q.forEach { runCatching { svc?.send(it) } }
            }
            override fun onServiceDisconnected(n: ComponentName?) { died(app) }
        }
        conn = c
        val ok = runCatching { app.bindService(Intent(app, BrainService::class.java), c, Context.BIND_AUTO_CREATE) }.getOrDefault(false)
        if (!ok) { conn = null; lastError = "The brain couldn't start on this phone."; failAll() }
    }

    private fun failAll() {
        val cbs = callbacks.values.toList(); callbacks.clear(); waiting.clear()
        cbs.forEach { it(null, lastError) }
    }

    /** The brain process closed (usually: the phone needed the memory back). */
    private fun died(app: Context) {
        if (conn == null && svc == null) return
        val working = callbacks.isNotEmpty() || waiting.isNotEmpty()
        runCatching { conn?.let { app.unbindService(it) } }
        conn = null; svc = null; ready = false
        if (working) {
            // It closed while loading/thinking: count it; switch off after twice in a row.
            val n = prefs(app).getInt("crashes", 0) + 1
            prefs(app).edit().putInt("crashes", n).putBoolean("crashed", n >= 2).apply()
            lastError = if (n >= 2) "My brain closed twice — it's too heavy for the phone's free memory, so I switched it off (the launcher is fine). Open 🧠 and tap “Try Again” after closing some apps, or use the smaller 0.5B q8 brain."
                else "My brain needed more memory than the phone had free and closed — the launcher is fine. Close a few apps and ask me again."
        }
        failAll()
    }

    private fun release() = main.post {
        runCatching { conn?.let { appCtx?.unbindService(it) } }
        conn = null; svc = null; ready = false; warming = false
        callbacks.clear(); waiting.clear()
    }

    /** Is the brain loaded and ready right now? */
    val loaded get() = ready

    /** Start loading the brain in the background (so the first answer comes quicker). */
    fun warmUp(ctx: Context) {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { warmUp(ctx) }; return }
        if (!installed(ctx) || ready || warming || crashed(ctx)) return
        warming = true; lastError = null
        send(ctx, BrainService.LOAD, Bundle()) { _, _ -> warming = false }
    }

    /**
     * Ask Gilbert something. [partial] gets the answer typed out word by word (on the main thread);
     * [done] gets his final answer (or null if the brain couldn't run).
     */
    fun ask(ctx: Context, history: List<Pair<Boolean, String>>, question: String,
            partial: ((String) -> Unit)? = null, done: (String?) -> Unit) {
        val app = ctx.applicationContext
        if (!installed(app)) { done(null); return }
        if (crashed(app)) { lastError = "My brain is switched off (it kept closing). Open 🧠 and tap “Try Again”."; done(null); return }
        busy = true
        exec.execute {
            val p = runCatching { prompt(app, history, question) }.getOrDefault(question)
            main.post {
                send(app, BrainService.ASK, Bundle().apply { putString("prompt", p) }) { raw, _ ->
                    val answer = raw?.let { runCatching { clean(it) }.getOrNull() }?.takeIf { it.isNotBlank() }
                    if (answer == null || partial == null) { busy = false; done(answer); return@send }
                    // Type it out quickly, word by word, like he's talking.
                    val words = answer.split(" ")
                    main.post(object : Runnable {
                        var n = 0
                        override fun run() {
                            n++
                            partial(words.take(n).joinToString(" "))
                            if (n < words.size) main.postDelayed(this, 35) else { busy = false; done(answer) }
                        }
                    })
                }
            }
        }
    }

    /**
     * Pocher's digest: turn a list of messages into a few factual bullet points (no chatting).
     * [done] gets the bullets, or null if the brain couldn't run.
     */
    fun digest(ctx: Context, lines: List<String>, done: (String?) -> Unit) {
        val app = ctx.applicationContext
        if (!installed(app) || crashed(app) || busy) { done(null); return }
        busy = true
        val sys = "You are Gilbert, a calm, precise assistant like JARVIS. Turn the chat messages into a short factual digest: " +
            "3 to 5 bullet points, each starting with \"• \", naming who said what and anything to do or remember. " +
            "No greetings, no advice, no questions."
        val body = StringBuilder()
        for (l in lines) { if (body.length + l.length > 1400) break; body.append(l.take(160)).append('\n') }
        val p = "<|im_start|>system\n$sys<|im_end|>\n<|im_start|>user\nMessages:\n$body<|im_end|>\n<|im_start|>assistant\n"
        send(app, BrainService.ASK, Bundle().apply { putString("prompt", p) }) { raw, _ ->
            busy = false
            var t = raw ?: ""
            for (stop in listOf("<|im_end|>", "<|im_start|>")) t = t.substringBefore(stop)
            done(t.replace("\\n", "\n").trim().takeIf { it.isNotBlank() })
        }
    }

    /** A short slice of the "About you" profile for the brain's prompt (small brains need short prompts). */
    fun profile(ctx: Context, max: Int): String {
        val t = ctx.getSharedPreferences("gil_profile", Context.MODE_PRIVATE).getString("about", "") ?: ""
        if (t.isBlank()) return ""
        return "About them: " + t.replace(Regex("[#*_>-]+"), " ").replace(Regex("\\s+"), " ").trim().take(max) + ". "
    }

    /** Gilbert's persona (from Gilbert_Brain/persona.txt). */
    const val PERSONA = "You are Gilbert, John's private voice assistant for school, coding, and client work. " +
        "Speak with calm precision and occasional understated dry wit. Lead with the result or next action. " +
        "Default to one to three short spoken sentences. Use English, Tagalog, or Taglish to match John. " +
        "Address him as John occasionally. If uncertain, say what is missing and ask one focused question. " +
        "Use humor sparingly and skip it when the stakes are serious. " +
        "Never claim to have saved, sent, scheduled, or completed an action unless the app confirms success. " +
        "Never invent client details, deadlines, grades, or test results. " +
        "For long code or plans, give a short spoken summary and put the details in text."

    /** Cut the answer at any chat markers (keeps the full text — the console shows it; he speaks the start). */
    private fun clean(raw: String): String {
        var t = raw
        for (stop in listOf("<|im_end|>", "<|im_start|>", "<end_of_turn>", "<start_of_turn>", "\nUser:", "\nuser\n", "\nGilbert:"))
            t = t.substringBefore(stop)
        t = t.replace("\\n", "\n").replace("\\t", " ")
        return t.trim().removePrefix("Gilbert:").removePrefix("assistant").trim().take(1600)
    }

    /** The first one to three sentences (what he says out loud). */
    fun spoken(text: String): String {
        val first = text.substringBefore("```").replace(Regex("\\s+"), " ").trim()
        return first.split(Regex("(?<=[.!?])\\s+")).take(3).joinToString(" ").take(300)
    }

    /**
     * A short prompt in the chat format the model was trained on: his persona, a little context
     * (name, a few notes, open tasks, time, relevant messages), the last two turns, your message.
     */
    private fun prompt(ctx: Context, history: List<Pair<Boolean, String>>, q: String): String {
        val facts = StringBuilder()
        GilbertNarrator.ownerName(ctx)?.let { facts.append("His name: $it. ") }
        GilbertMemory.facts(ctx).take(4).takeIf { it.isNotEmpty() }?.let { facts.append("Notes: ${it.joinToString("; ") { n -> n.take(80) }}. ") }
        GilbertTasks.open(ctx).take(4).takeIf { it.isNotEmpty() }?.let { facts.append("Open tasks: ${it.joinToString("; ") { t -> t.title.take(60) }}. ") }
        facts.append("Now: ${java.text.SimpleDateFormat("EEE MMM d, h:mm a", java.util.Locale.getDefault()).format(java.util.Date())} (Asia/Manila).")
        GilbertArchive.search(ctx, q, 2).takeIf { it.isNotEmpty() }?.let { facts.append(" Related messages: ${it.joinToString(" / ") { l -> l.take(100) }}.") }
        val system = PERSONA + " " + profile(ctx, 400) + "Context: " + facts
        val sb = StringBuilder()
        sb.append("<|im_start|>system\n").append(system).append("<|im_end|>\n")
        // Two recent turns (like the desktop brain).
        for ((user, text) in history.takeLast(4)) {
            sb.append(if (user) "<|im_start|>user\n" else "<|im_start|>assistant\n").append(text.take(200)).append("<|im_end|>\n")
        }
        sb.append("<|im_start|>user\n").append(q.take(400)).append("<|im_end|>\n<|im_start|>assistant\n")
        return sb.toString()
    }
}
