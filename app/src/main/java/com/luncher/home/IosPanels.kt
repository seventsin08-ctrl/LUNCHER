package com.luncher.home

import android.animation.ValueAnimator
import android.app.Activity
import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.service.notification.StatusBarNotification
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextClock
import android.widget.TextView
import androidx.dynamicanimation.animation.DynamicAnimation
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random

private val EASE_IOS = PathInterpolator(0.2f, 0.9f, 0.1f, 1f)
private fun Context.dpx(v: Int) = (v * resources.displayMetrics.density).roundToInt()

private fun label(c: Context, s: String, size: Float, color: Int = Color.WHITE, bold: Boolean = false) = TextView(c).apply {
    text = s; textSize = size; setTextColor(color)
    typeface = Typeface.create(if (bold) "sans-serif-medium" else "sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
}

/**
 * A panel that covers the Home Screen (Notification Center, Today View, StandBy, lock page).
 * It asks the [Ios.Stage] first, so two of them can never be on screen together.
 */
abstract class IosLayer(protected val act: Activity, protected val root: FrameLayout, protected val stage: Ios.Stage, private val id: String) {
    protected var layer: FrameLayout? = null
    val isShowing get() = layer != null
    /** Blur/dim the Home Screen behind (true) or bring it back (false). */
    var onBlur: ((Boolean) -> Unit)? = null

    protected abstract fun build(l: FrameLayout)
    protected open fun enter(l: FrameLayout) {
        l.alpha = 0f
        l.animate().alpha(1f).setDuration((240 * Motion.scale).toLong()).setInterpolator(EASE_IOS).start()
    }
    protected open fun exit(l: FrameLayout, done: () -> Unit) {
        l.animate().alpha(0f).setDuration((200 * Motion.scale).toLong()).setInterpolator(EASE_IOS).withEndAction(done).start()
    }

    fun show(): Boolean {
        if (layer != null) return true
        if (!stage.request(id)) return false
        val l = FrameLayout(act).apply { isClickable = true; isFocusable = true }
        layer = l
        build(l)
        root.addView(l, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        onBlur?.invoke(true)
        enter(l)
        return true
    }

    fun hide(immediate: Boolean = false) {
        val l = layer ?: return
        layer = null
        onBlur?.invoke(false)
        val finish = { root.removeView(l); stage.release(id); onHidden() }
        if (immediate || Motion.scale <= 0f) finish() else exit(l, finish)
    }

    protected open fun onHidden() {}
}

// ====================================================================== 23. Notification Center

class NotificationCenter(act: Activity, root: FrameLayout, stage: Ios.Stage) : IosLayer(act, root, stage, "nc") {
    var appInfo: ((String) -> Pair<Bitmap?, String>?)? = null
    var summary: (() -> List<Triple<String, String, String>>)? = null
    var onSummaryRead: (() -> Unit)? = null
    var topInset = 0
    var bottomInset = 0
    private var panel: LinearLayout? = null

    override fun build(l: FrameLayout) {
        l.setBackgroundColor(Color.argb(150, 0, 0, 0))
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(act.dpx(14), topInset + act.dpx(18), act.dpx(14), bottomInset + act.dpx(30)) }
        panel = col
        // The big clock, like the top of iPhone's Notification Center.
        col.addView(TextClock(act).apply {
            format12Hour = "EEEE, MMMM d"; format24Hour = "EEEE, d MMMM"; textSize = 17f; setTextColor(0xE6FFFFFF.toInt()); gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        col.addView(TextClock(act).apply {
            format12Hour = "h:mm"; format24Hour = "H:mm"; textSize = 78f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif", Typeface.BOLD); includeFontPadding = false
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = act.dpx(18) })
        fill(col)
        val scroll = ScrollView(act).apply { isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; addView(col) }
        l.addView(scroll, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        col.setOnClickListener { hide() }
        l.setOnClickListener { hide() }
        // Swipe up anywhere to put it away.
        var downY = 0f
        scroll.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> downY = e.rawY
                MotionEvent.ACTION_UP -> if (downY - e.rawY > act.dpx(90) && !scroll.canScrollVertically(1)) { hide(); return@setOnTouchListener true }
            }
            false
        }
    }

    private fun fill(col: LinearLayout) {
        while (col.childCount > 2) col.removeViewAt(2)
        val sum = summary?.invoke().orEmpty()
        if (sum.isNotEmpty()) {
            col.addView(card(null, "Your Summary", "${sum.size} notification${if (sum.size == 1) "" else "s"}",
                sum.take(4).joinToString("\n") { "• ${it.second.ifBlank { it.first }}: ${it.third}" }.take(400), 0xFFFF9F0A.toInt()) {
                onSummaryRead?.invoke(); fill(col)
            }, rowLp())
        }
        if (NotificationService.instance == null) {
            col.addView(card(null, "Notification Centre", "Allow notification access",
                "Tap to let Apple Launcher show your notifications here", 0xFF0A84FF.toInt()) {
                runCatching { act.startActivity(android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                hide()
            }, rowLp())
            return
        }
        val sbns = NotificationService.active().filter {
            it.packageName != act.packageName && !it.isOngoing && it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0
        }
        val notes = sbns.map { Ios.Note(it.packageName, it.key, it.postTime) }
        val byKey = sbns.associateBy { it.key }
        val groups = Ios.group(notes)
        if (groups.isEmpty() && sum.isEmpty()) {
            col.addView(label(act, "No Older Notifications", 15f, 0x99FFFFFF.toInt()).apply { gravity = Gravity.CENTER; setPadding(0, act.dpx(30), 0, 0) },
                LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            return
        }
        if (groups.isNotEmpty()) {
            val head = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(act.dpx(6), 0, act.dpx(4), act.dpx(8)) }
            head.addView(label(act, "Notification Centre", 20f, Color.WHITE, true), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            head.addView(label(act, "Clear All", 14f, Color.WHITE, true).apply {
                setPadding(act.dpx(12), act.dpx(6), act.dpx(12), act.dpx(6)); background = Glass.pill(0x33FFFFFF, act.dpx(14).toFloat())
                setOnClickListener { v ->
                    v.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                    NotificationService.clear(sbns.filter { it.isClearable }.map { it.key }); fill(col)
                }
            })
            col.addView(head)
        }
        for ((pkg, list) in groups) {
            val first = byKey[list.first().title] ?: continue
            val (title, text) = textOf(first)
            val info = appInfo?.invoke(pkg)
            val more = list.size - 1
            val body = if (more > 0) "$text\n+$more more" else text
            val v = card(info?.first, info?.second ?: pkg, title, body, 0) {
                runCatching { first.notification.contentIntent?.send() }
                hide()
            }
            swipeToClear(v) { NotificationService.clear(list.mapNotNull { byKey[it.title] }.filter { it.isClearable }.map { it.key }) }
            col.addView(v, rowLp())
        }
    }

    private fun textOf(s: StatusBarNotification): Pair<String, String> {
        val ex = s.notification.extras
        return (ex?.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: "") to (ex?.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: "")
    }

    private fun rowLp() = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = act.dpx(8) }

    private fun card(icon: Bitmap?, app: String, title: String, text: String, accent: Int, onTap: () -> Unit): View {
        val c = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.TOP
            setPadding(act.dpx(14), act.dpx(12), act.dpx(14), act.dpx(12))
            background = Glass.panel(act, 22f)
            setOnClickListener { onTap() }
        }
        val img = ImageView(act).apply {
            if (icon != null) setImageBitmap(icon) else background = Glass.circle(if (accent != 0) accent else 0x55FFFFFF)
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, o: android.graphics.Outline) = o.setRoundRect(0, 0, view.width, view.height, view.width * 0.26f)
            }
            clipToOutline = true
        }
        c.addView(img, LinearLayout.LayoutParams(act.dpx(38), act.dpx(38)).apply { marginEnd = act.dpx(12) })
        val t = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        t.addView(label(act, app.uppercase(), 11f, 0x99FFFFFF.toInt(), true))
        if (title.isNotBlank()) t.addView(label(act, title, 15f, Color.WHITE, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        if (text.isNotBlank()) t.addView(label(act, text, 14f, 0xE6FFFFFF.toInt()).apply { maxLines = 5; ellipsize = android.text.TextUtils.TruncateAt.END })
        c.addView(t, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        return c
    }

    /** Swipe a card sideways to clear it, like on iPhone. */
    private fun swipeToClear(v: View, clear: () -> Unit) {
        var x0 = 0f; var moved = false
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { x0 = e.rawX; moved = false }
                MotionEvent.ACTION_MOVE -> { val dx = e.rawX - x0; if (abs(dx) > act.dpx(12)) { moved = true; v.parent?.requestDisallowInterceptTouchEvent(true); v.translationX = dx; v.alpha = 1f - abs(dx) / v.width.coerceAtLeast(1) } }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (moved && abs(v.translationX) > v.width * 0.35f) {
                        v.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                        v.animate().translationX(if (v.translationX > 0) v.width.toFloat() else -v.width.toFloat()).alpha(0f).setDuration(180)
                            .withEndAction { clear(); (v.parent as? LinearLayout)?.removeView(v) }.start()
                        return@setOnTouchListener true
                    }
                    if (moved) { Motion.spring(v, DynamicAnimation.TRANSLATION_X, 0f, Motion.FLUID); v.animate().alpha(1f).setDuration(150).start(); return@setOnTouchListener true }
                }
            }
            false
        }
    }

    override fun enter(l: FrameLayout) {
        super.enter(l)
        val p = panel ?: return
        Motion.spring(p, DynamicAnimation.TRANSLATION_Y, 0f, Motion.FLUID, start = -act.dpx(140).toFloat())
    }

    override fun exit(l: FrameLayout, done: () -> Unit) {
        panel?.animate()?.translationY(-act.dpx(160).toFloat())?.setDuration((220 * Motion.scale).toLong())?.setInterpolator(EASE_IOS)?.start()
        super.exit(l, done)
    }
}

// ====================================================================== 21. Today View

class TodayView(act: Activity, root: FrameLayout, stage: Ios.Stage) : IosLayer(act, root, stage, "today") {
    /** Builds the cards each time it opens (fresh data). */
    var cards: (() -> List<View>)? = null
    var topInset = 0
    var bottomInset = 0
    private var column: View? = null

    override fun build(l: FrameLayout) {
        l.setBackgroundColor(Color.argb(90, 0, 0, 0))
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(act.dpx(16), topInset + act.dpx(14), act.dpx(16), bottomInset + act.dpx(24)) }
        col.addView(TextClock(act).apply {
            format12Hour = "EEEE, MMMM d"; format24Hour = "EEEE, d MMMM"; textSize = 15f; setTextColor(0xCCFFFFFF.toInt())
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        col.addView(label(act, "Today", 34f, Color.WHITE, true), LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { bottomMargin = act.dpx(12) })
        for (card in cards?.invoke().orEmpty()) {
            val f = FrameLayout(act).apply { background = Glass.panel(act, 24f); addView(card, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)) }
            col.addView(f, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = act.dpx(12) })
        }
        val scroll = ScrollView(act).apply { isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; addView(col) }
        column = scroll
        l.addView(scroll, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        l.setOnClickListener { hide() }
        // Swipe left to go back to the Home Screen.
        var x0 = 0f; var y0 = 0f
        val back = View.OnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { x0 = e.rawX; y0 = e.rawY }
                MotionEvent.ACTION_MOVE -> { val dx = e.rawX - x0; if (dx < 0 && abs(dx) > abs(e.rawY - y0) * 1.4f) scroll.translationX = dx }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (scroll.translationX < -act.dpx(90)) { hide(); return@OnTouchListener true }
                    if (scroll.translationX != 0f) Motion.spring(scroll, DynamicAnimation.TRANSLATION_X, 0f, Motion.FLUID)
                }
            }
            false
        }
        scroll.setOnTouchListener(back)
    }

    override fun enter(l: FrameLayout) {
        super.enter(l)
        column?.let { Motion.spring(it, DynamicAnimation.TRANSLATION_X, 0f, Motion.FLUID, start = -act.resources.displayMetrics.widthPixels * 0.6f) }
    }

    override fun exit(l: FrameLayout, done: () -> Unit) {
        column?.animate()?.translationX(-act.resources.displayMetrics.widthPixels.toFloat())?.setDuration((240 * Motion.scale).toLong())?.setInterpolator(EASE_IOS)?.start()
        super.exit(l, done)
    }
}

