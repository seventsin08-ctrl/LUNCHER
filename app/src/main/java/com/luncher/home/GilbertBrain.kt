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
                .setMaxTokens(768)
                .build())
        }.getOrNull()
        return llm
    }

    /** Ask Gilbert something. [done] gets his answer (or null if the brain couldn't run). */
    fun ask(ctx: Context, history: List<Pair<Boolean, String>>, question: String, done: (String?) -> Unit) {
        busy = true
        val app = ctx.applicationContext
        exec.execute {
            val answer = runCatching {
                val m = load(app) ?: return@runCatching null
                m.generateResponse(prompt(app, history, question)).trim()
                    .substringBefore("User:").substringBefore("<end_of_turn>").trim()
            }.getOrNull()
            main.post { busy = false; done(answer?.takeIf { it.isNotBlank() }) }
        }
    }

    /** Who Gilbert is + what he knows about you + the chat so far. */
    private fun prompt(ctx: Context, history: List<Pair<Boolean, String>>, q: String): String {
        val h = runCatching { Gilbert.health(ctx) }.getOrNull()
        val sb = StringBuilder()
        sb.append("You are Gilbert, a small glowing orb living in the user's phone launcher. ")
        sb.append("You are their warm, playful best friend and helpful assistant. Answer in 1-3 short sentences, casual, kind, ")
        sb.append("sometimes with one emoji. Never make things up about their messages — only use what is listed below.\n\n")
        sb.append("What you know about them:\n").append(GilbertMemory.profile(ctx)).append("\n")
        sb.append(GilbertMemory.recap(ctx)).append("\n")
        Gilbert.notes(ctx).take(8).takeIf { it.isNotEmpty() }?.let { n ->
            sb.append("Latest messages:\n")
            for (x in n) sb.append("- ${x.title}: ${x.text.take(80)}\n")
        }
        h?.let { sb.append("Phone: battery ${it.battery}%${if (it.charging) " (charging)" else ""}, ${it.tempC?.toInt() ?: "?"}°C.\n") }
        sb.append("Time: ${java.text.SimpleDateFormat("EEE h:mm a", java.util.Locale.getDefault()).format(java.util.Date())}\n\n")
        for ((user, text) in history.takeLast(6)) sb.append(if (user) "User: " else "Gilbert: ").append(text).append("\n")
        sb.append("User: ").append(q).append("\nGilbert:")
        return sb.toString()
    }
}
