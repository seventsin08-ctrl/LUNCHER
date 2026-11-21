package com.luncher.home

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Gilbert's HUD look: cyan + white lines on black, like a sci-fi heads-up display. */
object Hud {
    const val CYAN = 0xFF00E5FF.toInt()
    const val SOFT = 0xFF7FF4FF.toInt()
    val mono: Typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
    val monoBold: Typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    fun a(c: Int, alpha: Int) = Color.argb(alpha.coerceIn(0, 255), Color.red(c), Color.green(c), Color.blue(c))
}

/** Runs [step] every frame while the view is on screen (time in seconds). */
abstract class HudAnimView(ctx: Context) : View(ctx) {
    protected val d = ctx.resources.displayMetrics.density
    protected var t = 0f
    private var last = 0L
    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1000; repeatCount = ValueAnimator.INFINITE
        addUpdateListener {
            val now = System.nanoTime()
            t += if (last == 0L) 0.016f else ((now - last) / 1e9f).coerceIn(0.004f, 0.05f)
            last = now; invalidate()
        }
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); last = 0L; anim.start() }
    override fun onDetachedFromWindow() { anim.cancel(); super.onDetachedFromWindow() }
    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible) { last = 0L; if (anim.isPaused) anim.resume() } else if (anim.isRunning) anim.pause()
    }
}

/**
 * The big HUD around Gilbert's globe: rotating rings with ticks, segmented arcs, side brackets
 * and dot grids. [boot] plays the start-up (everything draws itself in); [ping] sends a pulse
 * ring out when new data arrives.
 */
class HudRings(ctx: Context) : HudAnimView(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.BUTT }
    private val f = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    /** 0 → 1 start-up progress. */
    var boot = 1f; set(v) { field = v; invalidate() }
    private var pingT = -10f

    fun startBoot(ms: Long = 1100) {
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ms; interpolator = DecelerateInterpolator(1.4f)
            addUpdateListener { boot = it.animatedValue as Float }
        }.start()
    }
    fun ping() { pingT = t }

    private fun seg(k: Float, from: Float, to: Float) = ((k - from) / (to - from)).coerceIn(0f, 1f)

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val cx = w / 2f; val cy = h / 2f
        val R = min(w, h) * 0.46f
        val b = boot
        // --- concentric rings with ticks (each spins its own way)
        val rings = floatArrayOf(1f, 0.86f, 0.74f, 0.62f)
        rings.forEachIndexed { i, k ->
            val rr = R * k
            val prog = seg(b, i * 0.12f, 0.5f + i * 0.12f)
            if (prog <= 0f) return@forEachIndexed
            r.set(cx - rr, cy - rr, cx + rr, cy + rr)
            val rot = (t * (if (i % 2 == 0) 14f else -20f) * (1f + i * 0.3f)) % 360f
            p.strokeWidth = if (i == 3) d * 1.4f else d * 0.8f
            p.color = if (i == 3) Hud.a(Color.WHITE, 230) else Hud.a(Color.WHITE, 70 + i * 25)
            c.drawArc(r, rot - 90f, 360f * prog, false, p)
            // tick marks
            if (i < 3) {
                p.strokeWidth = d * 1.2f
                p.color = Hud.a(Hud.SOFT, 150)
                val n = 36 - i * 8
                for (k2 in 0 until (n * prog).toInt()) {
                    if (k2 % 3 == 1) continue
                    val a = Math.toRadians((rot + k2 * 360f / n).toDouble())
                    val r1 = rr - d * 2f; val r2 = rr + d * 2f
                    c.drawLine(cx + (r1 * cos(a)).toFloat(), cy + (r1 * sin(a)).toFloat(), cx + (r2 * cos(a)).toFloat(), cy + (r2 * sin(a)).toFloat(), p)
                }
            }
        }
        // --- segmented bright arcs sweeping around
        val arcs = seg(b, 0.35f, 0.9f)
        if (arcs > 0f) {
            p.strokeWidth = d * 3f
            for (k in 0 until 4) {
                val rr = R * (0.8f + 0.06f * (k % 2))
                r.set(cx - rr, cy - rr, cx + rr, cy + rr)
                val start = (t * (30f + k * 11f) * (if (k % 2 == 0) 1 else -1) + k * 90f) % 360f
                p.color = Hud.a(if (k == 0) Hud.CYAN else Color.WHITE, ((if (k == 0) 220 else 150) * arcs).toInt())
                c.drawArc(r, start, (22f + 10f * k) * arcs, false, p)
            }
        }
        // --- dot grids on both sides
        val grid = seg(b, 0.45f, 1f)
        if (grid > 0f) {
            f.color = Hud.a(Color.WHITE, (110 * grid).toInt())
            val gap = d * 4f
            for (side in listOf(-1, 1)) {
                val gx = cx + side * R * 0.98f
                for (row in -4..4) for (col in 0..3) {
                    val x = gx + side * col * gap - (if (side > 0) 0f else 0f)
                    val y = cy + row * gap
                    if ((row + col + (t * 3).toInt()) % 7 == 0) continue
                    c.drawCircle(x, y, d * 0.8f, f)
                }
            }
        }
        // --- side brackets (slide in)
        val br = seg(b, 0.3f, 1f)
        if (br > 0f) {
            p.strokeWidth = d * 1.2f; p.color = Hud.a(Color.WHITE, (200 * br).toInt())
            val slide = (1f - br) * d * 30f
            for (side in listOf(-1, 1)) {
                val x0 = cx + side * (R * 1.25f + slide)
                val x1 = x0 + side * d * 26f
                c.drawLine(x0, cy - R * 0.8f, x1, cy - R * 0.55f, p)
                c.drawLine(x0, cy + R * 0.8f, x1, cy + R * 0.55f, p)
                c.drawLine(x1, cy - R * 0.35f, x1, cy + R * 0.35f, p)
                f.color = Hud.a(Color.WHITE, (230 * br).toInt())
                c.drawRect(minOf(x1, x1 + side * d * 3f), cy - R * 0.2f, maxOf(x1, x1 + side * d * 3f), cy + R * 0.2f, f)
                f.color = Hud.a(Hud.CYAN, (200 * br).toInt())
                c.drawRect(minOf(x0, x0 + side * d * 10f), cy - R * 0.92f, maxOf(x0, x0 + side * d * 10f), cy - R * 0.92f + d * 2.5f, f)
                c.drawRect(minOf(x0, x0 + side * d * 10f), cy + R * 0.92f - d * 2.5f, maxOf(x0, x0 + side * d * 10f), cy + R * 0.92f, f)
            }
        }
        // --- ping: a ring flies out
        val pt = t - pingT
        if (pt in 0f..1.2f) {
            val k = pt / 1.2f
            val rr = R * (0.55f + 0.7f * k)
            p.strokeWidth = d * (2.5f - 2f * k); p.color = Hud.a(Hud.CYAN, (220 * (1f - k)).toInt())
            c.drawCircle(cx, cy, rr, p)
        }
    }
}