// ====================================================================== 31. StandBy

class StandBy(act: Activity, root: FrameLayout, stage: Ios.Stage) : IosLayer(act, root, stage, "standby") {
    /** +90 = phone turned left (top to the left), -90 = turned right. */
    var turn = 90f
    var battery: (() -> Int)? = null
    var photo: (() -> Bitmap?)? = null

    override fun build(l: FrameLayout) {
        l.setBackgroundColor(Color.BLACK)
        val sw = act.resources.displayMetrics.widthPixels; val sh = act.resources.displayMetrics.heightPixels
        // The launcher stays portrait; StandBy draws its own landscape page rotated to match the phone.
        val land = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(act.dpx(28), act.dpx(16), act.dpx(28), act.dpx(16))
            rotation = turn
        }
        val left = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        left.addView(TextClock(act).apply {
            format12Hour = "h:mm"; format24Hour = "H:mm"; textSize = 96f; setTextColor(0xFFFF9F0A.toInt())
            typeface = Typeface.create("sans-serif", Typeface.BOLD); includeFontPadding = false
        })
        left.addView(TextClock(act).apply {
            format12Hour = "EEEE, MMMM d"; format24Hour = "EEEE, d MMMM"; textSize = 18f; setTextColor(0xCCFF9F0A.toInt())
        })
        land.addView(left, LinearLayout.LayoutParams(0, MATCH_PARENT, 1.3f))
        val right = FrameLayout(act).apply { background = GradientDrawable().apply { cornerRadius = act.dpx(28).toFloat(); setColor(0xFF1C1C1E.toInt()) }; clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() { override fun getOutline(v: View, o: android.graphics.Outline) = o.setRoundRect(0, 0, v.width, v.height, act.dpx(28).toFloat()) } }
        val pic = photo?.invoke()
        if (pic != null) right.addView(ImageView(act).apply { setImageBitmap(pic); scaleType = ImageView.ScaleType.CENTER_CROP }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        val lvl = battery?.invoke() ?: 0
        right.addView(label(act, "⚡ $lvl%", 22f, 0xFF30D158.toInt(), true).apply {
            setPadding(act.dpx(16), act.dpx(10), act.dpx(16), act.dpx(10)); background = Glass.pill(0x99000000.toInt(), act.dpx(18).toFloat())
        }, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply { setMargins(act.dpx(14), 0, 0, act.dpx(14)) })
        land.addView(right, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f).apply { marginStart = act.dpx(20) })
        // A sh×sw box rotated 90° fills the portrait window exactly.
        l.addView(land, FrameLayout.LayoutParams(sh, sw, Gravity.CENTER))
        l.setOnClickListener { hide() }
    }
}

