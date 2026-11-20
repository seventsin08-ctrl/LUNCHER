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
    /** Big game makers / words that only game apps use in their package names. */
    private val gameName = Regex("(ubisoft|rainbowsix|tencent\\.ig|pubg|mihoyo|hoyoverse|garena|supercell|roblox|mojang|" +
        "activision|riotgames|moonton|netease\\.(?!cloudmusic)|epicgames|ea\\.game|gameloft|miniclip|kiloo|playrix|" +
        "nianticlabs|levelinfinite|krafton|\\.game\\.|\\.games\\.)", RegexOption.IGNORE_CASE)
    private val listeners = LinkedHashSet<() -> Unit>()
    fun listen(fn: () -> Unit) { listeners += fn }
    fun unlisten(fn: () -> Unit) { listeners -= fn }
    /** Told whenever a different app comes to the front (Gilbert reacts to what you're doing). */
    val appListeners = LinkedHashSet<(String) -> Unit>()

    /** Called by the accessibility helper whenever another app's window comes to the front. */
    fun onForeground(ctx: Context, pkg: String?) {
        if (pkg == null) return
        // The phone's own game helper (vivo Game Cube / game panel) only shows up in games:
        // that tells us the app on screen is a game even if it doesn't say so itself.
        if ((pkg.startsWith("com.vivo.gamewatch") || pkg.contains("gamecube") || pkg.contains("gamespace") ||
                pkg.contains("gameassistant") || pkg.contains("gamebooster")) && foreground != null) {
            val f = foreground!!
            val s = Settings(ctx)
            if (f !in s.gameApps && f !in s.notGameApps) { s.gameApps = s.gameApps + f; gameCache[f] = true; recheck(ctx) }
            return
        }
        // Our own floating windows, the status bar and the keyboard don't change what's "on screen".
        if (pkg == ctx.packageName || pkg == "com.android.systemui" || pkg.contains("inputmethod") ||
            pkg.contains("keyboard") || pkg.startsWith("com.vivo.gamewatch") || pkg.contains("gamecube")) return
        if (foreground != pkg) { foreground = pkg; for (l in appListeners.toList()) runCatching { l(pkg) } }
        recheck(ctx)
    }

    /** Is the app on screen a game? Updates [playing] and tells everyone if it changed. */
    fun recheck(ctx: Context) {
        val pkg = foreground ?: return
        val game = Settings(ctx).gameHide && isGame(ctx, pkg)
        if (game != playing) {
            playing = game
            for (l in listeners.toList()) runCatching { l() }
        }
    }

    /** Mark / unmark the app on screen as a game by hand (from the tools). */
    fun setGame(ctx: Context, pkg: String, on: Boolean) {
        val s = Settings(ctx)
        if (on) { s.gameApps = s.gameApps + pkg; s.notGameApps = s.notGameApps - pkg }
        else { s.gameApps = s.gameApps - pkg; s.notGameApps = s.notGameApps + pkg }
        gameCache[pkg] = on
        recheck(ctx)
    }

    /** Back on the Home Screen: no game on screen. */
    fun leftApps() {
        foreground = null
        if (playing) { playing = false; for (l in listeners.toList()) runCatching { l() } }
    }

    private fun isGame(ctx: Context, pkg: String): Boolean = gameCache.getOrPut(pkg) {
        val s = Settings(ctx)
        if (pkg in s.notGameApps) return@getOrPut false
        if (pkg in s.gameApps) return@getOrPut true
        runCatching {
            val ai = ctx.packageManager.getApplicationInfo(pkg, 0)
            @Suppress("DEPRECATION")
            ai.category == ApplicationInfo.CATEGORY_GAME || (ai.flags and ApplicationInfo.FLAG_IS_GAME) != 0 ||
                // Built with a game engine (Unity, Unreal, Cocos, Godot…)?
                (ai.nativeLibraryDir?.let { java.io.File(it).list()?.any { f ->
                    f == "libunity.so" || f == "libUE4.so" || f == "libUnreal.so" || f.startsWith("libcocos") ||
                        f == "libgodot_android.so" || f == "libil2cpp.so" || f == "libGameCore.so" } } ?: false) ||
                gameName.containsMatchIn(pkg)
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
