package com.luncher.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Classic iPod widget: a screen with what's playing and a working click wheel.
 * Spin the wheel to change the volume (with a click each step); MENU opens the music app,
 * ⏮ / ⏭ skip, ▶❙❙ and the centre button play or pause.
 */
class IPodView(
    ctx: Context,
    private val info: () -> Triple<String, String, Bitmap?>?,
    private val media: () -> IslandMedia?,
    private val onMenu: () -> Unit,
    private val onVolumeStep: (Int) -> Unit
) : View(ctx) {
    private fun dp(v: Float) = v * resources.displayMetrics.density

    private val body = Paint(Paint.ANTI_ALIAS_FLAG)
    private val screen = Paint(Paint.ANTI_ALIAS_FLAG)
    private val wheel = Paint(Paint.ANTI_ALIAS_FLAG)
    private val center = Paint(Paint.ANTI_ALIAS_FLAG)
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF8E8E93.toInt(); textAlign = Paint.Align.CENTER }
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private val clock = SimpleDateFormat("h:mm a", Locale.getDefault())
    private var pressedZone = -1

    private val tick = object : Runnable {
        override fun run() { invalidate(); if (isAttachedToWindow) postDelayed(this, 1000) }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); post(tick) }
    override fun onDetachedFromWindow() { removeCallbacks(tick); super.onDetachedFromWindow() }

    // Wheel geometry (set in onDraw)
    private var cx = 0f
    private var cy = 0f
    private var outer = 0f
    private var inner = 0f

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val rad = dp(22f)
        body.shader = LinearGradient(0f, 0f, w, h, intArrayOf(0xFFF4F4F6.toInt(), 0xFFCFCFD4.toInt(), 0xFFB9B9BF.toInt()), null, Shader.TileMode.CLAMP)
        r.set(0f, 0f, w, h)
        c.drawRoundRect(r, rad, rad, body)

        // Screen
        val pad = dp(10f)
        val sh = h * 0.46f
        r.set(pad, pad, w - pad, pad + sh)
        val now = info()
        val m = media()
        val art = now?.third
        screen.shader = LinearGradient(0f, r.top, 0f, r.bottom, intArrayOf(0xFF9C3B2E.toInt(), 0xFF5E1F19.toInt()), null, Shader.TileMode.CLAMP)
        if (art == null) screen.shader = LinearGradient(0f, r.top, 0f, r.bottom, intArrayOf(0xFF2C2C30.toInt(), 0xFF111114.toInt()), null, Shader.TileMode.CLAMP)
        c.drawRoundRect(r, dp(12f), dp(12f), screen)
        c.save()
        val clip = android.graphics.Path().apply { addRoundRect(r, dp(12f), dp(12f), android.graphics.Path.Direction.CW) }
        c.clipPath(clip)
        if (art != null) {
            // Blurred-ish artwork backdrop: stretch the cover, then dim it.
            c.drawBitmap(art, null, r, null)
            c.drawColor(0x88000000.toInt())
        }
        txt.textSize = dp(10f); txt.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD); txt.color = Color.WHITE
        c.drawText("${clock.format(Date())}  Music", r.left + dp(8f), r.top + dp(15f), txt)
        val title = now?.first ?: "Not Playing"
        val artist = now?.second ?: "iPod"
        txt.textSize = dp(13f)
        c.drawText(ellipsize(title, txt, r.width() - dp(16f)), r.left + dp(8f), r.bottom - dp(34f), txt)
        txt.textSize = dp(10.5f); txt.typeface = Typeface.DEFAULT; txt.color = 0xCCFFFFFF.toInt()
        c.drawText(ellipsize(artist, txt, r.width() - dp(16f)), r.left + dp(8f), r.bottom - dp(21f), txt)
        // Progress
        val by = r.bottom - dp(11f)
        bar.color = 0x55FFFFFF
        c.drawRoundRect(r.left + dp(8f), by - dp(2f), r.right - dp(8f), by + dp(2f), dp(2f), dp(2f), bar)
        if (m != null && m.duration > 0) {
            val f = (m.position().toFloat() / m.duration).coerceIn(0f, 1f)
            bar.color = Color.WHITE
            c.drawRoundRect(r.left + dp(8f), by - dp(2f), r.left + dp(8f) + (r.width() - dp(16f)) * f, by + dp(2f), dp(2f), dp(2f), bar)
        }
        c.restore()

        // Click wheel
        val wheelTop = pad + sh + dp(8f)
        outer = minOf(w - pad * 2, h - wheelTop - pad) / 2f
        inner = outer * 0.36f
        cx = w / 2f; cy = wheelTop + (h - wheelTop - pad) / 2f
        wheel.shader = RadialGradient(cx, cy - outer * 0.3f, outer * 1.3f, intArrayOf(0xFFFFFFFF.toInt(), 0xFFE5E5EA.toInt()), null, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, outer, wheel)
        if (pressedZone in 0..3) {
            bar.color = 0x18000000
            c.drawCircle(cx, cy, outer, bar)
        }
        center.shader = LinearGradient(0f, cy - inner, 0f, cy + inner, intArrayOf(0xFFD8D8DD.toInt(), 0xFFF7F7F9.toInt()), null, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, inner, center)
        glyph.textSize = outer * 0.17f
        glyph.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        c.drawText("MENU", cx, cy - outer * 0.62f, glyph)
        glyph.textSize = outer * 0.2f
        c.drawText("⏮", cx - outer * 0.68f, cy + outer * 0.07f, glyph)
        c.drawText("⏭", cx + outer * 0.68f, cy + outer * 0.07f, glyph)
        c.drawText(if (m?.playing?.invoke() == true) "❚❚" else "▶", cx, cy + outer * 0.78f, glyph)
    }

    private fun ellipsize(s: String, p: Paint, max: Float): String {
        if (p.measureText(s) <= max) return s
        var t = s
        while (t.isNotEmpty() && p.measureText("$t…") > max) t = t.dropLast(1)
        return "$t…"
    }

    // ---- touch: spin to change volume, tap zones for controls
    private var lastAngle = 0f
    private var spun = 0f
    private var moved = false
    private var downX = 0f
    private var downY = 0f

    private fun angle(x: Float, y: Float) = Math.toDegrees(atan2((y - cy).toDouble(), (x - cx).toDouble())).toFloat()

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val d = hypot(e.x - cx, e.y - cy)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (d > outer * 1.05f) return false
                parent?.requestDisallowInterceptTouchEvent(true)
                downX = e.x; downY = e.y; moved = false; spun = 0f
                lastAngle = angle(e.x, e.y)
                pressedZone = zone(e.x, e.y, d); invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                if (d < inner) return true
                val a = angle(e.x, e.y)
                var delta = a - lastAngle
                if (delta > 180) delta -= 360
                if (delta < -180) delta += 360
                lastAngle = a
                spun += delta
                if (abs(e.x - downX) + abs(e.y - downY) > dp(10f)) moved = true
                // One volume step every 18° — clockwise turns it up, like the real iPod.
                while (abs(spun) >= 18f) {
                    val step = if (spun > 0) 1 else -1
                    spun -= 18f * step
                    onVolumeStep(step)
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                }
            }
            MotionEvent.ACTION_UP -> {
                if (!moved) {
                    performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    val m = media()
                    when (zone(e.x, e.y, d)) {
                        0 -> onMenu()
                        1 -> m?.onPrev?.invoke()
                        2 -> m?.onNext?.invoke()
                        3, 4 -> m?.onPlayPause?.invoke()
                    }
                }
                pressedZone = -1; postDelayed({ invalidate() }, 250)
            }
            MotionEvent.ACTION_CANCEL -> { pressedZone = -1; invalidate() }
        }
        return true
    }

    /** 0 = MENU, 1 = back, 2 = forward, 3 = play/pause, 4 = centre button. */
    private fun zone(x: Float, y: Float, d: Float): Int {
        if (d < inner) return 4
        val a = angle(x, y)
        return when {
            a in -135f..-45f -> 0
            a in 45f..135f -> 3
            abs(a) > 135f -> 1
            else -> 2
        }
    }
}
