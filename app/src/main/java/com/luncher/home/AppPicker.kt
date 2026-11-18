package com.luncher.home

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.ViewOutlineProvider
import android.view.Window
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * iOS-style app chooser: app icons, search, Select All, round checkmarks.
 * Apps listed in [featured] (e.g. music apps) are shown first under their own header.
 */
object AppPicker {
    class App(val pkg: String, val label: String)

    /** Every app you can open, sorted by name (this launcher excluded). */
    fun allApps(act: Activity): List<App> {
        val pm = act.packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(main, 0)
            .map { App(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
            .filter { it.pkg != act.packageName }
            .distinctBy { it.pkg }
            .sortedBy { it.label.lowercase() }
    }

    /** Apps that play music or podcasts (media browser services and music-category apps). */
    fun musicPackages(act: Activity): Set<String> {
        val pm = act.packageManager
        val out = HashSet<String>()
        runCatching {
            pm.queryIntentServices(Intent("android.media.browse.MediaBrowserService"), 0)
                .forEach { out += it.serviceInfo.packageName }
        }
        runCatching {
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MUSIC), 0)
                .forEach { out += it.activityInfo.packageName }
        }
        runCatching {
            pm.getInstalledApplications(0).forEach {
                if (it.category == android.content.pm.ApplicationInfo.CATEGORY_AUDIO) out += it.packageName
            }
        }
        return out
    }

    fun show(
        act: Activity,
        title: String,
        apps: List<App>,
        current: Set<String>,
        featuredTitle: String? = null,
        featured: Set<String> = emptySet(),
        onDone: (Set<String>) -> Unit
    ) {
        val d = act.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).roundToInt()
        val chosen = LinkedHashSet(current)
        val icons = HashMap<String, Drawable?>()
        val io = Executors.newFixedThreadPool(2)
        val main = Handler(Looper.getMainLooper())

