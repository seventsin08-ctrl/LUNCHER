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
        // The ring sits around the camera, moved by your nudge; the window always contains the
        // WHOLE ring (it used to cut it in half) plus the tap strip to its left.
        val dx = dp(settings.hotspotDx); val dy = dp(settings.hotspotDy)
        val ringR = maxOf(cam.width(), cam.height()) / 2 + dp(5) + dp(16)   // room for the ripple too
        val cx = cam.centerX() + dx; val cy = cam.centerY() + dy
        val left = minOf(cam.left - dp(settings.tapZoneLen) + dx, cx - ringR)
        val right = cx + ringR
        val top = maxOf(0, cy - ringR)
        val bottom = cy + ringR
        l.x = left; l.y = top; l.width = right - left; l.height = maxOf(dp(24), bottom - top)
        v.camInView = RectF((cam.left + dx - left).toFloat(), (cam.top + dy - top).toFloat(), (cam.right + dx - left).toFloat(), (cam.bottom + dy - top).toFloat())
        v.invalidate()
        runCatching { wm?.updateViewLayout(v, l) }
    }

    /** Faint ring around the camera + faint line to its left; tapping anywhere in it opens the tools. */
    private inner class ZoneView : View(act) {
        var camInView = RectF()
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(2).toFloat() }
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; strokeWidth = dp(4).toFloat() }
        private var glow = 0f
        private var ripple = -1f
        private var press = 0f
        private var moving = false
        private var downX = 0f; private var downY = 0f
        private var startDx = 0; private var startDy = 0
        // Hold for half a second, then drag: moves the button (saved as you let go).
        private val lift = Runnable {
            moving = true
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            glow = 1f; invalidate()
        }

        private fun playRipple() {
            android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 420; interpolator = android.view.animation.DecelerateInterpolator(2f)
                addUpdateListener { ripple = it.animatedValue as Float; invalidate() }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(a: android.animation.Animator) { ripple = -1f; glow = 0f; invalidate() }
                })
                start()
            }
        }

        init {
            setOnTouchListener { _, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        glow = 1f; press = 1f; invalidate()
                        downX = e.rawX; downY = e.rawY; startDx = settings.hotspotDx; startDy = settings.hotspotDy
                        moving = false; postDelayed(lift, 480)
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (moving) {
                            val d = act.resources.displayMetrics.density
                            settings.hotspotDx = (startDx + (e.rawX - downX) / d).roundToInt().coerceIn(-150, 150)
                            settings.hotspotDy = (startDy + (e.rawY - downY) / d).roundToInt().coerceIn(-30, 80)
                            place()
                        } else if (kotlin.math.abs(e.rawX - downX) + kotlin.math.abs(e.rawY - downY) > dp(12)) removeCallbacks(lift)
                    }
                    MotionEvent.ACTION_UP -> {
                        removeCallbacks(lift)
                        press = 0f
                        if (moving) { moving = false; glow = 0f; invalidate() }
                        else {
                            performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                            playRipple()
                            onTap()
                        }
                    }
                    MotionEvent.ACTION_CANCEL -> { removeCallbacks(lift); moving = false; press = 0f; glow = 0f; invalidate() }
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
            c.drawCircle(cx, cy, r * (if (press > 0f) 0.92f else 1f), ring)
            // Tap: a soft ripple grows out of the ring.
            if (ripple >= 0f) {
                ring.alpha = (140 * (1f - ripple)).toInt()
                c.drawCircle(cx, cy, r + dp(14) * ripple, ring)
            }
            // Faint line from the camera towards the left: "tap here".
            line.color = 0xFFFFFFFF.toInt(); line.alpha = (55 * a).toInt()
            c.drawLine(dp(12).toFloat(), cy, cx - r - dp(8), cy, line)
        }
    }
}
