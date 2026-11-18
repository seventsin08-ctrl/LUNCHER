package com.luncher.home

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.Build
import android.view.MotionEvent
import android.view.View
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** What kind of audio device is connected; picks the 3D model. */
enum class HeadsetKind(val label: String) {
    OVER_EAR("Headphones"),
    EARBUDS("Earbuds"),
    WIRED("Wired Earphones"),
    SPEAKER("Speaker")
}

/**
 * Real-time 3D model of the connected headphones.
 *
 * Smooth (Gouraud) shading: every vertex has its own averaged normal and is lit with a
 * studio setup (key light, fill, soft sky/ground ambient, glossy highlight and a fresnel
 * rim), and the GPU blends the colours across each triangle in a single drawVertices call.
 * The model whirls in on a spring, then turns slowly with a gentle sway; drag to spin it.
 */
class Headset3DView(ctx: Context, kind: HeadsetKind) : View(ctx) {

    // ------------------------------------------------------------------ materials & mesh

    private class Mat(
        val r: Float, val g: Float, val b: Float,
        val shine: Float, val spec: Float, val rim: Float
    )

    private class MeshBuilder {
        val xs = ArrayList<Float>(); val ys = ArrayList<Float>(); val zs = ArrayList<Float>()
        val vm = ArrayList<Int>()
        val vp = ArrayList<Int>()
        val tris = ArrayList<Int>()
        /** For each part (one sphere / tube), the "inside" points used to orient its normals. */
        val spines = ArrayList<List<FloatArray>>()
        var mat = 0

        fun v(x: Float, y: Float, z: Float): Int {
            xs += x; ys += y; zs += z; vm += mat; vp += spines.size - 1
            return xs.size - 1
        }

        fun t(a: Int, b: Int, c: Int) { tris += a; tris += b; tris += c }

        /** Ellipsoid with shared vertices so normals average smoothly. */
        fun sphere(cx: Float, cy: Float, cz: Float, rx: Float, ry: Float, rz: Float, m: Int,
                   su: Int = 34, sv: Int = 24) {
            mat = m
            spines += listOf(floatArrayOf(cx, cy, cz))
            val top = v(cx, cy + ry, cz)
            val rings = IntArray(sv - 1)
            for (i in 1 until sv) {
                val th = PI * i / sv
                rings[i - 1] = xs.size
                for (j in 0 until su) {
                    val ph = 2 * PI * j / su
                    v(
                        (cx + rx * sin(th) * cos(ph)).toFloat(),
                        (cy + ry * cos(th)).toFloat(),
                        (cz + rz * sin(th) * sin(ph)).toFloat()
                    )
                }
            }
            val bottom = v(cx, cy - ry, cz)
            for (j in 0 until su) t(top, rings[0] + (j + 1) % su, rings[0] + j)
            for (i in 0 until sv - 2) for (j in 0 until su) {
                val a = rings[i] + j; val b = rings[i] + (j + 1) % su
                val c = rings[i + 1] + j; val d = rings[i + 1] + (j + 1) % su
                t(a, b, c); t(b, d, c)
            }
            val last = rings[sv - 2]
            for (j in 0 until su) t(bottom, last + j, last + (j + 1) % su)
        }