// ====================================================================== 32. Charging ring

class ChargingRing(act: Activity, root: FrameLayout, stage: Ios.Stage) : IosLayer(act, root, stage, "charging") {
    var level = 0
    override fun build(l: FrameLayout) {
        l.isClickable = false
        val ring = RingView(act, level)
        l.addView(ring, FrameLayout.LayoutParams(act.dpx(170), act.dpx(170), Gravity.CENTER))
        ring.scaleX = 0.6f; ring.scaleY = 0.6f
        Motion.scaleTo(ring, 1f, Motion.BOUNCY)
        ring.play()
        l.postDelayed({ hide() }, 2200)
    }

    private class RingView(c: Context, private val level: Int) : View(c) {
        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0x3330D158; strokeCap = Paint.Cap.ROUND }
        private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xFF30D158.toInt(); strokeCap = Paint.Cap.ROUND }
        private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0x8830D158.toInt(); strokeCap = Paint.Cap.ROUND }
        private val disc = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCC000000.toInt() }
        private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER; typeface = Typeface.create("sans-serif-medium", Typeface.BOLD) }
        private var p = 0f
        private val box = RectF()
        init { setLayerType(LAYER_TYPE_SOFTWARE, null) } // blur mask needs software; tiny view, drawn ~40 frames once
        fun play() = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = (900 * Motion.scale.coerceAtLeast(0.3f)).toLong(); interpolator = EASE_IOS
            addUpdateListener { p = it.animatedValue as Float; invalidate() }; start()
        }
        override fun onDraw(c: Canvas) {
            val s = width.coerceAtMost(height).toFloat()
            val sw = s * 0.08f
            track.strokeWidth = sw; arc.strokeWidth = sw; glow.strokeWidth = sw * 1.6f
            if (glow.maskFilter == null) glow.maskFilter = BlurMaskFilter(sw, BlurMaskFilter.Blur.NORMAL)
            box.set(sw * 1.5f, sw * 1.5f, s - sw * 1.5f, s - sw * 1.5f)
            c.drawCircle(s / 2, s / 2, s / 2, disc)
            c.drawArc(box, 0f, 360f, false, track)
            val sweep = 360f * level.coerceIn(0, 100) / 100f * p
            c.drawArc(box, -90f, sweep, false, glow)
            c.drawArc(box, -90f, sweep, false, arc)
            txt.textSize = s * 0.2f
            c.drawText("⚡${(level * p).toInt()}%", s / 2, s / 2 + txt.textSize * 0.35f, txt)
        }
    }
}

