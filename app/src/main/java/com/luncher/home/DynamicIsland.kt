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
import androidx.dynamicanimation.animation.DynamicAnimation
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
    val actions: List<Pair<String, () -> Unit>> = emptyList(),
    /** When set, the expanded island shows the full iPhone-style music player. */
    val media: IslandMedia? = null,
    /** Custom big view for the expanded island (weather gauge, steps, calendar…). */
    val custom: ((Activity) -> View)? = null
)

/** A round button in the island's pull-down quick controls. */
class QuickAction(val iconRes: Int, val label: String, val active: () -> Boolean, val onTap: () -> Unit)

/** Live music controls for the expanded island. */
class IslandMedia(
    val appIcon: Bitmap?,
    val duration: Long,
    val position: () -> Long,
    val playing: () -> Boolean,
    val outputIconRes: Int,
    val onPlayPause: () -> Unit,
    val onPrev: () -> Unit,
    val onNext: () -> Unit,
    val onSeek: (Long) -> Unit,
    val onAdd: () -> Unit,
    val onOutput: () -> Unit
)

/** Thin rounded progress line; tap or drag on it to seek. */
class IslandProgressView(ctx: android.content.Context) : View(ctx) {
    var fraction = 0f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }
    var onSeek: ((Float) -> Unit)? = null
    private var dragging = false
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x4DFFFFFF }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

    override fun onDraw(canvas: Canvas) {
        val h = if (dragging) height * 0.62f else height * 0.42f
        val top = (height - h) / 2f
        val r = h / 2f
        canvas.drawRoundRect(0f, top, width.toFloat(), top + h, r, r, track)
        canvas.drawRoundRect(0f, top, (width * fraction).coerceAtLeast(h), top + h, r, r, fill)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val f = (e.x / width.coerceAtLeast(1)).coerceIn(0f, 1f)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { dragging = true; parent?.requestDisallowInterceptTouchEvent(true); fraction = f }
            MotionEvent.ACTION_MOVE -> fraction = f
            MotionEvent.ACTION_UP -> { dragging = false; fraction = f; onSeek?.invoke(f) }
            MotionEvent.ACTION_CANCEL -> { dragging = false; invalidate() }
        }
        return true
    }
}

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
                val r = if (toolsMode && toolsShape != "pill" && toolsShape != "leaf") toolsRadius(view.height.toFloat())
                    else minOf(view.height / 2f, dp(40).toFloat())
                outline.setRoundRect(0, 0, view.width, view.height, r)
            }
        }
        clipToOutline = true
        elevation = dp(6).toFloat()
        // Real perspective for the 3D flips and tilts.
        cameraDistance = 9000f * act.resources.displayMetrics.density
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
    /** The cute face  °~°  shown in a round island when nothing else is happening. */
    private val face = IslandFace(act).apply { visibility = View.GONE }
    var faceOn = false
    /** Gilbert's size in dp, and what tapping him does. */
    var faceSize = 46
    var onFaceTap: (() -> Unit)? = null
    fun configureFace(s: Settings) { face.configure(s); faceSize = s.gilbertSize; mirror?.configureFace(s); if (face.visibility == View.VISIBLE) refresh() }
    /** How many notifications Gilbert is holding (red bubble). */
    fun setFaceBadge(n: Int) { face.badge = n; mirror?.setFaceBadge(n) }
    /** Gilbert's feelings: a base mood, short reactions, and where he looks. */
    fun setFaceMood(m: String) { face.baseMood = m; mirror?.setFaceMood(m) }
    fun faceReact(m: String, ms: Long = 1800) { face.react(m, ms); mirror?.faceReact(m, ms) }
    fun faceLookAt(x: Float, y: Float) { face.lookAt(x, y); mirror?.faceLookAt(x, y) }
    private fun showFace(show: Boolean) {
        if (show == (face.visibility == View.VISIBLE)) return
        if (show) {
            face.visibility = View.VISIBLE
            face.alpha = 0f; face.scaleX = 0.5f; face.scaleY = 0.5f
            face.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(80).setDuration(320)
                .setInterpolator(android.view.animation.OvershootInterpolator(1.5f)).start()
        } else face.animate().alpha(0f).scaleX(0.5f).scaleY(0.5f).setDuration(140)
            .withEndAction { face.visibility = View.GONE }.start()
    }
    /** Pop an app's logo in the round island (new message, notification, app update…). */
    fun peekIcon(icon: android.graphics.Bitmap?) {
        mirror?.peekIcon(icon)
        if (face.visibility == View.VISIBLE) { face.peek(icon); if (!overlayMode) SoundFX.play(act, "blip") }
    }

    // ---------------------------------------------------------------- Gilbert talks (speech bubble)
    private val bubble: TextView by lazy {
        TextView(act).apply {
            setTextColor(Color.WHITE); textSize = 13.5f; maxWidth = dp(270)
            setPadding(dp(14), dp(9), dp(14), dp(10))
            background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(0xF21C1C1E.toInt()); setStroke(maxOf(1, dp(1)), 0x2EFFFFFF) }
            elevation = dp(8).toFloat()
            visibility = View.GONE
            setOnClickListener { hideBubble.run() }
        }.also { root.addView(it, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL)) }
    }
    private val hideBubble: Runnable = Runnable {
        if (bubble.visibility == View.VISIBLE)
            bubble.animate().alpha(0f).scaleX(0.85f).scaleY(0.85f).setDuration(180).withEndAction { bubble.visibility = View.GONE }.start()
    }

    /** Gilbert says something in a little bubble under the island. */
    fun say(text: String) {
        mirror?.say(text)
        if (pill.visibility != View.VISIBLE || expanded || searching) return
        bubble.text = text
        (bubble.layoutParams as FrameLayout.LayoutParams).topMargin = topMargin + (pill.height.takeIf { it > 0 } ?: dp(40)) + dp(8)
        bubble.requestLayout()
        bubble.translationX = pill.translationX
        bubble.pivotY = 0f
        bubble.visibility = View.VISIBLE
        bubble.alpha = 0f; bubble.scaleX = 0.6f; bubble.scaleY = 0.6f; bubble.translationY = -dp(8).toFloat()
        bubble.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f).setDuration(320)
            .setInterpolator(android.view.animation.OvershootInterpolator(1.4f)).start()
        if (!overlayMode) SoundFX.play(act, "chirp")
        bubble.removeCallbacks(hideBubble)
        bubble.postDelayed(hideBubble, 6000)
    }

    // ---------------------------------------------------------------- held apps beside Gilbert
    /** Tap an app logo beside Gilbert: see what that app sent. */
    var onHeldTap: ((String) -> Unit)? = null
    private val heldRow: LinearLayout by lazy {
        LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE }
            .also { root.addView(it, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL)) }
    }

    /** Apps Gilbert is holding notifications for: (logo, count, package), shown next to him. */
    fun setHeld(items: List<Triple<android.graphics.Bitmap?, Int, String>>) {
        if (overlayMode) return
        val row = heldRow
        row.removeAllViews()
        for ((icon, n, pkg) in items.take(4)) {
            val f = FrameLayout(act).apply {
                setOnClickListener { buzz(); SoundFX.play(act, "pop"); onHeldTap?.invoke(pkg) }
            }
            f.addView(ImageView(act).apply {
                if (icon != null) setImageBitmap(icon) else { setImageResource(R.drawable.ic_chat); setColorFilter(Color.WHITE) }
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) = outline.setOval(0, 0, view.width, view.height)
                }
                clipToOutline = true
            }, FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER))
            f.addView(TextView(act).apply {
                text = if (n > 99) "99+" else "$n"; textSize = 9f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
                typeface = Typeface.DEFAULT_BOLD; setPadding(dp(3), 0, dp(3), 0)
                background = GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(0xFFFF3B30.toInt()) }
                minWidth = dp(15)
            }, FrameLayout.LayoutParams(WRAP_CONTENT, dp(15), Gravity.TOP or Gravity.END))
            row.addView(f, LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginStart = dp(3) })
        }
        val show = items.isNotEmpty() && pill.visibility == View.VISIBLE
        if (show && row.visibility != View.VISIBLE) {
            row.visibility = View.VISIBLE; row.alpha = 0f; row.scaleX = 0.6f; row.scaleY = 0.6f
            row.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(android.view.animation.OvershootInterpolator(1.3f)).start()
        } else if (!show) row.visibility = View.GONE
        row.post { positionSide() }
    }

    /** Keep the held-app logos just to the right of the island. */
    private fun positionSide() {
        if (overlayMode) return
        val row = heldRow
        if (row.visibility != View.VISIBLE) return
        val w = (if (targetW > 0) targetW else pill.width) * pill.scaleX
        val h = (if (targetH > 0) targetH else pill.height) * pill.scaleY
        (row.layoutParams as FrameLayout.LayoutParams).topMargin = topMargin + ((h - dp(32)) / 2).toInt().coerceAtLeast(0)
        row.requestLayout()
        row.animate().translationX(pill.translationX + w / 2f + dp(8) + row.width / 2f).setDuration(220).start()
    }

    /** The oblong tool strip shown when idle. */
    private val toolsRow = object : LinearLayout(act) {
        override fun onInterceptTouchEvent(e: MotionEvent) = toolsTouch(e)
        override fun onTouchEvent(e: MotionEvent): Boolean { toolsTouch(e); return true }
    }.apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
        setPadding(dp(8), 0, dp(8), 0)
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
        pill.addView(compact, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT, Gravity.TOP))
        pill.addView(expandedView, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP))
        pill.addView(toolsRow, FrameLayout.LayoutParams(MATCH_PARENT, dp(36), Gravity.TOP))
        pill.addView(face, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.addView(pill, FrameLayout.LayoutParams(dp(120), dp(36), Gravity.TOP or Gravity.CENTER_HORIZONTAL))

        val detector = GestureDetector(act, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (expanded) collapse() else if (current() == null) {
                    // Tap Gilbert: his card. (The tools still open from the camera strip.)
                    val gil = onFaceTap
                    if (faceOn && face.visibility == View.VISIBLE && gil != null) { buzz(); gil() }
                    else if (toolsOn) toggleTools() else showQuick()
                } else expand()
                return true
            }
            override fun onLongPress(e: MotionEvent) {
                buzz()
                if (expanded) { collapse(); onVolume(); return }
                // Over other apps: hold and drag to move the island out of the way (the whole window moves).
                if (overlayMode && onWindowDrag != null) {
                    windowDragging = true
                    Motion.scaleTo(pill, sizeScale * 1.08f, Motion.SNAPPY)
                    return
                }
                if (overlayMode) { collapse(); onVolume(); return }
                // Long-press and drag to move the island; let go without moving for volume.
                dragging = true
                Motion.scaleTo(pill, sizeScale * 1.12f, Motion.SNAPPY)
            }
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (e1 == null) return false
                val dx = e2.x - e1.x; val dy = e2.y - e1.y
                if (abs(dy) > abs(dx) && dy > dp(30)) {
                    // Pull down: quick controls (the assistant is one of the buttons).
                    if (quickActions != null) { if (!quickMode) { collapse(false); showQuick() } }
                    else { buzz(); collapse(); onAssistant() }
                    return true
                }
                if (abs(dx) > abs(dy) && abs(dx) > dp(30)) {
                    cycle(if (dx < 0) 1 else -1); return true
                }
                if (dy < -dp(30) && expanded) { collapse(); return true }
                return false
            }
        })
        pill.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> v.pivotX = v.width / 2f; v.pivotY = 0f }
        pill.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN) { downRawX = e.rawX; downRawY = e.rawY }
            if (windowDragging) windowDrag(e) else if (dragging) drag(e) else detector.onTouchEvent(e)
            true
        }
    }

    /** True if a screen point lands on the island (so the home swipe-down ignores it). */
    fun hit(rawX: Float, rawY: Float): Boolean {
        if (pill.visibility != View.VISIBLE) return false
        val loc = IntArray(2)
        pill.getLocationOnScreen(loc)
        return rawX >= loc[0] - dp(10) && rawX <= loc[0] + pill.width + dp(10) &&
            rawY >= loc[1] - dp(10) && rawY <= loc[1] + pill.height + dp(14)
    }

    /** Over other apps: moves the island's own window (dx, dy since the press; done = let go). */
    var onWindowDrag: ((Float, Float, Boolean) -> Unit)? = null
    private var windowDragging = false

    private fun windowDrag(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> onWindowDrag?.invoke(e.rawX - downRawX, e.rawY - downRawY, false)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                windowDragging = false
                Motion.scaleTo(pill, sizeScale, Motion.BOUNCY)
                buzz()
                onWindowDrag?.invoke(e.rawX - downRawX, e.rawY - downRawY, true)
            }
        }
    }

    private fun drag(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                pill.translationX = offsetX + (e.rawX - downRawX)
                pill.translationY = e.rawY - downRawY
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                val dx = pill.translationX - offsetX
                val dy = pill.translationY
                Motion.scaleTo(pill, sizeScale, Motion.BOUNCY)
                if (abs(dx) < dp(8) && abs(dy) < dp(8)) {
                    pill.translationX = offsetX; pill.translationY = 0f
                    onVolume()
                } else {
                    buzz()
                    offsetX += dx
                    onMoved?.invoke(dx, dy)
                }
            }
        }
    }

    fun setTopInset(px: Int) {
        pill.translationY = 0f
        topMargin = px
        (pill.layoutParams as FrameLayout.LayoutParams).topMargin = px
        pill.requestLayout()
    }

    /** Decides which activities may appear (from Settings › Island Content). */
    var allow: (String) -> Boolean = { true }
    /** Length of short pop-ups (0 = use the caller's own length). */
    var popupMs = 0L

    fun ids(): Set<String> = activities.keys.toSet()

    // ---- Look & feel (Settings › Dynamic Island)
    /** Size of the small island, 0.8..1.3. */
    var sizeScale = 1f
    /** black | glass | outline | color */
    var style = "black"
    /** spring | fade | slide | zoom */
    var anim = "spring"
    /** Sideways position (px) from the centre. */
    var offsetX = 0f
    /** Called after you long-press and drag the island somewhere new (dx, dy in px). */
    var onMoved: ((Float, Float) -> Unit)? = null

    private var dragging = false
    private var downRawX = 0f
    private var downRawY = 0f

    /** Re-apply size, colour style and position. */
    fun applyLook() {
        val r = (pill.background as? GradientDrawable)?.cornerRadius ?: dp(18).toFloat()
        pill.background = GradientDrawable().apply {
            cornerRadius = r
            when (style) {
                // Open (music player, etc.): nearly solid so nothing behind shows through the text.
                "glass" -> { setColor(Color.argb(if (expanded) 248 else 175, 18, 18, 20)); setStroke(maxOf(1, dp(1)), Color.argb(80, 255, 255, 255)) }
                "outline" -> { setColor(Color.BLACK); setStroke(maxOf(2, dp(2)), ThemeEngine.alpha(ThemeEngine.accent, 220)) }
                "color" -> setColor(IconFactory.blend(Color.BLACK, ThemeEngine.accent, 0.38f))
                else -> setColor(Color.BLACK)
            }
        }
        if (!expanded && !dragging && !toolsMode) {
            pill.pivotY = 0f
            pill.scaleX = sizeScale; pill.scaleY = sizeScale
            pill.translationX = offsetX
        }
        if (toolsMode) applyToolsLook()
    }

    /** Drops anything that is no longer allowed. */
    fun applyFilter() {
        mirror?.applyFilter()
        val gone = activities.keys.filter { !allow(it) }
        if (gone.isNotEmpty()) { gone.forEach { activities.remove(it) }; refresh() }
    }

    /** Keep a small empty island at the top even when nothing is happening (like iPhone). */
    var alwaysShow = false
    /** Tools shown in the oblong island when nothing is playing (null = off). */
    var idleTools: (() -> List<QuickAction>)? = null
    // Tools bar look (set from Settings › Dynamic Island › Island Tools).
    var toolsScale = 1f
    var toolsColor = Color.BLACK
    var toolsStroke = 0
    var toolsIcon = Color.WHITE
    var toolsShape = "pill"
    var toolsAngle = 0f
    var toolsDx = 0f
    var toolsDy = 0f
    /** Called after you drag the tools bar somewhere new (dx, dy in px from its normal spot). */
    var onToolsMoved: ((Float, Float) -> Unit)? = null
    private var toolsMode = false
    private val toolsOn get() = idleTools != null
    /** Buttons shown when you pull the island down (flashlight, silent, rotation…). */
    var quickActions: (() -> List<QuickAction>)? = null
    private var quickMode = false

    /** A second island (floating over other apps) that copies everything shown here. */
    var mirror: DynamicIsland? = null
    /** True for the floating copy: it lives in its own small window over other apps. */
    var overlayMode = false
    /** Called when the island appears (true) or goes away (false). */
    var onShownChanged: ((Boolean) -> Unit)? = null
    /** Is the island on screen right now? */
    fun shownNow() = pill.visibility == View.VISIBLE

    fun snapshot(): List<IslandActivity> = activities.values.toList()

    private fun screenW() = if (overlayMode) act.resources.displayMetrics.widthPixels else root.width

    fun put(a: IslandActivity) {
        mirror?.put(a)
        putLocal(a)
    }

    /** Music apps hand over a fresh copy of the same album art every update — treat it as the same picture. */
    private fun sameIcon(x: Bitmap?, y: Bitmap?): Boolean {
        if (x === y) return true
        if (x == null || y == null || x.width != y.width || x.height != y.height) return false
        return runCatching { x.sameAs(y) }.getOrDefault(false)
    }

    /** Same layout as before (only text/time changed)? Then we can update in place. */
    private fun sameShape(o: IslandActivity, a: IslandActivity) =
        sameIcon(o.icon, a.icon) && o.iconRes == a.iconRes && o.title == a.title && o.showBars == a.showBars &&
            (o.media == null) == (a.media == null) && o.actions.map { it.first } == a.actions.map { it.first }

    private fun putLocal(a: IslandActivity) {
        if (!allow(a.id)) { removeLocal(a.id); return }
        // Fast path: a ticking timer/clock just changes its numbers — no relayout, no rebuild.
        val old = activities[a.id]
        if (old != null && sameShape(old, a) && pill.visibility == View.VISIBLE && enabled) {
            activities[a.id] = a
            if (current() === a) {
                if (!expanded) {
                    bind(a)
                    val w = compactWidth(a)
                    val h = compactHeight(a)
                    if (abs(w - pill.width) > dp(6) || abs(h - pill.height) > dp(4)) animateSize(w, h, dp(18).toFloat())
                } else if (a.media == null) {
                    expSubtitle?.text = a.subtitle
                }
            }
            return
        }
        val isNew = !activities.containsKey(a.id)
        activities[a.id] = a
        if (isNew) {
            hidden.remove(a.id)
            // Info (weather, steps…) never takes the spot of music, timers or calls.
            if (a.id != "info" || activities.size == 1) index = activities.keys.indexOf(a.id)
        }
        while (activities.size > 3) activities.remove(activities.keys.first())
        refresh()
    }

    fun remove(id: String) {
        mirror?.remove(id)
        removeLocal(id)
    }

    private fun removeLocal(id: String) {
        if (activities.remove(id) != null) refresh()
    }

    /** Show something briefly (e.g. charging started), then let it go. */
    fun flash(a: IslandActivity, millis: Long) {
        mirror?.flash(a, millis)
        if (!allow(a.id)) return
        putLocal(a)
        val ms = if (popupMs > 0) popupMs else millis
        pill.postDelayed({ if (activities[a.id] === a) removeLocal(a.id) }, ms)
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
        // Flip like a card turning to the next live activity.
        compact.cameraDistance = 8000f * act.resources.displayMetrics.density
        spin(compact, DynamicAnimation.ROTATION_Y, 55f * dir, 320f, 0.82f)
    }

    /** Spring a rotation (3D) like the rest of the island's motion. */
    private fun spin(v: View, prop: DynamicAnimation.ViewProperty, from: Float, stiff: Float = 320f, damp: Float = 0.62f) {
        if (Motion.scale <= 0f) { prop.setValue(v, 0f); return }
        prop.setValue(v, from)
        SpringAnimation(v, prop, 0f).apply {
            spring.stiffness = (stiff / (Motion.scale * Motion.scale)).coerceIn(30f, 5000f)
            spring.dampingRatio = damp
            start()
        }
    }

    /** Contents of the big island cascade in, each tipping forward in 3D. */
    private fun cascade(group: android.view.ViewGroup) {
        if (Motion.scale <= 0f) return
        for (i in 0 until group.childCount) {
            val ch = group.getChildAt(i)
            ch.cameraDistance = 8000f * act.resources.displayMetrics.density
            ch.alpha = 0f; ch.translationY = -dp(8).toFloat(); ch.rotationX = -25f
            ch.animate().alpha(1f).translationY(0f).rotationX(0f)
                .setStartDelay(((90 + i * 45) * Motion.scale).toLong())
                .setDuration((400 * Motion.scale).toLong())
                .setInterpolator(android.view.animation.PathInterpolator(0.2f, 0.9f, 0.1f, 1f)).start()
        }
    }

    private fun buzz() {
        if (haptics()) pill.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
    }

    private fun current(): IslandActivity? {
        val list = visibleList()
        if (list.isEmpty()) return null
        index = index.coerceIn(0, list.size - 1)
        // The info pill (weather, steps…) steps aside while the tools are open.
        if (toolsOpen && list[index].id == "info") return null
        return list[index]
    }

    private fun refresh() {
        val a = current()
        if (!enabled || (a == null && !alwaysShow && !toolsOn && !faceOn)) {
            toolsRow.visibility = View.GONE
            if (expanded) collapse(animate = false)
            if (pill.visibility == View.VISIBLE) {
                val a = pill.animate().alpha(0f).setDuration((280 * Motion.scale).toLong())
                    .setInterpolator(android.view.animation.PathInterpolator(0.4f, 0f, 0.2f, 1f))
                when (anim) {
                    "fade" -> {}
                    "slide" -> a.translationY(-dp(50).toFloat())
                    "3d" -> a.rotationX(-55f).scaleX(0.88f * sizeScale).scaleY(0.88f * sizeScale)
                    else -> a.scaleX(0.7f * sizeScale).scaleY(0.7f * sizeScale)
                }
                a.withEndAction { pill.visibility = View.GONE; pill.translationY = 0f; OverlayZones.setIsland(0); onShownChanged?.invoke(false) }.start()
            }
            return
        }
        if (a == null) compact.visibility = View.INVISIBLE else { compact.visibility = View.VISIBLE; bind(a) }
        if (pill.visibility != View.VISIBLE) {
            pill.visibility = View.VISIBLE
            onShownChanged?.invoke(true)
            pill.alpha = 0f
            pill.translationX = offsetX
            when (anim) {
                "fade" -> {
                    pill.scaleX = sizeScale; pill.scaleY = sizeScale
                    pill.animate().alpha(1f).setDuration((360 * Motion.scale).toLong())
                        .setInterpolator(android.view.animation.DecelerateInterpolator(1.6f)).start()
                }
                "slide" -> {
                    pill.scaleX = sizeScale; pill.scaleY = sizeScale
                    Motion.spring(pill, DynamicAnimation.TRANSLATION_Y, 0f, Motion.FLUID, start = -dp(50).toFloat())
                    Motion.alphaTo(pill, 1f, Motion.SNAPPY)
                }
                "3d" -> {
                    // Swings down from the top edge in 3D, then settles with a soft bounce.
                    pill.scaleX = sizeScale * 0.92f; pill.scaleY = sizeScale * 0.92f
                    spin(pill, DynamicAnimation.ROTATION_X, -60f, 220f, 0.74f)
                    Motion.scaleTo(pill, sizeScale, Motion.FLUID)
                    pill.animate().alpha(1f).setDuration((300 * Motion.scale).toLong())
                        .setInterpolator(android.view.animation.DecelerateInterpolator(1.6f)).start()
                }
                "zoom" -> {
                    pill.scaleX = 0f; pill.scaleY = 0f
                    pill.animate().scaleX(sizeScale).scaleY(sizeScale).alpha(1f)
                        .setInterpolator(android.view.animation.PathInterpolator(0.2f, 0.9f, 0.1f, 1f))
                        .setDuration((380 * Motion.scale).toLong()).start()
                }
                else -> {
                    pill.scaleX = 0.6f * sizeScale; pill.scaleY = 0.6f * sizeScale
                    Motion.scaleTo(pill, sizeScale, Motion.FLUID)
                    Motion.alphaTo(pill, 1f, Motion.SNAPPY)
                }
            }
        }
        if (quickMode) return
        showTools(a == null && !expanded && toolsOn && toolsOpen)
        showFace(a == null && !expanded && faceOn && !(toolsOn && toolsOpen))
        if (!expanded) animateSize(if (a == null) idleW() else compactWidth(a), if (a == null) idleH() else compactHeight(a),
            if (a == null) idleH() / 2f else dp(18).toFloat())
        else if (a != null) bindExpanded(a) else collapse()
        parkAtCamera(a == null && !expanded && toolsOn && !toolsOpen && !faceOn)
    }

    /** While Search is open the island (tools) fades away, and comes back when it closes. */
    fun setSearching(on: Boolean) {
        searching = on
        pill.animate().cancel()
        if (pill.visibility != View.VISIBLE) return
        pill.animate().alpha(if (on) 0f else 1f).scaleX(sizeScale * (if (on) 0.9f else 1f)).scaleY(sizeScale * (if (on) 0.9f else 1f))
            .setDuration((if (on) 180L else 280L)).start()
        pill.isEnabled = !on
    }
    private var searching = false

    private fun idleW() = when {
        toolsOn && toolsOpen -> dp(16) + toolCount * dp(34)
        faceOn -> dp(faceSize)           // a round island with Gilbert
        toolsOn -> camera?.let { maxOf(dp(46), it.width() + dp(22)) } ?: dp(48)   // tucked around the front camera
        else -> dp(118)
    }
    private fun idleH() = if (faceOn && !(toolsOn && toolsOpen)) dp(faceSize) else if (toolsOn && !toolsOpen) camera?.let { maxOf(dp(32), it.height() + dp(14)) } ?: dp(36) else dp(36)

    /**
     * A bigger invisible tap area around and below the camera pill: the status bar takes taps
     * at the very top of the screen, so this reaches down past it and is easy to hit.
     */
    private val hotspot by lazy {
        View(act).apply {
            isClickable = true
            setOnClickListener { if (toolsOn && !toolsOpen && !expanded && current() == null) toggleTools() }
            visibility = View.GONE
        }.also { h ->
            root.addView(h, root.indexOfChild(pill), FrameLayout.LayoutParams(dp(150), dp(72), Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        }
    }

    /** The front camera hole on screen (from the display cutout), so the closed tools hide around it. */
    /** Fine-tune where the tap area sits (px). */
    var hotDx = 0f
    var hotDy = 0f
    var camera: android.graphics.Rect? = null
        set(v) { field = v; if (toolsOn && !toolsOpen && !expanded && current() == null) pill.post { refresh() } }

    /** Closed tools sit exactly on the camera; open (or anything else showing) uses the island's spot. */
    private fun parkAtCamera(on: Boolean) {
        val cam = camera
        // The big tap area sits under the closed pill (around the camera or the island's spot).
        hotspot.visibility = if (on) View.VISIBLE else View.GONE
        (hotspot.layoutParams as FrameLayout.LayoutParams).topMargin = topMargin
        if (!on || cam == null) {
            hotspot.translationX = offsetX + hotDx; hotspot.translationY = -dp(14).toFloat() + hotDy
            if (pill.translationY != 0f && !dragging) pill.animate().translationY(0f).translationX(offsetX)
                .setDuration((280 * Motion.scale).toLong()).start()
            return
        }
        val loc = IntArray(2); root.getLocationOnScreen(loc)
        val cx = loc[0] + root.width / 2f
        val cy = loc[1] + topMargin + idleH() * sizeScale / 2f
        hotspot.translationX = cam.exactCenterX() - cx + hotDx
        hotspot.translationY = (cam.top - dp(10)) - (loc[1] + topMargin).toFloat() + hotDy
        pill.animate().translationX(cam.exactCenterX() - cx).translationY(cam.exactCenterY() - cy)
            .setDuration((280 * Motion.scale).toLong()).setInterpolator(android.view.animation.PathInterpolator(0.2f, 0.9f, 0.1f, 1f)).start()
    }

    /** Tools open from the little island around the camera, and tuck back after a few seconds. */
    private var toolsOpen = false
    private val closeTools = Runnable { if (toolsOpen) { toolsOpen = false; refresh() } }
    fun toggleTools() {
        if (!toolsOn || expanded || (current() != null && current()?.id != "info")) return
        SoundFX.play(act, "pop")
        toolsOpen = !toolsOpen
        buzz()
        pill.removeCallbacks(closeTools)
        if (toolsOpen) pill.postDelayed(closeTools, 6000)
        refresh()
    }
    private var toolCount = 0

    // ---- moving the tools bar: hold it, then drag anywhere
    private var tDown = false; private var tDrag = false
    private var tX = 0f; private var tY = 0f
    private val tLift = Runnable {
        tDrag = true
        buzz()
        pill.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        Motion.scaleTo(pill, sizeScale * toolsScale * 1.08f, Motion.SNAPPY)
        // Let go of the button under the finger.
        val now = android.os.SystemClock.uptimeMillis()
        val c = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
        for (i in 0 until toolsRow.childCount) toolsRow.getChildAt(i).dispatchTouchEvent(c)
        c.recycle()
    }
    private fun toolsTouch(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                tDown = true; tDrag = false; tX = e.rawX; tY = e.rawY
                // Using the tools keeps them open a little longer.
                pill.removeCallbacks(closeTools); pill.postDelayed(closeTools, 6000)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!tDrag && abs(e.rawX - tX) + abs(e.rawY - tY) > dp(10)) toolsRow.removeCallbacks(tLift)
                if (tDrag) {
                    pill.translationX = offsetX + toolsDx + (e.rawX - tX)
                    pill.translationY = toolsDy + (e.rawY - tY)
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                toolsRow.removeCallbacks(tLift); tDown = false
                if (tDrag) {
                    tDrag = false
                    toolsDx += e.rawX - tX; toolsDy += e.rawY - tY
                    Motion.scaleTo(pill, sizeScale * toolsScale, Motion.BOUNCY)
                    onToolsMoved?.invoke(toolsDx, toolsDy)
                    buzz()
                    return true
                }
            }
        }
        return tDrag
    }

    private fun toolsRadius(h: Float): Float = when (toolsShape) {
        "square" -> dp(6).toFloat(); "rounded" -> dp(12).toFloat(); else -> h / 2f
    }

    /** Dress the island as the tools bar: colour, shape, size, tilt and position. */
    fun applyToolsLook() {
        if (!toolsMode) return
        val h = dp(36).toFloat()
        pill.background = GradientDrawable().apply {
            setColor(toolsColor)
            if (toolsStroke != 0) setStroke(maxOf(1, dp(1)), toolsStroke)
            if (toolsShape == "leaf") cornerRadii = floatArrayOf(h / 2, h / 2, dp(4).toFloat(), dp(4).toFloat(), h / 2, h / 2, dp(4).toFloat(), dp(4).toFloat())
            else cornerRadius = toolsRadius(h)
        }
        pill.invalidateOutline()
        for (i in 0 until toolsRow.childCount) ((toolsRow.getChildAt(i) as? FrameLayout)?.getChildAt(0) as? ImageView)?.let { iv ->
            if (iv.tag != "on") iv.setColorFilter(toolsIcon)
        }
        if (!tDrag) {
            pill.animate().rotation(toolsAngle).translationX(offsetX + toolsDx).translationY(toolsDy)
                .setDuration((320 * Motion.scale).toLong()).setInterpolator(android.view.animation.PathInterpolator(0.2f, 0.9f, 0.1f, 1f)).start()
            Motion.scaleTo(pill, sizeScale * toolsScale, Motion.FLUID)
        }
    }

    /** Back to the normal island look and spot. */
    private fun leaveToolsLook() {
        if (!toolsMode) return
        toolsMode = false
        applyLook()
        pill.invalidateOutline()
        pill.animate().rotation(0f).translationY(0f).setDuration((260 * Motion.scale).toLong()).start()
        if (!expanded) { pill.animate().translationX(offsetX); Motion.scaleTo(pill, sizeScale, Motion.FLUID) }
    }

    /** Fill / show the oblong tool row (only while idle and folded). */
    private fun showTools(show: Boolean) {
        if (!show) {
            leaveToolsLook()
            if (toolsRow.visibility == View.VISIBLE) toolsRow.animate().alpha(0f).setDuration(120)
                .withEndAction { toolsRow.visibility = View.GONE }.start()
            return
        }
        toolsMode = true
        val list = idleTools?.invoke().orEmpty()
        toolCount = list.size
        toolsRow.removeAllViews()
        for (q in list) {
            val on = q.active()
            val b = FrameLayout(act).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (on) Color.WHITE else 0x00000000) }
                contentDescription = q.label
                setOnClickListener { v ->
                    buzz()
                    v.animate().scaleX(0.8f).scaleY(0.8f).setDuration(80).withEndAction { Motion.scaleTo(v, 1f, Motion.BOUNCY) }.start()
                    // The gear spins as it opens the launcher settings.
                    if (q.label == "Settings") (v as? FrameLayout)?.getChildAt(0)?.animate()?.rotationBy(360f)?.setDuration(520)
                        ?.setInterpolator(android.view.animation.PathInterpolator(0.2f, 0.9f, 0.1f, 1f))?.start()
                    q.onTap()
                    v.postDelayed({ if (toolsRow.visibility == View.VISIBLE && !expanded) showTools(true) }, 600)
                }
            }
            b.addView(ImageView(act).apply {
                setImageResource(q.iconRes); setColorFilter(if (on) 0xFF1C1C1E.toInt() else toolsIcon); tag = if (on) "on" else null
            }, FrameLayout.LayoutParams(dp(17), dp(17), Gravity.CENTER))
            toolsRow.addView(b, LinearLayout.LayoutParams(dp(30), dp(30)).apply { marginStart = dp(2); marginEnd = dp(2) })
        }
        applyToolsLook()
        if (toolsRow.visibility != View.VISIBLE) {
            toolsRow.visibility = View.VISIBLE
            toolsRow.alpha = 0f
            toolsRow.animate().alpha(1f).setStartDelay(80).setDuration(220).start()
            for (i in 0 until toolsRow.childCount) {
                val c = toolsRow.getChildAt(i)
                c.scaleX = 0.5f; c.scaleY = 0.5f
                c.animate().scaleX(1f).scaleY(1f).setStartDelay(100L + i * 30L).setDuration(260)
                    .setInterpolator(android.view.animation.PathInterpolator(0.2f, 0.9f, 0.1f, 1f)).start()
            }
        }
    }

    /** Pull-down quick controls: round buttons either side of a close chevron (iOS concept). */
    fun showQuick() {
        val acts = quickActions?.invoke() ?: return
        if (expanded && !quickMode) collapse(false)
        quickMode = true
        expanded = true
        buzz()
        showFace(false)
        showTools(false)
        bindQuick(acts)
        compact.animate().alpha(0f).setDuration(160).start()
        expandedView.visibility = View.VISIBLE
        expandedView.alpha = 0f
        expandedView.animate().alpha(1f).setStartDelay(90).setDuration(220).start()
        expandedView.measure(
            View.MeasureSpec.makeMeasureSpec(expandedW(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        Motion.scaleTo(pill, 1f, Motion.FLUID)
        pill.animate().translationX(0f).setDuration((260 * Motion.scale).toLong()).start()
        animateSize(expandedW(), expandedView.measuredHeight, dp(32).toFloat())
        spin(pill, DynamicAnimation.ROTATION_X, -8f, 260f, 0.76f)
        (expandedView.getChildAt(0) as? android.view.ViewGroup)?.let { cascade(it) }
        if (!overlayMode && catcher == null) {
            val c = View(act).apply { isClickable = true; setOnClickListener { collapse() } }
            root.addView(c, root.indexOfChild(pill), FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            catcher = c
        }
    }

    private fun bindQuick(acts: List<QuickAction>) {
        expandedView.removeAllViews()
        expandedView.setPadding(dp(12), dp(12), dp(12), dp(12))
        val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val half = (acts.size + 1) / 2
        fun btn(q: QuickAction): View {
            val on = q.active()
            val f = FrameLayout(act).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (on) Color.WHITE else 0xFF2C2C2E.toInt()) }
                contentDescription = q.label
                setOnClickListener { v ->
                    buzz()
                    v.animate().scaleX(0.82f).scaleY(0.82f).setDuration(80).withEndAction { Motion.scaleTo(v, 1f, Motion.BOUNCY) }.start()
                    q.onTap()
                    v.postDelayed({ if (quickMode) bindQuick(quickActions?.invoke() ?: acts) }, 220)
                }
            }
            f.addView(ImageView(act).apply {
                setImageResource(q.iconRes); setColorFilter(if (on) 0xFF1C1C1E.toInt() else Color.WHITE)
            }, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
            return f
        }
        for (q in acts.take(half)) row.addView(btn(q), LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(8) })
        row.addView(View(act), LinearLayout.LayoutParams(0, 0, 1f))
        // Centre: close chevron
        row.addView(FrameLayout(act).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFF2C2C2E.toInt()) }
            setOnClickListener { collapse() }
            addView(ImageView(act).apply { setImageResource(R.drawable.ic_set_chevron); setColorFilter(Color.WHITE); rotation = 90f },
                FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER))
        }, LinearLayout.LayoutParams(dp(40), dp(40)))
        row.addView(View(act), LinearLayout.LayoutParams(0, 0, 1f))
        for (q in acts.drop(half)) row.addView(btn(q), LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(8) })
        expandedView.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }

    /** Width the lyric would need on one line. */
    private fun lyricNeed(a: IslandActivity): Int {
        val text = label.text?.toString().orEmpty()
        val dots = if (visibleList().size > 1) dp(10) else 0
        val bars = if (a.showBars) dp(28) else 0
        return dp(8) + dp(22) + dp(8) + kotlin.math.ceil(label.paint.measureText(text)).toInt() + bars + dp(12) + dp(10) + dots
    }

    /** Small island height: taller (two lines) when a lyric is too long for one line, so you can read all of it. */
    private fun compactHeight(a: IslandActivity): Int {
        if (a.media == null || label.text.isNullOrEmpty()) { label.maxLines = 1; return dp(36) }
        val long = lyricNeed(a) > screenW() - dp(40)
        label.maxLines = if (long) 2 else 1
        return if (long) dp(54) else dp(36)
    }

    private fun compactWidth(a: IslandActivity): Int {
        if (a.media != null) {
            val dots = if (visibleList().size > 1) dp(10) else 0
            val text = label.text?.toString().orEmpty()
            if (text.isEmpty()) return dp(172) + dots
            // padding 8 + art 22 + gap 8 + text + gap 6 + bars 22 + padding 12 (+ a little room)
            val bars = if (a.showBars) dp(28) else 0
            val need = dp(8) + dp(22) + dp(8) + kotlin.math.ceil(label.paint.measureText(text)).toInt() + bars + dp(12) + dp(10) + dots
            return need.coerceIn(dp(172) + dots, screenW() - dp(40))
        }
        val textW = label.paint.measureText(a.title).toInt().coerceAtMost(dp(150))
        val trailW = a.trailing?.let { trailing.paint.measureText(it).toInt() + dp(6) } ?: 0
        val barsW = if (a.showBars) dp(28) else 0
        val count = visibleList().size
        val dotsW = if (count > 1) dp(10) else 0
        return (dp(8) + dp(22) + dp(8) + textW + barsW + trailW + dp(14) + dotsW)
            .coerceIn(dp(120), screenW().coerceAtLeast(dp(300)) - dp(60))
    }

    private var boundIcon: Bitmap? = null
    private fun bind(a: IslandActivity) {
        if (a.icon != null) { if (!sameIcon(boundIcon, a.icon)) { icon.setImageBitmap(a.icon); icon.clearColorFilter(); boundIcon = a.icon } }
        else { icon.setImageResource(a.iconRes); icon.setColorFilter(a.iconTint); boundIcon = null }
        // Music: keep the lyric that's showing (the island is re-bound on every music update —
        // clearing it each time made it flicker).
        if (a.media == null) {
            label.text = a.title
            if (label.ellipsize != TextUtils.TruncateAt.END) { label.ellipsize = TextUtils.TruncateAt.END; label.isSelected = false }
            pill.removeCallbacks(compactLyrics); lyricSongKey = null; lyricRunning = false
        } else {
            val key = "${a.title}|${a.subtitle}"
            if (key != lyricSongKey) { lyricSongKey = key; label.text = "" }
            // The island widens to fit the whole lyric line (no sideways scrolling back and forth,
            // and nothing re-set on every music update — that caused the flicker).
            if (label.ellipsize != TextUtils.TruncateAt.END) { label.ellipsize = TextUtils.TruncateAt.END; label.isSelected = false }
            if (Lyrics.enabled && !lyricRunning) { lyricRunning = true; pill.post(compactLyrics) }
        }
        trailing.text = a.trailing ?: ""
        trailing.setTextColor(a.accent)
        trailing.visibility = if (a.trailing == null) View.GONE else View.VISIBLE
        bars.color = a.accent
        bars.visibility = if (a.showBars) View.VISIBLE else View.GONE
    }

    private var ticker: Runnable? = null
    private var expSubtitle: TextView? = null

    private var lyricSongKey: String? = null
    private var lyricRunning = false

    /** Small island + music: show the current lyric line next to the art (fades as lines change). */
    private val compactLyrics: Runnable = object : Runnable {
        override fun run() {
            val a = current()
            val m = a?.media
            if (a == null || m == null || !Lyrics.enabled) { lyricRunning = false; return }
            if (!expanded) {
                val song = Lyrics.cached(a.title, a.subtitle)
                if (song == null) Lyrics.fetch(a.title, a.subtitle, m.duration) { }
                else {
                    val line = if (song.synced.isNotEmpty()) Lyrics.lineAt(song, m.position() + Lyrics.LEAD_MS) else ""
                    val show = if (line == "♪") "" else line
                    if (label.text.toString() != show) {
                        // Old line drifts up and out, the new one rises in — no jump.
                        label.animate().cancel()
                        label.animate().alpha(0f).translationY(-dp(5).toFloat()).setDuration(120).withEndAction {
                            label.text = show
                            label.translationY = dp(5).toFloat()
                            label.animate().alpha(1f).translationY(0f).setDuration(240)
                                .setInterpolator(android.view.animation.DecelerateInterpolator(2f)).start()
                            // Grow / shrink smoothly so the whole line fits.
                            val c = current()
                            if (!expanded && c?.media != null) {
                                val w = compactWidth(c)
                                val h = compactHeight(c)
                                if (abs(w - pill.width) > dp(6) || abs(h - pill.height) > dp(4)) animateSize(w, h, dp(18).toFloat())
                            }
                        }.start()
                    }
                }
            }
            pill.postDelayed(this, 250)
        }
    }

    private fun fmtTime(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return if (s >= 3600) String.format("%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
        else String.format("%02d:%02d", s / 60, s % 60)
    }

    /** iPhone-style expanded Now Playing: artwork, title, progress and controls. */
    private fun bindMusic(a: IslandActivity, m: IslandMedia) {
        expandedView.setPadding(dp(22), dp(20), dp(22), dp(14))
        // Spotify / ArchiveTune look: the album art, blurred, glowing behind the player.
        expandedView.background = blurredArt(a.icon)?.let { b ->
            android.graphics.drawable.BitmapDrawable(act.resources, b).apply { alpha = 110 }
        }
        expandedView.clipToOutline = true
        expandedView.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, dp(40).toFloat())
        }
        val head = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val artFrame = FrameLayout(act)
        artFrame.addView(ImageView(act).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            if (a.icon != null) setImageBitmap(a.icon) else { setImageResource(a.iconRes); setColorFilter(a.iconTint)
                background = GradientDrawable().apply { setColor(0xFF2C2C2E.toInt()) } }
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) =
                    outline.setRoundRect(0, 0, view.width, view.height, dp(14).toFloat())
            }
            clipToOutline = true
        }, FrameLayout.LayoutParams(dp(66), dp(66)))
        if (m.appIcon != null) {
            artFrame.addView(ImageView(act).apply {
                setImageBitmap(m.appIcon)
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) = outline.setOval(0, 0, view.width, view.height)
                }
                clipToOutline = true
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.BLACK) }
                setPadding(dp(2), dp(2), dp(2), dp(2))
            }, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.BOTTOM or Gravity.END))
        }
        head.addView(artFrame, LinearLayout.LayoutParams(dp(72), dp(72)))
        val texts = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(act).apply {
            text = a.title; setTextColor(Color.WHITE); textSize = 21f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD); maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        texts.addView(TextView(act).apply {
            text = a.subtitle; setTextColor(0x99FFFFFF.toInt()); textSize = 16f; maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        head.addView(texts, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = dp(14) })
        head.addView(ImageView(act).apply {
            setImageResource(m.outputIconRes)
            setOnClickListener { buzz(); m.onOutput() }
        }, LinearLayout.LayoutParams(dp(30), dp(30)).apply { gravity = Gravity.TOP; topMargin = dp(2) })
        expandedView.addView(head)
        // Live lyrics (when the song is in the lyrics library).
        val lyr = if (Lyrics.enabled) LyricsView(act, big = true).also {
            it.clear("♪"); it.visibility = View.GONE
            // Safety: if the lyrics ever change height, re-fit the open island so the buttons never get cut.
            it.addOnLayoutChangeListener { _, _, t, _, b, _, ot, _, ob -> if (b - t != ob - ot && expanded) pill.post { relayoutExpanded() } }
            expandedView.addView(it, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(12) })
        } else null

        val prog = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        fun timeLabel() = TextView(act).apply {
            setTextColor(0x99FFFFFF.toInt()); textSize = 13f
            fontFeatureSettings = "tnum"
        }
        val elapsed = timeLabel()
        val total = timeLabel().apply { text = fmtTime(m.duration); gravity = Gravity.END }
        val bar = IslandProgressView(act)
        prog.addView(elapsed, LinearLayout.LayoutParams(dp(50), WRAP_CONTENT))
        prog.addView(bar, LinearLayout.LayoutParams(0, dp(16), 1f).apply { setMargins(dp(8), 0, dp(8), 0) })
        prog.addView(total, LinearLayout.LayoutParams(dp(50), WRAP_CONTENT))
        if (m.duration <= 0) { bar.visibility = View.INVISIBLE; total.text = "" }
        bar.onSeek = { f -> buzz(); m.onSeek((f * m.duration).toLong()) }
        expandedView.addView(prog, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(16) })

        val controls = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        fun ctl(res: Int, size: Int, fn: () -> Unit) = ImageView(act).apply {
            setImageResource(res)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding((dp(52) - dp(size)) / 2, 0, (dp(52) - dp(size)) / 2, 0)
            setOnClickListener { v ->
                buzz()
                v.animate().scaleX(0.8f).scaleY(0.8f).setDuration(80).withEndAction {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(160).start()
                }.start()
                fn()
            }
        }.also { controls.addView(it, LinearLayout.LayoutParams(0, dp(size), 1f)) }
        ctl(R.drawable.ic_add_circle, 30) { m.onAdd() }
        ctl(R.drawable.ic_island_prev, 32) { m.onPrev() }
        val play = ctl(if (m.playing()) R.drawable.ic_island_pause else R.drawable.ic_island_play, 40) { m.onPlayPause() }
        ctl(R.drawable.ic_island_next, 32) { m.onNext() }
        ctl(R.drawable.ic_broadcast, 30) { m.onOutput() }
        expandedView.addView(controls, LinearLayout.LayoutParams(MATCH_PARENT, dp(56)).apply { topMargin = dp(12) })

        // Keep the time and play/pause icon live while open.
        ticker?.let { pill.removeCallbacks(it) }
        var wasPlaying: Boolean? = null
        val t = object : Runnable {
            override fun run() {
                // The music app sends a fresh player object on every update — follow the newest one
                // for this song instead of stopping (that froze the time and lyrics).
                val cur = current()
                if (!expanded || cur == null || cur.id != a.id || cur.title != a.title) return
                val m = cur.media ?: return
                val pos = m.position().coerceIn(0, if (m.duration > 0) m.duration else Long.MAX_VALUE)
                elapsed.text = fmtTime(pos)
                if (m.duration > 0) bar.fraction = pos.toFloat() / m.duration
                if (lyr != null) {
                    val song = Lyrics.cached(a.title, a.subtitle)
                    if (song != null) {
                        // Lyrics found: the open island grows to fit them.
                        if (lyr.visibility != View.VISIBLE) { lyr.visibility = View.VISIBLE; pill.post { relayoutExpanded() } }
                        lyr.show(song, pos)
                    } else Lyrics.fetch(a.title, a.subtitle, m.duration) { }
                }
                val p = m.playing()
                if (p != wasPlaying) {
                    wasPlaying = p
                    play.setImageResource(if (p) R.drawable.ic_island_pause else R.drawable.ic_island_play)
                }
                pill.postDelayed(this, 250)
            }
        }
        ticker = t
        t.run()
    }

    /** Re-measure the open island after its contents changed. */
    private fun relayoutExpanded() {
        val a = current() ?: return
        expandedView.measure(View.MeasureSpec.makeMeasureSpec(expandedW(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        animateSize(expandedW(), expandedView.measuredHeight, dp(if (a.media != null) 46 else 38).toFloat())
    }

    private fun bindExpanded(a: IslandActivity) {
        expandedView.removeAllViews()
        expandedView.background = null; expandedView.clipToOutline = false
        a.media?.let { bindMusic(a, it); return }
        a.custom?.let { build ->
            expandedView.setPadding(dp(20), dp(16), dp(20), dp(16))
            expandedView.addView(build(act), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            return
        }
        expandedView.setPadding(dp(20), dp(18), dp(20), dp(16))
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
        }.also { expSubtitle = it })
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
        applyLook()
        SoundFX.play(act, "open")
        hideBubble.run()
        buzz()
        showFace(false)
        showTools(false)
        bindExpanded(a)
        Motion.scaleTo(pill, 1f, Motion.FLUID)
        pill.animate().translationX(0f).setDuration((260 * Motion.scale).toLong()).start()
        compact.animate().alpha(0f).setDuration(160).start()
        expandedView.visibility = View.VISIBLE
        expandedView.alpha = 0f
        expandedView.animate().alpha(1f).setStartDelay(90).setDuration(260).start()
        expandedView.measure(
            View.MeasureSpec.makeMeasureSpec(expandedW(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        animateSize(expandedW(), expandedView.measuredHeight, dp(if (a.media != null) 46 else 38).toFloat())
        // A little 3D tilt as it opens, and the contents tip in one after another.
        spin(pill, DynamicAnimation.ROTATION_X, -8f, 260f, 0.76f)
        cascade(expandedView)
        // Tap anywhere else to fold it back.
        val c = View(act).apply { isClickable = true; setOnClickListener { collapse() } }
        if (!overlayMode) {
            root.addView(c, root.indexOfChild(pill), FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            catcher = c
        }
    }

    private fun expandedW() = screenW() - dp(if (overlayMode) 76 else 16)

    fun collapse(animate: Boolean = true) {
        if (!expanded) return
        expanded = false
        applyLook()
        if (animate) SoundFX.play(act, "close")
        quickMode = false
        catcher?.let { root.removeView(it) }
        catcher = null
        expandedView.animate().cancel()
        expandedView.visibility = View.GONE
        compact.animate().alpha(1f).setDuration(if (animate) 180 else 0).start()
        if (animate) spin(pill, DynamicAnimation.ROTATION_X, 6f, 300f, 0.8f)
        Motion.scaleTo(pill, sizeScale, Motion.FLUID)
        pill.animate().translationX(offsetX).setDuration((260 * Motion.scale).toLong()).start()
        val cur = current()
        if (cur != null) animateSize(compactWidth(cur), compactHeight(cur), dp(18).toFloat())
        else if ((alwaysShow || toolsOn || faceOn) && enabled) {
            compact.visibility = View.INVISIBLE
            showTools(toolsOn && toolsOpen)
            showFace(faceOn && !(toolsOn && toolsOpen))
            animateSize(idleW(), idleH(), idleH() / 2f)
            parkAtCamera(toolsOn && !toolsOpen && !faceOn)
        }
        else refresh()
    }

    /** Tell the side island / sidebar / chat windows how far down the open island reaches. */
    private fun reportZone(h: Int) {
        if (h <= dp(44) || !pill.isAttachedToWindow || !pill.isShown) { OverlayZones.setIsland(0); return }
        val loc = IntArray(2); pill.getLocationOnScreen(loc)
        val top = (loc[1] - pill.translationY).toInt().coerceAtLeast(0)
        OverlayZones.setIsland(top + h)
    }

    /** Spring the pill's size like the island morphing. */
    private var targetW = 0
    private var targetH = 0
    private fun animateSize(w: Int, h: Int, radius: Float) {
        targetW = w; targetH = h
        pill.post { positionSide() }
        val lp = pill.layoutParams as FrameLayout.LayoutParams
        val startW = if (pill.width > 0) pill.width else lp.width
        val startH = if (pill.height > 0) pill.height else lp.height
        if (!toolsMode) (pill.background as? GradientDrawable)?.cornerRadius = radius
        reportZone(h)
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
                // Softer, nearly bounce-free morph so growing/shrinking feels fluid.
                stiffness = (340f / (Motion.scale * Motion.scale)).coerceIn(30f, 10000f)
                dampingRatio = 0.84f
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
