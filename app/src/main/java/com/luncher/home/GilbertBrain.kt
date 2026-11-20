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

    /** Copy the chosen brain file into the app (runs in the background). */
    fun install(ctx: Context, uri: Uri, done: (Boolean, String) -> Unit) {
        exec.execute {
            val r = runCatching {
                release()
                val tmp = File(ctx.filesDir, "gilbert_brain.tmp")
                ctx.contentResolver.openInputStream(uri)!!.use { input -> tmp.outputStream().use { input.copyTo(it, 1 shl 20) } }
                if (tmp.length() < 10_000_000) { tmp.delete(); error("That file is too small to be a brain (.task model)") }
                file(ctx).delete(); tmp.renameTo(file(ctx))
            }
            main.post { done(r.isSuccess, r.exceptionOrNull()?.message ?: "Gilbert's brain is ready 🧠") }
        }
    }

    fun remove(ctx: Context) { exec.execute { release(); file(ctx).delete() } }

    private fun release() { runCatching { llm?.close() }; llm = null }

    private fun load(ctx: Context): LlmInference? {
        llm?.let { return it }
        if (!installed(ctx)) return null
        llm = runCatching {
            LlmInference.createFromOptions(ctx, LlmInference.LlmInferenceOptions.builder()
                .setModelPath(file(ctx).absolutePath)
                .setMaxTokens(1024)
                .build())
        }.getOrNull()
        return llm
    }

    /** Start loading the brain in the background (so the first answer comes quicker). */
    fun warmUp(ctx: Context) {
        if (!installed(ctx) || llm != null) return
        val app = ctx.applicationContext
        exec.execute { load(app) }
    }

    /** Ask Gilbert something. [done] gets his answer (or null if the brain couldn't run). */
    fun ask(ctx: Context, history: List<Pair<Boolean, String>>, question: String, done: (String?) -> Unit) {
        busy = true
        val app = ctx.applicationContext
        exec.execute {
            val answer = runCatching {
                val m = load(app) ?: return@runCatching null
                clean(m.generateResponse(prompt(app, history, question)))
            }.getOrNull()
            main.post { busy = false; done(answer?.takeIf { it.isNotBlank() }) }
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
