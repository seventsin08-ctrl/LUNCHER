package com.luncher.home

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Build

/**
 * Renders every app icon in the dark "clear glass" monochrome style:
 * a smoked-glass rounded tile with a light, desaturated glyph on top.
 */
object IconFactory {

    const val CORNER = 0.225f

    /** Draws the shared see-through "clear glass" tile (used by app icons and live icons). */
    fun drawTile(c: Canvas, w: Float, h: Float) {
        val r = minOf(w, h) * CORNER
        val rect = RectF(0f, 0f, w, h)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, w * 0.35f, h,
                intArrayOf(0x9C77777E.toInt(), 0x7A45454B, 0xA61E1E22.toInt()),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        c.drawRoundRect(rect, r, r, fill)
    }

    /** Liquid-glass sheen + bright rim drawn on top of a tile. */
    fun drawGloss(c: Canvas, w: Float, h: Float) {
        val r = minOf(w, h) * CORNER
        val gloss = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, 0f, h * 0.5f,
                0x33FFFFFF, 0x00FFFFFF,
                Shader.TileMode.CLAMP
            )
        }
        c.drawRoundRect(RectF(0f, 0f, w, h), r, r, gloss)
        val stroke = maxOf(1.2f, w / 70f)
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
            shader = LinearGradient(
                0f, 0f, w, h,
                intArrayOf(0xC8FFFFFF.toInt(), 0x22FFFFFF, 0x14FFFFFF, 0x80FFFFFF.toInt()),
                floatArrayOf(0f, 0.35f, 0.65f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        val half = stroke / 2f
        c.drawRoundRect(RectF(half, half, w - half, h - half), r - half, r - half, border)
    }

    fun render(d: Drawable, size: Int): Bitmap {
        val s = size.toFloat()
        val r = s * CORNER
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        drawTile(c, s, s)

        // Glyph goes into its own layer, gets clipped to the rounded tile shape, then is drawn on top.
        val glyph = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val g = Canvas(glyph)
        var filter: ColorMatrixColorFilter? = grayscale(1.2f)

        if (d is AdaptiveIconDrawable) {
            val bleed = (s * 0.25f).toInt()
            val mono = if (Build.VERSION.SDK_INT >= 33) d.monochrome else null
            if (mono != null) {
                val l = mono.mutate()
                l.setTint(0xFFF5F5F5.toInt())
                l.setBounds(-bleed, -bleed, size + bleed, size + bleed)
                l.draw(g)
                filter = null
            } else {
                d.background?.mutate()?.let {
                    it.alpha = 70
                    it.setBounds(-bleed, -bleed, size + bleed, size + bleed)
                    it.draw(g)
                }
                d.foreground?.mutate()?.let {
                    it.setBounds(-bleed, -bleed, size + bleed, size + bleed)
                    it.draw(g)
                }
            }
        } else {
            val pad = (s * 0.17f).toInt()
            val l = d.mutate()
            l.setBounds(pad, pad, size - pad, size - pad)
            l.draw(g)
        }

        val mask = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
        g.drawRoundRect(RectF(0f, 0f, s, s), r, r, mask)

        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { colorFilter = filter }
        c.drawBitmap(glyph, 0f, 0f, p)
        glyph.recycle()

        drawGloss(c, s, s)
        return out
    }

    /** Desaturate and slightly brighten so glyphs read as light-on-dark. */
    fun grayscale(brightness: Float = 1f): ColorMatrixColorFilter {
        val m = ColorMatrix().apply { setSaturation(0f) }
        val b = ColorMatrix(
            floatArrayOf(
                brightness, 0f, 0f, 0f, 18f,
                0f, brightness, 0f, 0f, 18f,
                0f, 0f, brightness, 0f, 18f,
                0f, 0f, 0f, 1f, 0f
            )
        )
        m.postConcat(b)
        return ColorMatrixColorFilter(m)
    }
}
