package com.luncher.home

import android.content.Context
import java.io.File

/**
 * If the launcher ever closes because of a bug, write down why — Gilbert tells you next time
 * you open his chat (so you can send me a screenshot and I can fix it).
 */
object CrashLog {
    private var installed = false
    private fun file(ctx: Context) = File(ctx.filesDir, "last_crash.txt")

    fun install(ctx: Context) {
        if (installed) return
        installed = true
        val app = ctx.applicationContext
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                val trace = e.stackTraceToString().lines().filter { it.isNotBlank() }
                val ours = trace.firstOrNull { it.contains("com.luncher") }?.trim() ?: ""
                file(app).writeText("${e.javaClass.simpleName}: ${e.message?.take(120) ?: ""}\n$ours")
            }
            prev?.uncaughtException(t, e)
        }
    }

    /** The last crash (once), or null. */
    fun take(ctx: Context): String? {
        val f = file(ctx)
        if (!f.exists()) return null
        val s = runCatching { f.readText() }.getOrNull()
        f.delete()
        return s?.takeIf { it.isNotBlank() }
    }
}
