package com.luncher.home

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import java.util.Calendar
import kotlin.math.roundToInt

/**
 * Landscape Home Screen (iPhone feature 36): turn the phone sideways and the Home Screen lays itself
 * out like an iPhone in landscape — clock and status rings on the left, your apps in the middle,
 * the dock standing up on the right. The launcher window stays portrait; this page is drawn turned
 * to match the phone, and the status/nav bars hide while it shows.
 */
class LandscapeHome(act: Activity, root: FrameLayout, stage: Ios.Stage) : IosLayer(act, root, stage, "landscape") {
    var turn = -90f
    var apps: (() -> List<AppInfo>)? = null
    var dock: (() -> List<AppInfo>)? = null
    var onLaunch: ((AppInfo, View) -> Unit)? = null
    var battery: (() -> Int)? = null
    var charging: (() -> Boolean)? = null
    var showLabels = true
    private var land: View? = null
    private val hiddenUnder = ArrayList<Pair<View, Int>>()

    private fun dp(v: Int) = (v * act.resources.displayMetrics.density).roundToInt()
    private val ease = PathInterpolator(0.2f, 0.9f, 0.1f, 1f)

    override fun build(l: FrameLayout) {
        l.setBackgroundColor(0x26000000)
        val dm = act.resources.displayMetrics
        val W = maxOf(dm.widthPixels, dm.heightPixels)   // landscape width
        val H = minOf(dm.widthPixels, dm.heightPixels)   // landscape height
        val side = dp(34); val vpad = dp(14)
        val row = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(side, vpad, side, vpad)
            rotation = turn
        }
        land = row
        val innerH = H - vpad * 2