        val dialog = Dialog(act)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val card = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(30).toFloat()
                setColor(0xFA1C1C1E.toInt())
                setStroke(maxOf(1, dp(1)), 0x22FFFFFF)
            }
            setPadding(0, dp(18), 0, dp(8))
        }

        // Header: Cancel · Title · Done
        val header = FrameLayout(act).apply { setPadding(dp(18), 0, dp(18), 0) }
        fun headerBtn(text: String, bold: Boolean, g: Int, fn: () -> Unit) = TextView(act).apply {
            this.text = text; textSize = 17f; setTextColor(0xFF0A84FF.toInt())
            typeface = Typeface.create("sans-serif-medium", if (bold) Typeface.BOLD else Typeface.NORMAL)
            setPadding(dp(4), dp(6), dp(4), dp(6))
            setOnClickListener { fn() }
        }.also { header.addView(it, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, g or Gravity.CENTER_VERTICAL)) }
        headerBtn("Cancel", false, Gravity.START) { dialog.dismiss() }
        headerBtn("Done", true, Gravity.END) { onDone(LinkedHashSet(chosen)); dialog.dismiss() }
        header.addView(TextView(act).apply {
            text = title; textSize = 17f; setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER))
        card.addView(header)

        // Search field
        val search = EditText(act).apply {
            hint = "Search"
            setHintTextColor(0x80FFFFFF.toInt()); setTextColor(Color.WHITE); textSize = 16f
            setSingleLine()
            background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(0x26FFFFFF) }
            setPadding(dp(14), dp(9), dp(14), dp(9))
        }
        card.addView(search, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            setMargins(dp(16), dp(14), dp(16), dp(6))
        })

        // "x selected" · Select All
        val countRow = FrameLayout(act).apply { setPadding(dp(20), dp(4), dp(20), dp(4)) }
        val count = TextView(act).apply { textSize = 13f; setTextColor(0x99FFFFFF.toInt()) }
        val selectAll = TextView(act).apply { textSize = 14f; setTextColor(0xFF0A84FF.toInt()); setPadding(dp(6), dp(6), 0, dp(6)) }
        countRow.addView(count, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.START or Gravity.CENTER_VERTICAL))
        countRow.addView(selectAll, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.END or Gravity.CENTER_VERTICAL))
        card.addView(countRow)

        // Rows: headers (String) and apps (App)
        var rows: List<Any> = emptyList()
        val list = RecyclerView(act).apply { layoutManager = LinearLayoutManager(act); overScrollMode = View.OVER_SCROLL_NEVER }

        fun build(q: String) {
            val match = apps.filter { q.isBlank() || it.label.contains(q, true) }
            val top = match.filter { it.pkg in featured }
            val rest = match.filter { it.pkg !in featured }
            val r = ArrayList<Any>()
            if (top.isNotEmpty() && featuredTitle != null) { r += featuredTitle; r.addAll(top); if (rest.isNotEmpty()) r += "All Apps" }
            r.addAll(if (featuredTitle != null) rest else match)
            rows = r
        }

        fun refreshCount() {
            count.text = "${chosen.size} selected"
            selectAll.text = if (apps.isNotEmpty() && apps.all { it.pkg in chosen }) "Deselect All" else "Select All"
        }

        val adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = rows.size
            override fun getItemViewType(position: Int) = if (rows[position] is String) 0 else 1

            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                if (viewType == 0) {
                    val tv = TextView(act).apply {
                        textSize = 13f; setTextColor(0x99FFFFFF.toInt())
                        setPadding(dp(20), dp(14), dp(20), dp(6))
                        layoutParams = RecyclerView.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                    }
                    return object : RecyclerView.ViewHolder(tv) {}
                }
                val row = LinearLayout(act).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(18), dp(8), dp(18), dp(8))
                    layoutParams = RecyclerView.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                    isClickable = true
                    background = android.graphics.drawable.RippleDrawable(
                        android.content.res.ColorStateList.valueOf(0x22FFFFFF), null, ColorDrawable(Color.WHITE))
                }
                val icon = ImageView(act).apply {
                    outlineProvider = object : ViewOutlineProvider() {
                        override fun getOutline(view: View, outline: Outline) =
                            outline.setRoundRect(0, 0, view.width, view.height, view.width * 0.24f)
                    }
                    clipToOutline = true
                }
                row.addView(icon, LinearLayout.LayoutParams(dp(42), dp(42)))
                row.addView(TextView(act).apply {
                    textSize = 17f; setTextColor(Color.WHITE); maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = dp(14) })
                row.addView(CheckView(act), LinearLayout.LayoutParams(dp(26), dp(26)))
                return object : RecyclerView.ViewHolder(row) {}
            }

            override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int) {
                val item = rows[position]
                if (item is String) { (h.itemView as TextView).text = item.uppercase(); return }
                val app = item as App
                val row = h.itemView as LinearLayout
                val icon = row.getChildAt(0) as ImageView
                (row.getChildAt(1) as TextView).text = app.label
                val check = row.getChildAt(2) as CheckView
                check.checked = app.pkg in chosen
                icon.tag = app.pkg
                if (icons.containsKey(app.pkg)) icon.setImageDrawable(icons[app.pkg])
                else {
                    icon.setImageDrawable(null)
                    io.execute {
                        val dr = runCatching { act.packageManager.getApplicationIcon(app.pkg) }.getOrNull()
                        main.post {
                            icons[app.pkg] = dr
                            if (icon.tag == app.pkg) icon.setImageDrawable(dr)
                        }
                    }
                }
                row.setOnClickListener {
                    if (!chosen.add(app.pkg)) chosen.remove(app.pkg)
                    check.checked = app.pkg in chosen
                    it.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    refreshCount()
                }
            }
        }
        list.adapter = adapter
        val h = (act.resources.displayMetrics.heightPixels * 0.62f).toInt()
        card.addView(list, LinearLayout.LayoutParams(MATCH_PARENT, h))

        build("")
        refreshCount()
        selectAll.setOnClickListener {
            if (apps.all { it.pkg in chosen }) chosen.clear() else apps.forEach { chosen += it.pkg }
            refreshCount(); adapter.notifyDataSetChanged()
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { build(s?.toString().orEmpty()); adapter.notifyDataSetChanged() }
        })

        dialog.setContentView(card)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout((act.resources.displayMetrics.widthPixels * 0.92f).toInt(), WRAP_CONTENT)
            setDimAmount(0.5f)
        }
        dialog.setOnDismissListener { io.shutdownNow() }
        dialog.show()
    }

    /** Round iOS checkmark: blue filled with a tick when on, thin ring when off. */
    class CheckView(ctx: android.content.Context) : View(ctx) {
        var checked = false
            set(v) { field = v; invalidate() }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0x66FFFFFF }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF0A84FF.toInt() }
        private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; color = Color.WHITE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        private val path = Path()

        override fun onDraw(c: Canvas) {
            val r = minOf(width, height) / 2f
            val cx = width / 2f; val cy = height / 2f
            if (checked) {
                c.drawCircle(cx, cy, r, fill)
                tick.strokeWidth = r * 0.2f
                path.reset()
                path.moveTo(cx - r * 0.42f, cy + r * 0.02f)
                path.lineTo(cx - r * 0.1f, cy + r * 0.34f)
                path.lineTo(cx + r * 0.45f, cy - r * 0.3f)
                c.drawPath(path, tick)
            } else {
                ring.strokeWidth = r * 0.1f
                c.drawCircle(cx, cy, r - ring.strokeWidth, ring)
            }
        }
    }
}