// ====================================================================== 29. Siri glow

/** Rainbow glow around the screen edge; brightens with your voice. Only runs while listening. */
class EdgeGlowView(c: Context) : View(c) {
    var level = 0f
        set(v) { field = v.coerceIn(0f, 1f) }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val soft = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private var angle = 0f
    private var running = false
    private val box = RectF()
    private val colors = intArrayOf(0xFF0A84FF.toInt(), 0xFFBF5AF2.toInt(), 0xFFFF375F.toInt(), 0xFFFF9F0A.toInt(), 0xFF64D2FF.toInt(), 0xFF0A84FF.toInt())
    private val tick = object : Runnable { override fun run() { if (!running) return; angle = (angle + 3f) % 360f; invalidate(); postOnAnimation(this) } }

    fun start() {
        if (running) return
        running = true; visibility = VISIBLE; alpha = 0f
        animate().alpha(1f).setDuration(260).start()
        postOnAnimation(tick)
    }
    fun stop() {
        if (!running) return
        running = false
        animate().alpha(0f).setDuration(260).withEndAction { visibility = GONE }.start()
    }
    override fun onDetachedFromWindow() { running = false; super.onDetachedFromWindow() }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val r = resources.displayMetrics.density * 44f
        val shader = SweepGradient(w / 2, h / 2, colors, null).apply {
            setLocalMatrix(android.graphics.Matrix().apply { setRotate(angle, w / 2, h / 2) })
        }
        val sw = resources.displayMetrics.density * (5f + 7f * level)
        paint.shader = shader; paint.strokeWidth = sw
        soft.shader = shader; soft.strokeWidth = sw * 3.2f; soft.alpha = 90
        box.set(0f, 0f, w, h)
        c.drawRoundRect(box, r, r, soft)
        c.drawRoundRect(box, r, r, paint)
    }
}

