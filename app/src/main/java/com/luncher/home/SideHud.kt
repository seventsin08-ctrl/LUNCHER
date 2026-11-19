package com.luncher.home

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.media.AudioManager
import android.text.TextPaint
import android.text.TextUtils
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import kotlin.math.roundToInt

/**
 * The "side island": a black liquid shape on the left edge of the screen.
 * - Always rests as a small bump on the edge (tap it for a volume slider you can drag).
 * - Grows out for volume, Silent / Ring, Focus — and new messages (icon + sender + text;
 *   tap to open, swipe left to dismiss).
 * Its window is only as big as what it shows, so it never blocks the app underneath.
 */
class SideHud(private val act: Activity, private val settings: Settings) {
    private fun dp(v: Int) = (v * act.resources.displayMetrics.density).roundToInt()

    private enum class Kind { REST, VOLUME, STATUS, MESSAGE }

    private var wm: WindowManager? = null
    private var lp: WindowManager.LayoutParams? = null
    private var view: HudView? = null
    private val backToRest = Runnable { retract() }
    private var onOpen: (() -> Unit)? = null
    /** Set by the launcher: play / pause the current music (for "Tap the Sliver For → Play / Pause"). */
    var onPlayPause: (() -> Unit)? = null

    private val audio get() = act.getSystemService(AudioManager::class.java)

    /** Show the resting bump (or remove everything if the side island is off). */
    fun rest() {
        if (!settings.sideHud || !settings.sideRest) { dismiss(); return }
        lp?.let { l ->
            val y = (act.resources.displayMetrics.heightPixels * settings.sideY / 100f).toInt() - dp(125)
            if (l.y != y) { l.y = y; view?.let { v -> runCatching { wm?.updateViewLayout(v, l) } } }
        }
        val v = view ?: create() ?: return
        if (v.kind != Kind.REST) return
        v.kind = Kind.REST
        size(Kind.REST)
        v.growTo(1f)
    }

    fun volume(level: Float) = show(Kind.VOLUME) { it.level = level }

    fun status(icons: List<Pair<Int, Int>>, label: String) = show(Kind.STATUS) {
        it.label = label
        it.icons = icons.map { (res, tint) -> act.getDrawable(res)!!.mutate().apply { setTint(tint) } }
    }

    /** A new message: app icon in the shape, sender and text beside it. */
    fun message(icon: Bitmap?, title: String, text: String, open: () -> Unit) = show(Kind.MESSAGE) {
        it.appIcon = icon; it.label = title; it.body = text
        onOpen = open
    }

    private fun show(kind: Kind, fill: (HudView) -> Unit) {
        val v = view ?: create() ?: return
        val wasRest = v.kind == Kind.REST
        v.kind = kind
        fill(v)
        size(kind)
        if (wasRest) v.grown = 0f
        v.invalidate()
        v.growTo(1f)
        v.removeCallbacks(backToRest)
        val ms = settings.sideMs.toLong()
        v.postDelayed(backToRest, if (kind == Kind.MESSAGE) ms + 3200L else ms)
        if (kind == Kind.MESSAGE) v.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
    }

    /** Window size for each state: tiny strip at rest, wider only while something is showing. */
    private fun size(kind: Kind) {
        val l = lp ?: return
        val v = view ?: return
        l.width = when (kind) { Kind.REST -> dp(20); Kind.VOLUME -> dp(96); Kind.STATUS -> dp(220); Kind.MESSAGE -> dp(320) }
        l.flags = if (kind == Kind.STATUS) l.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            else l.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        runCatching { wm?.updateViewLayout(v, l) }
    }

