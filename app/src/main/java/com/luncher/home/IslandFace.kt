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
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The island's "alive" orb: a spinning sphere of glowing particles (warm orange on top, white
 * below) that slowly gathers itself into a little face  °~° . It blinks, glances around,
 * breathes, and now and then dissolves back into the sphere as if thinking, then reforms.
 * When something new arrives (message, notification, app update) the particles swirl into the
 * sphere around that app's logo, then become the face again.
 */
class IslandFace(ctx: Context) : View(ctx) {
    private val d = ctx.resources.displayMetrics.density

    private class P(
        val sx: Float, val sy: Float, val sz: Float,     // point on the unit sphere
        var fx: Float, var fy: Float,                    // where it sits in the face (-1…1)
        val part: Int,                                   // 0 outline, 1 left eye, 2 right eye, 3 mouth
        val seed: Float, val size: Float
    )
    private val ps = ArrayList<P>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clip = Path()
    private val r = RectF()

    private var t = 0f
    private var morph = 0f          // 0 = sphere, 1 = face
    private var morphTarget = 0f
    private var spinBoost = 0f
    private var blink = 0f
    private var look = 0f
    private var lookTarget = 0f
    private var bornAt = 0L
    private var nextBlink = 0L
    private var nextLook = 0L
    private var nextThink = 0L

    private var logo: Bitmap? = null
    private var logoK = 0f
    private var logoUntil = 0L

