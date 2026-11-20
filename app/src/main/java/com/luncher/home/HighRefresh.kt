package com.luncher.home

import android.app.Activity
import android.content.Context
import android.view.WindowManager

/**
 * Ask the screen for its fastest refresh rate (90 / 120 / 144 Hz when the phone has it) for the
 * launcher's own screens and floating windows, so scrolling and animations are as smooth as the
 * display allows.
 */
object HighRefresh {
    private fun bestMode(ctx: Context): Int {
        val d = runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 30) ctx.display
            else @Suppress("DEPRECATION") (ctx.getSystemService(WindowManager::class.java)).defaultDisplay
        }.getOrNull() ?: return 0
        val cur = d.mode
        return d.supportedModes
            .filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }
            .maxByOrNull { it.refreshRate }?.modeId ?: 0
    }

    fun apply(act: Activity) {
        val id = bestMode(act)
        if (id == 0) return
        val lp = act.window.attributes
        lp.preferredDisplayModeId = id
        act.window.attributes = lp
    }

    fun apply(ctx: Context, lp: WindowManager.LayoutParams) {
        val id = bestMode(ctx)
        if (id != 0) lp.preferredDisplayModeId = id
    }
}
