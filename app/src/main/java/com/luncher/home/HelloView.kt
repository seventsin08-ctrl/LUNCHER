package com.luncher.home

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The iPhone "hello" welcome: a soft slate screen where "hello" is written in ONE flowing
 * pen stroke (like Apple's), then "Swipe up to begin" fades in. Swipe up or tap to start.
 */
class HelloView(ctx: Context, private val onDone: () -> Unit) : FrameLayout(ctx) {
    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    private val writer = Writer(ctx)
    private val hint = TextView(ctx).apply {
        text = "Swipe up to begin"; setTextColor(Color.WHITE); textSize = 17f; gravity = Gravity.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); alpha = 0f
        letterSpacing = 0.01f
    }
    private val info = TextView(ctx).apply {
        text = "i"; setTextColor(0xCCFFFFFF.toInt()); textSize = 13f; gravity = Gravity.CENTER
        typeface = Typeface.create("serif", Typeface.ITALIC); alpha = 0f
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setStroke(dp(1).coerceAtLeast(1) + 1, 0xCCFFFFFF.toInt()) }
    }
    private var finished = false
    private var downY = 0f

    init {
        isClickable = true
        // Apple's hello background: slate blue-grey, lighter at the top.
        background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0xFF4A5363.toInt(), 0xFF2E3440.toInt(), 0xFF1B1F27.toInt()))
        addView(writer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(hint, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply { bottomMargin = dp(110) })
        addView(info, LayoutParams(dp(22), dp(22), Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(26), dp(110)) })
        post {
            writer.write {
                hint.translationY = dp(10).toFloat()
                hint.animate().alpha(1f).translationY(0f).setDuration(600).setInterpolator(EASE).start()
                info.animate().alpha(1f).setDuration(600).start()
                writer.breathe()
            }
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> downY = e.rawY
            MotionEvent.ACTION_MOVE -> {
                val dy = (e.rawY - downY).coerceAtMost(0f)
                translationY = dy * 0.6f
                alpha = 1f + dy / (height.coerceAtLeast(1) * 1.2f)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                if (downY - e.rawY > dp(60) || abs(downY - e.rawY) < dp(10)) finish()
                else { Motion.spring(this, androidx.dynamicanimation.animation.DynamicAnimation.TRANSLATION_Y, 0f, Motion.FLUID); animate().alpha(1f).setDuration(200).start() }
        }
        return true
    }

    fun finish() {
        if (finished) return
        finished = true
        writer.stop()
        animate().alpha(0f).translationY(-height * 0.35f).setDuration(420).setInterpolator(EASE).withEndAction { onDone() }.start()
    }

    companion object {
        private val EASE = PathInterpolator(0.2f, 0.9f, 0.1f, 1f)

        /** "hello" as one continuous monoline pen stroke, drawn in a 800×340 box. */
        fun helloPath(): Path = Path().apply {
            moveTo(40f, 300f)
            cubicTo(120f, 260f, 190f, 120f, 190f, 70f)          // up into the h
            cubicTo(190f, 30f, 150f, 30f, 145f, 70f)            // h loop
            cubicTo(140f, 140f, 130f, 250f, 125f, 300f)         // h stem down
            cubicTo(140f, 230f, 175f, 200f, 205f, 205f)         // h hump
            cubicTo(235f, 210f, 235f, 250f, 232f, 280f)
            cubicTo(230f, 300f, 245f, 305f, 265f, 295f)
            cubicTo(300f, 280f, 345f, 250f, 345f, 225f)         // e
            cubicTo(345f, 200f, 315f, 195f, 300f, 215f)
            cubicTo(280f, 245f, 290f, 300f, 335f, 302f)
            cubicTo(370f, 304f, 395f, 280f, 415f, 250f)
            cubicTo(450f, 195f, 480f, 110f, 480f, 70f)          // first l
            cubicTo(480f, 35f, 445f, 35f, 442f, 75f)
            cubicTo(438f, 140f, 430f, 260f, 440f, 290f)
            cubicTo(448f, 308f, 480f, 302f, 505f, 270f)
            cubicTo(540f, 210f, 565f, 110f, 565f, 70f)          // second l
            cubicTo(565f, 35f, 530f, 35f, 527f, 75f)
            cubicTo(523f, 140f, 515f, 260f, 525f, 290f)
            cubicTo(533f, 308f, 565f, 302f, 590f, 270f)
            cubicTo(605f, 245f, 625f, 205f, 660f, 205f)         // o
            cubicTo(700f, 205f, 705f, 260f, 680f, 290f)
            cubicTo(660f, 312f, 620f, 305f, 622f, 270f)
            cubicTo(624f, 235f, 655f, 215f, 690f, 222f)
            cubicTo(715f, 227f, 735f, 225f, 760f, 210f)         // tail
        }
    }

    /** Writes the stroke like a pen, with a soft glow under it; then gently breathes. */
    private class Writer(ctx: Context) : View(ctx) {
        private val src = helloPath()
        private val path = Path()
        private val partial = Path()
        private val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; color = Color.WHITE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; color = Color.WHITE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; alpha = 40
        }
        private val halo = Paint(Paint.ANTI_ALIAS_FLAG)
        private var measure: PathMeasure? = null
        private var length = 0f
        private var progress = 0f
        private var glow = 0f
        private var anim: ValueAnimator? = null

        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
            // Fit the 800×340 drawing to 72% of the width, a little above centre.
            val s = w * 0.72f / 720f
            val m = Matrix().apply { setTranslate(-40f, -30f); postScale(s, s); postTranslate((w - 720f * s) / 2f, h * 0.42f - 140f * s) }
            path.reset(); src.transform(m, path)
            measure = PathMeasure(path, false).also { length = it.length }
            pen.strokeWidth = (w * 0.012f).coerceAtLeast(3f)
            glowPaint.strokeWidth = pen.strokeWidth * 4f
            halo.shader = RadialGradient(w / 2f, h * 0.42f, w * 0.55f, intArrayOf(0x22FFFFFF, 0x00FFFFFF), null, Shader.TileMode.CLAMP)
        }

        fun write(end: () -> Unit) {
            anim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = (2600 * Motion.scale.coerceAtLeast(0.4f)).toLong()
                startDelay = 250
                interpolator = PathInterpolator(0.45f, 0.05f, 0.3f, 1f)
                addUpdateListener { progress = it.animatedValue as Float; invalidate() }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(a: android.animation.Animator) { if (progress >= 1f) end() }
                })
                start()
            }
        }

        /** A slow glow pulse once written (stops when the screen goes away). */
        fun breathe() {
            anim = ValueAnimator.ofFloat(0f, 1f, 0f).apply {
                duration = 3200; repeatCount = ValueAnimator.INFINITE
                addUpdateListener { glow = it.animatedValue as Float; invalidate() }
                start()
            }
        }

        fun stop() { anim?.cancel() }
        override fun onDetachedFromWindow() { stop(); super.onDetachedFromWindow() }

        override fun onDraw(c: Canvas) {
            val m = measure ?: return
            c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), halo.apply { alpha = (120 + 135 * glow).toInt() })
            partial.reset()
            m.getSegment(0f, length * progress, partial, true)
            glowPaint.alpha = (30 + 40 * glow).toInt()
            c.drawPath(partial, glowPaint)
            c.drawPath(partial, pen)
        }
    }
}
