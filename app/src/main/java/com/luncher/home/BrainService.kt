package com.luncher.home

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import java.io.File

/**
 * Gilbert's brain runs here, in its OWN process (":brain"). A big brain can use more memory
 * than the phone has to spare; if Android has to close it, only this process dies — the
 * launcher keeps running and Gilbert falls back to his memory mode.
 */
class BrainService : Service() {
    companion object {
        const val LOAD = 1; const val ASK = 2; const val LOADED = 11; const val ANSWER = 12
    }

    private var llm: LlmInference? = null
    private lateinit var worker: Handler

    override fun onCreate() {
        super.onCreate()
        val t = HandlerThread("gilbert-brain").apply { start() }
        worker = Handler(t.looper) { m -> handle(m); true }
    }

    override fun onBind(intent: Intent?): IBinder = Messenger(Handler(mainLooper) { m ->
        // Copy the message: the original is recycled once this returns.
        worker.sendMessage(Message.obtain(m)); true
    }).binder

    private fun reply(to: Messenger?, what: Int, id: Int, b: Bundle) {
        runCatching { to?.send(Message.obtain(null, what, id, 0).apply { data = b }) }
    }

    private fun handle(m: Message) {
        val path = m.data?.getString("path") ?: return
        when (m.what) {
            LOAD -> {
                val err = load(path, m.data.getBoolean("heavy"))
                reply(m.replyTo, LOADED, m.arg1, Bundle().apply { putString("err", err) })
            }
            ASK -> {
                var err = load(path, m.data.getBoolean("heavy"))
                val text = if (err != null) null else runCatching { llm!!.generateResponse(m.data.getString("prompt") ?: "") }
                    .onFailure { err = "The brain stumbled (${it.message?.take(60) ?: "unknown"})" }.getOrNull()
                reply(m.replyTo, ANSWER, m.arg1, Bundle().apply { putString("text", text); putString("err", err) })
            }
        }
    }

    /** Load the brain once. Returns an error message, or null when it's ready. */
    private fun load(path: String, heavy: Boolean): String? {
        if (llm != null) return null
        val f = File(path)
        if (!f.exists()) return "No brain file"
        // Enough free memory? (A brain needs roughly its own size in free RAM.)
        val am = getSystemService(android.app.ActivityManager::class.java)
        val mi = android.app.ActivityManager.MemoryInfo(); runCatching { am.getMemoryInfo(mi) }
        val need = if (heavy) 0.55 else 0.8
        if (mi.availMem > 0 && mi.availMem < f.length() * need)
            return "Not enough free memory right now (${mi.availMem / 1_000_000} MB free, the brain needs about ${(f.length() * need / 1_000_000).toLong()} MB). Close some apps and try again."
        return runCatching {
            llm = LlmInference.createFromOptions(this, LlmInference.LlmInferenceOptions.builder()
                .setModelPath(path)
                .setMaxTokens(1024)   // room for a digest of many messages
                .build())
            null
        }.getOrElse { "The brain couldn't start (${it.message?.take(60) ?: "unknown"}). Try the q8 .task file." }
    }

    override fun onDestroy() {
        runCatching { llm?.close() }; llm = null
        // Free the brain's memory completely.
        android.os.Process.killProcess(android.os.Process.myPid())
        super.onDestroy()
    }
}
