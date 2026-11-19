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
    private class Category(val id: String, val title: String, val color: Int, val icon: Int, val sections: List<String>)

    /** iOS-style Settings: a home page of colourful rows, each opening its own page. */
    private val categories = listOf(
        Category("setup", "Permissions & Setup", 0xFF34C759.toInt(), R.drawable.ic_cc_lock, listOf("PERMISSIONS")),
        Category("look", "Appearance", 0xFF0A84FF.toInt(), R.drawable.ic_cc_contrast, listOf("APPEARANCE", "THEME", "WALLPAPER")),
        Category("home", "Home Screen & Widgets", 0xFF5E5CE6.toInt(), R.drawable.ic_set_grid, listOf("APPS", "HOME SCREEN", "WIDGETS")),
        Category("island", "Dynamic Island", 0xFF1C1C1E.toInt(), R.drawable.ic_set_island,
            listOf("DYNAMIC ISLAND", "ISLAND TOOLS", "SIDE ISLAND", "CHAT WINDOW", "ISLAND CONTENT", "ISLAND SERVICES")),
        Category("sidebar", "Sidebar", 0xFFFF9F0A.toInt(), R.drawable.ic_set_sidebar, listOf("SIDEBAR")),
        Category("motion", "Animations & Gestures", 0xFFFF375F.toInt(), R.drawable.ic_set_hand, listOf("ANIMATIONS", "GESTURES")),
        Category("sound", "Sounds & Haptics", 0xFFFF453A.toInt(), R.drawable.ic_cc_bell, listOf("SOUNDS")),
        Category("phone", "Phone & Connections", 0xFF30D158.toInt(), R.drawable.ic_cc_wifi, listOf("PHONE & CONNECTIVITY", "CONNECTIONS")),
        Category("backup", "Backup & Transfer", 0xFF64D2FF.toInt(), R.drawable.ic_set_backup, listOf("BACKUP & TRANSFER")),
        Category("general", "General", 0xFF8E8E93.toInt(), R.drawable.ic_set_gear, listOf("GENERAL", "RESET"))
    )
    private val groups = listOf(listOf("setup"), listOf("look", "home", "island", "sidebar"), listOf("motion", "sound", "phone"), listOf("backup", "general"))
    /** null = the Settings home page. */
    private var page: String? = null
    private lateinit var scrollView: ScrollView
    private lateinit var navTitle: TextView
    private lateinit var navBack: View
    private lateinit var navBar: FrameLayout
    private var largeTitle: View? = null

    /** Hologram entrance: each card tilts in and flickers as it locks into place. */
    private fun holoIn() {
        val ease = android.view.animation.PathInterpolator(0.2f, 0.9f, 0.1f, 1f)
        for (i in 0 until minOf(list.childCount, 14)) {
            val v = list.getChildAt(i)
            v.cameraDistance = 9000f * resources.displayMetrics.density
            v.alpha = 0f; v.rotationX = -22f; v.translationY = dp(14).toFloat()
            v.animate().alpha(1f).rotationX(0f).translationY(0f).setStartDelay(40L + i * 35L).setDuration(380)
                .setInterpolator(ease).withEndAction {
                    android.animation.ValueAnimator.ofFloat(1f, 0.6f, 1f, 0.8f, 1f).apply {
                        duration = 220; addUpdateListener { a -> v.alpha = a.animatedValue as Float }; start()
                    }
                }.start()
        }
    }

    private fun open(id: String?, forward: Boolean) {
        page = id
        build()
        scrollView.scrollTo(0, 0)
        // Slide like iOS: new page comes in from the right, going back comes from the left.
        val w = resources.displayMetrics.widthPixels.toFloat()
        list.translationX = if (forward) w * 0.35f else -w * 0.25f
        list.alpha = 0f
        list.animate().translationX(0f).alpha(1f).setDuration(260)
            .setInterpolator(android.view.animation.PathInterpolator(0.2f, 0.9f, 0.1f, 1f)).start()
        if (holo) holoIn()
        updateNav()
        list.post { updatePreviewVisibility() }
    }

    @Deprecated("Back on the Settings home page closes Settings")
    override fun onBackPressed() {
        if (page != null) open(null, forward = false) else super.onBackPressed()
    }

    /** Fade the small centred title in once the big title scrolls away. */
    private fun updateNav() {
        if (!::navTitle.isInitialized) return
        navBack.visibility = if (page != null) View.VISIBLE else View.GONE
        navTitle.text = categories.firstOrNull { it.id == page }?.title ?: "Settings"
        val t = largeTitle
        val shown = t == null || scrollY > t.bottom - dp(10)
        navTitle.alpha = if (shown) 1f else 0f
        navBar.background = if (shown) GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            if (holo) intArrayOf(0xF2020A12.toInt(), 0xCC020A12.toInt(), 0x00000000)
            else intArrayOf(0xF2000000.toInt(), 0xCC000000.toInt(), 0x00000000)) else null
    }

    private val holo get() = s.holoSettings
    private var hc = 0xFF64D2FF.toInt()
    /** Card background: glass panel with a glowing rim (hologram) or the plain dark card. */
    private fun cardBg(r: Float = dp(26).toFloat()): GradientDrawable = if (holo) GradientDrawable(
        GradientDrawable.Orientation.TL_BR, intArrayOf(Holo.alpha(hc, 0x2A), Holo.alpha(hc, 0x0C), Holo.alpha(hc, 0x1E))
    ).apply { cornerRadius = r; setStroke(maxOf(1, dp(1)), Holo.alpha(hc, 0x77)) }
        else GradientDrawable().apply { cornerRadius = r; setColor(0xFF1C1C1E.toInt()) }
    private fun sepColor() = if (holo) Holo.alpha(hc, 0x33) else 0xFF38383A.toInt()
    private fun chevColor() = if (holo) Holo.alpha(hc, 0xAA) else 0xFF5A5A5F.toInt()

    private fun iconSquare(res: Int, color: Int, size: Int = dp(30)) = FrameLayout(this).apply {
        background = if (holo) GradientDrawable().apply {
            cornerRadius = size * 0.26f; setColor(Holo.alpha(hc, 0x22)); setStroke(maxOf(1, dp(1)), Holo.alpha(hc, 0xAA))
        } else GradientDrawable().apply { cornerRadius = size * 0.26f; setColor(color) }
        addView(ImageView(this@SettingsActivity).apply { setImageResource(res); setColorFilter(if (holo) hc else Color.WHITE) },
            FrameLayout.LayoutParams((size * 0.62f).toInt(), (size * 0.62f).toInt(), Gravity.CENTER))
    }

    /** The Settings home page: launcher card on top, then grouped category rows. */
    private fun buildHome() {
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = cardBg()
        }
        head.addView(ImageView(this).apply {
            setImageDrawable(runCatching { packageManager.getApplicationIcon(packageName) }.getOrNull())
        }, LinearLayout.LayoutParams(dp(58), dp(58)))
        val ver = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: ""
        head.addView(TextView(this).apply {
            text = "Apple Launcher\nVersion $ver · Home Screen, island, widgets & more"
            setTextColor(Color.WHITE); textSize = 15f; setPadding(dp(14), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        list.addView(head, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(18) })
        // Like iPhone's "Finish Setting Up": shows until everything the launcher needs is allowed.
        val missing = Setup.items(this).count { !it.allowed(this) }
        if (missing > 0) {
            val fin = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(18), dp(14), dp(14), dp(14))
                background = cardBg()
                setOnClickListener { open("setup", forward = true) }
            }
            fin.addView(title("Finish Setting Up"), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            fin.addView(TextView(this).apply {
                text = "$missing"; setTextColor(Color.WHITE); textSize = 14f; gravity = Gravity.CENTER
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFFFF3B30.toInt()) }
            }, LinearLayout.LayoutParams(dp(26), dp(26)).apply { marginEnd = dp(8) })
            fin.addView(ImageView(this).apply {
                setImageResource(R.drawable.ic_set_chevron); setColorFilter(chevColor()); rotation = 180f
            }, LinearLayout.LayoutParams(dp(16), dp(16)))
            list.addView(fin, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(22) })
        }
        for (g in groups) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = cardBg()
                clipToOutline = true
                outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: android.graphics.Outline) =
                        outline.setRoundRect(0, 0, view.width, view.height, dp(26).toFloat())
                }
            }
            for ((i, id) in g.withIndex()) {
                val c = categories.first { it.id == id }
                if (i > 0) card.addView(View(this).apply { setBackgroundColor(sepColor()) },
                    LinearLayout.LayoutParams(MATCH_PARENT, maxOf(1, dp(0.5f))).apply { marginStart = dp(60) })
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(16), dp(12), dp(14), dp(12))
                    background = android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), null,
                        android.graphics.drawable.ColorDrawable(Color.WHITE))
                    setOnClickListener { open(c.id, forward = true) }
                }
                row.addView(iconSquare(c.icon, c.color).apply {
                    if (!holo && c.color == 0xFF1C1C1E.toInt()) (background as GradientDrawable).setStroke(dp(1), 0x55FFFFFF)
                }, LinearLayout.LayoutParams(dp(30), dp(30)))
                row.addView(title(c.title), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = dp(14) })
                row.addView(ImageView(this).apply {
                    setImageResource(R.drawable.ic_set_chevron); setColorFilter(chevColor()); rotation = 180f
                }, LinearLayout.LayoutParams(dp(16), dp(16)))
                card.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            }
            list.addView(card, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(22) })
        }
    }

    private var islandPreview: IslandPreview? = null
    private var islandCard: View? = null

    /** Show the island preview while the Dynamic Island settings are on screen. */
    private fun updatePreviewVisibility() {
        val p = islandPreview ?: return
        val card = islandCard
        if (card == null || !s.island || !card.isAttachedToWindow) { p.hide(); return }
        val r = android.graphics.Rect()
        val visible = card.getGlobalVisibleRect(r) && r.height() > dp(120)
        if (visible) p.show(s) else p.hide()
    }

    /** A live side island on this screen while you edit it. */
    private var sidePreview: SideHud? = null
    private fun previewSide() {
        ThemeEngine.resolve(this, s)
        val p = sidePreview ?: SideHud(this, s, preview = true).also { sidePreview = it }
        p.demo()
    }

    private fun previewChanged(replay: Boolean = false) {
        val p = islandPreview ?: return
        if (replay) p.replay(s) else { p.show(s); p.refresh(s) }
    }

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
        // The live island preview floats above the settings list.
        val frame = FrameLayout(this)
        ThemeEngine.resolve(this, s)
        hc = Holo.color()
        if (holo) {
            // Hologram backdrop (theme colour grid + scan line) behind the pages.
            frame.addView(HoloBackdropView(this, hc), FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            scroll.setBackgroundColor(Color.TRANSPARENT)
        }
        frame.addView(scroll, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        scrollView = scroll
        // iOS 26-style top bar: round glass back button and a small centred title.
        navBar = FrameLayout(this)
        navBack = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL; setColor(0xCC2C2C2E.toInt()); setStroke(maxOf(1, dp(1)), if (holo) Holo.alpha(hc, 0xAA) else 0x33FFFFFF)
            }
            addView(ImageView(this@SettingsActivity).apply { setImageResource(R.drawable.ic_set_chevron); setColorFilter(Color.WHITE) },
                FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
            setOnClickListener { v -> v.animate().scaleX(0.88f).scaleY(0.88f).setDuration(80).withEndAction {
                v.animate().scaleX(1f).scaleY(1f).setDuration(160).start() }.start(); open(null, forward = false) }
            visibility = View.GONE
        }
        navTitle = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 17f; alpha = 0f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        navBar.addView(navBack, FrameLayout.LayoutParams(dp(46), dp(46), Gravity.START or Gravity.BOTTOM).apply { setMargins(dp(14), 0, 0, dp(6)) })
        navBar.addView(navTitle, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM).apply { bottomMargin = dp(18) })
        frame.addView(navBar, FrameLayout.LayoutParams(MATCH_PARENT, dp(110), Gravity.TOP))
        val preview = IslandPreview(this).apply { load(s) }
        frame.addView(preview, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        islandPreview = preview
        setContentView(frame)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            list.setPadding(dp(16), bars.top + dp(8), dp(16), bars.bottom + dp(32))
            preview.statusTop = bars.top
            navBar.layoutParams = (navBar.layoutParams as FrameLayout.LayoutParams).apply { height = bars.top + dp(62) }
            preview.cutTop = insets.getInsets(WindowInsetsCompat.Type.displayCutout()).top
            insets
        }
        scroll.viewTreeObserver.addOnScrollChangedListener { scrollY = scroll.scrollY; updatePreviewVisibility(); updateNav() }
        build()
        scroll.post { updatePreviewVisibility() }
        intent?.getStringExtra("page")?.let { p -> if (categories.any { it.id == p }) open(p, forward = true) }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_SETUP) {
            if (guiding) { list.removeCallbacks(stepRun); list.postDelayed(stepRun, 500) } else rebuild()
            return
        }
        if (requestCode == REQ_MIC) {
            if (!VoiceService.hasMic(this)) s.voiceOn = false
            VoiceService.sync(this)
            rebuild()
        }
    }

    override fun onPause() {
        visible = false
        ToolsBar.settingsMode(null) // back to the tools bar
        sidePreview?.dismiss(); sidePreview = null
        // Apply sidebar changes (side, size, apps) right away, even over other apps.
        runCatching { SidebarService.sync(this) }
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        visible = true
        // The tools bar turns into ✕ while you're in here; tapping it closes Settings.
        ToolsBar.settingsMode { finish() }
        if (pendingRingtone != null && SysSettings.System.canWrite(this)) applyRingtone()
        // Permission rows may have changed while the user was in system settings.
        rebuild()
        if (guiding) { list.removeCallbacks(stepRun); list.postDelayed(stepRun, 500) }
    }

    private fun rebuild() {
        val sv = list.parent as? ScrollView
        val y = scrollY
        build()
        sv?.post { sv.scrollTo(0, y) }
    }

    private fun build() {
        list.removeAllViews()

        largeTitle = TextView(this).apply {
            text = categories.firstOrNull { it.id == page }?.title ?: "Settings"
            setTextColor(Color.WHITE)
            textSize = 34f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setPadding(dp(4), dp(52), 0, dp(14))
            if (holo) {
                text = text.toString().uppercase(); textSize = 26f; letterSpacing = 0.14f
                typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
                setShadowLayer(dp(10).toFloat(), 0f, 0f, hc)
            }
        }
        list.addView(largeTitle)
        if (page == null) { buildHome(); updateNav(); return }

        // ---------------- Permissions & Setup
        val perms = section("PERMISSIONS")
        if (perms.parent != null) {
            val items = Setup.items(this)
            val missingNow = items.count { !it.allowed(this) }
            info(perms, if (missingNow == 0) "Everything is set up ✓" else
                "$missingNow still needed. Tap one to go straight to the right screen, or tap Set Up All to go through them one by one.")
            if (missingNow > 0) button(perms, "Set Up All", 0xFF0A84FF.toInt()) {
                guiding = true; tried.clear(); nextSetupStep()
            }
            for (it in items) permRow(perms, it)
            if (Setup.isVivo) button(perms, "vivo: Allow Background Start…", 0xFF0A84FF.toInt()) { Setup.vivoBackground(this) }
        }

        val apps = section("APPS")
        button(apps, "Manage & uninstall apps…", 0xFF0A84FF.toInt()) {
            startActivity(Intent(this, ManageAppsActivity::class.java))
        }
        info(apps, "Select apps by icon and use Android's confirmation to uninstall them.")

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
        segment(look, "Theme Presets", LauncherThemes.presets.map { it.id to it.label },
            LauncherThemes.matching(s.iconStyle, s.tint, s.glass, s.tintPanels).orEmpty()) { id ->
            val preset = LauncherThemes.presets.firstOrNull { it.id == id }
            if (preset != null) {
                s.iconStyle = preset.iconStyle
                s.tint = preset.tint
                s.glass = preset.glass
                s.tintPanels = preset.tintPanels
                rebuild()
            }
        }
        segment(look, "Icon Style", listOf(
            "clear" to "Glass", "tinted" to "Tinted", "dark" to "Dark", "light" to "Light", "color" to "Color"
        ), s.iconStyle) { s.iconStyle = it; rebuild() }
        segment(look, "More Icon Styles", listOf("mono" to "Monochrome", "frost" to "Frosted"),
            s.iconStyle) { s.iconStyle = it; rebuild() }
        info(look, "Glass gives icons a clear look. Color shows the original app icons.")
        swatches(look, "Accent Color", s.tint) { s.tint = it; rebuild() }

        // ---------------- Theme colours for the whole launcher
        val theme = section("THEME")
        segment(theme, "Colors From", listOf("wallpaper" to "Wallpaper", "system" to "Phone Theme",
            "custom" to "My Color", "mono" to "Mono"), s.themeSource) { s.themeSource = it; rebuild() }
        info(theme, when (s.themeSource) {
            "wallpaper" -> "The island, sliders and glass take their colour from your wallpaper and change when you change it."
            "system" -> "Uses your phone's own theme colour (Android 12+), otherwise the wallpaper."
            "custom" -> "Pick the colour below."
            else -> "Black, white and grey only, like the classic iPhone look."
        })
        if (s.themeSource == "custom") swatches(theme, "Theme Color", s.themeColor) { s.themeColor = it; rebuild() }
        toggle(theme, "Tint Glass, Dock & Widgets", "Blend the theme colour into see-through panels", s.themeSurfaces) {
            s.themeSurfaces = it
        }
        toggle(look, "Glass Effect", "See-through folders, widgets and dock", s.glass) { s.glass = it; rebuild() }
        slider(look, "Liquid Glass", 0, 100, s.glassClarity, {
            when { it < 34 -> "Frosted"; it < 67 -> "Balanced"; else -> "Clear" }
        }) { s.glassClarity = it }
        toggle(look, "Tint Widgets & Dock", "Use the accent color on glass panels", s.tintPanels) { s.tintPanels = it; rebuild() }
        slider(look, "Wallpaper Dimming", 0, 80, s.dim, { "$it%" }) { s.dim = it }
        button(look, "Restore Glass Look", 0xFF0A84FF.toInt()) {
            s.iconStyle = "clear"
            s.glass = true
            s.tintPanels = false
            rebuild()
        }
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
        slider(home, "Icon Size", 50, 86, s.iconSize, { "${it}" }) { s.iconSize = it }
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
            getSharedPreferences("luncher", MODE_PRIVATE).edit().remove("widget_positions").remove("home_page_count").apply()
            rebuild()
        }

        // ---------------- Widgets
        val widgets = section("WIDGETS")
        toggle(widgets, "Photos", null, s.widgetEnabled("PHOTO")) { s.setWidgetEnabled("PHOTO", it) }
        toggle(widgets, "Clock & Calendar Icons", null, s.widgetEnabled("ICONS")) { s.setWidgetEnabled("ICONS", it) }
        toggle(widgets, "Calendar", null, s.widgetEnabled("CALENDAR")) { s.setWidgetEnabled("CALENDAR", it) }
        toggle(widgets, "Now Playing", null, s.widgetEnabled("MUSIC")) { s.setWidgetEnabled("MUSIC", it) }
        toggle(widgets, "Now Playing: Keep Last Song", "Stays on the Home Screen with your last song — even after the music " +
            "ends or the phone restarts — tap ▶ to carry on", s.musicKeepLast) { s.musicKeepLast = it }
        toggle(widgets, "Now Playing: Oblong Shape", "A long, fully rounded (pill) shape instead of a rounded square",
            s.musicOblong) { s.musicOblong = it }
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
        // ---------------- Wallpaper
        val wall = section("WALLPAPER")
        toggle(wall, "Spatial Wallpaper", "Your photo with a 3D depth effect that moves as you tilt the phone",
            s.spatialWallpaper) {
            s.spatialWallpaper = it
            if (it && s.spatialWallpaperUri == null) pickWallpaper()
            rebuild()
        }
        button(wall, if (s.spatialWallpaperUri == null) "Choose Photo…" else "Change Photo…", 0xFF0A84FF.toInt()) {
            pickWallpaper()
        }
        toggle(wall, "Extend", "Fill empty space around the photo instead of cropping it", s.extendWallpaper) {
            s.extendWallpaper = it
        }
        toggle(wall, "3D Depth Effect", null, s.wallpaperDepth) { s.wallpaperDepth = it }

        // ---------------- Island
        val isl = section("DYNAMIC ISLAND")
        islandCard = isl
        islandPreview?.load(s)
        val pv = islandPreview
        toggle(isl, "Dynamic Island", "Live activities at the top: music, timers, charging. Swipe sideways to switch, long-press for volume", s.island) { s.island = it; updatePreviewVisibility() }
        info(isl, "While you edit, a preview island shows at the top of the screen.")
        toggle(isl, "Show Over Other Apps", "Keep the island on top while you use other apps", s.islandOverApps) { s.islandOverApps = it; rebuild() }
        if (s.islandOverApps && !HeadsetPopup.canShowOverApps(this)) {
            button(isl, "Allow Showing Over Other Apps…", 0xFF0A84FF.toInt()) {
                runCatching {
                    startActivity(Intent(SysSettings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:$packageName")))
                }
            }
        }
        segment(isl, "Position", listOf("statusbar" to "In Status Bar", "below" to "Below Status Bar"),
            s.islandPlacement) { s.islandPlacement = it; pv?.placement = it; previewChanged() }
        slider(isl, "Move Up / Down", -20, 200, s.islandNudge.coerceIn(-20, 200), { if (it > 0) "+$it" else "$it" },
            live = { pv?.nudge = it; previewChanged() }) { s.islandNudge = it; pv?.nudge = it; previewChanged() }
        slider(isl, "Move Left / Right", -150, 150, s.islandX.coerceIn(-150, 150), { if (it > 0) "+$it" else "$it" },
            live = { pv?.x = it; previewChanged() }) { s.islandX = it; pv?.x = it; previewChanged() }
        button(isl, "Reset Island Position", 0xFF0A84FF.toInt()) {
            s.islandX = 0; s.islandNudge = 0; s.islandPlacement = "below"; rebuild(); previewChanged()
        }
        info(isl, "Tip: long-press the island on the Home Screen and drag it wherever you like.")
        slider(isl, "Island Size", 80, 130, s.islandScale, { "$it%" },
            live = { pv?.scale = it; previewChanged() }) { s.islandScale = it; pv?.scale = it; previewChanged() }
        segment(isl, "Island Style", listOf("black" to "Black", "glass" to "Glass", "outline" to "Outline", "color" to "Color"),
            s.islandStyle) { s.islandStyle = it; pv?.style = it; previewChanged(replay = true) }
        segment(isl, "Pop-up Animation", listOf("3d" to "3D", "spring" to "Pop", "fade" to "Fade", "slide" to "Slide", "zoom" to "Zoom"),
            s.islandAnim) { s.islandAnim = it; pv?.anim = it; previewChanged(replay = true) }
        // ---------------- Side island: what it shows and how it behaves
        val side2 = section("SIDE ISLAND")
        toggle(side2, "Side Island", "A black shape that grows out of the left edge of the screen", s.sideHud) {
            s.sideHud = it; rebuild()
        }
        if (s.sideHud) {
            info(side2, "Choose what the side island shows:")
            toggle(side2, "Volume", "Volume buttons on the Home Screen show a slider here", s.sideShowVolume) { s.sideShowVolume = it }
            toggle(side2, "Silent & Ring", "When you switch between Ring, Vibrate and Silent", s.sideShowRinger) { s.sideShowRinger = it }
            toggle(side2, "Focus", "When a Focus mode turns on or off", s.sideShowFocus) { s.sideShowFocus = it }
            toggle(side2, "Messages", "New messages slide out with the app icon, sender and text (tap to open, swipe left to dismiss)",
                s.sideMessages) { s.sideMessages = it; rebuild() }
            if (s.sideMessages) {
                toggle(side2, "Chat Window", "A small window beside the side island with the app's logo and the latest " +
                    "messages — scroll to read, tap to open the chat", s.sideChatCard) { s.sideChatCard = it }
                segment(side2, "Messages From", listOf("all" to "All Apps", "chosen" to "Chosen Apps"), s.sideMsgMode) {
                    s.sideMsgMode = it
                }
                button(side2, "Choose Message Apps (${s.sideMsgApps.size})…", 0xFF0A84FF.toInt()) {
                    chooseApps("Messages From", launchableApps(), s.sideMsgApps) { chosen ->
                        s.sideMsgApps = chosen
                        if (chosen.isNotEmpty()) s.sideMsgMode = "chosen"
                    }
                }
            }
            toggle(side2, "Recent Apps", "An extension under the chat button: your recently used apps — scroll it and tap one " +
                "to switch apps quickly", s.sideRecents) { s.sideRecents = it }
            toggle(side2, "Headphones", "Your connected earbuds, AirPods, headset or wired headphones — with battery — " +
                "in the side island (tap it for Bluetooth settings)", s.sideDevice) { s.sideDevice = it }
            toggle(side2, "Temperature", "The phone's temperature in the side island", s.sideTemp) { s.sideTemp = it }
            segment(side2, "Temperature Unit", listOf("c" to "°C", "f" to "°F"), if (s.sideTempF) "f" else "c") { s.sideTempF = it == "f" }
            segment(side2, "Recent Apps Shown", listOf("1" to "1", "2" to "2", "3" to "3", "4" to "4", "5" to "5"),
                s.sideRecentsShown.toString()) { s.sideRecentsShown = it.toInt() }
            segment(side2, "Recent App Size", listOf("s" to "Small", "m" to "Medium", "l" to "Large"), s.sideRecentsSize) {
                s.sideRecentsSize = it
            }
            toggle(side2, "Always Show on Edge", "A slim sliver stays on the edge when nothing is showing", s.sideRest) {
                s.sideRest = it; rebuild()
            }
            if (s.sideRest) segment(side2, "Tap the Sliver For", listOf("volume" to "Volume Slider", "music" to "Play / Pause",
                "none" to "Nothing"), s.sideTap) { s.sideTap = it }
            info(side2, "Look & position — a preview shows on the edge while you change these. " +
                "Tip: long-press the side island (here or on the Home Screen) and drag it up or down.")
            segment(side2, "Edge", listOf("left" to "Left", "right" to "Right"), if (s.sideRight) "right" else "left") {
                s.sideRight = it == "right"; previewSide()
            }
            segment(side2, "Color", listOf("black" to "Black", "glass" to "Glass", "theme" to "Theme", "custom" to "Custom"),
                s.sideColor) { s.sideColor = it; previewSide(); rebuild() }
            if (s.sideColor == "custom") swatches(side2, "Custom Color", s.sideCustomColor) { s.sideCustomColor = it; previewSide() }
            slider(side2, "Visibility", 30, 100, s.sideOpacity, { "$it%" },
                live = { s.sideOpacity = it; sidePreview?.rest() }) { s.sideOpacity = it; previewSide() }
            slider(side2, "Position on Edge", 10, 80, s.sideY, { "$it%" },
                live = { s.sideY = it; sidePreview?.rest() }) { s.sideY = it; previewSide(); runCatching { SidebarService.sync(this) } }
            button(side2, "Preview Side Island", 0xFF0A84FF.toInt()) { previewSide() }
            segment(side2, "Stays For", listOf("1200" to "Short", "1800" to "Normal", "3500" to "Long"), s.sideMs.toString()) {
                s.sideMs = it.toInt()
            }
        }

        // ---------------- Chat window: photos, replies and the photo viewer
        val chatSec = section("CHAT WINDOW")
        toggle(chatSec, "Attachments", "Photos, videos, voice messages and files show as chips — Download saves them " +
            "(when the app shares the file), otherwise they open in the app",
            s.sideMedia) { s.sideMedia = it }
        toggle(chatSec, "Chat Button in Side Island", "Under the volume slider: the latest app's logo and a red count — " +
            "tap it for Message History (every app's chats under its logo), tap a chat to open its window", s.sideHistory) { s.sideHistory = it }
        button(chatSec, "Reset Window Sizes", 0xFF0A84FF.toInt()) {
            s.resetWinSizes()
            android.widget.Toast.makeText(this, "Chat and history windows are back to their normal size", android.widget.Toast.LENGTH_SHORT).show()
        }
        button(chatSec, "Open Message History", 0xFF0A84FF.toInt()) {
            NotificationService.loadHistory(this)
            ThemeEngine.resolve(this, s)
            val p = sidePreview ?: SideHud(this, s, preview = true).also { sidePreview = it }
            p.historyProvider = { NotificationService.chatHistory.values.toList() }
            p.appLookup = { pkg -> runCatching {
                val pm = packageManager
                val d = pm.getApplicationIcon(pkg)
                val b = android.graphics.Bitmap.createBitmap(96, 96, android.graphics.Bitmap.Config.ARGB_8888)
                val cv = android.graphics.Canvas(b); d.setBounds(0, 0, 96, 96); d.draw(cv)
                b to pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            }.getOrNull() }
            p.openChat = { c -> packageManager.getLaunchIntentForPackage(c.pkg)?.let { runCatching { startActivity(it) } } }
            p.openHistory()
        }
        info(chatSec, "On the Home Screen or in any app: tap the side island's sliver, then the app logo under the volume slider.")
        toggle(chatSec, "Reply Box", "Answer from the chat window — works with any app that has Reply in its notifications " +
            "(Messenger, WhatsApp, Telegram, Instagram, SMS, Discord…)", s.sideReply) { s.sideReply = it }

        // What may appear in the island
        val content = section("ISLAND CONTENT")
        for ((type, label, sub) in listOf(
            Triple("music", "Now Playing", "Song, artwork and playback controls"),
            Triple("timer", "Timers", "Live countdown and “Timer done”"),
            Triple("charging", "Charging", "Battery level when you plug in"),
            Triple("focus", "Focus", "When a Focus mode turns on or off"),
            Triple("note", "Notifications", "New notifications pop up briefly")
        )) toggle(content, label, sub, s.islandAllows(type)) { s.setIslandAllows(type, it) }
        // Origin-island-style services
        val services = section("ISLAND SERVICES")
        for ((id, name, desc) in IslandServices.services)
            toggle(services, name, desc, s.islandAllows(id)) { s.setIslandAllows(id, it) }
        button(services, "Pinned Services (${s.islandPinned.size})…", 0xFF0A84FF.toInt()) {
            val targets = IslandServices.shareTargets(this)
            chooseApps("Pinned Services", targets, s.islandPinned.toSet()) { chosen ->
                // Keep the order you already had, add new ones at the end.
                s.islandPinned = s.islandPinned.filter { it in chosen } + chosen.filter { it !in s.islandPinned }
            }
        }
        info(services, "Pinned services appear when you copy something, so you can send it straight to those apps.")
        segment(services, "Music Player Sources", listOf("all" to "All Apps", "chosen" to "Chosen Apps"),
            s.islandAudioMode) { s.islandAudioMode = it }
        button(services, "Manage Audio Sources (${s.islandAudioApps.size})…", 0xFF0A84FF.toInt()) {
            chooseApps("Audio Sources", launchableApps(), s.islandAudioApps,
                "Music & audio apps", AppPicker.musicPackages(this)) { chosen ->
                s.islandAudioApps = chosen
                if (chosen.isNotEmpty()) s.islandAudioMode = "chosen"
            }
        }

        segment(content, "Notifications from", listOf("all" to "All Apps", "chosen" to "Chosen Apps"),
            s.islandNoteMode) { s.islandNoteMode = it }
        button(content, "Choose Apps (${s.islandNoteApps.size})…", 0xFF0A84FF.toInt()) { chooseIslandApps() }
        segment(content, "Pop-up Length", listOf("2500" to "Short", "4500" to "Normal", "8000" to "Long"),
            s.islandPopupMs.toString()) { s.islandPopupMs = it.toInt() }

        toggle(isl, "Always Show Island", "A small island stays at the top; pull it down (or tap it) for quick controls",
            s.islandAlways) { s.islandAlways = it; previewChanged() }

        // ---------------- Island tools bar (shown when nothing is playing)
        val tools = section("ISLAND TOOLS")
        toggle(tools, "Tools Bar", "Always on screen — on the Home Screen and over every app: " +
            "Flashlight, Brightness, Silent, Game Mode, Lock Screen and Settings", s.islandTools) {
            s.islandTools = it; runCatching { SidebarService.sync(this) }; rebuild()
        }
        if (s.islandTools) {
            slider(tools, "Size", 60, 150, s.toolsScale, { "$it%" }) { s.toolsScale = it }
            segment(tools, "Color", listOf("black" to "Black", "glass" to "Glass", "theme" to "Theme", "white" to "White", "custom" to "Custom"),
                s.toolsColor) { s.toolsColor = it; rebuild() }
            if (s.toolsColor == "custom") swatches(tools, "Custom Color", s.toolsCustomColor) { s.toolsCustomColor = it }
            slider(tools, "Opacity", 40, 100, s.toolsOpacity, { "$it%" }) { s.toolsOpacity = it }
            segment(tools, "Shape", listOf("pill" to "Pill", "rounded" to "Rounded", "square" to "Square", "leaf" to "Leaf"),
                s.toolsShape) { s.toolsShape = it }
            slider(tools, "Angle", -30, 30, s.toolsAngle, { "$it°" }) { s.toolsAngle = it }
            button(tools, "Reset Position", 0xFF0A84FF.toInt()) {
                s.toolsDx = 0; s.toolsDy = 0
                s.toolsScale = s.toolsScale // nudges the launcher to re-apply the look
                android.widget.Toast.makeText(this, "The tools bar is back at the top", android.widget.Toast.LENGTH_SHORT).show()
            }
            info(tools, "It never hides by itself. Tap its arrow ⌃ to fold it into a small tab, and ⌄ to open it again. " +
                "Move it: hold the bar for a moment, then drag it anywhere on the screen.")
        }

        // ---------------- Sounds
        val snd = section("SOUNDS")
        button(snd, "Use a Song as Ringtone…", 0xFF0A84FF.toInt()) { pickRingtone() }
        button(snd, "Sound & Vibration Settings…", 0xFF0A84FF.toInt()) {
            runCatching { startActivity(Intent(SysSettings.ACTION_SOUND_SETTINGS)) }
        }
        info(snd, "Long-press the Dynamic Island (or the Home Screen menu) for separate Ringtone, Notification, " +
            "Alarm and Media volume.")

        // ---------------- Phone & Connectivity shortcuts
        val phone = section("PHONE & CONNECTIVITY")
        button(phone, "Call Screening & Spam…", 0xFF0A84FF.toInt()) {
            runCatching { startActivity(Intent(android.telecom.TelecomManager.ACTION_SHOW_CALL_SETTINGS)) }
                .onFailure { runCatching { startActivity(Intent(SysSettings.ACTION_SETTINGS)) } }
        }
        button(phone, "Wi-Fi (switch to mobile data when weak)…", 0xFF0A84FF.toInt()) {
            runCatching { startActivity(Intent(SysSettings.ACTION_WIFI_SETTINGS)) }
        }
        button(phone, "Face & Fingerprint Unlock…", 0xFF0A84FF.toInt()) {
            runCatching { startActivity(Intent(SysSettings.ACTION_SECURITY_SETTINGS)) }
        }

        // ---------------- Connections
        val conn = section("CONNECTIONS")
        toggle(conn, "Headphone Pop-up",
            "Show a card with battery when AirPods-style, Bluetooth or wired headphones connect",
            s.headsetPopup) { s.headsetPopup = it }
        val overApps = HeadsetPopup.canShowOverApps(this)
        button(conn, if (overApps) "Pop-up shows over other apps ✓" else "Show Pop-up Over Other Apps…",
            if (overApps) 0xFF30D158.toInt() else 0xFF0A84FF.toInt()) {
            runCatching {
                startActivity(Intent(SysSettings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:$packageName")))
            }
        }

        // ---------------- Sidebar
        val side = section("SIDEBAR")
        toggle(side, "Sidebar", "A handle on the edge of the screen: swipe it in for your favourite apps", s.sidebarOn) {
            s.sidebarOn = it; rebuild()
        }
        if (s.sidebarOn) {
            button(side, "Choose Sidebar Apps (${s.sidebarApps.size})…", 0xFF0A84FF.toInt()) {
                chooseApps("Sidebar Apps", launchableApps(), s.sidebarApps.toSet()) { chosen ->
                    // Keep your order; newly picked apps go at the end.
                    s.sidebarApps = s.sidebarApps.filter { it in chosen } + chosen.filter { it !in s.sidebarApps }
                }
            }
            if (s.sidebarApps.size > 1) button(side, "Change Order…", 0xFF0A84FF.toInt()) { reorderSidebar() }
            slider(side, "Size", 60, 140, s.sidebarScale, { "$it%" }) { s.sidebarScale = it }
            slider(side, "Height", 35, 90, s.sidebarHeight, { "$it%" }) { s.sidebarHeight = it }
            toggle(side, "App Names", "Show names under the icons", s.sidebarLabels) { s.sidebarLabels = it }
            segment(side, "Side", listOf("left" to "Left Edge", "right" to "Right Edge"),
                if (s.sidebarRight) "right" else "left") { s.sidebarRight = it == "right" }
            toggle(side, "Show Over Other Apps", "Always there — inside any app, games and full-screen videos",
                s.sidebarOverApps) { s.sidebarOverApps = it; rebuild() }
            if (s.sidebarOverApps && !HeadsetPopup.canShowOverApps(this)) {
                button(side, "Allow Showing Over Other Apps…", 0xFF0A84FF.toInt()) {
                    runCatching { startActivity(Intent(SysSettings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:$packageName"))) }
                }
            }
            if (s.sidebarOverApps) info(side, "Android shows a small “Sidebar is on” notification while it runs over other apps. " +
                "You can hide it by long-pressing the notification and turning off its category.")
            info(side, "Tip: tap or swipe the handle inward to open. Pinch the open sidebar to resize it. " +
                "Long-press the handle and drag to move it up or down.")
        }

        // ---------------- Reset (starts again with the "hello" setup screen)
        val hello = section("RESET")
        info(hello, "Reset puts the launcher back to how it was when you first installed it: layout, folders, widgets, " +
            "island, theme and every setting. It starts again with the “hello” screen. Export your setup first if you want to keep it.")
        button(hello, "Reset Launcher…", 0xFFFF453A.toInt()) {
            android.app.AlertDialog.Builder(this, R.style.Theme_Luncher_Dialog)
                .setTitle("Reset Apple Launcher?")
                .setMessage("Everything goes back to the start. This can't be undone.")
                .setPositiveButton("Reset") { _, _ ->
                    getSharedPreferences("luncher", MODE_PRIVATE).edit().clear().commit()
                    java.io.File(filesDir, "restored").deleteRecursively()
                    restartLauncher()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        // ---------------- Backup & transfer (like moving to a new iPhone)
        val backup = section("BACKUP & TRANSFER")
        info(backup, "Save your whole setup to one file — Home Screen pages, folders, docks, widgets, photos, " +
            "3D wallpaper, icons, theme, island, Focus and every setting — then load it on a new phone or after reinstalling.")
        button(backup, "Export Launcher Setup…", 0xFF0A84FF.toInt()) {
            val day = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
            val i = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/zip").putExtra(Intent.EXTRA_TITLE, "AppleLauncher-setup-$day.zip")
            runCatching { startActivityForResult(i, REQ_EXPORT) }
        }
        button(backup, "Import Launcher Setup…", 0xFF0A84FF.toInt()) {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*").putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/octet-stream"))
            runCatching { startActivityForResult(i, REQ_IMPORT) }
        }
        info(backup, "Widgets that come from other apps need to be added again after importing; everything else comes back as it was.")

        // ---------------- About
        val about = section("GENERAL")
        button(about, "Default Home App…", 0xFF0A84FF.toInt()) {
            runCatching { startActivity(Intent(SysSettings.ACTION_HOME_SETTINGS)) }
        }
        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: ""
        info(about, "Apple Launcher $version")
    }

    // ================================================================ wallpaper & ringtone pickers

    @Suppress("DEPRECATION")
    private fun pickWallpaper() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "image/*"
        }
        runCatching { startActivityForResult(i, REQ_WALLPAPER) }
    }

    private var pendingRingtone: android.net.Uri? = null

    @Suppress("DEPRECATION")
    private fun pickRingtone() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "audio/*"
        }
        runCatching { startActivityForResult(i, REQ_RINGTONE) }
    }

    @Deprecated("Classic result API is fine here")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        when (requestCode) {
            REQ_EXPORT -> {
                val toast = android.widget.Toast.makeText(this, "Exporting…", android.widget.Toast.LENGTH_SHORT)
                toast.show()
                Thread {
                    val r = Backup.export(this, uri)
                    runOnUiThread { android.widget.Toast.makeText(this, r.message, android.widget.Toast.LENGTH_LONG).show() }
                }.start()
            }
            REQ_IMPORT -> {
                android.app.AlertDialog.Builder(this, R.style.Theme_Luncher_Dialog)
                    .setTitle("Import this setup?")
                    .setMessage("Your current Home Screen, folders, widgets and settings will be replaced. The launcher restarts afterwards.")
                    .setPositiveButton("Import") { _, _ ->
                        Thread {
                            val r = Backup.import(this, uri)
                            runOnUiThread {
                                android.widget.Toast.makeText(this, r.message, android.widget.Toast.LENGTH_LONG).show()
                                if (r.ok) restartLauncher()
                            }
                        }.start()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            REQ_WALLPAPER -> {
                runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                s.spatialWallpaperUri = uri.toString()
                s.spatialWallpaper = true
                rebuild()
            }
            REQ_RINGTONE -> {
                pendingRingtone = uri
                runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                if (!SysSettings.System.canWrite(this)) {
                    android.widget.Toast.makeText(this, "Allow “Modify system settings”, then come back",
                        android.widget.Toast.LENGTH_LONG).show()
                    runCatching {
                        startActivity(Intent(SysSettings.ACTION_MANAGE_WRITE_SETTINGS,
                            android.net.Uri.parse("package:$packageName")))
                    }
                } else applyRingtone()
            }
        }
    }

    private fun applyRingtone() {
        val src = pendingRingtone ?: return
        pendingRingtone = null
        val ok = runCatching {
            val name = runCatching {
                contentResolver.query(src, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            }.getOrNull() ?: "Ringtone.mp3"
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Audio.Media.DISPLAY_NAME, name)
                put(android.provider.MediaStore.Audio.Media.MIME_TYPE, contentResolver.getType(src) ?: "audio/mpeg")
                put(android.provider.MediaStore.Audio.Media.IS_RINGTONE, true)
                if (Build.VERSION.SDK_INT >= 29) {
                    put(android.provider.MediaStore.Audio.Media.RELATIVE_PATH, android.os.Environment.DIRECTORY_RINGTONES)
                }
            }
            val dest = contentResolver.insert(android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("no destination")
            contentResolver.openOutputStream(dest)?.use { out ->
                contentResolver.openInputStream(src)?.use { it.copyTo(out) }
            }
            android.media.RingtoneManager.setActualDefaultRingtoneUri(
                this, android.media.RingtoneManager.TYPE_RINGTONE, dest)
        }.isSuccess
        android.widget.Toast.makeText(this, if (ok) "Ringtone set" else "Couldn't set that song as ringtone",
            android.widget.Toast.LENGTH_SHORT).show()
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

    /** Start the Home Screen fresh so everything loads from the imported setup. */
    private fun restartLauncher() {
        val home = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        list.postDelayed({
            startActivity(home)
            finishAffinity()
            // A clean process makes sure nothing from the old layout lingers in memory.
            android.os.Handler(mainLooper).postDelayed({ Runtime.getRuntime().exit(0) }, 600)
        }, 900)
    }

    /** Simple reorder: tap an app to move it to the top of the sidebar. */
    private fun reorderSidebar() {
        val all = launchableApps().toMap()
        val cur = s.sidebarApps.filter { it in all }
        android.app.AlertDialog.Builder(this, R.style.Theme_Luncher_Dialog)
            .setTitle("Tap an app to move it to the top")
            .setItems(cur.map { all[it] ?: it }.toTypedArray()) { _, i ->
                s.sidebarApps = listOf(cur[i]) + cur.filterIndexed { j, _ -> j != i }
                reorderSidebar()
            }
            .setPositiveButton("Done") { _, _ -> rebuild() }
            .show()
    }

    // ---------------- guided setup
    private var guiding = false
    /** One step at a time: coming back from a pop-up and from a screen can happen together. */
    private val stepRun = Runnable { if (guiding) nextSetupStep() }
    private val tried = HashSet<String>()

    /** Open the next permission that's still missing (each one only once per run). */
    private fun nextSetupStep() {
        val next = Setup.items(this).firstOrNull { !it.allowed(this) && it.id !in tried }
        if (next == null) {
            guiding = false
            val left = Setup.items(this).count { !it.allowed(this) }
            android.widget.Toast.makeText(this, if (left == 0) "All set ✓" else "Done — $left skipped", android.widget.Toast.LENGTH_SHORT).show()
            rebuild(); return
        }
        tried += next.id
        askFor(next)
    }

    private fun askFor(item: Setup.Item) {
        val p = item.runtime
        if (p != null) requestPermissions(arrayOf(p), REQ_SETUP) else item.open?.invoke(this)
    }

    private fun permRow(card: LinearLayout, item: Setup.Item) {
        val ok = item.allowed(this)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(14), dp(12))
            setOnClickListener { askFor(item) }
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(title(item.title))
        texts.addView(subtitle(item.why))
        row.addView(texts, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        row.addView(TextView(this).apply {
            text = if (ok) "Allowed" else "Allow ›"
            textSize = 13f; gravity = Gravity.CENTER
            setTextColor(if (ok) 0xFF30D158.toInt() else Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat(); setColor(if (ok) 0x2230D158 else 0xFF0A84FF.toInt())
            }
            setPadding(dp(12), dp(6), dp(12), dp(6))
        }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(10) })
        addRow(card, row)
    }

    private fun launchableApps(): List<Pair<String, String>> {
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(main, 0)
            .map { it.activityInfo.packageName to it.loadLabel(packageManager).toString() }
            .filter { it.first != packageName }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }
    }

    /** Tick-list of apps; [onDone] gets the chosen package names, then the page refreshes. */
    private fun chooseApps(
        title: String,
        apps: List<Pair<String, String>>,
        current: Set<String>,
        featuredTitle: String? = null,
        featured: Set<String> = emptySet(),
        onDone: (Set<String>) -> Unit
    ) {
        AppPicker.show(this, title, apps.map { AppPicker.App(it.first, it.second) }, current, featuredTitle, featured) {
            onDone(it); recreate()
        }
    }

    /** Pick which apps' notifications may pop up in the Dynamic Island. */
    private fun chooseIslandApps() {
        chooseApps("Notifications", launchableApps(), s.islandNoteApps) { chosen ->
            s.islandNoteApps = chosen
            if (chosen.isNotEmpty()) s.islandNoteMode = "chosen"
        }
    }

    private fun section(title: String): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg()
        }
        // Sections that belong to another page are built but never shown.
        val cat = categories.firstOrNull { it.id == page } ?: return card
        if (title !in cat.sections) return card
        list.addView(TextView(this).apply {
            text = title
            setTextColor(if (holo) Holo.alpha(hc, 0xCC) else 0xFF8E8E93.toInt())
            textSize = 13f
            if (holo) { typeface = Typeface.create("monospace", Typeface.NORMAL); letterSpacing = 0.16f }
            setPadding(dp(18), dp(20), 0, dp(8))
        })
        list.addView(card, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        return card
    }

    private fun addRow(card: LinearLayout, v: View, pad: Boolean = false) {
        if (card.childCount > 0) {
            card.addView(View(this).apply { setBackgroundColor(sepColor()) },
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
                intArrayOf(if (holo) hc else 0xFF30D158.toInt(), if (holo) Holo.alpha(hc, 0x40) else 0xFF39393D.toInt())
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
        live: ((Int) -> Unit)? = null,
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
                    if (fromUser) live?.invoke(p + min)
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
        /** True while Settings is on screen (the floating island stays away then). */
        @Volatile var visible = false
        const val REQ_WALLPAPER = 5100
        const val REQ_RINGTONE = 5101
        const val REQ_EXPORT = 5102
        const val REQ_MIC = 5104
        const val REQ_SETUP = 5105
        const val REQ_IMPORT = 5103

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
