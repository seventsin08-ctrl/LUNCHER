package com.luncher.home

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.dynamicanimation.animation.DynamicAnimation
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A countdown timer shared by the Timer widget, the Dynamic Island and Control Center.
 * Starting it also sets a real timer in the phone's Clock app (no UI), so it still rings
 * when the launcher is closed.
 */
object TimerState {
    private const val KEY_END = "timer_end"
    private const val KEY_TOTAL = "timer_total"
    var endAt = 0L
        private set
    var total = 0L
        private set
    private val listeners = LinkedHashSet<() -> Unit>()
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            emit()
            if (running) handler.postDelayed(this, 1000L - System.currentTimeMillis() % 1000L)
        }
    }

    val running get() = endAt > System.currentTimeMillis()
    val remaining get() = (endAt - System.currentTimeMillis()).coerceAtLeast(0L)

    fun load(ctx: Context) {
        val p = ctx.getSharedPreferences("luncher", Context.MODE_PRIVATE)
        endAt = p.getLong(KEY_END, 0L); total = p.getLong(KEY_TOTAL, 0L)
        if (running) handler.post(ticker)
    }

    fun start(ctx: Context, minutes: Int, label: String = "Timer") {
        total = minutes * 60_000L
        endAt = System.currentTimeMillis() + total
        save(ctx)
        // Hand the alarm to the system Clock app so it rings even if we're not running.
        runCatching {
            ctx.startActivity(Intent(AlarmClock.ACTION_SET_TIMER)
                .putExtra(AlarmClock.EXTRA_LENGTH, minutes * 60)
                .putExtra(AlarmClock.EXTRA_MESSAGE, label)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    fun cancel(ctx: Context) {
        endAt = 0L; total = 0L
        save(ctx)
        // Ask the Clock app to dismiss its timer too (supported on many phones).
        runCatching {
            ctx.startActivity(Intent("android.intent.action.DISMISS_TIMER")
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        emit()
    }

    private fun save(ctx: Context) {
        ctx.getSharedPreferences("luncher", Context.MODE_PRIVATE).edit()
            .putLong(KEY_END, endAt).putLong(KEY_TOTAL, total).apply()
    }

    fun format(ms: Long): String {
        val s = (ms + 999) / 1000
        val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec)
    }

    fun add(l: () -> Unit) { listeners += l }
    fun remove(l: () -> Unit) { listeners -= l }
    private fun emit() { for (l in listeners.toList()) l() }
}

/** Countdown ring used by the Timer widget. */
class TimerRingView(ctx: Context) : View(ctx) {
    var progress = 0f
        set(v) { field = v; invalidate() }
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = 0x33FFFFFF; strokeCap = Paint.Cap.ROUND
    }
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = 0xFFFF9F0A.toInt(); strokeCap = Paint.Cap.ROUND
    }

    override fun onDraw(canvas: Canvas) {
        val s = min(width, height).toFloat()
        val st = s * 0.09f
        track.strokeWidth = st; bar.strokeWidth = st
        val r = s / 2f - st
        val rect = RectF(width / 2f - r, height / 2f - r, width / 2f + r, height / 2f + r)
        canvas.drawArc(rect, 0f, 360f, false, track)
        canvas.drawArc(rect, -90f, 360f * progress.coerceIn(0f, 1f), false, bar)
    }
}

/** Live Activity widget: a timer with quick presets that also feeds the Dynamic Island. */
object TimerWidget {
    fun create(ctx: Context): View {
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).roundToInt()
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(12), dp(12), dp(12), dp(10))
        }
        val ringBox = FrameLayout(ctx)
        val ring = TimerRingView(ctx)
        ringBox.addView(ring, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        val time = TextView(ctx).apply {
            setTextColor(Color.WHITE)
            textSize = 20f
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            gravity = Gravity.CENTER
            fontFeatureSettings = "tnum"
        }
        ringBox.addView(time, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        col.addView(ringBox, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        val chips = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val presets = listOf(1, 5, 10, 15)
        val chipViews = presets.map { m ->
            TextView(ctx).apply {
                text = "${m}m"
                setTextColor(Color.WHITE)
                textSize = 12f
                gravity = Gravity.CENTER
                background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(0x33FFFFFF) }
                setOnClickListener { TimerState.start(ctx, m) }
            }
        }
        chipViews.forEach { chips.addView(it, LinearLayout.LayoutParams(0, dp(24), 1f).apply { setMargins(dp(2), 0, dp(2), 0) }) }
        col.addView(chips, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) })

        fun render() {
            if (TimerState.running) {
                time.text = TimerState.format(TimerState.remaining)
                ring.progress = if (TimerState.total > 0) TimerState.remaining.toFloat() / TimerState.total else 0f
                chips.visibility = View.GONE
            } else {
                time.text = "Timer"
                ring.progress = 0f
                chips.visibility = View.VISIBLE
            }
        }
        val listener: () -> Unit = { render() }
        col.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { TimerState.add(listener); render() }
            override fun onViewDetachedFromWindow(v: View) { TimerState.remove(listener) }
        })
        ringBox.setOnClickListener { if (TimerState.running) TimerState.cancel(ctx) }
        render()
        return col
    }
}

