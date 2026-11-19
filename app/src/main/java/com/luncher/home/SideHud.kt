package com.luncher.home

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import kotlin.math.roundToInt

/**
 * The concept "side island": a black shape that grows out of the left edge of the screen
 * (with soft, liquid curves where it meets the edge) for volume, Silent / Ring and Focus.
 * It never takes touches, so it can't get in the way.
 */
class SideHud(private val act: Activity) {
    private fun dp(v: Int) = (v * act.resources.displayMetrics.density).roundToInt()

    private var wm: WindowManager? = null
    private var view: HudView? = null
    private val hideRun = Runnable { retract() }

    /** Volume 0..1 as a slider inside the shape. */
    fun volume(level: Float) = show(Kind.VOLUME, level, null, emptyList())

    /** An icon (or a column of icons) with a word beside it, e.g. SILENT or WORK. */
    fun status(icons: List<Pair<Int, Int>>, label: String) = show(Kind.STATUS, 0f, label, icons)

    private enum class Kind { VOLUME, STATUS }

    private fun show(kind: Kind, level: Float, label: String?, icons: List<Pair<Int, Int>>) {
        val v = view ?: create() ?: return
        v.kind = kind; v.level = level; v.label = label
        v.icons = icons.map { (res, tint) -> act.getDrawable(res)!!.mutate().apply { setTint(tint) } }
        v.invalidate()
        v.grow()
        v.removeCallbacks(hideRun)
        v.postDelayed(hideRun, if (kind == Kind.VOLUME) 1400 else 1800)
    }

    private fun create(): HudView? {
        val svc = LuncherAccessibility.instance
        val type = when {
            svc != null -> WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            android.provider.Settings.canDrawOverlays(act) -> WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else -> WindowManager.LayoutParams.TYPE_APPLICATION
        }
        val ctx = if (type == WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY) svc!! else act
        val w = ctx.getSystemService(WindowManager::class.java) ?: return null
        val v = HudView(act)
        val lp = WindowManager.LayoutParams(dp(220), dp(240), type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.START or Gravity.TOP
            y = (act.resources.displayMetrics.heightPixels * 0.22f).toInt()
            if (type == WindowManager.LayoutParams.TYPE_APPLICATION) token = act.window.decorView.windowToken
        }
        if (runCatching { w.addView(v, lp) }.isFailure) return null
        wm = w; view = v
        return v
    }

    private fun retract() {
        val v = view ?: return
        v.shrink {
            runCatching { wm?.removeViewImmediate(v) }
            if (view === v) view = null
        }
    }

    fun dismiss() {
        val v = view ?: return
        view = null
        runCatching { wm?.removeViewImmediate(v) }
    }

    private inner class HudView(ctx: Activity) : View(ctx) {
        var kind = Kind.VOLUME
        var level = 0f
        var label: String? = null
        var icons: List<Drawable> = emptyList()
        private var grown = 0f
        private var spring: SpringAnimation? = null
        private val black = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF3A3A3C.toInt() }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF2F2F7.toInt() }
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = dp(16).toFloat(); typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setShadowLayer(dp(4).toFloat(), 0f, dp(1).toFloat(), 0x99000000.toInt())
        }
        private val path = Path()
        private val r = RectF()
        private val speaker = act.getDrawable(R.drawable.ic_speaker)!!.mutate()

        private fun animateTo(target: Float, end: (() -> Unit)? = null) {
            spring?.cancel()
            val holder = FloatValueHolder(grown)
            spring = SpringAnimation(holder).apply {
                spring = SpringForce(target).apply {
                    stiffness = if (target > 0f) 380f else 900f
                    dampingRatio = if (target > 0f) 0.68f else 1f
                }
                setMinimumVisibleChange(0.002f)
                addUpdateListener { _, v, _ -> grown = v.coerceAtLeast(0f); invalidate() }
                if (end != null) addEndListener { _, _, _, _ -> end() }
                start()
            }
        }

        fun grow() = animateTo(1f)
        fun shrink(end: () -> Unit) = animateTo(0f, end)

        override fun onDraw(c: Canvas) {
            if (grown <= 0.001f) return
            val bodyW = (if (kind == Kind.VOLUME) dp(58) else dp(46)) * grown
            val h = if (kind == Kind.VOLUME) dp(150).toFloat() else dp(44) + dp(40) * (icons.size - 1).coerceAtLeast(0).toFloat()
            val top = (height - h) / 2f
            val bottom = top + h
            val f = dp(26) * grown.coerceAtMost(1f)
            val rad = minOf(dp(20).toFloat(), bodyW / 2f)
            // Liquid shape: soft fillets where it leaves the screen edge, round outer corners.
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
            c.drawPath(path, black)
            val a = ((grown - 0.4f) / 0.6f).coerceIn(0f, 1f)
            if (a <= 0f) return
            if (kind == Kind.VOLUME) {
                val inset = dp(7).toFloat()
                r.set(inset, top + inset, bodyW - inset, bottom - inset)
                val cr = dp(14).toFloat()
                track.alpha = (255 * a).toInt()
                c.drawRoundRect(r, cr, cr, track)
                c.save()
                c.clipRect(r.left, r.bottom - r.height() * level, r.right, r.bottom)
                fill.alpha = (255 * a).toInt()
                c.drawRoundRect(r, cr, cr, fill)
                c.restore()
                val s = dp(22)
                speaker.setBounds((bodyW / 2 - s / 2).toInt(), (r.bottom - dp(10) - s).toInt(), (bodyW / 2 + s / 2).toInt(), (r.bottom - dp(10)).toInt())
                speaker.setTint(if (level > 0.2f) 0xFF6E6E73.toInt() else Color.WHITE)
                speaker.alpha = (255 * a).toInt()
                speaker.draw(c)
            } else {
                val s = dp(24)
                var y = top + dp(10)
                for (d in icons) {
                    d.setBounds((bodyW / 2 - s / 2).toInt(), y.toInt(), (bodyW / 2 + s / 2).toInt(), (y + s).toInt())
                    d.alpha = (255 * a).toInt()
                    d.draw(c)
                    y += dp(40)
                }
                label?.let {
                    text.alpha = (255 * a).toInt()
                    c.drawText(it, bodyW + dp(14), (top + bottom) / 2f + dp(6), text)
                }
            }
        }
    }
}
