package com.luncher.home

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable

object Glass {
    /** Smoked translucent glass panel used for widgets, dock and pills. */
    fun panel(ctx: Context, radiusDp: Float, dark: Boolean = true): GradientDrawable {
        val d = ctx.resources.displayMetrics.density
        val colors = if (dark) {
            intArrayOf(
                Color.argb(120, 72, 72, 78),
                Color.argb(105, 40, 40, 44),
                Color.argb(130, 22, 22, 25)
            )
        } else {
            intArrayOf(Color.argb(80, 255, 255, 255), Color.argb(40, 255, 255, 255))
        }
        return GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, colors).apply {
            cornerRadius = radiusDp * d
            setStroke(maxOf(1, (0.8f * d).toInt()), Color.argb(70, 255, 255, 255))
        }
    }

    fun circle(color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }
}
