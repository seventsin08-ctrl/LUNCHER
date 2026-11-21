package com.luncher.home

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings as SysSettings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Game mode's floating ball: a little Gilbert-style orb that appears only while a game is on
 * screen. Drag it anywhere (it snaps to the nearest edge); tap it for the game tools —
 * mainly who may pop up during the game (Everyone / Priority only / No one), the messages you
 * missed, and your Sidebar apps (they open in a small window over the game).
 * Pop-ups that are allowed show as a small banner next to the ball, never over the middle.
 */
class GameBall(private val ctx: Context, private val apps: () -> List<AppInfo>) {
    companion object {
        /** The ball on screen right now (null when not gaming). */
        var instance: GameBall? = null
            private set
    }

    private class Missed(val pkg: String, val title: String, val text: String, val time: Long)

    private val s = Settings(ctx)
    private val main = Handler(Looper.getMainLooper())
    private val dm get() = ctx.resources.displayMetrics
    private fun dp(v: Int) = (v * dm.density).roundToInt()
    private fun dpf(v: Float) = v * dm.density
    private var wm: WindowManager? = null
    private var ball: FrameLayout? = null
    private var face: IslandFace? = null
    private var badge: TextView? = null
    private var ballLp: WindowManager.LayoutParams? = null
    private var panel: View? = null
    private var banner: View? = null
    private val missed = ArrayList<Missed>()
    private val size get() = dp(52)

    private fun type() = if (LuncherAccessibility.instance != null) WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        else WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    private fun winCtx(): Context = LuncherAccessibility.instance ?: ctx
    private fun canShow() = LuncherAccessibility.instance != null || SysSettings.canDrawOverlays(ctx)

    private fun baseLp(w: Int, h: Int, extra: Int = 0) = WindowManager.LayoutParams(w, h, type(),
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or extra, PixelFormat.TRANSLUCENT).apply {
        gravity = Gravity.TOP or Gravity.LEFT
        if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
    }