        /** Smooth tube along a polyline, optionally closed, with rounded (domed) end caps. */
        fun tube(pts: List<FloatArray>, radius: (Int) -> Float, m: Int, seg: Int = 22,
                 closed: Boolean = false, caps: Boolean = false, flatCaps: Boolean = false) {
            mat = m
            spines += pts.toList()
            val n = pts.size
            val rings = IntArray(n)
            val frames = ArrayList<Array<FloatArray>>()
            for (k in 0 until n) {
                val prev = if (k > 0) pts[k - 1] else if (closed) pts[n - 1] else pts[k]
                val next = if (k < n - 1) pts[k + 1] else if (closed) pts[0] else pts[k]
                val tng = norm(floatArrayOf(next[0] - prev[0], next[1] - prev[1], next[2] - prev[2]))
                val up = if (abs(tng[2]) > 0.9f) floatArrayOf(1f, 0f, 0f) else floatArrayOf(0f, 0f, 1f)
                val nn = norm(cross(tng, up))
                val bb = cross(tng, nn)
                frames += arrayOf(tng, nn, bb)
                val r = radius(k)
                rings[k] = xs.size
                for (j in 0 until seg) {
                    val ph = 2 * PI * j / seg
                    val c = cos(ph).toFloat(); val s = sin(ph).toFloat()
                    v(
                        pts[k][0] + r * (c * nn[0] + s * bb[0]),
                        pts[k][1] + r * (c * nn[1] + s * bb[1]),
                        pts[k][2] + r * (c * nn[2] + s * bb[2])
                    )
                }
            }
            val last = if (closed) n else n - 1
            for (k in 0 until last) {
                val r0 = rings[k]; val r1 = rings[(k + 1) % n]
                for (j in 0 until seg) {
                    val a = r0 + j; val b = r0 + (j + 1) % seg
                    val c = r1 + j; val d = r1 + (j + 1) % seg
                    t(a, b, c); t(b, d, c)
                }
            }
            if (caps && !closed) {
                for ((k, dir) in listOf(0 to -1f, n - 1 to 1f)) {
                    val (tng, nn, bb) = frames[k]
                    val r = radius(k)
                    // A few rings shrinking towards the tip make a smooth rounded end.
                    var prevRing = rings[k]
                    val steps = if (flatCaps) 1 else 3
                    for (st in 1..steps) {
                        val a = (PI / 2 * st / (steps + 1))
                        val rr = if (flatCaps) r * 0.6f else (r * cos(a)).toFloat()
                        val off = if (flatCaps) 0f else (r * 0.55f * sin(a)).toFloat() * dir
                        val ring = xs.size
                        for (j in 0 until seg) {
                            val ph = 2 * PI * j / seg
                            val c = cos(ph).toFloat(); val s = sin(ph).toFloat()
                            v(
                                pts[k][0] + tng[0] * off + rr * (c * nn[0] + s * bb[0]),
                                pts[k][1] + tng[1] * off + rr * (c * nn[1] + s * bb[1]),
                                pts[k][2] + tng[2] * off + rr * (c * nn[2] + s * bb[2])
                            )
                        }
                        for (j in 0 until seg) {
                            val a0 = prevRing + j; val b0 = prevRing + (j + 1) % seg
                            val c0 = ring + j; val d0 = ring + (j + 1) % seg
                            t(a0, b0, c0); t(b0, d0, c0)
                        }
                        prevRing = ring
                    }
                    val tipOff = if (flatCaps) 0f else r * 0.55f * dir
                    val ctr = v(pts[k][0] + tng[0] * tipOff, pts[k][1] + tng[1] * tipOff, pts[k][2] + tng[2] * tipOff)
                    for (j in 0 until seg) t(ctr, prevRing + j, prevRing + (j + 1) % seg)
                }
            }
        }

        fun cylinder(a: FloatArray, b: FloatArray, r: Float, m: Int, seg: Int = 40) =
            tube(listOf(a, b), { r }, m, seg, caps = true, flatCaps = true)
    }

    private val mats: List<Mat>
    private val vx: FloatArray; private val vy: FloatArray; private val vz: FloatArray
    private val nx: FloatArray; private val ny: FloatArray; private val nz: FloatArray
    private val vmat: IntArray
    private val ta: IntArray; private val tb: IntArray; private val tc: IntArray

