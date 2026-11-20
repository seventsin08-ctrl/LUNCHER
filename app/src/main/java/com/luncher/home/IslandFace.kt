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
        val seed: Float, val size: Float,
        var mk: Float = 0f                               // 0…1 along its eye / mouth (for mood shapes)
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

    // ---- Emotions
    /**
     * neutral, happy, excited, love, curious (searching), watching (videos), listening (music),
     * surprised, sad, sleepy, thinking. [baseMood] is how he feels about what's on screen;
     * [react] shows a short reaction on top of it.
     */
    var baseMood = "neutral"
    private var reaction: String? = null
    private var reactionUntil = 0L
    val mood get() = if (reaction != null && System.currentTimeMillis() < reactionUntil) reaction!! else baseMood
    // Smoothly blended mood parameters.
    private var mSmile = 0f; private var mSquint = 1f; private var mArc = 0f; private var mO = 0f
    private var mLookY = 0f; private var mBounce = 0f; private var mTint = 0; private var mTintK = 0f
    private var lookX: Float? = null; private var lookY: Float? = null; private var lookUntil = 0L

    fun react(m: String, ms: Long = 1800) { reaction = m; reactionUntil = System.currentTimeMillis() + ms; invalidate() }
    /** Look towards a point (-1…1 each way), e.g. where your finger is, for a moment. */
    fun lookAt(x: Float, y: Float, ms: Long = 1200) {
        lookX = x.coerceIn(-1f, 1f); lookY = y.coerceIn(-1f, 1f); lookUntil = System.currentTimeMillis() + ms
    }

    private var logo: Bitmap? = null
    private var logoK = 0f
    private var logoUntil = 0L

    // ---- Settings (Gilbert › colours, face, particles, speed)
    private var topCol = 0xFFFFA24C.toInt()
    private var botCol = Color.WHITE
    private var speedK = 1f
    private var style = "classic"
    /** Held notifications: a little count bubble on Gilbert. */
    var badge = 0
        set(v) { field = v; invalidate() }
    private val badgeP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFF3B30.toInt() }
    private val badgeT = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER; typeface = android.graphics.Typeface.DEFAULT_BOLD }

    init { build(240) }

    /** Apply Gilbert's settings (colours, face style, how many particles, how fast). */
    fun configure(s: Settings) {
        val (a, b) = Gilbert.colors(s)
        topCol = a; botCol = b
        speedK = s.gilbertSpeed / 100f
        val st = s.gilbertFace
        if (s.gilbertDensity != ps.size || st != style) { style = st; build(s.gilbertDensity) }
        invalidate()
    }

    private fun build(n: Int) {
        ps.clear()
        // Fibonacci sphere: evenly spread points.
        val golden = PI * (3 - sqrt(5.0))
        for (i in 0 until n) {
            val y = 1 - (i / (n - 1f)) * 2
            val rad = sqrt(1 - y * y)
            val th = golden * i
            val x = (cos(th) * rad).toFloat(); val z = (sin(th) * rad).toFloat()
            val part = when { i % 5 == 1 -> 1; i % 5 == 2 -> 2; i % 5 == 3 -> 3; else -> 0 }
            ps += P(x, y, z, 0f, 0f, part, (Math.random() * 6.28).toFloat(), (0.55f + Math.random().toFloat() * 0.6f))
        }
        val eyeL = ps.count { it.part == 1 }; val eyeR = ps.count { it.part == 2 }; val mouth = ps.count { it.part == 3 }
        val outlineCount = ps.count { it.part == 0 }
        var a = 0; var b = 0; var m = 0; var o = 0
        // Eye shape per style: rings °, happy arcs ^, dots •, sleepy lines -
        fun eye(k: Float, cx: Float): Pair<Float, Float> = when (style) {
            "happy" -> { val ang = PI + PI * k; (cx + 0.15f * cos(ang).toFloat()) to (-0.10f + 0.12f * sin(ang).toFloat()) }
            "dots" -> { val ang = 2 * PI * k; (cx + 0.06f * cos(ang).toFloat()) to (-0.16f + 0.06f * sin(ang).toFloat()) }
            "sleepy" -> (cx - 0.15f + 0.30f * k) to -0.14f
            else -> { val ang = 2 * PI * k; (cx + 0.15f * cos(ang).toFloat()) to (-0.16f + 0.15f * sin(ang).toFloat()) }
        }
        // Mouth per style: wave ~, smile ‿, small o
        fun mouthAt(k: Float): Pair<Float, Float> = when (style) {
            "happy", "dots" -> { val ang = PI * k; (-0.30f * cos(ang).toFloat()) to (0.18f + 0.16f * sin(ang).toFloat()) }
            "sleepy" -> { val ang = 2 * PI * k; (0.07f * cos(ang).toFloat()) to (0.32f + 0.07f * sin(ang).toFloat()) }
            else -> (-0.34f + 0.68f * k) to 0.30f
        }
        for (p in ps) when (p.part) {
            1 -> { p.mk = a / eyeL.toFloat(); val (x, y) = eye(a++ / eyeL.toFloat(), -0.34f); p.fx = x; p.fy = y }
            2 -> { p.mk = b / eyeR.toFloat(); val (x, y) = eye(b++ / eyeR.toFloat(), 0.34f); p.fx = x; p.fy = y }
            3 -> { p.mk = m / (mouth - 1f).coerceAtLeast(1f); val (x, y) = mouthAt(m++ / (mouth - 1f).coerceAtLeast(1f)); p.fx = x; p.fy = y }
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

    /** Saves battery and keeps everything else smooth: the orb only animates while you can see it. */
    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible) { lastFrame = 0L; if (anim.isPaused) anim.resume() else if (!anim.isStarted && isAttachedToWindow) anim.start() }
        else if (anim.isRunning) anim.pause()
    }
    private var lastFrame = 0L

    override fun setVisibility(v: Int) {
        val was = visibility
        super.setVisibility(v)
        if (v == VISIBLE && was != VISIBLE) { bornAt = System.currentTimeMillis(); morph = 0f; morphTarget = 0f }
    }

    private fun tick() {
        val now = System.currentTimeMillis()
        // Same speed on 60, 90 or 120 Hz screens (it used to run faster on faster screens).
        val ns = System.nanoTime()
        t += if (lastFrame == 0L) 0.016f else ((ns - lastFrame) / 1e9f).coerceIn(0.004f, 0.05f)
        lastFrame = ns
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
        val md = mood
        // Where he looks: your finger, the screen while you watch/search, or around on his own.
        val lx = lookX; val ly = lookY
        if (lx != null && now < lookUntil) lookTarget = lx
        else when (md) {
            "curious" -> lookTarget = sin(t * 2.4f) * 0.9f            // reading along as you type/search
            "watching" -> lookTarget = sin(t * 0.5f) * 0.25f          // fixed on the screen, tiny drift
            else -> if (now >= nextLook) { lookTarget = listOf(-1f, 0f, 1f, 0f, 0f).random(); nextLook = now + 2500 + (Math.random() * 4000).toLong() }
        }
        look += (lookTarget - look) * 0.08f
        // Mood shapes: smile (+) / frown (−), squint, happy ^ arcs, o-mouth, looking down, bounce, colour.
        val tgt = when (md) {
            "happy" -> floatArrayOf(1f, 1f, 1f, 0f, 0f, 0.4f)
            "excited" -> floatArrayOf(1f, 1.15f, 0f, 0f, 0f, 1f)
            "love" -> floatArrayOf(0.8f, 1f, 1f, 0f, 0f, 0.3f)
            "curious" -> floatArrayOf(0.1f, 1.1f, 0f, 0f, 0.15f, 0f)
            "watching" -> floatArrayOf(0f, 0.75f, 0f, 0.6f, 0.55f, 0f)
            "listening" -> floatArrayOf(0.7f, 0.5f, 1f, 0f, 0f, 0.6f)
            "surprised" -> floatArrayOf(0f, 1.4f, 0f, 1f, 0f, 0.2f)
            "sad" -> floatArrayOf(-0.9f, 0.85f, 0f, 0f, 0.35f, 0f)
            "sleepy" -> floatArrayOf(0f, 0.15f, 0f, 0.5f, 0.2f, 0f)
            "thinking" -> floatArrayOf(-0.2f, 0.9f, 0f, 0f, -0.4f, 0f)
            else -> floatArrayOf(0.25f, 1f, 0f, 0f, 0f, 0f)
        }
        if (lx != null && now < lookUntil) tgt[4] = ly ?: 0f
        val e = 0.1f
        mSmile += (tgt[0] - mSmile) * e; mSquint += (tgt[1] - mSquint) * e; mArc += (tgt[2] - mArc) * e
        mO += (tgt[3] - mO) * e; mLookY += (tgt[4] - mLookY) * e; mBounce += (tgt[5] - mBounce) * e
        val tint = when (md) { "love" -> 0xFFFF6B9A.toInt(); "sad" -> 0xFF64B5F6.toInt(); "excited" -> 0xFFFFD54F.toInt(); "sleepy" -> 0xFF9FA8DA.toInt(); else -> 0 }
        if (tint != 0) mTint = tint
        mTintK += ((if (tint != 0) 1f else 0f) - mTintK) * e

        invalidate()
    }

    /** Pop an app's logo: the particles swirl into an orb around it, then form the face again. */
    fun peek(icon: Bitmap?) {
        if (icon == null) return
        logo = icon
        logoUntil = System.currentTimeMillis() + 3200
        react("surprised", 900)
        morphTarget = 0f; spinBoost = 1.4f
        performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
    }

    private fun ease(x: Float) = (x * x * (3 - 2 * x)).coerceIn(0f, 1f)

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val cx = w / 2f; val cy = h / 2f
        val R = minOf(w, h) * 0.42f
        val k = ease(morph)
        val rot = t * (0.6f + spinBoost * 4f) * speedK
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
                1, 2 -> {
                    val ec = -0.16f
                    // Happy ^ eyes: fold the lower half of each eye up into an arc.
                    if (mArc > 0.01f && fy > ec) fy = fy + (ec - (fy - ec) - fy) * mArc
                    fy = ec + (fy - ec) * mSquint * (1f - blink * 0.9f)
                    fx += look * 0.07f; fy += mLookY * 0.08f
                    if (mood == "sad") fy += (if (p.part == 1) -1 else 1) * (fx + (if (p.part == 1) 0.34f else -0.34f)) * 0.25f
                }
                3 -> {
                    if (style == "classic" && mO < 0.5f) fy += 0.08f * sin(fx * 9f + t * 3f * speedK) * (0.7f + 0.3f * sin(t)) * (1f - mO)
                    // Smile / frown curve.
                    val u = (fx / 0.34f).coerceIn(-1f, 1f)
                    fy += mSmile * 0.10f * (1f - u * u) - mSmile * 0.02f
                    // "o" mouth (surprised / watching / sleepy): gather into a small ring.
                    if (mO > 0.01f) {
                        val ang = 2 * PI * p.mk
                        val ox = (0.08f * cos(ang)).toFloat(); val oy = (0.30f + 0.08f * sin(ang)).toFloat()
                        fx += (ox - fx) * mO; fy += (oy - fy) * mO
                    }
                    fy += mLookY * 0.05f
                }
            }
            if (mBounce > 0.01f) fy -= kotlin.math.abs(sin(t * 5f * speedK)) * 0.06f * mBounce
            val jx = sin(t * 1.3f + p.seed) * 0.02f; val jy = cos(t * 1.1f + p.seed * 1.7f) * 0.02f
            val fxp = cx + (fx + jx) * R * breathe
            val fyp = cy + (fy + jy) * R * breathe
            val px = sxp + (fxp - sxp) * k
            val py = syp + (fyp - syp) * k
            // Colour: warm orange on top, white below (like the reference); face lines turn bright white.
            val top = ((1f - p.sy) / 2f).coerceIn(0f, 1f)
            val sphereCol = blend(botCol, topCol, top)
            var faceCol = if (p.part == 0) blend(botCol, topCol, top * 0.8f) else Color.WHITE
            if (mTintK > 0.02f) faceCol = blend(faceCol, mTint, mTintK * (if (p.part == 0) 0.8f else 0.45f))
            paint.color = blend(sphereCol, faceCol, k)
            val depth = (z + 1f) / 2f                  // 0 back … 1 front
            val alphaSphere = 0.25f + 0.75f * depth
            val alphaFace = if (p.part == 0) 0.55f else 1f
            paint.alpha = (255 * (alphaSphere + (alphaFace - alphaSphere) * k) * (1f - logoK * 0.35f)).toInt().coerceIn(0, 255)
            val size = p.size * d * (0.55f + 0.45f * (depth + (1f - depth) * k)) * (if (p.part != 0) 1f + 0.25f * k else 1f)
            c.drawCircle(px, py, size, paint)
        }
        if (badge > 0 && logoK < 0.5f) {
            // Red bubble: how many notifications Gilbert is holding.
            val br = minOf(w, h) * 0.17f
            val bx = w - br; val by = br
            c.drawCircle(bx, by, br, badgeP)
            badgeT.textSize = br * 1.15f
            c.drawText(if (badge > 9) "9+" else "$badge", bx, by - (badgeT.descent() + badgeT.ascent()) / 2, badgeT)
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
