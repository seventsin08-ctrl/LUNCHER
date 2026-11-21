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
    private var panelItems: List<View> = emptyList()
    // Springs for the panel: a soft, slightly lively open and a calm, no-bounce close.
    private val OPEN = Motion.Spec(340f, 0.78f)
    private val OPEN_Y = Motion.Spec(300f, 0.72f)
    private val CLOSE = Motion.Spec(520f, 1f)
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

    /** Panel width: one or two columns of apps. */
    private fun cardW() = if (settings.sidebarCols >= 2) sp(178) else sp(100)

    fun open() {
        if (panelRoot != null) return
        val w = wm ?: return
        val right = settings.sidebarRight
        var pinchRef: android.view.ScaleGestureDetector? = null
        var dragHook: ((MotionEvent) -> Boolean)? = null
        // Sees every touch first, so a two-finger pinch works even on top of the app icons.
        val root = object : FrameLayout(act) {
            override fun dispatchTouchEvent(e: MotionEvent): Boolean {
                if (e.pointerCount == 1 && dragHook?.invoke(e) == true) return true
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
        // Only as tall as its apps: grows as you add more, scrolls once it reaches the size limit.
        val scroll = object : ScrollView(act) {
            var maxH = Int.MAX_VALUE
            override fun onMeasure(w: Int, h: Int) = super.onMeasure(w, View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST))
        }.apply { isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS }
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        scroll.addView(col)
        val chosen = chosenApps()
        val items = ArrayList<View>()

        val twoCols = settings.sidebarCols >= 2
        // Two columns (like the phone's own smart sidebar): apps sit side by side in rows.
        var row: LinearLayout? = null
        fun put(v: View) {
            if (!twoCols) { col.addView(v, LinearLayout.LayoutParams(sp(78), WRAP_CONTENT).apply { bottomMargin = sp(10) }); return }
            val r = row?.takeIf { it.childCount < 2 } ?: LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }.also {
                row = it; col.addView(it, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { bottomMargin = sp(10) })
            }
            r.addView(v, LinearLayout.LayoutParams(sp(78), WRAP_CONTENT))
        }
        // All Father first (the system lives on this side; Gilbert lives in the island).
        val gil = assistantItem { close(); AllFatherActivity.open(act) }
        ((gil as? LinearLayout)?.getChildAt(1) as? TextView)?.text = "All Father"
        put(gil); items += gil
        for ((i, a) in chosen.withIndex()) {
            if (!twoCols && i == 2 && chosen.size > 3) col.addView(View(act).apply {
                background = GradientDrawable().apply { cornerRadius = dp(2).toFloat(); setColor(0x66FFFFFF) }
            }, LinearLayout.LayoutParams(dp(28), dp(3)).apply { setMargins(0, dp(4), 0, dp(12)) })
            val item = appItem(a.label, a) { launch(a); close() }
            put(item)
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
        put(edit)
        items += edit
        card.addView(scroll, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        val back = TextView(act).apply {
            text = if (right) "›" else "‹"; textSize = 26f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x33FFFFFF) }
            setOnClickListener { close() }
        }
        card.addView(back, LinearLayout.LayoutParams(sp(44), sp(44)).apply { topMargin = dp(8) })
        val screenH = act.resources.displayMetrics.heightPixels
        root.addView(card, FrameLayout.LayoutParams(cardW(), WRAP_CONTENT, Gravity.CENTER_VERTICAL))
        // Size limit (Settings › Sidebar › Height); below it the panel just hugs the apps.
        val maxCard = (screenH * settings.sidebarHeight / 100f).toInt()
        val chrome = dp(16) + dp(12) + sp(44) + dp(8)
        scroll.maxH = (maxCard - chrome).coerceAtLeast(sp(80))
        card.measure(View.MeasureSpec.makeMeasureSpec(cardW(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        val cardH = card.measuredHeight.coerceAtMost(maxCard)
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
            y = panelTop(screenH, cardH)
            if (!overApps) token = host?.window?.decorView?.windowToken
            // Stay put over full-screen games and videos (including in the notch area).
            if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        if (runCatching { w.addView(root, lp) }.isFailure) return
        panelRoot = root; panel = card
        SoundFX.play(act, "open")
        // Make room for the island: when it opens at the top, the panel's top slides down
        // (its bottom stays put); when it closes, the panel grows back.
        val baseH = cardH
        var normalTop = panelTop(screenH, cardH)
        var curTop = normalTop
        var slide: android.animation.ValueAnimator? = null
        val place = { top: Int ->
            val h = (normalTop + baseH - top).coerceAtLeast(sp(80) + chrome)
            scroll.maxH = minOf(maxCard, h) - chrome
            scroll.requestLayout()
            lp.y = top
            runCatching { w.updateViewLayout(root, lp) }
        }
        val fit = {
            val ib = OverlayZones.islandBottom
            val target = maxOf(normalTop, if (ib > 0) ib + dp(10) else 0).coerceAtMost(normalTop + baseH - sp(80) - chrome)
            if (target != curTop && panelRoot === root) {
                slide?.cancel()
                slide = android.animation.ValueAnimator.ofInt(curTop, target).apply {
                    duration = 280; interpolator = android.view.animation.DecelerateInterpolator(1.8f)
                    addUpdateListener { curTop = it.animatedValue as Int; if (panelRoot === root) place(curTop) }
                    start()
                }
            }
        }
        // Long-press the open panel, then drag it up or down to move it (like the side island).
        run {
            var downY = 0f; var startTop = 0; var dragging = false; var downTime = 0L
            val slop = android.view.ViewConfiguration.get(act).scaledTouchSlop
            val lift = Runnable {
                dragging = true
                startTop = lp.y
                card.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                Motion.scaleTo(card, 1.04f, Motion.SNAPPY)
                // Let go of whatever the finger was pressing (an app, the scroll list).
                val cancel = MotionEvent.obtain(downTime, android.os.SystemClock.uptimeMillis(), MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
                card.dispatchTouchEvent(cancel); cancel.recycle()
            }
            dragHook = { e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downY = e.rawY; dragging = false; downTime = e.downTime
                        root.postDelayed(lift, 420)
                        false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (!dragging && kotlin.math.abs(e.rawY - downY) > slop) root.removeCallbacks(lift)
                        if (dragging) {
                            val minTop = maxOf(dp(24), if (OverlayZones.islandBottom > 0) OverlayZones.islandBottom + dp(10) else 0)
                            lp.y = (startTop + (e.rawY - downY)).toInt().coerceIn(minTop, screenH - card.height - dp(24))
                            runCatching { w.updateViewLayout(root, lp) }
                            true
                        } else false
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        root.removeCallbacks(lift)
                        if (dragging) {
                            dragging = false
                            Motion.scaleTo(card, 1f, Motion.BOUNCY)
                            card.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                            // Remember it: the centre of the panel, in dp.
                            val center = lp.y + card.height / 2
                            settings.sidebarPanelY = (center / act.resources.displayMetrics.density).roundToInt()
                            normalTop = lp.y; curTop = lp.y
                            true
                        } else false
                    }
                    else -> dragging
                }
            }
        }
        zoneListener?.let { OverlayZones.unlisten(it) }
        zoneListener = fit
        OverlayZones.listen(fit)
        if (OverlayZones.islandBottom > 0) { curTop = maxOf(normalTop, OverlayZones.islandBottom + dp(10)).coerceAtMost(normalTop + baseH - sp(80) - chrome); place(curTop) }
        OverlayZones.setSidebar(cardW() + dp(20), right)
        handle?.animate()?.alpha(0f)?.setDuration(120)?.start()
        root.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        // Grows out of the edge like the side island: slides in while stretching from the edge,
        // settling with a soft spring; the icons follow one after another.
        val off = dp(110).toFloat() * (if (right) 1 else -1)
        panelItems = items
        card.alpha = 0f
        card.post {
            card.pivotX = if (right) card.width.toFloat() else 0f
            card.pivotY = card.height / 2f
            Motion.spring(card, DynamicAnimation.TRANSLATION_X, 0f, OPEN, start = off)
            Motion.spring(card, DynamicAnimation.SCALE_X, 1f, OPEN, start = 0.55f)
            Motion.spring(card, DynamicAnimation.SCALE_Y, 1f, OPEN_Y, start = 0.82f)
            card.animate().alpha(1f).setDuration(200).setInterpolator(android.view.animation.DecelerateInterpolator(1.5f)).start()
            for ((i, v) in items.withIndex()) {
                v.alpha = 0f; v.translationX = off * 0.35f; v.scaleX = 0.8f; v.scaleY = 0.8f
                v.animate().alpha(1f).translationX(0f).scaleX(1f).scaleY(1f).setStartDelay(70L + i * 28L).setDuration(320)
                    .setInterpolator(android.view.animation.PathInterpolator(0.2f, 0.9f, 0.1f, 1f)).start()
            }
        }
        // Safety: never leave the panel stuck on screen.
        root.postDelayed({ if (panelRoot === root) close() }, 60_000)
    }

    fun close(immediate: Boolean = false) {
        val root = panelRoot ?: return
        val card = panel
        panelRoot = null; panel = null
        if (!immediate) SoundFX.play(act, "close")
        zoneListener?.let { OverlayZones.unlisten(it) }; zoneListener = null
        OverlayZones.setSidebar(0, settings.sidebarRight)
        handle?.animate()?.alpha(1f)?.setDuration(200)?.start()
        val remove = { runCatching { wm?.removeViewImmediate(root) }; Unit }
        if (immediate || card == null) { remove(); return }
        // Shrinks back into the edge: icons fade first, then the panel tucks away (no bounce).
        val right = settings.sidebarRight
        val off = dp(110).toFloat() * (if (right) 1 else -1)
        card.pivotX = if (right) card.width.toFloat() else 0f
        card.pivotY = card.height / 2f
        val its = panelItems; panelItems = emptyList()
        for ((i, v) in its.asReversed().withIndex()) {
            v.animate().cancel()
            v.animate().alpha(0f).translationX(off * 0.25f).scaleX(0.85f).scaleY(0.85f)
                .setStartDelay(i * 12L).setDuration(140).start()
        }
        Motion.spring(card, DynamicAnimation.SCALE_X, 0.55f, CLOSE)
        Motion.spring(card, DynamicAnimation.SCALE_Y, 0.85f, CLOSE)
        Motion.spring(card, DynamicAnimation.TRANSLATION_X, off, CLOSE) { remove() }
        card.animate().alpha(0f).setStartDelay(60).setDuration(190)
            .setInterpolator(android.view.animation.AccelerateInterpolator(1.4f)).start()
        root.postDelayed({ if (root.isAttachedToWindow) remove() }, 700)
    }

    /** Top of the open panel: where you last dragged it, or the middle of the screen. */
    private fun panelTop(screenH: Int, cardH: Int): Int {
        val c = settings.sidebarPanelY
        val top = if (c >= 0) dp(c) - cardH / 2 else (screenH - cardH) / 2
        return top.coerceIn(dp(24), (screenH - cardH - dp(24)).coerceAtLeast(dp(24)))
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
