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
    private var style = "orb"
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

    // ------------------------------------------------------------ the globe face ("orb")
    // A cyan wireframe globe: a shell of points stitched together with faint lines, a dense
    // glowing core, and little spikes around it. Moods change its spin, core and colour.
    private var links = IntArray(0)
    private val core = ArrayList<FloatArray>()
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private var glowR = 0f
    private var orbPulse = 0f
    /** A little ping (new data arrived): the core flashes and the globe spins up. */
    fun ping() { orbPulse = 1f; spinBoost = maxOf(spinBoost, 0.8f); invalidate() }

    private fun buildOrb() {
        // Each point links to its 2 nearest neighbours → the mesh lines.
        val n = ps.size
        links = IntArray(n * 2)
        for (i in 0 until n) {
            var b1 = -1; var b2 = -1; var d1 = 9f; var d2 = 9f
            val a = ps[i]
            for (j in 0 until n) if (j != i) {
                val q = ps[j]
                val dd = (a.sx - q.sx) * (a.sx - q.sx) + (a.sy - q.sy) * (a.sy - q.sy) + (a.sz - q.sz) * (a.sz - q.sz)
                if (dd < d1) { d2 = d1; b2 = b1; d1 = dd; b1 = j } else if (dd < d2) { d2 = dd; b2 = j }
            }
            links[i * 2] = b1; links[i * 2 + 1] = b2
        }
        core.clear()
        val rnd = java.util.Random(7)
        repeat(140) {
            // Gaussian cloud, densest in the middle.
            core += floatArrayOf((rnd.nextGaussian() * 0.16).toFloat(), (rnd.nextGaussian() * 0.2).toFloat(),
                (rnd.nextGaussian() * 0.16).toFloat(), rnd.nextFloat() * 6.28f)
        }
    }

    private fun drawOrb(c: Canvas, w: Float, h: Float) {
        if (links.size != ps.size * 2) buildOrb()
        val cx = w / 2f; val cy = h / 2f
        val md = mood
        orbPulse *= 0.94f
        val spin = when (md) { "thinking", "curious" -> 2.6f; "excited" -> 2f; "sleepy" -> 0.35f; else -> 1f }
        val coreK = when (md) { "excited", "surprised" -> 1.35f; "sleepy" -> 0.65f; "listening" -> 1f + 0.2f * sin(t * 8f); "thinking" -> 0.9f + 0.15f * sin(t * 5f); else -> 1f } + orbPulse * 0.6f
        // His colour is how he feels: a mood reaction first, otherwise his mind's dominant emotion.
        val target = when (md) {
            "love" -> 0xFFFF7EB6.toInt(); "sad" -> 0xFFFF6A3D.toInt(); "excited" -> 0xFFD07CFF.toInt(); "sleepy" -> 0xFF4A6FA5.toInt()
            "curious" -> 0xFF3DFFB8.toInt(); "happy" -> 0xFFFFC94D.toInt()
            else -> GilbertMind.color() ?: Hud.CYAN
        }
        colNow = if (colNow == 0) target else blend(colNow, target, 0.05f)   // fades smoothly between feelings
        val base = colNow
        val R = minOf(w, h) * 0.40f * (1f + 0.025f * sin(t * 1.6f))
        val rot = t * (0.45f + spinBoost * 3f) * speedK * spin
        val cr = cos(rot); val sr = sin(rot)
        val tilt = 0.4f + 0.1f * sin(t * 0.3f); val ct = cos(tilt); val st = sin(tilt)
        val n = ps.size
        val xs = FloatArray(n); val ys = FloatArray(n); val zs = FloatArray(n)
        for (i in 0 until n) {
            val p = ps[i]
            val x = p.sx * cr + p.sz * sr
            var z = -p.sx * sr + p.sz * cr
            val y = p.sy * ct - z * st; z = p.sy * st + z * ct
            xs[i] = cx + x * R; ys[i] = cy + y * R; zs[i] = z
        }
        // Glow behind the core.
        val gr = R * 0.75f * coreK
        if (kotlin.math.abs(gr - glowR) > 1f || glow.shader == null) {
            glowR = gr
            glow.shader = android.graphics.RadialGradient(0f, 0f, gr.coerceAtLeast(1f),
                intArrayOf(android.graphics.Color.argb(150, Color.red(base), Color.green(base), Color.blue(base)), Color.TRANSPARENT), null,
                android.graphics.Shader.TileMode.CLAMP)
        }
        c.save(); c.translate(cx, cy); c.drawCircle(0f, 0f, gr, glow); c.restore()
        // Mesh lines (front brighter).
        line.strokeWidth = maxOf(0.6f, d * 0.5f)
        for (i in 0 until n) for (k in 0..1) {
            val j = links[i * 2 + k]; if (j < 0 || j < i && links[j * 2] == i) continue
            val depth = ((zs[i] + zs[j]) / 2f + 1f) / 2f
            line.color = base; line.alpha = (25 + 110 * depth).toInt()
            c.drawLine(xs[i], ys[i], xs[j], ys[j], line)
        }
        // Shell points + spikes.
        for (i in 0 until n) {
            val depth = (zs[i] + 1f) / 2f
            paint.color = base; paint.alpha = (60 + 195 * depth).toInt().coerceIn(0, 255)
            c.drawCircle(xs[i], ys[i], d * (0.5f + 0.7f * depth) * ps[i].size / 1.6f, paint)
            if (i % 9 == 0 && depth > 0.35f) {
                val dx = xs[i] - cx; val dy = ys[i] - cy
                line.alpha = (140 * depth).toInt()
                c.drawLine(cx + dx * 1.1f, cy + dy * 1.1f, cx + dx * 1.2f, cy + dy * 1.2f, line)
            }
        }
        // The core: a cloud that gathers into his little body, which changes pose with what he's doing.
        val now = System.currentTimeMillis()
        val pose = when (md) {
            "listening" -> "talk"; "excited", "surprised" -> "jump"; "sleepy" -> "slump"; "sad" -> "down"
            "thinking", "curious" -> "think"; "happy", "love" -> if (now - waveAt < 2600) "wave" else "stand"
            else -> "stand"
        }
        // Now and then (and when new data arrives) he dissolves into the cloud and reforms.
        if (now > nextMelt) { bodyGoal = 0f; nextMelt = now + 22_000 + (Math.random() * 18_000).toLong(); meltUntil = now + 1400 }
        if (now > meltUntil && md != "thinking") bodyGoal = 1f
        if (orbPulse > 0.5f) { bodyGoal = 0f; meltUntil = now + 900 }
        if (md == "thinking" && sin(t * 0.8f) > 0.6f) bodyGoal = 0f
        bodyK += (bodyGoal - bodyK) * 0.06f
        val bk = bodyK * bodyK * (3 - 2 * bodyK)
        val coreCol = blend(base, Color.WHITE, 0.55f)
        val small = minOf(w, h) < d * 70   // in the tiny island he stays a cloud (too small for a body)
        for ((i, q) in core.withIndex()) {
            val x = q[0] * cr + q[2] * sr; val z = -q[0] * sr + q[2] * cr
            val j = 0.04f * sin(t * 3f + q[3])
            var px = cx + (x + j) * R * coreK; var py = cy + (q[1] + j) * R * coreK
            if (!small && bk > 0.01f) {
                val (bx, by) = bodyPoint(i, core.size, pose)
                px += (cx + bx * R - px) * bk; py += (cy + by * R - py) * bk
            }
            paint.color = coreCol
            paint.alpha = (150 + 100 * ((z + 0.5f).coerceIn(0f, 1f) * (1f - bk) + bk)).toInt().coerceIn(0, 255)
            c.drawCircle(px, py, d * (0.75f + 0.25f * bk), paint)
        }
    }

    private var colNow = 0
    private var bodyK = 0f
    private var bodyGoal = 1f
    private var nextMelt = System.currentTimeMillis() + 15_000
    private var meltUntil = 0L
    private var waveAt = 0L
    /** Wave hello (e.g. when his console opens). */
    fun wave() { waveAt = System.currentTimeMillis(); react("happy", 2600) }

    /**
     * Where particle [i] of [n] sits in his body for a [pose] (units of the globe's radius, 0,0 = centre).
     * A simple figure like a sign-post person: head, torso, two arms, two legs.
     */
    private fun bodyPoint(i: Int, n: Int, pose: String): Pair<Float, Float> {
        val k = i.toFloat() / n
        // Gentle life: breathing and a sway.
        val sway = sin(t * 1.3f) * 0.008f
        val bob = when (pose) { "talk" -> kotlin.math.abs(sin(t * 6f)) * -0.012f; "jump" -> -kotlin.math.abs(sin(t * 5f)) * 0.08f; else -> sin(t * 1.6f) * 0.006f }
        val slump = if (pose == "slump") 0.05f else if (pose == "down") 0.03f else 0f
        val headY = -0.33f + slump * 1.4f + bob
        fun line(x0: Float, y0: Float, x1: Float, y1: Float, u: Float) = (x0 + (x1 - x0) * u + sway) to (y0 + (y1 - y0) * u + bob)
        return when {
            k < 0.2f -> {           // head (filled disc)
                val u = k / 0.2f; val ang = u * 2f * PI.toFloat() * 3.3f; val rr = 0.075f * kotlin.math.sqrt((u * 7f) % 1f)
                val hx = if (pose == "down" || pose == "slump") 0.01f else 0f
                (rr * cos(ang) + hx + sway) to (headY + rr * sin(ang))
            }
            k < 0.48f -> {          // torso (rounded block)
                val u = (k - 0.2f) / 0.28f; val col = (u * 5f).toInt(); val row = (u * 5f) % 1f
                (-0.085f + col * 0.0425f + sway) to (-0.22f + slump + row * 0.33f + bob)
            }
            k < 0.62f -> {          // right arm (his left on screen)
                val u = (k - 0.48f) / 0.14f
                val (hx, hy) = when (pose) {
                    "wave" -> (0.2f + 0.04f * sin(t * 9f)) to -0.42f
                    "jump" -> 0.2f to -0.4f
                    "think" -> 0.03f to -0.27f
                    "talk" -> (0.18f + 0.03f * sin(t * 4f)) to (-0.02f + 0.03f * sin(t * 3f))
                    "slump" -> 0.12f to 0.16f
                    else -> 0.13f to 0.08f
                }
                line(0.11f, -0.2f + slump, hx, hy + slump, u)
            }
            k < 0.76f -> {          // left arm
                val u = (k - 0.62f) / 0.14f
                val (hx, hy) = when (pose) {
                    "jump" -> -0.2f to -0.4f
                    "talk" -> (-0.17f - 0.02f * sin(t * 3.5f)) to (0.0f + 0.03f * cos(t * 3f))
                    "down" -> -0.09f to 0.1f
                    "slump" -> -0.12f to 0.16f
                    else -> -0.13f to 0.08f
                }
                line(-0.11f, -0.2f + slump, hx, hy + slump, u)
            }
            k < 0.88f -> {          // right leg
                val u = (k - 0.76f) / 0.12f
                line(0.04f, 0.11f + slump, if (pose == "jump") 0.09f else 0.05f, 0.42f, u)
            }
            else -> {               // left leg
                val u = (k - 0.88f) / 0.12f
                line(-0.04f, 0.11f + slump, if (pose == "jump") -0.09f else -0.05f, 0.42f, u)
            }
        }
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (style == "orb") {
            drawOrb(c, w, h)
            if (badge > 0) drawBadge(c, w, h)
            val lg = logo
            if (lg != null && logoK > 0.02f) {
                val ls = minOf(w, h) * 0.45f * logoK
                r.set(w / 2 - ls / 2, h / 2 - ls / 2, w / 2 + ls / 2, h / 2 + ls / 2)
                clip.reset(); clip.addOval(r, Path.Direction.CW)
                c.save(); c.clipPath(clip)
                c.drawBitmap(lg, null, r, Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = (255 * logoK).toInt().coerceIn(0, 255) })
                c.restore()
            }
            return
        }
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
        if (badge > 0 && logoK < 0.5f) drawBadge(c, w, h)
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

    /**
     * Red bubble: how many notifications Gilbert is holding. It sits fully INSIDE his round face
     * (top-right), because the island clips him to a circle and a corner badge got cut off.
     */
    private val badgeRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.BLACK }
    private fun drawBadge(c: Canvas, w: Float, h: Float) {
        val R = minOf(w, h) / 2f
        val br = R * 0.36f
        val dist = (R - br - R * 0.04f).coerceAtLeast(0f)
        val bx = w / 2f + dist * 0.7071f; val by = h / 2f - dist * 0.7071f
        badgeRing.strokeWidth = maxOf(1f, br * 0.18f)
        c.drawCircle(bx, by, br, badgeP)
        c.drawCircle(bx, by, br, badgeRing)
        val label = if (badge > 9) "9+" else "$badge"
        badgeT.textSize = br * (if (label.length > 1) 0.95f else 1.2f)
        c.drawText(label, bx, by - (badgeT.descent() + badgeT.ascent()) / 2, badgeT)
    }

    private fun blend(a: Int, b: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        return Color.rgb((Color.red(a) + (Color.red(b) - Color.red(a)) * k).toInt(),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * k).toInt(),
            (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * k).toInt())
    }
}
