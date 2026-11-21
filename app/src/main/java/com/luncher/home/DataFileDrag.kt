package com.luncher.home

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * The data file you drag out of the side island: a full-screen, see-through layer that draws the
 * file under your finger and — once you pull it towards the right edge — All Father's code box.
 * Drop the file in the box and he takes it.
 */
class DataFileDrag(private val ctx: Context, private val wm: WindowManager, type: Int, private val title: String, private val meta: String) {
    private val d = ctx.resources.displayMetrics.density
    private var fx = 0f; private var fy = 0f
    private var near = false
    var over = false; private set
    private val box = RectF()
    private var typed = 0
    private val code = "> allfather.ingest(file)\n> classify → analyze → act\n> DROP DATA HERE_"

    private val layer = object : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG); private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE }
        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat(); val gold = 0xFFD8A640.toInt()
            // All Father's tab on the right edge.
            p.style = Paint.Style.FILL; p.color = if (near) 0xEE120E06.toInt() else 0xCC0C0B08.toInt()
            val tw = if (near) 30 * d else 20 * d
            c.drawRoundRect(w - tw, h * .38f, w + 20 * d, h * .62f, 14 * d, 14 * d, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = d; p.color = gold; c.drawRoundRect(w - tw, h * .38f, w + 20 * d, h * .62f, 14 * d, 14 * d, p)
            c.save(); c.rotate(90f, w - tw / 2, h / 2); tp.textAlign = Paint.Align.CENTER; tp.textSize = 9 * d; tp.color = gold; tp.letterSpacing = .3f
            c.drawText("ALL FATHER", w - tw / 2, h / 2 + 3 * d, tp); c.restore(); tp.letterSpacing = 0f
            // His code box.
            if (near) {
                box.set(w - tw - 210 * d, h / 2 - 70 * d, w - tw - 10 * d, h / 2 + 70 * d)
                p.style = Paint.Style.FILL; p.color = 0xF4070604.toInt(); c.drawRoundRect(box, 4 * d, 4 * d, p)
                p.style = Paint.Style.STROKE; p.color = gold; c.drawRoundRect(box, 4 * d, 4 * d, p)
                tp.textAlign = Paint.Align.LEFT; tp.textSize = 10 * d; tp.color = 0xFFCDBF95.toInt()
                code.take(typed).split('\n').forEachIndexed { i, s -> c.drawText(s, box.left + 10 * d, box.top + 20 * d + i * 14 * d, tp) }
                if (typed < code.length) { typed += 3; postInvalidateOnAnimation() }
                val dz = RectF(box.left + 10 * d, box.top + 70 * d, box.right - 10 * d, box.bottom - 10 * d)
                p.color = if (over) gold else 0xFF6A5528.toInt(); p.pathEffect = DashPathEffect(floatArrayOf(5 * d, 4 * d), 0f); c.drawRoundRect(dz, 3 * d, 3 * d, p); p.pathEffect = null
                if (over) { p.style = Paint.Style.FILL; p.color = 0x22D8A640; c.drawRoundRect(dz, 3 * d, 3 * d, p) }
                tp.textAlign = Paint.Align.CENTER; tp.color = if (over) gold else 0xFFA88D4F.toInt(); tp.textSize = 10 * d
                c.drawText("DROP DATA HERE", dz.centerX(), dz.centerY() + 4 * d, tp)
            }
            // The file under your finger.
            val fw = 150 * d; val fh = 62 * d
            val r = RectF(fx - fw / 2, fy - fh / 2, fx + fw / 2, fy + fh / 2)
            c.save(); c.rotate(-3f, fx, fy)
            p.style = Paint.Style.FILL; p.color = 0xFF120F08.toInt(); p.setShadowLayer(18 * d, 0f, 6 * d, 0x99000000.toInt()); c.drawRoundRect(r, 6 * d, 6 * d, p); p.clearShadowLayer()
            p.style = Paint.Style.STROKE; p.color = gold; c.drawRoundRect(r, 6 * d, 6 * d, p)
            tp.textAlign = Paint.Align.LEFT; tp.color = gold; tp.textSize = 8.5f * d; c.drawText("DATA FILE", r.left + 10 * d, r.top + 16 * d, tp)
            tp.color = 0xFFEBE8E1.toInt(); tp.textSize = 12 * d; tp.typeface = Typeface.DEFAULT_BOLD
            c.drawText(ellipsize(title, tp, fw - 20 * d), r.left + 10 * d, r.top + 34 * d, tp); tp.typeface = Typeface.MONOSPACE
            tp.color = 0xFF8A8C92.toInt(); tp.textSize = 8.5f * d; c.drawText(ellipsize(meta, tp, fw - 20 * d), r.left + 10 * d, r.top + 50 * d, tp)
            c.restore()
        }
    }

    private fun ellipsize(s: String, p: Paint, max: Float): String {
        if (p.measureText(s) <= max) return s
        var t = s; while (t.isNotEmpty() && p.measureText("$t…") > max) t = t.dropLast(1); return "$t…"
    }

    private val lp = WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, type,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START }

    fun start(x: Float, y: Float): Boolean {
        fx = x; fy = y
        return runCatching { wm.addView(layer, lp) }.isSuccess
    }

    /** Move the file; returns true while it is over All Father's drop box. */
    fun move(x: Float, y: Float): Boolean {
        fx = x; fy = y
        val w = layer.width.toFloat().takeIf { it > 0 } ?: ctx.resources.displayMetrics.widthPixels.toFloat()
        val wasNear = near
        near = x > w * .5f
        if (near && !wasNear) { typed = 0; layer.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK) }
        val wasOver = over
        over = near && box.width() > 0 && x > box.left - 30 * d && y > box.top - 40 * d && y < box.bottom + 40 * d
        if (over && !wasOver) layer.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
        layer.invalidate()
        return over
    }

    fun end() { runCatching { wm.removeView(layer) } }
}