    init {
        val mb = MeshBuilder()
        mats = when (kind) {
            HeadsetKind.EARBUDS -> {
                buildEarbuds(mb); listOf(
                    Mat(244f, 244f, 247f, 60f, 0.65f, 0.25f),   // glossy white
                    Mat(34f, 34f, 38f, 18f, 0.15f, 0.05f),      // mesh grille
                    Mat(196f, 200f, 206f, 90f, 1.0f, 0.3f)      // metal tip
                )
            }
            HeadsetKind.OVER_EAR -> {
                buildOverEar(mb); listOf(
                    Mat(84f, 85f, 92f, 45f, 0.55f, 0.35f),      // anodized shell
                    Mat(30f, 30f, 34f, 8f, 0.08f, 0.12f),       // soft cushions / padding
                    Mat(186f, 189f, 196f, 90f, 1.0f, 0.3f)      // polished sliders
                )
            }
            HeadsetKind.WIRED -> {
                buildWired(mb); listOf(
                    Mat(242f, 242f, 245f, 50f, 0.6f, 0.25f),
                    Mat(34f, 34f, 38f, 18f, 0.15f, 0.05f),
                    Mat(194f, 198f, 204f, 90f, 1.0f, 0.3f)
                )
            }
            HeadsetKind.SPEAKER -> {
                buildSpeaker(mb); listOf(
                    Mat(62f, 64f, 72f, 10f, 0.1f, 0.25f),       // fabric
                    Mat(32f, 32f, 36f, 40f, 0.45f, 0.2f),       // rubber end caps
                    Mat(214f, 214f, 218f, 70f, 0.9f, 0.3f)      // logo
                )
            }
        }
        val vc = mb.xs.size
        vx = FloatArray(vc) { mb.xs[it] }; vy = FloatArray(vc) { mb.ys[it] }; vz = FloatArray(vc) { mb.zs[it] }
        vmat = IntArray(vc) { mb.vm[it] }
        val vpart = IntArray(vc) { mb.vp[it] }
        val tcount = mb.tris.size / 3
        ta = IntArray(tcount) { mb.tris[it * 3] }
        tb = IntArray(tcount) { mb.tris[it * 3 + 1] }
        tc = IntArray(tcount) { mb.tris[it * 3 + 2] }

        // Smooth vertex normals: area-weighted average of the faces around each vertex,
        // oriented to point away from the part's local centre.
        nx = FloatArray(vc); ny = FloatArray(vc); nz = FloatArray(vc)
        for (k in 0 until tcount) {
            val a = ta[k]; val b = tb[k]; val c = tc[k]
            val ux = vx[b] - vx[a]; val uy = vy[b] - vy[a]; val uz = vz[b] - vz[a]
            val wx = vx[c] - vx[a]; val wy = vy[c] - vy[a]; val wz = vz[c] - vz[a]
            var fx = uy * wz - uz * wy; var fy = uz * wx - ux * wz; var fz = ux * wy - uy * wx
            // Make every face normal point "outwards" from the triangle's local surroundings.
            val mx = (vx[a] + vx[b] + vx[c]) / 3f; val my = (vy[a] + vy[b] + vy[c]) / 3f
            val mz = (vz[a] + vz[b] + vz[c]) / 3f
            val cx = nearest(mb.spines[vpart[a]], mx, my, mz)
            if (fx * (mx - cx[0]) + fy * (my - cx[1]) + fz * (mz - cx[2]) < 0f) { fx = -fx; fy = -fy; fz = -fz }
            for (i in intArrayOf(a, b, c)) { nx[i] += fx; ny[i] += fy; nz[i] += fz }
        }
        for (i in 0 until vc) {
            val l = sqrt(nx[i] * nx[i] + ny[i] * ny[i] + nz[i] * nz[i])
            if (l > 1e-9f) { nx[i] /= l; ny[i] /= l; nz[i] /= l } else { ny[i] = 1f }
        }
    }

    private fun nearest(pts: List<FloatArray>, x: Float, y: Float, z: Float): FloatArray {
        var best = pts[0]
        var bd = Float.MAX_VALUE
        for (c in pts) {
            val d = (x - c[0]) * (x - c[0]) + (y - c[1]) * (y - c[1]) + (z - c[2]) * (z - c[2])
            if (d < bd) { bd = d; best = c }
        }
        return best
    }

    private fun buildEarbuds(mb: MeshBuilder) {
        for (s in intArrayOf(-1, 1)) {
            val x = 0.4f * s
            mb.sphere(x, 0.36f, 0f, 0.25f, 0.23f, 0.22f, 0)
            mb.sphere(x - 0.18f * s, 0.41f, 0.07f, 0.075f, 0.062f, 0.05f, 1, 18, 12)
            val stem = (0..10).map { k ->
                val f = k / 10f
                floatArrayOf(x + 0.05f * s * f, 0.18f - 0.8f * f, 0.05f - 0.02f * f)
            }
            mb.tube(stem, { k -> 0.09f - 0.016f * k / 10f }, 0, 26)
            mb.sphere(x + 0.05f * s, -0.62f, 0.03f, 0.074f, 0.045f, 0.074f, 2, 24, 12)
        }
    }

