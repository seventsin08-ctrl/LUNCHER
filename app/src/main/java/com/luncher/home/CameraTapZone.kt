package com.luncher.home

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import kotlin.math.roundToInt

/**
 * The place you tap to open the island tools: a strip in the status bar from the front camera
 * towards the left, with a faint ring around the camera and a faint line so you can see it.
 *
 * It's its own tiny window ABOVE the status bar (via the Accessibility Helper), because
 * normally the status bar takes every tap at the very top of the screen.
 */
class CameraTapZone(private val act: Activity, private val settings: Settings, private val onTap: () -> Unit) {
    private fun dp(v: Int) = (v * act.resources.displayMetrics.density).roundToInt()
    private var wm: WindowManager? = null
    private var view: ZoneView? = null
    private var lp: WindowManager.LayoutParams? = null
    var camera: Rect? = null
        set(v) { field = v; place() }
    private var hidden = false

    /** Hide while searching or gaming, show again after. */
    fun setHidden(h: Boolean) {
        hidden = h
        view?.animate()?.alpha(if (h) 0f else 1f)?.setDuration(180)?.start()
        lp?.let { l ->
            l.flags = if (h) l.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                else l.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            runCatching { wm?.updateViewLayout(view, l) }
        }
    }

    fun attach() {
        if (view != null) { place(); return }
        if (!settings.islandTools) return
        val svc = LuncherAccessibility.instance ?: return   // only this kind of window sits above the status bar
        val w = svc.getSystemService(WindowManager::class.java) ?: return
        val v = ZoneView()
        val l = WindowManager.LayoutParams(dp(10), dp(10), WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        if (runCatching { w.addView(v, l) }.isFailure) return
        wm = w; view = v; lp = l
        place()
        if (hidden) setHidden(true)
    }

    fun detach() {
        view?.let { v -> runCatching { wm?.removeViewImmediate(v) } }
        view = null; lp = null
    }

    /** Size and place the strip: from a bit left of the camera to just past it, the height of the status bar. */
    private fun place() {
        val v = view ?: return
        val l = lp ?: return
        val cam = camera
        val portrait = act.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
        if (cam == null || !portrait || !settings.islandTools) { v.visibility = View.GONE; return }
        v.visibility = View.VISIBLE
        val left = cam.left - dp(settings.tapZoneLen) + dp(settings.hotspotDx)
        val right = cam.right + dp(10) + dp(settings.hotspotDx)
        val top = maxOf(0, cam.top - dp(4) + dp(settings.hotspotDy))
        val bottom = cam.bottom + dp(10) + dp(settings.hotspotDy)
        l.x = left; l.y = top; l.width = right - left; l.height = maxOf(dp(24), bottom - top)
        v.camInView = RectF((cam.left - left).toFloat(), (cam.top - top).toFloat(), (cam.right - left).toFloat(), (cam.bottom - top).toFloat())
        v.invalidate()
        runCatching { wm?.updateViewLayout(v, l) }
    }

    /** Faint ring around the camera + faint line to its left; tapping anywhere in it opens the tools. */
    private inner class ZoneView : View(act) {
        var camInView = RectF()
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(2).toFloat() }
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; strokeWidth = dp(4).toFloat() }
        private var glow = 0f

        init {
            setOnTouchListener { _, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { glow = 1f; invalidate() }
                    MotionEvent.ACTION_UP -> {
                        performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                        animate().setDuration(1).withEndAction { glow = 0f; invalidate() }.start()
                        onTap()
                    }
                    MotionEvent.ACTION_CANCEL -> { glow = 0f; invalidate() }
                }
                true
            }
        }

        override fun onDraw(c: Canvas) {
            if (!settings.tapZoneHint && glow == 0f) return
            val a = if (glow > 0f) 1f else 0.55f
            // Faint ring hugging the camera.
            ring.color = 0xFFFFFFFF.toInt(); ring.alpha = (70 * a).toInt()
            val cx = camInView.centerX(); val cy = camInView.centerY()
            val r = maxOf(camInView.width(), camInView.height()) / 2f + dp(5)
            c.drawCircle(cx, cy, r, ring)
            // Faint line from the camera towards the left: "tap here".
            line.color = 0xFFFFFFFF.toInt(); line.alpha = (55 * a).toInt()
            c.drawLine(dp(12).toFloat(), cy, cx - r - dp(8), cy, line)
        }
    }
}
