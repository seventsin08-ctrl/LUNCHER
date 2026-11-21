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

    /** A folder's name and apps, or null if [AppInfo] isn't a folder. */
    var folderOf: ((AppInfo) -> Pair<String, List<AppInfo>>?)? = null
    /** Hide / bring back the launcher's floating extras (island, sidebars, tools) around this page. */
    var onChrome: ((Boolean) -> Unit)? = null
    private var pageLayer: FrameLayout? = null
    private var folderLayer: View? = null
    private var W = 0
    private var H = 0

    override fun build(l: FrameLayout) {
        pageLayer = l
        l.setBackgroundColor(0x33000000)
        // Use the real window size (bars hide while this shows), not the smaller display size.
        val rw = root.width.takeIf { it > 0 } ?: act.resources.displayMetrics.widthPixels
        val rh = root.height.takeIf { it > 0 } ?: act.resources.displayMetrics.heightPixels
        W = maxOf(rw, rh); H = minOf(rw, rh)
        val dockApps = dock?.invoke().orEmpty().take(5)
        val g = Ios.landLayout(W, H, act.resources.displayMetrics.density, showLabels, dockApps.isNotEmpty())
        val page = FrameLayout(act).apply { rotation = turn; clipChildren = false }
        land = page

        // ---- Left: clock and status rings.
        page.addView(FrameLayout(act).apply {
            background = material(28)
            addView(AnalogClockView(act), FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT).apply { setMargins(dp(10), dp(10), dp(10), dp(10)) })
            setOnClickListener { runCatching { act.startActivity(android.content.Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } }
        }, at(g.left, g.top, g.widget, g.widget))
        page.addView(rings(), at(g.left, g.top + g.widget + dp(14), g.widget, g.widget))

        // ---- Middle: your apps (folders open sideways too), paged.
        val list = apps?.invoke().orEmpty()
        val per = g.cols * g.rows
        val pageCount = ((list.size + per - 1) / per).coerceAtLeast(1)
        val pages = (0 until pageCount).map { p -> grid(list.drop(p * per).take(per), g.cols, g.rows) }
        val middle = FrameLayout(act)
        val vp = ViewPager2(act).apply { adapter = Pages(pages); offscreenPageLimit = 1 }
        (vp.getChildAt(0) as? RecyclerView)?.overScrollMode = View.OVER_SCROLL_NEVER
        middle.addView(vp, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT).apply { bottomMargin = dp(16) })
        val dots = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val dotViews = (0 until pageCount).map { i -> View(act).apply { background = Glass.circle(Color.WHITE); alpha = if (i == 0) 1f else 0.35f } }
        if (pageCount > 1) dotViews.forEach { dots.addView(it, LinearLayout.LayoutParams(dp(6), dp(6)).apply { setMargins(dp(4), 0, dp(4), 0) }) }
        middle.addView(dots, FrameLayout.LayoutParams(MATCH_PARENT, dp(10), Gravity.BOTTOM))
        vp.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                dotViews.forEachIndexed { i, d -> d.animate().alpha(if (i == position) 1f else 0.35f).setDuration(150).start() }
                if (position > 0) vp.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            }
        })
        page.addView(middle, at(g.gridLeft, g.top, g.gridW, g.innerH))

        // ---- Right: the dock, standing up, centred.
        val shownDock = dockApps.take(g.dockSlots)
        if (shownDock.isNotEmpty()) {
            val dockCol = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                setPadding(dp(10), dp(12), dp(10), dp(12))
                background = Glass.panel(act, 30f)
            }
            for (a in shownDock) dockCol.addView(icon(a, false), LinearLayout.LayoutParams(dp(56), dp(56)).apply { setMargins(0, dp(5), 0, dp(5)) })
            val dockH = (shownDock.size * dp(66) + dp(24)).coerceAtMost(g.innerH)
            page.addView(dockCol, at(g.dockLeft, g.top + (g.innerH - dockH) / 2, g.dockW, dockH))
        }

        // A W×H box turned 90° fills the portrait window exactly.
        l.addView(page, FrameLayout.LayoutParams(W, H, Gravity.CENTER))
    }

    private fun at(x: Int, y: Int, w: Int, h: Int) = FrameLayout.LayoutParams(w, h).apply { leftMargin = x; topMargin = y }

    /** The same dark frosted material as the Today View cards. */
    private fun material(r: Int) = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(Color.argb(150, 44, 44, 50), Color.argb(170, 24, 24, 28))).apply {
        cornerRadius = dp(r).toFloat(); setStroke(maxOf(1, dp(1) / 2 + 1), Color.argb(34, 255, 255, 255))
    }

    /** A folder opened in landscape: a glass panel that zooms up in the middle, turned like the page. */
    private fun openFolder(name: String, members: List<AppInfo>, from: View) {
        val l = pageLayer ?: return
        closeFolder(immediate = true)
        val holder = FrameLayout(act).apply { rotation = turn; setBackgroundColor(0x66000000); isClickable = true; setOnClickListener { closeFolder() } }
        val cols = if (members.size <= 4) 2 else if (members.size <= 9) 3 else 4
        val panel = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL; isClickable = true
            setPadding(dp(18), dp(14), dp(18), dp(14))
            background = material(30)
        }
        panel.addView(TextView(act).apply { text = name; textSize = 20f; setTextColor(Color.WHITE); typeface = Typeface.create("sans-serif-medium", Typeface.BOLD) })
        val g = GridLayout(act).apply { columnCount = cols }
        for (a in members) g.addView(icon(a, true, inFolder = true), GridLayout.LayoutParams().apply { width = dp(84); height = dp(88) })
        val sv = android.widget.ScrollView(act).apply { isVerticalScrollBarEnabled = false; addView(g) }
        panel.addView(sv, LinearLayout.LayoutParams(WRAP_CONTENT, minOf(((members.size + cols - 1) / cols) * dp(88), H - dp(110))).apply { topMargin = dp(8) })
        holder.addView(panel, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER))
        l.addView(holder, FrameLayout.LayoutParams(W, H, Gravity.CENTER))
        folderLayer = holder
        holder.alpha = 0f
        holder.animate().alpha(1f).setDuration(180).start()
        Motion.scaleTo(panel, 1f, Motion.FLUID, start = 0.4f)
        from.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
    }

    private fun closeFolder(immediate: Boolean = false) {
        val f = folderLayer ?: return
        folderLayer = null
        if (immediate) { pageLayer?.removeView(f); return }
        f.animate().alpha(0f).setDuration(160).withEndAction { pageLayer?.removeView(f) }.start()
    }

    /** Back closes an open folder first. */
    fun back(): Boolean { if (folderLayer != null) { closeFolder(); return true }; return false }

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

    private fun icon(a: AppInfo, label: Boolean, inFolder: Boolean = false): View {
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
        col.setOnClickListener { v ->
            val folder = if (inFolder) null else folderOf?.invoke(a)
            if (folder != null) { openFolder(folder.first, folder.second, v); return@setOnClickListener }
            v.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            closeFolder(immediate = true)
            onLaunch?.invoke(a, v)
        }
        return col
    }

    /** 2×2 status rings: battery, steps, date, and charging/clock — like iPhone's Batteries widget. */
    private fun rings(): View {
        val box = GridLayout(act).apply {
            columnCount = 2; rowCount = 2
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = material(28)
        }
        val lvl = battery?.invoke() ?: 0
        val steps = InfoData.stepsToday
        val cal = Calendar.getInstance()
        val dayFrac = cal.get(Calendar.DAY_OF_MONTH) / cal.getActualMaximum(Calendar.DAY_OF_MONTH).toFloat()
        val temp = InfoData.weather?.temp
        val items = listOf(
            Triple(if (charging?.invoke() == true) "⚡$lvl" else "$lvl%", lvl / 100f, if (lvl <= 20) 0xFFFF453A.toInt() else 0xFF30D158.toInt()),
            Triple(when { steps < 0 -> "—"; steps >= 1000 -> String.format(java.util.Locale.US, "%.1fk", steps / 1000f); else -> "$steps" },
                if (steps < 0) 0f else (steps / 10_000f).coerceAtMost(1f), 0xFF34C759.toInt()),
            Triple("${cal.get(Calendar.DAY_OF_MONTH)}", dayFrac, 0xFFFF9F0A.toInt()),
            Triple(if (temp != null) "$temp°" else android.text.format.DateFormat.format("H:mm", cal).toString(),
                (cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)) / 1440f, 0xFF64D2FF.toInt())
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
            txt.textSize = s * (if (label.length > 3) 0.2f else if (label.length > 2) 0.24f else 0.3f)
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
        onChrome?.invoke(false)
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
        folderLayer = null; pageLayer = null
        onChrome?.invoke(true)
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
