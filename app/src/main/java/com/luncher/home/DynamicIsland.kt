package com.luncher.home

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.text.TextUtils
import android.view.GestureDetector
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

/** One live activity shown in the island (music, charging, headphones…). */
class IslandActivity(
    val id: String,
    val icon: Bitmap?,
    val iconRes: Int,
    val iconTint: Int,
    val title: String,
    val subtitle: String,
    val trailing: String?,
    val showBars: Boolean,
    val accent: Int,
    val actions: List<Pair<String, () -> Unit>> = emptyList()
)

/**
 * A Dynamic Island–style black pill at the top of the Home Screen.
 * - Grows to show up to three live activities; swipe left/right to switch or hide one.
 * - Tap to expand it into a large card with controls.
 * - Swipe down on it to open your assistant; long-press for volume controls.
 */
class DynamicIsland(
    private val act: Activity,
    private val root: FrameLayout,
    private val onAssistant: () -> Unit,
    private val onVolume: () -> Unit,
    private val haptics: () -> Boolean
) {
    private val activities = LinkedHashMap<String, IslandActivity>()
    private val hidden = HashSet<String>()
    private var index = 0
    private var expanded = false
    var enabled = true
        set(v) { field = v; refresh() }

    private fun dp(v: Int) = (v * act.resources.displayMetrics.density).roundToInt()

    private val pill = FrameLayout(act).apply {
        background = GradientDrawable().apply { setColor(Color.BLACK); cornerRadius = dp(20).toFloat() }
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, minOf(view.height / 2f, dp(40).toFloat()))
            }
        }
        clipToOutline = true
        elevation = dp(6).toFloat()
        visibility = View.GONE
    }
    private val compact = LinearLayout(act).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8), 0, dp(12), 0)
    }
    private val icon = ImageView(act).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    private val label = TextView(act).apply {
        setTextColor(Color.WHITE)
        textSize = 13f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    private val trailing = TextView(act).apply {
        textSize = 13f
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        maxLines = 1
    }
    private val bars = BarsView(act)
    private val expandedView = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(18), dp(20), dp(16))
        visibility = View.GONE
    }
    private var topMargin = 0
    private var widthSpring: SpringAnimation? = null
    private var heightSpring: SpringAnimation? = null
    private var catcher: View? = null

    init {
        icon.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) =
                outline.setRoundRect(0, 0, view.width, view.height, view.width * 0.3f)
        }
        icon.clipToOutline = true
        compact.addView(icon, LinearLayout.LayoutParams(dp(22), dp(22)))
        compact.addView(label, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = dp(8) })
        compact.addView(bars, LinearLayout.LayoutParams(dp(22), dp(16)).apply { marginStart = dp(6) })
        compact.addView(trailing, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(6) })
        pill.addView(compact, FrameLayout.LayoutParams(MATCH_PARENT, dp(36), Gravity.TOP))
        pill.addView(expandedView, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP))
        root.addView(pill, FrameLayout.LayoutParams(dp(120), dp(36), Gravity.TOP or Gravity.CENTER_HORIZONTAL))

        val detector = GestureDetector(act, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (expanded) collapse() else expand()
                return true
            }
            override fun onLongPress(e: MotionEvent) {
                buzz()
                collapse()
                onVolume()
            }
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (e1 == null) return false
                val dx = e2.x - e1.x; val dy = e2.y - e1.y
                if (abs(dy) > abs(dx) && dy > dp(30)) {
                    buzz(); collapse(); onAssistant(); return true
                }
                if (abs(dx) > abs(dy) && abs(dx) > dp(30)) {
                    cycle(if (dx < 0) 1 else -1); return true
                }
                if (dy < -dp(30) && expanded) { collapse(); return true }
                return false
            }
        })
        pill.setOnTouchListener { _, e -> detector.onTouchEvent(e); true }
    }

    /** True if a screen point lands on the island (so the home swipe-down ignores it). */
    fun hit(rawX: Float, rawY: Float): Boolean {
        if (pill.visibility != View.VISIBLE) return false
        val loc = IntArray(2)
        pill.getLocationOnScreen(loc)
        return rawX >= loc[0] - dp(10) && rawX <= loc[0] + pill.width + dp(10) &&
            rawY >= loc[1] - dp(10) && rawY <= loc[1] + pill.height + dp(14)
    }

    fun setTopInset(px: Int) {
        topMargin = px
        (pill.layoutParams as FrameLayout.LayoutParams).topMargin = px
        pill.requestLayout()
    }

    fun put(a: IslandActivity) {
        val isNew = !activities.containsKey(a.id)
        activities[a.id] = a
        if (isNew) {
            hidden.remove(a.id)
            index = activities.keys.indexOf(a.id)
        }
        while (activities.size > 3) activities.remove(activities.keys.first())
        refresh()
    }

    fun remove(id: String) {
        if (activities.remove(id) != null) refresh()
    }

    /** Show something briefly (e.g. charging started), then let it go. */
    fun flash(a: IslandActivity, millis: Long) {
        put(a)
        pill.postDelayed({ if (activities[a.id] === a) remove(a.id) }, millis)
    }

    private fun visibleList() = activities.values.filter { it.id !in hidden }

    private fun cycle(dir: Int) {
        val list = visibleList()
        if (list.size <= 1) {
            // Only one activity: swiping tucks it away until something new happens.
            list.firstOrNull()?.let { hidden += it.id }
        } else {
            index = (index + dir + list.size) % list.size
        }
        buzz()
        refresh()
    }

    private fun buzz() {
        if (haptics()) pill.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
    }

    private fun current(): IslandActivity? {
        val list = visibleList()
        if (list.isEmpty()) return null
        index = index.coerceIn(0, list.size - 1)
        return list[index]
    }

    private fun refresh() {
        val a = current()
        if (!enabled || a == null) {
            if (expanded) collapse(animate = false)
            if (pill.visibility == View.VISIBLE) {
                pill.animate().alpha(0f).scaleX(0.6f).scaleY(0.6f).setDuration((180 * Motion.scale).toLong())
                    .withEndAction { pill.visibility = View.GONE }.start()
            }
            return
        }
        bind(a)
        if (pill.visibility != View.VISIBLE) {
            pill.visibility = View.VISIBLE
            pill.alpha = 0f
            pill.scaleX = 0.5f; pill.scaleY = 0.5f
            Motion.scaleTo(pill, 1f, Motion.BOUNCY)
            Motion.alphaTo(pill, 1f, Motion.SNAPPY)
        }
        if (!expanded) animateSize(compactWidth(a), dp(36), dp(18).toFloat())
        else bindExpanded(a)
    }

    private fun compactWidth(a: IslandActivity): Int {
        val textW = label.paint.measureText(a.title).toInt().coerceAtMost(dp(150))
        val trailW = a.trailing?.let { trailing.paint.measureText(it).toInt() + dp(6) } ?: 0
        val barsW = if (a.showBars) dp(28) else 0
        val count = visibleList().size
        val dotsW = if (count > 1) dp(10) else 0
        return (dp(8) + dp(22) + dp(8) + textW + barsW + trailW + dp(14) + dotsW)
            .coerceIn(dp(120), root.width.coerceAtLeast(dp(300)) - dp(60))
    }

    private fun bind(a: IslandActivity) {
        if (a.icon != null) { icon.setImageBitmap(a.icon); icon.clearColorFilter() }
        else { icon.setImageResource(a.iconRes); icon.setColorFilter(a.iconTint) }
        label.text = a.title
        trailing.text = a.trailing ?: ""
        trailing.setTextColor(a.accent)
        trailing.visibility = if (a.trailing == null) View.GONE else View.VISIBLE
        bars.color = a.accent
        bars.visibility = if (a.showBars) View.VISIBLE else View.GONE
    }

    private fun bindExpanded(a: IslandActivity) {
        expandedView.removeAllViews()
        val head = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val big = ImageView(act).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            if (a.icon != null) setImageBitmap(a.icon) else { setImageResource(a.iconRes); setColorFilter(a.iconTint) }
            outlineProvider = icon.outlineProvider
            clipToOutline = true
        }
        head.addView(big, LinearLayout.LayoutParams(dp(56), dp(56)))
        val texts = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(act).apply {
            text = a.title; setTextColor(Color.WHITE); textSize = 17f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD); maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        texts.addView(TextView(act).apply {
            text = a.subtitle; setTextColor(0xB3FFFFFF.toInt()); textSize = 14f; maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        head.addView(texts, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = dp(14) })
        if (a.showBars) {
            val b = BarsView(act).apply { color = a.accent }
            head.addView(b, LinearLayout.LayoutParams(dp(30), dp(22)))
        }
        expandedView.addView(head)
        if (a.actions.isNotEmpty()) {
            val row = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            for ((name, fn) in a.actions) {
                row.addView(TextView(act).apply {
                    text = name
                    setTextColor(Color.WHITE)
                    textSize = 14f
                    gravity = Gravity.CENTER
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    background = GradientDrawable().apply {
                        cornerRadius = dp(18).toFloat(); setColor(Color.argb(255, 44, 44, 48))
                    }
                    setOnClickListener { buzz(); fn() }
                }, LinearLayout.LayoutParams(0, dp(40), 1f).apply { setMargins(dp(4), 0, dp(4), 0) })
            }
            expandedView.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(16) })
        }
    }

    fun expand() {
        val a = current() ?: return
        if (expanded) return
        expanded = true
        buzz()
        bindExpanded(a)
        compact.animate().alpha(0f).setDuration(120).start()
        expandedView.visibility = View.VISIBLE
        expandedView.alpha = 0f
        expandedView.animate().alpha(1f).setStartDelay(80).setDuration(200).start()
        expandedView.measure(
            View.MeasureSpec.makeMeasureSpec(root.width - dp(24), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        animateSize(root.width - dp(24), expandedView.measuredHeight, dp(38).toFloat())
        // Tap anywhere else to fold it back.
        val c = View(act).apply { isClickable = true; setOnClickListener { collapse() } }
        root.addView(c, root.indexOfChild(pill), FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        catcher = c
    }

    fun collapse(animate: Boolean = true) {
        if (!expanded) return
        expanded = false
        catcher?.let { root.removeView(it) }
        catcher = null
        expandedView.animate().cancel()
        expandedView.visibility = View.GONE
        compact.animate().alpha(1f).setDuration(if (animate) 180 else 0).start()
        current()?.let { animateSize(compactWidth(it), dp(36), dp(18).toFloat()) }
    }

    /** Spring the pill's size like the island morphing. */
    private fun animateSize(w: Int, h: Int, radius: Float) {
        val lp = pill.layoutParams as FrameLayout.LayoutParams
        val startW = if (pill.width > 0) pill.width else lp.width
        val startH = if (pill.height > 0) pill.height else lp.height
        (pill.background as? GradientDrawable)?.cornerRadius = radius
        if (Motion.scale <= 0f) {
            lp.width = w; lp.height = h; pill.layoutParams = lp; return
        }
        widthSpring?.cancel(); heightSpring?.cancel()
        widthSpring = springInt(startW, w) { v -> lp.width = v; pill.layoutParams = lp }
        heightSpring = springInt(startH, h) { v -> lp.height = v; pill.layoutParams = lp }
    }

    private fun springInt(from: Int, to: Int, apply: (Int) -> Unit): SpringAnimation {
        val holder = FloatValueHolder(from.toFloat())
        return SpringAnimation(holder).apply {
            spring = SpringForce(to.toFloat()).apply {
                stiffness = (500f / (Motion.scale * Motion.scale)).coerceIn(30f, 10000f)
                dampingRatio = 0.72f
            }
            setMinimumVisibleChange(1f)
            addUpdateListener { _, value, _ -> apply(value.toInt()) }
            start()
        }
    }
}

/** Little animated audio bars, like the island's music waveform. */
class BarsView(ctx: android.content.Context) : View(ctx) {
    var color = Color.WHITE
        set(v) { field = v; paint.color = v; invalidate() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val start = System.nanoTime()

    override fun onDraw(canvas: Canvas) {
        val t = (System.nanoTime() - start) / 1e9
        val n = 4
        val gap = width / (n * 2f)
        val bw = gap
        for (i in 0 until n) {
            val amp = 0.35 + 0.65 * abs(sin(t * (3.1 + i * 0.9) + i * 1.3))
            val bh = (height * amp).toFloat()
            val x = i * (bw + gap) + gap / 2f
            canvas.drawRoundRect(x, (height - bh) / 2f, x + bw, (height + bh) / 2f, bw / 2f, bw / 2f, paint)
        }
        if (isShown && Motion.scale > 0f) postInvalidateOnAnimation()
    }
}

/** Separate volume sliders for ringtone, notifications, alarms and media. */
object VolumePanel {
    fun show(act: Activity, root: FrameLayout, bottomInset: Int) {
        val d = act.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).roundToInt()
        val audio = act.getSystemService(AudioManager::class.java)
        val layer = FrameLayout(act).apply {
            setBackgroundColor(Color.argb(90, 0, 0, 0))
            alpha = 0f
            isClickable = true
        }
        val card = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(18))
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.argb(240, 50, 50, 54), Color.argb(248, 28, 28, 31))
            ).apply { cornerRadius = dp(30).toFloat(); setStroke(maxOf(1, dp(1)), Color.argb(55, 255, 255, 255)) }
            isClickable = true
            elevation = dp(20).toFloat()
        }
        card.addView(TextView(act).apply {
            text = "Volume"; setTextColor(Color.WHITE); textSize = 20f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        })
        val streams = listOf(
            "Ringtone" to AudioManager.STREAM_RING,
            "Notifications" to AudioManager.STREAM_NOTIFICATION,
            "Alarms" to AudioManager.STREAM_ALARM,
            "Media" to AudioManager.STREAM_MUSIC,
            "Calls" to AudioManager.STREAM_VOICE_CALL
        )
        for ((name, stream) in streams) {
            val max = audio.getStreamMaxVolume(stream)
            val label = TextView(act).apply {
                setTextColor(0xCCFFFFFF.toInt()); textSize = 14f
                text = name
                setPadding(0, dp(14), 0, dp(2))
            }
            card.addView(label)
            card.addView(SeekBar(act).apply {
                this.max = max
                progress = audio.getStreamVolume(stream)
                progressTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
                thumbTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
                progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0x55FFFFFF)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                        if (!fromUser) return
                        runCatching { audio.setStreamVolume(stream, p, 0) }.onFailure {
                            sb.progress = audio.getStreamVolume(stream)
                            android.widget.Toast.makeText(act, "Turn off Do Not Disturb to change $name",
                                android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                    override fun onStartTrackingTouch(sb: SeekBar) {}
                    override fun onStopTrackingTouch(sb: SeekBar) {}
                })
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        layer.addView(card, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM).apply {
            setMargins(dp(10), 0, dp(10), bottomInset + dp(10))
        })
        root.addView(layer, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        fun close() {
            layer.animate().alpha(0f).setDuration(200).start()
            Motion.spring(card, androidx.dynamicanimation.animation.DynamicAnimation.TRANSLATION_Y,
                dp(700).toFloat(), Motion.SNAPPY) { root.removeView(layer) }
            layer.postDelayed({ if (layer.parent != null) root.removeView(layer) }, 800)
        }
        layer.setOnClickListener { close() }
        layer.animate().alpha(1f).setDuration(200).start()
        Motion.spring(card, androidx.dynamicanimation.animation.DynamicAnimation.TRANSLATION_Y, 0f,
            Motion.FLUID, start = dp(700).toFloat())
    }
}
