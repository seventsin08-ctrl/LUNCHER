package com.luncher.home

import android.content.Context
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable

object Glass {
    /** Configured from Settings by the activity. */
    var glass = true
    var tint = Color.WHITE
    var tintPanels = false
    /** Liquid Glass slider: < 1 clearer, > 1 hazier. */
    var opacity = 1f

    private fun scaleAlpha(c: Int): Int =
        Color.argb((Color.alpha(c) * opacity).toInt().coerceIn(12, 250), Color.red(c), Color.green(c), Color.blue(c))

    /** Liquid Glass Shine (iPhone feature 3): a light-catching rim on glass surfaces. */
    var shine = true

    /** Adds the bright top-left rim + soft inner highlight. Static drawing: costs nothing per frame. */
    fun shined(base: GradientDrawable, radiusPx: Float, strength: Float = 1f): Drawable =
        if (!shine || !glass) base else LayerDrawable(arrayOf(base, ShineDrawable(radiusPx, strength)))

    private class ShineDrawable(private val r: Float, private val k: Float) : Drawable() {
        private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
        private val box = RectF()
        override fun onBoundsChange(b: android.graphics.Rect) {
            val w = b.width().toFloat(); val h = b.height().toFloat()
            if (w <= 0 || h <= 0) return
            rim.strokeWidth = maxOf(1.2f, minOf(w, h) * 0.012f).coerceAtMost(3f)
            // Light comes from the top-left: bright there, fading to nothing bottom-right.
            rim.shader = LinearGradient(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(),
                intArrayOf(Color.argb((150 * k).toInt().coerceIn(0, 255), 255, 255, 255), Color.argb((25 * k).toInt(), 255, 255, 255),
                    Color.argb((70 * k).toInt().coerceIn(0, 255), 255, 255, 255)), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
            glow.shader = LinearGradient(0f, b.top.toFloat(), 0f, b.top + h * 0.45f,
                Color.argb((34 * k).toInt().coerceIn(0, 255), 255, 255, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        }
        override fun draw(c: Canvas) {
            val b = bounds
            if (b.isEmpty) return
            val rr = minOf(r, b.height() / 2f, b.width() / 2f)
            val i = rim.strokeWidth / 2f
            box.set(b.left + i, b.top + i, b.right - i, b.bottom - i)
            c.drawRoundRect(box, rr, rr, glow)
            c.drawRoundRect(box, rr, rr, rim)
        }
        override fun setAlpha(alpha: Int) { rim.alpha = alpha; glow.alpha = alpha }
        override fun setColorFilter(cf: ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    /** Clear-glass (or solid) panel used for widgets, dock and pills. */
    fun panel(ctx: Context, radiusDp: Float): Drawable = shined(panelBase(ctx, radiusDp), radiusDp * ctx.resources.displayMetrics.density)

    fun panelBase(ctx: Context, radiusDp: Float): GradientDrawable {
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
        return GradientDrawable(GradientDrawable.Orientation.TL_BR, (if (glass) tinted.map { scaleAlpha(it) }.toIntArray() else tinted)).apply {
            cornerRadius = radiusDp * d
            setStroke(maxOf(1, (0.9f * d).toInt()), Color.argb(if (glass) 85 else 40, 255, 255, 255))
        }
    }

    /** Denser glass for context menus so text stays readable. */
    fun menu(ctx: Context, radiusDp: Float): GradientDrawable {
        val d = ctx.resources.displayMetrics.density
        return GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            // One Glass Slider (feature 4): menus follow it too, but never get too clear to read.
            intArrayOf(Color.argb((225 * opacity).toInt().coerceIn(170, 245), 58, 58, 62), Color.argb((235 * opacity).toInt().coerceIn(180, 250), 36, 36, 40))
        ).apply {
            cornerRadius = radiusDp * d
            setStroke(maxOf(1, (0.7f * d).toInt()), Color.argb(60, 255, 255, 255))
        }
    }

    /** A readable, translucent folder surface that follows the Glass Effect setting. */
    fun folder(ctx: Context, radiusDp: Float): Drawable = shined(folderBase(ctx, radiusDp), radiusDp * ctx.resources.displayMetrics.density, 0.8f)

    private fun folderBase(ctx: Context, radiusDp: Float): GradientDrawable {
        if (!glass) return menu(ctx, radiusDp)
        val d = ctx.resources.displayMetrics.density
        val colors = intArrayOf(Color.argb(205, 57, 57, 64), Color.argb(220, 23, 23, 28))
        val tinted = if (tintPanels && tint != Color.WHITE) {
            colors.map { IconFactory.blend(it, Color.argb(Color.alpha(it), Color.red(tint), Color.green(tint), Color.blue(tint)), 0.18f) }
                .toIntArray()
        } else colors
        return GradientDrawable(GradientDrawable.Orientation.TL_BR, tinted.map { scaleAlpha(it) }.toIntArray()).apply {
            cornerRadius = radiusDp * d
            setStroke(maxOf(1, (0.8f * d).toInt()), Color.argb(75, 255, 255, 255))
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
