package com.luncher.home

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Color
import android.os.Build

/**
 * One accent colour for the whole launcher: Control Center tiles and sliders, the island,
 * glass panels, switches. It comes from the wallpaper by default (like Material You),
 * from the phone's system theme, from a colour you pick, or plain white (Mono).
 */
object ThemeEngine {
    /** Accent used for "on" tiles, slider fills, island highlights. */
    var accent = Color.WHITE
        private set
    /** Text/icon colour that reads well on [accent]. */
    var onAccent = 0xFF1C1C1E.toInt()
        private set
    /** True when the accent is a real colour (not Mono white). */
    val colorful get() = accent != Color.WHITE

    private var listener: WallpaperManager.OnColorsChangedListener? = null

    fun resolve(ctx: Context, s: Settings) {
        val raw = when (s.themeSource) {
            "wallpaper" -> wallpaperColor(ctx)
            "system" -> systemColor(ctx) ?: wallpaperColor(ctx)
            "custom" -> s.themeColor
            else -> null
        }
        accent = raw?.let { readable(it) } ?: Color.WHITE
        onAccent = if (luminance(accent) > 0.5f) 0xFF1C1C1E.toInt() else Color.WHITE
    }

    /** Calls [onChange] when the wallpaper (and so its colours) changes. */
    fun watch(ctx: Context, onChange: () -> Unit) {
        if (Build.VERSION.SDK_INT < 27 || listener != null) return
        val l = WallpaperManager.OnColorsChangedListener { _, which ->
            if (which and WallpaperManager.FLAG_SYSTEM != 0) onChange()
        }
        runCatching {
            WallpaperManager.getInstance(ctx).addOnColorsChangedListener(l, android.os.Handler(android.os.Looper.getMainLooper()))
            listener = l
        }
    }

    fun unwatch(ctx: Context) {
        val l = listener ?: return
        if (Build.VERSION.SDK_INT >= 27) runCatching { WallpaperManager.getInstance(ctx).removeOnColorsChangedListener(l) }
        listener = null
    }

    /** The most colourful of the wallpaper's main colours. */
    fun wallpaperColor(ctx: Context): Int? {
        if (Build.VERSION.SDK_INT < 27) return null
        val wc = runCatching { WallpaperManager.getInstance(ctx).getWallpaperColors(WallpaperManager.FLAG_SYSTEM) }
            .getOrNull() ?: return null
        val list = listOfNotNull(wc.primaryColor, wc.secondaryColor, wc.tertiaryColor).map { it.toArgb() }
        return list.maxByOrNull { saturation(it) + 0.15f * (if (luminance(it) in 0.15f..0.85f) 1f else 0f) }
    }

    fun systemColor(ctx: Context): Int? =
        if (Build.VERSION.SDK_INT >= 31) runCatching { ctx.getColor(android.R.color.system_accent1_300) }.getOrNull() else null

    /** Keep the accent visible on dark glass: lift very dark colours, tame greys. */
    private fun readable(c: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(c, hsv)
        if (hsv[1] < 0.12f) return Color.WHITE // nearly grey wallpaper → Mono look
        hsv[1] = hsv[1].coerceIn(0.35f, 0.85f)
        hsv[2] = hsv[2].coerceIn(0.78f, 1f)
        return Color.HSVToColor(hsv)
    }

    fun saturation(c: Int): Float { val h = FloatArray(3); Color.colorToHSV(c, h); return h[1] }

    fun luminance(c: Int): Float =
        (0.2126f * Color.red(c) + 0.7152f * Color.green(c) + 0.0722f * Color.blue(c)) / 255f

    /** [c] with a new alpha (0..255). */
    fun alpha(c: Int, a: Int) = Color.argb(a, Color.red(c), Color.green(c), Color.blue(c))
}