    init {
        val n = 240
        // Fibonacci sphere: evenly spread points.
        val golden = PI * (3 - sqrt(5.0))
        var eyeL = 0; var eyeR = 0; var mouth = 0
        for (i in 0 until n) {
            val y = 1 - (i / (n - 1f)) * 2
            val rad = sqrt(1 - y * y)
            val th = golden * i
            val x = (cos(th) * rad).toFloat(); val z = (sin(th) * rad).toFloat()
            val part = when { i % 5 == 1 -> 1; i % 5 == 2 -> 2; i % 5 == 3 -> 3; else -> 0 }
            ps += P(x, y, z, 0f, 0f, part, (Math.random() * 6.28).toFloat(), (0.55f + Math.random().toFloat() * 0.6f))
            when (part) { 1 -> eyeL++; 2 -> eyeR++; 3 -> mouth++ }
        }
        // Face targets: two ring eyes, a wavy mouth, the rest form the round outline.
        var a = 0; var b = 0; var m = 0; var o = 0
        val outlineCount = ps.count { it.part == 0 }
        for (p in ps) when (p.part) {
            1 -> { val ang = 2 * PI * a++ / eyeL; p.fx = -0.34f + 0.15f * cos(ang).toFloat(); p.fy = -0.16f + 0.15f * sin(ang).toFloat() }
            2 -> { val ang = 2 * PI * b++ / eyeR; p.fx = 0.34f + 0.15f * cos(ang).toFloat(); p.fy = -0.16f + 0.15f * sin(ang).toFloat() }
            3 -> { val k = m++ / (mouth - 1f); p.fx = -0.34f + 0.68f * k; p.fy = 0.30f }
            else -> { val ang = 2 * PI * o++ / outlineCount; p.fx = 0.92f * cos(ang).toFloat(); p.fy = 0.92f * sin(ang).toFloat() }
        }
    }

    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1000; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
        addUpdateListener { tick() }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Born as a sphere, then slowly gathers into a face.
        val now = System.currentTimeMillis()
        bornAt = now; morph = 0f; morphTarget = 0f
        nextBlink = now + 4500; nextLook = now + 5500; nextThink = now + 26_000
        anim.start()
    }
    override fun onDetachedFromWindow() { anim.cancel(); super.onDetachedFromWindow() }

    override fun setVisibility(v: Int) {
        val was = visibility
        super.setVisibility(v)
        if (v == VISIBLE && was != VISIBLE) { bornAt = System.currentTimeMillis(); morph = 0f; morphTarget = 0f }
    }

    private fun tick() {
        val now = System.currentTimeMillis()
        t += 0.016f
        // Life cycle: sphere for ~2s after appearing, then become the face.
        if (logo == null) morphTarget = if (now - bornAt < 2200) 0f else 1f
        // Every so often it "thinks": melts back into the sphere for a moment.
        if (logo == null && now >= nextThink && morph > 0.95f) {
            morphTarget = 0f; bornAt = now; spinBoost = 1f
            nextThink = now + 24_000 + (Math.random() * 20_000).toLong()
        }
        if (logo != null && now >= logoUntil) { logo = null; bornAt = now - 1600 }
        morph += (morphTarget - morph) * (if (morphTarget > morph) 0.035f else 0.08f)
        logoK += ((if (logo != null) 1f else 0f) - logoK) * 0.12f
        spinBoost *= 0.97f
        if (now >= nextBlink && morph > 0.8f) {
            ValueAnimator.ofFloat(0f, 1f, 0f).apply { duration = 200; addUpdateListener { blink = it.animatedValue as Float }; start() }
            nextBlink = now + 2400 + (Math.random() * 3600).toLong()
        }
        if (now >= nextLook) { lookTarget = listOf(-1f, 0f, 1f, 0f, 0f).random(); nextLook = now + 2500 + (Math.random() * 4000).toLong() }
        look += (lookTarget - look) * 0.08f
        invalidate()
    }

    /** Pop an app's logo: the particles swirl into an orb around it, then form the face again. */
    fun peek(icon: Bitmap?) {
        if (icon == null) return
        logo = icon
        logoUntil = System.currentTimeMillis() + 3200
        morphTarget = 0f; spinBoost = 1.4f
        performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
    }

    private fun ease(x: Float) = (x * x * (3 - 2 * x)).coerceIn(0f, 1f)

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val cx = w / 2f; val cy = h / 2f
        val R = minOf(w, h) * 0.42f
        val k = ease(morph)
        val rot = t * (0.6f + spinBoost * 4f)
        val cr = cos(rot); val sr = sin(rot)
        val tilt = 0.35f; val ct = cos(tilt); val st = sin(tilt)
        val breathe = 1f + 0.03f * sin(t * 1.6f)
        for (p in ps) {
            // Sphere position (spinning around Y, tilted towards you).
            var x = p.sx * cr + p.sz * sr
            var z = -p.sx * sr + p.sz * cr
            var y = p.sy
            val y2 = y * ct - z * st; z = y * st + z * ct; y = y2
            val sxp = cx + x * R * breathe
            val syp = cy + y * R * breathe
            // Face position (with blinking eyes, a glance and a wiggly mouth).
            var fx = p.fx; var fy = p.fy
            when (p.part) {
                1, 2 -> { val ec = -0.16f; fy = ec + (fy - ec) * (1f - blink * 0.9f); fx += look * 0.07f }
                3 -> fy += 0.08f * sin(fx * 9f + t * 3f) * (0.7f + 0.3f * sin(t))
            }
            val jx = sin(t * 1.3f + p.seed) * 0.02f; val jy = cos(t * 1.1f + p.seed * 1.7f) * 0.02f
            val fxp = cx + (fx + jx) * R * breathe
            val fyp = cy + (fy + jy) * R * breathe
            val px = sxp + (fxp - sxp) * k
            val py = syp + (fyp - syp) * k
            // Colour: warm orange on top, white below (like the reference); face lines turn bright white.
            val top = ((1f - p.sy) / 2f).coerceIn(0f, 1f)
            val sphereCol = blend(Color.WHITE, 0xFFFFA24C.toInt(), top)
            val faceCol = if (p.part == 0) blend(Color.WHITE, 0xFFFFB46B.toInt(), top * 0.8f) else Color.WHITE
            paint.color = blend(sphereCol, faceCol, k)
            val depth = (z + 1f) / 2f                  // 0 back … 1 front
            val alphaSphere = 0.25f + 0.75f * depth
            val alphaFace = if (p.part == 0) 0.55f else 1f
            paint.alpha = (255 * (alphaSphere + (alphaFace - alphaSphere) * k) * (1f - logoK * 0.35f)).toInt().coerceIn(0, 255)
            val size = p.size * d * (0.55f + 0.45f * (depth + (1f - depth) * k)) * (if (p.part != 0) 1f + 0.25f * k else 1f)
            c.drawCircle(px, py, size, paint)
        }
        val lg = logo
        if (lg != null && logoK > 0.02f) {
            val ls = R * 1.15f * logoK
            r.set(cx - ls / 2, cy - ls / 2, cx + ls / 2, cy + ls / 2)
            clip.reset(); clip.addOval(r, Path.Direction.CW)
            c.save(); c.clipPath(clip)
            c.drawBitmap(lg, null, r, Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = (255 * logoK).toInt().coerceIn(0, 255) })
            c.restore()
        }
    }

    private fun blend(a: Int, b: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        return Color.rgb((Color.red(a) + (Color.red(b) - Color.red(a)) * k).toInt(),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * k).toInt(),
            (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * k).toInt())
    }
}
