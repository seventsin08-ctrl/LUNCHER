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
        frame.addView(scroll, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        val preview = IslandPreview(this).apply { load(s) }
        frame.addView(preview, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        islandPreview = preview
        setContentView(frame)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            list.setPadding(dp(16), bars.top + dp(8), dp(16), bars.bottom + dp(32))
            preview.statusTop = bars.top
            preview.cutTop = insets.getInsets(WindowInsetsCompat.Type.displayCutout()).top
            insets
        }
        scroll.viewTreeObserver.addOnScrollChangedListener { scrollY = scroll.scrollY; updatePreviewVisibility() }
        build()
        scroll.post { updatePreviewVisibility() }
    }

    override fun onPause() {
        visible = false
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        visible = true
        if (pendingRingtone != null && SysSettings.System.canWrite(this)) applyRingtone()
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

        // ---------------- Island & Assistant
        val isl = section("DYNAMIC ISLAND & ASSISTANT")
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
        segment(isl, "Pop-up Animation", listOf("spring" to "Pop", "fade" to "Fade", "slide" to "Slide", "zoom" to "Zoom"),
            s.islandAnim) { s.islandAnim = it; pv?.anim = it; previewChanged(replay = true) }
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

        toggle(isl, "Swipe Down for Assistant", "Swipe down on the island to open your AI assistant",
            s.assistant != "off") { on ->
            s.assistant = if (on) (getSharedPreferences("luncher", MODE_PRIVATE).getString("last_assistant", "gemini") ?: "gemini") else "off"
            rebuild()
        }
        if (s.assistant != "off") segment(isl, "Assistant", listOf("system" to "Google", "gemini" to "Gemini", "chatgpt" to "ChatGPT"),
            s.assistant) { s.assistant = it; getSharedPreferences("luncher", MODE_PRIVATE).edit().putString("last_assistant", it).apply() }

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
