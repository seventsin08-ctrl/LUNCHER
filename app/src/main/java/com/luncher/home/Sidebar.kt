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
    private val act: Activity,
    private val settings: Settings,
    private val apps: () -> List<AppInfo>,
    private val launch: (AppInfo) -> Unit
) {
    private fun dp(v: Int) = (v * act.resources.displayMetrics.density).roundToInt()

    private var wm: WindowManager? = null
    private var handle: View? = null
    private var handleLp: WindowManager.LayoutParams? = null
    private var panelRoot: FrameLayout? = null
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

    /** Show (or refresh) the edge handle. */
    fun attach() {
        detach()
        if (!settings.sidebarOn) return
        val type = windowType()
        overApps = type != null && settings.sidebarOverApps
        val ctxForWm = if (overApps) (LuncherAccessibility.instance ?: act) else act
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
            y = dp(settings.sidebarY)
            if (!overApps) token = act.window.decorView.windowToken
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
        val root = FrameLayout(act).apply { clipChildren = false; clipToPadding = false; setPadding(dp(10), 0, dp(10), 0) }
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
        for ((i, a) in chosen.withIndex()) {
            if (i == 2 && chosen.size > 3) col.addView(View(act).apply {
                background = GradientDrawable().apply { cornerRadius = dp(2).toFloat(); setColor(0x66FFFFFF) }
            }, LinearLayout.LayoutParams(dp(28), dp(3)).apply { setMargins(0, dp(4), 0, dp(12)) })
            val item = appItem(a.label, a) { launch(a); close() }
            col.addView(item, LinearLayout.LayoutParams(dp(78), WRAP_CONTENT).apply { bottomMargin = dp(10) })
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
        col.addView(edit, LinearLayout.LayoutParams(dp(78), WRAP_CONTENT))
        items += edit
        card.addView(scroll, LinearLayout.LayoutParams(WRAP_CONTENT, 0, 1f))
        val back = TextView(act).apply {
            text = if (right) "›" else "‹"; textSize = 26f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x33FFFFFF) }
            setOnClickListener { close() }
        }
        card.addView(back, LinearLayout.LayoutParams(dp(44), dp(44)).apply { topMargin = dp(8) })
        val screenH = act.resources.displayMetrics.heightPixels
        root.addView(card, FrameLayout.LayoutParams(dp(100), (screenH * 0.72f).toInt(), Gravity.CENTER_VERTICAL))
        root.setOnTouchListener { _, e -> if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) close(); false }

        val lp = WindowManager.LayoutParams(
            WRAP_CONTENT, WRAP_CONTENT,
            handleLp?.type ?: WindowManager.LayoutParams.TYPE_APPLICATION,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or WindowManager.LayoutParams.FLAG_DIM_BEHIND or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = (if (right) Gravity.END else Gravity.START) or Gravity.CENTER_VERTICAL
            dimAmount = 0.32f
            if (!overApps) token = act.window.decorView.windowToken
            if (Build.VERSION.SDK_INT >= 31) blurBehindRadius = dp(18)
            if (Build.VERSION.SDK_INT >= 31) flags = flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
        }
        if (runCatching { w.addView(root, lp) }.isFailure) return
        panelRoot = root; panel = card
        handle?.animate()?.alpha(0f)?.setDuration(120)?.start()
        act.window.decorView.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
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
        box.addView(icon, FrameLayout.LayoutParams(dp(54), dp(54)))
        if (app == null) box.addView(TextView(act).apply {
            text = "+"; textSize = 28f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
        }, FrameLayout.LayoutParams(dp(54), dp(54)))
        v.addView(box, LinearLayout.LayoutParams(dp(54), dp(54)))
        v.addView(TextView(act).apply {
            text = label; textSize = 12f; setTextColor(Color.WHITE); maxLines = 2; gravity = Gravity.CENTER
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
