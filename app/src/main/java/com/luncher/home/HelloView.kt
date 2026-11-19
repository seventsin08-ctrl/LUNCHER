package com.luncher.home

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.Shader
import android.graphics.Typeface
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * The iPhone "hello" welcome screen: the word is written out in cursive, stroke by stroke,
 * then cycles through other languages. Swipe up or tap to begin.
 */
class HelloView(ctx: Context, private val onDone: () -> Unit) : FrameLayout(ctx) {
    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    private val words = listOf("hello", "hola", "bonjour", "ciao", "olá", "hallo", "kumusta")
    private var wordIndex = 0
    private val writer = Writer(ctx)
    private val hint = TextView(ctx).apply {
        text = "Swipe up to begin"; setTextColor(0xCCFFFFFF.toInt()); textSize = 16f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); alpha = 0f
    }
    private var finished = false
    private var downY = 0f

    init {
        isClickable = true
        setBackgroundColor(Color.BLACK)
        addView(writer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(hint, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
            .apply { bottomMargin = dp(90) })
        post { writeNext() }
        hint.animate().alpha(1f).setStartDelay(1800).setDuration(600).start()
    }

    private fun writeNext() {
        if (finished) return
        writer.setWord(words[wordIndex % words.size])
        wordIndex++
        writer.write {
            // Hold, fade the word out, then write the next language.
            writer.animate().alpha(0f).setStartDelay(900).setDuration(350).withEndAction {
                writer.alpha = 1f
                writeNext()
            }.start()
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> downY = e.rawY
            MotionEvent.ACTION_MOVE -> translationY = (e.rawY - downY).coerceAtMost(0f) * 0.5f
            MotionEvent.ACTION_UP -> if (downY - e.rawY > dp(40) || kotlin.math.abs(downY - e.rawY) < dp(10)) finish()
                else animate().translationY(0f).setDuration(200).start()
        }
        return true
    }

    fun finish() {
        if (finished) return
        finished = true
        animate().alpha(0f).scaleX(1.08f).scaleY(1.08f).translationY(-dp(60).toFloat())
            .setDuration(420).setInterpolator(DecelerateInterpolator(1.8f)).withEndAction { onDone() }.start()
    }

    /** Draws a word's outline progressively (like handwriting), then fills it in. */
    private class Writer(ctx: Context) : View(ctx) {
        private val face = Typeface.create("cursive", Typeface.NORMAL)
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = face }
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; color = Color.WHITE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val glyphs = Path()
        private val partial = Path()
        private val contours = ArrayList<Pair<Path, Float>>()
        private var total = 0f
        private var progress = 0f
        private var fillAlpha = 0f
        private var word = "hello"

        fun setWord(w: String) { word = w; buildPath(); progress = 0f; fillAlpha = 0f; invalidate() }

        private fun buildPath() {
            if (width == 0) return
            textPaint.textSize = width * 0.30f
            val tw = textPaint.measureText(word)
            if (tw > width * 0.84f) textPaint.textSize *= width * 0.84f / tw
            val x = (width - textPaint.measureText(word)) / 2f
            val y = height * 0.46f
            glyphs.reset()
            textPaint.getTextPath(word, 0, word.length, x, y, glyphs)
            contours.clear(); total = 0f
            val pm = PathMeasure(glyphs, false)
            do {
                val len = pm.length
                if (len > 0f) {
                    val seg = Path()
                    pm.getSegment(0f, len, seg, true)
                    contours += seg to len
                    total += len
                }
            } while (pm.nextContour())
            stroke.strokeWidth = textPaint.textSize * 0.018f + 1.5f
            // Soft white-to-silver shimmer across the word.
            fill.shader = LinearGradient(x, 0f, x + textPaint.measureText(word), 0f,
                intArrayOf(Color.WHITE, 0xFFDDE6FF.toInt(), Color.WHITE), null, Shader.TileMode.CLAMP)
        }

        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { buildPath() }

        fun write(end: () -> Unit) {
            if (contours.isEmpty()) buildPath()
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = (1700 * Motion.scale).toLong().coerceAtLeast(1)
                interpolator = PathInterpolator(0.35f, 0f, 0.25f, 1f)
                addUpdateListener { progress = it.animatedValue as Float; invalidate() }
                doOnEnd {
                    ValueAnimator.ofFloat(0f, 1f).apply {
                        duration = (380 * Motion.scale).toLong().coerceAtLeast(1)
                        addUpdateListener { fillAlpha = it.animatedValue as Float; invalidate() }
                        doOnEnd { end() }
                        start()
                    }
                }
                start()
            }
        }

        private fun ValueAnimator.doOnEnd(f: () -> Unit) = addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) = f()
        })

        override fun onDraw(c: Canvas) {
            if (fillAlpha > 0f) {
                fill.alpha = (255 * fillAlpha).toInt()
                c.drawPath(glyphs, fill)
            }
            // Outline written so far.
            var left = total * progress
            stroke.alpha = (255 * (1f - fillAlpha * 0.6f)).toInt()
            for ((p, len) in contours) {
                if (left <= 0f) break
                if (left >= len) c.drawPath(p, stroke)
                else {
                    partial.reset()
                    PathMeasure(p, false).getSegment(0f, left, partial, true)
                    c.drawPath(partial, stroke)
                }
                left -= len
            }
        }
    }
}
