package com.luncher.home

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.dynamicanimation.animation.DynamicAnimation
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The AirPods-style card: when headphones connect (Bluetooth or wired), a glass card
 * springs up from the bottom with the headphones, their name and battery level.
 */
class HeadsetPopup private constructor(private val app: Context) {
    companion object {
        const val REQ_BLUETOOTH = 4300
        @Volatile private var shared: HeadsetPopup? = null

        /**
         * One watcher for the whole app. It's started by the launcher and also by the
         * notification / accessibility services, so it keeps listening while another
         * app is open.
         */
        fun get(ctx: Context): HeadsetPopup = shared ?: synchronized(this) {
            shared ?: HeadsetPopup(ctx.applicationContext).also { shared = it; it.start() }
        }

        /** True when the card can appear on top of other apps. */
        fun canShowOverApps(ctx: Context): Boolean =
            LuncherAccessibility.instance != null || android.provider.Settings.canDrawOverlays(ctx)
        private const val ACTION_BATTERY = "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED"
        private const val EXTRA_BATTERY = "android.bluetooth.device.extra.BATTERY_LEVEL"
    }

    private class Headset(
        val key: String,
        val name: String,
        val address: String?,
        val bluetooth: Boolean,
        val audioType: Int,
        var kind: HeadsetKind
    )

    private val settings = Settings(app)
    private fun enabled() = settings.headsetPopup
    private fun haptics() = settings.haptics

    // The launcher screen, when it's in front.
    private var host: Activity? = null
    private var hostRoot: FrameLayout? = null
    var hostResumed = false

    // The floating window used when another app is in front.
    private var overlay: FrameLayout? = null
    private var overlayWm: WindowManager? = null
    private var inApp = true

    fun attach(act: Activity, root: FrameLayout) { host = act; hostRoot = root }
    fun detach(act: Activity) {
        if (host !== act) return
        if (inApp) hide(immediate = true)
        host = null; hostRoot = null; hostResumed = false
    }

    private val audio = app.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val ease = PathInterpolator(0.2f, 0.9f, 0.1f, 1f)
    private val known = HashSet<String>()
    private var layer: FrameLayout? = null
    private var card: View? = null
    private var shown: Headset? = null
    private var batteryView: HeadsetBatteryView? = null
    private var batteryLabel: TextView? = null
    private var permissionHint: TextView? = null
    private val autoHide = Runnable { hide() }
    var bottomInset = 0

