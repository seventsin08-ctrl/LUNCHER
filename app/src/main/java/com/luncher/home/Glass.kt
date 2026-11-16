package com.luncher.home

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable

object Glass {
    /** See-through "clear glass" panel used for widgets, dock and pills. */
    fun panel(ctx: Context, radiusDp: Float): GradientDrawable {
        val d = ctx.resources.displayMetrics.density
        return GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(
                Color.argb(95, 130, 130, 138),
                Color.argb(70, 70, 70, 76),
                Color.argb(110, 24, 24, 27)
            )
        ).apply {
            cornerRadius = radiusDp * d
            setStroke(maxOf(1, (0.9f * d).toInt()), Color.argb(85, 255, 255, 255))
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