    private fun buildOverEar(mb: MeshBuilder) {
        val band = (0..36).map { k ->
            val t = PI * k / 36
            floatArrayOf((0.68f * cos(t)).toFloat(), (0.05f + 0.82f * sin(t)).toFloat(), 0f)
        }
        mb.tube(band, { 0.062f }, 0, 20)
        val pad = (9..27).map { k ->
            val t = PI * k / 36
            floatArrayOf((0.62f * cos(t)).toFloat(), (0.05f + 0.75f * sin(t)).toFloat(), 0f)
        }
        mb.tube(pad, { 0.074f }, 1, 20, caps = true)
        for (s in intArrayOf(-1, 1)) {
            val x = 0.68f * s
            mb.cylinder(floatArrayOf(x, 0.07f, 0f), floatArrayOf(x, -0.12f, 0f), 0.034f, 2, 20)
            // Rounded ear-cup shell.
            mb.sphere(x + 0.0f * s, -0.38f, 0f, 0.13f, 0.38f, 0.38f, 0, 40, 26)
            // Plush cushion ring on the inside.
            val ring = (0 until 40).map { k ->
                val t = 2 * PI * k / 40
                floatArrayOf(x - 0.12f * s, (-0.38f + 0.27f * cos(t)).toFloat(), (0.27f * sin(t)).toFloat())
            }
            mb.tube(ring, { 0.085f }, 1, 18, closed = true)
        }
    }

    private fun buildWired(mb: MeshBuilder) {
        val split = floatArrayOf(0f, -0.38f, 0.04f)
        for (s in intArrayOf(-1, 1)) {
            val x = 0.34f * s
            mb.sphere(x, 0.5f, 0f, 0.2f, 0.18f, 0.18f, 0)
            mb.sphere(x - 0.14f * s, 0.54f, 0.05f, 0.065f, 0.052f, 0.04f, 1, 18, 12)
            val stem = (0..5).map { k ->
                val f = k / 5f
                floatArrayOf(x + 0.02f * s * f, 0.36f - 0.26f * f, 0.03f)
            }
            mb.tube(stem, { k -> 0.07f - 0.022f * k / 5f }, 0, 22, caps = true)
            val stemBot = stem.last()
            val cable = (0..18).map { k ->
                val f = k / 18f
                val bend = sin(f * PI).toFloat() * 0.12f * s
                floatArrayOf(
                    stemBot[0] + (split[0] - stemBot[0]) * f + bend,
                    stemBot[1] + (split[1] - stemBot[1]) * f,
                    0.03f + 0.04f * f
                )
            }
            mb.tube(cable, { 0.022f }, 0, 12)
        }
        mb.sphere(split[0], split[1], split[2], 0.045f, 0.065f, 0.045f, 0, 20, 14)
        val main = (0..14).map { k ->
            val f = k / 14f
            floatArrayOf(sin(f * PI).toFloat() * 0.08f, split[1] - 0.42f * f, split[2])
        }
        mb.tube(main, { 0.026f }, 0, 12)
        mb.tube(listOf(floatArrayOf(0f, -0.8f, split[2]), floatArrayOf(0f, -0.98f, split[2])), { 0.055f }, 0, 24, caps = true)
        mb.cylinder(floatArrayOf(0f, -0.98f, split[2]), floatArrayOf(0f, -1.12f, split[2]), 0.028f, 2, 20)
    }

    private fun buildSpeaker(mb: MeshBuilder) {
        mb.tube((0..8).map { k -> floatArrayOf(-0.66f + 1.32f * k / 8f, 0f, 0f) }, { 0.36f }, 0, 48)
        mb.tube(listOf(floatArrayOf(-0.78f, 0f, 0f), floatArrayOf(-0.68f, 0f, 0f)), { 0.37f }, 1, 48, caps = true)
        mb.tube(listOf(floatArrayOf(0.68f, 0f, 0f), floatArrayOf(0.78f, 0f, 0f)), { 0.37f }, 1, 48, caps = true)
    }