/**
 * A HUD panel: thin frame with bright corner brackets. [reveal] draws the frame in, and a scan
 * line sweeps down over the content each time a window opens or refreshes.
 */
class HudFrame(ctx: Context) : LinearLayout(ctx) {
    private val d = ctx.resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val f = Paint(Paint.ANTI_ALIAS_FLAG)
    private var reveal = 1f
    private var scan = 2f
    init { setWillNotDraw(false); orientation = VERTICAL }

    fun play() {
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 520; interpolator = DecelerateInterpolator()
            addUpdateListener { reveal = it.animatedValue as Float; scan = reveal * 1.2f; invalidate() }
        }.start()
    }

    override fun dispatchDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        // background
        f.shader = null; f.color = 0x66000000; c.drawRect(0f, 0f, w, h, f)
        super.dispatchDraw(c)
        val k = reveal
        // thin frame
        p.strokeWidth = maxOf(1f, d * 0.8f); p.color = Hud.a(Hud.SOFT, 70)
        c.drawLine(0f, 0f, w * k, 0f, p); c.drawLine(w, h, w - w * k, h, p)
        c.drawLine(0f, h, 0f, h - h * k, p); c.drawLine(w, 0f, w, h * k, p)
        // corner brackets
        val L = d * 16f * k
        p.strokeWidth = d * 2f; p.color = Hud.a(Hud.CYAN, 230)
        c.drawLine(0f, 0f, L, 0f, p); c.drawLine(0f, 0f, 0f, L, p)
        c.drawLine(w, 0f, w - L, 0f, p); c.drawLine(w, 0f, w, L, p)
        c.drawLine(0f, h, L, h, p); c.drawLine(0f, h, 0f, h - L, p)
        c.drawLine(w, h, w - L, h, p); c.drawLine(w, h, w, h - L, p)
        // scan line
        if (scan in 0f..1.05f) {
            val y = h * scan
            f.shader = LinearGradient(0f, y - d * 24f, 0f, y, Color.TRANSPARENT, Hud.a(Hud.CYAN, 70), Shader.TileMode.CLAMP)
            c.drawRect(0f, y - d * 24f, w, y, f)
            f.shader = null; f.color = Hud.a(Hud.CYAN, 200)
            c.drawRect(0f, y - d, w, y, f)
        }
    }
}

