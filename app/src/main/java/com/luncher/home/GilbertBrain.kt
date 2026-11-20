package com.luncher.home

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.google.mediapipe.tasks.genai.llminference.LlmInference
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
    private var llm: LlmInference? = null
    @Volatile var busy = false; private set

    /** Where to get a brain file (a .task model for MediaPipe). */
    const val DOWNLOAD_URL = "https://huggingface.co/litert-community"

    fun file(ctx: Context) = File(ctx.filesDir, "gilbert_brain.task")
    fun installed(ctx: Context) = file(ctx).let { it.exists() && it.length() > 10_000_000 }
    fun sizeMb(ctx: Context) = (file(ctx).length() / 1_000_000)

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
                prefs(app).edit().putBoolean("loading", false).putBoolean("crashed", false).putBoolean("heavy", force).apply()
                lastError = null
            }
            if (r.isFailure) File(app.filesDir, "gilbert_brain.tmp").delete()
            main.post { done(r.isSuccess, r.exceptionOrNull()?.message ?: "Gilbert's brain is ready 🧠") }
        }
    }

    /** Did the last brain crash the launcher while loading? (Then it stays off until you pick another.) */
    fun crashed(ctx: Context): Boolean {
        val p = prefs(ctx)
        // "loading" still set = the app died while loading the brain last time.
        if (p.getBoolean("loading", false)) p.edit().putBoolean("loading", false).putBoolean("crashed", true).apply()
        return p.getBoolean("crashed", false)
    }

    fun remove(ctx: Context) {
        prefs(ctx).edit().putBoolean("loading", false).putBoolean("crashed", false).apply()
        lastError = null
        exec.execute { release(); file(ctx).delete() }
    }

    private fun release() { runCatching { llm?.close() }; llm = null }

    private fun load(ctx: Context): LlmInference? {
        llm?.let { return it }
        if (!installed(ctx)) return null
        if (crashed(ctx)) { lastError = "This brain crashed the launcher, so I switched it off. Pick a smaller “q8” .task file in Settings."; return null }
        // Enough memory? (A brain needs roughly its own size in free RAM.)
        val am = ctx.getSystemService(android.app.ActivityManager::class.java)
        val mi = android.app.ActivityManager.MemoryInfo(); runCatching { am.getMemoryInfo(mi) }
        // Heavy brains you chose anyway get a looser check (they may still be too much for the phone).
        val need = if (prefs(ctx).getBoolean("heavy", false)) 0.55 else 0.9
        if (mi.availMem > 0 && mi.availMem < file(ctx).length() * need) {
            lastError = "Not enough free memory right now (${mi.availMem / 1_000_000} MB). Close some apps and try again."
            return null
        }
        val p = prefs(ctx)
        p.edit().putBoolean("loading", true).commit()   // if we crash during this, next time we know
        llm = runCatching {
            LlmInference.createFromOptions(ctx, LlmInference.LlmInferenceOptions.builder()
                .setModelPath(file(ctx).absolutePath)
                .setMaxTokens(640)   // smaller = starts answering sooner
                .build())
        }.onFailure { lastError = "The brain couldn't start (${it.message?.take(60) ?: "unknown"}). Try the q8 .task file." }.getOrNull()
        p.edit().putBoolean("loading", false).apply()
        if (llm != null) lastError = null
        return llm
    }

    /** Start loading the brain in the background (so the first answer comes quicker). */
    fun warmUp(ctx: Context) {
        if (!installed(ctx) || llm != null) return
        val app = ctx.applicationContext
        exec.execute { load(app) }
    }

    /**
     * Ask Gilbert something. [partial] gets the answer typed out word by word (on the main thread);
     * [done] gets his final answer (or null if the brain couldn't run).
     */
    fun ask(ctx: Context, history: List<Pair<Boolean, String>>, question: String,
            partial: ((String) -> Unit)? = null, done: (String?) -> Unit) {
        busy = true
        val app = ctx.applicationContext
        exec.execute {
            val answer = runCatching {
                val m = load(app) ?: return@runCatching null
                clean(m.generateResponse(prompt(app, history, question)))
            }.getOrNull()?.takeIf { it.isNotBlank() }
            if (answer == null || partial == null) { main.post { busy = false; done(answer) }; return@execute }
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

    /** Cut the answer at any chat markers and keep it short. */
    private fun clean(raw: String): String {
        var t = raw
        for (stop in listOf("<|im_end|>", "<|im_start|>", "<end_of_turn>", "<start_of_turn>", "\nUser:", "\nuser", "\nGilbert:"))
            t = t.substringBefore(stop)
        // Some answers come with a literal "\\n" — turn it into a space.
        t = t.replace("\\n", " ").replace("\\t", " ")
        t = t.trim().removePrefix("Gilbert:").removePrefix("assistant").trim()
        // At most 3 sentences.
        val parts = t.split(Regex("(?<=[.!?])\\s+"))
        return parts.take(3).joinToString(" ").trim()
    }


    /**
     * A SHORT prompt in the chat format the model was trained on (long prompts made it slow and
     * made it repeat the facts back instead of answering).
     */
    private fun prompt(ctx: Context, history: List<Pair<Boolean, String>>, q: String): String {
        val facts = StringBuilder()
        GilbertTalk.ownerName(ctx)?.let { facts.append("Their name: $it. ") }
        GilbertMemory.facts(ctx).take(6).takeIf { it.isNotEmpty() }?.let { facts.append("About them: ${it.joinToString("; ")}. ") }
        GilbertMemory.topArtists(ctx, 3).takeIf { it.isNotEmpty() }?.let { facts.append("Loves: ${it.joinToString { a -> a.first }}. ") }
        GilbertMemory.topPeople(ctx, 3).takeIf { it.isNotEmpty() }?.let { facts.append("Chats with: ${it.joinToString { p -> p.first }}. ") }
        facts.append("Time: ${java.text.SimpleDateFormat("EEE h:mm a", java.util.Locale.getDefault()).format(java.util.Date())}.")
        val name = GilbertTalk.ownerName(ctx) ?: "friend"
        val unread = Gilbert.heldCount()
        val system = "You are Gilbert, $name's personal assistant and best friend, living in their phone. " +
            "Personality: professional, calm and capable like a great executive assistant, but warm and loyal like a best friend. " +
            "Speak clearly in 1-2 short sentences. Be specific and helpful, offer a next step when useful, " +
            "use their name now and then, at most one emoji. Never say \"How can I assist you today\". " +
            "Facts: $facts" + (if (unread > 0) " They have $unread waiting notifications." else "")
        val sb = StringBuilder()
        sb.append("<|im_start|>system\n").append(system).append("<|im_end|>\n")
        // One example so the small brain gets the tone right.
        sb.append("<|im_start|>user\nhey<|im_end|>\n<|im_start|>assistant\n")
            .append("Hey $name, good to see you. Everything's under control — want a quick rundown of your messages? 🙂<|im_end|>\n")
        for ((user, text) in history.takeLast(4)) {
            sb.append(if (user) "<|im_start|>user\n" else "<|im_start|>assistant\n").append(text.take(160)).append("<|im_end|>\n")
        }
        sb.append("<|im_start|>user\n").append(q.take(200)).append("<|im_end|>\n<|im_start|>assistant\n")
        return sb.toString()
    }
}