/**
 * Smart Stack: several widgets in one space. Swipe up or down to flip between them
 * (with a spring), dots show where you are, and it rotates on its own every so often.
 */
class SmartStackView(ctx: Context, private val pages: List<View>, private val onPageChanged: (Int) -> Unit = {}) :
    FrameLayout(ctx) {

    companion object {
        /** True while a finger is on a stack, so the Home Screen's swipe-down search waits. */
        @Volatile var touching = false
    }

    private var index = 0
    private val dots = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
    private var downY = 0f
    private var downX = 0f
    private var dragging = false
    private val rotate = object : Runnable {
        override fun run() {
            if (isAttachedToWindow && !touching && pages.size > 1) show(index + 1, 1)
            postDelayed(this, 25_000L)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    init {
        clipChildren = true
        for ((i, p) in pages.withIndex()) {
            addView(p, LayoutParams(MATCH_PARENT, MATCH_PARENT))
            p.visibility = if (i == 0) View.VISIBLE else View.INVISIBLE
        }
        addView(dots, LayoutParams(dp(8), WRAP_CONTENT, Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = dp(5) })
        paintDots()
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); postDelayed(rotate, 25_000L) }
    override fun onDetachedFromWindow() { removeCallbacks(rotate); touching = false; super.onDetachedFromWindow() }

    private fun paintDots() {
        dots.removeAllViews()
        if (pages.size < 2) return
        for (i in pages.indices) {
            dots.addView(View(context).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) }
                alpha = if (i == index) 1f else 0.35f
            }, LinearLayout.LayoutParams(dp(5), dp(5)).apply { setMargins(0, dp(2), 0, dp(2)) })
        }
    }

    /** Flip to page [to]; dir 1 = content moves up (next), -1 = down (previous). */
    fun show(to: Int, dir: Int) {
        if (pages.size < 2) return
        val next = ((to % pages.size) + pages.size) % pages.size
        if (next == index) return
        val out = pages[index]; val inc = pages[next]
        val h = height.toFloat().coerceAtLeast(1f)
        inc.visibility = View.VISIBLE
        Motion.spring(inc, DynamicAnimation.TRANSLATION_Y, 0f, Motion.FLUID, start = h * dir)
        Motion.scaleTo(inc, 1f, Motion.FLUID, start = 0.92f)
        Motion.spring(out, DynamicAnimation.TRANSLATION_Y, -h * dir, Motion.FLUID) {
            out.visibility = View.INVISIBLE; out.translationY = 0f
        }
        Motion.scaleTo(out, 0.92f, Motion.FLUID)
        index = next
        paintDots()
        onPageChanged(next)
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downY = e.y; downX = e.x; dragging = false; touching = true }
            MotionEvent.ACTION_MOVE -> {
                val dy = e.y - downY
                if (!dragging && abs(dy) > dp(14) && abs(dy) > abs(e.x - downX) * 1.3f && pages.size > 1) {
                    dragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> touching = false
        }
        return false
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> pages[index].translationY = (e.y - downY) * 0.5f
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                touching = false
                val dy = e.y - downY
                if (dragging && abs(dy) > dp(28)) {
                    if (Motion.scale > 0f) performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
                    show(if (dy < 0) index + 1 else index - 1, if (dy < 0) 1 else -1)
                } else {
                    Motion.spring(pages[index], DynamicAnimation.TRANSLATION_Y, 0f, Motion.BOUNCY)
                }
                dragging = false
            }
        }
        return true
    }

    @Suppress("unused")
    private fun detach(v: View) = (v.parent as? ViewGroup)?.removeView(v)
}