// ====================================================================== 33. Live weather sky

class SkyView(c: Context) : View(c) {
    var sky = Ios.Sky.CLEAR_DAY
        set(v) { field = v; seed() }
    /** Asked before each frame: may the sky move right now? (Stage + battery saver + visible). */
    var fps: () -> Int = { 0 }
    private val bgPaint = Paint()
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    // Soft clouds from a cached radial gradient (GPU-friendly, no blur pass).
    private val cloud = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = android.graphics.RadialGradient(0f, 0f, 100f, intArrayOf(0xFFFFFFFF.toInt(), 0x00FFFFFF), null, Shader.TileMode.CLAMP)
    }
    private var parts = FloatArray(0)
    private var t = 0f
    private var lastH = 0

    private fun seed() {
        val n = when (sky) { Ios.Sky.RAIN, Ios.Sky.STORM -> 70; Ios.Sky.SNOW -> 55; Ios.Sky.CLEAR_NIGHT -> 60; Ios.Sky.CLOUDS, Ios.Sky.FOG -> 6; else -> 4 }
        val r = Random(7)
        parts = FloatArray(n * 3) { i -> when (i % 3) { 0 -> r.nextFloat(); 1 -> r.nextFloat(); else -> 0.4f + r.nextFloat() * 0.6f } }
        lastH = 0
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (lastH != height) {
            lastH = height
            val (top, bottom) = when (sky) {
                Ios.Sky.CLEAR_DAY -> 0xFF2F7BD9.toInt() to 0xFF8EC5F5.toInt()
                Ios.Sky.CLEAR_NIGHT -> 0xFF050816.toInt() to 0xFF1B2440.toInt()
                Ios.Sky.CLOUDS -> 0xFF5A6E85.toInt() to 0xFF9AAABB.toInt()
                Ios.Sky.RAIN -> 0xFF2E3A48.toInt() to 0xFF56677A.toInt()
                Ios.Sky.STORM -> 0xFF15191F.toInt() to 0xFF3A4350.toInt()
                Ios.Sky.SNOW -> 0xFF7D8DA3.toInt() to 0xFFC9D3DF.toInt()
                Ios.Sky.FOG -> 0xFF7A8088.toInt() to 0xFFB4B9BF.toInt()
            }
            bgPaint.shader = LinearGradient(0f, 0f, 0f, h, top, bottom, Shader.TileMode.CLAMP)
        }
        c.drawRect(0f, 0f, w, h, bgPaint)
        val n = parts.size / 3
        for (i in 0 until n) {
            val x0 = parts[i * 3]; val y0 = parts[i * 3 + 1]; val z = parts[i * 3 + 2]
            when (sky) {
                Ios.Sky.RAIN, Ios.Sky.STORM -> {
                    val y = ((y0 + t * 0.9f * z) % 1f) * h; val x = x0 * w
                    dot.color = Color.argb((90 * z).toInt(), 200, 220, 255); dot.strokeWidth = 2f * z
                    c.drawLine(x, y, x - 6f * z, y + 30f * z, dot)
                }
                Ios.Sky.SNOW -> {
                    val y = ((y0 + t * 0.12f * z) % 1f) * h; val x = (x0 * w + kotlin.math.sin((t * 2 + i).toDouble()).toFloat() * 14f)
                    dot.color = Color.argb((200 * z).toInt(), 255, 255, 255); c.drawCircle(x, y, 3f * z + 1f, dot)
                }
                Ios.Sky.CLEAR_NIGHT -> {
                    val tw = 0.5f + 0.5f * kotlin.math.sin((t * 3 + i * 1.7f).toDouble()).toFloat()
                    dot.color = Color.argb((160 * z * tw).toInt(), 255, 255, 255); c.drawCircle(x0 * w, y0 * h * 0.7f, 1.6f * z, dot)
                }
                else -> {
                    val x = ((x0 + t * 0.015f * z) % 1.3f - 0.15f) * w; val y = y0 * h * 0.5f
                    cloud.alpha = (if (sky == Ios.Sky.CLEAR_DAY) 60f else 120f * z).toInt().coerceAtMost(150)
                    c.save(); c.translate(x, y); c.scale(2.2f * z, 0.7f * z); c.drawCircle(0f, 0f, 100f, cloud); c.restore()
                }
            }
        }
        if (sky == Ios.Sky.STORM && (t * 10).toInt() % 97 == 0) c.drawColor(0x33FFFFFF)
        val f = fps()
        if (f > 0 && isShown) {
            t += 1f / f
            postInvalidateDelayed(1000L / f)
        }
    }

    /** Called when the sky may move again (home visible, nothing covering it). */
    fun wake() { if (fps() > 0) invalidate() }
}

