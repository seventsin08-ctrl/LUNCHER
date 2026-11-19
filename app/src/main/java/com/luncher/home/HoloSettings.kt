package com.luncher.home

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * The launcher's settings as a hologram (Iron Man style): tapping ⚙ in the island spins an
 * arc-reactor ring, the Home Screen fades away, and glass category panels flicker in with a
 * cyan glow. Tap a panel to open that page; the ✕ button folds everything away and brings the
 * apps back.
 */
class HoloSettings(
    private val act: Activity,
    private val root: FrameLayout,
    /** Views to fade out while it's open (the Home Screen, dock, island…). */
    private val fadeTargets: () -> List<View>
) {
    private class Cat(val id: String, val title: String, val sub: String, val icon: Int)
    private val cats = listOf(
        Cat("setup", "Permissions", "Setup & access", R.drawable.ic_cc_lock),
        Cat("look", "Appearance", "Theme · wallpaper", R.drawable.ic_cc_contrast),
        Cat("home", "Home Screen", "Apps · widgets", R.drawable.ic_set_grid),
        Cat("island", "Dynamic Island", "Island · side island · chats", R.drawable.ic_set_island),
        Cat("sidebar", "Sidebar", "Apps · size · edge", R.drawable.ic_set_sidebar),
        Cat("motion", "Motion", "Animations · gestures", R.drawable.ic_set_hand),
        Cat("sound", "Sounds", "Ringtone · haptics", R.drawable.ic_cc_bell),
        Cat("phone", "Connections", "Phone · Wi-Fi · Bluetooth", R.drawable.ic_cc_wifi),
        Cat("backup", "Backup", "Export · import", R.drawable.ic_set_backup),
        Cat("general", "General", "About · reset", R.drawable.ic_set_gear)
    )

    private fun dp(v: Int) = (v * act.resources.displayMetrics.density).roundToInt()
    private val CYAN = 0xFF64D2FF.toInt()
    private val ease = PathInterpolator(0.2f, 0.9f, 0.1f, 1f)
    private var layer: FrameLayout? = null
    private val faded = ArrayList<View>()
    val isOpen get() = layer != null

    fun open() {
        if (layer != null) return
        val l = FrameLayout(act).apply { isClickable = true; isFocusable = true }
        val bg = HoloBackdrop(act)
        l.addView(bg, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        // 1. The reactor: a spinning ring with the gear in the middle.
        val reactor = ReactorView(act)
        val rs = dp(150)
        l.addView(reactor, FrameLayout.LayoutParams(rs, rs, Gravity.CENTER))

        // 2. Header + category panels (appear after the spin).
        val content = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; alpha = 0f }
        val head = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(56), 0, dp(14))
        }
        val title = TextView(act).apply {
            text = ""; textSize = 22f; setTextColor(Color.WHITE); letterSpacing = 0.18f
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            setShadowLayer(dp(10).toFloat(), 0f, 0f, CYAN)
        }
        val subtitle = TextView(act).apply {
            text = "APPLE LAUNCHER · SYSTEM CONFIGURATION"; textSize = 10.5f; setTextColor(0xAA64D2FF.toInt()); letterSpacing = 0.22f
            typeface = Typeface.create("monospace", Typeface.NORMAL)
        }
        head.addView(title); head.addView(subtitle, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(4) })
        content.addView(head)

        val scroll = ScrollView(act).apply { isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER }
        val grid = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(4), dp(16), dp(120)) }
        val panels = ArrayList<View>()
        for (row in cats.chunked(2)) {
            val r = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
            for ((i, c) in row.withIndex()) {
                val p = panel(c)
                panels += p
                r.addView(p, LinearLayout.LayoutParams(0, dp(112), 1f).apply {
                    if (i == 0) marginEnd = dp(6) else marginStart = dp(6)
                })
            }
            if (row.size == 1) r.addView(View(act), LinearLayout.LayoutParams(0, dp(112), 1f).apply { marginStart = dp(6) })
            grid.addView(r, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(12) })
        }
        scroll.addView(grid)
        content.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        l.addView(content, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        // 3. Exit button.
        val exit = TextView(act).apply {
            text = "✕   EXIT"; textSize = 13f; setTextColor(CYAN); letterSpacing = 0.25f; gravity = Gravity.CENTER
            typeface = Typeface.create("monospace", Typeface.BOLD)
            setShadowLayer(dp(8).toFloat(), 0f, 0f, CYAN)
            background = GradientDrawable().apply {
                cornerRadius = dp(26).toFloat(); setColor(0x2264D2FF); setStroke(dp(1), 0xAA64D2FF.toInt())
            }
            alpha = 0f
            setOnClickListener { performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK); close() }
        }
        l.addView(exit, FrameLayout.LayoutParams(dp(150), dp(50), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(44) })

        root.addView(l, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        layer = l
        l.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)

        // Home Screen fades away.
        faded.clear()
        for (v in fadeTargets()) if (v !== l && v.visibility == View.VISIBLE) {
            faded += v
            v.animate().alpha(0f).scaleX(0.96f).scaleY(0.96f).setDuration(320).start()
        }
        bg.alpha = 0f
        bg.animate().alpha(1f).setDuration(380).start()

        // Reactor spins up in the middle, then lifts away as the panels come in.
        reactor.alpha = 0f; reactor.scaleX = 0.3f; reactor.scaleY = 0.3f; reactor.rotation = -180f
        reactor.animate().alpha(1f).scaleX(1f).scaleY(1f).rotation(0f).setDuration(620).setInterpolator(ease)
            .withEndAction {
                if (layer !== l) return@withEndAction
                reactor.animate().translationY(-(root.height / 2f - dp(40))).scaleX(0.32f).scaleY(0.32f).alpha(0.9f)
                    .setDuration(420).setInterpolator(ease).start()
                content.alpha = 1f
                typeOut(title, "SETTINGS")
                for ((i, p) in panels.withIndex()) {
                    p.alpha = 0f; p.scaleX = 0.7f; p.scaleY = 0.7f; p.rotationX = -35f; p.translationY = dp(20).toFloat()
                    p.cameraDistance = 9000f * act.resources.displayMetrics.density
                    p.animate().alpha(1f).scaleX(1f).scaleY(1f).rotationX(0f).translationY(0f)
                        .setStartDelay(120L + i * 55L).setDuration(460).setInterpolator(ease)
                        .withEndAction { flicker(p) }.start()
                }
                exit.animate().alpha(1f).setStartDelay(500).setDuration(300).start()
            }.start()
    }

    fun close() {
        val l = layer ?: return
        layer = null
        l.isClickable = false
        // Panels fold back, then everything fades and the apps return.
        val content = l.getChildAt(2)
        content.animate().alpha(0f).scaleX(0.92f).scaleY(0.92f).setDuration(260).start()
        l.animate().alpha(0f).setStartDelay(160).setDuration(300).withEndAction { root.removeView(l) }.start()
        for (v in faded) v.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(120).setDuration(380).setInterpolator(ease).start()
        faded.clear()
    }

    /** Opening a page: a quick pulse, then the regular Settings page on top. */
    private fun openPage(id: String, v: View) {
        v.animate().scaleX(1.06f).scaleY(1.06f).setDuration(110).withEndAction {
            v.animate().scaleX(1f).scaleY(1f).setDuration(160).start()
            runCatching {
                act.startActivity(Intent(act, SettingsActivity::class.java).putExtra("page", id))
                act.overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            }
        }.start()
    }

    private fun panel(c: Cat): View {
        val p = HoloPanel(act)
        val inner = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(14), dp(12))
        }
        val icon = ImageView(act).apply { setImageResource(c.icon); setColorFilter(CYAN) }
        inner.addView(icon, LinearLayout.LayoutParams(dp(24), dp(24)))
        inner.addView(View(act), LinearLayout.LayoutParams(0, 0, 1f))
        inner.addView(TextView(act).apply {
            text = c.title.uppercase(); textSize = 13f; setTextColor(Color.WHITE); letterSpacing = 0.08f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD); maxLines = 1
            setShadowLayer(dp(6).toFloat(), 0f, 0f, 0x8864D2FF.toInt())
        })
        inner.addView(TextView(act).apply {
            text = c.sub; textSize = 10.5f; setTextColor(0xAA9FE6FF.toInt()); maxLines = 1
            typeface = Typeface.create("monospace", Typeface.NORMAL)
        })
        p.addView(inner, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        p.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.95f).scaleY(0.95f).setDuration(90).start()
                MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
            }
            false
        }
        p.setOnClickListener { v -> v.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK); openPage(c.id, v) }
        return p
    }

    /** Hologram flicker as a panel locks in. */
    private fun flicker(v: View) {
        ValueAnimator.ofFloat(1f, 0.55f, 1f, 0.75f, 1f).apply {
            duration = 260; addUpdateListener { v.alpha = it.animatedValue as Float }; start()
        }
    }

    /** Letters appear one by one, like a HUD readout. */
    private fun typeOut(t: TextView, text: String) {
        for (i in 0..text.length) t.postDelayed({ t.text = text.substring(0, i) + if (i < text.length) "▌" else "" }, 60L * i)
    }

    // ================================================================ drawing

    /** Dark blue-black backdrop with a faint grid, a soft glow and a scan line sweeping down. */
    private inner class HoloBackdrop(ctx: Activity) : View(ctx) {
        private val fill = Paint()
        private val grid = Paint().apply { color = 0x1464D2FF; strokeWidth = 1f }
        private val scan = Paint()
        private var t = 0f
        private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 3200; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
            addUpdateListener { t = it.animatedValue as Float; invalidate() }
        }
        override fun onAttachedToWindow() { super.onAttachedToWindow(); anim.start() }
        override fun onDetachedFromWindow() { anim.cancel(); super.onDetachedFromWindow() }
        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            fill.shader = RadialGradient(w / 2, h * 0.38f, h * 0.7f, intArrayOf(0xF0061826.toInt(), 0xF5020A12.toInt(), 0xFA000204.toInt()),
                floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w, h, fill)
            val step = dp(28).toFloat()
            var x = (w % step) / 2; while (x < w) { c.drawLine(x, 0f, x, h, grid); x += step }
            var y = 0f; while (y < h) { c.drawLine(0f, y, w, y, grid); y += step }
            val sy = h * t
            scan.shader = LinearGradient(0f, sy - dp(80), 0f, sy, intArrayOf(0x0064D2FF, 0x2264D2FF), null, Shader.TileMode.CLAMP)
            c.drawRect(0f, sy - dp(80), w, sy, scan)
        }
    }

    /** Arc-reactor ring: rotating dashed arcs around a gear. */
    private inner class ReactorView(ctx: Activity) : View(ctx) {
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
        private val gear = act.getDrawable(R.drawable.ic_set_gear)!!.mutate().apply { setTint(Color.WHITE) }
        private var a = 0f
        private val anim = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 4000; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
            addUpdateListener { a = it.animatedValue as Float; invalidate() }
        }
        override fun onAttachedToWindow() { super.onAttachedToWindow(); anim.start() }
        override fun onDetachedFromWindow() { anim.cancel(); super.onDetachedFromWindow() }
        override fun onDraw(c: Canvas) {
            val cx = width / 2f; val cy = height / 2f; val r = minOf(cx, cy)
            glow.shader = RadialGradient(cx, cy, r, intArrayOf(0x6664D2FF, 0x1864D2FF, 0x0064D2FF), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
            c.drawCircle(cx, cy, r, glow)
            val rects = listOf(0.92f, 0.74f, 0.56f)
            for ((i, k) in rects.withIndex()) {
                val rr = r * k
                val box = RectF(cx - rr, cy - rr, cx + rr, cy + rr)
                ring.strokeWidth = dp(if (i == 1) 4 else 2).toFloat()
                ring.color = if (i == 1) CYAN else 0xAA64D2FF.toInt()
                val dir = if (i % 2 == 0) 1 else -1
                val segs = 3 + i * 2
                val sweep = 360f / segs
                for (s in 0 until segs) c.drawArc(box, a * dir * (1 + i * 0.4f) + s * sweep, sweep * 0.62f, false, ring)
            }
            val g = (r * 0.62f).toInt()
            c.save(); c.rotate(-a * 0.5f, cx, cy)
            gear.setBounds((cx - g / 2).toInt(), (cy - g / 2).toInt(), (cx + g / 2).toInt(), (cy + g / 2).toInt()); gear.draw(c)
            c.restore()
        }
    }

    /** Glass panel with a cyan rim and corner brackets. */
    private inner class HoloPanel(ctx: Activity) : FrameLayout(ctx) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(1).toFloat(); color = 0x7764D2FF }
        private val corner = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(2).toFloat(); color = CYAN; strokeCap = Paint.Cap.ROUND }
        private val path = Path()
        init { setWillNotDraw(false); isClickable = true }
        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat(); val r = dp(14).toFloat()
            fill.shader = LinearGradient(0f, 0f, w, h, intArrayOf(0x3364D2FF, 0x0E64D2FF, 0x2264D2FF), null, Shader.TileMode.CLAMP)
            val box = RectF(1f, 1f, w - 1, h - 1)
            c.drawRoundRect(box, r, r, fill)
            c.drawRoundRect(box, r, r, rim)
            val L = dp(14).toFloat(); val i = dp(3).toFloat()
            path.reset()
            path.moveTo(i, i + L); path.lineTo(i, i); path.lineTo(i + L, i)
            path.moveTo(w - i - L, h - i); path.lineTo(w - i, h - i); path.lineTo(w - i, h - i - L)
            c.drawPath(path, corner)
        }
    }
}