    private fun create(): HudView? {
        val svc = LuncherAccessibility.instance
        val type = when {
            svc != null -> WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            android.provider.Settings.canDrawOverlays(act) -> WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else -> WindowManager.LayoutParams.TYPE_APPLICATION
        }
        if (type == WindowManager.LayoutParams.TYPE_APPLICATION && act.window?.decorView?.windowToken == null) return null
        val ctx = if (type == WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY) svc!! else act
        val w = ctx.getSystemService(WindowManager::class.java) ?: return null
        val v = HudView(act)
        val l = WindowManager.LayoutParams(dp(20), dp(250), type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.START or Gravity.TOP
            y = (act.resources.displayMetrics.heightPixels * settings.sideY / 100f).toInt() - dp(125)
            if (type == WindowManager.LayoutParams.TYPE_APPLICATION) token = act.window.decorView.windowToken
            if (android.os.Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        if (runCatching { w.addView(v, l) }.isFailure) return null
        wm = w; lp = l; view = v
        return v
    }

    /** Shrink back to the resting bump (or away if resting is off). */
    private fun retract() {
        val v = view ?: return
        onOpen = null
        v.growTo(0f) {
            if (view !== v) return@growTo
            if (settings.sideHud && settings.sideRest) { v.kind = Kind.REST; size(Kind.REST); v.growTo(1f) }
            else dismiss()
        }
    }

    fun dismiss() {
        val v = view ?: return
        view = null; lp = null
        runCatching { wm?.removeViewImmediate(v) }
    }

    private inner class HudView(ctx: Activity) : View(ctx) {
        var kind = Kind.REST
        var level = 0f
        var label: String? = null
        var body: String? = null
        var appIcon: Bitmap? = null
        var icons: List<Drawable> = emptyList()
        var grown = 0f
        private var spring: SpringAnimation? = null
        private val black = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
        private val restPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var restShaderTop = -1f
        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF3A3A3C.toInt() }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF2F2F7.toInt() }
        private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = dp(16).toFloat(); typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setShadowLayer(dp(4).toFloat(), 0f, dp(1).toFloat(), 0xAA000000.toInt())
        }
        private val small = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xEEFFFFFF.toInt(); textSize = dp(13).toFloat()
            setShadowLayer(dp(4).toFloat(), 0f, dp(1).toFloat(), 0xAA000000.toInt())
        }
        private val path = Path()
        private val r = RectF()
        private val iconClip = Path()
        private val speaker = act.getDrawable(R.drawable.ic_speaker)!!.mutate()

        fun growTo(target: Float, end: (() -> Unit)? = null) {
            spring?.cancel()
            val holder = FloatValueHolder(grown)
            spring = SpringAnimation(holder).apply {
                spring = SpringForce(target).apply {
                    stiffness = if (target > 0f) 380f else 900f
                    dampingRatio = if (target > 0f) 0.66f else 1f
                }
                setMinimumVisibleChange(0.002f)
                addUpdateListener { _, v, _ -> grown = v.coerceAtLeast(0f); invalidate() }
                if (end != null) addEndListener { _, canceled, _, _ -> if (!canceled) end() }
                start()
            }
        }

        private fun shape(bodyW: Float, top: Float, bottom: Float, fillet: Float = dp(26).toFloat()) {
            // The curves where it leaves the edge must fit inside the window, or they get cut off.
            val f = minOf(fillet, width * 0.45f) * grown.coerceAtMost(1f)
            val rad = minOf(dp(20).toFloat(), bodyW / 2f)
            path.reset()
            path.moveTo(0f, top - f)
            path.cubicTo(0f, top - f * 0.35f, f * 0.25f, top, f * 0.9f, top)
            path.lineTo(bodyW - rad, top)
            path.quadTo(bodyW, top, bodyW, top + rad)
            path.lineTo(bodyW, bottom - rad)
            path.quadTo(bodyW, bottom, bodyW - rad, bottom)
            path.lineTo(f * 0.9f, bottom)
            path.cubicTo(f * 0.25f, bottom, 0f, bottom + f * 0.35f, 0f, bottom + f)
            path.close()
        }

        override fun onDraw(c: Canvas) {
            if (grown <= 0.001f) return
            val a = ((grown - 0.4f) / 0.6f).coerceIn(0f, 1f)
            when (kind) {
                Kind.REST -> {
                    // Resting: a soft black sliver hugging the edge like the phone's bezel,
                    // fading out at both ends — no corners, no outline.
                    val h = dp(120).toFloat()
                    val top = (height - h) / 2f
                    val w = dp(4) * grown
                    if (restShaderTop != top) {
                        restShaderTop = top
                        restPaint.shader = android.graphics.LinearGradient(0f, top, 0f, top + h,
                            intArrayOf(0x00000000, 0xFF000000.toInt(), 0xFF000000.toInt(), 0x00000000),
                            floatArrayOf(0f, 0.3f, 0.7f, 1f), android.graphics.Shader.TileMode.CLAMP)
                    }
                    restPaint.alpha = (230 * grown).toInt().coerceIn(0, 230)
                    r.set(-w, top, w, top + h)
                    c.drawRoundRect(r, w, w * 6f, restPaint)
                }
                Kind.VOLUME -> {
                    val bodyW = dp(58) * grown
                    val h = dp(150).toFloat(); val top = (height - h) / 2f; val bottom = top + h
                    shape(bodyW, top, bottom); c.drawPath(path, black)
                    if (a <= 0f) return
                    val inset = dp(7).toFloat()
                    r.set(inset, top + inset, bodyW - inset, bottom - inset)
                    val cr = dp(14).toFloat()
                    track.alpha = (255 * a).toInt(); c.drawRoundRect(r, cr, cr, track)
                    c.save(); c.clipRect(r.left, r.bottom - r.height() * level, r.right, r.bottom)
                    fill.alpha = (255 * a).toInt(); c.drawRoundRect(r, cr, cr, fill); c.restore()
                    val s = dp(22)
                    speaker.setBounds((bodyW / 2 - s / 2).toInt(), (r.bottom - dp(10) - s).toInt(), (bodyW / 2 + s / 2).toInt(), (r.bottom - dp(10)).toInt())
                    speaker.setTint(if (level > 0.2f) 0xFF6E6E73.toInt() else Color.WHITE)
                    speaker.alpha = (255 * a).toInt(); speaker.draw(c)
                }
                Kind.STATUS -> {
                    val bodyW = dp(46) * grown
                    val h = dp(44) + dp(40) * (icons.size - 1).coerceAtLeast(0).toFloat()
                    val top = (height - h) / 2f; val bottom = top + h
                    shape(bodyW, top, bottom); c.drawPath(path, black)
                    if (a <= 0f) return
                    val s = dp(24); var y = top + dp(10)
                    for (d in icons) {
                        d.setBounds((bodyW / 2 - s / 2).toInt(), y.toInt(), (bodyW / 2 + s / 2).toInt(), (y + s).toInt())
                        d.alpha = (255 * a).toInt(); d.draw(c); y += dp(40)
                    }
                    label?.let { text.alpha = (255 * a).toInt(); c.drawText(it, bodyW + dp(14), (top + bottom) / 2f + dp(6), text) }
                }
                Kind.MESSAGE -> {
                    val bodyW = dp(54) * grown
                    val h = dp(62).toFloat(); val top = (height - h) / 2f; val bottom = top + h
                    shape(bodyW, top, bottom); c.drawPath(path, black)
                    if (a <= 0f) return
                    val s = dp(36).toFloat()
                    val ix = (bodyW - s) / 2f; val iy = (top + bottom - s) / 2f
                    appIcon?.let { bmp ->
                        c.save()
                        iconClip.reset(); iconClip.addRoundRect(ix, iy, ix + s, iy + s, s * 0.24f, s * 0.24f, Path.Direction.CW)
                        c.clipPath(iconClip)
                        val p = Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = (255 * a).toInt() }
                        r.set(ix, iy, ix + s, iy + s); c.drawBitmap(bmp, null, r, p)
                        c.restore()
                    }
                    val tx = bodyW + dp(12); val maxW = (width - tx - dp(8)).coerceAtLeast(dp(40).toFloat())
                    text.alpha = (255 * a).toInt(); small.alpha = (240 * a).toInt()
                    val t = TextUtils.ellipsize(label ?: "", text, maxW, TextUtils.TruncateAt.END).toString()
                    val b = TextUtils.ellipsize(body ?: "", small, maxW, TextUtils.TruncateAt.END).toString()
                    c.drawText(t, tx, (top + bottom) / 2f - dp(3), text)
                    c.drawText(b, tx, (top + bottom) / 2f + dp(15), small)
                }
            }
        }

        // ---- touch: tap the bump for volume, drag to change it; tap a message to open, swipe left to dismiss
        private var downX = 0f
        private var downY = 0f
        private var startLevel = 0f
        private var dragging = false

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; dragging = false; startLevel = level
                    removeCallbacks(backToRest)
                }
                MotionEvent.ACTION_MOVE -> if (kind == Kind.VOLUME) {
                    val dy = downY - e.rawY
                    if (kotlin.math.abs(dy) > dp(6)) dragging = true
                    if (dragging) {
                        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                        val lv = (startLevel + dy / dp(150)).coerceIn(0f, 1f)
                        val steps = (lv * max).roundToInt()
                        if (steps != audio.getStreamVolume(AudioManager.STREAM_MUSIC)) {
                            runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, steps, 0) }
                            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        }
                        level = lv; invalidate()
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val dx = e.rawX - downX
                    when {
                        kind == Kind.MESSAGE && dx < -dp(30) -> retract()
                        kind == Kind.MESSAGE && kotlin.math.abs(dx) < dp(10) -> { val o = onOpen; retract(); o?.invoke() }
                        kind == Kind.REST -> {
                            performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                            when (settings.sideTap) {
                                "volume" -> {
                                    val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                                    volume(audio.getStreamVolume(AudioManager.STREAM_MUSIC) / max.toFloat())
                                }
                                "music" -> onPlayPause?.invoke()
                            }
                            return true
                        }
                        else -> {}
                    }
                    postDelayed(backToRest, 1800)
                }
            }
            return true
        }
    }
}