// ====================================================================== 34. Lock-style page

class LockCover(act: Activity, root: FrameLayout, stage: Ios.Stage) : IosLayer(act, root, stage, "lockpage") {
    var info: (() -> String)? = null
    private var content: LinearLayout? = null

    override fun build(l: FrameLayout) {
        l.setBackgroundColor(Color.argb(120, 0, 0, 0))
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(0, act.dpx(90), 0, 0) }
        content = col
        col.addView(TextClock(act).apply {
            format12Hour = "EEEE, MMMM d"; format24Hour = "EEEE, d MMMM"; textSize = 19f; setTextColor(0xE6FFFFFF.toInt())
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        col.addView(TextClock(act).apply {
            format12Hour = "h:mm"; format24Hour = "H:mm"; textSize = 96f; setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif", Typeface.BOLD); includeFontPadding = false
        })
        info?.invoke()?.takeIf { it.isNotBlank() }?.let {
            col.addView(label(act, it, 15f, Color.WHITE).apply {
                setPadding(act.dpx(14), act.dpx(7), act.dpx(14), act.dpx(7)); background = Glass.panel(act, 16f)
            }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = act.dpx(14) })
        }
        l.addView(col, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP))
        val hint = label(act, "Swipe up to open", 14f, 0xCCFFFFFF.toInt()).apply { gravity = Gravity.CENTER }
        l.addView(hint, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM).apply { bottomMargin = act.dpx(60) })
        l.addView(View(act).apply { background = Glass.pill(0xCCFFFFFF.toInt(), act.dpx(3).toFloat()) },
            FrameLayout.LayoutParams(act.dpx(134), act.dpx(5), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = act.dpx(34) })
        // Follow the finger up; let go past a third of the screen (or fling) to open.
        var y0 = 0f; var t0 = 0L
        l.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { y0 = e.rawY; t0 = e.eventTime }
                MotionEvent.ACTION_MOVE -> { val dy = (e.rawY - y0).coerceAtMost(0f); l.translationY = dy; l.alpha = 1f + dy / l.height.coerceAtLeast(1) }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val dy = y0 - e.rawY; val fast = dy > act.dpx(60) && e.eventTime - t0 < 250
                    if (dy > l.height / 3f || fast) {
                        l.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                        l.animate().translationY(-l.height.toFloat()).alpha(0f).setDuration((220 * Motion.scale).toLong()).withEndAction { hide(immediate = true) }.start()
                    } else { Motion.spring(l, DynamicAnimation.TRANSLATION_Y, 0f, Motion.FLUID); l.animate().alpha(1f).setDuration(150).start() }
                }
            }
            true
        }
    }
}

