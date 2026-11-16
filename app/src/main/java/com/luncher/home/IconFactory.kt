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

    /** Draws the shared glass tile (used by app icons and live icons). */
    fun drawTile(c: Canvas, w: Float, h: Float) {
        val r = minOf(w, h) * CORNER
        val rect = RectF(0f, 0f, w, h)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, 0f, h,
                intArrayOf(0xFF4C4C52.toInt(), 0xFF2A2A2E.toInt(), 0xFF1B1B1E.toInt()),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        c.drawRoundRect(rect, r, r, fill)
    }

    /** Glossy highlight + hairline border drawn on top of a tile. */
    fun drawGloss(c: Canvas, w: Float, h: Float) {
        val r = minOf(w, h) * CORNER
        val gloss = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, 0f, h * 0.55f,
                0x2EFFFFFF, 0x00FFFFFF,
                Shader.TileMode.CLAMP
            )
        }
        c.drawRoundRect(RectF(0f, 0f, w, h), r, r, gloss)
        val stroke = maxOf(1f, w / 90f)
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
            shader = LinearGradient(
                0f, 0f, 0f, h,
                0x66FFFFFF, 0x14FFFFFF,
                Shader.TileMode.CLAMP
            )
        }
        val half = stroke / 2f
        c.drawRoundRect(RectF(half, half, w - half, h - half), r - half, r - half, border)
    }

    fun render(d: Drawable, size: Int): Bitmap {
        val s = size.toFloat()
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        drawTile(c, s, s)

        // Draw the glyph into its own layer, then composite it onto the tile only (SRC_ATOP).
        val glyph = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val g = Canvas(glyph)
        var filter: ColorMatrixColorFilter? = grayscale(1.15f)

        if (d is AdaptiveIconDrawable) {
            val bleed = (s * 0.25f).toInt()
            val mono = if (Build.VERSION.SDK_INT >= 33) d.monochrome else null
            val layer = mono ?: d.foreground
            if (layer != null) {
                val l = layer.mutate()
                if (mono != null) {
                    l.setTint(0xFFF2F2F2.toInt())
                    filter = null
                }
                l.setBounds(-bleed, -bleed, size + bleed, size + bleed)
                l.draw(g)
            }
        } else {
            val pad = (s * 0.18f).toInt()
            val l = d.mutate()
            l.setBounds(pad, pad, size - pad, size - pad)
            l.draw(g)
        }

        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP)
            colorFilter = filter
        }
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
