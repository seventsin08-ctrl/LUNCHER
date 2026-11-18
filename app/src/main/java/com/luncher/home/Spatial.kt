package com.luncher.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Camera
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * Shared, smoothed phone tilt (-1..1 on each axis) for the spatial / 3D effects.
 * The resting angle slowly becomes "neutral", so holding the phone at any angle works.
 */
object Tilt : SensorEventListener {
    var x = 0f
        private set
    var y = 0f
        private set

    private var manager: SensorManager? = null
    private var registered = false
    private var paused = false
    private var baseX = Float.NaN
    private var baseY = Float.NaN
    private val listeners = LinkedHashSet<() -> Unit>()

    fun add(ctx: Context, l: () -> Unit) {
        listeners += l
        if (manager == null) manager = ctx.applicationContext.getSystemService(SensorManager::class.java)
        register()
    }

    fun remove(l: () -> Unit) {
        listeners -= l
        if (listeners.isEmpty()) unregister()
    }

    fun pause() { paused = true; unregister() }
    fun resume() { paused = false; if (listeners.isNotEmpty()) register() }

    private fun register() {
        if (registered || paused) return
        val m = manager ?: return
        val sensor = m.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: m.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        registered = m.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
    }

    private fun unregister() {
        if (!registered) return
        manager?.unregisterListener(this)
        registered = false
    }

    override fun onSensorChanged(e: SensorEvent) {
        val rx = -e.values[0] / SensorManager.GRAVITY_EARTH
        val ry = e.values[1] / SensorManager.GRAVITY_EARTH
        if (baseX.isNaN()) { baseX = rx; baseY = ry }
        baseX += (rx - baseX) * 0.008f
        baseY += (ry - baseY) * 0.008f
        val tx = ((rx - baseX) * 3.2f).coerceIn(-1f, 1f)
        val ty = ((ry - baseY) * 3.2f).coerceIn(-1f, 1f)
        x += (tx - x) * 0.18f
        y += (ty - y) * 0.18f
        for (l in listeners.toList()) l()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}

/**
 * Draws a photo with a "spatial scene" effect: it tilts in 3D, shifts with parallax and
 * catches a moving glare as you move the phone. Optional "extend" fills empty space
 * around a photo with a soft blurred copy instead of cropping it.
 */
class SpatialImageView(ctx: Context) : View(ctx) {
    var bitmap: Bitmap? = null
        set(v) { field = v; blurred = null; invalidate() }
    var filter: ColorFilter? = null
        set(v) { field = v; paint.colorFilter = v; blurPaint.colorFilter = v; invalidate() }
    var spatial = true
        set(v) { field = v; updateListening(); invalidate() }
    /** 0..1.5 strength of the 3D effect. */
    var depth = 1f
    var extend = false
        set(v) { field = v; invalidate() }
    /** Extra horizontal shift (e.g. home page scrolling), in pixels. */
    var shiftX = 0f
        set(v) { field = v; invalidate() }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val blurPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val glare = Paint(Paint.ANTI_ALIAS_FLAG)
    private val camera = Camera()
    private val m = Matrix()
    private var blurred: Bitmap? = null
    private val listener: () -> Unit = { invalidate() }
    private var listening = false

    private fun updateListening() {
        val want = spatial && isAttachedToWindow
        if (want && !listening) { Tilt.add(context, listener); listening = true }
        if (!want && listening) { Tilt.remove(listener); listening = false }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); updateListening() }
    override fun onDetachedFromWindow() {
        if (listening) { Tilt.remove(listener); listening = false }
        super.onDetachedFromWindow()
    }

    private fun blurredCopy(b: Bitmap): Bitmap {
        blurred?.let { return it }
        // Cheap, smooth blur: shrink a lot, then let bitmap filtering scale it back up.
        val w = max(1, b.width / 24); val h = max(1, b.height / 24)
        val small = Bitmap.createScaledBitmap(b, w, h, true)
        val mid = Bitmap.createScaledBitmap(small, max(1, w * 3), max(1, h * 3), true)
        blurred = mid
        return mid
    }

    override fun onDraw(canvas: Canvas) {
        val b = bitmap ?: return
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val tx = if (spatial) Tilt.x * depth else 0f
        val ty = if (spatial) Tilt.y * depth else 0f

        canvas.save()
        if (spatial) {
            // Real perspective tilt of the photo plane.
            camera.save()
            camera.rotateY(tx * 7f)
            camera.rotateX(-ty * 7f)
            camera.getMatrix(m)
            camera.restore()
            m.preTranslate(-w / 2f, -h / 2f)
            m.postTranslate(w / 2f, h / 2f)
            canvas.concat(m)
        }
        val zoom = if (spatial) 1.14f else 1f
        val px = -tx * w * 0.045f + shiftX
        val py = -ty * h * 0.045f

        if (extend) {
            val bl = blurredCopy(b)
            drawCover(canvas, bl, w, h, zoom * 1.05f, px * 0.5f, py * 0.5f, blurPaint)
            val s = min(w / b.width, h / b.height) * zoom
            val dw = b.width * s; val dh = b.height * s
            val left = (w - dw) / 2f + px; val top = (h - dh) / 2f + py
            canvas.save()
            canvas.translate(left, top)
            canvas.scale(s, s)
            canvas.drawBitmap(b, 0f, 0f, paint)
            canvas.restore()
        } else {
            drawCover(canvas, b, w, h, zoom, px, py, paint)
        }

        if (spatial) {
            // A soft sheen that slides across the glass as you tilt.
            val gx = w * (0.5f + tx * 0.7f)
            glare.shader = LinearGradient(
                gx - w * 0.6f, 0f, gx + w * 0.6f, h,
                intArrayOf(0x00FFFFFF, 0x26FFFFFF, 0x00FFFFFF), floatArrayOf(0.2f, 0.5f, 0.8f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, w, h, glare)
        }
        canvas.restore()
    }

    private fun drawCover(c: Canvas, b: Bitmap, w: Float, h: Float, zoom: Float, px: Float, py: Float, p: Paint) {
        val s = max(w / b.width, h / b.height) * zoom
        val dw = b.width * s; val dh = b.height * s
        c.save()
        c.translate((w - dw) / 2f + px, (h - dh) / 2f + py)
        c.scale(s, s)
        c.drawBitmap(b, 0f, 0f, p)
        c.restore()
    }
}
