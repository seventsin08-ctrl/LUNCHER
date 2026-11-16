package com.luncher.home

import android.app.Activity
import android.app.AppOpsManager
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings as SysSettings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.core.app.NotificationManagerCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.math.roundToInt

/** iOS-style grouped settings screen for Luncher. */
class SettingsActivity : Activity() {

    private lateinit var s: Settings
    private lateinit var list: LinearLayout
    private var previewRow: LinearLayout? = null
    private var scrollY = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        s = Settings(this)
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.BLACK)
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(list, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        setContentView(scroll)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            list.setPadding(dp(16), bars.top + dp(8), dp(16), bars.bottom + dp(32))
            insets
        }
        scroll.viewTreeObserver.addOnScrollChangedListener { scrollY = scroll.scrollY }
        build()
    }

    override fun onResume() {
        super.onResume()
        // Permission rows may have changed while the user was in system settings.
        rebuild()
    }

    private fun rebuild() {
        val sv = list.parent as? ScrollView
        val y = scrollY
        build()
        sv?.post { sv.scrollTo(0, y) }
    }

    private fun build() {
        list.removeAllViews()

        list.addView(TextView(this).apply {
            text = "Settings"
            setTextColor(Color.WHITE)
            textSize = 34f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setPadding(dp(4), dp(8), 0, dp(14))
        })

        // ---------------- Appearance
        val look = section("APPEARANCE")
        previewRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(16), dp(8), dp(16))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF6E6E73.toInt(), 0xFF2C2C30.toInt(), 0xFF141416.toInt())
            ).apply { cornerRadius = dp(12).toFloat() }
        }
        addRow(look, previewRow!!, pad = true)
        refreshPreview()
        segment(look, "Icon Style", listOf(
            "clear" to "Clear", "tinted" to "Tinted", "dark" to "Dark", "light" to "Light", "color" to "Color"
        ), s.iconStyle) { s.iconStyle = it; refreshPreview() }
        swatches(look, "Accent Color", s.tint) { s.tint = it; refreshPreview() }
        toggle(look, "Glass Effect", "See-through widgets, dock and icons", s.glass) { s.glass = it }
        toggle(look, "Tint Widgets & Dock", "Use the accent color on glass panels", s.tintPanels) { s.tintPanels = it }
        slider(look, "Wallpaper Dimming", 0, 80, s.dim, { "$it%" }) { s.dim = it }
        button(look, "Dark Glass Wallpaper", 0xFF0A84FF.toInt()) {
            s.pendingEdit = false
            getSharedPreferences("luncher", MODE_PRIVATE).edit().putBoolean("s_apply_wallpaper", true).apply()
            finish()
        }
        button(look, "Choose Wallpaper…", 0xFF0A84FF.toInt()) {
            runCatching {
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), "Choose wallpaper"))
            }
        }

        // ---------------- Home Screen
        val home = section("HOME SCREEN")
        slider(home, "Icon Size", 50, 70, s.iconSize, { "${it}" }) { s.iconSize = it }
        toggle(home, "Show App Names", null, s.showLabels) { s.showLabels = it }
        segment(home, "Columns", listOf("4" to "4", "5" to "5"), s.columns.toString()) { s.columns = it.toInt() }
        toggle(home, "Notification Badges", null, s.badges) { s.badges = it }
        if (!hasNotificationAccess()) {
            button(home, "Allow Badges & Now Playing…", 0xFF0A84FF.toInt()) {
                runCatching { startActivity(Intent(SysSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
            }
        }
        button(home, "Edit Home Screen", 0xFF0A84FF.toInt()) {
            s.pendingEdit = true
            finish()
        }
        val hidden = s.hiddenApps
        if (hidden.isNotEmpty()) {
            button(home, "Show ${hidden.size} Hidden App${if (hidden.size == 1) "" else "s"}", 0xFF0A84FF.toInt()) {
                s.hiddenApps = emptySet()
                rebuild()
            }
        }
        button(home, "Reset Home Screen Layout", 0xFFFF453A.toInt()) {
            s.order = emptyList()
            s.hiddenWidgets = emptySet()
            getSharedPreferences("luncher", MODE_PRIVATE).edit().remove("widget_positions").apply()
            rebuild()
        }

        // ---------------- Widgets
        val widgets = section("WIDGETS")
        toggle(widgets, "Photos", null, s.widgetEnabled("PHOTO")) { s.setWidgetEnabled("PHOTO", it) }
        toggle(widgets, "Clock & Calendar Icons", null, s.widgetEnabled("ICONS")) { s.setWidgetEnabled("ICONS", it) }
        toggle(widgets, "Calendar", null, s.widgetEnabled("CALENDAR")) { s.setWidgetEnabled("CALENDAR", it) }
        toggle(widgets, "Now Playing", null, s.widgetEnabled("MUSIC")) { s.setWidgetEnabled("MUSIC", it) }
        toggle(widgets, "Batteries", null, s.widgetEnabled("BATTERY")) { s.setWidgetEnabled("BATTERY", it) }
        if (s.hiddenWidgets.isNotEmpty()) {
            button(widgets, "Restore Removed Widgets", 0xFF0A84FF.toInt()) {
                s.hiddenWidgets = emptySet()
                rebuild()
            }
        }

        // ---------------- Animations
        val anim = section("ANIMATIONS")
        toggle(anim, "Animations", "Turn off for instant, motion-free transitions", s.animations) {
            s.animations = it
            rebuild()
        }
        if (s.animations) {
            segment(anim, "Speed", listOf("slow" to "Slow", "normal" to "Normal", "fast" to "Fast"), s.animSpeed) {
                s.animSpeed = it
            }
            segment(anim, "Opening Apps", listOf(
                "zoom" to "Zoom", "fade" to "Fade", "slide" to "Slide Up", "none" to "None"
            ), s.launchAnim) { s.launchAnim = it }
            segment(anim, "Page Swipe", listOf(
                "depth" to "Depth", "flat" to "Flat", "cube" to "Cube", "zoom" to "Zoom"
            ), s.pageEffect) { s.pageEffect = it }
        }
        toggle(anim, "Blur Effects", if (Build.VERSION.SDK_INT >= 31) null else "Needs Android 12 or newer", s.blur) {
            s.blur = it
        }
        toggle(anim, "Haptic Feedback", null, s.haptics) { s.haptics = it }

        // ---------------- Gestures
        val gest = section("GESTURES")
        segment(gest, "Swipe Down", listOf(
            "search" to "Search", "notifications" to "Notifications", "none" to "Off"
        ), s.swipeDown) { s.swipeDown = it }
        segment(gest, "Swipe Up", listOf("none" to "Off", "library" to "Library"), s.swipeUp) {
            s.swipeUp = it
            rebuild()
        }
        toggle(gest, "Double-Tap to Lock", "Double-tap an empty spot on the Home Screen", s.doubleTapLock) {
            s.doubleTapLock = it
            rebuild()
        }
        val needsHelper = s.doubleTapLock
        if (needsHelper || LuncherAccessibility.instance != null) {
            info(
                gest,
                if (LuncherAccessibility.instance != null) "Gesture helper is on ✓"
                else "Double-Tap to Lock needs the Apple Launcher Gestures helper."
            )
            if (LuncherAccessibility.instance == null) {
                button(gest, "Turn On Apple Launcher Gestures…", 0xFF0A84FF.toInt()) {
                    runCatching { startActivity(Intent(SysSettings.ACTION_ACCESSIBILITY_SETTINGS)) }
                }
            }
        }
        // ---------------- About
        val about = section("GENERAL")
        button(about, "Default Home App…", 0xFF0A84FF.toInt()) {
            runCatching { startActivity(Intent(SysSettings.ACTION_HOME_SETTINGS)) }
        }
        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: ""
        info(about, "Apple Launcher $version")
    }

    private fun refreshPreview() {
        val row = previewRow ?: return
        row.removeAllViews()
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val apps = pm.queryIntentActivities(intent, 0).filter { it.activityInfo.packageName != packageName }.take(5)
        val px = dp(52) * 2
        for (ri in apps) {
            val bmp = runCatching { IconFactory.render(ri.loadIcon(pm), px, s.iconStyle, s.tint) }.getOrNull() ?: continue
            row.addView(ImageView(this).apply { setImageBitmap(bmp) },
                LinearLayout.LayoutParams(dp(52), dp(52)).apply { setMargins(dp(6), 0, dp(6), 0) })
        }
    }

    // ================================================================ building blocks

    private fun section(title: String): LinearLayout {
        list.addView(TextView(this).apply {
            text = title
            setTextColor(0xFF8E8E93.toInt())
            textSize = 13f
            setPadding(dp(16), dp(18), 0, dp(7))
        })
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(0xFF1C1C1E.toInt())
            }
        }
        list.addView(card, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        return card
    }

    private fun addRow(card: LinearLayout, v: View, pad: Boolean = false) {
        if (card.childCount > 0) {
            card.addView(View(this).apply { setBackgroundColor(0xFF38383A.toInt()) },
                LinearLayout.LayoutParams(MATCH_PARENT, maxOf(1, dp(0.5f))).apply { marginStart = dp(16) })
        }
        if (pad) {
            val wrap = FrameLayout(this).apply { setPadding(dp(12), dp(12), dp(12), dp(12)) }
            wrap.addView(v, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            card.addView(wrap, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        } else {
            card.addView(v, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
    }

    private fun title(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(Color.WHITE)
        textSize = 17f
    }

    private fun subtitle(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(0xFF8E8E93.toInt())
        textSize = 13f
    }

    private fun toggle(card: LinearLayout, label: String, sub: String?, value: Boolean, onChange: (Boolean) -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(11), dp(12), dp(11))
            minimumHeight = dp(48)
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(title(label))
        if (sub != null) texts.addView(subtitle(sub))
        row.addView(texts, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        val sw = Switch(this).apply {
            isChecked = value
            thumbTintList = ColorStateList.valueOf(Color.WHITE)
            trackTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(0xFF30D158.toInt(), 0xFF39393D.toInt())
            )
            setOnCheckedChangeListener { _, checked -> onChange(checked) }
        }
        row.addView(sw)
        row.setOnClickListener { sw.toggle() }
        addRow(card, row)
    }

    private fun segment(
        card: LinearLayout,
        label: String,
        options: List<Pair<String, String>>,
        selected: String,
        onChange: (String) -> Unit
    ) {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(11), dp(16), dp(12))
        }
        col.addView(title(label))
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(2), dp(2), dp(2), dp(2))
            background = GradientDrawable().apply {
                cornerRadius = dp(9).toFloat()
                setColor(0xFF2C2C2E.toInt())
            }
        }
        var current = selected
        val cells = ArrayList<TextView>()
        fun paint() {
            options.forEachIndexed { i, (key, _) ->
                val on = key == current
                cells[i].background = if (on) GradientDrawable().apply {
                    cornerRadius = dp(7).toFloat()
                    setColor(0xFF636366.toInt())
                } else null
                cells[i].typeface = Typeface.create("sans-serif-medium", if (on) Typeface.BOLD else Typeface.NORMAL)
            }
        }
        for ((key, name) in options) {
            val tv = TextView(this).apply {
                text = name
                setTextColor(Color.WHITE)
                textSize = 13f
                gravity = Gravity.CENTER
                maxLines = 1
                setPadding(dp(2), dp(7), dp(2), dp(7))
                setOnClickListener {
                    if (current != key) {
                        current = key
                        paint()
                        onChange(key)
                    }
                }
            }
            cells += tv
            bar.addView(tv, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }
        paint()
        col.addView(bar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) })
        addRow(card, col)
    }

    private fun slider(
        card: LinearLayout,
        label: String,
        min: Int,
        max: Int,
        value: Int,
        fmt: (Int) -> String,
        onChange: (Int) -> Unit
    ) {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(11), dp(16), dp(8))
        }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        head.addView(title(label), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        val valueText = subtitle(fmt(value)).apply { textSize = 15f }
        head.addView(valueText)
        col.addView(head)
        val bar = SeekBar(this).apply {
            this.max = max - min
            progress = value - min
            progressTintList = ColorStateList.valueOf(0xFF0A84FF.toInt())
            thumbTintList = ColorStateList.valueOf(Color.WHITE)
            progressBackgroundTintList = ColorStateList.valueOf(0xFF48484A.toInt())
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    valueText.text = fmt(p + min)
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) = onChange(sb.progress + min)
            })
        }
        col.addView(bar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(6) })
        addRow(card, col)
    }

    private fun swatches(card: LinearLayout, label: String, selected: Int, onChange: (Int) -> Unit) {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(11), dp(16), dp(14))
        }
        col.addView(title(label))
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        var current = selected
        val dots = ArrayList<View>()
        fun paint() {
            Settings.TINTS.forEachIndexed { i, c ->
                dots[i].background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(c)
                    if (c == current) setStroke(dp(3), 0xFF0A84FF.toInt())
                    else setStroke(maxOf(1, dp(1)), 0x33FFFFFF)
                }
                dots[i].scaleX = if (c == current) 1.12f else 1f
                dots[i].scaleY = if (c == current) 1.12f else 1f
            }
        }
        for (c in Settings.TINTS) {
            val slot = FrameLayout(this)
            val v = View(this).apply {
                setOnClickListener {
                    current = c
                    paint()
                    onChange(c)
                }
            }
            slot.addView(v, FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER))
            dots += v
            row.addView(slot, LinearLayout.LayoutParams(0, dp(32), 1f))
        }
        paint()
        col.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, dp(32)).apply { topMargin = dp(10) })
        addRow(card, col)
    }

    private fun button(card: LinearLayout, label: String, color: Int, onClick: () -> Unit) {
        val tv = TextView(this).apply {
            text = label
            setTextColor(color)
            textSize = 17f
            setPadding(dp(16), dp(13), dp(16), dp(13))
            setOnClickListener { onClick() }
        }
        addRow(card, tv)
    }

    private fun info(card: LinearLayout, text: String) {
        addRow(card, subtitle(text).apply { setPadding(dp(16), dp(12), dp(16), dp(12)) })
    }

    // ================================================================ permission checks

    private fun hasNotificationAccess(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)

    private fun hasUsageAccess(): Boolean = usageAccessGranted(this)

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()
    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).roundToInt()

    companion object {
        fun usageAccessGranted(act: Activity): Boolean = runCatching {
            val ops = act.getSystemService(AppOpsManager::class.java)
            val mode = if (Build.VERSION.SDK_INT >= 29) {
                ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), act.packageName)
            } else {
                @Suppress("DEPRECATION")
                ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), act.packageName)
            }
            mode == AppOpsManager.MODE_ALLOWED
        }.getOrDefault(false)
    }
}
