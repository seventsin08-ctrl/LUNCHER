package com.luncher.home

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.sin

/**
 * The island's little face  °~°  — two ring eyes and a wavy mouth. It blinks, glances around
 * and wiggles its mouth. When something new arrives (a message, a notification, an app
 * update) it pops that app's logo for a moment, then goes back to smiling.
 */
class IslandFace(ctx: Context) : View(ctx) {
    private val d = ctx.resources.displayMetrics.density
    private val eye = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.8f * d; color = Color.WHITE }
    private val mouth = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.8f * d; color = Color.WHITE; strokeCap = Paint.Cap.ROUND
    }
    private val blush = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x55FF6B8B }
    private val path = Path()
    private val clip = Path()
    private val r = RectF()
    private var t = 0f
    private var blink = 0f            // 0 = open, 1 = closed
    private var look = 0f             // -1 left … 1 right
    private var nextBlink = System.currentTimeMillis() + 2500
    private var nextLook = System.currentTimeMillis() + 4000

    // ---- the logo pop
    private var logo: Bitmap? = null
    private var logoK = 0f            // 0 = face, 1 = logo
    private val hideLogo = Runnable {
        ValueAnimator.ofFloat(logoK, 0f).apply {
            duration = 320; addUpdateListener { logoK = it.animatedValue as Float; invalidate() }; start()
        }
    }

    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2400; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
        addUpdateListener {
            t += 0.016f
            val now = System.currentTimeMillis()
            if (now >= nextBlink) {
                // Blink (sometimes twice).
                ValueAnimator.ofFloat(0f, 1f, 0f).apply { duration = 180; addUpdateListener { v -> blink = v.animatedValue as Float }; start() }
                nextBlink = now + 2200 + (Math.random() * 3200).toLong()
            }
            if (now >= nextLook) {
                val to = listOf(-1f, 0f, 1f, 0f).random()
                ValueAnimator.ofFloat(look, to).apply { duration = 420; addUpdateListener { v -> look = v.animatedValue as Float }; start() }
                nextLook = now + 3000 + (Math.random() * 4000).toLong()
            }
            invalidate()
        }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); anim.start() }
    override fun onDetachedFromWindow() { anim.cancel(); removeCallbacks(hideLogo); super.onDetachedFromWindow() }

    /** Pop an app's logo in place of the face for a few seconds. */
    fun peek(icon: Bitmap?) {
        if (icon == null) return
        logo = icon
        removeCallbacks(hideLogo)
        ValueAnimator.ofFloat(logoK, 1f).apply {
            duration = 360; interpolator = android.view.animation.OvershootInterpolator(1.6f)
            addUpdateListener { logoK = it.animatedValue as Float; invalidate() }; start()
        }
        performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
        postDelayed(hideLogo, 3200)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val cx = w / 2f; val cy = h / 2f
        val s = minOf(w, h)
        val faceA = (1f - logoK).coerceIn(0f, 1f)
        if (faceA > 0.01f) {
            c.save()
            val sc = 0.6f + 0.4f * faceA
            c.scale(sc, sc, cx, cy)
            val bob = sin(t * 2.2f) * s * 0.02f
            val ex = s * 0.17f; val ey = cy - s * 0.08f + bob
            val er = s * 0.075f
            val lx = look * s * 0.03f
            eye.alpha = (255 * faceA).toInt(); mouth.alpha = eye.alpha
            // Eyes: little rings  °  °  (squash to lines when blinking)
            for (side in listOf(-1, 1)) {
                val x = cx + side * ex + lx
                if (blink > 0.6f) c.drawLine(x - er, ey, x + er, ey, mouth)
                else { r.set(x - er, ey - er * (1f - blink), x + er, ey + er * (1f - blink)); c.drawOval(r, eye) }
            }
            // Blush
            blush.alpha = (80 * faceA).toInt()
            c.drawCircle(cx - s * 0.27f, cy + s * 0.08f + bob, s * 0.06f, blush)
            c.drawCircle(cx + s * 0.27f, cy + s * 0.08f + bob, s * 0.06f, blush)
            // Mouth:  ~  a small wave that wiggles
            val my = cy + s * 0.13f + bob
            val mw = s * 0.2f
            val amp = s * 0.045f * (0.7f + 0.3f * sin(t * 3f))
            path.reset()
            path.moveTo(cx - mw, my)
            path.cubicTo(cx - mw / 2, my - amp, cx - mw / 4, my - amp, cx, my)
            path.cubicTo(cx + mw / 4, my + amp, cx + mw / 2, my + amp, cx + mw, my)
            c.drawPath(path, mouth)
            c.restore()
        }
        val lg = logo
        if (lg != null && logoK > 0.01f) {
            val ls = s * 0.78f * logoK.coerceAtMost(1.15f)
            r.set(cx - ls / 2, cy - ls / 2, cx + ls / 2, cy + ls / 2)
            clip.reset(); clip.addOval(r, Path.Direction.CW)
            c.save(); c.clipPath(clip)
            c.drawBitmap(lg, null, r, Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = (255 * logoK.coerceIn(0f, 1f)).toInt() })
            c.restore()
        }
    }
}