    // ------------------------------------------------------------------ the ball
    fun attach() {
        if (ball != null || !s.gameBall || !canShow()) return
        val c = winCtx()
        val w = c.getSystemService(WindowManager::class.java) ?: return
        val f = FrameLayout(c).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xF0000000.toInt()); setStroke(maxOf(1, dp(1)), 0x55FFFFFF) }
            elevation = dpf(8f)
        }
        val fc = IslandFace(c).apply { runCatching { configure(s) } }
        f.addView(fc, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        val b = TextView(c).apply {
            textSize = 10f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            setPadding(dp(4), 0, dp(4), 0); minWidth = dp(18)
            background = GradientDrawable().apply { cornerRadius = dpf(9f); setColor(0xFFFF3B30.toInt()) }
            visibility = View.GONE
        }
        val holder = FrameLayout(c).apply { clipChildren = false; clipToPadding = false }
        holder.addView(f, FrameLayout.LayoutParams(size, size, Gravity.BOTTOM or Gravity.LEFT))
        holder.addView(b, FrameLayout.LayoutParams(WRAP_CONTENT, dp(18), Gravity.TOP or Gravity.RIGHT))
        val lp = baseLp(size + dp(8), size + dp(8))
        place(lp)
        holder.setOnTouchListener(DragTouch())
        if (runCatching { w.addView(holder, lp) }.isFailure) return
        DragPass.watch(w, holder)
        wm = w; ball = holder; face = fc; badge = b; ballLp = lp; instance = this
        holder.scaleX = 0.2f; holder.scaleY = 0.2f; holder.alpha = 0f
        holder.animate().alpha(1f).setDuration(200).start()
        Motion.scaleTo(holder, 1f, Motion.BOUNCY)
        fc.react("excited", 1500)
        updateBadge()
    }

    fun detach() {
        closePanel(); hideBanner()
        ball?.let { v -> runCatching { wm?.removeViewImmediate(v) } }
        ball = null; face = null; badge = null; ballLp = null
        if (instance === this) instance = null
    }

    private fun place(lp: WindowManager.LayoutParams) {
        val W = dm.widthPixels; val H = dm.heightPixels
        val bw = size + dp(8)
        lp.x = if (s.gameBallRight) W - bw - dp(4) else dp(4)
        lp.y = (H * s.gameBallY).roundToInt().coerceIn(dp(8), H - bw - dp(8))
    }

    private fun updateBadge() {
        val n = missed.size
        badge?.apply { text = if (n > 99) "99+" else "$n"; visibility = if (n > 0) View.VISIBLE else View.GONE }
        face?.badge = 0
    }

    private inner class DragTouch : View.OnTouchListener {
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            val lp = ballLp ?: return false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y; moved = false
                    ball?.animate()?.scaleX(0.9f)?.scaleY(0.9f)?.setDuration(120)?.start()
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (!moved && abs(dx) + abs(dy) > dp(8)) { moved = true; closePanel() }
                    if (moved) {
                        lp.x = (startX + dx).roundToInt(); lp.y = (startY + dy).roundToInt()
                        runCatching { wm?.updateViewLayout(ball, lp) }
                        face?.lookAt(e.rawX, e.rawY)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    Motion.scaleTo(ball ?: return true, 1f, Motion.BOUNCY)
                    if (moved) snap() else if (e.actionMasked == MotionEvent.ACTION_UP) { SoundFX.play(ctx, "pop"); togglePanel() }
                }
            }
            return true
        }
    }

    /** Glide to the nearest side and remember where it is. */
    private fun snap() {
        val lp = ballLp ?: return
        val W = dm.widthPixels; val H = dm.heightPixels
        val bw = size + dp(8)
        val right = lp.x + bw / 2 > W / 2
        val toX = if (right) W - bw - dp(4) else dp(4)
        val toY = lp.y.coerceIn(dp(8), H - bw - dp(8))
        s.gameBallRight = right; s.gameBallY = toY / H.toFloat()
        val fx = lp.x; val fy = lp.y
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 320; interpolator = DecelerateInterpolator(2f)
            addUpdateListener {
                val t = it.animatedValue as Float
                lp.x = (fx + (toX - fx) * t).roundToInt(); lp.y = (fy + (toY - fy) * t).roundToInt()
                runCatching { wm?.updateViewLayout(ball, lp) }
            }
        }.start()
        SoundFX.play(ctx, "tick")
    }

    /** Screen moved (rotation): keep the ball on its side. */
    fun reposition() { val lp = ballLp ?: return; place(lp); runCatching { wm?.updateViewLayout(ball, lp) }; closePanel() }

    // ------------------------------------------------------------------ notifications during a game
    /** A notification arrived while gaming. Returns true if it was shown as a banner. */
    fun onNotification(pkg: String, title: String, text: String, icon: Bitmap?): Boolean {
        val allowed = when (s.gamePopups) { "all" -> true; "priority" -> pkg in s.gilbertPriority; else -> false }
        missed.add(Missed(pkg, title, text, System.currentTimeMillis()))
        while (missed.size > 60) missed.removeAt(0)
        updateBadge()
        face?.react(if (allowed) "surprised" else "curious", 1200)
        if (allowed) showBanner(pkg, title, text, icon)
        return allowed
    }

    private val hideBannerRun = Runnable { hideBanner() }
    private fun hideBanner() {
        main.removeCallbacks(hideBannerRun)
        val v = banner ?: return
        banner = null
        v.animate().alpha(0f).setDuration(160).withEndAction { runCatching { wm?.removeViewImmediate(v) } }.start()
    }

    /** Small banner beside the ball (never over the middle of the game). */
    private fun showBanner(pkg: String, title: String, text: String, icon: Bitmap?) {
        val w = wm ?: return
        val blp0 = ballLp ?: return
        hideBanner()
        val c = winCtx()
        val row = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(14), dp(8))
            background = GradientDrawable().apply { cornerRadius = dpf(20f); setColor(0xF21C1C1E.toInt()); setStroke(maxOf(1, dp(1)), 0x2EFFFFFF) }
            elevation = dpf(8f)
        }
        if (icon != null) row.addView(roundIcon(c, icon, 28), LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginEnd = dp(10) })
        row.addView(LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(c).apply { this.text = title.ifBlank { appName(pkg) }; textSize = 13f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD; maxLines = 1 })
            addView(TextView(c).apply { this.text = text; textSize = 12f; setTextColor(0xB3FFFFFF.toInt()); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        }, LinearLayout.LayoutParams(dp(210), WRAP_CONTENT))
        row.setOnClickListener { hideBanner(); open(pkg) }
        val lp = baseLp(WRAP_CONTENT, WRAP_CONTENT)
        val right = s.gameBallRight
        lp.gravity = Gravity.TOP or (if (right) Gravity.RIGHT else Gravity.LEFT)
        lp.x = size + dp(16); lp.y = blp0.y + dp(4)
        if (runCatching { w.addView(row, lp) }.isFailure) return
        DragPass.watch(w, row)
        banner = row
        row.alpha = 0f; row.translationX = dpf(if (right) 30f else -30f)
        row.animate().alpha(1f).translationX(0f).setDuration(260).setInterpolator(DecelerateInterpolator(2f)).start()
        SoundFX.play(ctx, "blip")
        main.postDelayed(hideBannerRun, 3500)
    }

    // ------------------------------------------------------------------ the tools panel
    private fun closePanel() {
        val v = panel ?: return
        panel = null
        v.animate().alpha(0f).scaleX(0.85f).scaleY(0.85f).setDuration(160).withEndAction { runCatching { wm?.removeViewImmediate(v) } }.start()
    }

    private fun togglePanel() { if (panel != null) { SoundFX.play(ctx, "close"); closePanel() } else showPanel() }

    private fun showPanel() {
        val w = wm ?: return
        val blp = ballLp ?: return
        hideBanner()
        val c = winCtx()
        val card = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xF73A3A3E.toInt(), 0xF7232326.toInt(), 0xF7161618.toInt())).apply {
                cornerRadius = dpf(26f); setStroke(maxOf(1, dp(1)), 0x2EFFFFFF) }
            elevation = dpf(12f)
        }
        // Header
        card.addView(LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(TextView(c).apply { text = "🎮  Game Mode"; textSize = 16f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD },
                LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(TextView(c).apply {
                text = "✕"; textSize = 13f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x26FFFFFF) }
                setOnClickListener { SoundFX.play(ctx, "close"); closePanel() }
            }, LinearLayout.LayoutParams(dp(28), dp(28)))
        })
        // Pop-ups: Everyone / Priority / No one
        card.addView(label(c, "POP-UPS DURING GAMES"))
        val seg = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(dp(3), dp(3), dp(3), dp(3))
            background = GradientDrawable().apply { cornerRadius = dpf(14f); setColor(0x1FFFFFFF) }
        }
        val opts = listOf("all" to "Everyone", "priority" to "★ Priority", "off" to "No one")
        val segViews = ArrayList<TextView>()
        fun paint() {
            for ((i, v) in segViews.withIndex()) {
                val on = opts[i].first == s.gamePopups
                v.setTextColor(if (on) Color.BLACK else Color.WHITE)
                v.background = GradientDrawable().apply { cornerRadius = dpf(11f); setColor(if (on) Color.WHITE else Color.TRANSPARENT) }
            }
        }
        for ((key, name) in opts) {
            val t = TextView(c).apply {
                text = name; textSize = 12.5f; gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(12), dp(7), dp(12), dp(7))
                setOnClickListener {
                    s.gamePopups = key; paint(); SoundFX.play(ctx, "tick")
                    face?.react(if (key == "off") "sleepy" else "happy", 1500)
                }
            }
            segViews += t; seg.addView(t, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }
        paint()
        card.addView(seg, LinearLayout.LayoutParams(dp(270), WRAP_CONTENT))
        card.addView(TextView(c).apply {
            text = "Everything else waits here — nothing covers your game"; textSize = 11f; setTextColor(0x80FFFFFF.toInt())
            setPadding(dp(4), dp(5), 0, 0)
        })

        // Missed messages
        card.addView(label(c, if (missed.isEmpty()) "MESSAGES · none yet" else "MESSAGES · ${missed.size}"))
        if (missed.isNotEmpty()) {
            val list = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
            for (m in missed.asReversed().take(4)) {
                list.addView(LinearLayout(c).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(4), 0, dp(4))
                    iconFor(m.pkg)?.let { addView(roundIcon(c, it, 24), LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(9) }) }
                    addView(TextView(c).apply {
                        text = listOf(m.title, m.text).filter { it.isNotBlank() }.joinToString(": ")
                        textSize = 13f; setTextColor(Color.WHITE); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                    }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                    setOnClickListener { closePanel(); open(m.pkg) }
                })
            }
            card.addView(list, LinearLayout.LayoutParams(dp(270), WRAP_CONTENT))
            card.addView(TextView(c).apply {
                text = "Clear"; textSize = 12f; setTextColor(0xFFFF453A.toInt()); typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(4), dp(4), dp(4), dp(2))
                setOnClickListener { missed.clear(); updateBadge(); SoundFX.play(ctx, "tick"); closePanel() }
            })
        }

        // Sidebar apps → small window over the game
        val all = apps()
        val fav = s.sidebarApps.mapNotNull { k -> all.firstOrNull { it.key == k || it.packageName == k } }
        if (fav.isNotEmpty()) {
            card.addView(label(c, "APPS · open in a small window"))
            val row = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
            for (a in fav) row.addView(ImageView(c).apply {
                setImageBitmap(a.icon)
                outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(v: View, o: android.graphics.Outline) = o.setRoundRect(0, 0, v.width, v.height, v.width * 0.26f)
                }; clipToOutline = true
                setOnClickListener { SoundFX.play(ctx, "open"); closePanel(); GameState.launch(ctx, a.component) }
            }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(10) })
            card.addView(HorizontalScrollView(c).apply { isHorizontalScrollBarEnabled = false; addView(row) },
                LinearLayout.LayoutParams(dp(270), WRAP_CONTENT))
        }

        // Not a game?
        card.addView(TextView(c).apply {
            text = "Not a game? Turn off for this app"; textSize = 11.5f; setTextColor(0xFF0A84FF.toInt())
            setPadding(dp(4), dp(12), 0, 0)
            setOnClickListener { GameState.foreground?.let { GameState.setGame(ctx, it, false) }; closePanel() }
        })

        // Place it beside the ball, kept on screen.
        card.measure(View.MeasureSpec.makeMeasureSpec(dm.widthPixels, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(dm.heightPixels, View.MeasureSpec.AT_MOST))
        val cw = card.measuredWidth; val ch = card.measuredHeight
        val lp = baseLp(WRAP_CONTENT, WRAP_CONTENT, WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH)
        val right = s.gameBallRight
        lp.x = if (right) blp.x - cw - dp(6) else blp.x + size + dp(14)
        lp.y = (blp.y - dp(10)).coerceIn(dp(8), (dm.heightPixels - ch - dp(8)).coerceAtLeast(dp(8)))
        card.setOnTouchListener { _, e -> if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) { closePanel(); true } else false }
        if (runCatching { w.addView(card, lp) }.isFailure) return
        DragPass.watch(w, card)
        panel = card
        card.pivotX = if (right) cw.toFloat() else 0f; card.pivotY = dpf(30f)
        card.alpha = 0f; card.scaleX = 0.4f; card.scaleY = 0.4f
        card.animate().alpha(1f).setDuration(180).start()
        Motion.scaleTo(card, 1f, Motion.FLUID)
        face?.react("happy", 1200)
    }

    // ------------------------------------------------------------------ helpers
    private fun label(c: Context, t: String) = TextView(c).apply {
        text = t; textSize = 11f; setTextColor(0x8CFFFFFF.toInt()); letterSpacing = 0.05f; typeface = Typeface.DEFAULT_BOLD
        setPadding(dp(4), dp(12), 0, dp(6))
    }
    private fun roundIcon(c: Context, bmp: Bitmap, sizeDp: Int) = ImageView(c).apply {
        setImageBitmap(bmp)
        outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(v: View, o: android.graphics.Outline) = o.setRoundRect(0, 0, v.width, v.height, v.width * 0.28f)
        }; clipToOutline = true
    }
    private fun appName(pkg: String) = apps().firstOrNull { it.packageName == pkg }?.label
        ?: runCatching { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
    private fun iconFor(pkg: String): Bitmap? = apps().firstOrNull { it.packageName == pkg }?.icon
    private fun open(pkg: String) {
        missed.removeAll { it.pkg == pkg }; updateBadge()
        val a = apps().firstOrNull { it.packageName == pkg }
        if (a != null) GameState.launch(ctx, a.component)
        else ctx.packageManager.getLaunchIntentForPackage(pkg)?.let { i ->
            i.component?.let { GameState.launch(ctx, it) } }
    }
}