    private fun dp(v: Int) = (v * app.resources.displayMetrics.density).roundToInt()

    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            for (d in addedDevices) {
                val h = headsetOf(d) ?: continue
                if (known.add(h.key)) onConnected(h)
            }
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            for (d in removedDevices) {
                val h = headsetOf(d) ?: continue
                // Bluetooth headsets show up as several devices; only react once all are gone.
                val stillThere = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { headsetOf(it)?.key == h.key }
                if (!stillThere && known.remove(h.key) && shown?.key == h.key) hide()
            }
        }
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val level = i.getIntExtra(EXTRA_BATTERY, -1)
            @Suppress("DEPRECATION")
            val device = i.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
            val addr = runCatching { device?.address }.getOrNull()
            val h = shown ?: return
            if (level in 0..100 && h.bluetooth && (addr == null || addr == h.address)) showBattery(level)
        }
    }

    private fun start() {
        // Headsets already plugged in when the launcher starts don't trigger a pop-up.
        for (d in audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) headsetOf(d)?.let { known += it.key }
        audio.registerAudioDeviceCallback(callback, handler)
        ContextCompat.registerReceiver(app, batteryReceiver, IntentFilter(ACTION_BATTERY), ContextCompat.RECEIVER_EXPORTED)
    }

    private fun stop() {
        runCatching { audio.unregisterAudioDeviceCallback(callback) }
        runCatching { app.unregisterReceiver(batteryReceiver) }
        handler.removeCallbacksAndMessages(null)
    }

    /** Called after the user answers the Bluetooth permission prompt. */
    fun onPermissionResult() {
        val h = shown ?: return
        permissionHint?.visibility = View.GONE
        fetchBattery(h)
    }

    private fun headsetOf(d: AudioDeviceInfo): Headset? {
        if (!d.isSink) return null
        val bt = d.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
            d.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
            (Build.VERSION.SDK_INT >= 31 && d.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
        val wired = d.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
            d.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
            (Build.VERSION.SDK_INT >= 26 && d.type == AudioDeviceInfo.TYPE_USB_HEADSET)
        if (!bt && !wired) return null
        val address = if (Build.VERSION.SDK_INT >= 28) d.address.takeIf { it.isNotBlank() } else null
        val product = d.productName?.toString()?.trim().orEmpty()
        val name = when {
            bt && product.isNotEmpty() && !product.equals(Build.MODEL, true) -> product
            bt -> "Bluetooth Headphones"
            else -> "Headphones"
        }
        val key = if (bt) "bt:" + (address ?: name) else "wired"
        val kind = if (bt) guessKind(name, null) else HeadsetKind.WIRED
        return Headset(key, name, address, bt, d.type, kind)
    }

    /**
     * Work out what the user is wearing. The device name is the strongest hint
     * ("Galaxy Buds", "WH-1000XM5", "JBL Flip"…), then the Bluetooth device class.
     */
    private fun guessKind(name: String, btClass: Int?): HeadsetKind {
        val n = name.lowercase()
        fun has(vararg words: String) = words.any { n.contains(it) }
        return when {
            has("airpods max", "pixel buds pro max") -> HeadsetKind.OVER_EAR
            has("speaker", "soundlink", "flip", "charge", "boombox", "boom", "pulse", "xtreme",
                "soundcore motion", "wonderboom", "srs-", "go 3", "clip", "echo", "home") -> HeadsetKind.SPEAKER
            has("buds", "pods", "earbud", "ear bud", "tws", "wf-", "elite", "freebuds", "liberty", "nothing ear",
                "ear (", "earfun", "airdots", "redmi", "oneplus buds", "life p", "life a", "jlab", "skullcandy",
                "beats fit", "powerbeats", "studio buds", "in-ear", "in ear", "true wireless") -> HeadsetKind.EARBUDS
            has("wh-", "headphone", "headset", "qc", "quietcomfort", "bose 700", "momentum", "max", "studio",
                "solo", "hd ", "hd4", "hd5", "ath-", "over-ear", "over ear", "q20", "q30", "q35", "q45",
                "life q", "tune 7", "live 6", "live 7", "arctis", "kraken", "hyperx", "steelseries", "razer",
                "logitech", "corsair") -> HeadsetKind.OVER_EAR
            btClass == android.bluetooth.BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES -> HeadsetKind.OVER_EAR
            btClass == android.bluetooth.BluetoothClass.Device.AUDIO_VIDEO_LOUDSPEAKER ||
                btClass == android.bluetooth.BluetoothClass.Device.AUDIO_VIDEO_PORTABLE_AUDIO ||
                btClass == android.bluetooth.BluetoothClass.Device.AUDIO_VIDEO_HIFI_AUDIO -> HeadsetKind.SPEAKER
            else -> HeadsetKind.EARBUDS
        }
    }

    /** Refine the guess with the Bluetooth device class once we're allowed to read it. */
    private fun refineKind(h: Headset) {
        if (!h.bluetooth || !hasBluetoothPermission()) return
        val addr = h.address ?: return
        val cls = runCatching {
            val adapter = app.getSystemService(BluetoothManager::class.java)?.adapter ?: return
            adapter.getRemoteDevice(addr).bluetoothClass?.deviceClass
        }.getOrNull() ?: return
        h.kind = guessKind(h.name, cls)
    }

    private fun onConnected(h: Headset) {
        if (!enabled()) return
        // Give Bluetooth a moment so A2DP/SCO duplicates settle and the name is known.
        handler.postDelayed({ show(h) }, if (h.bluetooth) 450 else 150)
    }

    /** Never leave a window behind: if anything goes wrong while building the card, remove it. */
    private fun show(h: Headset) {
        try {
            showInner(h)
        } catch (t: Throwable) {
            android.util.Log.w("HeadsetPopup", "pop-up failed", t)
            hide(immediate = true)
            forceRemoveOverlay()
        }
        // Hard safety net: whatever happens, the floating window is gone after 15 seconds.
        handler.removeCallbacks(watchdog)
        handler.postDelayed(watchdog, 15_000)
    }

    private val watchdog = Runnable { hide(immediate = true); forceRemoveOverlay() }

    private fun forceRemoveOverlay() {
        val win = overlay ?: return
        val wm = overlayWm
        overlay = null
        overlayWm = null
        runCatching { wm?.removeViewImmediate(win) }
    }

    private fun showInner(h: Headset) {
        hide(immediate = true)
        forceRemoveOverlay()
        // In the launcher: draw inside it. Anywhere else: a floating window over the app.
        val a = host
        val useApp = a != null && hostRoot != null && hostResumed && !a.isFinishing && !a.isDestroyed
        val act: Context
        val root: ViewGroup
        var navInset = bottomInset
        if (useApp) {
            act = a!!
            root = hostRoot!!
        } else {
            val svc = LuncherAccessibility.instance
            val type = when {
                svc != null -> WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                android.provider.Settings.canDrawOverlays(app) -> WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else -> return
            }
            act = svc ?: app
            val wm = act.getSystemService(WindowManager::class.java) ?: return
            val win = FrameLayout(act)
            // Only as tall as the card, and taps outside it go straight to the app underneath
            // (a tap outside also closes the card), so the screen can never get "stuck".
            val lp = WindowManager.LayoutParams(
                MATCH_PARENT, WRAP_CONTENT, type,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.BOTTOM
                if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                if (Build.VERSION.SDK_INT >= 30) setFitInsetsTypes(0)
            }
            win.setOnTouchListener { _, e ->
                if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) hide()
                false
            }
            if (runCatching { wm.addView(win, lp) }.isFailure) return
            overlay = win
            overlayWm = wm
            root = win
            val id = app.resources.getIdentifier("navigation_bar_height", "dimen", "android")
            navInset = if (id > 0) app.resources.getDimensionPixelSize(id) else dp(24)
        }
        inApp = useApp
        refineKind(h)
        shown = h

        val l = FrameLayout(act).apply {
            // Dim the Home Screen behind the card; over other apps there's no dim layer at all.
            if (useApp) {
                setBackgroundColor(Color.argb(60, 0, 0, 0))
                isClickable = true
                setOnClickListener { hide() }
            }
            alpha = 0f
        }

        val c = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
            // Small, minimal card (like iPhone's AirPods card), tinted with your wallpaper / theme colour.
            val tint = if (ThemeEngine.colorful) ThemeEngine.accent else 0xFF8E8E93.toInt()
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(IconFactory.blend(Color.argb(250, 34, 34, 36), tint, 0.16f), IconFactory.blend(Color.argb(252, 22, 22, 24), tint, 0.08f))
            ).apply {
                cornerRadius = dp(32).toFloat()
                setStroke(maxOf(1, dp(1)), ThemeEngine.alpha(IconFactory.blend(Color.WHITE, tint, 0.4f), 60))
            }
            elevation = dp(24).toFloat()
            isClickable = true
        }

        // Header: name centred, close button on the right.
        val header = FrameLayout(act)
        header.addView(TextView(act).apply {
            text = h.name
            setTextColor(Color.WHITE)
            textSize = 18f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER
            maxLines = 1
            setPadding(dp(40), 0, dp(40), 0)
        }, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.CENTER))
        header.addView(TextView(act).apply {
            text = "✕"
            setTextColor(0xCCFFFFFF.toInt())
            textSize = 14f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(70, 255, 255, 255))
            }
            setOnClickListener { hide() }
        }, FrameLayout.LayoutParams(dp(30), dp(30), Gravity.END or Gravity.CENTER_VERTICAL))
        c.addView(header, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        // Spinning 3D model of what's connected (earbuds, headphones, wired earphones, speaker).
        val art = Headset3DView(act, h.kind)
        val landscape = act.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        c.addView(art, LinearLayout.LayoutParams(MATCH_PARENT, dp(if (landscape) 110 else 150)).apply { topMargin = dp(2) })

        // Battery ring + label (Bluetooth only, once the level is known).
        // iPhone-style battery: a ring, the percentage under it, then what it is.
        val batteryRow = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val ring = HeadsetBatteryView(act).apply { visibility = if (h.bluetooth) View.INVISIBLE else View.GONE }
        batteryRow.addView(ring, LinearLayout.LayoutParams(dp(38), dp(38)))
        val label = TextView(act).apply {
            setTextColor(Color.WHITE)
            textSize = 15f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER
            text = "Connected"
            setPadding(0, dp(6), 0, 0)
        }
        batteryRow.addView(label)
        batteryRow.addView(TextView(act).apply {
            text = h.kind.label
            setTextColor(0x8CFFFFFF.toInt())
            textSize = 13f
            gravity = Gravity.CENTER
        })
        c.addView(batteryRow, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
            topMargin = dp(4); bottomMargin = dp(8)
        })
        batteryView = ring
        batteryLabel = label

        val hint = TextView(act).apply {
            text = "Allow “Nearby devices” to see battery"
            setTextColor(0xFF0A84FF.toInt())
            textSize = 13f
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(0, dp(6), 0, 0)
            setOnClickListener { requestBluetooth(force = true) }
        }
        c.addView(hint, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        permissionHint = hint


        // Never wider than a phone card — it no longer stretches across the whole screen (landscape / games).
        val cardW = minOf(act.resources.displayMetrics.widthPixels - dp(20), dp(340))
        l.addView(c, FrameLayout.LayoutParams(cardW, WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            setMargins(0, 0, 0, navInset + dp(12))
        })
        root.addView(l, FrameLayout.LayoutParams(MATCH_PARENT, if (useApp) MATCH_PARENT else WRAP_CONTENT))
        layer = l
        if (haptics()) l.performHapticFeedback(HapticFeedbackConstants.CONFIRM,
            HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING)
        card = c
        enableSwipeDown(c)

        // Spring up from below the screen, like the iOS card.
        val h0 = maxOf(root.height, app.resources.displayMetrics.heightPixels).toFloat().coerceAtLeast(dp(600).toFloat())
        l.animate().alpha(1f).setDuration((220 * Motion.scale).toLong()).setInterpolator(ease).start()
        Motion.spring(c, DynamicAnimation.TRANSLATION_Y, 0f, Motion.FLUID, start = h0)
        art.scaleX = 0.5f; art.scaleY = 0.5f
        art.postDelayed({ Motion.scaleTo(art, 1f, Motion.BOUNCY) }, 120)
        art.playEntrance()
        startFloating(art)

        if (h.bluetooth) {
            fetchBattery(h)
            handler.postDelayed({ if (shown === h) fetchBattery(h) }, 1500)
            handler.postDelayed({ if (shown === h) fetchBattery(h) }, 4000)
        }
        handler.removeCallbacks(autoHide)
        handler.postDelayed(autoHide, 7000)
    }

    private fun startFloating(v: View) {
        if (Motion.scale <= 0f) return
        val a = android.animation.ObjectAnimator.ofFloat(v, "translationY", 0f, -dp(6).toFloat()).apply {
            duration = 1600
            repeatMode = android.animation.ValueAnimator.REVERSE
            repeatCount = android.animation.ValueAnimator.INFINITE
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
        }
        v.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {}
            override fun onViewDetachedFromWindow(view: View) = a.cancel()
        })
        a.start()
    }

    private fun enableSwipeDown(c: View) {
        var downY = 0f
        c.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downY = e.rawY; handler.removeCallbacks(autoHide) }
                MotionEvent.ACTION_MOVE -> {
                    val dy = (e.rawY - downY).coerceAtLeast(0f)
                    v.translationY = dy
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (v.translationY > dp(90)) hide()
                    else {
                        Motion.spring(v, DynamicAnimation.TRANSLATION_Y, 0f, Motion.BOUNCY)
                        handler.postDelayed(autoHide, 5000)
                    }
                }
            }
            true
        }
    }

    fun hide(immediate: Boolean = false) {
        handler.removeCallbacks(autoHide)
        val l = layer
        if (l == null) { forceRemoveOverlay(); return }
        val c = card
        layer = null
        card = null
        shown = null
        batteryView = null
        batteryLabel = null
        permissionHint = null
        val win = overlay
        val wm = overlayWm
        overlay = null
        overlayWm = null
        val remove = {
            (l.parent as? ViewGroup)?.removeView(l)
            if (win != null) runCatching { wm?.removeViewImmediate(win) }
        }
        if (immediate || Motion.scale <= 0f || c == null) {
            remove()
            return
        }
        val dist = (c.height + bottomInset + dp(80)).toFloat()
        l.animate().alpha(0f).setDuration((260 * Motion.scale).toLong()).setInterpolator(ease).start()
        Motion.spring(c, DynamicAnimation.TRANSLATION_Y, dist, Motion.SNAPPY) { remove() }
        // Safety net in case the spring is interrupted.
        handler.postDelayed({ remove() }, 900)
    }

    private fun showBattery(level: Int) {
        val ring = batteryView ?: return
        if (ring.visibility != View.VISIBLE) {
            ring.visibility = View.VISIBLE
            ring.alpha = 0f
            Motion.alphaTo(ring, 1f, Motion.CALM)
            // The ring fills up to the level, like the iPhone card.
            android.animation.ValueAnimator.ofInt(0, level).apply {
                duration = (700 * Motion.scale).toLong().coerceAtLeast(1)
                interpolator = ease
                addUpdateListener { ring.level = it.animatedValue as Int }
                start()
            }
        } else ring.level = level
        batteryLabel?.text = "$level%"
        permissionHint?.visibility = View.GONE
    }

    private fun hasBluetoothPermission(): Boolean =
        Build.VERSION.SDK_INT < 31 ||
            app.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun requestBluetooth(force: Boolean) {
        if (Build.VERSION.SDK_INT < 31 || hasBluetoothPermission()) return
        val prefs = app.getSharedPreferences("luncher", Context.MODE_PRIVATE)
        if (!force && prefs.getBoolean("asked_bt_permission", false)) {
            permissionHint?.visibility = View.VISIBLE
            return
        }
        prefs.edit().putBoolean("asked_bt_permission", true).apply()
        val a = host
        if (inApp && a != null && !a.isFinishing) {
            a.requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQ_BLUETOOTH)
        } else if (force) {
            // Over another app there's no screen to ask from: open the app's permission page.
            hide(immediate = true)
            runCatching {
                app.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.fromParts("package", app.packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        } else {
            prefs.edit().putBoolean("asked_bt_permission", false).apply()
            permissionHint?.visibility = View.VISIBLE
        }
    }

    /** What's connected right now (for the side island): name, kind and battery (-1 = unknown). */
    fun connectedNow(): Triple<String, HeadsetKind, Int>? {
        val h = runCatching { audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstNotNullOfOrNull { headsetOf(it) } }.getOrNull()
            ?: return null
        runCatching { refineKind(h) }
        var level = -1
        val addr = h.address
        if (h.bluetooth && addr != null && hasBluetoothPermission()) level = runCatching {
            val dev = app.getSystemService(BluetoothManager::class.java)!!.adapter.getRemoteDevice(addr)
            dev.javaClass.getMethod("getBatteryLevel").invoke(dev) as Int
        }.getOrDefault(-1)
        return Triple(h.name, h.kind, if (level in 0..100) level else -1)
    }

    private fun fetchBattery(h: Headset) {
        if (!hasBluetoothPermission()) {
            requestBluetooth(force = false)
            return
        }
        val addr = h.address ?: return
        val level = runCatching {
            val adapter = app.getSystemService(BluetoothManager::class.java)?.adapter ?: return
            val dev = adapter.getRemoteDevice(addr)
            dev.javaClass.getMethod("getBatteryLevel").invoke(dev) as Int
        }.getOrNull() ?: return
        if (level in 0..100) showBattery(level)
    }
}

/** Small battery ring for the headset card. */
class HeadsetBatteryView(ctx: Context) : View(ctx) {
    var level = 0
        set(v) { field = v; invalidate() }

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0x40FFFFFF
        strokeCap = Paint.Cap.ROUND
    }
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    override fun onDraw(canvas: Canvas) {
        val s = min(width, height).toFloat()
        if (s <= 0f) return
        val stroke = s * 0.14f
        track.strokeWidth = stroke
        bar.strokeWidth = stroke
        bar.color = if (level <= 20) 0xFFFF453A.toInt() else 0xFF30D158.toInt()
        val r = s / 2f - stroke
        val rect = RectF(width / 2f - r, height / 2f - r, width / 2f + r, height / 2f + r)
        canvas.drawArc(rect, 0f, 360f, false, track)
        canvas.drawArc(rect, -90f, 360f * level / 100f, false, bar)
    }
}
