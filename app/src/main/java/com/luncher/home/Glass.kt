package com.luncher.home

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable

object Glass {
    /** Configured from Settings by the activity. */
    var glass = true
    var tint = Color.WHITE
    var tintPanels = false

    /** Clear-glass (or solid) panel used for widgets, dock and pills. */
    fun panel(ctx: Context, radiusDp: Float): GradientDrawable {
        val d = ctx.resources.displayMetrics.density
        val colors = if (glass) {
            intArrayOf(Color.argb(95, 130, 130, 138), Color.argb(70, 70, 70, 76), Color.argb(110, 24, 24, 27))
        } else {
            intArrayOf(0xF2333336.toInt(), 0xF2262628.toInt(), 0xF21A1A1C.toInt())
        }
        val tinted = if (tintPanels && tint != Color.WHITE) {
            colors.map { IconFactory.blend(it, Color.argb(Color.alpha(it), Color.red(tint), Color.green(tint), Color.blue(tint)), 0.28f) }
                .toIntArray()
        } else colors
        return GradientDrawable(GradientDrawable.Orientation.TL_BR, tinted).apply {
            cornerRadius = radiusDp * d
            setStroke(maxOf(1, (0.9f * d).toInt()), Color.argb(if (glass) 85 else 40, 255, 255, 255))
        }
    }

    /** Denser glass for context menus so text stays readable. */
    fun menu(ctx: Context, radiusDp: Float): GradientDrawable {
        val d = ctx.resources.displayMetrics.density
        return GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.argb(225, 58, 58, 62), Color.argb(235, 36, 36, 40))
        ).apply {
            cornerRadius = radiusDp * d
            setStroke(maxOf(1, (0.7f * d).toInt()), Color.argb(60, 255, 255, 255))
        }
    }

    fun circle(color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    fun pill(color: Int, radiusPx: Float): GradientDrawable = GradientDrawable().apply {
        cornerRadius = radiusPx
        setColor(color)
    }
}
