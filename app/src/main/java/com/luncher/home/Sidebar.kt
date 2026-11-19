package com.luncher.home

import android.app.Activity
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.GestureDetector
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.dynamicanimation.animation.DynamicAnimation
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Smart sidebar (like vivo / Samsung edge panel): a thin handle on the screen edge.
 * Swipe it in (or tap it) and a glass panel slides out with your favourite apps.
 * Works on top of other apps when the launcher may draw over them, otherwise on the Home Screen.
 */
class Sidebar(
    /** Any context (the launcher screen, or the background sidebar service). */
    private val act: android.content.Context,
    /** The launcher screen when the sidebar only lives on the Home Screen, else null. */
    private val host: Activity?,
    private val settings: Settings,
    private val apps: () -> List<AppInfo>,
    private val launch: (AppInfo) -> Unit
) {
    private fun dp(v: Int) = (v * act.resources.displayMetrics.density).roundToInt()
    /** Size of the panel and its apps (Settings › Sidebar › Size, or pinch the open panel). */
    private val k get() = settings.sidebarScale / 100f
    private fun sp(v: Int) = (v * k * act.resources.displayMetrics.density).roundToInt()

    private var wm: WindowManager? = null
    private var handle: View? = null
    private var handleLp: WindowManager.LayoutParams? = null
    private var panelRoot: FrameLayout? = null
    private var zoneListener: (() -> Unit)? = null
    private var panel: View? = null
    private var overApps = false

    val isOpen get() = panelRoot != null

    private fun windowType(): Int? {
        val svc = LuncherAccessibility.instance
        return when {
            svc != null -> WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            android.provider.Settings.canDrawOverlays(act) -> WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else -> null
        }
    }

    /**
     * Keep the handle out of the side island's area when both are on the same edge
     * (the side island, its volume slider and the chat button below it).
     */
    private fun clearOfSideIsland(y: Int): Int {
        if (!settings.sideHud || settings.sidebarRight != settings.sideRight) return y
        val h = act.resources.displayMetrics.heightPixels
        val center = (h * settings.sideY / 100f).toInt()
        val zoneTop = center - dp(115)
        val zoneBottom = center + dp(150)
        val handleH = dp(96)
        if (y + handleH < zoneTop || y > zoneBottom) return y
        val below = zoneBottom + dp(12)
        val above = zoneTop - handleH - dp(12)
        return if (below + handleH < h - dp(80)) below else above.coerceAtLeast(dp(40))
    }

    /** Show (or refresh) the edge handle. */
    fun attach() {
        detach()
        if (!settings.sidebarOn) return
        val type = windowType()
        overApps = type != null && settings.sidebarOverApps
        if (!overApps && host == null) return
        val ctxForWm: android.content.Context = if (overApps) (LuncherAccessibility.instance ?: act) else host!!
        val w = ctxForWm.getSystemService(WindowManager::class.java) ?: return
        val right = settings.sidebarRight
        val h = HandleView(act, right)
        val lp = WindowManager.LayoutParams(
            dp(22), dp(96),
            if (overApps) type!! else WindowManager.LayoutParams.TYPE_APPLICATION,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = (if (right) Gravity.END else Gravity.START) or Gravity.TOP
            y = clearOfSideIsland(dp(settings.sidebarY))
            if (!overApps) token = host?.window?.decorView?.windowToken
            // Stay put over full-screen games and videos (including in the notch area).
            if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        if (runCatching { w.addView(h, lp) }.isFailure) return
        wm = w; handle = h; handleLp = lp
        h.alpha = 0f
        h.animate().alpha(1f).setDuration(300).start()
    }

    fun detach() {
        close(immediate = true)
        handle?.let { runCatching { wm?.removeViewImmediate(it) } }
        handle = null; handleLp = null
    }

    fun open() {
        if (panelRoot != null) return
        val w = wm ?: return
        val right = settings.sidebarRight
        var pinchRef: android.view.ScaleGestureDetector? = null
        // Sees every touch first, so a two-finger pinch works even on top of the app icons.
        val root = object : FrameLayout(act) {
            override fun dispatchTouchEvent(e: MotionEvent): Boolean {
                val p = pinchRef
                if (p != null && e.pointerCount > 1) { p.onTouchEvent(e); return true }
                if (p != null && p.isInProgress) { p.onTouchEvent(e); return true }
                return super.dispatchTouchEvent(e)
            }
        }.apply { clipChildren = false; clipToPadding = false; setPadding(dp(10), 0, dp(10), 0) }
        val card = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(10), dp(16), dp(10), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(32).toFloat()
                setColor(Color.argb(238, 44, 44, 46)); setStroke(maxOf(1, dp(1)), Color.argb(50, 255, 255, 255))
            }
            elevation = dp(16).toFloat()
        }
        val scroll = ScrollView(act).apply { isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER }
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        scroll.addView(col)
        val chosen = chosenApps()
        val items = ArrayList<View>()
        // The assistant: tap to talk (timer, message, call, notes, report…).
        if (settings.sidebarAssistant) {
            val ai = assistantItem { close(immediate = true); AssistantActivity.open(act) }
            col.addView(ai, LinearLayout.LayoutParams(sp(78), WRAP_CONTENT).apply { bottomMargin = sp(10) })
            items += ai
        }
        for ((i, a) in chosen.withIndex()) {
            if (i == 2 && chosen.size > 3) col.addView(View(act).apply {
                background = GradientDrawable().apply { cornerRadius = dp(2).toFloat(); setColor(0x66FFFFFF) }
            }, LinearLayout.LayoutParams(dp(28), dp(3)).apply { setMargins(0, dp(4), 0, dp(12)) })
            val item = appItem(a.label, a) { launch(a); close() }
            col.addView(item, LinearLayout.LayoutParams(sp(78), WRAP_CONTENT).apply { bottomMargin = sp(10) })
            items += item
        }
        // Edit: opens the Sidebar page in Settings to add, remove or reorder apps.
        val edit = appItem("Edit", null) {
            close()
            runCatching {
                act.startActivity(Intent(act, SettingsActivity::class.java).putExtra("page", "sidebar")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
        col.addView(edit, LinearLayout.LayoutParams(sp(78), WRAP_CONTENT))
        items += edit
        card.addView(scroll, LinearLayout.LayoutParams(WRAP_CONTENT, 0, 1f))
        val back = TextView(act).apply {
            text = if (right) "›" else "‹"; textSize = 26f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x33FFFFFF) }
            setOnClickListener { close() }
        }
        card.addView(back, LinearLayout.LayoutParams(sp(44), sp(44)).apply { topMargin = dp(8) })
        val screenH = act.resources.displayMetrics.heightPixels
        root.addView(card, FrameLayout.LayoutParams(sp(100), (screenH * settings.sidebarHeight / 100f).toInt(), Gravity.CENTER_VERTICAL))
        // Pinch the open panel to make it (and its apps) bigger or smaller.
        val pinch = android.view.ScaleGestureDetector(act, object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
            var start = 0f
            override fun onScaleBegin(d: android.view.ScaleGestureDetector): Boolean { start = settings.sidebarScale.toFloat(); return true }
            override fun onScale(d: android.view.ScaleGestureDetector): Boolean {
                start *= d.scaleFactor
                val v = start.toInt().coerceIn(60, 140)
                card.scaleX = v / settings.sidebarScale.toFloat(); card.scaleY = card.scaleX
                return true
            }
            override fun onScaleEnd(d: android.view.ScaleGestureDetector) {
                settings.sidebarScale = start.toInt().coerceIn(60, 140)
                card.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                close(immediate = true); open()
            }
        })
        pinchRef = pinch
        root.setOnTouchListener { _, e -> if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) close(); false }

        val lp = WindowManager.LayoutParams(
            WRAP_CONTENT, WRAP_CONTENT,
            handleLp?.type ?: WindowManager.LayoutParams.TYPE_APPLICATION,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            // No dim / blur behind: it would darken and smear the island and side island too.
            gravity = (if (right) Gravity.END else Gravity.START) or Gravity.TOP
            y = (screenH - (screenH * settings.sidebarHeight / 100f).toInt()) / 2
            if (!overApps) token = host?.window?.decorView?.windowToken
            // Stay put over full-screen games and videos (including in the notch area).
            if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        if (runCatching { w.addView(root, lp) }.isFailure) return
        panelRoot = root; panel = card
        // Make room for the island: when it opens at the top, the panel's top slides down
        // (its bottom stays put); when it closes, the panel grows back.
        val baseH = (screenH * settings.sidebarHeight / 100f).toInt()
        val normalTop = (screenH - baseH) / 2
        var curTop = normalTop
        var slide: android.animation.ValueAnimator? = null
        val place = { top: Int ->
            val h = (normalTop + baseH - top).coerceAtLeast(dp(220))
            (card.layoutParams as FrameLayout.LayoutParams).height = h
            card.requestLayout()
            lp.y = top
            runCatching { w.updateViewLayout(root, lp) }
        }
        val fit = {
            val ib = OverlayZones.islandBottom
            val target = maxOf(normalTop, if (ib > 0) ib + dp(10) else 0).coerceAtMost(screenH - dp(240))
            if (target != curTop && panelRoot === root) {
                slide?.cancel()
                slide = android.animation.ValueAnimator.ofInt(curTop, target).apply {
                    duration = 280; interpolator = android.view.animation.DecelerateInterpolator(1.8f)
                    addUpdateListener { curTop = it.animatedValue as Int; if (panelRoot === root) place(curTop) }
                    start()
                }
            }
        }
        zoneListener?.let { OverlayZones.unlisten(it) }
        zoneListener = fit
        OverlayZones.listen(fit)
        if (OverlayZones.islandBottom > 0) { curTop = maxOf(normalTop, OverlayZones.islandBottom + dp(10)).coerceAtMost(screenH - dp(240)); place(curTop) }
        OverlayZones.setSidebar(sp(100) + dp(20), right)
        handle?.animate()?.alpha(0f)?.setDuration(120)?.start()
        root.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        // Slide in from the edge, the icons following one after another.
        val off = dp(130).toFloat() * (if (right) 1 else -1)
        Motion.spring(card, DynamicAnimation.TRANSLATION_X, 0f, Motion.FLUID, start = off)
        for ((i, v) in items.withIndex()) {
            v.alpha = 0f; v.translationX = off * 0.4f
            v.animate().alpha(1f).translationX(0f).setStartDelay(40L + i * 22L).setDuration(240)
                .setInterpolator(android.view.animation.DecelerateInterpolator(2f)).start()
        }
        // Safety: never leave the panel stuck on screen.
        root.postDelayed({ if (panelRoot === root) close() }, 60_000)
    }

    fun close(immediate: Boolean = false) {
        val root = panelRoot ?: return
        val card = panel
        panelRoot = null; panel = null
        zoneListener?.let { OverlayZones.unlisten(it) }; zoneListener = null
        OverlayZones.setSidebar(0, settings.sidebarRight)
        handle?.animate()?.alpha(1f)?.setDuration(200)?.start()
        val remove = { runCatching { wm?.removeViewImmediate(root) }; Unit }
        if (immediate || card == null) { remove(); return }
        val off = dp(140).toFloat() * (if (settings.sidebarRight) 1 else -1)
        card.animate().translationX(off).alpha(0f).setDuration(200)
            .setInterpolator(android.view.animation.AccelerateInterpolator(1.6f))
            .withEndAction { remove() }.start()
        root.postDelayed({ if (root.isAttachedToWindow) remove() }, 600)
    }

    private fun chosenApps(): List<AppInfo> {
        val all = apps()
        val picked = settings.sidebarApps.mapNotNull { pkg -> all.firstOrNull { it.packageName == pkg } }
        return picked.ifEmpty { all.take(8) }
    }

    /** Glowing orb button for the assistant. */
    private fun assistantItem(onClick: () -> Unit): View {
        val v = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setOnClickListener { it.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK); onClick() }
            setOnTouchListener { view, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> view.animate().scaleX(0.88f).scaleY(0.88f).setDuration(90).start()
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> Motion.scaleTo(view, 1f, Motion.BOUNCY)
                }
                false
            }
        }
        val orb = TextView(act).apply {
            text = "✦"; textSize = 24f * k.coerceIn(0.8f, 1.2f); setTextColor(Color.WHITE); gravity = Gravity.CENTER
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF5E5CE6.toInt(), 0xFF0A84FF.toInt(), 0xFF64D2FF.toInt())).apply { shape = GradientDrawable.OVAL }
            elevation = dp(6).toFloat()
        }
        // Gentle breathing glow so it reads as "alive".
        val pulse = android.animation.ObjectAnimator.ofFloat(orb, View.ALPHA, 1f, 0.78f).apply {
            duration = 1400; repeatMode = android.animation.ValueAnimator.REVERSE; repeatCount = android.animation.ValueAnimator.INFINITE
        }
        orb.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = pulse.start()
            override fun onViewDetachedFromWindow(view: View) = pulse.cancel()
        })
        v.addView(orb, LinearLayout.LayoutParams(sp(54), sp(54)))
        v.addView(TextView(act).apply {
            text = "Assistant"; textSize = 12f * k.coerceIn(0.8f, 1.2f); visibility = if (settings.sidebarLabels) View.VISIBLE else View.GONE
            setTextColor(Color.WHITE); gravity = Gravity.CENTER; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(5) })
        return v
    }

    private fun appItem(label: String, app: AppInfo?, onClick: () -> Unit): View {
        val v = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setOnClickListener { it.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK); onClick() }
            setOnTouchListener { view, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> view.animate().scaleX(0.88f).scaleY(0.88f).setDuration(90).start()
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> Motion.scaleTo(view, 1f, Motion.BOUNCY)
                }
                false
            }
        }
        val icon = ImageView(act).apply {
            if (app != null) setImageBitmap(app.icon) else {
                background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(0x33FFFFFF) }
            }
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) =
                    outline.setRoundRect(0, 0, view.width, view.height, view.width * 0.24f)
            }
            clipToOutline = true
        }
        val box = FrameLayout(act)
        box.addView(icon, FrameLayout.LayoutParams(sp(54), sp(54)))
        if (app == null) box.addView(TextView(act).apply {
            text = "+"; textSize = 28f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
        }, FrameLayout.LayoutParams(sp(54), sp(54)))
        v.addView(box, LinearLayout.LayoutParams(sp(54), sp(54)))
        v.addView(TextView(act).apply {
            text = label; textSize = 12f * k.coerceIn(0.8f, 1.2f); visibility = if (settings.sidebarLabels) View.VISIBLE else View.GONE; setTextColor(Color.WHITE); maxLines = 2; gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(5) })
        return v
    }

    /** The thin edge handle: swipe inwards or tap to open; long-press and drag to move it. */
    private inner class HandleView(ctx: android.content.Context, private val right: Boolean) : View(ctx) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 255, 255, 255) }
        private var downRawX = 0f
        private var downRawY = 0f
        private var startY = 0
        private var moving = false
        private val detector = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapUp(e: MotionEvent): Boolean { open(); return true }
            override fun onLongPress(e: MotionEvent) {
                moving = true
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                animate().scaleX(1.6f).setDuration(120).start()
            }
        })

        override fun onDraw(c: Canvas) {
            val w = dp(4).toFloat()
            val x = if (right) width - w - dp(3) else dp(3).toFloat()
            val top = height * 0.2f
            c.drawRoundRect(x, top, x + w, height - top, w / 2, w / 2, paint)
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downRawX = e.rawX; downRawY = e.rawY; startY = handleLp?.y ?: 0; moving = false }
                MotionEvent.ACTION_MOVE -> {
                    if (moving) {
                        val lp = handleLp ?: return true
                        lp.y = (startY + (e.rawY - downRawY)).toInt().coerceIn(dp(40), resources.displayMetrics.heightPixels - dp(140))
                        runCatching { wm?.updateViewLayout(this, lp) }
                        return true
                    }
                    val dx = e.rawX - downRawX
                    if ((right && dx < -dp(14)) || (!right && dx > dp(14))) { open(); return true }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (moving) {
                    moving = false
                    animate().scaleX(1f).setDuration(160).start()
                    settings.sidebarY = ((handleLp?.y ?: 0) / resources.displayMetrics.density).roundToInt()
                    return true
                }
            }
            detector.onTouchEvent(e)
            return true
        }
    }
}