        // ---- Left: two square widgets (clock, status rings), like the picture.
        val wsz = ((innerH - dp(14)) / 2).coerceAtMost(dp(170))
        val left = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        left.addView(FrameLayout(act).apply {
            background = GradientDrawable().apply { cornerRadius = dp(28).toFloat(); setColor(Color.WHITE) }
            addView(AnalogClockView(act), FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT).apply { setMargins(dp(8), dp(8), dp(8), dp(8)) })
        }, LinearLayout.LayoutParams(wsz, wsz))
        left.addView(rings(), LinearLayout.LayoutParams(wsz, wsz).apply { topMargin = dp(14) })
        row.addView(left, LinearLayout.LayoutParams(WRAP_CONTENT, MATCH_PARENT))

        // ---- Middle: your apps, paged.
        val dockW = dp(78)
        val gridW = W - side * 2 - wsz - dockW - dp(16) * 2
        val cell = dp(84)
        val cols = (gridW / cell).coerceIn(3, 7)
        val rows = ((innerH - dp(16)) / dp(if (showLabels) 86 else 72)).coerceIn(2, 5)
        val list = apps?.invoke().orEmpty()
        val per = cols * rows
        val pageCount = ((list.size + per - 1) / per).coerceAtLeast(1)
        val pages = (0 until pageCount).map { p -> grid(list.drop(p * per).take(per), cols, rows) }
        val middle = FrameLayout(act)
        val vp = ViewPager2(act).apply { adapter = Pages(pages); offscreenPageLimit = 1 }
        (vp.getChildAt(0) as? RecyclerView)?.overScrollMode = View.OVER_SCROLL_NEVER
        middle.addView(vp, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT).apply { bottomMargin = dp(14) })
        val dots = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val dotViews = (0 until pageCount).map { i -> View(act).apply { background = Glass.circle(Color.WHITE); alpha = if (i == 0) 1f else 0.35f } }
        if (pageCount > 1) dotViews.forEach { dots.addView(it, LinearLayout.LayoutParams(dp(6), dp(6)).apply { setMargins(dp(4), 0, dp(4), 0) }) }
        middle.addView(dots, FrameLayout.LayoutParams(MATCH_PARENT, dp(10), Gravity.BOTTOM))
        vp.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                dotViews.forEachIndexed { i, d -> d.animate().alpha(if (i == position) 1f else 0.35f).setDuration(150).start() }
                vp.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            }
        })
        row.addView(middle, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f).apply { setMargins(dp(16), 0, dp(16), 0) })

        // ---- Right: the dock, standing up.
        val dockApps = dock?.invoke().orEmpty().take(5)
        val dockCol = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(dp(10), dp(12), dp(10), dp(12))
            background = Glass.panel(act, 30f)
        }
        for (a in dockApps) dockCol.addView(icon(a, false), LinearLayout.LayoutParams(dp(56), dp(56)).apply { setMargins(0, dp(5), 0, dp(5)) })
        if (dockApps.isNotEmpty()) row.addView(dockCol, LinearLayout.LayoutParams(dockW, WRAP_CONTENT))

        // A W×H box turned 90° fills the portrait window exactly.
        l.addView(row, FrameLayout.LayoutParams(W, H, Gravity.CENTER))
    }

    private class Pages(private val views: List<View>) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = views.size
        override fun getItemViewType(position: Int) = position
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val v = views[viewType]; (v.parent as? ViewGroup)?.removeView(v)
            v.layoutParams = RecyclerView.LayoutParams(MATCH_PARENT, MATCH_PARENT)
            return object : RecyclerView.ViewHolder(v) {}
        }
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {}
    }

    private fun grid(list: List<AppInfo>, cols: Int, rows: Int): View {
        val g = GridLayout(act).apply { columnCount = cols; rowCount = rows }
        for (a in list) g.addView(icon(a, showLabels), GridLayout.LayoutParams(
            GridLayout.spec(GridLayout.UNDEFINED, 1f), GridLayout.spec(GridLayout.UNDEFINED, 1f)).apply { width = 0; height = 0 })
        // Fill empty cells so the grid keeps its shape on the last page.
        repeat(cols * rows - list.size) {
            g.addView(View(act), GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED, 1f), GridLayout.spec(GridLayout.UNDEFINED, 1f)).apply { width = 0; height = 0 })
        }
        return g
    }

    private fun icon(a: AppInfo, label: Boolean): View {
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        col.addView(ImageView(act).apply { setImageBitmap(a.icon) }, LinearLayout.LayoutParams(dp(54), dp(54)))
        if (label) col.addView(TextView(act).apply {
            text = a.label; textSize = 11f; setTextColor(Color.WHITE); maxLines = 1; gravity = Gravity.CENTER
            ellipsize = android.text.TextUtils.TruncateAt.END; setShadowLayer(dp(3).toFloat(), 0f, dp(1).toFloat(), 0x99000000.toInt())
        }, LinearLayout.LayoutParams(dp(78), WRAP_CONTENT).apply { topMargin = dp(4) })
        col.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> Motion.scaleTo(v, 0.86f, Motion.SNAPPY)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> Motion.scaleTo(v, 1f, Motion.BOUNCY)
            }
            false
        }
        col.setOnClickListener { v -> v.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK); onLaunch?.invoke(a, v) }
        return col
    }

    /** 2×2 status rings: battery, steps, date, and charging/clock — like iPhone's Batteries widget. */
    private fun rings(): View {
        val box = GridLayout(act).apply {
            columnCount = 2; rowCount = 2
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = GradientDrawable().apply { cornerRadius = dp(28).toFloat(); setColor(0x33FFFFFF); setStroke(1, 0x33FFFFFF) }
        }
        val lvl = battery?.invoke() ?: 0
        val steps = InfoData.stepsToday
        val cal = Calendar.getInstance()
        val dayFrac = cal.get(Calendar.DAY_OF_MONTH) / cal.getActualMaximum(Calendar.DAY_OF_MONTH).toFloat()
        val items = listOf(
            Triple(if (charging?.invoke() == true) "⚡" else "📱", lvl / 100f, if (lvl <= 20) 0xFFFF453A.toInt() else 0xFF30D158.toInt()),
            Triple("👟", if (steps < 0) 0f else (steps / 10_000f).coerceAtMost(1f), 0xFF30D158.toInt()),
            Triple("${cal.get(Calendar.DAY_OF_MONTH)}", dayFrac, 0xFFFF9F0A.toInt()),
            Triple("☀", (cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)) / 1440f, 0xFF64D2FF.toInt())
        )
        for ((t, f, c) in items) box.addView(Ring(act, t, f, c), GridLayout.LayoutParams(
            GridLayout.spec(GridLayout.UNDEFINED, 1f), GridLayout.spec(GridLayout.UNDEFINED, 1f)).apply {
            width = 0; height = 0; setMargins(dp(4), dp(4), dp(4), dp(4)) })
        return box
    }

    private class Ring(c: Context, private val label: String, private val frac: Float, private val color: Int) : View(c) {
        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER; typeface = Typeface.create("sans-serif-medium", Typeface.BOLD) }
        private val box = RectF()
        private var shown = 0f
        init {
            android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 900; startDelay = 200; interpolator = PathInterpolator(0.2f, 0.9f, 0.1f, 1f)
                addUpdateListener { shown = it.animatedValue as Float; invalidate() }; start()
            }
        }
        override fun onDraw(cv: Canvas) {
            val s = minOf(width, height).toFloat(); if (s <= 0) return
            val sw = s * 0.11f
            track.strokeWidth = sw; arc.strokeWidth = sw
            track.color = Color.argb(60, Color.red(color), Color.green(color), Color.blue(color)); arc.color = color
            val l = (width - s) / 2f + sw; val t = (height - s) / 2f + sw
            box.set(l, t, l + s - sw * 2, t + s - sw * 2)
            cv.drawArc(box, 0f, 360f, false, track)
            cv.drawArc(box, -90f, 360f * frac.coerceIn(0f, 1f) * shown, false, arc)
            txt.textSize = s * (if (label.length > 2) 0.26f else 0.32f)
            cv.drawText(label, width / 2f, height / 2f + txt.textSize * 0.36f, txt)
        }
    }

    override fun enter(l: FrameLayout) {
        // Everything of the portrait Home Screen steps aside (the wallpaper stays).
        hiddenUnder.clear()
        for (i in 0 until root.childCount) {
            val v = root.getChildAt(i)
            if (v === l || v is SpatialImageView || v is SkyView) continue
            if (i <= 2 && v !is ViewGroup) continue   // wallpaper scrim stays
            hiddenUnder += v to v.visibility
            v.visibility = View.INVISIBLE
        }
        setBars(false)
        l.alpha = 0f
        l.animate().alpha(1f).setDuration((260 * Motion.scale).toLong()).start()
        // Rotate into place, like the iPhone turning its Home Screen.
        land?.let { v ->
            v.rotation = turn * 0.72f; v.scaleX = 0.9f; v.scaleY = 0.9f
            v.animate().rotation(turn).scaleX(1f).scaleY(1f).setDuration((420 * Motion.scale).toLong()).setInterpolator(ease).start()
        }
    }

    override fun exit(l: FrameLayout, done: () -> Unit) {
        land?.animate()?.rotation(turn * 0.72f)?.scaleX(0.9f)?.scaleY(0.9f)?.setDuration((260 * Motion.scale).toLong())?.setInterpolator(ease)?.start()
        l.animate().alpha(0f).setDuration((260 * Motion.scale).toLong()).withEndAction(done).start()
    }

    override fun onHidden() {
        for ((v, vis) in hiddenUnder) v.visibility = vis
        hiddenUnder.clear()
        setBars(true)
    }

    private fun setBars(show: Boolean) {
        val w = act.window ?: return
        if (Build.VERSION.SDK_INT >= 30) {
            val c = w.insetsController ?: return
            if (show) c.show(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
            else {
                c.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                c.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
            }
        }
    }
}
