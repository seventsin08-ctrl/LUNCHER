package com.luncher.home

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
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
 * Renders app icons in iOS-like styles:
 *  clear  – see-through glass tile, light glyph
 *  tinted – dark tile, glyph colored with the accent tint
 *  dark   – dark tile, original colored glyph
 *  light  – frosted white tile, dark glyph
 *  color  – the app's original icon in a rounded square
 *  mono   – dark monochrome tile with a light glyph
 *  frost  – softly tinted pale tile with a dark glyph
 */
object IconFactory {

    const val CORNER = 0.225f

    /** Current style for live icons (clock / calendar) – set by the activity. */
    var style: String = "clear"
    var tint: Int = Color.WHITE

    fun drawTile(c: Canvas, w: Float, h: Float) = drawTile(c, w, h, style, tint)

    fun drawTile(c: Canvas, w: Float, h: Float, style: String, tint: Int) {
        val r = minOf(w, h) * CORNER
        val rect = RectF(0f, 0f, w, h)
        val colors = when (style) {
            "tinted" -> intArrayOf(blend(tint, Color.BLACK, 0.72f), blend(tint, Color.BLACK, 0.86f), 0xFF0E0E10.toInt())
            "mono" -> intArrayOf(0xFF29292C.toInt(), 0xFF151517.toInt(), 0xFF080809.toInt())
            "frost" -> intArrayOf(blend(tint, 0xFFF9F7F4.toInt(), 0.75f),
                blend(tint, 0xFFE7E4DF.toInt(), 0.65f), blend(tint, 0xFFD4D0CA.toInt(), 0.55f))
            "dark", "color" -> intArrayOf(0xFF3A3A3E.toInt(), 0xFF242427.toInt(), 0xFF161618.toInt())
            "light" -> intArrayOf(0xF0F7F7F9.toInt(), 0xE6E6E6EA.toInt(), 0xE0D2D2D8.toInt())
            else -> intArrayOf(0x9C77777E.toInt(), 0x7A45454B, 0xA61E1E22.toInt())
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, 0f, w * 0.35f, h, colors, floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        }
        if (style == "clear" && Glass.opacity != 1f) {
            fill.alpha = (255 * Glass.opacity).toInt().coerceIn(40, 255)
        }
        c.drawRoundRect(rect, r, r, fill)
    }

    fun drawGloss(c: Canvas, w: Float, h: Float) = drawGloss(c, w, h, style)

