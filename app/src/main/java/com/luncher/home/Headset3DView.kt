package com.luncher.home

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
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
 * A small real-time 3D renderer (flat-shaded triangles, painter's algorithm) that draws
 * a glossy model of the connected headphones, spins it in like iOS, then turns slowly.
 * Drag to spin it yourself.
 */
class Headset3DView(ctx: Context, kind: HeadsetKind) : View(ctx) {

    // ------------------------------------------------------------------ mesh

    private class Mat(val r: Int, val g: Int, val b: Int, val shine: Float, val spec: Float)

    private class MeshBuilder {
        val verts = ArrayList<FloatArray>()
        val tris = ArrayList<IntArray>()

        fun v(x: Float, y: Float, z: Float): Int { verts += floatArrayOf(x, y, z); return verts.size - 1 }
        fun t(a: Int, b: Int, c: Int, m: Int) { tris += intArrayOf(a, b, c, m) }

        /** Ellipsoid. */
        fun sphere(cx: Float, cy: Float, cz: Float, rx: Float, ry: Float, rz: Float, m: Int, su: Int = 14, sv: Int = 10) {
            val start = verts.size
            for (i in 0..sv) {
                val th = PI * i / sv
                for (j in 0 until su) {
                    val ph = 2 * PI * j / su
                    v(
                        (cx + rx * sin(th) * cos(ph)).toFloat(),
                        (cy + ry * cos(th)).toFloat(),
                        (cz + rz * sin(th) * sin(ph)).toFloat()
                    )
                }
            }
            for (i in 0 until sv) for (j in 0 until su) {
                val a = start + i * su + j
                val b = start + i * su + (j + 1) % su
                val c = start + (i + 1) * su + j
                val d = start + (i + 1) * su + (j + 1) % su
                t(a, b, c, m); t(b, d, c, m)
            }
        }

        /** Tube along a polyline (open or closed), with optional end caps. */
        fun tube(pts: List<FloatArray>, radius: (Int) -> Float, m: Int, seg: Int = 10,
                 closed: Boolean = false, caps: Boolean = false) {
            val n = pts.size
            val rings = IntArray(n)
            for (k in 0 until n) {
                val prev = if (k > 0) pts[k - 1] else if (closed) pts[n - 1] else pts[k]
                val next = if (k < n - 1) pts[k + 1] else if (closed) pts[0] else pts[k]
                val tng = norm(floatArrayOf(next[0] - prev[0], next[1] - prev[1], next[2] - prev[2]))
                val up = if (abs(tng[2]) > 0.9f) floatArrayOf(1f, 0f, 0f) else floatArrayOf(0f, 0f, 1f)
                val nn = norm(cross(tng, up))
                val bb = cross(tng, nn)
                val r = radius(k)
                rings[k] = verts.size
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
                    t(a, b, c, m); t(b, d, c, m)
                }
            }
            if (caps && !closed) {
                for ((k, ring) in listOf(0 to rings[0], n - 1 to rings[n - 1])) {
                    val ctr = v(pts[k][0], pts[k][1], pts[k][2])
                    for (j in 0 until seg) t(ctr, ring + j, ring + (j + 1) % seg, m)
                }
            }
        }

        fun cylinder(a: FloatArray, b: FloatArray, r: Float, m: Int, seg: Int = 18) =
            tube(listOf(a, b), { r }, m, seg, caps = true)
    }

    private val mats: List<Mat>
    private val vx: FloatArray; private val vy: FloatArray; private val vz: FloatArray
    private val ta: IntArray; private val tb: IntArray; private val tc: IntArray; private val tm: IntArray

    init {
        val mb = MeshBuilder()
        mats = when (kind) {
            HeadsetKind.EARBUDS -> {
                buildEarbuds(mb); listOf(
                    Mat(246, 246, 248, 40f, 0.55f),   // glossy white
                    Mat(40, 40, 44, 10f, 0.1f),       // grille
                    Mat(200, 203, 208, 60f, 0.8f)     // metal tip
                )
            }
            HeadsetKind.OVER_EAR -> {
                buildOverEar(mb); listOf(
                    Mat(70, 70, 76, 30f, 0.45f),      // anodized shell
                    Mat(28, 28, 31, 6f, 0.06f),       // cushions / band padding
                    Mat(180, 182, 188, 60f, 0.9f)     // metal sliders
                )
            }
            HeadsetKind.WIRED -> {
                buildWired(mb); listOf(
                    Mat(244, 244, 246, 36f, 0.5f),    // white buds + cable
                    Mat(40, 40, 44, 10f, 0.1f),       // grilles
                    Mat(196, 199, 204, 60f, 0.85f)    // plug
                )
            }
            HeadsetKind.SPEAKER -> {
                buildSpeaker(mb); listOf(
                    Mat(56, 58, 64, 8f, 0.08f),       // fabric
                    Mat(30, 30, 33, 20f, 0.3f),       // end caps
                    Mat(210, 210, 214, 50f, 0.7f)     // logo strip
                )
            }
        }
        vx = FloatArray(mb.verts.size) { mb.verts[it][0] }
        vy = FloatArray(mb.verts.size) { mb.verts[it][1] }
        vz = FloatArray(mb.verts.size) { mb.verts[it][2] }
        ta = IntArray(mb.tris.size) { mb.tris[it][0] }
        tb = IntArray(mb.tris.size) { mb.tris[it][1] }
        tc = IntArray(mb.tris.size) { mb.tris[it][2] }
        tm = IntArray(mb.tris.size) { mb.tris[it][3] }
    }

    private fun buildEarbuds(mb: MeshBuilder) {
        for (s in intArrayOf(-1, 1)) {
            val x = 0.4f * s
            // Rounded head, tilted slightly towards the middle.
            mb.sphere(x, 0.36f, 0f, 0.25f, 0.23f, 0.23f, 0)
            // Speaker grille facing inwards.
            mb.sphere(x - 0.17f * s, 0.4f, 0.06f, 0.07f, 0.06f, 0.06f, 1, 10, 6)
            // Stem.
            val stem = (0..6).map { k ->
                val f = k / 6f
                floatArrayOf(x + 0.05f * s * f, 0.2f - 0.82f * f, 0.05f)
            }
            mb.tube(stem, { k -> 0.085f - 0.012f * k / 6f }, 0, 12)
            mb.sphere(x + 0.05f * s, -0.62f, 0.05f, 0.075f, 0.05f, 0.075f, 2, 12, 6)
        }
    }

    private fun buildOverEar(mb: MeshBuilder) {
        // Headband arc.
        val band = (0..22).map { k ->
            val t = PI * k / 22
            floatArrayOf((0.68f * cos(t)).toFloat(), (0.05f + 0.82f * sin(t)).toFloat(), 0f)
        }
        mb.tube(band, { 0.065f }, 0, 10)
        // Padding under the top of the band.
        val pad = (5..17).map { k ->
            val t = PI * k / 22
            floatArrayOf((0.62f * cos(t)).toFloat(), (0.05f + 0.75f * sin(t)).toFloat(), 0f)
        }
        mb.tube(pad, { 0.075f }, 1, 10)
        for (s in intArrayOf(-1, 1)) {
            val x = 0.68f * s
            // Metal slider from band down to the cup.
            mb.cylinder(floatArrayOf(x, 0.06f, 0f), floatArrayOf(x, -0.12f, 0f), 0.035f, 2, 10)
            // Ear cup shell (disc facing sideways).
            mb.cylinder(floatArrayOf(x + 0.05f * s, -0.38f, 0f), floatArrayOf(x - 0.12f * s, -0.38f, 0f), 0.36f, 0, 26)
            // Cushion ring on the inside.
            val ring = (0 until 24).map { k ->
                val t = 2 * PI * k / 24
                floatArrayOf(x - 0.17f * s, (-0.38f + 0.28f * cos(t)).toFloat(), (0.28f * sin(t)).toFloat())
            }
            mb.tube(ring, { 0.08f }, 1, 8, closed = true)
        }
    }

    private fun buildWired(mb: MeshBuilder) {
        val split = floatArrayOf(0f, -0.38f, 0.04f)
        for (s in intArrayOf(-1, 1)) {
            val x = 0.34f * s
            mb.sphere(x, 0.5f, 0f, 0.2f, 0.18f, 0.18f, 0)
            mb.sphere(x - 0.13f * s, 0.53f, 0.05f, 0.06f, 0.05f, 0.05f, 1, 10, 6)
            // Short stem then cable curving down to the split.
            val stemTop = floatArrayOf(x, 0.38f, 0.03f)
            val stemBot = floatArrayOf(x + 0.02f * s, 0.12f, 0.03f)
            mb.tube(listOf(stemTop, stemBot), { k -> if (k == 0) 0.07f else 0.05f }, 0, 10)
            val cable = (0..10).map { k ->
                val f = k / 10f
                val bend = sin(f * PI).toFloat() * 0.12f * s
                floatArrayOf(
                    stemBot[0] + (split[0] - stemBot[0]) * f + bend,
                    stemBot[1] + (split[1] - stemBot[1]) * f,
                    0.03f + 0.04f * f
                )
            }
            mb.tube(cable, { 0.022f }, 0, 6)
        }
        // Y-splitter, cable down to the plug.
        mb.sphere(split[0], split[1], split[2], 0.045f, 0.06f, 0.045f, 0, 10, 6)
        val main = (0..8).map { k ->
            val f = k / 8f
            floatArrayOf(sin(f * PI).toFloat() * 0.08f, split[1] - 0.42f * f, split[2])
        }
        mb.tube(main, { 0.026f }, 0, 6)
        mb.cylinder(floatArrayOf(0f, -0.8f, split[2]), floatArrayOf(0f, -0.98f, split[2]), 0.055f, 0, 12)
        mb.cylinder(floatArrayOf(0f, -0.98f, split[2]), floatArrayOf(0f, -1.12f, split[2]), 0.028f, 2, 10)
    }

    private fun buildSpeaker(mb: MeshBuilder) {
        mb.cylinder(floatArrayOf(-0.7f, 0f, 0f), floatArrayOf(0.7f, 0f, 0f), 0.36f, 0, 28)
        mb.cylinder(floatArrayOf(-0.76f, 0f, 0f), floatArrayOf(-0.68f, 0f, 0f), 0.37f, 1, 28)
        mb.cylinder(floatArrayOf(0.68f, 0f, 0f), floatArrayOf(0.76f, 0f, 0f), 0.37f, 1, 28)
        mb.sphere(0f, 0f, 0.36f, 0.22f, 0.06f, 0.02f, 2, 12, 6)
    }

    // ------------------------------------------------------------------ rendering

    private val tx = FloatArray(vx.size); private val ty = FloatArray(vx.size); private val tz = FloatArray(vx.size)
    private val sx = FloatArray(vx.size); private val sy = FloatArray(vx.size)
    private val depth = FloatArray(ta.size)
    private val shade = IntArray(ta.size)
    private val visible = BooleanArray(ta.size)
    private val order = Array(ta.size) { it }
    private val path = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL_AND_STROKE
        strokeWidth = 0.9f
        strokeJoin = Paint.Join.ROUND
    }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val light = norm(floatArrayOf(-0.45f, 0.65f, 0.75f))
    private val half = norm(floatArrayOf(light[0], light[1], light[2] + 1f))

    private var yaw = 0f
    private var autoYaw = 0f
    private var spin = FloatValueHolder(0f)
    private var velocity = 0f
    private var dragging = false
    private var lastX = 0f
    private var lastT = 0L
    private var lastFrame = 0L
    private val startTime = System.nanoTime()
    private val spinAnim = SpringAnimation(spin).apply {
        spring = SpringForce(0f).apply { stiffness = 40f; dampingRatio = 0.85f }
    }

    /** iOS-style entrance: the model whirls in and slows to a gentle turn. */
    fun playEntrance() {
        if (Motion.scale <= 0f) return
        spin.value = 540f
        spinAnim.cancel()
        spinAnim.setStartValue(540f)
        spinAnim.animateToFinalPosition(0f)
    }

    override fun onDetachedFromWindow() {
        spinAnim.cancel()
        super.onDetachedFromWindow()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                dragging = true; velocity = 0f; lastX = e.x; lastT = e.eventTime
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.x - lastX
                val dt = max(1L, e.eventTime - lastT)
                val deg = dx / width.coerceAtLeast(1) * 300f
                autoYaw += deg
                velocity = deg / dt * 1000f
                lastX = e.x; lastT = e.eventTime
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
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

        // Gentle idle turn, plus any fling from the finger that slowly decays.
        if (!dragging) {
            val idle = if (Motion.scale > 0f) 28f else 0f
            autoYaw += (idle + velocity) * dt
            velocity *= (1f - min(1f, dt * 2.5f))
        }
        yaw = autoYaw + spin.value
        val pitch = 14f + 5f * sin(t * 1.2f)

        // Soft floor shadow.
        val scale = min(w, h) * 0.42f
        val cx = w / 2f; val cy = h * 0.47f
        shadowPaint.shader = RadialGradient(cx, cy + scale * 1.05f, scale * 0.9f,
            0x55000000, 0x00000000, Shader.TileMode.CLAMP)
        canvas.save()
        canvas.scale(1f, 0.22f, cx, cy + scale * 1.05f)
        canvas.drawCircle(cx, cy + scale * 1.05f, scale * 0.9f, shadowPaint)
        canvas.restore()

        val ry = Math.toRadians(yaw.toDouble()); val rp = Math.toRadians(pitch.toDouble())
        val cyw = cos(ry).toFloat(); val syw = sin(ry).toFloat()
        val cp = cos(rp).toFloat(); val sp = sin(rp).toFloat()
        val cam = 4.2f
        for (i in vx.indices) {
            val x1 = vx[i] * cyw + vz[i] * syw
            val z1 = -vx[i] * syw + vz[i] * cyw
            val y2 = vy[i] * cp - z1 * sp
            val z2 = vy[i] * sp + z1 * cp
            tx[i] = x1; ty[i] = y2; tz[i] = z2
            val f = cam / (cam - z2)
            sx[i] = cx + x1 * scale * f
            sy[i] = cy - y2 * scale * f
        }

        for (k in ta.indices) {
            val a = ta[k]; val b = tb[k]; val c = tc[k]
            val ux = tx[b] - tx[a]; val uy = ty[b] - ty[a]; val uz = tz[b] - tz[a]
            val wx = tx[c] - tx[a]; val wy = ty[c] - ty[a]; val wz = tz[c] - tz[a]
            var nx = uy * wz - uz * wy; var ny = uz * wx - ux * wz; var nz = ux * wy - uy * wx
            val len = sqrt(nx * nx + ny * ny + nz * nz)
            if (len < 1e-7f) { visible[k] = false; continue }
            nx /= len; ny /= len; nz /= len
            if (nz < 0f) { nx = -nx; ny = -ny; nz = -nz }   // two-sided lighting
            visible[k] = true
            depth[k] = (tz[a] + tz[b] + tz[c]) / 3f
            val m = mats[tm[k]]
            val diff = max(0f, nx * light[0] + ny * light[1] + nz * light[2])
            val spec = max(0f, nx * half[0] + ny * half[1] + nz * half[2]).pow(m.shine) * m.spec
            val rim = (1f - nz).pow(3f) * 0.18f
            val l = 0.32f + 0.68f * diff
            shade[k] = Color.rgb(
                clamp(m.r * l + 255f * (spec + rim)),
                clamp(m.g * l + 255f * (spec + rim)),
                clamp(m.b * l + 255f * (spec + rim))
            )
        }
        order.sortWith(compareBy { depth[it] })
        for (k in order) {
            if (!visible[k]) continue
            path.rewind()
            path.moveTo(sx[ta[k]], sy[ta[k]])
            path.lineTo(sx[tb[k]], sy[tb[k]])
            path.lineTo(sx[tc[k]], sy[tc[k]])
            path.close()
            paint.color = shade[k]
            canvas.drawPath(path, paint)
        }

        if (isAttachedToWindow) postInvalidateOnAnimation()
    }

    private fun clamp(v: Float): Int = v.toInt().coerceIn(0, 255)

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
