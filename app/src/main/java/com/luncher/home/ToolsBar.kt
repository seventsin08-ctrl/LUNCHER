package com.luncher.home

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The tools bar: Flashlight, Brightness, Silent, Game Mode, Lock Screen and Settings, always on
 * screen — on the Home Screen and on top of every app. It never hides by itself; the arrow at
 * its end folds it into a small tab (and back). Hold it to drag it anywhere. Its size, colour,
 * shape and angle come from Settings › Dynamic Island › Island Tools.
 */
class ToolsBar(private val ctx: Context) {
    companion object {
        /** The bar on screen right now (so the launcher can fade it while Search is open). */
        var current: ToolsBar? = null
            private set
        /** While the launcher's Settings is open, the bar becomes a single ✕ that closes it. */
        var exitAction: (() -> Unit)? = null
            private set
        fun settingsMode(exit: (() -> Unit)?) {
            if (exitAction == null && exit == null) return
            exitAction = exit
            current?.let { b -> b.main.post { b.rebuild(animateIn = true) } }
        }
    }

    private class Tool(val icon: Int, val label: String, val on: () -> Boolean, val tap: (View) -> Unit)

    private val settings = Settings(ctx)
    private val main = Handler(Looper.getMainLooper())
    private fun dp(v: Float) = (v * ctx.resources.displayMetrics.density).roundToInt()
    private fun dp(v: Int) = dp(v.toFloat())
    private var wm: WindowManager? = null
    private var root: FrameLayout? = null
    private var lp: WindowManager.LayoutParams? = null
    private var torchOn = false
    private val torchCb = object : android.hardware.camera2.CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) { torchOn = enabled; main.post { rebuild() } }
    }
    private var searching = false

    fun attach() {
        detach()
        if (!settings.islandTools) return
        val svc = LuncherAccessibility.instance
        val type = when {
            svc != null -> WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            android.provider.Settings.canDrawOverlays(ctx) -> WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else -> return
        }
        val w = (svc ?: ctx).getSystemService(WindowManager::class.java) ?: return
        val r = FrameLayout(ctx).apply { clipChildren = false; clipToPadding = false }
        val l = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = dp(settings.toolsDx)
            y = dp(64) + dp(settings.toolsDy)
            if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        if (runCatching { w.addView(r, l) }.isFailure) return
        DragPass.watch(w, r)
        wm = w; root = r; lp = l; current = this
        push = 0; yielding = false
        OverlayZones.listen(zoneFit)
        runCatching { ctx.getSystemService(android.hardware.camera2.CameraManager::class.java)?.registerTorchCallback(torchCb, main) }
        rebuild(animateIn = true)
    }

    fun detach() {
        if (current === this) current = null
        OverlayZones.unlisten(zoneFit)
        runCatching { ctx.getSystemService(android.hardware.camera2.CameraManager::class.java)?.unregisterTorchCallback(torchCb) }
        root?.let { r -> runCatching { wm?.removeViewImmediate(r) } }
        root = null; lp = null
    }

    /** Fades away while the launcher's Search is open, and comes back after. */
    fun setSearching(on: Boolean) {
        searching = on
        applyVisibility()
    }

    // ---------------------------------------------------------------- never covering anything
    private var yielding = false          // something else needs this spot right now
    private var push = 0                  // moved down to clear the open island
    private val zoneFit: () -> Unit = { main.post { fitZones() } }

    /**
     * Steps aside for the other floating pieces: hides while the Sidebar or the open side
     * island would overlap it, and slides down below the Dynamic Island while it's open.
     */
    private fun fitZones() {
        val r = root ?: return
        val l = lp ?: return
        if (r.width == 0) { r.post { fitZones() }; return }
        val loc = IntArray(2); r.getLocationOnScreen(loc)
        // Use where it normally sits (without the island push) to decide.
        val me = android.graphics.Rect(loc[0], loc[1] - push, loc[0] + r.width, loc[1] - push + r.height)
        val sw = ctx.resources.displayMetrics.widthPixels
        var hide = false
        val sb = OverlayZones.sidebarWidth
        if (sb > 0) {
            val zone = if (OverlayZones.sidebarRight) android.graphics.Rect(sw - sb, 0, sw, Int.MAX_VALUE / 2)
                else android.graphics.Rect(0, 0, sb, Int.MAX_VALUE / 2)
            if (android.graphics.Rect.intersects(zone, me)) hide = true
        }
        OverlayZones.sideHud?.let { if (android.graphics.Rect.intersects(it, me)) hide = true }
        if (hide != yielding) { yielding = hide; applyVisibility() }
        // Below the open island (it comes back up when the island folds).
        val ib = OverlayZones.islandBottom
        val want = if (ib > 0 && me.top < ib + dp(8) && me.bottom > 0) ib + dp(8) - me.top else 0
        if (want != push) {
            val from = push; push = want
            android.animation.ValueAnimator.ofInt(from, want).apply {
                duration = 280; interpolator = android.view.animation.DecelerateInterpolator(1.6f)
                var last = from
                addUpdateListener { a ->
                    val v = a.animatedValue as Int
                    l.y += v - last; last = v
                    runCatching { wm?.updateViewLayout(root, l) }
                }
                start()
            }
        }
    }

    private fun applyVisibility() {
        val r = root ?: return
        val off = searching || yielding
        r.animate().cancel()
        r.animate().alpha(if (off) 0f else 1f).scaleX(if (off) 0.85f else 1f).scaleY(if (off) 0.85f else 1f)
            .setDuration(if (off) 160 else 260).start()
        lp?.let { l ->
            l.flags = if (off) l.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else l.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            runCatching { wm?.updateViewLayout(r, l) }
        }
    }

    // ================================================================ building

    private fun tools(): List<Tool> {
        val am = ctx.getSystemService(android.media.AudioManager::class.java)
        return listOf(
            Tool(R.drawable.ic_cc_flashlight, "Flashlight", { torchOn }) { setTorch(!torchOn) },
            Tool(R.drawable.ic_cc_sun, "Brightness", { false }) { cycleBrightness() },
            Tool(R.drawable.ic_cc_camera, "Camera", { false }) {
                val ok = runCatching {
                    ctx.startActivity(Intent(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }.isSuccess || runCatching {
                    ctx.startActivity(Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }.isSuccess
                if (!ok) toast("No camera app found")
            },
            Tool(R.drawable.ic_cc_bell_off, "Silent", { am.ringerMode != android.media.AudioManager.RINGER_MODE_NORMAL }) {
                runCatching {
                    am.ringerMode = if (am.ringerMode == android.media.AudioManager.RINGER_MODE_NORMAL)
                        android.media.AudioManager.RINGER_MODE_VIBRATE else android.media.AudioManager.RINGER_MODE_NORMAL
                }
            },
            Tool(R.drawable.ic_cc_game, "Game Mode", { settings.gameMode }) { toggleGameMode() },
            Tool(R.drawable.ic_cc_lock, "Lock Screen", { false }) {
                val ok = Build.VERSION.SDK_INT >= 28 &&
                    LuncherAccessibility.perform(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
                if (!ok) toast("Turn on the Accessibility Helper (Settings › Permissions & Setup) to lock the screen")
            },
            Tool(R.drawable.ic_set_gear, "Settings", { false }) { v ->
                (v as? FrameLayout)?.getChildAt(0)?.animate()?.rotationBy(360f)?.setDuration(520)?.start()
                main.postDelayed({
                    runCatching { ctx.startActivity(Intent(ctx, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }, 200)
            }
        )
    }

    /** Colour of the bar and of its icons, from the settings. */
    private fun colors(): Triple<Int, Int, Int> {
        val op = (settings.toolsOpacity * 255 / 100).coerceIn(40, 255)
        fun withA(c: Int, a: Int = op) = (c and 0x00FFFFFF) or (a shl 24)
        val base = when (settings.toolsColor) {
            "glass" -> withA(Color.rgb(28, 28, 32), (op * 0.72f).toInt())
            "theme" -> withA(IconFactory.blend(Color.BLACK, ThemeEngine.accent, 0.5f))
            "white" -> withA(Color.rgb(245, 245, 247))
            "custom" -> withA(settings.toolsCustomColor)
            else -> withA(Color.BLACK)
        }
        val stroke = if (settings.toolsColor == "glass") 0x55FFFFFF else 0x22FFFFFF
        val lum = (0.299 * Color.red(base) + 0.587 * Color.green(base) + 0.114 * Color.blue(base)) / 255
        val icon = if (lum > 0.62) 0xFF1C1C1E.toInt() else Color.WHITE
        return Triple(base, stroke, icon)
    }

    private fun rebuild(animateIn: Boolean = false) {
        val r = root ?: return
        val l = lp ?: return
        r.removeAllViews()
        ThemeEngine.resolve(ctx, settings)
        // Pushed against a side: a half circle that blossoms out of the edge, ✕ in the middle.
        var edge = settings.toolsEdge
        // Never on the Sidebar's side: move to the other edge.
        if ((edge == "left" || edge == "right") && settings.sidebarOn && (edge == "right") == settings.sidebarRight) {
            edge = if (edge == "right") "left" else "right"; settings.toolsEdge = edge
        }
        if (edge == "left" || edge == "right") {
            l.gravity = Gravity.TOP or (if (edge == "left") Gravity.LEFT else Gravity.RIGHT)
            l.x = 0
            if (settings.toolsEdgeStyle == "panel") rebuildPanel(r, l, edge == "right", animateIn)
            else rebuildEdge(r, l, edge == "right", animateIn)
            return
        }
        l.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        l.x = dp(settings.toolsDx)
        val k = settings.toolsScale / 100f
        val exit = exitAction
        val collapsed = settings.toolsCollapsed || exit != null
        val (bg, stroke, iconCol) = colors()
        val h = dp(36 * k)
        val btn = dp(30 * k)
        val list = if (collapsed) emptyList() else tools()

        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8 * k), 0, dp(6 * k), 0)
            background = GradientDrawable().apply {
                setColor(bg); setStroke(maxOf(1, dp(1)), stroke)
                when (settings.toolsShape) {
                    "square" -> cornerRadius = dp(6 * k).toFloat()
                    "rounded" -> cornerRadius = dp(12 * k).toFloat()
                    "leaf" -> cornerRadii = floatArrayOf(h / 2f, h / 2f, dp(4).toFloat(), dp(4).toFloat(), h / 2f, h / 2f, dp(4).toFloat(), dp(4).toFloat())
                    else -> cornerRadius = h / 2f
                }
            }
            elevation = dp(6).toFloat()
        }
        for (t in list) {
            val on = t.on()
            val b = FrameLayout(ctx).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (on) Color.WHITE else Color.TRANSPARENT) }
                contentDescription = t.label
                setOnClickListener { v ->
                    v.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    v.animate().scaleX(0.8f).scaleY(0.8f).setDuration(80).withEndAction { Motion.scaleTo(v, 1f, Motion.BOUNCY) }.start()
                    t.tap(v)
                    main.postDelayed({ rebuild() }, 600)
                }
            }
            b.addView(ImageView(ctx).apply { setImageResource(t.icon); setColorFilter(if (on) 0xFF1C1C1E.toInt() else iconCol) },
                FrameLayout.LayoutParams(dp(17 * k), dp(17 * k), Gravity.CENTER))
            bar.addView(b, LinearLayout.LayoutParams(btn, btn).apply { marginStart = dp(2 * k); marginEnd = dp(2 * k) })
        }
        // In Settings: just a ✕ that closes it (the bar comes back once you leave).
        if (exit != null) {
            val x = android.widget.TextView(ctx).apply {
                text = "✕"; textSize = 15f * k; setTextColor(iconCol); gravity = Gravity.CENTER
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                contentDescription = "Close Settings"
                setOnClickListener { v ->
                    v.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    v.animate().rotationBy(90f).scaleX(0.8f).scaleY(0.8f).setDuration(160).withEndAction { exit() }.start()
                }
            }
            bar.addView(x, LinearLayout.LayoutParams(dp(24 * k), dp(24 * k)))
        }
        // The arrow: fold the bar into a small tab (⌃), or open it again (⌄).
        val arrow = FrameLayout(ctx).apply {
            contentDescription = if (collapsed) "Show tools" else "Hide tools"
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.argb(40, 255, 255, 255)) }
            setOnClickListener { v ->
                v.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                settings.toolsCollapsed = !settings.toolsCollapsed
                rebuild(animateIn = true)
            }
        }
        arrow.addView(ImageView(ctx).apply {
            setImageResource(R.drawable.ic_set_chevron); setColorFilter(iconCol)
            rotation = if (collapsed) -90f else 90f   // ⌄ = show, ⌃ = hide
        }, FrameLayout.LayoutParams(dp(14 * k), dp(14 * k), Gravity.CENTER))
        if (exit == null) bar.addView(arrow, LinearLayout.LayoutParams(dp(24 * k), dp(24 * k)).apply { marginStart = dp(if (collapsed) 0f else 4 * k) })

        // Size the window to the (tilted) bar so empty corners never block taps.
        val barW = dp(8 * k) + dp(6 * k) + list.size * (btn + dp(4 * k)) + dp(24 * k) + dp(if (collapsed) 0f else 4 * k)
        val ang = Math.toRadians(settings.toolsAngle.toDouble())
        val bw = (abs(barW * cos(ang)) + abs(h * sin(ang))).toInt() + dp(8)
        val bh = (abs(barW * sin(ang)) + abs(h * cos(ang))).toInt() + dp(8)
        val frame = DragFrame().apply { clipChildren = false }
        frame.addView(bar, FrameLayout.LayoutParams(barW, h, Gravity.CENTER))
        bar.rotation = settings.toolsAngle.toFloat()
        r.addView(frame, FrameLayout.LayoutParams(bw, bh))
        r.alpha = if (searching || yielding) 0f else 1f
        runCatching { wm?.updateViewLayout(r, l) }
        r.post { fitZones() }
        if (animateIn) {
            // The tab morphs: a little spin as it turns into ✕ (or back into the arrow).
            bar.rotation = settings.toolsAngle - 90f
            bar.animate().rotation(settings.toolsAngle.toFloat()).setDuration(320)
                .setInterpolator(android.view.animation.PathInterpolator(0.2f, 0.9f, 0.1f, 1f)).start()
            bar.alpha = 0f; bar.scaleX = 0.7f; bar.scaleY = 0.7f
            bar.animate().alpha(1f).setDuration(220).start()
            Motion.scaleTo(bar, 1f, Motion.FLUID)
            for (i in 0 until bar.childCount) {
                val c = bar.getChildAt(i)
                c.alpha = 0f; c.animate().alpha(1f).setStartDelay(60L + i * 30L).setDuration(220).start()
            }
        }
    }

    /**
     * Docked to a side: the tools fan out in a half circle from the edge with ✕ in the middle
     * (✕ folds it into a small half-moon tab; tap the tab and it blossoms out again).
     */
    private fun rebuildEdge(r: FrameLayout, l: WindowManager.LayoutParams, right: Boolean, animateIn: Boolean) {
        val k = settings.toolsScale / 100f
        val exit = exitAction
        val folded = settings.toolsCollapsed || exit != null
        val (bg, stroke, iconCol) = colors()
        val btn = dp(36 * k)
        val ring = dp(96 * k)                                  // radius the tools sit on
        val disc = if (folded) dp(30 * k) else ring + btn / 2 + dp(12 * k)
        // The window is the WHOLE circle; half of it sits off-screen, so it looks like a half
        // circle on the edge — and stays a complete circle while you drag it away.
        val W = disc * 2; val H = disc * 2
        val inward = if (right) -1 else 1                      // +x points into the screen
        val fan = object : FrameLayout(ctx) {
            private val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = bg }
            private val rim = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                style = android.graphics.Paint.Style.STROKE; strokeWidth = maxOf(1, dp(1)).toFloat(); color = stroke
            }
            init { setWillNotDraw(false); clipChildren = false; elevation = dp(6).toFloat() }
            override fun onDraw(c: android.graphics.Canvas) {
                c.drawCircle(width / 2f, height / 2f, disc - 1f, fill)
                c.drawCircle(width / 2f, height / 2f, disc - 1f, rim)
            }
        }
        // Where something sits: (distance into the screen from the edge, offset up/down) → view position.
        fun place(v: View, size: Int, inX: Int, dy: Int) {
            fan.addView(v, FrameLayout.LayoutParams(size, size).apply {
                gravity = Gravity.TOP or Gravity.LEFT
                leftMargin = disc + inward * inX - size / 2
                topMargin = disc + dy - size / 2
            })
        }
        // Centre: ✕ in the middle of the half circle (fold / close Settings), or the arrow tab.
        val centre = android.widget.TextView(ctx).apply {
            gravity = Gravity.CENTER; setTextColor(iconCol); typeface = android.graphics.Typeface.DEFAULT_BOLD
            text = when { exit != null -> "✕"; folded -> if (right) "‹" else "›"; else -> "✕" }
            textSize = (if (folded && exit == null) 22f else 16f) * k
            contentDescription = when { exit != null -> "Close Settings"; folded -> "Show tools"; else -> "Hide tools" }
            background = if (!folded) GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.argb(46, 255, 255, 255)) } else null
            setOnClickListener { v ->
                v.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                v.animate().rotationBy(90f * inward).setDuration(180).start()
                when {
                    exit != null -> v.postDelayed({ exit() }, 150)
                    folded -> { settings.toolsCollapsed = false; rebuild(animateIn = true) }
                    else -> foldEdge(fan) { settings.toolsCollapsed = true; rebuild(animateIn = true) }
                }
            }
        }
        val cs = if (folded) dp(28 * k) else btn
        val cIn = if (folded) dp(15 * k) else (ring * 0.38f).toInt()
        place(centre, cs, cIn, 0)
        val toolViews = ArrayList<View>()
        if (!folded) {
            val list = tools()
            val n = list.size
            for ((idx, t) in list.withIndex()) {
                // Evenly spread from -62° to +62°: a balanced arc around the ✕.
                val a = Math.toRadians(-62.0 + 124.0 * idx / maxOf(1, n - 1))
                val inX = (ring * cos(a)).toInt()
                val dy = (ring * sin(a)).toInt()
                val on = t.on()
                val b = FrameLayout(ctx).apply {
                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (on) Color.WHITE else Color.argb(34, 255, 255, 255)) }
                    contentDescription = t.label
                    setOnClickListener { v ->
                        v.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                        v.animate().scaleX(0.8f).scaleY(0.8f).setDuration(80).withEndAction { Motion.scaleTo(v, 1f, Motion.BOUNCY) }.start()
                        t.tap(v)
                        main.postDelayed({ rebuild() }, 600)
                    }
                }
                b.addView(ImageView(ctx).apply { setImageResource(t.icon); setColorFilter(if (on) 0xFF1C1C1E.toInt() else iconCol) },
                    FrameLayout.LayoutParams(dp(19 * k), dp(19 * k), Gravity.CENTER))
                place(b, btn, inX, dy)
                // How far it travels to reach the ✕ (for blossoming out / folding back).
                b.tag = floatArrayOf((inward * (cIn - inX)).toFloat(), (-dy).toFloat())
                toolViews += b
            }
        }
        val frame = DragFrame().apply { clipChildren = false }
        frame.addView(fan, FrameLayout.LayoutParams(W, H))
        r.addView(frame, FrameLayout.LayoutParams(W, H))
        r.alpha = if (searching || yielding) 0f else 1f
        l.x = -disc
        l.y = l.y.coerceIn(-disc / 2, ctx.resources.displayMetrics.heightPixels - H + disc / 2)
        runCatching { wm?.updateViewLayout(r, l) }
        r.post { fitZones() }
        if (animateIn) {
            val style = settings.toolsAnim
            fan.pivotX = disc.toFloat(); fan.pivotY = disc.toFloat()
            if (style == "ripple") {
                // Circles ripple out from the edge; each tool pops in as the wave reaches it.
                val rings = RippleRings(disc, iconCol)
                fan.addView(rings, 0, FrameLayout.LayoutParams(W, H))
                rings.play()
            }
            if (style == "spin") { fan.rotation = -160f * inward; fan.animate().rotation(0f).setDuration(520)
                .setInterpolator(android.view.animation.PathInterpolator(0.2f, 0.9f, 0.1f, 1f)).start() }
            if (style == "fade") {
                fan.alpha = 0f; fan.scaleX = 0.92f; fan.scaleY = 0.92f
                fan.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(320).start()
                return
            }
            // The circle grows out of the edge, then the tools fly out of the ✕ one by one.
            fan.scaleX = 0.2f; fan.scaleY = 0.2f; fan.alpha = 0f
            fan.animate().alpha(1f).setDuration(180).start()
            Motion.spring(fan, androidx.dynamicanimation.animation.DynamicAnimation.SCALE_X, 1f, Motion.Spec(380f, 0.8f))
            Motion.spring(fan, androidx.dynamicanimation.animation.DynamicAnimation.SCALE_Y, 1f, Motion.Spec(380f, 0.8f))
            centre.alpha = 0f; centre.rotation = -90f * inward
            centre.animate().alpha(1f).rotation(0f).setStartDelay(60).setDuration(300).start()
            for ((idx, v) in toolViews.withIndex()) {
                val d = v.tag as FloatArray
                v.translationX = d[0]; v.translationY = d[1]
                v.scaleX = 0.2f; v.scaleY = 0.2f; v.alpha = 0f
                v.animate().translationX(0f).translationY(0f).scaleX(1f).scaleY(1f).alpha(1f)
                    .setStartDelay((if (style == "ripple") 160L + idx * 45L else 90L + idx * 40L))
                    .setDuration(if (style == "spin") 520 else 420)
                    .setInterpolator(android.view.animation.PathInterpolator(0.25f, 1.35f, 0.4f, 1f)).start()
            }
        }
    }

    /** Expanding rings for the "Ripple" opening. */
    private inner class RippleRings(private val maxR: Int, private val color: Int) : View(ctx) {
        private val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE; strokeWidth = dp(2).toFloat()
        }
        private var t = 0f
        fun play() {
            android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 900; interpolator = android.view.animation.DecelerateInterpolator(1.4f)
                addUpdateListener { t = it.animatedValue as Float; invalidate() }
                doOnEndCompat { (parent as? android.view.ViewGroup)?.removeView(this@RippleRings) }
                start()
            }
        }
        override fun onDraw(c: android.graphics.Canvas) {
            for (i in 0 until 3) {
                val k = (t * 1.35f - i * 0.18f).coerceIn(0f, 1f)
                if (k <= 0f) continue
                p.color = color
                p.alpha = ((1f - k) * 200).toInt()
                c.drawCircle(width / 2f, height / 2f, maxR * k, p)
            }
        }
    }

    private fun android.animation.ValueAnimator.doOnEndCompat(fn: () -> Unit) =
        addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) = fn()
        })

    /**
     * Docked to a side as a panel, like the Sidebar: tool tiles with labels in a tall rounded
     * card, and a round arrow button at the bottom that folds it into a small tab.
     */
    private fun rebuildPanel(r: FrameLayout, l: WindowManager.LayoutParams, right: Boolean, animateIn: Boolean) {
        val k = settings.toolsScale / 100f
        val exit = exitAction
        val folded = settings.toolsCollapsed || exit != null
        val (bg, stroke, iconCol) = colors()
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            if (folded) setPadding(0, 0, 0, 0) else setPadding(dp(10 * k), dp(16 * k), dp(10 * k), dp(12 * k))
            background = GradientDrawable().apply {
                setColor(bg); setStroke(maxOf(1, dp(1)), stroke)
                if (folded) cornerRadii = if (right) floatArrayOf(dp(18).toFloat(), dp(18).toFloat(), 0f, 0f, 0f, 0f, dp(18).toFloat(), dp(18).toFloat())
                    else floatArrayOf(0f, 0f, dp(18).toFloat(), dp(18).toFloat(), dp(18).toFloat(), dp(18).toFloat(), 0f, 0f)
                else cornerRadius = dp(32 * k).toFloat()
            }
            elevation = dp(10).toFloat()
        }
        val items = ArrayList<View>()
        if (folded) {
            // Small tab on the edge: ‹ / › opens it (or ✕ closes Settings).
            val tab = android.widget.TextView(ctx).apply {
                text = when { exit != null -> "✕"; right -> "‹"; else -> "›" }
                textSize = (if (exit != null) 15f else 22f) * k; setTextColor(iconCol); gravity = Gravity.CENTER
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                contentDescription = if (exit != null) "Close Settings" else "Show tools"
                setOnClickListener { v ->
                    v.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    if (exit != null) exit() else { settings.toolsCollapsed = false; rebuild(animateIn = true) }
                }
            }
            col.addView(tab, LinearLayout.LayoutParams(dp(26 * k), dp(70 * k)))
        } else {
            for (t in tools()) {
                val on = t.on()
                val item = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                    contentDescription = t.label
                    setOnTouchListener { v, e ->
                        when (e.actionMasked) {
                            MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.88f).scaleY(0.88f).setDuration(90).start()
                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> Motion.scaleTo(v, 1f, Motion.BOUNCY)
                        }
                        false
                    }
                }
                val tile = FrameLayout(ctx).apply {
                    background = GradientDrawable().apply {
                        cornerRadius = dp(14 * k).toFloat(); setColor(if (on) Color.WHITE else Color.argb(40, 255, 255, 255))
                    }
                }
                tile.addView(ImageView(ctx).apply { setImageResource(t.icon); setColorFilter(if (on) 0xFF1C1C1E.toInt() else iconCol) },
                    FrameLayout.LayoutParams(dp(24 * k), dp(24 * k), Gravity.CENTER))
                item.addView(tile, LinearLayout.LayoutParams(dp(52 * k), dp(52 * k)))
                item.addView(android.widget.TextView(ctx).apply {
                    text = t.label; textSize = 11.5f * k.coerceIn(0.8f, 1.2f); setTextColor(iconCol); gravity = Gravity.CENTER; maxLines = 1
                }, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(4) })
                item.setOnClickListener { v ->
                    v.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    t.tap(tile)
                    main.postDelayed({ rebuild() }, 600)
                }
                col.addView(item, LinearLayout.LayoutParams(dp(76 * k), WRAP).apply { bottomMargin = dp(9 * k) })
                items += item
            }
            // Round arrow button at the bottom (like the Sidebar's): folds the panel away.
            val close = android.widget.TextView(ctx).apply {
                text = if (right) "›" else "‹"; textSize = 24f * k; setTextColor(iconCol); gravity = Gravity.CENTER
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.argb(46, 255, 255, 255)) }
                contentDescription = "Hide tools"
                setOnClickListener { v ->
                    v.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    val off = dp(120).toFloat() * (if (right) 1 else -1)
                    col.animate().translationX(off).alpha(0f).setDuration(200).withEndAction {
                        settings.toolsCollapsed = true; rebuild(animateIn = true)
                    }.start()
                }
            }
            col.addView(close, LinearLayout.LayoutParams(dp(42 * k), dp(42 * k)).apply { topMargin = dp(2) })
            items += close
        }
        val frame = DragFrame().apply { clipChildren = false; setPadding(0, dp(6), 0, dp(6)) }
        frame.addView(col, FrameLayout.LayoutParams(WRAP, WRAP))
        r.addView(frame, FrameLayout.LayoutParams(WRAP, WRAP))
        r.alpha = if (searching || yielding) 0f else 1f
        l.x = if (folded) 0 else dp(8)
        l.y = l.y.coerceIn(0, ctx.resources.displayMetrics.heightPixels - dp(200))
        runCatching { wm?.updateViewLayout(r, l) }
        r.post { fitZones() }
        if (animateIn) {
            // Slides in from the edge like the Sidebar; the tiles follow one after another.
            val off = dp(110).toFloat() * (if (right) 1 else -1)
            col.post {
                col.pivotX = if (right) col.width.toFloat() else 0f; col.pivotY = col.height / 2f
                Motion.spring(col, androidx.dynamicanimation.animation.DynamicAnimation.TRANSLATION_X, 0f, Motion.Spec(340f, 0.78f), start = off)
                col.alpha = 0f; col.animate().alpha(1f).setDuration(200).start()
                for ((i, v) in items.withIndex()) {
                    v.alpha = 0f; v.translationX = off * 0.35f; v.scaleX = 0.8f; v.scaleY = 0.8f
                    v.animate().alpha(1f).translationX(0f).scaleX(1f).scaleY(1f).setStartDelay(70L + i * 28L).setDuration(320)
                        .setInterpolator(android.view.animation.PathInterpolator(0.2f, 0.9f, 0.1f, 1f)).start()
                }
            }
        }
    }
    private val WRAP = android.view.ViewGroup.LayoutParams.WRAP_CONTENT

    /** Tools fly back into the centre, then [done]. */
    private fun foldEdge(fan: FrameLayout, done: () -> Unit) {
        var last = 0L
        for (i in 0 until fan.childCount) {
            val v = fan.getChildAt(i)
            val d = v.tag as? FloatArray ?: continue
            last = maxOf(last, i * 25L)
            v.animate().translationX(d[0]).translationY(d[1]).scaleX(0.3f).scaleY(0.3f).alpha(0f)
                .setStartDelay(i * 25L).setDuration(220).start()
        }
        fan.animate().scaleX(0.4f).scaleY(0.4f).alpha(0.6f).setStartDelay(last + 120).setDuration(180)
            .withEndAction { done() }.start()
    }

    /** Hold anywhere on the bar, then drag it around the screen. */
    private inner class DragFrame : FrameLayout(ctx) {
        private var dx = 0f; private var dy = 0f; private var sx = 0; private var sy = 0
        private var dragging = false
        private val lift = Runnable {
            dragging = true
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            getChildAt(0)?.let { Motion.scaleTo(it, 1.08f, Motion.SNAPPY) }
            val now = android.os.SystemClock.uptimeMillis()
            val c = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
            (getChildAt(0) as? android.view.ViewGroup)?.let { b -> for (i in 0 until b.childCount) b.getChildAt(i).dispatchTouchEvent(c) }
            c.recycle()
        }
        override fun onInterceptTouchEvent(e: MotionEvent) = handle(e)
        override fun onTouchEvent(e: MotionEvent): Boolean { handle(e); return true }
        private fun handle(e: MotionEvent): Boolean {
            val l = lp ?: return false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { dx = e.rawX; dy = e.rawY; sx = l.x; sy = l.y; dragging = false; postDelayed(lift, 420) }
                MotionEvent.ACTION_MOVE -> {
                    if (!dragging && abs(e.rawX - dx) + abs(e.rawY - dy) > dp(10)) removeCallbacks(lift)
                    if (dragging) {
                        val fromRight = (l.gravity and Gravity.HORIZONTAL_GRAVITY_MASK) == Gravity.RIGHT
                        l.x = sx + (if (fromRight) -1 else 1) * (e.rawX - dx).toInt()
                        l.y = (sy + (e.rawY - dy).toInt()).coerceIn(0, ctx.resources.displayMetrics.heightPixels - dp(40))
                        runCatching { wm?.updateViewLayout(root, l) }
                        return true
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    removeCallbacks(lift)
                    if (dragging) {
                        dragging = false
                        getChildAt(0)?.let { Motion.scaleTo(it, 1f, Motion.BOUNCY) }
                        val d = ctx.resources.displayMetrics.density
                        // Where did it land? Near a side → dock there as a half circle.
                        val sw = ctx.resources.displayMetrics.widthPixels
                        val wv = (root?.width ?: width)
                        val cx = when {
                            (l.gravity and Gravity.HORIZONTAL_GRAVITY_MASK) == Gravity.LEFT -> l.x + wv / 2
                            (l.gravity and Gravity.HORIZONTAL_GRAVITY_MASK) == Gravity.RIGHT -> sw - l.x - wv / 2
                            else -> sw / 2 + l.x
                        }
                        // Even a little of it past a side (10%… half) → it becomes the circle on that edge.
                        val left = cx - wv / 2; val rightEdge = cx + wv / 2
                        var newEdge = when { left < dp(14) -> "left"; rightEdge > sw - dp(14) -> "right"; else -> "none" }
                        // That side belongs to the Sidebar: the tools can't dock there.
                        if (newEdge != "none" && settings.sidebarOn && (newEdge == "right") == settings.sidebarRight) {
                            toast("That side is the Sidebar's — the tools stay on the screen")
                            settings.toolsDx = ((if (newEdge == "right") sw / 2 - dp(140) else -(sw / 2 - dp(140))) / d).roundToInt()
                            newEdge = "none"
                            settings.toolsEdge = "none"
                            main.post { rebuild(animateIn = true) }
                            performHapticFeedback(HapticFeedbackConstants.REJECT)
                            settings.toolsDy = ((l.y - push - dp(64)) / d).roundToInt()
                            return true
                        }
                        if (newEdge == "none") settings.toolsDx = ((cx - sw / 2) / d).roundToInt()
                        settings.toolsDy = ((l.y - push - dp(64)) / d).roundToInt()
                        val changed = newEdge != settings.toolsEdge
                        settings.toolsEdge = newEdge
                        performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                        if (!changed && newEdge != "none") {
                            // Same edge: glide back into place (no rebuild, no flash).
                            val target = -(wv / 2)
                            android.animation.ValueAnimator.ofInt(l.x, target).apply {
                                duration = 260; interpolator = android.view.animation.DecelerateInterpolator(1.8f)
                                addUpdateListener { a -> l.x = a.animatedValue as Int; runCatching { wm?.updateViewLayout(root, l) } }
                                start()
                            }
                        } else if (changed) main.post { rebuild(animateIn = true) }
                        return true
                    }
                }
            }
            return dragging
        }
    }

    // ================================================================ actions

    private fun toast(t: String) = main.post { Toast.makeText(ctx, t, Toast.LENGTH_SHORT).show() }

    private fun setTorch(on: Boolean) {
        val cm = ctx.getSystemService(android.hardware.camera2.CameraManager::class.java) ?: return
        runCatching {
            val id = cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it).get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return
            cm.setTorchMode(id, on); torchOn = on
        }
    }

    /** 25 → 50 → 75 → 100 % → 25 %… */
    private fun cycleBrightness() {
        if (!android.provider.Settings.System.canWrite(ctx)) {
            toast("Allow “Modify system settings” to change brightness")
            runCatching {
                ctx.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            return
        }
        runCatching {
            val cr = ctx.contentResolver
            android.provider.Settings.System.putInt(cr, android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE,
                android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            val cur = android.provider.Settings.System.getInt(cr, android.provider.Settings.System.SCREEN_BRIGHTNESS, 128)
            val steps = listOf(64, 128, 191, 255)
            val next = steps.firstOrNull { it > cur + 8 } ?: steps.first()
            android.provider.Settings.System.putInt(cr, android.provider.Settings.System.SCREEN_BRIGHTNESS, next)
            toast("Brightness ${(next * 100f / 255).roundToInt()}%")
        }
    }

    private fun toggleGameMode() {
        val nm = ctx.getSystemService(android.app.NotificationManager::class.java)
        if (!nm.isNotificationPolicyAccessGranted) {
            toast("Allow Do Not Disturb access for Game Mode")
            runCatching { ctx.startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            return
        }
        val on = !settings.gameMode
        settings.gameMode = on
        runCatching {
            nm.setInterruptionFilter(if (on) android.app.NotificationManager.INTERRUPTION_FILTER_PRIORITY
                else android.app.NotificationManager.INTERRUPTION_FILTER_ALL)
        }
        toast(if (on) "🎮 Game Mode on — notifications silenced" else "Game Mode off")
    }
}