    fun drawGloss(c: Canvas, w: Float, h: Float, style: String) {
        val r = minOf(w, h) * CORNER
        val strong = style == "clear" || style == "light" || style == "frost"
        val gloss = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, 0f, h * 0.5f,
                if (strong) 0x33FFFFFF else 0x14FFFFFF, 0x00FFFFFF,
                Shader.TileMode.CLAMP
            )
        }
        c.drawRoundRect(RectF(0f, 0f, w, h), r, r, gloss)
        val stroke = maxOf(1.2f, w / 70f)
        val rim = if (strong) intArrayOf(0xC8FFFFFF.toInt(), 0x22FFFFFF, 0x14FFFFFF, 0x80FFFFFF.toInt())
        else intArrayOf(0x66FFFFFF, 0x14FFFFFF, 0x0AFFFFFF, 0x3DFFFFFF)
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.style = Paint.Style.STROKE
            strokeWidth = stroke
            shader = LinearGradient(0f, 0f, w, h, rim, floatArrayOf(0f, 0.35f, 0.65f, 1f), Shader.TileMode.CLAMP)
        }
        val half = stroke / 2f
        c.drawRoundRect(RectF(half, half, w - half, h - half), r - half, r - half, border)
    }

    fun render(d: Drawable, size: Int, style: String, tint: Int): Bitmap {
        val s = size.toFloat()
        val r = s * CORNER
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val bleed = (s * 0.25f).toInt()

        if (style == "color") {
            val layer = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val g = Canvas(layer)
            if (d is AdaptiveIconDrawable) {
                d.background?.mutate()?.let { it.setBounds(-bleed, -bleed, size + bleed, size + bleed); it.draw(g) }
                d.foreground?.mutate()?.let { it.setBounds(-bleed, -bleed, size + bleed, size + bleed); it.draw(g) }
            } else {
                g.drawColor(Color.WHITE)
                val pad = (s * 0.14f).toInt()
                d.mutate().apply { setBounds(pad, pad, size - pad, size - pad); draw(g) }
            }
            mask(g, s, r)
            c.drawBitmap(layer, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            layer.recycle()
            return out
        }

        drawTile(c, s, s, style, tint)

        val glyph = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val g = Canvas(glyph)
        val monoColor: Int? = when (style) {
            "tinted" -> blend(tint, Color.WHITE, 0.15f)
            "light", "frost" -> 0xFF2C2C2E.toInt()
            "mono" -> 0xFFF5F5F5.toInt()
            "dark" -> null
            else -> 0xFFF5F5F5.toInt()
        }
        var usedMono = false
        if (d is AdaptiveIconDrawable) {
            val mono = if (Build.VERSION.SDK_INT >= 33 && monoColor != null) d.monochrome else null
            if (mono != null && monoColor != null) {
                mono.mutate().apply {
                    setTint(monoColor)
                    setBounds(-bleed, -bleed, size + bleed, size + bleed)
                    draw(g)
                }
                usedMono = true
            } else {
                if (style != "dark") {
                    d.background?.mutate()?.let {
                        it.alpha = 70
                        it.setBounds(-bleed, -bleed, size + bleed, size + bleed)
                        it.draw(g)
                        it.alpha = 255
                    }
                }
                d.foreground?.mutate()?.let {
                    it.setBounds(-bleed, -bleed, size + bleed, size + bleed)
                    it.draw(g)
                }
            }
        } else {
            val pad = (s * 0.17f).toInt()
            d.mutate().apply { setBounds(pad, pad, size - pad, size - pad); draw(g) }
        }
        mask(g, s, r)

        val filter: ColorMatrixColorFilter? = if (usedMono) null else when (style) {
            "tinted" -> tintFilter(tint)
            "light", "frost" -> darkGlyph()
            "dark" -> null
            else -> grayscale(1.2f)
        }
        c.drawBitmap(glyph, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { colorFilter = filter })
        glyph.recycle()

        drawGloss(c, s, s, style)
        return out
    }

    private fun mask(g: Canvas, s: Float, r: Float) {
        val m = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
        g.drawRoundRect(RectF(0f, 0f, s, s), r, r, m)
    }

    /** Desaturate and slightly brighten so glyphs read as light-on-dark. */
    fun grayscale(brightness: Float = 1f): ColorMatrixColorFilter {
        val m = ColorMatrix().apply { setSaturation(0f) }
        m.postConcat(
            ColorMatrix(
                floatArrayOf(
                    brightness, 0f, 0f, 0f, 18f,
                    0f, brightness, 0f, 0f, 18f,
                    0f, 0f, brightness, 0f, 18f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        )
        return ColorMatrixColorFilter(m)
    }

    private fun tintFilter(tint: Int): ColorMatrixColorFilter {
        val m = ColorMatrix().apply { setSaturation(0f) }
        val rf = Color.red(tint) / 255f * 1.25f
        val gf = Color.green(tint) / 255f * 1.25f
        val bf = Color.blue(tint) / 255f * 1.25f
        m.postConcat(
            ColorMatrix(
                floatArrayOf(
                    rf, 0f, 0f, 0f, 10f,
                    0f, gf, 0f, 0f, 10f,
                    0f, 0f, bf, 0f, 10f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        )
        return ColorMatrixColorFilter(m)
    }

    private fun darkGlyph(): ColorMatrixColorFilter {
        val m = ColorMatrix().apply { setSaturation(0f) }
        m.postConcat(
            ColorMatrix(
                floatArrayOf(
                    -0.6f, 0f, 0f, 0f, 190f,
                    0f, -0.6f, 0f, 0f, 190f,
                    0f, 0f, -0.6f, 0f, 190f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        )
        return ColorMatrixColorFilter(m)
    }

    fun blend(a: Int, b: Int, t: Float): Int {
        val u = 1f - t
        return Color.argb(
            (Color.alpha(a) * u + Color.alpha(b) * t).toInt(),
            (Color.red(a) * u + Color.red(b) * t).toInt(),
            (Color.green(a) * u + Color.green(b) * t).toInt(),
            (Color.blue(a) * u + Color.blue(b) * t).toInt()
        )
    }
}