/** A round gauge: an arc that fills to [value]/[max] while the number counts up. */
class HudGauge(ctx: Context, private val label: String, private val value: Int, private val max: Int) : HudAnimView(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; color = Color.WHITE }
    private val r = RectF()
    private var k = 0f
    override fun onDraw(c: Canvas) {
        k += (1f - k) * 0.06f
        val w = width.toFloat(); val h = height.toFloat()
        val s = min(w, h * 0.8f); val cx = w / 2f; val cy = s / 2f + d * 2f
        val rr = s / 2f - d * 6f
        r.set(cx - rr, cy - rr, cx + rr, cy + rr)
        p.strokeWidth = d * 1f; p.color = Hud.a(Color.WHITE, 50); c.drawCircle(cx, cy, rr + d * 4f, p)
        p.strokeWidth = d * 4f; p.color = Hud.a(Color.WHITE, 30); c.drawArc(r, 135f, 270f, false, p)
        val frac = (value.toFloat() / max.coerceAtLeast(1)).coerceIn(0f, 1f)
        p.color = Hud.CYAN; c.drawArc(r, 135f, 270f * frac * k, false, p)
        // little rotating tick
        p.strokeWidth = d * 1.2f; p.color = Hud.a(Hud.SOFT, 160)
        val a = Math.toRadians((t * 60f % 360f).toDouble())
        c.drawLine(cx + ((rr + d * 2f) * cos(a)).toFloat(), cy + ((rr + d * 2f) * sin(a)).toFloat(),
            cx + ((rr + d * 6f) * cos(a)).toFloat(), cy + ((rr + d * 6f) * sin(a)).toFloat(), p)
        tp.typeface = Hud.monoBold; tp.textSize = rr * 0.62f; tp.color = Color.WHITE
        c.drawText("${(value * k + 0.5f).toInt()}", cx, cy - (tp.descent() + tp.ascent()) / 2, tp)
        tp.typeface = Hud.mono; tp.textSize = d * 8.5f; tp.color = Hud.a(Color.WHITE, 160)
        c.drawText(label, cx, h - d * 2f, tp)
    }
}

/**
 * The people orbit: a planet of white particles with a tilted ring; the people who message you
 * most ride on the ring with their names (closest = most messages).
 */
class HudOrbit(ctx: Context, private val names: List<Pair<String, Int>>) : HudAnimView(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Hud.mono }
    private val planet = ArrayList<FloatArray>()
    private val ring = ArrayList<FloatArray>()
    init {
        val n = 260; val golden = PI * (3 - Math.sqrt(5.0))
        for (i in 0 until n) {
            val y = 1 - (i / (n - 1f)) * 2; val rad = Math.sqrt((1 - y * y).toDouble()); val th = golden * i
            planet += floatArrayOf((cos(th) * rad).toFloat(), y, (sin(th) * rad).toFloat())
        }
        val rnd = java.util.Random(3)
        repeat(420) { ring += floatArrayOf((rnd.nextFloat() * 2 * PI).toFloat(), 1.35f + rnd.nextFloat() * 0.45f) }
    }
    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val cx = w / 2f; val cy = h / 2f
        val R = min(w * 0.22f, h * 0.30f)
        val tilt = 0.32f
        val rot = t * 0.35f
        val cr = cos(rot); val sr = sin(rot)
        // back half of the ring
        fun ringPt(a: Float, rr: Float): Triple<Float, Float, Float> {
            val x = cos(a) * rr; val z = sin(a) * rr
            return Triple(cx + x * R, cy + z * R * tilt - x * R * 0.18f, z)
        }
        fun drawRing(front: Boolean) {
            for (q in ring) {
                val a = q[0] + t * 0.12f
                val (x, y, z) = ringPt(a, q[1])
                if ((z > 0) != front) continue
                p.color = Hud.a(Color.WHITE, if (front) 200 else 70); c.drawCircle(x, y, d * 0.9f, p)
            }
        }
        drawRing(false)
        for (q in planet) {
            val x = q[0] * cr + q[2] * sr; var z = -q[0] * sr + q[2] * cr
            val y = q[1]
            val depth = (z + 1f) / 2f
            p.color = Hud.a(Color.WHITE, (40 + 200 * depth).toInt())
            c.drawCircle(cx + x * R, cy + y * R, d * (0.6f + 0.8f * depth), p)
        }
        drawRing(true)
        // people on the ring
        val n = names.size.coerceAtLeast(1)
        names.forEachIndexed { i, (name, count) ->
            val a = (2 * PI * i / n).toFloat() + t * 0.18f
            val (x, y, z) = ringPt(a, 1.55f)
            val front = z > -0.2f
            p.color = Hud.a(if (i == 0) Hud.CYAN else Hud.SOFT, if (front) 255 else 110)
            c.drawCircle(x, y, d * (3f + 3f * count / (names.first().second.coerceAtLeast(1)).toFloat()), p)
            tp.textSize = d * 9f; tp.color = Hud.a(Color.WHITE, if (front) 230 else 90)
            c.drawText(name.take(12), x, y - d * 8f, tp)
        }
    }
}
