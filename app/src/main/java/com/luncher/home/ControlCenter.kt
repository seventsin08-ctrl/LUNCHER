package com.luncher.home

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.MediaStore
import android.provider.Settings
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.dynamicanimation.animation.DynamicAnimation
import kotlin.math.roundToInt
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2

/**
 * iPhone-style Control Center: glass tiles over a blurred Home Screen.
 *
 * Android doesn't let a launcher flip Wi-Fi, Bluetooth, mobile data or airplane mode by
 * itself, so those tiles open Android's own quick panels for that setting (one tap to
 * switch). Flashlight, brightness, volume, music, timer and Focus work right here.
 */
class ControlCenter(
    private val act: Activity,
    private val root: FrameLayout,
    private val music: () -> Triple<String, String, Bitmap?>?,
    private val musicPlaying: () -> Boolean,
    private val playPause: () -> Unit,
    private val next: () -> Unit,
    private val prev: () -> Unit,
    private val focusLabel: () -> String,
    private val onFocus: () -> Unit,
    private val onTimer: () -> Unit,
    private val onLock: () -> Unit,
    private val onBlur: (Boolean) -> Unit,
    private val haptics: () -> Boolean
) {
    private var layer: FrameLayout? = null
    private var torchOn = false
    val isShowing get() = layer != null

    private fun dp(v: Int) = (v * act.resources.displayMetrics.density).roundToInt()

    private fun tick(v: View) {
        if (haptics()) v.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
    }

    private fun glass(radius: Int, active: Int? = null) = GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        if (active != null) intArrayOf(active, active)
        else intArrayOf(Color.argb((70 * Glass.opacity).toInt().coerceIn(30, 200), 255, 255, 255),
            Color.argb((28 * Glass.opacity).toInt().coerceIn(12, 160), 255, 255, 255))
    ).apply {
        cornerRadius = dp(radius).toFloat()
        setStroke(maxOf(1, dp(1)), Color.argb(70, 255, 255, 255))
    }

    private fun circle(color: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }

    /** The iOS-style Start Timer dial, shown in Search when you type "timer". */
    fun timerDial(): View = TimerDial()

    /** Set by the launcher: the current song for the big music tile. */
    var media: (() -> IslandMedia?)? = null
    var mediaTitle: (() -> Pair<String, String>?)? = null
    var onVolumePanel: (() -> Unit)? = null
    /** dark = solid dark tiles like iOS 18, glass = see-through Liquid Glass tiles */
    var tileStyle = "dark"

    private var musicTicker: Runnable? = null

    /** iPhone features 25/26: a second page of your own controls, each resizable by holding it. */
    var pagesOn = true
    /** Bouncy Control Center (feature 7): tiles drop in with a spring; off = a quick fade. */
    var bounceIn = true
    var resizeOn = true
    /** Runs a control from page 2 (torch, timer, focus…); set by the launcher. */
    var runControl: ((String, View) -> Unit)? = null

    private class FixedPages(private val views: List<View>) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = views.size
        override fun getItemViewType(position: Int) = position
        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val v = views[viewType]
            (v.parent as? android.view.ViewGroup)?.removeView(v)
            v.layoutParams = RecyclerView.LayoutParams(MATCH_PARENT, MATCH_PARENT)
            return object : RecyclerView.ViewHolder(v) {}
        }
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {}
    }

    private fun parseControls(): List<Ios.Tile> {
        val raw = com.luncher.home.Settings(act).myControls
        return raw.split(',').mapNotNull { e ->
            val p = e.split(':'); val id = p.getOrNull(0)?.trim().orEmpty()
            if (IosWidgets.SHORTCUTS.none { it.id == id }) null
            else Ios.Tile(id, p.getOrNull(1)?.toIntOrNull() ?: 1, p.getOrNull(2)?.toIntOrNull() ?: 1)
        }
    }

    private fun saveControls(tiles: List<Ios.Tile>) {
        com.luncher.home.Settings(act).myControls = tiles.joinToString(",") { "${it.id}:${it.w}:${it.h}" }
    }

    /** Page 2: "My Controls", packed into the same 4-wide grid as page 1 (never overlapping). */
    private fun myControlsPage(W: Int, c: Int, gap: Int, pad: Int, top: Int, bottom: Int): View {
        val col = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, top + dp(40), pad, bottom + dp(24))
            isClickable = true
            setOnClickListener { hide() }
        }
        col.addView(TextView(act).apply {
            text = "My Controls"; setTextColor(Color.WHITE); textSize = 22f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        })
        col.addView(TextView(act).apply {
            text = if (resizeOn) "Hold a control to change its size" else ""; setTextColor(0x99FFFFFF.toInt()); textSize = 13f
        }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { bottomMargin = dp(14) })
        val grid = FrameLayout(act)
        col.addView(grid, LinearLayout.LayoutParams(W, WRAP_CONTENT))
        fun render(animateId: String?) {
            grid.removeAllViews()
            val tiles = parseControls()
            val placed = Ios.pack(tiles)
            val rows = (placed.maxOfOrNull { it.row + it.h } ?: 0)
            grid.layoutParams = (grid.layoutParams as LinearLayout.LayoutParams).apply { height = rows * c + (rows - 1).coerceAtLeast(0) * gap }
            for (p in placed) {
                val s = IosWidgets.SHORTCUTS.first { it.id == p.id }
                val w = p.w * c + (p.w - 1) * gap; val h = p.h * c + (p.h - 1) * gap
                val tile = LinearLayout(act).apply {
                    orientation = if (p.w == 2 && p.h == 1) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    background = tileBg(if (p.w == 1 && p.h == 1) c / 2f else dp(30).toFloat())
                    setPadding(dp(12), dp(8), dp(12), dp(8))
                }
                tile.addView(TextView(act).apply { text = s.emoji; textSize = if (p.h == 2) 34f else 22f; gravity = Gravity.CENTER; setTextColor(Color.WHITE) })
                if (p.w > 1) tile.addView(TextView(act).apply {
                    text = s.label; setTextColor(Color.WHITE); textSize = if (p.h == 2) 16f else 14f
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                    setPadding(if (p.h == 1) dp(10) else 0, if (p.h == 2) dp(8) else 0, 0, 0)
                })
                tile.setOnClickListener { v -> tick(v); press(v); runControl?.invoke(p.id, v) }
                if (resizeOn) tile.setOnLongClickListener { v ->
                    if (haptics()) v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    val now = parseControls().map { t -> if (t.id == p.id) Ios.nextSize(t.w, t.h).let { (nw, nh) -> Ios.Tile(t.id, nw, nh) } else t }
                    saveControls(now); render(p.id); true
                }
                grid.addView(tile, lp(w, h, p.col * (c + gap), p.row * (c + gap)))
                if (p.id == animateId) Motion.scaleTo(tile, 1f, Motion.BOUNCY, start = 0.8f)
            }
        }
        render(null)
        return android.widget.ScrollView(act).apply {
            isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER
            addView(col, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
    }

    private fun tileBg(radius: Float, active: Boolean = false, activeColor: Int = ThemeEngine.accent): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = radius
            if (active) setColor(activeColor)
            else if (tileStyle == "glass") {
                colors = intArrayOf(Color.argb((70 * Glass.opacity).toInt().coerceIn(30, 200), 255, 255, 255),
                    Color.argb((28 * Glass.opacity).toInt().coerceIn(12, 160), 255, 255, 255))
                orientation = GradientDrawable.Orientation.TL_BR
                setStroke(maxOf(1, dp(1)), Color.argb(70, 255, 255, 255))
            } else {
                // Dark tiles, slightly tinted with the theme colour.
                val base = Color.argb(200, 30, 30, 33)
                setColor(if (ThemeEngine.colorful) IconFactory.blend(base, ThemeEngine.alpha(ThemeEngine.accent, 200), 0.10f) else base)
            }
        }

    private fun icon(res: Int, tint: Int) = ImageView(act).apply {
        setImageResource(res); setColorFilter(tint); scaleType = ImageView.ScaleType.FIT_CENTER
    }

    /** Round button with a vector icon; white/accent background when on. */
    private fun round(res: Int, active: Boolean, size: Int, iconSize: Int = (size * 0.42f).toInt(),
                      activeColor: Int = ThemeEngine.accent, onClick: (View) -> Unit): FrameLayout {
        val f = FrameLayout(act).apply {
            background = tileBg(size / 2f, active, activeColor)
            setOnClickListener { tick(it); press(it); onClick(it) }
        }
        val tint = if (active) (if (ThemeEngine.luminance(activeColor) > 0.5f) 0xFF1C1C1E.toInt() else Color.WHITE) else Color.WHITE
        f.addView(icon(res, tint), FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER))
        return f
    }

    private fun press(v: View) {
        v.animate().scaleX(0.9f).scaleY(0.9f).setDuration(70).withEndAction {
            Motion.scaleTo(v, 1f, Motion.BOUNCY)
        }.start()
    }

    /** Wide pill: round icon on the left, two lines of text. */
    private fun pill(res: Int, title: String, sub: String, h: Int, iconCircle: Int?, onClick: () -> Unit): LinearLayout {
        val p = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = tileBg(h / 2f)
            setPadding(dp(12), 0, dp(14), 0)
            setOnClickListener { tick(it); press(it); onClick() }
        }
        val ic = FrameLayout(act).apply { if (iconCircle != null) background = circle(iconCircle) }
        val iconTint = if (iconCircle != null && ThemeEngine.luminance(iconCircle) > 0.5f) 0xFF1C1C1E.toInt() else Color.WHITE
        ic.addView(icon(res, iconTint), FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
        p.addView(ic, LinearLayout.LayoutParams((h * 0.62f).toInt(), (h * 0.62f).toInt()))
        p.addView(TextView(act).apply {
            text = if (sub.isEmpty()) title else "$title\n$sub"
            setTextColor(Color.WHITE); textSize = 14f; maxLines = 2
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setPadding(dp(10), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        return p
    }

    private fun lp(w: Int, h: Int, l: Int = 0, t: Int = 0) =
        FrameLayout.LayoutParams(w, h).apply { leftMargin = l; topMargin = t }

    fun show(topInset: Int, bottomInset: Int) {
        if (layer != null) return
        lastTop = topInset; lastBottom = bottomInset
        val l = FrameLayout(act).apply {
            setBackgroundColor(Color.argb(120, 0, 0, 0))
            alpha = 0f
            isClickable = true
            setOnClickListener { hide() }
        }
        val pad = dp(20)
        val gap = dp(14)
        val W = act.resources.displayMetrics.widthPixels - pad * 2
        val c = (W - gap * 3) / 4          // one grid cell
        val two = c * 2 + gap              // two cells + gap
        val panel = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, topInset + dp(40), pad, bottomInset + dp(24))
            isClickable = true
        }
        val accent = ThemeEngine.accent

        // ---- Block 1: connectivity (2x2) + brightness & volume sliders.
        val b1 = FrameLayout(act)
        val conn = FrameLayout(act).apply { background = tileBg(dp(34).toFloat()) }
        val ip = dp(12)
        val big = (two - ip * 3) / 2
        val airplane = Settings.Global.getInt(act.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
        val wifi = runCatching { act.applicationContext.getSystemService(WifiManager::class.java).isWifiEnabled }.getOrDefault(false)
        val bt = runCatching { act.getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter?.isEnabled == true }.getOrDefault(false)
        val loc = runCatching {
            if (Build.VERSION.SDK_INT >= 28) act.getSystemService(android.location.LocationManager::class.java).isLocationEnabled else false
        }.getOrDefault(false)
        conn.addView(round(R.drawable.ic_cc_airplane, airplane, big, activeColor = 0xFFFF9F0A.toInt()) {
            openPanel(Settings.ACTION_AIRPLANE_MODE_SETTINGS, null)
        }, lp(big, big, ip, ip))
        conn.addView(round(R.drawable.ic_broadcast, false, big) { openHotspot() }, lp(big, big, ip * 2 + big, ip))
        conn.addView(round(R.drawable.ic_cc_wifi, wifi, big, activeColor = if (ThemeEngine.colorful) accent else 0xFF0A84FF.toInt()) {
            openPanel(Settings.ACTION_WIFI_SETTINGS, if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_WIFI else null)
        }, lp(big, big, ip, ip * 2 + big))
        val mini = (big - dp(6)) / 2
        val mx = ip * 2 + big; val my = ip * 2 + big
        conn.addView(round(R.drawable.ic_cc_cell, true, mini, activeColor = 0xFF30D158.toInt()) {
            openPanel(Settings.ACTION_DATA_ROAMING_SETTINGS, if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_INTERNET_CONNECTIVITY else null)
        }, lp(mini, mini, mx, my))
        conn.addView(round(R.drawable.ic_cc_bluetooth, bt, mini, activeColor = 0xFF0A84FF.toInt()) {
            openPanel(Settings.ACTION_BLUETOOTH_SETTINGS, null)
        }, lp(mini, mini, mx + mini + dp(6), my))
        conn.addView(round(R.drawable.ic_cc_vpn, false, mini) { openPanel(Settings.ACTION_VPN_SETTINGS, null) },
            lp(mini, mini, mx, my + mini + dp(6)))
        conn.addView(round(R.drawable.ic_cc_location, loc, mini, activeColor = 0xFF0A84FF.toInt()) {
            openPanel(Settings.ACTION_LOCATION_SOURCE_SETTINGS, null)
        }, lp(mini, mini, mx + mini + dp(6), my + mini + dp(6)))
        b1.addView(conn, lp(two, two))
        b1.addView(brightnessSlider(), lp(c, two, two + gap, 0))
        b1.addView(volumeSlider(), lp(c, two, two + gap * 2 + c, 0))
        panel.addView(b1, LinearLayout.LayoutParams(W, two))

        // ---- Row 2: battery saver, screen record, Focus / Do Not Disturb.
        val r2 = FrameLayout(act)
        val saver = runCatching { act.getSystemService(android.os.PowerManager::class.java).isPowerSaveMode }.getOrDefault(false)
        r2.addView(round(R.drawable.ic_cc_battery, saver, c, activeColor = 0xFFFFD60A.toInt()) {
            openPanel(Settings.ACTION_BATTERY_SAVER_SETTINGS, null)
        }, lp(c, c))
        r2.addView(round(R.drawable.ic_cc_record, false, c) { openQuickSettings("Tap Screen record in Quick Settings") }, lp(c, c, c + gap))
        val focusName = focusLabel()
        r2.addView(pill(R.drawable.ic_cc_moon, if (focusName == "Off") "Do Not Disturb" else focusName,
            if (focusName == "Off") "Focus off" else "On", c, if (focusName == "Off") Color.WHITE else accent) {
            hide(); onFocus()
        }, lp(two, c, two + gap))
        panel.addView(r2, LinearLayout.LayoutParams(W, c).apply { topMargin = gap })

        // ---- Music tile.
        panel.addView(musicTile(W, (c * 1.75f).toInt()), LinearLayout.LayoutParams(W, (c * 1.75f).toInt()).apply { topMargin = gap })

        // ---- Block 3: orientation lock (2x2) + flashlight, dark mode, music app, ring/silent.
        val b3 = FrameLayout(act)
        val autoRotate = runCatching { Settings.System.getInt(act.contentResolver, Settings.System.ACCELEROMETER_ROTATION) == 1 }.getOrDefault(true)
        val orient = FrameLayout(act).apply {
            background = tileBg(dp(34).toFloat(), !autoRotate, Color.WHITE)
            setPadding(dp(18), dp(18), dp(18), dp(16))
        }
        val oTint = if (!autoRotate) 0xFFFF453A.toInt() else Color.WHITE
        orient.addView(icon(R.drawable.ic_cc_rotation, oTint), FrameLayout.LayoutParams(dp(38), dp(38), Gravity.TOP or Gravity.START))
        val oText = TextView(act).apply {
            text = "Orientation\n${if (autoRotate) "Unlocked" else "Locked"}"
            setTextColor(if (!autoRotate) 0xFF1C1C1E.toInt() else Color.WHITE); textSize = 15f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        orient.addView(oText, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.START))
        orient.setOnClickListener { v -> tick(v); press(v); toggleRotation() }
        b3.addView(orient, lp(two, two))
        lateinit var torch: FrameLayout
        torch = round(R.drawable.ic_cc_flashlight, torchOn, c, activeColor = Color.WHITE) {
            if (setTorch(!torchOn)) {
                torch.background = tileBg(c / 2f, torchOn, Color.WHITE)
                (torch.getChildAt(0) as ImageView).setColorFilter(if (torchOn) 0xFF1C1C1E.toInt() else Color.WHITE)
            }
        }
        b3.addView(torch, lp(c, c, two + gap, 0))
        val night = (act.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        b3.addView(round(R.drawable.ic_cc_contrast, night, c, activeColor = Color.WHITE) {
            hide()
            val ok = runCatching { act.startActivity(Intent("android.settings.DARK_THEME_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
            if (!ok) runCatching { act.startActivity(Intent(Settings.ACTION_DISPLAY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }, lp(c, c, two + gap * 2 + c, 0))
        b3.addView(round(R.drawable.ic_cc_notes, musicPlaying(), c) { hide(); openMusicApp() }, lp(c, c, two + gap, c + gap))
        val audio = act.getSystemService(AudioManager::class.java)
        lateinit var bell: FrameLayout
        fun bellIcon() = if (audio.ringerMode == AudioManager.RINGER_MODE_NORMAL) R.drawable.ic_cc_bell else R.drawable.ic_cc_bell_off
        bell = round(bellIcon(), false, c) {
            runCatching {
                audio.ringerMode = if (audio.ringerMode == AudioManager.RINGER_MODE_NORMAL) AudioManager.RINGER_MODE_VIBRATE
                    else AudioManager.RINGER_MODE_NORMAL
            }
            (bell.getChildAt(0) as ImageView).setImageResource(bellIcon())
            toast(if (audio.ringerMode == AudioManager.RINGER_MODE_NORMAL) "Ring" else "Silent (vibrate)")
        }
        b3.addView(bell, lp(c, c, two + gap * 2 + c, c + gap))
        panel.addView(b3, LinearLayout.LayoutParams(W, two).apply { topMargin = gap })

        // ---- Row 4: Recognise Music + Headphone Level.
        val ph = (c * 0.86f).toInt()
        val r4 = FrameLayout(act)
        r4.addView(pill(R.drawable.ic_cc_shazam, "Recognise", "Music", ph, null) { hide(); recogniseMusic() }, lp(two, ph))
        val hp = headphoneName()
        r4.addView(pill(R.drawable.ic_headphones, "Headphone", if (hp != null) "Level" else "Level", ph, null) {
            hide(); onVolumePanel?.invoke()
        }, lp(two, ph, two + gap))
        panel.addView(r4, LinearLayout.LayoutParams(W, ph).apply { topMargin = gap })

        // ---- Row 5: lock, camera, calculator, voice recorder.
        val r5 = FrameLayout(act)
        r5.addView(round(R.drawable.ic_cc_lock, false, c) { hide(); onLock() }, lp(c, c))
        r5.addView(round(R.drawable.ic_cc_camera, false, c) {
            hide(); runCatching { act.startActivity(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }, lp(c, c, c + gap))
        r5.addView(round(R.drawable.ic_cc_calc, false, c) {
            hide()
            runCatching {
                act.startActivity(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALCULATOR).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.onFailure { toast("No calculator app found") }
        }, lp(c, c, (c + gap) * 2))
        r5.addView(round(R.drawable.ic_cc_record, false, c) {
            hide()
            runCatching { act.startActivity(Intent(MediaStore.Audio.Media.RECORD_SOUND_ACTION).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                .onFailure { toast("No voice recorder app found") }
        }.also { (it.getChildAt(0) as ImageView).setImageResource(R.drawable.ic_cc_mic) }, lp(c, c, (c + gap) * 3))
        panel.addView(r5, LinearLayout.LayoutParams(W, c).apply { topMargin = gap })

        val scroll = android.widget.ScrollView(act).apply {
            isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER
            addView(panel, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        if (pagesOn) {
            // Multi-page Control Center: swipe sideways to "My Controls".
            val second = myControlsPage(W, c, gap, pad, topInset, bottomInset)
            val vp = ViewPager2(act).apply { adapter = FixedPages(listOf(scroll, second)); offscreenPageLimit = 1 }
            (vp.getChildAt(0) as? RecyclerView)?.overScrollMode = View.OVER_SCROLL_NEVER
            l.addView(vp, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT, Gravity.TOP))
            val dotsRow = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
            val dotViews = (0..1).map { View(act).apply { background = circle(Color.WHITE); alpha = if (it == 0) 1f else 0.35f } }
            dotViews.forEach { dotsRow.addView(it, LinearLayout.LayoutParams(dp(7), dp(7)).apply { setMargins(dp(4), 0, dp(4), 0) }) }
            l.addView(dotsRow, FrameLayout.LayoutParams(MATCH_PARENT, dp(16), Gravity.BOTTOM).apply { bottomMargin = bottomInset + dp(6) })
            vp.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    dotViews.forEachIndexed { i, d -> d.animate().alpha(if (i == position) 1f else 0.35f).setDuration(150).start() }
                    if (haptics()) vp.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                }
            })
        } else l.addView(scroll, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT, Gravity.TOP))
        // Tapping empty space in the panel closes it too.
        panel.setOnClickListener { hide() }
        root.addView(l, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        layer = l
        onBlur(true)
        if (haptics()) root.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        l.animate().alpha(1f).setDuration((220 * Motion.scale).toLong()).start()
        // Tiles drop in from the top with a spring, a touch staggered.
        for (i in 0 until panel.childCount) {
            val ch = panel.getChildAt(i)
            ch.alpha = 0f
            if (!bounceIn) { ch.animate().alpha(1f).setDuration((160 * Motion.scale).toLong()).start(); continue }
            ch.postDelayed({
                Motion.spring(ch, DynamicAnimation.TRANSLATION_Y, 0f, Motion.BOUNCY, start = -dp(60).toFloat())
                Motion.alphaTo(ch, 1f, Motion.SNAPPY)
            }, (i * 30L * Motion.scale).toLong())
        }
    }

    /** iOS 18-style Now Playing tile: artwork, title, AirPlay-like output, controls and progress. */
    private fun musicTile(w: Int, h: Int): View {
        val tile = FrameLayout(act).apply { background = tileBg(dp(34).toFloat()); isClickable = true }
        val m = media?.invoke()
        val t = mediaTitle?.invoke()
        val info = music()
        val p = dp(16)
        val artSize = h - p * 2
        val art = ImageView(act).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, dp(18).toFloat())
            }
            clipToOutline = true
            if (info?.third != null) setImageBitmap(info.third)
            else { setImageResource(R.drawable.ic_music); setColorFilter(0x88FFFFFF.toInt()); background = circle(0); setPadding(dp(24), dp(24), dp(24), dp(24))
                setBackgroundColor(Color.argb(60, 255, 255, 255)) }
        }
        tile.addView(art, lp(artSize, artSize, p, p))
        val right = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        val head = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(TextView(act).apply {
            text = if (t == null) "Not Playing" else "${t.first}\n${t.second}"
            setTextColor(Color.WHITE); textSize = 15f; maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        head.addView(round(R.drawable.ic_broadcast, false, dp(40)) { hide(); m?.onOutput?.invoke() }, LinearLayout.LayoutParams(dp(40), dp(40)))
        right.addView(head)
        right.addView(View(act), LinearLayout.LayoutParams(0, 0, 1f))
        val ctr = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        fun cbtn(res: Int, size: Int, fn: () -> Unit) = ImageView(act).apply {
            setImageResource(res); setColorFilter(if (m == null) 0x66FFFFFF else Color.WHITE)
            setOnClickListener { v -> tick(v); press(v); fn() }
        }.also { ctr.addView(it, LinearLayout.LayoutParams(0, dp(size), 1f)) }
        cbtn(R.drawable.ic_island_prev, 26) { prev() }
        val play = cbtn(if (musicPlaying()) R.drawable.ic_island_pause else R.drawable.ic_island_play, 32) { playPause() }
        cbtn(R.drawable.ic_island_next, 26) { next() }
        right.addView(ctr)
        val bar = IslandProgressView(act)
        right.addView(bar, LinearLayout.LayoutParams(MATCH_PARENT, dp(14)).apply { topMargin = dp(8) })
        val times = FrameLayout(act)
        val el = TextView(act).apply { setTextColor(0x99FFFFFF.toInt()); textSize = 11f }
        val rem = TextView(act).apply { setTextColor(0x99FFFFFF.toInt()); textSize = 11f }
        times.addView(el, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.START))
        times.addView(rem, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.END))
        right.addView(times)
        tile.addView(right, lp(w - artSize - p * 3, h - p * 2, artSize + p * 2, p))
        if (m != null && m.duration > 0) {
            bar.onSeek = { f -> m.onSeek((f * m.duration).toLong()) }
            fun f(ms: Long): String { val s = (ms / 1000).coerceAtLeast(0); return String.format("%d:%02d", s / 60, s % 60) }
            val r = object : Runnable {
                override fun run() {
                    if (layer == null) return
                    val pos = m.position().coerceIn(0, m.duration)
                    bar.fraction = pos.toFloat() / m.duration
                    el.text = f(pos); rem.text = "-" + f(m.duration - pos)
                    play.setImageResource(if (m.playing()) R.drawable.ic_island_pause else R.drawable.ic_island_play)
                    tile.postDelayed(this, 500)
                }
            }
            musicTicker = r
            r.run()
        } else bar.visibility = View.INVISIBLE
        return tile
    }

    private fun openHotspot() {
        hide()
        val tries = listOf(
            Intent().setClassName("com.android.settings", "com.android.settings.TetherSettings"),
            Intent("android.settings.TETHER_SETTINGS"),
            Intent(Settings.ACTION_WIRELESS_SETTINGS)
        )
        for (i in tries) if (runCatching { act.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
    }

    /** Opens Android's Quick Settings (for tiles a launcher can't run itself, like screen recording). */
    private fun openQuickSettings(hint: String) {
        hide()
        val ok = LuncherAccessibility.perform(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS) || runCatching {
            @Suppress("WrongConstant")
            val sb = act.getSystemService("statusbar")
            sb.javaClass.getMethod("expandSettingsPanel").invoke(sb)
        }.isSuccess
        if (ok) toast(hint)
    }

    private fun toggleRotation() {
        if (!Settings.System.canWrite(act)) {
            toast("Allow “Modify system settings” to lock rotation")
            runCatching {
                act.startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${act.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            return
        }
        val cur = Settings.System.getInt(act.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 1)
        runCatching { Settings.System.putInt(act.contentResolver, Settings.System.ACCELEROMETER_ROTATION, if (cur == 1) 0 else 1) }
        toast(if (cur == 1) "Orientation Locked" else "Orientation Unlocked")
        // Rebuild so the tile shows the new state.
        layer?.let { root.removeView(it); layer = null; onBlur(false) }
        show(lastTop, lastBottom)
    }
    private var lastTop = 0
    private var lastBottom = 0

    private fun openMusicApp() {
        val i = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MUSIC)
        runCatching { act.startActivity((musicApp?.invoke() ?: i).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { toast("No music app found") }
    }
    /** Launch intent of the app that's playing, set by the launcher. */
    var musicApp: (() -> Intent?)? = null

    private fun recogniseMusic() {
        val pm = act.packageManager
        val shazam = pm.getLaunchIntentForPackage("com.shazam.android")
        val tries = listOfNotNull(
            shazam,
            Intent("com.google.android.googlequicksearchbox.MUSIC_SEARCH"),
            Intent("android.media.action.MEDIA_PLAY_FROM_SEARCH").setPackage("com.google.android.googlequicksearchbox")
        )
        for (i in tries) if (runCatching { act.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
        toast("Install Shazam or the Google app to recognise music")
    }

    private fun headphoneName(): String? = runCatching {
        act.getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull {
            it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET
        }?.productName?.toString()
    }.getOrNull()

    fun hide() {
        val l = layer ?: return
        layer = null
        onBlur(false)
        l.animate().alpha(0f).translationY(-dp(30).toFloat()).setDuration((200 * Motion.scale).toLong())
            .withEndAction { root.removeView(l) }.start()
    }

    private fun toast(s: String) = Toast.makeText(act, s, Toast.LENGTH_SHORT).show()

    private fun openPanel(fallback: String, panel: String?) {
        hide()
        val ok = panel != null && runCatching {
            act.startActivity(Intent(panel).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
        if (!ok) runCatching { act.startActivity(Intent(fallback).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    private fun iconText(t: String, size: Float) = TextView(act).apply {
        text = t; setTextColor(Color.WHITE); textSize = size; gravity = Gravity.CENTER
    }

    private fun roundButton(icon: String, active: Int?, onClick: () -> Unit): View {
        val v = TextView(act).apply {
            text = icon; textSize = 20f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
            background = circle(active ?: Color.argb(70, 255, 255, 255))
            setOnClickListener { tick(it); onClick() }
        }
        v.layoutParams = GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED, 1f), GridLayout.spec(GridLayout.UNDEFINED, 1f))
            .apply { width = dp(60); height = dp(60); setMargins(dp(4), dp(4), dp(4), dp(4)) }
        return v
    }

    private fun squareTile(icon: String, active: Int? = null, onClick: () -> Unit): TextView = TextView(act).apply {
        text = icon; textSize = 24f; gravity = Gravity.CENTER
        background = if (active != null) glass(22, active) else glass(22)
        setOnClickListener { tick(it); onClick() }
    }

    private fun setTorch(on: Boolean): Boolean {
        val cm = act.getSystemService(CameraManager::class.java) ?: return false
        val id = runCatching {
            cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        }.getOrNull() ?: run { toast("No flashlight on this phone"); return false }
        return runCatching { cm.setTorchMode(id, on); torchOn = on }.isSuccess
    }

    /** Tall glass slider like iOS: drag up/down; the white fill shows the level. */
    /**
     * Swipeable minute ruler with a big time readout. Each minute "clicks" with a haptic tick;
     * flings glide and snap. Tap to start (or stop a running timer).
     */
    private inner class TimerDial : View(act) {
        private val prefs = act.getSharedPreferences("luncher", android.content.Context.MODE_PRIVATE)
        private val step = dp(10).toFloat()
        private val maxMin = 180
        private var offset = prefs.getInt("cc_timer_min", 10).coerceIn(1, maxMin) * step
        private var lastMin = (offset / step).roundToInt()
        private val scroller = android.widget.OverScroller(act, android.view.animation.DecelerateInterpolator(1.4f))
        private var tracker: android.view.VelocityTracker? = null
        private var downX = 0f
        private var lastX = 0f
        private var moved = false
        private var touching = false
        private val slop = android.view.ViewConfiguration.get(act).scaledTouchSlop
        private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; strokeCap = Paint.Cap.ROUND }
        private val numPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = dp(10).toFloat(); textAlign = Paint.Align.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        private val orange = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFF9F0A.toInt(); strokeCap = Paint.Cap.ROUND }
        private val big = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = dp(30).toFloat(); textAlign = Paint.Align.RIGHT
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL); fontFeatureSettings = "tnum"
        }
        private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xCCFFFFFF.toInt(); textSize = dp(13).toFloat()
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        private val tri = android.graphics.Path()
        private val listener: () -> Unit = { invalidate() }

        init {
            background = tileBg(dp(30).toFloat())
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) =
                    outline.setRoundRect(0, 0, view.width, view.height, dp(30).toFloat())
            }
            clipToOutline = true
        }

        override fun onAttachedToWindow() { super.onAttachedToWindow(); TimerState.add(listener) }
        override fun onDetachedFromWindow() { TimerState.remove(listener); super.onDetachedFromWindow() }

        private fun fmt(totalSec: Long): String {
            val h = totalSec / 3600; val m = (totalSec / 60) % 60; val s = totalSec % 60
            return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%d:%02d", m, s)
        }

        override fun onDraw(c: Canvas) {
            super.onDraw(c)
            val w = width.toFloat()
            val cx = w / 2f
            val running = TimerState.running && !touching && scroller.isFinished
            // Ruler position: the picked minute, or the time left while a timer runs.
            val cur = if (running) TimerState.remaining / 60_000f else offset / step
            val numY = dp(16).toFloat()
            val tickTop = dp(22).toFloat()
            val first = (cur - cx / step).toInt() - 1
            val last = (cur + cx / step).toInt() + 1
            for (m in first..last) {
                if (m < 0 || m > maxMin) continue
                val x = cx + (m - cur) * step
                val fade = (1f - kotlin.math.abs(x - cx) / cx).coerceIn(0f, 1f)
                val major = m % 5 == 0
                tickPaint.alpha = ((if (major) 230 else 110) * fade).toInt()
                tickPaint.strokeWidth = dp(if (major) 2 else 1).toFloat().coerceAtLeast(1.5f)
                c.drawLine(x, tickTop, x, tickTop + dp(if (major) 14 else 8), tickPaint)
                if (major) {
                    numPaint.alpha = (200 * fade).toInt()
                    c.drawText("$m", x, numY, numPaint)
                }
            }
            // Orange pointer in the middle.
            orange.strokeWidth = dp(2).toFloat()
            c.drawLine(cx, tickTop - dp(2), cx, tickTop + dp(16), orange)
            tri.reset()
            tri.moveTo(cx - dp(5), tickTop + dp(22)); tri.lineTo(cx + dp(5), tickTop + dp(22)); tri.lineTo(cx, tickTop + dp(16)); tri.close()
            c.drawPath(tri, orange)

            val baseY = height - dp(16).toFloat()
            small.color = 0xCCFFFFFF.toInt()
            if (running) {
                c.drawText("Timer · tap to stop", dp(18).toFloat(), baseY, small)
                big.color = 0xFFFF9F0A.toInt()
                c.drawText(fmt(TimerState.remaining / 1000), w - dp(18), baseY + dp(4), big)
            } else {
                c.drawText("Start Timer", dp(18).toFloat(), baseY, small)
                big.color = Color.WHITE
                c.drawText(fmt((offset / step).roundToInt() * 60L), w - dp(18), baseY + dp(4), big)
            }
        }

        private fun minuteChanged() {
            val m = (offset / step).roundToInt()
            if (m != lastMin) {
                lastMin = m
                if (haptics()) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            }
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (tracker == null) tracker = android.view.VelocityTracker.obtain()
            tracker?.addMovement(e)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    scroller.forceFinished(true)
                    downX = e.x; lastX = e.x; moved = false; touching = true
                    if (TimerState.running) offset = (TimerState.remaining / 60_000f).roundToInt().coerceIn(1, maxMin) * step
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!moved && kotlin.math.abs(e.x - downX) > slop) moved = true
                    if (moved) {
                        offset = (offset - (e.x - lastX)).coerceIn(step, maxMin * step)
                        minuteChanged(); invalidate()
                    }
                    lastX = e.x
                }
                MotionEvent.ACTION_UP -> {
                    touching = false
                    if (!moved) {
                        tick(this)
                        if (TimerState.running) TimerState.cancel(act)
                        else {
                            val m = (offset / step).roundToInt().coerceIn(1, maxMin)
                            prefs.edit().putInt("cc_timer_min", m).apply()
                            TimerState.start(act, m)
                        }
                        invalidate()
                    } else {
                        tracker?.computeCurrentVelocity(1000)
                        val vx = tracker?.xVelocity ?: 0f
                        scroller.fling(offset.toInt(), 0, -vx.toInt(), 0, step.toInt(), (maxMin * step).toInt(), 0, 0)
                        postInvalidateOnAnimation()
                    }
                    tracker?.recycle(); tracker = null
                }
                MotionEvent.ACTION_CANCEL -> { touching = false; tracker?.recycle(); tracker = null; snap() }
            }
            return true
        }

        override fun computeScroll() {
            if (!scroller.isFinished && scroller.computeScrollOffset()) {
                offset = scroller.currX.toFloat()
                minuteChanged()
                postInvalidateOnAnimation()
                if (scroller.isFinished) snap()
            }
        }

        private fun snap() {
            val target = (offset / step).roundToInt().coerceIn(1, maxMin) * step
            android.animation.ValueAnimator.ofFloat(offset, target).apply {
                duration = (160 * Motion.scale).toLong().coerceAtLeast(1)
                addUpdateListener { offset = it.animatedValue as Float; invalidate() }
                start()
            }
            prefs.edit().putInt("cc_timer_min", (target / step).roundToInt()).apply()
        }
    }

    private inner class GlassSlider(
        private val iconRes: Int,
        private var value: Float,
        private val onChange: (Float) -> Unit
    ) : View(act) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ThemeEngine.accent }
        private val iconDr = act.getDrawable(iconRes)?.mutate()
        init {
            background = tileBg(dp(34).toFloat())
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) =
                    outline.setRoundRect(0, 0, view.width, view.height, dp(34).toFloat())
            }
            clipToOutline = true
        }

        override fun onDraw(canvas: Canvas) {
            val h = height.toFloat()
            canvas.drawRect(0f, h * (1f - value), width.toFloat(), h, fill)
            val s = dp(28)
            val top = (h - dp(18) - s).toInt()
            iconDr?.setBounds((width - s) / 2, top, (width + s) / 2, top + s)
            iconDr?.setTint(if (value * h > h - top - dp(4)) (if (ThemeEngine.luminance(ThemeEngine.accent) > 0.5f) 0xFF6E6E73.toInt() else Color.WHITE) else Color.WHITE)
            iconDr?.draw(canvas)
        }

        private var startY = 0f
        private var startV = 0f
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startY = e.y; startV = value; parent?.requestDisallowInterceptTouchEvent(true)
                    Motion.scaleTo(this, 1.04f, Motion.SNAPPY)
                }
                MotionEvent.ACTION_MOVE -> {
                    value = (startV + (startY - e.y) / height.coerceAtLeast(1)).coerceIn(0f, 1f)
                    onChange(value); invalidate()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    Motion.scaleTo(this, 1f, Motion.BOUNCY); tick(this)
                }
            }
            return true
        }
    }

    private fun brightnessSlider(): View {
        val cur = runCatching {
            Settings.System.getInt(act.contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f
        }.getOrDefault(0.5f)
        var asked = false
        return GlassSlider(R.drawable.ic_cc_sun, cur) { v ->
            if (Settings.System.canWrite(act)) {
                runCatching {
                    Settings.System.putInt(act.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
                        Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                    Settings.System.putInt(act.contentResolver, Settings.System.SCREEN_BRIGHTNESS,
                        (v * 255).toInt().coerceIn(2, 255))
                }
            } else {
                // Until "Modify system settings" is allowed, at least brighten/dim this screen.
                val lp = act.window.attributes
                lp.screenBrightness = v.coerceIn(0.02f, 1f)
                act.window.attributes = lp
                if (!asked) {
                    asked = true
                    toast("Allow “Modify system settings” to change brightness everywhere")
                    runCatching {
                        act.startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                            Uri.parse("package:${act.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
            }
        }
    }

    private fun volumeSlider(): View {
        val audio = act.getSystemService(AudioManager::class.java)
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return GlassSlider(R.drawable.ic_speaker, audio.getStreamVolume(AudioManager.STREAM_MUSIC) / max.toFloat()) { v ->
            runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, (v * max).roundToInt(), 0) }
        }
    }
}