    // ------------------------------------------------------------------ rendering

    private val vcount = vx.size
    private val screen = FloatArray(vcount * 2)
    private val colors = IntArray(vcount)
    private val tz = FloatArray(vcount)
    private val keys = LongArray(ta.size)
    private val indices = ShortArray(ta.size * 3)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val key = norm(floatArrayOf(-0.5f, 0.7f, 0.65f))
    private val fill = norm(floatArrayOf(0.8f, -0.1f, 0.4f))
    private val half = norm(floatArrayOf(key[0], key[1], key[2] + 1f))

    private var autoYaw = -24f
    private val spin = FloatValueHolder(0f)
    private val lift = FloatValueHolder(0f)
    private var velocity = 0f
    private var dragging = false
    private var lastX = 0f
    private var lastT = 0L
    private var lastFrame = 0L
    private val startTime = System.nanoTime()
    private val spinAnim = SpringAnimation(spin).apply {
        spring = SpringForce(0f).apply { stiffness = 32f; dampingRatio = 0.88f }
    }
    private val liftAnim = SpringAnimation(lift).apply {
        spring = SpringForce(0f).apply { stiffness = 120f; dampingRatio = 0.62f }
    }

    init {
        // Hardware drawVertices needs Android 10+; older phones draw it in software.
        if (Build.VERSION.SDK_INT < 29) setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    /** iOS-style entrance: the model rises in, whirls round and settles into a slow turn. */
    fun playEntrance() {
        if (Motion.scale <= 0f) return
        spinAnim.cancel(); liftAnim.cancel()
        spin.value = 600f; lift.value = 0.45f
        spinAnim.setStartValue(600f); spinAnim.animateToFinalPosition(0f)
        liftAnim.setStartValue(0.45f); liftAnim.animateToFinalPosition(0f)
    }

    override fun onDetachedFromWindow() {
        spinAnim.cancel(); liftAnim.cancel()
        super.onDetachedFromWindow()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                dragging = true; velocity = 0f; lastX = e.x; lastT = e.eventTime
                spinAnim.cancel()
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.x - lastX
                val dt = max(1L, e.eventTime - lastT)
                val deg = dx / width.coerceAtLeast(1) * 320f
                autoYaw += deg
                velocity = velocity * 0.4f + (deg / dt * 1000f) * 0.6f
                lastX = e.x; lastT = e.eventTime
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                velocity = velocity.coerceIn(-1400f, 1400f)
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val now = System.nanoTime()
        val dt = if (lastFrame == 0L) 0f else ((now - lastFrame) / 1e9f).coerceAtMost(0.05f)
        lastFrame = now
        val t = (now - startTime) / 1e9f
        val animate = Motion.scale > 0f

        if (!dragging) {
            val idle = if (animate) 22f else 0f
            autoYaw += (idle + velocity) * dt
            // Exponential decay of a flick so it eases out naturally.
            velocity *= (1f - min(1f, dt * 2.2f))
        }
        val yaw = autoYaw + spin.value
        val sway = if (animate) sin(t * 0.9f) else 0f
        val pitch = 16f + 6f * sway
        val roll = if (animate) 3f * sin(t * 0.7f + 1f) else 0f
        val bob = if (animate) 0.035f * sin(t * 1.6f) else 0f

        val scale = min(w, h) * 0.40f
        val cx = w / 2f
        val cy = h * 0.47f + (lift.value - bob) * scale

        // Soft glow behind the model and a floor shadow that breathes with the float.
        glowPaint.shader = RadialGradient(cx, h * 0.47f, scale * 1.35f,
            intArrayOf(0x33FFFFFF, 0x0FFFFFFF, 0x00FFFFFF), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, h * 0.47f, scale * 1.35f, glowPaint)
        val shY = h * 0.47f + scale * 1.08f
        val shR = scale * (0.85f - bob * 2f - lift.value * 0.4f)
        shadowPaint.shader = RadialGradient(cx, shY, shR.coerceAtLeast(1f),
            0x66000000, 0x00000000, Shader.TileMode.CLAMP)
        canvas.save()
        canvas.scale(1f, 0.2f, cx, shY)
        canvas.drawCircle(cx, shY, shR.coerceAtLeast(1f), shadowPaint)
        canvas.restore()

        val ry = Math.toRadians(yaw.toDouble()); val rp = Math.toRadians(pitch.toDouble())
        val rr = Math.toRadians(roll.toDouble())
        val cyw = cos(ry).toFloat(); val syw = sin(ry).toFloat()
        val cp = cos(rp).toFloat(); val sp = sin(rp).toFloat()
        val cr = cos(rr).toFloat(); val sr = sin(rr).toFloat()
        val cam = 4.4f

        for (i in 0 until vcount) {
            // Rotate position: yaw (Y), pitch (X), roll (Z).
            val x1 = vx[i] * cyw + vz[i] * syw
            val z1 = -vx[i] * syw + vz[i] * cyw
            val y2 = vy[i] * cp - z1 * sp
            val z2 = vy[i] * sp + z1 * cp
            val x3 = x1 * cr - y2 * sr
            val y3 = x1 * sr + y2 * cr
            tz[i] = z2
            val f = cam / (cam - z2)
            screen[i * 2] = cx + x3 * scale * f
            screen[i * 2 + 1] = cy - y3 * scale * f

            // Same rotation for the normal.
            val n1x = nx[i] * cyw + nz[i] * syw
            val n1z = -nx[i] * syw + nz[i] * cyw
            val n2y = ny[i] * cp - n1z * sp
            val n2z = ny[i] * sp + n1z * cp
            val qx = n1x * cr - n2y * sr
            val qy = n1x * sr + n2y * cr
            val qz = n2z
            colors[i] = shadeVertex(mats[vmat[i]], qx, qy, qz)
        }

        // Painter's algorithm: sort triangles far → near with a primitive LongArray sort.
        for (k in ta.indices) {
            val d = (tz[ta[k]] + tz[tb[k]] + tz[tc[k]]) / 3f
            // Map the float to an int with the same signed ordering, then pack (depth, index).
            val bits = java.lang.Float.floatToIntBits(d)
            val ordered = if (bits < 0) bits xor 0x7FFFFFFF else bits
            keys[k] = (ordered.toLong() shl 32) or k.toLong()
        }
        keys.sort()
        for ((o, kk) in keys.withIndex()) {
            val k = (kk and 0xFFFFFFFFL).toInt()
            indices[o * 3] = ta[k].toShort()
            indices[o * 3 + 1] = tb[k].toShort()
            indices[o * 3 + 2] = tc[k].toShort()
        }
        canvas.drawVertices(
            Canvas.VertexMode.TRIANGLES, screen.size, screen, 0, null, 0,
            colors, 0, indices, 0, indices.size, paint
        )

        if (isAttachedToWindow) postInvalidateOnAnimation()
    }

    /** Studio lighting for one vertex. */
    private fun shadeVertex(m: Mat, x: Float, y: Float, z: Float): Int {
        val facing = max(0f, z)
        val sky = 0.30f + 0.16f * (y * 0.5f + 0.5f)
        val diff = max(0f, x * key[0] + y * key[1] + z * key[2])
        val fil = max(0f, x * fill[0] + y * fill[1] + z * fill[2]) * 0.22f
        val spec = max(0f, x * half[0] + y * half[1] + z * half[2]).pow(m.shine) * m.spec
        val rim = (1f - facing).pow(2.6f) * m.rim
        val l = sky + 0.62f * diff + fil
        val add = 255f * (spec + rim)
        return Color.rgb(
            (m.r * l + add).toInt().coerceIn(0, 255),
            (m.g * l + add).toInt().coerceIn(0, 255),
            (m.b * l + add * 1.03f).toInt().coerceIn(0, 255)
        )
    }

    companion object {
        private fun cross(a: FloatArray, b: FloatArray) = floatArrayOf(
            a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]
        )

        private fun norm(a: FloatArray): FloatArray {
            val l = sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2])
            return if (l < 1e-7f) floatArrayOf(0f, 1f, 0f) else floatArrayOf(a[0] / l, a[1] / l, a[2] / l)
        }
    }
}