// ====================================================================== 13. App Lock

object AppLock {
    /** Asks for your fingerprint (or screen lock) before [onPass]. Falls back to opening on old phones. */
    fun check(act: Activity, appName: String, onPass: () -> Unit) {
        if (Build.VERSION.SDK_INT < 28) { onPass(); return }
        val b = android.hardware.biometrics.BiometricPrompt.Builder(act)
            .setTitle("Unlock $appName")
            .setSubtitle("Use your fingerprint to open")
        if (Build.VERSION.SDK_INT >= 30) b.setAllowedAuthenticators(
            android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_WEAK or
                android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL)
        else @Suppress("DEPRECATION") b.setDeviceCredentialAllowed(true)
        val prompt = runCatching { b.build() }.getOrElse { onPass(); return }
        runCatching {
            prompt.authenticate(android.os.CancellationSignal(), act.mainExecutor,
                object : android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: android.hardware.biometrics.BiometricPrompt.AuthenticationResult?) = onPass()
                    override fun onAuthenticationError(code: Int, msg: CharSequence?) {
                        // No fingerprint or screen lock set up on this phone: the lock can't work, so don't trap the app.
                        if (code == android.hardware.biometrics.BiometricPrompt.BIOMETRIC_ERROR_HW_NOT_PRESENT ||
                            code == android.hardware.biometrics.BiometricPrompt.BIOMETRIC_ERROR_HW_UNAVAILABLE ||
                            code == android.hardware.biometrics.BiometricPrompt.BIOMETRIC_ERROR_NO_BIOMETRICS ||
                            (Build.VERSION.SDK_INT >= 29 && code == android.hardware.biometrics.BiometricPrompt.BIOMETRIC_ERROR_NO_DEVICE_CREDENTIAL)) onPass()
                    }
                })
        }.onFailure { onPass() }
    }
}
