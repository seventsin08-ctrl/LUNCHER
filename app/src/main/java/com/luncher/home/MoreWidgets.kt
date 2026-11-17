package com.luncher.home

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Small built-in widgets; time displays follow the device's 12/24-hour preference. */
object MoreWidgets {
    private fun dp(ctx: Context, value: Int) = (ctx.resources.displayMetrics.density * value + 0.5f).toInt()

    private fun line(ctx: Context, value: String, size: Float, color: Int = Color.WHITE) = TextView(ctx).apply {
        text = value
        textSize = size
        setTextColor(color)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    private fun textClock(ctx: Context, format12: String, format24: String, zone: String? = null) = TextClock(ctx).apply {
        this.format12Hour = format12
        this.format24Hour = format24
        if (zone != null) timeZone = zone
        setTextColor(Color.WHITE)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    fun digitalClock(ctx: Context, wide: Boolean): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(ctx, 16), dp(ctx, 8), dp(ctx, 16), dp(ctx, 8))
        addView(textClock(ctx, "EEEE, MMM d", "EEEE, MMM d").apply {
            textSize = 13f
            setTextColor(0xCCFFFFFF.toInt())
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        addView(textClock(ctx, "h:mm", "HH:mm").apply {
            textSize = if (wide) 57f else 44f
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            setAutoSizeTextTypeUniformWithConfiguration(24, if (wide) 62 else 48, 1, TypedValue.COMPLEX_UNIT_SP)
        }, LinearLayout.LayoutParams(MATCH_PARENT, dp(ctx, 72)))
        addView(line(ctx, "LOCAL TIME", 10f, 0xB3FFFFFF.toInt()).apply {
            letterSpacing = 0.11f
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }

    fun dateCard(ctx: Context): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(ctx, 16), dp(ctx, 8), dp(ctx, 16), dp(ctx, 8))
        addView(textClock(ctx, "EEEE", "EEEE").apply {
            textSize = 13f
            setTextColor(0xCCFFFFFF.toInt())
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        addView(textClock(ctx, "d", "d").apply {
            textSize = 52f
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        addView(textClock(ctx, "MMMM yyyy", "MMMM yyyy").apply {
            textSize = 12f
            setTextColor(0xD9FFFFFF.toInt())
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }

    fun quoteCard(ctx: Context, value: String): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(ctx, 16), dp(ctx, 10), dp(ctx, 16), dp(ctx, 10))
        addView(line(ctx, "NOTE TO SELF", 10f, 0xB3FFFFFF.toInt()).apply {
            letterSpacing = 0.10f
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        addView(TextView(ctx).apply {
            text = value
            textSize = 21f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Color.WHITE)
            maxLines = 4
            ellipsize = TextUtils.TruncateAt.END
            setAutoSizeTextTypeUniformWithConfiguration(12, 21, 1, TypedValue.COMPLEX_UNIT_SP)
        }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply { topMargin = dp(ctx, 5) })
        addView(line(ctx, "Tap to edit", 10f, 0xAFFFFFFF.toInt()),
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }

    fun worldClocks(ctx: Context, cities: List<WorldClockZones.City>): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(ctx, 8), dp(ctx, 8), dp(ctx, 8), dp(ctx, 6))
        addView(line(ctx, "WORLD CLOCK  ·  TAP TO EDIT", 9f, 0xCCFFFFFF.toInt()).apply {
            letterSpacing = 0.06f
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        cities.forEach { city ->
            val col = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(ctx, 2), 0, dp(ctx, 2), 0)
            }
            col.addView(WorldClockDial(ctx, city.zoneId), LinearLayout.LayoutParams(dp(ctx, 48), dp(ctx, 48)))
            col.addView(line(ctx, city.label, 10f).apply { gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            col.addView(textClock(ctx, "h:mm a", "HH:mm", city.zoneId).apply {
                textSize = 10f
                gravity = Gravity.CENTER
                setTextColor(0xCCFFFFFF.toInt())
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            row.addView(col, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }
        addView(row, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
    }
}

/** Only ticks while attached, so unused pages do not keep background timers alive. */
private class WorldClockDial(ctx: Context, private val zoneId: String) : View(ctx) {
    private val dial = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xA8141418.toInt() }
    private val ticks = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xBBFFFFFF.toInt(); strokeCap = Paint.Cap.ROUND }
    private val hands = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; strokeCap = Paint.Cap.ROUND }
    private val ticker = object : Runnable {
        override fun run() {
            invalidate()
            postDelayed(this, 60_000L - System.currentTimeMillis() % 60_000L)
        }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); post(ticker) }
    override fun onDetachedFromWindow() { removeCallbacks(ticker); super.onDetachedFromWindow() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = min(width, height).toFloat()
        if (size <= 0f) return
        val cx = width / 2f; val cy = height / 2f; val radius = size * 0.46f
        canvas.drawCircle(cx, cy, radius, dial)
        ticks.strokeWidth = (size * 0.025f).coerceAtLeast(1f)
        for (i in 0 until 12) {
            val a = Math.toRadians(i * 30.0)
            val inner = radius * if (i % 3 == 0) 0.72f else 0.80f
            val outer = radius * 0.91f
            canvas.drawLine(cx + (inner * sin(a)).toFloat(), cy - (inner * cos(a)).toFloat(),
                cx + (outer * sin(a)).toFloat(), cy - (outer * cos(a)).toFloat(), ticks)
        }
        val now = Calendar.getInstance(TimeZone.getTimeZone(zoneId))
        val minute = now.get(Calendar.MINUTE)
        val hour = now.get(Calendar.HOUR)
        val hourAngle = Math.toRadians((hour + minute / 60.0) * 30.0)
        val minuteAngle = Math.toRadians(minute * 6.0)
        hands.strokeWidth = (size * 0.05f).coerceAtLeast(2f)
        canvas.drawLine(cx, cy, cx + (radius * 0.50f * sin(hourAngle)).toFloat(),
            cy - (radius * 0.50f * cos(hourAngle)).toFloat(), hands)
        hands.strokeWidth = (size * 0.03f).coerceAtLeast(1.5f)
        canvas.drawLine(cx, cy, cx + (radius * 0.76f * sin(minuteAngle)).toFloat(),
            cy - (radius * 0.76f * cos(minuteAngle)).toFloat(), hands)
        canvas.drawCircle(cx, cy, size * 0.035f, hands)
    }
}
