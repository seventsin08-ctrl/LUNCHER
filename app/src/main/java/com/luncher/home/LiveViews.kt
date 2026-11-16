package com.luncher.home

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** A live analog clock drawn as a glass app icon. */
class AnalogClockView(ctx: Context) : View(ctx) {
    private val ticker = object : Runnable {
        override fun run() {
            invalidate()
            postDelayed(this, 1000L - System.currentTimeMillis() % 1000L)
        }
    }
    private val face = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF101012.toInt() }
    private val num = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFEDEDED.toInt()
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x80FFFFFF.toInt() }
    private val hand = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF5F5F5.toInt()
        strokeCap = Paint.Cap.ROUND
    }
    private val sec = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFF9F0A.toInt()
        strokeCap = Paint.Cap.ROUND
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post(ticker)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(ticker)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val s = min(width, height).toFloat()
        if (s <= 0f) return
        canvas.save()
        canvas.translate((width - s) / 2f, (height - s) / 2f)
        IconFactory.drawTile(canvas, s, s)
        val cx = s / 2f
        val cy = s / 2f
        val r = s * 0.42f
        canvas.drawCircle(cx, cy, r, face)

        tick.strokeWidth = s * 0.012f
        for (i in 0 until 60) {
            if (i % 5 == 0) continue
            val a = Math.toRadians(i * 6.0)
            val r1 = r * 0.9f
            val r2 = r * 0.95f
            canvas.drawLine(
                cx + (r1 * sin(a)).toFloat(), cy - (r1 * cos(a)).toFloat(),
                cx + (r2 * sin(a)).toFloat(), cy - (r2 * cos(a)).toFloat(), tick
            )
        }
        num.textSize = s * 0.13f
        for (n in 1..12) {
            val a = Math.toRadians(n * 30.0)
            val rr = r * 0.72f
            val x = cx + (rr * sin(a)).toFloat()
            val y = cy - (rr * cos(a)).toFloat() - (num.descent() + num.ascent()) / 2f
            canvas.drawText(n.toString(), x, y, num)
        }

        val t = LocalTime.now()
        val hAngle = Math.toRadians(((t.hour % 12) + t.minute / 60.0) * 30.0)
        val mAngle = Math.toRadians((t.minute + t.second / 60.0) * 6.0)
        val sAngle = Math.toRadians(t.second * 6.0)

        hand.strokeWidth = s * 0.045f
        drawHand(canvas, cx, cy, hAngle, r * 0.5f, hand)
        hand.strokeWidth = s * 0.03f
        drawHand(canvas, cx, cy, mAngle, r * 0.78f, hand)
        sec.strokeWidth = s * 0.015f
        drawHand(canvas, cx, cy, sAngle, r * 0.85f, sec)
        canvas.drawCircle(cx, cy, s * 0.03f, sec)

        IconFactory.drawGloss(canvas, s, s)
        canvas.restore()
    }

    private fun drawHand(c: Canvas, cx: Float, cy: Float, a: Double, len: Float, p: Paint) {
        c.drawLine(cx, cy, cx + (len * sin(a)).toFloat(), cy - (len * cos(a)).toFloat(), p)
    }
}

/** Live calendar icon: weekday on top, today's date big underneath. */
class CalendarIconView(ctx: Context) : View(ctx) {
    private val ticker = object : Runnable {
        override fun run() {
            invalidate()
            postDelayed(this, 60_000L)
        }
    }
    private val day = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFF453A.toInt()
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val date = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF2F2F2.toInt()
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post(ticker)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(ticker)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val s = min(width, height).toFloat()
        if (s <= 0f) return
        canvas.save()
        canvas.translate((width - s) / 2f, (height - s) / 2f)
        IconFactory.drawTile(canvas, s, s)
        val today = LocalDate.now()
        day.textSize = s * 0.19f
        canvas.drawText(
            today.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()),
            s / 2f, s * 0.32f, day
        )
        date.textSize = s * 0.5f
        canvas.drawText(today.dayOfMonth.toString(), s / 2f, s * 0.8f, date)
        IconFactory.drawGloss(canvas, s, s)
        canvas.restore()
    }
}

/** Circular battery ring like the iOS "Batteries" widget. */
class BatteryRingView(ctx: Context) : View(ctx) {
    var level: Int = 0
        set(v) { field = v; invalidate() }
    var charging: Boolean = false
        set(v) { field = v; invalidate() }

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0x33FFFFFF
        strokeCap = Paint.Cap.ROUND
    }
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF2F2F2.toInt() }
    private val glyphStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFFF2F2F2.toInt()
    }

    override fun onDraw(canvas: Canvas) {
        val s = min(width, height).toFloat()
        if (s <= 0f) return
        val stroke = s * 0.1f
        track.strokeWidth = stroke
        bar.strokeWidth = stroke
        bar.color = when {
            charging -> 0xFF30D158.toInt()
            level <= 20 -> 0xFFFF453A.toInt()
            else -> 0xFFF2F2F2.toInt()
        }
        val cx = width / 2f
        val cy = height / 2f
        val r = s / 2f - stroke
        val rect = RectF(cx - r, cy - r, cx + r, cy + r)
        canvas.drawArc(rect, 0f, 360f, false, track)
        canvas.drawArc(rect, -90f, 360f * level / 100f, false, bar)

        // Small phone glyph in the middle.
        val pw = s * 0.16f
        val ph = s * 0.3f
        glyphStroke.strokeWidth = s * 0.03f
        val pr = RectF(cx - pw / 2, cy - ph / 2, cx + pw / 2, cy + ph / 2)
        canvas.drawRoundRect(pr, pw * 0.25f, pw * 0.25f, glyphStroke)
        canvas.drawRect(cx - pw * 0.18f, cy - ph / 2 + s * 0.035f, cx + pw * 0.18f, cy - ph / 2 + s * 0.05f, glyph)
    }
}
