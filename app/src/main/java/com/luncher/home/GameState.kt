package com.luncher.home

import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Rect
import android.os.Build

/**
 * Knows when you're playing a game (the app on screen is a game), so the islands, chat windows
 * and pop-ups get out of the way. The Sidebar stays — and apps opened from it then appear in a
 * small floating window instead of covering the game.
 */
object GameState {
    var playing = false
        private set
    var foreground: String? = null
        private set
    private val gameCache = HashMap<String, Boolean>()
    private val listeners = LinkedHashSet<() -> Unit>()
    fun listen(fn: () -> Unit) { listeners += fn }
    fun unlisten(fn: () -> Unit) { listeners -= fn }

    /** Called by the accessibility helper whenever another app's window comes to the front. */
    fun onForeground(ctx: Context, pkg: String?) {
        if (pkg == null) return
        // Our own floating windows, the status bar and the keyboard don't change what's "on screen".
        if (pkg == ctx.packageName || pkg == "com.android.systemui" || pkg.contains("inputmethod") ||
            pkg.contains("keyboard") || pkg.startsWith("com.vivo.gamewatch") || pkg.contains("gamecube")) return
        foreground = pkg
        val game = Settings(ctx).gameHide && isGame(ctx, pkg)
        if (game != playing) {
            playing = game
            for (l in listeners.toList()) runCatching { l() }
        }
    }

    private fun isGame(ctx: Context, pkg: String): Boolean = gameCache.getOrPut(pkg) {
        runCatching {
            val ai = ctx.packageManager.getApplicationInfo(pkg, 0)
            @Suppress("DEPRECATION")
            ai.category == ApplicationInfo.CATEGORY_GAME || (ai.flags and ApplicationInfo.FLAG_IS_GAME) != 0
        }.getOrDefault(false)
    }

    /**
     * Open an app; while you're gaming it opens in a small floating window over the game
     * (on phones that support small windows — otherwise it opens normally).
     */
    fun launch(ctx: Context, component: ComponentName) {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        if (playing && Settings(ctx).gameSmallWindow) {
            val m = ctx.resources.displayMetrics
            val w = (minOf(m.widthPixels, m.heightPixels) * 0.62f).toInt()
            val h = (w * 1.55f).toInt().coerceAtMost((m.heightPixels * 0.9f).toInt())
            val left = (m.widthPixels - w) / 2; val top = (m.heightPixels - h) / 2
            val opts = ActivityOptions.makeBasic().setLaunchBounds(Rect(left, top, left + w, top + h))
            // Freeform ("small window") mode — a hidden Android option many phones support.
            runCatching {
                ActivityOptions::class.java.getMethod("setLaunchWindowingMode", Int::class.javaPrimitiveType).invoke(opts, 5)
            }
            i.addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            if (runCatching { ctx.startActivity(i, opts.toBundle()) }.isSuccess) return
        }
        runCatching { ctx.startActivity(i) }
    }
}
