package com.luncher.home

import android.accessibilityservice.AccessibilityService
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityOptions
import android.app.AlertDialog
import android.app.Dialog
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.app.SearchManager
import android.app.WallpaperManager
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.RadialGradient
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.ColorDrawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.SystemClock
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.UserManager
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings as SysSettings
import android.text.TextUtils
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.ViewOutlineProvider
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.Space
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import androidx.core.view.drawToBitmap
import androidx.core.widget.addTextChangedListener
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

class MainActivity : Activity() {

    companion object {
        private const val CELL_H = 94
        private const val PAGE_PAD = 14
        private const val MIN_PAGES = 3
        private const val KEY_HOME_PAGES = "home_page_count"
        private const val MAX_HOME_PAGES = 101
        private const val KEY_DOCK = "dock"
        private const val KEY_RECENT = "recent"
        private const val REQ_PHOTO = 4100
        private const val REQ_WIDGET_BIND = 4101
        private const val REQ_WIDGET_CONFIGURE = 4102
        private const val WIDGET_HOST_ID = 1024

        private val EASE = PathInterpolator(0.2f, 0.9f, 0.1f, 1f)
        private val SPRING = OvershootInterpolator(1.6f)
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var settings: Settings
    private lateinit var appWidgetManager: AppWidgetManager
    private lateinit var appWidgetHost: AppWidgetHost
    private var appliedVersion = -1

    private lateinit var root: FrameLayout
    private lateinit var scrim: View
    private lateinit var content: LinearLayout
    private lateinit var pager: ViewPager2
    private lateinit var dots: LinearLayout
    private lateinit var searchPill: TextView
    private lateinit var dockRow: LinearLayout
    private var dockWidthAnimator: ValueAnimator? = null
    private var dockWidthSpring: androidx.dynamicanimation.animation.SpringAnimation? = null
    private var dockTargetWidth = 0
    private var displayedDockKeys: List<String> = emptyList()
    private lateinit var defaultBanner: TextView
    private lateinit var searchOverlay: LinearLayout
    private lateinit var searchField: EditText
    private lateinit var searchHeader: TextView
    private lateinit var calcRow: TextView
    private lateinit var searchExtras: LinearLayout
    private lateinit var webRow: TextView
    private lateinit var editBar: FrameLayout
    private lateinit var gestures: GestureDetector
    private lateinit var switcher: AppSwitcher
    private lateinit var headsetPopup: HeadsetPopup
    private lateinit var island: DynamicIsland
    private lateinit var controlCenter: ControlCenter
    private var focusId: String? = null
    private val timerListener: () -> Unit = { refreshIslandTimer() }
    private var timerWasRunning = false
    private lateinit var spatialWall: SpatialImageView
    private var spatialWallUri: String? = null
    private var wasCharging: Boolean? = null

    private val searchAdapter by lazy {
        AppListAdapter(false, { a, v -> launch(a, v) }, { a, v -> showAppMenu(a, v) })
    }
    private val pagesAdapter = PagesAdapter()

    private var allApps: List<AppInfo> = emptyList()
    private var appsReady = false
    private var pages: List<Page> = emptyList()
    private var searchResults: List<AppInfo> = emptyList()

    private var batteryLevel = 0
    private var batteryCharging = false
    private var batteryRing: BatteryRingView? = null
    private var batteryText: TextView? = null

    private val photoCache = HashMap<String, Bitmap>()
    private var pendingPhotoId: String? = null

    private val badgeViews = ArrayList<Pair<String, TextView>>()

    // Now Playing
    private var mediaController: MediaController? = null
    private var mediaListening = false
    private var musicViews: MusicViews? = null

    // Motion & modes
    private var menuLayer: FrameLayout? = null
    private var currentBlur = 0f
    private var blurAnimator: ValueAnimator? = null
    private var entrancePending = true
    private var isActive = false
    private var insetTop = 0
    private var insetBottom = 0

    // Edit mode / drag
    private var editMode = false
    private val jiggles = ArrayList<ObjectAnimator>()
    private var drag: DragState? = null
    private var dragGhost: ImageView? = null
    private var activeFolderDialog: FolderDialog? = null
    private var widgetDrag: WidgetDragState? = null
    private var activeResizeHandle: Pair<View, View.OnTouchListener>? = null
    private var pendingHomeHold: Runnable? = null
    private var holdX = 0f
    private var holdY = 0f
    private var previewOrder: List<String>? = null
    /** A blank page exists only for this drag until an item is dropped on it. */
    private var temporaryDragPageCount = 0
    private var folderHoverView: View? = null
    private var pendingReorder: Runnable? = null
    private var pendingReorderKey: String? = null
    private data class ScreenPosition(val page: Int, val x: Int, val y: Int)
    private var reflowPositions: Map<String, ScreenPosition> = emptyMap()
    private var reflowRevision = 0
    private val edgeFlip = Runnable { flipPageDuringDrag() }
    private var edgeDir = 0

    private val io = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val showSearchPill = Runnable { setPillMode(search = true) }

    private val cols: Int get() = settings.columns
    /** Icon size, never bigger than a grid cell (so the 4th column always fits on screen). */
    private val iconDp: Int get() = minOf(settings.iconSize,
        (cellW() / resources.displayMetrics.density - 14f).toInt().coerceAtLeast(36))
    private val anim: Float get() = settings.animScale
    private fun dur(ms: Long): Long = (ms * anim).toLong()

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            reloadApps()
            // An app was installed or updated: the face shows its logo.
            if (i.action == Intent.ACTION_PACKAGE_ADDED || i.action == Intent.ACTION_PACKAGE_REPLACED)
                i.data?.schemeSpecificPart?.let { pkg -> mainHandler.postDelayed({ peekApp(pkg) }, 600) }
        }
    }

    // ================================================================ Gilbert's feelings
    private var lastGazeAt = 0L
    private val appMoodListener: (String) -> Unit = { pkg -> mainHandler.post {
        updateGilbertMood(pkg)
        allApps.firstOrNull { it.packageName == pkg }?.let { GilbertMemory.app(this, it.label) }
    } }

    /** What Gilbert feels about what's on screen (and the time / phone state). */
    private fun updateGilbertMood(pkg: String? = GameState.foreground) {
        if (!::island.isInitialized) return
        if (!settings.gilbertEmotions) { island.setFaceMood("neutral"); return }
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val h = runCatching { Gilbert.health(this) }.getOrNull()
        val mood = when {
            searchingNow -> "curious"
            isActive && (hour >= 23 || hour < 6) -> "sleepy"
            h != null && h.battery in 0..15 && !h.charging -> "sad"
            isActive -> if (Gilbert.heldCount() > 0) "thinking" else "neutral"
            pkg != null -> appMood(pkg)
            else -> "neutral"
        }
        island.setFaceMood(mood)
        gilbertMood = mood
    }

    /** Watching (video), listening (music), chatting (happy), browsing/searching (curious)… */
    private fun appMood(pkg: String): String {
        val p = pkg.lowercase()
        val cat = if (Build.VERSION.SDK_INT >= 26) runCatching { packageManager.getApplicationInfo(pkg, 0).category }.getOrDefault(-1) else -1
        return when {
            listOf("youtube", "netflix", "tiktok", "musically", "ugc.trill", "video", "primevideo", "disney", "hbo", "iqiyi",
                "viu", "twitch", "bilibili", "vlc", "mxtech").any { p.contains(it) } || cat == android.content.pm.ApplicationInfo.CATEGORY_VIDEO -> "watching"
            listOf("spotify", "music", "soundcloud", "deezer", "podcast").any { p.contains(it) } || cat == android.content.pm.ApplicationInfo.CATEGORY_AUDIO -> "listening"
            listOf("chrome", "browser", "firefox", "opera", "quicksearch", "googlequicksearchbox", "bing", "duckduckgo").any { p.contains(it) } -> "curious"
            listOf("messenger", "orca", "whatsapp", "telegram", "viber", "messaging", "instagram", "snapchat", "discord").any { p.contains(it) } ||
                cat == android.content.pm.ApplicationInfo.CATEGORY_SOCIAL -> "happy"
            listOf("camera", "gallery", "photos", "albums").any { p.contains(it) } || cat == android.content.pm.ApplicationInfo.CATEGORY_IMAGE -> "love"
            cat == android.content.pm.ApplicationInfo.CATEGORY_PRODUCTIVITY || cat == android.content.pm.ApplicationInfo.CATEGORY_NEWS -> "thinking"
            else -> "neutral"
        }
    }

    /** Gilbert's red bubble + the logos of held apps next to him. */
    private fun refreshHeld() {
        if (!::island.isInitialized) return
        island.setFaceBadge(Gilbert.heldCount())
        island.setHeld(Gilbert.held.entries.reversed().map { (pkg, n) ->
            Triple(allApps.firstOrNull { it.packageName == pkg }?.icon, n, pkg)
        })
    }

    // ---- Gilbert talks now and then
    private var nextTalk = System.currentTimeMillis() + 25_000
    private var gilbertMood = "neutral"
    private fun maybeTalk() {
        if (!::island.isInitialized || !settings.islandFace || !settings.gilbertTalk || GameState.playing || searchingNow) return
        val now = System.currentTimeMillis()
        if (now < nextTalk) return
        nextTalk = now + settings.gilbertTalkMin * 60_000L + (Math.random() * 90_000).toLong()
        island.say(GilbertTalk.line(this, settings, gilbertMood))
    }

    /** Tap Gilbert: his card (held notifications, noisiest apps, phone check). */
    private fun openGilbert() {
        // Over other apps: tapping Gilbert opens his chat right there.
        if (!isActive) { GilbertLauncher.open(this); return }
        Gilbert.showPanel(this, root, settings,
            appInfo = { pkg -> allApps.firstOrNull { it.packageName == pkg }?.let { it.icon to it.label } },
            openApp = { pkg ->
                Gilbert.held.remove(pkg); island.setFaceBadge(Gilbert.heldCount())
                packageManager.getLaunchIntentForPackage(pkg)?.let { runCatching { startActivity(it) } }
            },
            openInbox = { if (::sideHud.isInitialized) sideHud.openHistory(); Gilbert.clearHeld() },
            openTools = { island.toggleTools() },
            openChat = { GilbertLauncher.open(this) })
    }

    /** Pop this app's logo in the island's round face. */
    private fun peekApp(pkg: String) {
        if (!::island.isInitialized || !settings.islandFace) return
        val bmp = allApps.firstOrNull { it.packageName == pkg }?.icon ?: runCatching {
            val d = packageManager.getApplicationIcon(pkg)
            val b = android.graphics.Bitmap.createBitmap(96, 96, android.graphics.Bitmap.Config.ARGB_8888)
            val cv = android.graphics.Canvas(b); d.setBounds(0, 0, 96, 96); d.draw(cv); b
        }.getOrNull()
        island.peekIcon(bmp)
    }
    /** Zoom the home screen in when the phone is unlocked. */
    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (isActive || hasWindowFocus()) playUnlockZoom()
        }
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = updateBattery(i)
    }

    private class MusicViews(
        val art: ImageView,
        val bg: ImageView,
        val title: TextView,
        val artist: TextView,
        val source: TextView,
        val play: ImageView,
        val hint: TextView,
        val body: View
    )

    private class DragState(
        val app: AppInfo,
        val fromDock: Boolean,
        val source: View,
        val floating: ImageView,
        val size: Int,
        val originFolderId: String? = null
    )

    private class WidgetDragState(
        val widget: PlacedWidget,
        val page: Int,
        val source: View,
        val floating: ImageView,
        val width: Int,
        val height: Int,
        val grabX: Float,
        val grabY: Float
    )

    private data class HomeFolder(val id: String, val name: String, val keys: List<String>)

    // ================================================================ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashLog.install(this)
        HighRefresh.apply(this)   // fastest refresh rate the screen has (90/120 Hz…)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        prefs = getSharedPreferences("luncher", MODE_PRIVATE)
        settings = Settings(this)
        appWidgetManager = AppWidgetManager.getInstance(this)
        appWidgetHost = AppWidgetHost(this, WIDGET_HOST_ID)
        applyStyleGlobals()
        Motion.scale = settings.animScale
        useHighestRefreshRate()
        buildUi()
        setContentView(root)
        ContextCompat.registerReceiver(
            this, unlockReceiver, IntentFilter(Intent.ACTION_USER_PRESENT), ContextCompat.RECEIVER_EXPORTED
        )
        headsetPopup = HeadsetPopup.get(this)
        headsetPopup.attach(this, root)
        island = DynamicIsland(this, root, { openAssistant() }, { showVolumePanel() }, { settings.haptics })
        controlCenter = ControlCenter(this, root,
            music = { currentMusic() },
            musicPlaying = { mediaController?.playbackState?.state == PlaybackState.STATE_PLAYING },
            playPause = { togglePlay() },
            next = { mediaController?.transportControls?.skipToNext() },
            prev = { mediaController?.transportControls?.skipToPrevious() },
            focusLabel = { Focus.current(this)?.name ?: "Off" },
            onFocus = { showFocusSheet() },
            onTimer = { showTimerPicker(null) },
            onLock = { lockScreen() },
            onBlur = { on -> animateBlur(if (on) dp(26).toFloat() else 0f, dur(260)) },
            haptics = { settings.haptics })
        controlCenter.media = { currentIslandMedia() }
        controlCenter.mediaTitle = {
            mediaController?.metadata?.let { m ->
                (m.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "Now Playing") to (m.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: "")
            }
        }
        controlCenter.musicApp = { mediaController?.packageName?.let { packageManager.getLaunchIntentForPackage(it) } }
        controlCenter.onVolumePanel = { showVolumePanel() }
        controlCenter.tileStyle = settings.ccStyle
        applyIslandLook()
        island.onMoved = { _, dy ->
            // Remember where you dragged it (saved in dp so it fits any screen density).
            val d = resources.displayMetrics.density
            settings.islandX = (island.offsetX / d).roundToInt()
            settings.islandNudge = (settings.islandNudge + dy / d).roundToInt().coerceIn(-20, 400)
            appliedVersion = settings.version
            ViewCompat.requestApplyInsets(root)
        }
        // Re-colour everything when the wallpaper changes.
        ThemeEngine.watch(this) {
            applyStyleGlobals(); restylePersistent(); applyIslandLook()
            // Gilbert's colours follow the new wallpaper too.
            if (::island.isInitialized) island.configureFace(settings)
            overlayIsland?.configureFace(settings)
            rebuild()
        }
        TimerState.load(this)
        TimerState.add(timerListener)
        focusId = Focus.current(this)?.id
        island.enabled = settings.island
        island.popupMs = settings.islandPopupMs.toLong()
        island.alwaysShow = settings.islandAlways
        island.faceOn = settings.islandFace
        island.configureFace(settings)
        island.onFaceTap = { openGilbert() }
        island.beatsOn = settings.islandBeats
        island.onBeatsToggle = { on ->
            settings.islandBeats = on; overlayIsland?.beatsOn = on
            if (on && !Beats.allowed(this)) runCatching { requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 7710) }
        }
        Gilbert.onHeldChanged = { mainHandler.post { refreshHeld() } }
        island.onHeldTap = { pkg ->
            // Tap a logo beside Gilbert: what that app sent.
            if (::sideHud.isInitialized) sideHud.openHistoryFor(pkg)
            Gilbert.held.remove(pkg); refreshHeld()
        }
        // The tools live in the island: hidden around the camera, tap it to open them.
        island.idleTools = if (settings.islandTools) ({ idleTools() }) else null
        island.quickActions = null // only one tools bar (ToolsBar) — none inside the island
        island.allow = { id ->
            settings.islandAllows(when {
                id == "timerdone" -> "timer"
                id.startsWith("ongoing:") -> id.split(":")[1]
                id == "mic" || id == "camera" -> "privacy"
                else -> id
            })
        }
        loadSpatialWallpaper()
        ViewCompat.requestApplyInsets(root)

        switcher = AppSwitcher(
            this, root,
            onOpen = { app, v -> switcher.hide(0f); launch(app, v) },
            onRemove = { app -> dismissFromSwitcher(app) },
            onClosed = { animateBlur(0f, dur(220)); restoreHome(true) }
        )

        gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (e1 == null || overlayOpen() || editMode || drag != null) return false
                if (::island.isInitialized && island.hit(e1.rawX, e1.rawY)) return false
                if (SmartStackView.touching) return false
                if (pages.getOrNull(pager.currentItem) is Page.Library) return false
                val dy = e2.y - e1.y
                val dx = e2.x - e1.x
                if (abs(dy) < abs(dx) * 1.5f) return false
                if (dy > dp(70) && velocityY > 700) {
                    onSwipeDown(); return true
                }
                if (dy < -dp(80) && velocityY < -700) {
                    if (settings.swipeUp == "library") {
                        onSwipeUp(); return true
                    }
                    return false
                }
                return false
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (!settings.doubleTapLock || overlayOpen() || editMode) return false
                lockScreen()
                return true
            }
        })

        NotificationService.onChange = { updateBadges() }
        NotificationService.onPosted = { pkg, title, text ->
            // Gilbert sees everything: counts it, pops the app's logo, and decides whether it may
            // pop up now (priority apps / within the limit) or waits quietly with him.
            Gilbert.record(this, pkg)
            Gilbert.note(this, pkg, title, text)
            // Gaming: the game ball decides (Everyone / Priority / No one) and shows a small banner beside it.
            val ballNow = GameBall.instance
            val gameHandled = GameState.playing && ballNow != null && pkg != GameState.foreground
            if (gameHandled) ballNow!!.onNotification(pkg, title, text, allApps.firstOrNull { it.packageName == pkg }?.icon)
            // You're already in that app: it can see its own messages, so nothing pops up for it.
            // Other apps keep popping up as usual.
            val inThatApp = (settings.gilbertQuietInApp && !isActive && GameState.foreground == pkg) ||
                settings.notifStyle == "original"   // Android's own banners only
            if (!inThatApp && !gameHandled) {
                if (pkg !in settings.gilbertQuiet) peekApp(pkg)
                val isMessage = NotificationService.lastChat[pkg] != null && title.isNotBlank()
                if (Gilbert.shouldPop(this, settings, pkg, isMessage)) showNotificationInIsland(pkg, title, text)
            }
        }
        NotificationService.onOngoing = { refreshOngoing() }
        startPrivacyWatch()
        sideHud = SideHud(this, settings)
        sideHud.onPlayPause = { togglePlay() }
        sideHud.onMoved = { setupSidebar() }
        NotificationService.loadHistory(this)
        // Recently used apps for the side island's extension (tap to switch apps).
        sideHud.deviceProvider = { runCatching { HeadsetPopup.get(this).connectedNow() }.getOrNull() }
        sideHud.recentProvider = {
            switcherEntries().map { e ->
                val app = e.app
                app.icon to {
                    runCatching {
                        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(app.component)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
                    }
                    Unit
                }
            }
        }
        sideHud.historyProvider = { NotificationService.chatHistory.values.toList() }
        sideHud.appLookup = { pkg -> allApps.firstOrNull { it.packageName == pkg }?.let { it.icon to it.label } }
        sideHud.openChat = { c ->
            val pi = c.open
            if (pi != null) IslandServices.send(this, pi)
            else packageManager.getLaunchIntentForPackage(c.pkg)?.let { runCatching { startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
        }
        root.post { runCatching { sideHud.rest() } }
        setupSidebar()
        GameState.listen(gameListener)
        GameState.appListeners += appMoodListener
        root.postDelayed({ setupTapZone() }, 600)
        InfoData.listen(infoListener)
        mainHandler.postDelayed(infoTicker, 1500)
        runCatching { getSystemService(android.hardware.camera2.CameraManager::class.java).registerTorchCallback(torchCallback, mainHandler) }
        ContextCompat.registerReceiver(this, ringerReceiver, IntentFilter(android.media.AudioManager.RINGER_MODE_CHANGED_ACTION), ContextCompat.RECEIVER_EXPORTED)
        // "hello" only when the launcher is set up fresh: first install, or after a reset / imported setup.
        // (Anyone who already saw it in an earlier version counts as set up.)
        // Also once after every update: "hello", then What's New with everything that changed.
        val cur = WhatsNew.current(this)
        val seen = prefs.getInt("seen_launcher_version", -1)
        val fresh = !prefs.getBoolean("hello_done", false) && !prefs.contains("hello_seen_version")
        if (fresh || (cur > 0 && cur != seen)) {
            prefs.edit().putBoolean("hello_done", true).putInt("seen_launcher_version", cur).apply()
            val from = if (seen in 1 until cur) seen else cur - 1
            // hello → a short tour of the features → what changed in this update
            root.postDelayed({ showHello { WhatsNew.showTour(this, root) { if (cur > 0) WhatsNew.show(this, root, from, cur) } } }, 300)
        }
        registerReceivers()
        appliedVersion = settings.version
        reloadApps()
    }

    override fun onStart() {
        super.onStart()
        runCatching { appWidgetHost.startListening() }
    }

    override fun onStop() {
        runCatching { appWidgetHost.stopListening() }
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        GameState.leftApps()   // back on the Home Screen: no game on screen
        if (::pager.isInitialized) pager.post { runCatching { pager.requestTransform() } }
        mainHandler.post { updateGilbertMood() }
        if (::island.isInitialized) root.post { setupTapZone() }
        // "Show hello again" from Settings › General.
        if (prefs.getBoolean("hello_force", false)) {
            prefs.edit().remove("hello_force").apply()
            root.postDelayed({ showHello() }, 250)
        }
        Tilt.resume()
        if (Focus.applySchedule(this) || Focus.current(this)?.id != focusId) applyFocusState(announce = true)
        isActive = true
        runCatching { VoiceService.sync(this) }
        if (::sideHud.isInitialized) runCatching { sideHud.rest() }
        if (::island.isInitialized) detachOverlayIsland()
        refreshOngoing()
        if (::headsetPopup.isInitialized) headsetPopup.hostResumed = true
        defaultBanner.visibility = if (isDefaultHome()) View.GONE else View.VISIBLE
        if (settings.version != appliedVersion) applySettings()
        if (prefs.getBoolean("s_apply_wallpaper", false)) {
            prefs.edit().putBoolean("s_apply_wallpaper", false).apply()
            applyGlassWallpaper()
        }
        if (settings.pendingEdit) {
            settings.pendingEdit = false
            mainHandler.postDelayed({ enterEditMode() }, 250)
        }
        setupMedia()
        updateBadges()
        if (content.alpha < 1f && !switcher.isShowing) restoreHome(animated = true)
    }

    override fun onPause() {
        Tilt.pause()
        isActive = false
        // Wait a moment: if you're only opening the launcher's own Settings, stay off.
        mainHandler.postDelayed({
            if (!isActive && !SettingsActivity.visible && !isFinishing) runCatching { attachOverlayIsland() }
        }, 450)
        if (::headsetPopup.isInitialized) headsetPopup.hostResumed = false
        activeFolderDialog?.let { folder ->
            if (folder.draggingMember) {
                folder.draggingMember = false
                folder.dismiss()
            }
        }
        if (activeResizeHandle != null) {
            activeResizeHandle = null
            refreshPages()
        }
        if (drag != null) cancelDrag()
        clearDragGhost()
        if (widgetDrag != null) cancelWidgetDrag()
        cancelHomeHold()
        super.onPause()
    }

    override fun onRestart() {
        super.onRestart()
        if (anim > 0f) when (launchStyle()) {
            "zoom" -> { content.scaleX = 1.12f; content.scaleY = 1.12f; content.alpha = 0f }
            "fade" -> content.alpha = 0f
            "slide" -> { content.alpha = 0f; content.translationY = dp(36).toFloat() }
        }
        restoreHome(animated = true)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        goHome()
    }

    @Deprecated("Launcher handles back itself")
    override fun onBackPressed() { Gilbert.panelClose?.let { it(); return }; goHome() }

    private val holo by lazy {
        HoloSettings(this, root) { (0 until root.childCount).map { root.getChildAt(it) } }
    }

    private fun goHome() {
        when {
            Gilbert.isPanelOpen() -> Gilbert.panelClose?.invoke()
            holo.isOpen -> holo.close()
            drag != null -> cancelDrag()
            widgetDrag != null -> cancelWidgetDrag()
            menuLayer != null -> dismissMenu()
            switcher.isShowing -> switcher.hide(anim)
            ::controlCenter.isInitialized && controlCenter.isShowing -> controlCenter.hide()
            searchOverlay.visibility == View.VISIBLE -> closeSearch()
            editMode -> exitEditMode()
            pager.currentItem != homePageIndex() -> pager.currentItem = homePageIndex()
        }
    }

    /** The page Home returns to: page 1, or the page chosen for the current Focus. */
    private fun homePageIndex(): Int =
        focusId?.let { Focus.homePage(this, it) }?.coerceIn(0, (homePageCount() - 1).coerceAtLeast(0)) ?: 0

    private fun overlayOpen() =
        menuLayer != null || switcher.isShowing || searchOverlay.visibility == View.VISIBLE ||
            (::controlCenter.isInitialized && controlCenter.isShowing)

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // Gilbert's eyes follow your finger on the Home Screen.
        if (settings.gilbertEmotions && ::island.isInitialized && (ev.actionMasked == MotionEvent.ACTION_DOWN ||
                (ev.actionMasked == MotionEvent.ACTION_MOVE && ev.eventTime - lastGazeAt > 120))) {
            lastGazeAt = ev.eventTime
            val w = root.width.coerceAtLeast(1).toFloat(); val h = root.height.coerceAtLeast(1).toFloat()
            island.faceLookAt((ev.rawX - w / 2) / (w / 2), (ev.rawY / h) * 1.6f - 0.2f)
        }
        // A new touch means an earlier drag lost its UP/CANCEL event; never leave its image stuck.
        if (ev.actionMasked == MotionEvent.ACTION_DOWN && (drag != null || widgetDrag != null)) {
            if (drag != null) cancelDrag() else cancelWidgetDrag()
        } else if (ev.actionMasked == MotionEvent.ACTION_DOWN && dragGhost != null) {
            clearDragGhost()
        }
        // Route the small resize handle before the page and app drag gestures see it.
        if (editMode && ev.actionMasked == MotionEvent.ACTION_DOWN) {
            activeResizeHandle = resizeHandleAt(ev.rawX, ev.rawY)
            if (activeResizeHandle != null) cancelHomeHold()
        }
        activeResizeHandle?.let { (view, listener) ->
            listener.onTouch(view, ev)
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL)
                activeResizeHandle = null
            return true
        }
        if (drag != null || widgetDrag != null) {
            when (ev.actionMasked) {
                MotionEvent.ACTION_MOVE -> if (drag != null) moveDrag(ev.rawX, ev.rawY)
                    else moveWidgetDrag(ev.rawX, ev.rawY)
                MotionEvent.ACTION_UP -> if (drag != null) endDrag(ev.rawX, ev.rawY)
                    else endWidgetDrag(ev.rawX, ev.rawY)
                MotionEvent.ACTION_CANCEL -> if (drag != null) cancelDrag() else cancelWidgetDrag()
            }
            return true
        }
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> scheduleHomeHold(ev.rawX, ev.rawY)
            MotionEvent.ACTION_MOVE -> if (hypot(ev.rawX - holdX, ev.rawY - holdY) > dp(10)) cancelHomeHold()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> cancelHomeHold()
        }
        if (::gestures.isInitialized) gestures.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    // ================================================================ island info (weather, steps, date…)
    private val infoIcons = HashMap<String, android.graphics.Bitmap>()
    private var infoCycle = 0
    private val infoListener: () -> Unit = { updateInfoIsland(advance = false) }
    private val infoTicker = object : Runnable {
        override fun run() { updateInfoIsland(advance = true); updateGilbertMood(); maybeTalk(); mainHandler.postDelayed(this, 10_000) }
    }
    private fun infoIcon(key: String, e: String, bg: Int = 0) = infoIcons.getOrPut(key) { InfoViews.emoji(this, e, bg) }

    private fun updateInfoIsland(advance: Boolean) {
        if (!::island.isInitialized) return
        val mode = settings.islandInfo
        if (mode == "off" || !settings.island) { island.remove("info"); return }
        InfoData.startSteps(this)
        InfoData.refreshWeather(this)
        val kinds = listOf("weather", "date", "steps", "events", "calories")
        var a: IslandActivity? = null
        if (mode == "cycle") {
            if (advance) infoCycle++
            for (t in 0 until kinds.size) { a = infoActivity(kinds[(infoCycle + t) % kinds.size]); if (a != null) break }
        } else a = infoActivity(mode)
        if (a == null) island.remove("info") else island.put(a)
    }

    private fun infoActivity(kind: String): IslandActivity? = when (kind) {
        "weather" -> InfoData.loadWeather(this)?.let { w ->
            val (e, cond) = InfoData.condition(w.code)
            IslandActivity("info", infoIcon("w$e", e), 0, 0, "${w.temp}°", "$cond · feels like ${w.feels}°",
                "${w.hi}/${w.lo}", false, 0xFFFF9F68.toInt(), custom = { InfoViews.weatherGauge(it, InfoData.weather) })
        }
        "date" -> {
            val cal = java.util.Calendar.getInstance()
            val mon = java.text.SimpleDateFormat("MMM", java.util.Locale.getDefault()).format(cal.time).uppercase()
            IslandActivity("info", infoIcon("cal", "📅"), 0, 0, mon, "Today", "${cal.get(java.util.Calendar.DAY_OF_MONTH)}",
                false, 0xFFFF453A.toInt(), custom = { InfoViews.monthCard(it, InfoData.eventsToday(this)) })
        }
        "steps" -> if (InfoData.stepsToday < 0) null else IslandActivity("info", infoIcon("steps", "🚶", 0xFF30D158.toInt()), 0, 0, "",
            "Steps today", String.format(java.util.Locale.getDefault(), "%,d", InfoData.stepsToday), false, 0xFF30D158.toInt(),
            custom = { InfoViews.stepsCard(it) })
        "events" -> InfoData.eventsToday(this).takeIf { it >= 0 }?.let { n ->
            IslandActivity("info", infoIcon("ev", "🗓️"), 0, 0, "Events", "Today", "$n", false, Color.WHITE,
                custom = { InfoViews.monthCard(it, n) })
        }
        "calories" -> if (InfoData.stepsToday < 0) null else IslandActivity("info", infoIcon("cal2", "🔥", 0xFFFF9500.toInt()), 0, 0, "kcal",
            "Burned today", String.format(java.util.Locale.getDefault(), "%.1f", InfoData.calories()), false, 0xFFFF9F0A.toInt(),
            custom = { InfoViews.stepsCard(it) })
        else -> null
    }

    /** The tap strip at the camera (above the status bar) that opens the island tools. */
    private var tapZone: CameraTapZone? = null
    private var searchingNow = false
    private fun setupTapZone() {
        if (!settings.islandTools) { tapZone?.detach(); tapZone = null; return }
        val z = tapZone ?: CameraTapZone(this, settings) {
            // Open the tools on whichever island is on screen (Home Screen, or over an app).
            if (isActive && ::island.isInitialized) island.toggleTools()
            else overlayIsland?.toggleTools() ?: island.toggleTools()
        }.also { tapZone = it }
        if (::island.isInitialized) z.camera = island.camera
        z.attach()
        z.setHidden(searchingNow || GameState.playing)
    }

    /** A game came on screen (or left): hide / bring back the islands and pop-ups. */
    private val gameListener: () -> Unit = { mainHandler.post { applyGameState() } }
    private fun applyGameState() {
        val g = GameState.playing
        overlayRoot?.visibility = if (g) View.GONE else View.VISIBLE
        tapZone?.setHidden(g || searchingNow)
        if (::sideHud.isInitialized) runCatching { if (g) sideHud.dismiss() else sideHud.rest() }
    }

    override fun onDestroy() {
        GameState.unlisten(gameListener)
        GameState.appListeners -= appMoodListener
        tapZone?.detach(); tapZone = null
        InfoData.unlisten(infoListener)
        mainHandler.removeCallbacks(infoTicker)
        cancelHomeHold()
        dockWidthAnimator?.cancel()
        dockWidthAnimator = null
        dockWidthSpring?.cancel()
        dockWidthSpring = null
        runCatching { unregisterReceiver(unlockReceiver) }
        if (::headsetPopup.isInitialized) headsetPopup.detach(this)
        NotificationService.onChange = null
        NotificationService.onPosted = null
        NotificationService.onOngoing = null
        sidebar?.detach()
        if (::sideHud.isInitialized) sideHud.dismiss()
        runCatching { unregisterReceiver(ringerReceiver) }
        runCatching { getSystemService(android.hardware.camera2.CameraManager::class.java).unregisterTorchCallback(torchCallback) }
        if (::island.isInitialized) detachOverlayIsland()
        ThemeEngine.unwatch(this)
        stopPrivacyWatch()
        mainHandler.removeCallbacks(ongoingTicker)
        TimerState.remove(timerListener)
        runCatching { unregisterReceiver(packageReceiver) }
        runCatching { unregisterReceiver(batteryReceiver) }
        if (mediaListening) {
            runCatching {
                getSystemService(MediaSessionManager::class.java).removeOnActiveSessionsChangedListener(sessionsListener)
            }
        }
        mediaController?.unregisterCallback(mediaCallback)
        stopJiggle()
        io.shutdownNow()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == HeadsetPopup.REQ_BLUETOOTH && ::headsetPopup.isInitialized) {
            headsetPopup.onPermissionResult()
        }
    }

    @Deprecated("Uses the classic result API, which is fine for a plain Activity")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_WIDGET_BIND || requestCode == REQ_WIDGET_CONFIGURE) {
            val saved = prefs.getInt("pending_widget_id", AppWidgetManager.INVALID_APPWIDGET_ID)
            val returned = data?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, saved) ?: saved
            if (resultCode != RESULT_OK || saved == AppWidgetManager.INVALID_APPWIDGET_ID || returned != saved) {
                cancelPendingAppWidget(saved)
            } else if (requestCode == REQ_WIDGET_BIND) {
                configureOrAddAppWidget(saved)
            } else {
                finishAddingAppWidget(saved)
            }
            return
        }
        if (requestCode != REQ_PHOTO) return
        val id = pendingPhotoId ?: return
        pendingPhotoId = null
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        prefs.edit().putString("photo_$id", uri.toString()).apply()
        photoCache.remove(id)
        refreshPages()
    }

    // ================================================================ settings

    private fun applyStyleGlobals() {
        Glass.glass = settings.glass
        IconFactory.CORNER = settings.iconCorner / 100f
        Lyrics.enabled = settings.lyrics
        Lyrics.LEAD_MS = settings.lyricsLead.toLong()
        // Theme: colours follow the wallpaper (or system / your pick) unless set to Mono.
        ThemeEngine.resolve(this, settings)
        if (ThemeEngine.colorful) {
            Glass.tint = ThemeEngine.accent
            Glass.tintPanels = settings.themeSurfaces || settings.tintPanels
        } else {
            Glass.tint = settings.tint
            Glass.tintPanels = settings.tintPanels
        }
        // Liquid Glass slider: 0 = frosted (more opaque), 100 = crystal clear.
        Glass.opacity = 1.6f - settings.glassClarity / 100f * 1.2f
        IconFactory.style = settings.iconStyle
        IconFactory.tint = settings.tint
    }

    /** Called when something changed in Settings: restyle everything and re-render icons. */
    /** Run at the screen's fastest refresh rate (90/120 Hz) so motion stays buttery. */
    private fun useHighestRefreshRate() {
        runCatching {
            @Suppress("DEPRECATION")
            val disp: android.view.Display = (if (Build.VERSION.SDK_INT >= 30) this@MainActivity.display
                else windowManager.defaultDisplay) ?: return
            val current = disp.mode
            val best = disp.supportedModes
                .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
                .maxByOrNull { it.refreshRate } ?: return
            val attrs = window.attributes
            attrs.preferredDisplayModeId = best.modeId
            window.attributes = attrs
        }
    }

    private fun applySettings() {
        Motion.scale = settings.animScale
        if (::island.isInitialized) {
            island.enabled = settings.island
            island.popupMs = settings.islandPopupMs.toLong()
            island.alwaysShow = settings.islandAlways
            island.faceOn = settings.islandFace
            overlayIsland?.faceOn = settings.islandFace
            island.configureFace(settings)
            island.idleTools = if (settings.islandTools) ({ idleTools() }) else null
            root.post { setupTapZone() }
            overlayIsland?.idleTools = island.idleTools
            applyIslandLook()
            island.applyFilter()
            ViewCompat.requestApplyInsets(root)
        }
        if (::controlCenter.isInitialized) controlCenter.tileStyle = settings.ccStyle
        setupSidebar()
        if (::sideHud.isInitialized) runCatching { sideHud.rest() }
        loadSpatialWallpaper()
        appliedVersion = settings.version
        applyStyleGlobals()
        restylePersistent()
        applyPageEffect()
        if (currentBlur > 0f && !settings.blur) animateBlur(0f, 1)
        reloadApps()
    }

    // ================================================================ Island over other apps

    private var lastIslandTop = 0
    private var overlayIsland: DynamicIsland? = null
    private var overlayRoot: FrameLayout? = null
    private var overlayWm: android.view.WindowManager? = null
    private var overlayLp: android.view.WindowManager.LayoutParams? = null

    /** When you leave the Home Screen, a copy of the island floats over the app you open. */
    private fun attachOverlayIsland() {
        if (overlayIsland != null || !::island.isInitialized) return
        if (!settings.island || !settings.islandOverApps) return
        val svc = LuncherAccessibility.instance
        val type = when {
            svc != null -> android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            android.provider.Settings.canDrawOverlays(this) -> android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else -> return
        }
        val wm = (svc ?: this).getSystemService(android.view.WindowManager::class.java) ?: return
        val holder = FrameLayout(this).apply {
            clipChildren = false; clipToPadding = false
            setPadding(dp(30), dp(4), dp(30), dp(18))
        }
        val ov = DynamicIsland(this, holder, { openAssistant() }, {
            // Long-press over an app: the phone's own volume slider.
            runCatching {
                getSystemService(android.media.AudioManager::class.java).adjustStreamVolume(
                    android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.ADJUST_SAME,
                    android.media.AudioManager.FLAG_SHOW_UI)
            }
        }, { settings.haptics })
        ov.overlayMode = true
        ov.alwaysShow = island.alwaysShow
        ov.quickActions = island.quickActions
        ov.idleTools = island.idleTools
        ov.faceOn = island.faceOn
        ov.configureFace(settings)
        ov.beatsOn = settings.islandBeats
        ov.onBeatsToggle = { on -> settings.islandBeats = on; island.beatsOn = on }
        // Over other apps, tapping Gilbert opens his inbox (Notifications history).
        ov.onFaceTap = { if (::sideHud.isInitialized) sideHud.openHistory() }
        ov.camera = island.camera
        applyToolsStyle(ov)
        ov.allow = island.allow
        ov.popupMs = island.popupMs
        ov.sizeScale = island.sizeScale; ov.style = island.style; ov.anim = island.anim
        ov.offsetX = 0f
        ov.applyLook()
        ov.setTopInset(0)
        val lp = android.view.WindowManager.LayoutParams(
            WRAP_CONTENT, WRAP_CONTENT, type,
            android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                android.view.WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            HighRefresh.apply(this@MainActivity, this)
            x = dp(settings.islandX + settings.islandAppsDx)
            y = (lastIslandTop - dp(4) + dp(settings.islandAppsDy)).coerceAtLeast(0)
            if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode =
                android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        // Only take touches while something is showing; tapping outside folds it back.
        ov.onShownChanged = { shown ->
            val l = overlayLp
            if (l != null && overlayRoot?.isAttachedToWindow == true) {
                l.flags = if (shown) l.flags and android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                    else l.flags or android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                runCatching { overlayWm?.updateViewLayout(overlayRoot, l) }
            }
        }
        holder.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) ov.collapse()
            false
        }
        // Whenever the island changes size (long lyric, opening the player…), keep it fully on screen.
        holder.addOnLayoutChangeListener { _, l, _, r, _, ol, _, or, _ ->
            if (r - l != or - ol) holder.post { keepOverlayOnScreen() }
        }
        // Hold the island over an app and drag it anywhere so it doesn't cover the app's buttons.
        var startX = 0; var startY = 0; var started = false
        ov.onWindowDrag = { dx, dy, done ->
            val l = overlayLp
            if (l != null) {
                if (!started) { startX = l.x; startY = l.y; started = true }
                val m = resources.displayMetrics
                l.x = (startX + dx.toInt()).coerceIn(-m.widthPixels / 2 + dp(40), m.widthPixels / 2 - dp(40))
                l.y = (startY + dy.toInt()).coerceIn(0, m.heightPixels - dp(80))
                runCatching { overlayWm?.updateViewLayout(overlayRoot, l) }
                if (done) {
                    started = false
                    keepOverlayOnScreen()
                    settings.islandAppsDx = (l.x / m.density).roundToInt() - settings.islandX
                    settings.islandAppsDy = ((l.y - (lastIslandTop - dp(4)).coerceAtLeast(0)) / m.density).roundToInt()
                }
            }
        }
        if (runCatching { wm.addView(holder, lp) }.isFailure) return
        overlayIsland = ov; overlayRoot = holder; overlayWm = wm; overlayLp = lp
        if (GameState.playing) holder.visibility = View.GONE
        island.mirror = ov
        for (a in island.snapshot()) ov.put(a)
        // It may already be showing (Gilbert, music…): make sure it takes taps over other apps right away.
        holder.post { ov.onShownChanged?.invoke(ov.shownNow()) }
    }

    private var overlaySlide: android.animation.ValueAnimator? = null

    /**
     * Over apps: if the island pokes past the side of the screen (after you drag it, or when it grows
     * for a long lyric), glide it back in so the whole island — and every word — stays visible.
     */
    private fun keepOverlayOnScreen() {
        val l = overlayLp ?: return
        val r = overlayRoot ?: return
        if (!r.isAttachedToWindow || r.width <= 0) return
        val m = resources.displayMetrics
        // The window has 30dp of invisible padding on each side around the island itself.
        val visibleW = r.width - r.paddingLeft - r.paddingRight
        val maxX = ((m.widthPixels - visibleW) / 2 - dp(6)).coerceAtLeast(0)
        val target = l.x.coerceIn(-maxX, maxX)
        val targetY = l.y.coerceIn(0, (m.heightPixels - r.height - dp(24)).coerceAtLeast(0))
        if (target == l.x && targetY == l.y) return
        overlaySlide?.cancel()
        val fx = l.x; val fy = l.y
        overlaySlide = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 260; interpolator = android.view.animation.DecelerateInterpolator(2f)
            addUpdateListener {
                val t = it.animatedValue as Float
                l.x = (fx + (target - fx) * t).roundToInt(); l.y = (fy + (targetY - fy) * t).roundToInt()
                runCatching { overlayWm?.updateViewLayout(r, l) }
            }
            start()
        }
    }

    private fun detachOverlayIsland() {
        island.mirror = null
        val r = overlayRoot
        if (r != null) runCatching { overlayWm?.removeViewImmediate(r) }
        overlayIsland = null; overlayRoot = null; overlayWm = null; overlayLp = null
    }

    // ================================================================ Side island, quick controls, sidebar, hello

    private lateinit var sideHud: SideHud
    private var sidebar: Sidebar? = null
    private var torchOn = false
    private var helloView: HelloView? = null

    private fun openMusicApp() {
        val i = mediaController?.packageName?.let { packageManager.getLaunchIntentForPackage(it) }
            ?: Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MUSIC)
        runCatching { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.onFailure { toast("No music app found") }
    }

    /** Volume up/down by one step, shown as the side island. */
    private fun stepVolume(step: Int) {
        val am = getSystemService(android.media.AudioManager::class.java)
        am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC,
            if (step > 0) android.media.AudioManager.ADJUST_RAISE else android.media.AudioManager.ADJUST_LOWER,
            if (settings.sideHud && settings.sideShowVolume) 0 else android.media.AudioManager.FLAG_SHOW_UI)
        if (settings.sideHud && settings.sideShowVolume && ::sideHud.isInitialized) {
            val max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            sideHud.volume(am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) / max.toFloat())
        }
    }

    // On the Home Screen the volume buttons show the side island instead of Android's slider.
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if (settings.sideHud && settings.sideShowVolume && (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP || keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN)) {
            stepVolume(if (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP) 1 else -1)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private val ringerReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (!settings.sideHud || !settings.sideShowRinger || !::sideHud.isInitialized) return
            when (getSystemService(android.media.AudioManager::class.java).ringerMode) {
                android.media.AudioManager.RINGER_MODE_NORMAL ->
                    sideHud.status(listOf(R.drawable.ic_cc_bell to Color.WHITE), "RING")
                android.media.AudioManager.RINGER_MODE_VIBRATE ->
                    sideHud.status(listOf(R.drawable.ic_cc_bell_off to 0xFFFF453A.toInt(), R.drawable.ic_cc_bell to 0xFF8E8E93.toInt()), "SILENT")
                else -> sideHud.status(listOf(R.drawable.ic_cc_bell_off to 0xFFFF453A.toInt()), "SILENT")
            }
        }
    }

    private val torchCallback = object : android.hardware.camera2.CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) { torchOn = enabled }
    }

    private fun setTorch(on: Boolean) {
        val cm = getSystemService(android.hardware.camera2.CameraManager::class.java) ?: return
        runCatching {
            val id = cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it).get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return
            cm.setTorchMode(id, on)
            torchOn = on
        }
    }

    /** The oblong island's tools when nothing is playing. */
    private fun idleTools(): List<QuickAction> {
        val am = getSystemService(android.media.AudioManager::class.java)
        val list = ArrayList<QuickAction>()
        list += QuickAction(R.drawable.ic_cc_flashlight, "Flashlight", { torchOn }) { setTorch(!torchOn) }
        list += QuickAction(R.drawable.ic_cc_sun, "Brightness", { false }) { cycleBrightness() }
        list += QuickAction(R.drawable.ic_cc_camera, "Camera", { false }) {
            val ok = runCatching { startActivity(Intent(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)) }.isSuccess ||
                runCatching { startActivity(Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE)) }.isSuccess
            if (!ok) toast("No camera app found")
        }
        list += QuickAction(R.drawable.ic_cc_bell_off, "Silent", { am.ringerMode != android.media.AudioManager.RINGER_MODE_NORMAL }) {
            runCatching {
                am.ringerMode = if (am.ringerMode == android.media.AudioManager.RINGER_MODE_NORMAL)
                    android.media.AudioManager.RINGER_MODE_VIBRATE else android.media.AudioManager.RINGER_MODE_NORMAL
            }
        }
        list += QuickAction(R.drawable.ic_cc_game, "Game Mode", { settings.gameMode }) { toggleGameMode() }
        list += QuickAction(R.drawable.ic_cc_lock, "Lock Screen", { false }) {
            lockScreen()
        }
        list += QuickAction(R.drawable.ic_set_gear, "Settings", { false }) {
            // The launcher's own settings (classic design).
            root.postDelayed({ runCatching { startActivity(Intent(this, SettingsActivity::class.java)) } }, 180)
        }
        return list
    }

    /** Tap: 25 → 50 → 75 → 100 % → 25 %… (needs "Modify system settings"). */
    private fun cycleBrightness() {
        if (!android.provider.Settings.System.canWrite(this)) {
            toast("Allow “Modify system settings” to change brightness")
            runCatching { startActivity(Intent(android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName"))) }
            return
        }
        runCatching {
            val cr = contentResolver
            android.provider.Settings.System.putInt(cr, android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE,
                android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            val cur = android.provider.Settings.System.getInt(cr, android.provider.Settings.System.SCREEN_BRIGHTNESS, 128)
            val steps = listOf(64, 128, 191, 255)
            val next = steps.firstOrNull { it > cur + 8 } ?: steps.first()
            android.provider.Settings.System.putInt(cr, android.provider.Settings.System.SCREEN_BRIGHTNESS, next)
            toast("Brightness ${(next * 100f / 255).roundToInt()}%")
        }
    }

    /** Game Mode: Do Not Disturb while you play (tap again to turn it off). */
    private fun toggleGameMode() {
        // Used over an app the launcher didn't know was a game: remember it as one.
        val fg = GameState.foreground
        if (!isActive && fg != null && !GameState.playing) { GameState.setGame(this, fg, true); toast("🎮 Marked as a game"); return }
        val nm = getSystemService(android.app.NotificationManager::class.java)
        if (!nm.isNotificationPolicyAccessGranted) {
            toast("Allow Do Not Disturb access for Game Mode")
            runCatching { startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) }
            return
        }
        val on = !settings.gameMode
        settings.gameMode = on
        runCatching {
            nm.setInterruptionFilter(if (on) android.app.NotificationManager.INTERRUPTION_FILTER_PRIORITY
                else android.app.NotificationManager.INTERRUPTION_FILTER_ALL)
        }
        toast(if (on) "🎮 Game Mode on — notifications silenced" else "Game Mode off")
    }

    /** Pull-down island controls. */
    private fun quickActions(): List<QuickAction> {
        val am = getSystemService(android.media.AudioManager::class.java)
        val list = ArrayList<QuickAction>()
        list += QuickAction(R.drawable.ic_cc_flashlight, "Flashlight", { torchOn }) { setTorch(!torchOn) }
        list += QuickAction(R.drawable.ic_cc_moon, "Focus", { focusId != null }) { island.collapse(); showFocusSheet() }
        list += QuickAction(R.drawable.ic_cc_bell_off, "Silent", { am.ringerMode != android.media.AudioManager.RINGER_MODE_NORMAL }) {
            runCatching {
                am.ringerMode = if (am.ringerMode == android.media.AudioManager.RINGER_MODE_NORMAL)
                    android.media.AudioManager.RINGER_MODE_VIBRATE else android.media.AudioManager.RINGER_MODE_NORMAL
            }
        }
        list += QuickAction(R.drawable.ic_cc_rotation, "Rotation Lock", {
            runCatching { android.provider.Settings.System.getInt(contentResolver, android.provider.Settings.System.ACCELEROMETER_ROTATION) == 0 }.getOrDefault(false)
        }) {
            if (android.provider.Settings.System.canWrite(this)) runCatching {
                val cur = android.provider.Settings.System.getInt(contentResolver, android.provider.Settings.System.ACCELEROMETER_ROTATION, 1)
                android.provider.Settings.System.putInt(contentResolver, android.provider.Settings.System.ACCELEROMETER_ROTATION, if (cur == 1) 0 else 1)
            } else {
                toast("Allow “Modify system settings” to lock rotation")
                runCatching { startActivity(Intent(android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName"))) }
            }
        }
        list += QuickAction(R.drawable.ic_cc_contrast, "Dark Mode", {
            (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        }) {
            island.collapse()
            val ok = runCatching { startActivity(Intent("android.settings.DARK_THEME_SETTINGS")) }.isSuccess
            if (!ok) runCatching { startActivity(Intent(android.provider.Settings.ACTION_DISPLAY_SETTINGS)) }
        }
        return list
    }

    private fun setupSidebar() {
        // Over other apps (games, videos…) a background service keeps the sidebar alive;
        // otherwise it lives on the Home Screen only.
        if (SidebarService.sync(this) && settings.sidebarOverApps) { sidebar?.detach(); return }
        if (sidebar == null) sidebar = Sidebar(this, this, settings, { allApps }) { a -> GameState.launch(this, a.component) }
        root.post { runCatching { sidebar?.attach() } }
    }

    /** The iPhone "hello" screen. */
    private fun showHello(then: (() -> Unit)? = null) {
        if (helloView != null) return
        SoundFX.play(this, "hello")
        val v = HelloView(this) {
            helloView?.let { root.removeView(it) }
            helloView = null
            then?.invoke()
        }
        helloView = v
        root.addView(v, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        v.alpha = 0f
        v.animate().alpha(1f).setDuration(400).start()
    }

    private fun applyIslandLook() {
        if (!::island.isInitialized) return
        island.sizeScale = settings.islandScale / 100f
        island.style = settings.islandStyle
        island.anim = settings.islandAnim
        island.offsetX = dp(settings.islandX).toFloat()
        island.applyLook()
        applyToolsStyle(island)
        overlayIsland?.let { applyToolsStyle(it) }
    }

    /** Island tools bar: size, colour, shape, angle and where you dragged it. */
    private fun applyToolsStyle(isl: DynamicIsland) {
        val op = (settings.toolsOpacity * 255 / 100).coerceIn(40, 255)
        fun withA(c: Int, a: Int = op) = (c and 0x00FFFFFF) or (a shl 24)
        isl.toolsScale = settings.toolsScale / 100f
        val base = when (settings.toolsColor) {
            "glass" -> withA(Color.rgb(28, 28, 32), (op * 0.72f).toInt())
            "theme" -> withA(IconFactory.blend(Color.BLACK, ThemeEngine.accent, 0.5f))
            "white" -> withA(Color.rgb(245, 245, 247))
            "custom" -> withA(settings.toolsCustomColor)
            else -> withA(Color.BLACK)
        }
        isl.toolsColor = base
        isl.toolsStroke = if (settings.toolsColor == "glass") 0x55FFFFFF else 0
        val lum = (0.299 * Color.red(base) + 0.587 * Color.green(base) + 0.114 * Color.blue(base)) / 255
        isl.toolsIcon = if (lum > 0.62) 0xFF1C1C1E.toInt() else Color.WHITE
        isl.toolsShape = settings.toolsShape
        isl.toolsAngle = settings.toolsAngle.toFloat()
        isl.toolsDx = 0f; isl.toolsDy = 0f   // the tools stay with the island (no dragging)
        isl.hotDx = dp(settings.hotspotDx).toFloat(); isl.hotDy = dp(settings.hotspotDy).toFloat()
        isl.toolsAngle = 0f
        isl.onToolsMoved = { dx, dy ->
            val d = resources.displayMetrics.density
            settings.toolsDx = (dx / d).roundToInt(); settings.toolsDy = (dy / d).roundToInt()
            // Keep the other copy (over apps) in the same spot.
            for (o in listOfNotNull(island, overlayIsland)) if (o !== isl) { o.toolsDx = dx; o.toolsDy = dy; o.applyToolsLook() }
        }
        isl.applyToolsLook()
    }

    private fun restylePersistent() {
        scrim.alpha = ((settings.dim + Focus.extraDim(focusId)).coerceAtMost(95)) / 100f
        searchPill.background = Glass.panel(this, 16f)
        dots.background = Glass.panel(this, 16f)
        val dr = settings.dockRadius.toFloat()
        dockRow.background = when (settings.dockStyle) {
            "dark" -> GradientDrawable().apply { cornerRadius = dp(dr.toInt()).toFloat(); setColor(0xD9141416.toInt()); setStroke(maxOf(1, dp(1)), 0x22FFFFFF) }
            "light" -> GradientDrawable().apply { cornerRadius = dp(dr.toInt()).toFloat(); setColor(0xCCF2F2F7.toInt()); setStroke(maxOf(1, dp(1)), 0x33FFFFFF) }
            "clear" -> null
            else -> Glass.panel(this, dr)
        }
        (dockRow.parent as? View)?.visibility = if (settings.dockOn) View.VISIBLE else View.GONE
        defaultBanner.background = Glass.panel(this, 18f)
        searchField.background = Glass.panel(this, 14f)
    }

    private fun haptic(v: View?, type: Int = HapticFeedbackConstants.LONG_PRESS) {
        if (settings.haptics) v?.performHapticFeedback(type)
    }

    // ================================================================ UI skeleton

    private fun buildUi() {
        root = FrameLayout(this)
        spatialWall = SpatialImageView(this).apply { visibility = View.GONE }
        root.addView(spatialWall, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        scrim = View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.argb(200, 0, 0, 0), Color.argb(50, 0, 0, 0), Color.argb(255, 0, 0, 0))
            )
        }
        root.addView(scrim, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
        }

        defaultBanner = TextView(this).apply {
            text = "Tap here to make Apple Launcher your Home app"
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(10), dp(16), dp(10))
            visibility = View.GONE
            setOnClickListener { openHomeSettings() }
        }
        content.addView(defaultBanner, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            setMargins(dp(16), dp(6), dp(16), dp(4))
        })

        pager = ViewPager2(this).apply {
            adapter = pagesAdapter
            offscreenPageLimit = 2
        }
        (pager.getChildAt(0) as? RecyclerView)?.overScrollMode = View.OVER_SCROLL_NEVER
        // If the pages turn out narrower/wider than the screen size we guessed, lay the grid out again
        // so every column (including the 4th) fits exactly.
        pager.addOnLayoutChangeListener(View.OnLayoutChangeListener { _, l, _, r, _, ol, _, or, _ ->
            if (r - l != or - ol && r - l > 0) { pager.post { refreshPages() } }
        })
        content.addView(pager, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        val pillHolder = FrameLayout(this)
        searchPill = TextView(this).apply {
            text = "Search"
            setTextColor(0xF2FFFFFF.toInt())
            textSize = 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_search, 0, 0, 0)
            compoundDrawablePadding = dp(5)
            setPadding(dp(13), 0, dp(15), 0)
            setOnClickListener { openSearch() }
        }
        searchPill.setOnTouchListener(pressEffect(searchPill, 0.92f))
        pillHolder.addView(searchPill, FrameLayout.LayoutParams(WRAP_CONTENT, dp(30), Gravity.CENTER))
        dots = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(9), 0, dp(9), 0)
            alpha = 0f
        }
        pillHolder.addView(dots, FrameLayout.LayoutParams(WRAP_CONTENT, dp(24), Gravity.CENTER))
        content.addView(pillHolder, LinearLayout.LayoutParams(MATCH_PARENT, dp(42)))

        dockRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(13), dp(8), dp(13))
            clipChildren = false
            clipToPadding = false
        }
        val dockArea = FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
        }
        dockArea.addView(dockRow, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER))
        content.addView(dockArea, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            setMargins(dp(10), dp(2), dp(10), dp(8))
        })

        root.addView(content, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        buildEditBar(pillHolder)
        buildSearchOverlay()
        restylePersistent()

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            insetTop = bars.top
            insetBottom = bars.bottom
            if (::headsetPopup.isInitialized) headsetPopup.bottomInset = bars.bottom
            // The island sits just below the status bar so it never covers the clock or icons,
            // and the Home Screen moves down to make room for it.
            // Position comes from Settings › Dynamic Island (in or below the status bar, plus a nudge).
            val cut = insets.getInsets(WindowInsetsCompat.Type.displayCutout()).top
            val band = if (cut > 0) cut else bars.top
            val base = if (settings.islandPlacement == "statusbar") ((band - dp(36)) / 2).coerceAtLeast(dp(3))
                else bars.top + dp(4)
            val islandTop = (base + dp(settings.islandNudge)).coerceAtLeast(dp(2))
            // Only push the Home Screen down when the island sits near the top; lower down it floats.
            val islandBottom = islandTop + (dp(36) * settings.islandScale / 100f).toInt()
            // Gilbert (the small orb) floats over the Home Screen — moving him never shifts your apps.
            val islandRoom = if (settings.island && !settings.islandFace && islandTop < bars.top + dp(70))
                (islandBottom + dp(6) - bars.top).coerceAtLeast(0) else 0
            if (::island.isInitialized) island.setTopInset(islandTop)
            // Where the front camera hole is, so the closed tools can hide right around it.
            val cam = if (Build.VERSION.SDK_INT >= 28) insets.toWindowInsets()?.displayCutout?.boundingRects
                ?.filter { it.top <= dp(4) && it.height() < dp(80) }?.minByOrNull { kotlin.math.abs(it.centerX() - root.width / 2) } else null
            if (::island.isInitialized) island.camera = cam
            overlayIsland?.camera = cam
            tapZone?.camera = cam
            lastIslandTop = islandTop
            content.setPadding(0, bars.top + islandRoom, 0, bars.bottom)
            searchOverlay.setPadding(dp(16), bars.top + dp(10), dp(16), maxOf(bars.bottom, ime.bottom))
            insets
        }

        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateDots(position)
                if (pager.scrollState != ViewPager2.SCROLL_STATE_IDLE) haptic(root, HapticFeedbackConstants.CLOCK_TICK)
            }

            override fun onPageScrollStateChanged(state: Int) {
                // While swiping, draw each page from a cached GPU layer so it stays at full frame rate.
                val rv = pager.getChildAt(0) as? RecyclerView
                val layer = if (state == ViewPager2.SCROLL_STATE_IDLE) View.LAYER_TYPE_NONE else View.LAYER_TYPE_HARDWARE
                if (rv != null) for (i in 0 until rv.childCount) {
                    val c = rv.getChildAt(i)
                    if (c.layerType != layer) c.setLayerType(layer, null)
                }
                mainHandler.removeCallbacks(showSearchPill)
                if (state == ViewPager2.SCROLL_STATE_IDLE) mainHandler.postDelayed(showSearchPill, 1000)
                else setPillMode(search = false)
            }

            override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {
                val n = pages.size
                val token = root.windowToken ?: return
                if (n < 2) return
                if (spatialWall.visibility == View.VISIBLE) {
                    // Gentle parallax across all pages, centred, never past the zoomed edge.
                    val f = (position + positionOffset) / (n - 1).coerceAtLeast(1)
                    spatialWall.shiftX = -(f - 0.5f) * 2f * spatialWall.shiftRange
                }
                runCatching {
                    val wm = WallpaperManager.getInstance(this@MainActivity)
                    wm.setWallpaperOffsetSteps(1f / (n - 1), 0f)
                    wm.setWallpaperOffsets(token, (position + positionOffset) / (n - 1), 0f)
                }
            }
        })
        applyPageEffect()
    }

    private fun applyPageEffect() {
        val effect = if (anim == 0f) "flat" else settings.pageEffect
        pager.setPageTransformer { page, position ->
            page.alpha = 1f
            page.scaleX = 1f
            page.scaleY = 1f
            page.rotationY = 0f
            page.rotationX = 0f
            page.rotation = 0f
            page.translationX = 0f
            page.translationY = 0f
            page.cameraDistance = 8000f * resources.displayMetrics.density
            page.pivotX = page.width / 2f
            page.pivotY = page.height / 2f
            // Pages further than one screen away are simply hidden-normal (no half-turned leftovers).
            if (abs(position) >= 1f && effect != "flat" && effect != "depth" && effect != "cube" && effect != "tilt" && effect != "roll") {
                page.alpha = if (abs(position) >= 1f) 0f else 1f
                return@setPageTransformer
            }
            val p = abs(position).coerceAtMost(1f)
            when (effect) {
                "depth" -> {
                    page.alpha = 1f - p * 0.35f
                    val s = 1f - p * 0.06f
                    page.scaleX = s; page.scaleY = s
                }
                "cube" -> {
                    page.cameraDistance = page.width * 14f
                    page.pivotX = if (position < 0f) page.width.toFloat() else 0f
                    page.rotationY = 55f * position.coerceIn(-1f, 1f)
                }
                "zoom" -> {
                    val s = 1f - p * 0.18f
                    page.scaleX = s; page.scaleY = s
                    page.alpha = 1f - p * 0.6f
                }
                // Rolls like a wheel around the bottom.
                "roll" -> {
                    page.pivotY = page.height * 1.6f
                    page.rotation = 18f * position.coerceIn(-1f, 1f)
                }
                // Flips over like a card around its middle.
                "flip" -> {
                    page.cameraDistance = page.width * 20f
                    page.translationX = -position * page.width
                    page.rotationY = 180f * position.coerceIn(-1f, 1f)
                    page.alpha = if (p < 0.5f) 1f else 0f
                }
                // The next page slides over the current one like a stack of cards.
                "card" -> {
                    if (position > 0f) {
                        page.translationX = -position * page.width
                        val s = 1f - p * 0.12f
                        page.scaleX = s; page.scaleY = s
                        page.alpha = 1f - p * 0.5f
                    }
                }
                // Pages tilt away in 3D.
                "tilt" -> {
                    page.cameraDistance = page.width * 10f
                    page.rotationY = -25f * position.coerceIn(-1f, 1f)
                    page.alpha = 1f - p * 0.3f
                }
                // Android-style: the old page shrinks back while the new one slides in.
                "stack" -> {
                    if (position < 0f) {
                        page.translationX = -position * page.width * 0.7f
                        val s = 1f - p * 0.1f
                        page.scaleX = s; page.scaleY = s
                        page.alpha = 1f - p * 0.7f
                    }
                }
                "fade" -> {
                    page.translationX = -position * page.width
                    page.alpha = 1f - p
                }
            }
        }
    }

    private fun buildEditBar(host: FrameLayout) {
        editBar = FrameLayout(this).apply { visibility = View.GONE }
        val plus = TextView(this).apply {
            text = "＋"
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
            background = Glass.panel(this@MainActivity, 17f)
            setOnClickListener { showRestoreWidgetsMenu(this) }
        }
        plus.setOnTouchListener(pressEffect(plus, 0.9f))
        editBar.addView(plus, FrameLayout.LayoutParams(dp(34), dp(34), Gravity.START or Gravity.CENTER_VERTICAL).apply {
            marginStart = dp(16)
        })
        val pageControls = TextView(this).apply {
            text = "Pages"
            contentDescription = "Manage home pages"
            setTextColor(Color.WHITE)
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(dp(15), 0, dp(15), 0)
            background = Glass.panel(this@MainActivity, 17f)
            setOnClickListener { showPageMenu() }
        }
        pageControls.setOnTouchListener(pressEffect(pageControls, 0.9f))
        editBar.addView(pageControls, FrameLayout.LayoutParams(WRAP_CONTENT, dp(34), Gravity.CENTER))
        val done = TextView(this).apply {
            text = "Done"
            setTextColor(Color.WHITE)
            textSize = 15f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(18), 0, dp(18), 0)
            background = Glass.panel(this@MainActivity, 17f)
            setOnClickListener { exitEditMode() }
        }
        done.setOnTouchListener(pressEffect(done, 0.9f))
        editBar.addView(done, FrameLayout.LayoutParams(WRAP_CONTENT, dp(34), Gravity.END or Gravity.CENTER_VERTICAL).apply {
            marginEnd = dp(16)
        })
        // The existing search strip gives edit controls their own space above the dock.
        host.addView(editBar, FrameLayout.LayoutParams(MATCH_PARENT, dp(40), Gravity.CENTER))
    }

    private fun buildSearchOverlay() {
        searchOverlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0x99101012.toInt())
            visibility = View.GONE
            isClickable = true
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        searchField = EditText(this).apply {
            hint = "Search apps, math, the web"
            setHintTextColor(0x99FFFFFF.toInt())
            setTextColor(Color.WHITE)
            textSize = 17f
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_search, 0, 0, 0)
            compoundDrawablePadding = dp(8)
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_GO
        }
        row.addView(searchField, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        val cancel = TextView(this).apply {
            text = "Cancel"
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding(dp(14), dp(10), dp(4), dp(10))
            setOnClickListener { closeSearch() }
        }
        row.addView(cancel, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        searchOverlay.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        calcRow = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 30f
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = Glass.panel(this@MainActivity, 14f)
            visibility = View.GONE
            setOnClickListener {
                val cm = getSystemService(ClipboardManager::class.java)
                cm?.setPrimaryClip(ClipData.newPlainText("result", text.toString().removePrefix("= ")))
                toast("Copied")
            }
        }
        searchOverlay.addView(calcRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(14) })
        searchExtras = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        searchOverlay.addView(searchExtras, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        searchHeader = TextView(this).apply {
            text = "Suggestions"
            setTextColor(0xB3FFFFFF.toInt())
            textSize = 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(dp(4), dp(18), 0, dp(6))
        }
        searchOverlay.addView(searchHeader, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val results = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = searchAdapter
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        searchOverlay.addView(results, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        webRow = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_search, 0, 0, 0)
            compoundDrawablePadding = dp(10)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            visibility = View.GONE
            setOnClickListener { searchWeb(searchField.text.toString()) }
        }
        searchOverlay.addView(webRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            bottomMargin = dp(8)
        })
        root.addView(searchOverlay, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        searchField.addTextChangedListener(afterTextChanged = { filterSearch(it?.toString().orEmpty()) })
        searchField.setOnEditorActionListener { _, _, _ ->
            val q = searchField.text.toString().trim().lowercase()
            if (q.contains("timer") || q == "tim") {
                closeSearch(false)
                showTimerPopup()
                return@setOnEditorActionListener true
            }
            val first = searchResults.firstOrNull()
            if (first != null) launch(first, searchField) else searchWeb(searchField.text.toString())
            true
        }
    }

    private fun setPillMode(search: Boolean) {
        searchPill.animate().alpha(if (search) 1f else 0f).setDuration(dur(220)).setInterpolator(EASE).start()
        dots.animate().alpha(if (search) 0f else 1f).setDuration(dur(220)).setInterpolator(EASE).start()
    }

    private fun updateDots(selected: Int) {
        dots.removeAllViews()
        for (i in pages.indices) {
            val v = View(this).apply {
                background = Glass.circle(Color.WHITE)
                alpha = if (i == selected) 1f else 0.35f
            }
            dots.addView(v, LinearLayout.LayoutParams(dp(7), dp(7)).apply { setMargins(dp(4), 0, dp(4), 0) })
        }
    }

    // ================================================================ motion helpers

    private fun animateBlur(to: Float, duration: Long) {
        if (Build.VERSION.SDK_INT < 31) return
        val target = if (settings.blur) to else 0f
        blurAnimator?.cancel()
        if (duration <= 1L) {
            currentBlur = target
            content.setRenderEffect(
                if (target < 0.5f) null else RenderEffect.createBlurEffect(target, target, Shader.TileMode.CLAMP)
            )
            return
        }
        blurAnimator = ValueAnimator.ofFloat(currentBlur, target).apply {
            this.duration = duration
            interpolator = EASE
            var lastStep = -1
            addUpdateListener {
                val v = it.animatedValue as Float
                currentBlur = v
                val step = (v * 2).toInt() // half-pixel steps look identical but cost far less
                if (step != lastStep) {
                    lastStep = step
                    content.setRenderEffect(
                        if (v < 0.5f) null else RenderEffect.createBlurEffect(v, v, Shader.TileMode.CLAMP)
                    )
                }
            }
            start()
        }
    }

    /** Stop springs on the home content before handing it to a ViewPropertyAnimator. */
    private fun contentAnimate(): android.view.ViewPropertyAnimator {
        Motion.cancel(content)
        return content.animate()
    }

    /** The home screen settles back with a calm spring, like returning home on iPhone. */
    private fun restoreHome(animated: Boolean) {
        content.animate().cancel()
        if (!animated || anim == 0f) {
            Motion.cancel(content)
            content.scaleX = 1f; content.scaleY = 1f; content.alpha = 1f; content.translationY = 0f
            return
        }
        Motion.scaleTo(content, 1f, Motion.CALM)
        Motion.alphaTo(content, 1f, Motion.CALM)
        if (content.translationY != 0f) content.animate().translationY(0f).setDuration(dur(320)).setInterpolator(EASE).start()
        if (currentBlur > 0f && menuLayer == null && searchOverlay.visibility != View.VISIBLE) {
            animateBlur(0f, dur(380))
        }
    }

    /** iPhone-style "unlock" zoom: the whole home screen flies in from slightly larger. */
    private fun playUnlockZoom() {
        if (anim == 0f || overlayOpen() || editMode) return
        content.animate().cancel()
        Motion.scaleTo(content, 1f, Motion.CALM, start = 1.16f)
        Motion.alphaTo(content, 1f, Motion.CALM, start = 0f)
    }

    private fun pressEffect(target: View, scale: Float = 0.88f) = View.OnTouchListener { _, e ->
        if (anim > 0f) {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // Sink quickly under the finger and dim slightly.
                    Motion.scaleTo(target, scale, Motion.SNAPPY)
                    Motion.alphaTo(target, 0.78f, Motion.SNAPPY)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    // Pop back with a soft bounce.
                    Motion.scaleTo(target, 1f, Motion.BOUNCY)
                    Motion.alphaTo(target, 1f, Motion.CALM)
                }
            }
        }
        false
    }

    private fun entrance(v: View, col: Int, row: Int) {
        if (!entrancePending || anim == 0f) return
        v.alpha = 0f
        v.scaleX = 0.8f
        v.scaleY = 0.8f
        // Ripple out from the top-left like the iOS home screen appearing.
        v.postDelayed({
            Motion.scaleTo(v, 1f, Motion.BOUNCY)
            Motion.alphaTo(v, 1f, Motion.CALM)
        }, dur(row * 40L + col * 25L))
    }

    private fun startJiggle(v: View) {
        if (anim == 0f) return
        val a = ObjectAnimator.ofFloat(v, "rotation", -1.8f, 1.8f).apply {
            duration = 115L + (Math.random() * 40).toLong()
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            startDelay = (Math.random() * 120).toLong()
        }
        a.start()
        jiggles += a
    }

    private fun stopJiggle() {
        for (a in jiggles) a.cancel()
        jiggles.clear()
    }

    // ================================================================ data

    private fun reloadApps() {
        val iconPx = dp(iconDp) * 2
        val style = settings.iconStyle
        val tint = settings.tint
        io.execute {
            val apps = runCatching { AppLoader.load(this, iconPx, style, tint) }.getOrDefault(emptyList())
            mainHandler.post {
                if (!isDestroyed) {
                    allApps = apps
                    appsReady = true
                    rebuild()
                    mainHandler.postDelayed({ entrancePending = false }, 1200)
                }
            }
        }
    }

    private fun rowsPerPage(): Int {
        if (settings.rows in 4..7) return settings.rows
        val m = resources.displayMetrics
        val hDp = m.heightPixels / m.density
        return ((hDp - 230f) / CELL_H).toInt().coerceIn(4, 7)
    }

    private fun defaultWidgetsFor(page: Int): List<PlacedWidget> {
        val wide = cols
        val half = cols / 2
        val right = cols - half
        return when (page) {
            0 -> listOf(
                PlacedWidget(WidgetType.PHOTO, 0, 0, half, 2, "p1"),
                PlacedWidget(WidgetType.CLOCK_ICON, right, 1, 1, 1),
                PlacedWidget(WidgetType.CALENDAR_ICON, right + 1, 1, 1, 1),
                PlacedWidget(WidgetType.PHOTO, right, 2, half, 2, "p2"),
                PlacedWidget(WidgetType.MUSIC, 0, 4, wide, 2)
            )
            1 -> listOf(
                PlacedWidget(WidgetType.PHOTO, right, 0, half, 2, "p3"),
                PlacedWidget(WidgetType.CALENDAR, 0, 2, wide, 2),
                PlacedWidget(WidgetType.PHOTO, 0, 4, half, 2, "p4")
            )
            2 -> listOf(
                PlacedWidget(WidgetType.PHOTO, 0, 0, wide, 2, "p5"),
                PlacedWidget(WidgetType.BATTERY, right, 2, half, 2)
            )
            else -> emptyList()
        }
    }

    private fun widgetPositions(): JSONObject = runCatching {
        JSONObject(prefs.getString("widget_positions", "{}") ?: "{}")
    }.getOrDefault(JSONObject())

    private fun additionalWidgetData(): JSONObject = runCatching {
        JSONObject(prefs.getString("additional_widgets", "{}") ?: "{}")
    }.getOrDefault(JSONObject())

    private fun additionalWidgets(): List<Pair<Int, PlacedWidget>> {
        val json = additionalWidgetData()
        return json.keys().asSequence().mapNotNull { id ->
            val item = json.optJSONObject(id) ?: return@mapNotNull null
            val type = runCatching { WidgetType.valueOf(item.optString("type")) }.getOrNull() ?: return@mapNotNull null
            val width = item.optInt("w", 1).coerceIn(1, cols)
            val height = item.optInt("h", 1).coerceIn(1, rowsPerPage())
            val page = item.optInt("page", 0).coerceIn(0, 100)
            val w = PlacedWidget(type,
                item.optInt("col", 0).coerceIn(0, cols - width),
                item.optInt("row", 0).coerceIn(0, rowsPerPage() - height), width, height, id, page)
            page to w
        }.toList()
    }

    private fun saveAdditionalWidget(w: PlacedWidget, page: Int) {
        val json = additionalWidgetData()
        json.put(w.id, JSONObject().put("type", w.type.name).put("page", page)
            .put("col", w.col).put("row", w.row).put("w", w.w).put("h", w.h))
        prefs.edit().putString("additional_widgets", json.toString()).apply()
    }

    private fun widgetsFor(page: Int): List<PlacedWidget> {
        val positions = widgetPositions()
        val builtIn = (0 until MIN_PAGES).flatMap { origin ->
            defaultWidgetsFor(origin).mapNotNull { widget ->
                val stable = widget.copy(originPage = origin)
                val saved = positions.optJSONObject(widgetKey(origin, stable))
                val savedPage = saved?.optInt("page", origin)?.coerceIn(0, 100) ?: origin
                val width = (saved?.optInt("w", widget.w) ?: widget.w).coerceIn(1, cols)
                val height = (saved?.optInt("h", widget.h) ?: widget.h).coerceIn(1, rowsPerPage())
                if (savedPage != page) null else stable.copy(
                    col = (saved?.optInt("col", widget.col) ?: widget.col).coerceIn(0, cols - width),
                    row = (saved?.optInt("row", widget.row) ?: widget.row).coerceIn(0, rowsPerPage() - height),
                    w = width, h = height
                )
            }
        }
        return builtIn + additionalWidgets().filter { it.first == page }.map { it.second }
    }

    private fun lastWidgetPage(): Int {
        val positions = widgetPositions()
        val builtInMax = positions.keys().asSequence().mapNotNull { positions.optJSONObject(it)?.optInt("page", 0) }
            .maxOrNull()?.coerceIn(0, 100) ?: 0
        return maxOf(builtInMax, additionalWidgets().maxOfOrNull { it.first } ?: 0)
    }

    private fun widgetKey(page: Int, w: PlacedWidget) =
        if (w.id.startsWith("extra_") || w.id.startsWith("appwidget_")) "extra:${w.id}"
        else "${if (w.originPage >= 0) w.originPage else page}:${w.type.name}:${w.id}"

    private fun widgetVisible(page: Int, w: PlacedWidget): Boolean {
        if (w.type == WidgetType.APPWIDGET) return true
        val typeKey = when (w.type) {
            WidgetType.CLOCK_ICON, WidgetType.CALENDAR_ICON -> "ICONS"
            WidgetType.PHOTO_WIDE -> "PHOTO"
            else -> w.type.name
        }
        return settings.widgetEnabled(typeKey) && widgetKey(page, w) !in settings.hiddenWidgets
    }

    /** Keep restored or re-enabled widgets from covering one another after a layout change. */
    private fun resolveWidgetCollisions() {
        val last = maxOf(MIN_PAGES - 1, lastWidgetPage()).coerceIn(0, 100)
        val widgets = (0..last).flatMap { page ->
            widgetsFor(page).filter { widgetVisible(page, it) }.map { page to it }
        }.sortedBy { (_, w) -> if (w.id.startsWith("extra_") || w.id.startsWith("appwidget_")) 0 else 1 }
        val occupied = mutableListOf<WidgetGrid.Placement>()
        val builtInPositions = widgetPositions()
        val added = additionalWidgetData()
        var changed = false
        for ((page, w) in widgets) {
            val desired = WidgetGrid.Placement(page, w.col, w.row, w.w, w.h)
            val target = if (WidgetGrid.fits(desired, cols, rowsPerPage(), occupied)) desired
                else WidgetGrid.firstFit(cols, rowsPerPage(), w.w, w.h,
                    listOf(page) + (0..100).filter { it != page }, occupied) ?: continue
            occupied += target
            if (target != desired) {
                if (w.id.startsWith("extra_") || w.id.startsWith("appwidget_")) {
                    added.optJSONObject(w.id)?.put("page", target.page)?.put("col", target.col)?.put("row", target.row)
                } else {
                    builtInPositions.put(widgetKey(page, w), JSONObject().put("page", target.page)
                        .put("col", target.col).put("row", target.row).put("w", w.w).put("h", w.h))
                }
                changed = true
            }
        }
        if (changed) prefs.edit().putString("widget_positions", builtInPositions.toString())
            .putString("additional_widgets", added.toString()).apply()
    }

    private fun folders(): List<HomeFolder> {
        val json = runCatching { JSONObject(prefs.getString("home_folders", "{}") ?: "{}") }.getOrDefault(JSONObject())
        return json.keys().asSequence().mapNotNull { id ->
            val value = json.optJSONObject(id) ?: return@mapNotNull null
            val members = value.optJSONArray("apps") ?: JSONArray()
            HomeFolder(id, value.optString("name", "Folder"),
                (0 until members.length()).mapNotNull { members.optString(it).takeIf(String::isNotEmpty) }.distinct())
        }.toList()
    }

    private fun saveFolders(folders: List<HomeFolder>) {
        val json = JSONObject()
        folders.forEach { folder ->
            json.put(folder.id, JSONObject().put("name", folder.name).put("apps", JSONArray(folder.keys)))
        }
        prefs.edit().putString("home_folders", json.toString()).apply()
    }

    private fun folderComponent(id: String) = ComponentName(packageName, "$packageName.folder.$id")
    private fun folderId(app: AppInfo): String? {
        val prefix = "$packageName.folder."
        return if (app.component.packageName == packageName && app.component.className.startsWith(prefix))
            app.component.className.removePrefix(prefix) else null
    }

    private fun folderApp(folder: HomeFolder, appsByKey: Map<String, AppInfo>): AppInfo {
        val side = dp(iconDp).coerceAtLeast(1) * 2
        val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        paint.color = 0xBB333338.toInt()
        canvas.drawRoundRect(0f, 0f, side.toFloat(), side.toFloat(), side * 0.23f, side * 0.23f, paint)
        val gap = side * 0.09f
        val size = (side - gap * 3) / 2f
        folder.keys.mapNotNull { appsByKey[it] }.take(4).forEachIndexed { index, app ->
            val left = gap + (index % 2) * (size + gap)
            val top = gap + (index / 2) * (size + gap)
            canvas.drawBitmap(app.icon, null, RectF(left, top, left + size, top + size), paint)
        }
        return AppInfo(folder.name, folderComponent(folder.id), bitmap)
    }

    /** Home apps in display order (custom order first, new apps at the end). */
    private fun homeApps(dockKeys: Set<String>): List<AppInfo> {
        val hidden = settings.hiddenApps
        val orderIndex = (previewOrder ?: settings.order).withIndex().associate { it.value to it.index }
        val groups = folders()
        val members = groups.flatMap { it.keys }.toSet()
        val byKey = allApps.associateBy { it.key }
        val grouped = groups.filter { folder -> folder.keys.any { it in byKey } }.map { folderApp(it, byKey) }
        return (allApps.filter { it.key !in members } + grouped)
            .filter { it.key !in dockKeys && it.key !in hidden }
            .sortedWith(compareBy({ orderIndex[it.key] ?: Int.MAX_VALUE }, { it.label.lowercase() }))
    }

    private fun rebuild() {
        reflowPositions = captureReflowPositions()
        reflowRevision++
        resolveWidgetCollisions()
        val dock = dockApps()
        val dockKeys = dock.map { it.key }.toSet()
        val home = homeApps(dockKeys)
        val homeByKey = home.associateBy { it.key }
        val slots = HomeGridOrder.normalize(previewOrder ?: settings.order, home.map { it.key })
        val rows = rowsPerPage()

        val result = mutableListOf<Page>()
        var next = 0
        var page = 0
        val lastWidget = lastWidgetPage()
        while (page < maxOf(minimumHomePages(), temporaryDragPageCount) || next < slots.size || page <= lastWidget) {
            val widgets = widgetsFor(page).filter { it.row + it.h <= rows && it.col + it.w <= cols && widgetVisible(page, it) }
            val taken = BooleanArray(rows * cols)
            for (w in widgets) {
                for (r in w.row until w.row + w.h) {
                    for (c in w.col until w.col + w.w) taken[r * cols + c] = true
                }
            }
            val apps = mutableListOf<Pair<AppInfo, Int>>()
            for (cell in 0 until rows * cols) {
                if (!taken[cell] && next < slots.size) {
                    val key = slots[next++]
                    homeByKey[key]?.let { apps += it to cell }
                }
            }
            result += Page.Home(widgets, apps)
            page++
        }
        if (settings.libraryOn) result += Page.Library

        pages = result
        refreshPages()
        if (pager.currentItem >= pages.size) pager.setCurrentItem(0, false)
        updateDots(pager.currentItem)
        buildDock(dock)
        filterSearch(searchField.text?.toString().orEmpty())
        appliedVersion = settings.version
    }

    @Suppress("NotifyDataSetChanged")
    private fun refreshPages() {
        stopJiggle()
        badgeViews.clear()
        musicViews = null
        pagesAdapter.notifyDataSetChanged()
        // Re-apply the swipe effect so no page is left rotated / see-through after a rebuild.
        pager.post { runCatching { pager.requestTransform() } }
    }

    private fun dockKeys(): List<String> =
        prefs.getString(dockPrefKey(), "").orEmpty().split('|').filter { it.isNotEmpty() }

    private fun dockApps(): List<AppInfo> {
        val byKey = allApps.associateBy { it.key }
        if (prefs.contains(dockPrefKey())) return dockKeys().mapNotNull { byKey[it] }.take(cols)
        val defaults = defaultDock()
        if (defaults.isNotEmpty()) prefs.edit().putString(dockPrefKey(), defaults.joinToString("|") { it.key }).apply()
        return defaults
    }

    private fun defaultDock(): List<AppInfo> {
        val intents = listOf(
            Intent(Intent.ACTION_DIAL),
            Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MESSAGING),
            Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA),
            Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MUSIC)
        )
        val result = mutableListOf<AppInfo>()
        for (i in intents) {
            val pkg = runCatching {
                packageManager.resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
            }.getOrNull() ?: continue
            allApps.firstOrNull { a -> a.packageName == pkg && result.none { it.key == a.key } }?.let { result += it }
        }
        for (a in allApps) {
            if (result.size >= 4) break
            if (result.none { it.key == a.key }) result += a
        }
        return result.take(4)
    }

    private fun saveDock(keys: List<String>) {
        prefs.edit().putString(dockPrefKey(), keys.distinct().joinToString("|")).apply()
    }

    private fun setDock(keys: List<String>) {
        saveDock(keys)
        rebuild()
    }

    private fun recentKeys(): List<String> =
        prefs.getString(KEY_RECENT, "").orEmpty().split('|').filter { it.isNotEmpty() }

    private fun recordRecent(app: AppInfo) {
        val list = (listOf(app.key) + recentKeys().filter { it != app.key }).take(20)
        prefs.edit()
            .putString(KEY_RECENT, list.joinToString("|"))
            .putLong("rt_${app.key}", System.currentTimeMillis())
            .apply()
    }

    // ================================================================ pages

    private inner class PagesAdapter : RecyclerView.Adapter<PageHolder>() {
        override fun getItemCount(): Int = pages.size

        override fun getItemViewType(position: Int): Int = if (pages[position] is Page.Library) 1 else 0

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): PageHolder =
            PageHolder(FrameLayout(parent.context).apply {
                layoutParams = android.view.ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)
                clipChildren = false
            })

        override fun onBindViewHolder(holder: PageHolder, position: Int) {
            holder.frame.removeAllViews()
            val view = when (val p = pages[position]) {
                is Page.Home -> buildHomePage(p, position)
                Page.Library -> buildLibraryPage()
            }
            holder.frame.addView(view, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
    }

    /** The real width the pages get (the window can be narrower than the screen on some phones). */
    private fun gridScreenW(): Int {
        val pw = if (::pager.isInitialized) pager.width else 0
        val rw = if (::root.isInitialized) root.width else 0
        return when { pw > 0 -> pw; rw > 0 -> rw; else -> resources.displayMetrics.widthPixels }
    }
    private fun cellW(): Int = (gridScreenW() - dp(PAGE_PAD) * 2) / cols

    private fun place(col: Int, row: Int, w: Int, h: Int): FrameLayout.LayoutParams {
        val cw = cellW()
        return FrameLayout.LayoutParams(cw * w, dp(CELL_H) * h).apply {
            leftMargin = cw * col
            topMargin = dp(CELL_H) * row
        }
    }

    private fun captureReflowPositions(): Map<String, ScreenPosition> {
        if (!::pager.isInitialized || anim == 0f || entrancePending) return emptyMap()
        val rv = pager.getChildAt(0) as? RecyclerView ?: return emptyMap()
        val found = mutableMapOf<String, ScreenPosition>()
        for (i in 0 until rv.childCount) {
            val holderView = rv.getChildAt(i)
            val index = rv.getChildViewHolder(holderView).bindingAdapterPosition
            if (index == RecyclerView.NO_POSITION) continue
            val frame = (holderView as? FrameLayout)?.getChildAt(0) as? FrameLayout ?: continue
            if (frame.tag != "home-page") continue
            for (n in 0 until frame.childCount) {
                val child = frame.getChildAt(n)
                val key = child.tag as? String ?: continue
                if (!key.startsWith("layout:")) continue
                val loc = IntArray(2)
                child.getLocationOnScreen(loc)
                found[key] = ScreenPosition(index, loc[0], loc[1])
            }
        }
        return found
    }

    private fun animateReflow(view: View, key: String, pageIndex: Int) {
        val old = reflowPositions[key] ?: return
        if (old.page != pageIndex || anim == 0f) return
        val revision = reflowRevision
        view.post {
            if (revision != reflowRevision || !view.isAttachedToWindow) return@post
            val loc = IntArray(2)
            view.getLocationOnScreen(loc)
            val dx = (old.x - loc[0]).toFloat()
            val dy = (old.y - loc[1]).toFloat()
            if (abs(dx) < 1f && abs(dy) < 1f) return@post
            // Slide from the old spot to the new one with a fluid spring (iOS-style reflow).
            Motion.translateTo(view, 0f, 0f, Motion.FLUID, startX = dx, startY = dy)
        }
    }

    private fun buildHomePage(p: Page.Home, pageIndex: Int): View {
        val frame = FrameLayout(this).apply {
            setPadding(dp(PAGE_PAD), dp(6), dp(PAGE_PAD), 0)
            clipChildren = false
            clipToPadding = false
            tag = "home-page"
            setOnLongClickListener {
                if (!editMode) showHomeMenu(it)
                true
            }
            setOnClickListener { if (editMode) exitEditMode() }
        }
        for (w in p.widgets) {
            val built = buildWidget(w, pageIndex)
            val v = if (editMode) withWidgetResizeHandle(built, w, pageIndex) else built
            val key = "layout:widget:${widgetKey(pageIndex, w)}"
            v.tag = key
            frame.addView(v, place(w.col, w.row, w.w, w.h))
            entrance(v, w.col, w.row)
            animateReflow(v, key, pageIndex)
        }
        for ((app, cell) in p.apps) {
            val v = makeAppCell(app, fromDock = false)
            val key = "layout:app:${app.key}"
            v.tag = key
            if (drag?.app?.key == app.key) v.alpha = 0.2f
            frame.addView(v, place(cell % cols, cell / cols, 1, 1))
            entrance(v, cell % cols, cell / cols)
            if (drag?.app?.key != app.key) animateReflow(v, key, pageIndex)
        }
        return frame
    }

    private fun buildLibraryPage(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(6), dp(16), 0)
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(TextView(this).apply {
            text = settings.libraryTitle.ifBlank { "App Library" }
            setTextColor(Color.WHITE)
            textSize = 30f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setShadowLayer(8f, 0f, 1f, 0x80000000.toInt())
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        val gear = TextView(this).apply {
            text = "⚙"
            setTextColor(Color.WHITE)
            textSize = 20f
            gravity = Gravity.CENTER
            background = Glass.panel(this@MainActivity, 18f)
            setOnClickListener { openSettings() }
        }
        gear.setOnTouchListener(pressEffect(gear, 0.9f))
        head.addView(gear, LinearLayout.LayoutParams(dp(36), dp(36)))
        col.addView(head, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val field = EditText(this).apply {
            hint = "Search App Library"
            setHintTextColor(0x99FFFFFF.toInt())
            setTextColor(Color.WHITE)
            textSize = 16f
            background = Glass.panel(this@MainActivity, 14f)
            setPadding(dp(14), dp(9), dp(14), dp(9))
            setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_search, 0, 0, 0)
            compoundDrawablePadding = dp(8)
            isSingleLine = true
        }
        col.addView(field, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            topMargin = dp(10); bottomMargin = dp(8)
        })

        // Search results (shown while typing) and the smart folders (shown otherwise).
        val libAdapter = AppListAdapter(false, { a, v -> launch(a, v) }, { a, v -> showAppMenu(a, v) })
        val list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = libAdapter
            overScrollMode = View.OVER_SCROLL_NEVER
            visibility = View.GONE
        }
        val categories = android.widget.ScrollView(this).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
        }
        categories.addView(buildLibraryFolders())
        // List style: every app A–Z instead of the smart folders.
        val asList = settings.libraryStyle == "list"
        if (asList) {
            libAdapter.submit(allApps.sortedBy { it.label.lowercase() })
            list.visibility = View.VISIBLE; categories.visibility = View.GONE
        }
        field.addTextChangedListener(afterTextChanged = { s ->
            val q = s?.toString().orEmpty().trim()
            if (q.isEmpty() && asList) {
                libAdapter.submit(allApps.sortedBy { it.label.lowercase() })
                list.visibility = View.VISIBLE; categories.visibility = View.GONE
            } else if (q.isEmpty()) {
                list.visibility = View.GONE; categories.visibility = View.VISIBLE
            } else {
                libAdapter.submit(allApps.filter { it.label.contains(q, true) }.sortedBy { !it.label.startsWith(q, true) })
                list.visibility = View.VISIBLE; categories.visibility = View.GONE
            }
        })
        val body = FrameLayout(this)
        body.addView(categories, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        body.addView(list, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        col.addView(body, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        return col
    }

    // ================================================================ cells & widgets

    private fun roundedOutline(radiusDp: Float? = null) = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            val r = radiusDp?.let { dp(it).toFloat() } ?: (minOf(view.width, view.height) * IconFactory.CORNER)
            outline.setRoundRect(0, 0, view.width, view.height, r)
        }
    }

    private fun label(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(Color.WHITE)
        textSize = settings.labelSize / 10f
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        gravity = Gravity.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setShadowLayer(6f, 0f, 1f, 0x99000000.toInt())
    }

    private fun minusButton(onClick: () -> Unit): TextView = TextView(this).apply {
        text = "−"
        setTextColor(Color.WHITE)
        textSize = 16f
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        gravity = Gravity.CENTER
        includeFontPadding = false
        background = Glass.circle(0xF0505055.toInt())
        elevation = dp(3).toFloat()
        setOnClickListener {
            haptic(this, HapticFeedbackConstants.CONTEXT_CLICK)
            onClick()
        }
    }

    /** Generic icon cell: icon + optional badge + optional label, with springy press feedback. */
    private fun makeCell(
        icon: View,
        text: String?,
        badgePackage: String?,
        onClick: (View) -> Unit,
        onLong: ((View) -> Unit)?,
        onRemove: (() -> Unit)? = null
    ): View {
        val cell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(4), 0, 0)
            clipChildren = false
            clipToPadding = false
        }
        val holder = FrameLayout(this).apply { clipChildren = false }
        icon.outlineProvider = roundedOutline()
        icon.clipToOutline = true
        holder.addView(icon, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        if (badgePackage != null && badgesOn() && !editMode) {
            val badge = makeBadge()
            holder.addView(badge, FrameLayout.LayoutParams(WRAP_CONTENT, dp(21), Gravity.TOP or Gravity.END).apply {
                topMargin = -dp(7); marginEnd = -dp(8)
            })
            badgeViews += badgePackage to badge
            applyBadge(badge, NotificationService.counts[badgePackage] ?: 0)
        }
        if (editMode && onRemove != null) {
            holder.addView(minusButton(onRemove), FrameLayout.LayoutParams(dp(22), dp(22), Gravity.TOP or Gravity.START).apply {
                topMargin = -dp(7); marginStart = -dp(7)
            })
        }
        cell.addView(holder, LinearLayout.LayoutParams(dp(iconDp), dp(iconDp)))
        if (text != null && settings.showLabels) {
            cell.addView(label(text), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                topMargin = dp(5); leftMargin = dp(2); rightMargin = dp(2)
            })
        }
        if (editMode) {
            startJiggle(holder)
        } else {
            cell.setOnClickListener { onClick(holder) }
            if (onLong != null) cell.setOnLongClickListener { onLong(holder); true }
            cell.setOnTouchListener(pressEffect(holder))
        }
        return cell
    }

    private fun makeAppCell(app: AppInfo, fromDock: Boolean, showLabel: Boolean = true): View {
        val icon = ImageView(this).apply { setImageBitmap(app.icon) }
        val cell = makeCell(
            icon, if (showLabel) app.label else null, if (folderId(app) == null) app.packageName else null,
            { launch(app, it) }, null,
            onRemove = { removeFromHome(app, fromDock) }
        )
        if (editMode) {
            val holder = (cell as LinearLayout).getChildAt(0)
            cell.setOnTouchListener(DragHandler(app, fromDock, holder))
        }
        return cell
    }

    private fun makeBadge(): TextView = TextView(this).apply {
        setTextColor(Color.BLACK)
        textSize = 11.5f
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        gravity = Gravity.CENTER
        minWidth = dp(21)
        includeFontPadding = false
        setPadding(dp(6), 0, dp(6), 0)
        background = Glass.pill(0xFFF4F4F4.toInt(), dp(11).toFloat())
        elevation = dp(2).toFloat()
        visibility = View.GONE
    }

    private fun applyBadge(badge: TextView, count: Int) {
        if (count <= 0 || !badgesOn()) {
            badge.visibility = View.GONE
            return
        }
        val wasHidden = badge.visibility != View.VISIBLE
        badge.text = if (count > 999) "999+" else count.toString()
        badge.visibility = View.VISIBLE
        if (wasHidden && badge.isAttachedToWindow && anim > 0f) {
            badge.scaleX = 0f; badge.scaleY = 0f
            badge.animate().scaleX(1f).scaleY(1f).setDuration(dur(380)).setInterpolator(SPRING).start()
        }
    }

    private fun updateBadges() {
        badgeViews.removeAll { !it.second.isAttachedToWindow && it.second.parent == null }
        for ((pkg, view) in badgeViews) applyBadge(view, NotificationService.counts[pkg] ?: 0)
    }

    private fun widgetFrame(
        inner: View,
        text: String,
        onClick: (View) -> Unit,
        onLong: ((View) -> Unit)? = null,
        onRemove: (() -> Unit)? = null,
        /** Oblong: ends fully rounded (a pill / stadium) instead of 22dp corners. */
        pill: Boolean = false
    ): View {
        val inset = ((cellW() - dp(iconDp)) / 2).coerceAtLeast(0)
        val outer = FrameLayout(this).apply { clipChildren = false }
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(inset, dp(4), inset, 0)
            clipChildren = false
        }
        val box = FrameLayout(this).apply {
            // GradientDrawable caps the radius at half the height, so a huge radius = pill ends.
            background = Glass.panel(this@MainActivity, if (pill) 999f else 22f)
            outlineProvider = if (pill) object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) =
                    outline.setRoundRect(0, 0, view.width, view.height, minOf(view.width, view.height) / 2f)
            } else roundedOutline(22f)
            clipToOutline = true
        }
        box.addView(inner, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        box.addView(View(this).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(if (pill) 999 else 22).toFloat()
                setStroke(maxOf(1, dp(1)), Color.argb(if (settings.glass) 80 else 30, 255, 255, 255))
            }
        }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        wrap.addView(box, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        val lbl = label(text)
        if (!settings.showLabels) lbl.visibility = View.INVISIBLE
        wrap.addView(lbl, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            topMargin = dp(5); bottomMargin = dp(9)
        })
        outer.addView(wrap, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        if (editMode) {
            startJiggle(box)
            if (onRemove != null) {
                outer.addView(minusButton(onRemove), FrameLayout.LayoutParams(dp(22), dp(22)).apply {
                    leftMargin = inset - dp(7); topMargin = dp(4) - dp(7)
                })
            }
        } else {
            box.setOnClickListener { onClick(box) }
            if (onLong != null) box.setOnLongClickListener { onLong(box); true }
            box.setOnTouchListener(pressEffect(box, 0.95f))
        }
        return outer
    }

    private data class WidgetResizeBounds(val minW: Int, val minH: Int, val maxW: Int, val maxH: Int)

    /** Built-in widgets can resize on both axes; app widgets follow their provider's resize mode. */
    private fun widgetResizeBounds(w: PlacedWidget): WidgetResizeBounds {
        var maxW = (cols - w.col).coerceAtLeast(w.w)
        var maxH = (rowsPerPage() - w.row).coerceAtLeast(w.h)
        if (w.type == WidgetType.APPWIDGET) {
            val id = w.id.removePrefix("appwidget_").toIntOrNull()
            val info = id?.let { appWidgetManager.getAppWidgetInfo(it) }
                ?: return WidgetResizeBounds(w.w, w.h, w.w, w.h)
            val horizontal = info.resizeMode and AppWidgetProviderInfo.RESIZE_HORIZONTAL != 0
            val vertical = info.resizeMode and AppWidgetProviderInfo.RESIZE_VERTICAL != 0
            val inset = ((cellW() - dp(iconDp)) / 2).coerceAtLeast(0)
            val minWidthPx = (if (info.minResizeWidth > 0) info.minResizeWidth else info.minWidth) + inset * 2
            val minHeightPx = (if (info.minResizeHeight > 0) info.minResizeHeight else info.minHeight) + dp(38)
            val minW = ((minWidthPx + cellW() - 1) / cellW()).coerceIn(1, w.w)
            val minH = ((minHeightPx + dp(CELL_H) - 1) / dp(CELL_H)).coerceIn(1, w.h)
            if (Build.VERSION.SDK_INT >= 31) {
                if (info.maxResizeWidth > 0)
                    maxW = minOf(maxW, ((info.maxResizeWidth + inset * 2) / cellW()).coerceAtLeast(w.w))
                if (info.maxResizeHeight > 0)
                    maxH = minOf(maxH, ((info.maxResizeHeight + dp(38)) / dp(CELL_H)).coerceAtLeast(w.h))
            }
            return WidgetResizeBounds(if (horizontal) minW else w.w, if (vertical) minH else w.h,
                if (horizontal) maxW else w.w, if (vertical) maxH else w.h)
        }
        val (minW, minH) = when (w.type) {
            WidgetType.PHOTO, WidgetType.PHOTO_WIDE, WidgetType.CLOCK_ICON, WidgetType.CALENDAR_ICON -> 1 to 1
            WidgetType.BATTERY -> 1 to 2
            else -> 2 to 2
        }
        return WidgetResizeBounds(minW.coerceAtMost(w.w), minH.coerceAtMost(w.h), maxW, maxH)
    }

    private fun widgetResizeCandidate(page: Int, w: PlacedWidget, width: Int, height: Int): WidgetGrid.Placement? {
        val bounds = widgetResizeBounds(w)
        if (width !in bounds.minW..bounds.maxW || height !in bounds.minH..bounds.maxH) return null
        val occupied = widgetsFor(page).filter { widgetVisible(page, it) && widgetKey(page, it) != widgetKey(page, w) }
            .map { WidgetGrid.Placement(page, it.col, it.row, it.w, it.h) }
        return WidgetGrid.resize(WidgetGrid.Placement(page, w.col, w.row, w.w, w.h),
            width, height, bounds.minW, bounds.minH, cols, rowsPerPage(), occupied)
    }

    private fun saveWidgetSize(page: Int, w: PlacedWidget, width: Int, height: Int) {
        if (widgetResizeCandidate(page, w, width, height) == null) {
            toast("There isn't room for that widget size")
            rebuild()
            return
        }
        if (w.id.startsWith("extra_") || w.id.startsWith("appwidget_")) {
            saveAdditionalWidget(w.copy(w = width, h = height), page)
        } else {
            val positions = widgetPositions()
            positions.put(widgetKey(page, w), JSONObject().put("page", page).put("col", w.col).put("row", w.row)
                .put("w", width).put("h", height))
            prefs.edit().putString("widget_positions", positions.toString()).apply()
        }
        rebuild()
    }

    private fun showWidgetSizeMenu(page: Int, w: PlacedWidget) {
        val bounds = widgetResizeBounds(w)
        val sizes = (bounds.minH..bounds.maxH).flatMap { h ->
            (bounds.minW..bounds.maxW).mapNotNull { width -> widgetResizeCandidate(page, w, width, h) }
        }
        if (sizes.isEmpty()) { toast("There isn't room to resize this widget"); return }
        val labels = sizes.map { "${it.width} × ${it.height}" +
            if (it.width == w.w && it.height == w.h) "  ·  current" else "" }
        AlertDialog.Builder(this, R.style.Theme_Luncher_Dialog).setTitle("Widget size · columns × rows")
            .setItems(labels.toTypedArray()) { _, index ->
                val selected = sizes[index]
                if (selected.width != w.w || selected.height != w.h)
                    saveWidgetSize(page, w, selected.width, selected.height)
            }.setNegativeButton("Cancel", null).show()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun withWidgetResizeHandle(content: View, w: PlacedWidget, page: Int): View {
        val outer = FrameLayout(this).apply { clipChildren = false; clipToPadding = false }
        outer.addView(content, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        val bounds = widgetResizeBounds(w)
        if (bounds.minW == bounds.maxW && bounds.minH == bounds.maxH) return outer
        val handle = TextView(this).apply {
            text = "↘"
            textSize = 22f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            contentDescription = "Resize widget. Drag the handle or tap for sizes."
            background = Glass.circle(0xF0505055.toInt())
            elevation = dp(6).toFloat()
            setOnClickListener { showWidgetSizeMenu(page, w) }
        }
        outer.addView(handle, FrameLayout.LayoutParams(dp(36), dp(36), Gravity.BOTTOM or Gravity.END).apply {
            bottomMargin = dp(2); marginEnd = dp(2)
        })
        var startX = 0f
        var startY = 0f
        var previewW = w.w
        var previewH = w.h
        var moved = false
        var blocked = false
        val resizeListener = View.OnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    cancelHomeHold()
                    view.parent?.requestDisallowInterceptTouchEvent(true)
                    startX = event.rawX; startY = event.rawY
                    previewW = w.w; previewH = w.h; moved = false; blocked = false
                    haptic(view, HapticFeedbackConstants.CONTEXT_CLICK)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - startX
                    val dy = event.rawY - startY
                    if (hypot(dx, dy) > dp(10)) moved = true
                    val width = (w.w + (dx / cellW()).roundToInt()).coerceIn(bounds.minW, bounds.maxW)
                    val height = (w.h + (dy / dp(CELL_H)).roundToInt()).coerceIn(bounds.minH, bounds.maxH)
                    if (widgetResizeCandidate(page, w, width, height) != null) {
                        previewW = width; previewH = height; blocked = false
                        outer.layoutParams = outer.layoutParams.apply { this.width = cellW() * width; this.height = dp(CELL_H) * height }
                    } else blocked = true
                    true
                }
                MotionEvent.ACTION_UP -> {
                    view.parent?.requestDisallowInterceptTouchEvent(false)
                    if (!moved) view.performClick()
                    else if (previewW != w.w || previewH != w.h) saveWidgetSize(page, w, previewW, previewH)
                    else if (blocked) toast("Choose a space without another widget")
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    view.parent?.requestDisallowInterceptTouchEvent(false)
                    outer.layoutParams = outer.layoutParams.apply { width = cellW() * w.w; height = dp(CELL_H) * w.h }
                    true
                }
                else -> true
            }
        }
        handle.tag = resizeListener
        handle.setOnTouchListener(resizeListener)
        return outer
    }

    private fun buildWidget(w: PlacedWidget, page: Int): View {
        val remove = { hideWidget(page, w) }
        return when (w.type) {
            WidgetType.CLOCK_ICON ->
                if (w.w == 1 && w.h == 1) makeCell(AnalogClockView(this), "Clock", null,
                    { openIntent(Intent(AlarmClock.ACTION_SHOW_ALARMS), it) }, null, remove)
                else widgetFrame(AnalogClockView(this), "Clock",
                    { openIntent(Intent(AlarmClock.ACTION_SHOW_ALARMS), it) }, null, remove)
            WidgetType.CALENDAR_ICON ->
                if (w.w == 1 && w.h == 1) makeCell(CalendarIconView(this), "Calendar", null,
                    { openCalendar(it) }, null, remove)
                else widgetFrame(CalendarIconView(this), "Calendar", { openCalendar(it) }, null, remove)
            WidgetType.PHOTO ->
                widgetFrame(photoWidget(w.id), "Photo", { v -> photoTapped(w.id, v) }, { v -> showPhotoMenu(w.id, v) }, remove)
            WidgetType.CALENDAR ->
                widgetFrame(calendarWidget(), "Calendar", { openCalendar(it) }, null, remove)
            WidgetType.BATTERY ->
                widgetFrame(batteryWidget(), "Batteries", { openIntent(Intent(Intent.ACTION_POWER_USAGE_SUMMARY), it) }, null, remove)
            WidgetType.MUSIC ->
                widgetFrame(musicWidget(), "Now Playing",
                    { musicTapped(it) }, null, remove,
                    pill = settings.musicOblong && settings.musicStyle == "classic")
            WidgetType.APPWIDGET -> buildAppWidget(w, remove)
            WidgetType.DIGITAL_CLOCK ->
                widgetFrame(MoreWidgets.digitalClock(this, false), "Digital Clock",
                    { openIntent(Intent(AlarmClock.ACTION_SHOW_ALARMS), it) }, null, remove)
            WidgetType.WIDE_CLOCK ->
                widgetFrame(MoreWidgets.digitalClock(this, true), "Wide Clock",
                    { openIntent(Intent(AlarmClock.ACTION_SHOW_ALARMS), it) }, null, remove)
            WidgetType.WORLD_CLOCK ->
                widgetFrame(MoreWidgets.worldClocks(this, WorldClockZones.resolve(prefs.getString("world_zones_${w.id}", null))),
                    "World Clock", { editWorldClock(w.id) }, null, remove)
            WidgetType.DATE_CARD ->
                widgetFrame(MoreWidgets.dateCard(this), "Date", { openCalendar(it) }, null, remove)
            WidgetType.QUOTE ->
                widgetFrame(MoreWidgets.quoteCard(this, prefs.getString("quote_${w.id}", null) ?: "One day at a time."),
                    "Quote", { editQuote(w.id) }, null, remove)
            WidgetType.PHOTO_WIDE ->
                widgetFrame(photoWidget(w.id), "Wide Photo", { v -> photoTapped(w.id, v) }, { v -> showPhotoMenu(w.id, v) }, remove)
            WidgetType.IPOD ->
                widgetFrame(IPodView(this, { currentMusic() }, { currentIslandMedia() }, { openMusicApp() }, { step -> stepVolume(step) }),
                    "iPod", { }, null, remove)
            WidgetType.TIMER ->
                widgetFrame(TimerWidget.create(this), "Timer", { v -> showTimerPicker(v) }, null, remove)
            WidgetType.WEATHER -> {
                InfoData.loadWeather(this); InfoData.refreshWeather(this)
                widgetFrame(InfoViews.todayWeather(this), "Weather", { InfoData.refreshWeather(this, force = true); toast("Updating weather…") }, null, remove)
            }
            WidgetType.STEPS -> {
                InfoData.startSteps(this)
                widgetFrame(FrameLayout(this).apply {
                    setPadding(dp(16), dp(14), dp(16), dp(14))
                    addView(InfoViews.stepsCard(this@MainActivity), FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.CENTER_VERTICAL))
                }, "Steps", { }, null, remove)
            }
            WidgetType.STACK -> {
                // Smart Stack: swipe up/down between date, batteries, timer and clock.
                val stack = SmartStackView(this, listOf(
                    MoreWidgets.dateCard(this), batteryWidget(), TimerWidget.create(this),
                    MoreWidgets.digitalClock(this, false)
                ))
                widgetFrame(stack, "Smart Stack", { }, null, remove)
            }
        }
    }

    private fun editQuote(id: String) {
        val input = glassField(prefs.getString("quote_$id", null) ?: "One day at a time.").apply {
            isSingleLine = false
            maxLines = 4
            filters = arrayOf(android.text.InputFilter.LengthFilter(140))
            setSelection(text.length)
        }
        AlertDialog.Builder(this, R.style.Theme_Luncher_Dialog).setTitle("Edit quote").setView(wrapField(input))
            .setPositiveButton("Save") { _, _ ->
                val value = input.text.toString().trim().ifEmpty { "One day at a time." }
                prefs.edit().putString("quote_$id", value).apply()
                refreshPages()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun editWorldClock(id: String) {
        val cities = WorldClockZones.choices
        val saved = WorldClockZones.resolve(prefs.getString("world_zones_$id", null))
            .map { it.key }.toSet()
        val selected = BooleanArray(cities.size) { cities[it].key in saved }
        AlertDialog.Builder(this, R.style.Theme_Luncher_Dialog).setTitle("Choose up to four cities")
            .setMultiChoiceItems(cities.map { it.label }.toTypedArray(), selected) { dialog, which, checked ->
                selected[which] = checked
                if (checked && selected.count { it } > 4) {
                    selected[which] = false
                    (dialog as AlertDialog).listView.setItemChecked(which, false)
                    toast("Choose up to four cities")
                } else if (!checked && selected.none { it }) {
                    selected[which] = true
                    (dialog as AlertDialog).listView.setItemChecked(which, true)
                    toast("Choose at least one city")
                }
            }
            .setPositiveButton("Save") { _, _ ->
                val ids = cities.filterIndexed { index, _ -> selected[index] }.joinToString("|") { it.key }
                prefs.edit().putString("world_zones_$id", ids).apply()
                refreshPages()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun buildAppWidget(w: PlacedWidget, remove: () -> Unit): View {
        val id = w.id.removePrefix("appwidget_").toIntOrNull()
        val info = id?.let { appWidgetManager.getAppWidgetInfo(it) }
        if (id == null || info == null) {
            return widgetFrame(TextView(this).apply {
                text = "Widget unavailable"
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
            }, "Widget", {}, null, remove)
        }
        val hostView = runCatching { appWidgetHost.createView(this, id, info) }.getOrElse {
            return widgetFrame(TextView(this).apply {
                text = "Widget unavailable"
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
            }, "Widget", {}, null, remove)
        }
        val inset = ((cellW() - dp(iconDp)) / 2).coerceAtLeast(0)
        val density = resources.displayMetrics.density
        val widthDp = ((cellW() * w.w - inset * 2) / density).roundToInt().coerceAtLeast(1)
        val heightDp = ((dp(CELL_H) * w.h - dp(38)) / density).roundToInt().coerceAtLeast(1)
        runCatching { hostView.updateAppWidgetSize(null, widthDp, heightDp, widthDp, heightDp) }
        val name = runCatching { info.loadLabel(packageManager) }.getOrDefault("Widget")
        return widgetFrame(hostView, name, {}, null, remove)
    }

    private fun photoWidget(id: String): View {
        val frame = FrameLayout(this)
        // Spatial Scene: the photo tilts in 3D with the phone (like iOS spatial photos).
        val img = SpatialImageView(this).apply {
            spatial = prefs.getBoolean("photo_spatial_$id", true) && anim > 0f
            depth = 0.9f
        }
        if (prefs.getBoolean("photo_bw_$id", true)) img.filter = IconFactory.grayscale()
        val hint = TextView(this).apply {
            text = "+\nAdd Photo"
            setTextColor(0xCCFFFFFF.toInt())
            textSize = 14f
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        frame.addView(img, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        frame.addView(hint, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        val cached = photoCache[id]
        val uri = prefs.getString("photo_$id", null)
        if (cached != null) {
            img.bitmap = cached; hint.visibility = View.GONE
        } else if (uri != null) {
            hint.visibility = View.GONE
            io.execute {
                val bmp = decodeSampled(Uri.parse(uri), dp(360))
                mainHandler.post {
                    if (bmp != null) {
                        photoCache[id] = bmp
                        img.alpha = 0f
                        img.bitmap = bmp
                        img.animate().alpha(1f).setDuration(dur(300)).setInterpolator(EASE).start()
                    } else {
                        hint.visibility = View.VISIBLE
                    }
                }
            }
        }
        return frame
    }

    private fun calendarWidget(): View {
        val today = LocalDate.now()
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(12), dp(10))
        }
        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        left.addView(TextView(this).apply {
            text = today.dayOfMonth.toString()
            setTextColor(Color.WHITE)
            textSize = 52f
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
            gravity = Gravity.CENTER
            includeFontPadding = false
        })
        left.addView(TextView(this).apply {
            text = String.format(
                Locale.getDefault(), "%d/%02d %s", today.year, today.monthValue,
                today.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
            )
            setTextColor(0xE6FFFFFF.toInt())
            textSize = 12f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, 0)
        })
        row.addView(left, LinearLayout.LayoutParams(0, MATCH_PARENT, 0.9f))

        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        right.addView(TextView(this).apply {
            text = today.month.getDisplayName(TextStyle.FULL, Locale.getDefault())
            setTextColor(Color.WHITE)
            textSize = 15f
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD_ITALIC)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val grid = GridLayout(this).apply { columnCount = 7 }
        val heads = listOf("M", "T", "W", "T", "F", "S", "S")
        val first = today.withDayOfMonth(1)
        val lead = first.dayOfWeek.value - 1
        val days = today.lengthOfMonth()
        val rowsNeeded = (lead + days + 6) / 7
        val cells = heads.map { it to 0 } +
            (0 until rowsNeeded * 7).map { i ->
                val d = i - lead + 1
                (if (d in 1..days) d.toString() else "") to (if (d == today.dayOfMonth) 2 else 1)
            }
        val accent = if (settings.tint == Color.WHITE) Color.WHITE else settings.tint
        for ((txt, kind) in cells) {
            val tv = TextView(this).apply {
                text = txt
                textSize = 9f
                gravity = Gravity.CENTER
                includeFontPadding = false
                typeface = Typeface.create(Typeface.SERIF, if (kind == 0) Typeface.BOLD else Typeface.NORMAL)
                when (kind) {
                    0 -> setTextColor(0xB3FFFFFF.toInt())
                    2 -> {
                        setTextColor(if (accent == Color.WHITE) Color.BLACK else Color.WHITE)
                        background = Glass.circle(accent)
                    }
                    else -> setTextColor(0xE6FFFFFF.toInt())
                }
            }
            val lp = GridLayout.LayoutParams(
                GridLayout.spec(GridLayout.UNDEFINED, 1f),
                GridLayout.spec(GridLayout.UNDEFINED, 1f)
            ).apply { width = 0; height = 0; setMargins(dp(1), dp(1), dp(1), dp(1)) }
            grid.addView(tv, lp)
        }
        right.addView(grid, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply { topMargin = dp(4) })
        row.addView(right, LinearLayout.LayoutParams(0, MATCH_PARENT, 1.4f))
        return row
    }

    private fun batteryWidget(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(12))
        }
        val ring = BatteryRingView(this).apply {
            level = batteryLevel
            charging = batteryCharging
        }
        col.addView(ring, LinearLayout.LayoutParams(dp(58), dp(58)))
        col.addView(Space(this), LinearLayout.LayoutParams(0, 0, 1f))
        val pct = TextView(this).apply {
            text = "$batteryLevel%"
            setTextColor(Color.WHITE)
            textSize = 40f
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            includeFontPadding = false
        }
        col.addView(pct, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        batteryRing = ring
        batteryText = pct
        return col
    }

    private fun roundIconButton(res: Int, sizeDp: Int, filled: Boolean, onClick: () -> Unit): ImageView =
        ImageView(this).apply {
            setImageResource(res)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            val pad = dp(sizeDp / 4)
            setPadding(pad, pad, pad, pad)
            if (filled) {
                background = Glass.circle(Color.WHITE)
                setColorFilter(Color.BLACK)
            } else {
                setColorFilter(0xF2FFFFFF.toInt())
            }
            setOnClickListener { haptic(this, HapticFeedbackConstants.CONTEXT_CLICK); onClick() }
            setOnTouchListener(pressEffect(this, 0.82f))
        }

    // ================================================================ Now Playing styles
    private var vinylSpin: android.animation.ObjectAnimator? = null
    private var playerFrame: FrameLayout? = null
    private var playerStyleNow: PlayerStyles.Style? = null
    private var playerLyrics: LyricsView? = null
    private var playerBlur: ImageView? = null
    private var lastBlurArt: android.graphics.Bitmap? = null
    private var playerFg: ((Int) -> Unit)? = null
    private var lastArtForTone: android.graphics.Bitmap? = null
    private val lyricsTicker = object : Runnable {
        override fun run() {
            updateLyricsLine()
            if (playerLyrics != null) mainHandler.postDelayed(this, 200)
        }
    }

    /** Current song position, counting time since the music app last reported it. */
    private fun songPosition(): Long {
        val st = mediaController?.playbackState ?: return 0
        if (st.state != PlaybackState.STATE_PLAYING) return st.position
        val since = android.os.SystemClock.elapsedRealtime() - st.lastPositionUpdateTime
        return st.position + (since * st.playbackSpeed).toLong()
    }

    private fun updateLyricsLine() {
        val tv = playerLyrics ?: return
        if (!Lyrics.enabled) return
        // Showing the last song (music app closed): no live position, so no lyrics until you press play.
        if (mediaController?.metadata == null) { tv.clear(""); return }
        val v = musicViews ?: return
        val title = v.title.text.toString(); val artist = v.artist.text.toString()
        if (title.isBlank() || v.body.visibility != View.VISIBLE) { tv.clear(); return }
        val song = Lyrics.cached(title, artist)
        if (song == null) {
            val dur = mediaController?.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
            Lyrics.fetch(title, artist, dur) { }
            tv.clear("♪")
            return
        }
        tv.show(song, songPosition())
    }

    /** Album tone: the background takes the album art's average colour. */
    private fun applyPlayerTone(art: android.graphics.Bitmap?) {
        val st = playerStyleNow ?: return
        if (art !== lastBlurArt) { lastBlurArt = art; playerBlur?.takeIf { it.visibility == View.VISIBLE }?.setImageBitmap(blurredArt(art)) }
        val f = playerFrame ?: return
        if (st.tone != "album" || art === lastArtForTone) return
        lastArtForTone = art
        val c = art?.let { runCatching { android.graphics.Bitmap.createScaledBitmap(it, 1, 1, true).getPixel(0, 0) }.getOrNull() } ?: 0xFF3A3A3C.toInt()
        val dark = IconFactory.blend(c, Color.BLACK, 0.35f)
        val lum = (Color.red(dark) * 0.3 + Color.green(dark) * 0.59 + Color.blue(dark) * 0.11)
        val bgD = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(IconFactory.blend(c, Color.WHITE, 0.08f), dark)).apply {
            cornerRadius = dp(22).toFloat() }
        if (st.art == "poster") (f.getChildAt(2) as? View)?.background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
            intArrayOf((dark and 0x00FFFFFF) or 0xE6000000.toInt(), 0x22000000)).apply { cornerRadius = dp(22).toFloat() }
        else f.background = bgD
        playerFg?.invoke(if (lum > 165) 0xFF1C1C1E.toInt() else Color.WHITE)
    }

    /** Builds the Now Playing widget in the chosen style (everything but "Classic"). */
    private fun styledPlayer(st: PlayerStyles.Style): View {
        vinylSpin?.cancel(); vinylSpin = null
        lastArtForTone = null
        val light = st.tone == "light"
        var fg = if (light) 0xFF1C1C1E.toInt() else Color.WHITE
        val frame = FrameLayout(this).apply {
            background = when (st.tone) {
                "light" -> GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFFF7F7FA.toInt(), 0xFFDCDCE2.toInt()))
                "glass" -> null
                "accent" -> GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(
                    IconFactory.blend(ThemeEngine.accent, Color.WHITE, 0.1f), IconFactory.blend(ThemeEngine.accent, Color.BLACK, 0.45f)))
                "album" -> GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF5A5A60.toInt(), 0xFF2C2C2E.toInt()))
                else -> GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF6E6E73.toInt(), 0xFF2C2C2E.toInt()))
            }?.apply { (this as GradientDrawable).cornerRadius = dp(22).toFloat() }
        }
        // Poster: the album art fills the widget behind a soft shade.
        val bg = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            visibility = if (st.art == "poster") View.VISIBLE else View.GONE
            if (st.art == "poster") { outlineProvider = roundedOutline(22f); clipToOutline = true }
        }
        frame.addView(bg, if (st.art == "poster") FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT) else FrameLayout.LayoutParams(1, 1))
        // Spotify / ArchiveTune look: a soft blurred copy of the album art glowing behind the player.
        val blurLayer = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP; alpha = if (st.tone == "glass") 0.75f else 0.45f
            outlineProvider = roundedOutline(22f); clipToOutline = true
            visibility = if (st.art != "poster" && st.tone != "light") View.VISIBLE else View.GONE
        }
        frame.addView(blurLayer, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        playerBlur = blurLayer
        frame.addView(View(this).apply {
            visibility = if (st.art == "poster") View.VISIBLE else View.GONE
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                if (light) intArrayOf(0xF2FFFFFF.toInt(), 0x66FFFFFF) else intArrayOf(0xF0000000.toInt(), 0x33000000)).apply { cornerRadius = dp(22).toFloat() }
        }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        // The art
        val art = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            outlineProvider = if (st.art == "square") roundedOutline(14f) else object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) = outline.setOval(0, 0, view.width, view.height)
            }
            clipToOutline = true
            setImageResource(R.drawable.ic_music); setColorFilter(0xB3FFFFFF.toInt())
        }
        val artHolder: View? = when (st.art) {
            "vinyl" -> {
                val rec = FrameLayout(this)
                val disc = FrameLayout(this).apply {
                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFF111111.toInt()); setStroke(dp(2), 0xFF2A2A2A.toInt()) }
                    elevation = dp(6).toFloat()
                }
                // Grooves
                disc.addView(View(this).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0); setStroke(dp(1), 0x22FFFFFF) } },
                    FrameLayout.LayoutParams(dp(78), dp(78), Gravity.CENTER))
                disc.addView(art, FrameLayout.LayoutParams(dp(50), dp(50), Gravity.CENTER))
                rec.addView(disc, FrameLayout.LayoutParams(dp(92), dp(92), Gravity.CENTER))
                // Tone arm resting on the edge of the record
                rec.addView(View(this).apply {
                    background = GradientDrawable().apply { cornerRadius = dp(2).toFloat(); setColor(if (light) 0xFF8E8E93.toInt() else 0xFFD1D1D6.toInt()) }
                    pivotX = dp(2).toFloat(); pivotY = 0f; rotation = 28f; elevation = dp(8).toFloat()
                }, FrameLayout.LayoutParams(dp(4), dp(56), Gravity.TOP or Gravity.END).apply { topMargin = dp(9); marginEnd = dp(9) })
                rec.addView(View(this).apply {
                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (light) 0xFFC7C7CC.toInt() else 0xFF8E8E93.toInt()) }
                    elevation = dp(9).toFloat()
                }, FrameLayout.LayoutParams(dp(16), dp(16), Gravity.TOP or Gravity.END).apply { topMargin = dp(2); marginEnd = dp(2) })
                vinylSpin = android.animation.ObjectAnimator.ofFloat(disc, View.ROTATION, 0f, 360f).apply {
                    duration = 6000; repeatCount = android.animation.ValueAnimator.INFINITE; interpolator = android.view.animation.LinearInterpolator()
                }
                rec.layoutParams = LinearLayout.LayoutParams(dp(104), dp(104)); rec
            }
            "square" -> art.apply { elevation = dp(6).toFloat(); layoutParams = LinearLayout.LayoutParams(dp(88), dp(88)) }
            "round" -> {
                val r = FrameLayout(this).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor((fg and 0x00FFFFFF) or 0x22000000) } }
                r.addView(art, FrameLayout.LayoutParams(dp(80), dp(80), Gravity.CENTER))
                vinylSpin = android.animation.ObjectAnimator.ofFloat(art, View.ROTATION, 0f, 360f).apply {
                    duration = 12000; repeatCount = android.animation.ValueAnimator.INFINITE; interpolator = android.view.animation.LinearInterpolator()
                }
                r.layoutParams = LinearLayout.LayoutParams(dp(92), dp(92)); r
            }
            else -> null
        }
        // Words, buttons and lyrics
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val source = TextView(this).apply { textSize = 11f; maxLines = 1 }
        val title = TextView(this).apply {
            textSize = 16f; typeface = Typeface.create("sans-serif-medium", Typeface.BOLD); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }
        val artist = TextView(this).apply { textSize = 13f; maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        texts.addView(source); texts.addView(title); texts.addView(artist)
        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val ctlViews = ArrayList<ImageView>()
        fun ctl(res: Int, onClick: () -> Unit) = ImageView(this).apply {
            setImageResource(res); scaleType = ImageView.ScaleType.CENTER_INSIDE
            setOnClickListener {
                haptic(this, HapticFeedbackConstants.CLOCK_TICK); SoundFX.play(this@MainActivity, "tick")
                scaleX = 0.8f; scaleY = 0.8f; Motion.scaleTo(this, 1f, Motion.BOUNCY); onClick()
            }
        }.also { ctlViews += it }
        controls.addView(ctl(R.drawable.ic_prev) { mediaController?.transportControls?.skipToPrevious() ?: mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS) },
            LinearLayout.LayoutParams(dp(32), dp(32)))
        val play = ctl(R.drawable.ic_play) { togglePlay() }
        controls.addView(play, LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginStart = dp(10); marginEnd = dp(10) })
        controls.addView(ctl(R.drawable.ic_next) { mediaController?.transportControls?.skipToNext() ?: mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT) },
            LinearLayout.LayoutParams(dp(32), dp(32)))
        texts.addView(controls, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(4) })
        val lyrics = LyricsView(this).apply { visibility = if (Lyrics.enabled) View.VISIBLE else View.GONE }
        texts.addView(lyrics, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(4) })
        // With lyrics on, the app name makes room for them (it's still on the island).
        if (Lyrics.enabled) source.visibility = View.GONE

        val textsLp = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        if (artHolder == null) { body.addView(texts, textsLp) }
        else if (st.artRight) { body.addView(texts, textsLp.apply { marginEnd = dp(12) }); body.addView(artHolder) }
        else { body.addView(artHolder); body.addView(texts, textsLp.apply { marginStart = dp(12) }) }
        frame.addView(body, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        val hint = TextView(this).apply { textSize = 14f; gravity = Gravity.CENTER; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
        frame.addView(hint, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        // Colours that follow the background (light text on dark, dark text on light).
        val paint: (Int) -> Unit = { c ->
            fg = c
            title.setTextColor(c); hint.setTextColor(c)
            artist.setTextColor((c and 0x00FFFFFF) or 0xA6000000.toInt())
            source.setTextColor((c and 0x00FFFFFF) or 0x80000000.toInt())
            lyrics.color = c
            for (b in ctlViews) {
                b.setColorFilter(c)
                b.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor((c and 0x00FFFFFF) or 0x1F000000) }
            }
        }
        paint(fg)
        playerFg = paint
        playerFrame = frame; playerStyleNow = st; playerLyrics = lyrics
        musicViews = MusicViews(art, bg, title, artist, source, play, hint, body)
        updateMusic()
        mainHandler.removeCallbacks(lyricsTicker); mainHandler.post(lyricsTicker)
        return frame
    }

    private fun musicWidget(): View {
        vinylSpin?.cancel(); vinylSpin = null
        playerFrame = null; playerStyleNow = null; playerLyrics = null; playerFg = null; playerBlur = null; lastBlurArt = null
        val st = PlayerStyles.playerStyle(settings.musicStyle)
        if (st.art != "classic") return styledPlayer(st)
        val frame = FrameLayout(this)
        val bg = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            alpha = 0.32f
            colorFilter = IconFactory.grayscale(0.9f)
        }
        frame.addView(bg, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        val oblong = settings.musicOblong
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // Oblong: keep everything clear of the round ends.
            if (oblong) setPadding(dp(30), dp(16), dp(30), dp(14)) else setPadding(dp(14), dp(14), dp(14), dp(12))
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val art = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            outlineProvider = if (oblong) object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) = outline.setOval(0, 0, view.width, view.height)
            } else roundedOutline(10f)
            clipToOutline = true
            background = Glass.panel(this@MainActivity, if (oblong) 999f else 10f)
            setImageResource(R.drawable.ic_music)
            setColorFilter(0xB3FFFFFF.toInt())
        }
        top.addView(art, LinearLayout.LayoutParams(dp(58), dp(58)))
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val title = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 15f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val artist = TextView(this).apply {
            setTextColor(0xB3FFFFFF.toInt())
            textSize = 13f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        texts.addView(title)
        texts.addView(artist)
        top.addView(texts, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = dp(12) })
        val play = roundIconButton(R.drawable.ic_play, 44, true) { togglePlay() }
        top.addView(play, LinearLayout.LayoutParams(dp(44), dp(44)))
        body.addView(top, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        body.addView(Space(this), LinearLayout.LayoutParams(0, 0, 1f))

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        controls.addView(roundIconButton(R.drawable.ic_prev, 36, false) {
            mediaController?.transportControls?.skipToPrevious() ?: mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
        }, LinearLayout.LayoutParams(dp(36), dp(36)))
        controls.addView(roundIconButton(R.drawable.ic_next, 36, false) {
            mediaController?.transportControls?.skipToNext() ?: mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
        }, LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginStart = dp(10) })
        val source = TextView(this).apply {
            setTextColor(0x99FFFFFF.toInt())
            textSize = 12f
            gravity = Gravity.END
            maxLines = 1
        }
        controls.addView(source, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        body.addView(controls, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        frame.addView(body, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        val hint = TextView(this).apply {
            setTextColor(0xD9FFFFFF.toInt())
            textSize = 14f
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setCompoundDrawablesRelativeWithIntrinsicBounds(0, R.drawable.ic_music, 0, 0)
            compoundDrawablePadding = dp(6)
        }
        frame.addView(hint, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        musicViews = MusicViews(art, bg, title, artist, source, play, hint, body)
        updateMusic()
        return frame
    }

    /** Resize around the centre while keeping the dock's height and page geometry unchanged. */
    private fun resizeDock(target: Int) {
        if (target == dockTargetWidth) return
        dockTargetWidth = target
        dockWidthAnimator?.cancel()
        dockWidthAnimator = null
        val lp = dockRow.layoutParams as FrameLayout.LayoutParams
        val current = if (lp.width > 0) lp.width else dockRow.width
        if (!dockRow.isLaidOut || current <= 0 || anim <= 0f) {
            dockWidthSpring?.cancel()
            lp.width = target
            dockRow.layoutParams = lp
            return
        }
        // Spring the width so the glass pill stretches and settles like iOS.
        dockWidthSpring?.cancel()
        val holder = androidx.dynamicanimation.animation.FloatValueHolder(current.toFloat())
        dockWidthSpring = androidx.dynamicanimation.animation.SpringAnimation(holder).apply {
            spring = androidx.dynamicanimation.animation.SpringForce(target.toFloat()).apply {
                stiffness = (Motion.FLUID.stiffness / (anim * anim)).coerceIn(30f, 10000f)
                dampingRatio = 0.78f
            }
            setMinimumVisibleChange(1f)
            addUpdateListener { _, value, _ ->
                val params = dockRow.layoutParams as FrameLayout.LayoutParams
                params.width = value.toInt()
                dockRow.layoutParams = params
            }
            start()
        }
    }

    private fun buildDock(dock: List<AppInfo>) {
        val keys = dock.map { it.key }
        val animateItems = dockRow.isLaidOut && anim > 0f && keys != displayedDockKeys
        val oldCenters = mutableMapOf<String, Int>()
        if (animateItems) {
            for (i in 0 until dockRow.childCount) {
                val child = dockRow.getChildAt(i)
                val key = child.tag as? String ?: continue
                val loc = IntArray(2)
                child.getLocationOnScreen(loc)
                oldCenters[key] = loc[0] + child.width / 2
            }
        }
        displayedDockKeys = keys
        val available = ((if (content.width > 0) content.width else resources.displayMetrics.widthPixels) -
            dp(20)).coerceAtLeast(1)
        val slot = if (dock.isEmpty()) 0 else minOf(dp(iconDp + 12),
            ((available - dp(16)) / dock.size).coerceAtLeast(1))
        val target = if (dock.isEmpty()) minOf(dp(230), available) else minOf(available, dp(16) + slot * dock.size)
        val dockIcon = if (dock.isEmpty()) dp(iconDp) else minOf(dp(iconDp * settings.dockIconPct / 100), (slot - dp(4)).coerceAtLeast(1))
        dockRow.minimumHeight = dp(iconDp + 26)
        dockRow.removeAllViews()
        if (dock.isEmpty()) {
            dockRow.addView(TextView(this).apply {
                text = if (editMode) "Drag apps here" else "Long-press an app → Add to Dock"
                setTextColor(0xB3FFFFFF.toInt())
                gravity = Gravity.CENTER
                textSize = 13f
            }, LinearLayout.LayoutParams(MATCH_PARENT, dp(iconDp)))
            resizeDock(target)
            return
        }
        for (app in dock) {
            val cell = makeAppCell(app, fromDock = true, showLabel = false)
            (cell as LinearLayout).apply {
                setPadding(0, 0, 0, 0)
                tag = app.key
                contentDescription = app.label
                val icon = getChildAt(0)
                val iconParams = icon.layoutParams as LinearLayout.LayoutParams
                iconParams.width = dockIcon
                iconParams.height = dockIcon
                icon.layoutParams = iconParams
            }
            dockRow.addView(cell, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            if (animateItems) {
                val oldCenter = oldCenters[app.key]
                if (oldCenter == null) {
                    cell.alpha = 0f
                    cell.scaleX = 0.8f
                    cell.scaleY = 0.8f
                    cell.doOnLayout {
                        if (cell.isAttachedToWindow) {
                            Motion.scaleTo(cell, 1f, Motion.BOUNCY)
                            Motion.alphaTo(cell, 1f, Motion.CALM)
                        }
                    }
                } else {
                    cell.doOnLayout {
                        if (cell.isAttachedToWindow) {
                            val loc = IntArray(2)
                            cell.getLocationOnScreen(loc)
                            val dx = (oldCenter - loc[0] - cell.width / 2).toFloat()
                            Motion.spring(cell, androidx.dynamicanimation.animation.DynamicAnimation.TRANSLATION_X,
                                0f, Motion.FLUID, start = dx)
                        }
                    }
                }
            }
        }
        resizeDock(target)
    }

    // ================================================================ edit mode & drag

    private fun enterEditMode(refresh: Boolean = true) {
        if (editMode) return
        if (menuLayer != null) dismissMenu(immediate = true)
        if (searchOverlay.visibility == View.VISIBLE) closeSearch(false)
        editMode = true
        haptic(root)
        mainHandler.removeCallbacks(showSearchPill)
        searchPill.animate().cancel()
        dots.animate().cancel()
        searchPill.visibility = View.GONE
        dots.visibility = View.GONE
        editBar.visibility = View.VISIBLE
        editBar.alpha = 0f
        editBar.animate().alpha(1f).setDuration(dur(200)).start()
        if (refresh) {
            refreshPages()
            buildDock(dockApps())
        }
    }

    private fun exitEditMode() {
        if (!editMode) return
        editMode = false
        editBar.animate().cancel()
        editBar.visibility = View.GONE
        searchPill.visibility = View.VISIBLE
        dots.visibility = View.VISIBLE
        setPillMode(search = true)
        refreshPages()
        buildDock(dockApps())
    }

    private fun removeFromHome(app: AppInfo, fromDock: Boolean) {
        if (folderId(app) != null) {
            ungroupFolder(app)
            return
        }
        if (fromDock) {
            setDock(dockKeys() - app.key)
            return
        }
        settings.hiddenApps = settings.hiddenApps + app.key
        toast("${app.label} is still in the App Library")
        rebuild()
    }

    private fun hideWidget(page: Int, w: PlacedWidget) {
        if (w.id.startsWith("extra_") || w.id.startsWith("appwidget_")) {
            val json = additionalWidgetData()
            json.remove(w.id)
            prefs.edit().putString("additional_widgets", json.toString()).apply()
            if (w.type == WidgetType.APPWIDGET) {
                w.id.removePrefix("appwidget_").toIntOrNull()?.let { id ->
                    runCatching { appWidgetHost.deleteAppWidgetId(id) }
                }
            } else if (w.type == WidgetType.PHOTO || w.type == WidgetType.PHOTO_WIDE) {
                prefs.edit().remove("photo_${w.id}").remove("photo_bw_${w.id}").apply()
                photoCache.remove(w.id)
            } else if (w.type == WidgetType.QUOTE) {
                prefs.edit().remove("quote_${w.id}").apply()
            } else if (w.type == WidgetType.WORLD_CLOCK) {
                prefs.edit().remove("world_zones_${w.id}").apply()
            }
            rebuild()
            return
        }
        settings.hiddenWidgets = settings.hiddenWidgets + widgetKey(page, w)
        rebuild()
    }

    private fun showRestoreWidgetsMenu(anchor: View) = showWidgetGallery()

    /** Cell size (columns x rows) a built-in widget uses when added. */
    private fun builtInSize(type: WidgetType): Pair<Int, Int> {
        val width = when (type) {
            WidgetType.CLOCK_ICON, WidgetType.CALENDAR_ICON -> 1
            WidgetType.CALENDAR, WidgetType.MUSIC, WidgetType.PHOTO_WIDE,
            WidgetType.WIDE_CLOCK, WidgetType.WORLD_CLOCK, WidgetType.WEATHER, WidgetType.STEPS -> cols
            else -> (cols / 2).coerceAtLeast(1)
        }
        val height = when (type) {
            WidgetType.CLOCK_ICON, WidgetType.CALENDAR_ICON -> 1
            WidgetType.IPOD -> 3
            else -> 2
        }
        return width to height
    }

    /**
     * iOS-style widget gallery: a sheet slides up with a live preview of every widget,
     * its name and size, and an Add Widget button. Search filters the list.
     */
    private fun showWidgetGallery() {
        if (menuLayer != null) dismissMenu(immediate = true)
        val gallery = listOf(
            Triple(WidgetType.PHOTO, "Photo", "A photo you pick, with an optional 3D depth effect"),
            Triple(WidgetType.PHOTO_WIDE, "Wide Photo", "A panoramic photo across the screen"),
            Triple(WidgetType.CLOCK_ICON, "Clock", "Analog clock, small"),
            Triple(WidgetType.DIGITAL_CLOCK, "Digital Clock", "Big time and date"),
            Triple(WidgetType.WIDE_CLOCK, "Wide Digital Clock", "Time, date and day across the screen"),
            Triple(WidgetType.WORLD_CLOCK, "World Clock", "Times in the cities you choose"),
            Triple(WidgetType.CALENDAR_ICON, "Calendar Icon", "Today's date, small"),
            Triple(WidgetType.CALENDAR, "Calendar", "The month with today highlighted"),
            Triple(WidgetType.DATE_CARD, "Date Card", "Day and date at a glance"),
            Triple(WidgetType.QUOTE, "Quote", "Your own words on the Home Screen"),
            Triple(WidgetType.BATTERY, "Batteries", "Phone and headphone battery levels"),
            Triple(WidgetType.MUSIC, "Now Playing", "What's playing, with controls"),
            Triple(WidgetType.STACK, "Smart Stack", "Swipe up and down between widgets"),
            Triple(WidgetType.TIMER, "Timer", "Start a timer; it counts down live in the island"),
            Triple(WidgetType.IPOD, "iPod", "Classic click wheel: spin for volume, tap to play, skip and open music"),
            Triple(WidgetType.WEATHER, "Weather", "Today's temperature, high/low and the next two days"),
            Triple(WidgetType.STEPS, "Steps", "Today's steps and calories with your last 7 days")
        )
        val layer = FrameLayout(this).apply { setBackgroundColor(Color.argb(110, 0, 0, 0)); isClickable = true }
        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            background = GradientDrawable().apply {
                cornerRadii = floatArrayOf(dp(34).toFloat(), dp(34).toFloat(), dp(34).toFloat(), dp(34).toFloat(), 0f, 0f, 0f, 0f)
                setColor(0xF51C1C1E.toInt())
            }
            setPadding(0, dp(10), 0, 0)
        }
        var closed = false
        fun close() {
            if (closed) return
            closed = true
            animateBlur(0f, dur(220))
            sheet.animate().translationY(sheet.height.toFloat()).setDuration(dur(260)).setInterpolator(EASE).start()
            layer.animate().alpha(0f).setDuration(dur(260)).withEndAction {
                root.removeView(layer)
                // Previews borrowed the live music/battery views; give them back to the Home Screen.
                rebuild()
            }.start()
        }
        layer.setOnClickListener { close() }
        // Grab handle + header
        sheet.addView(View(this).apply { background = Glass.pill(0x55FFFFFF, dp(3).toFloat()) },
            LinearLayout.LayoutParams(dp(40), dp(5)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        val header = FrameLayout(this).apply { setPadding(dp(20), dp(10), dp(20), 0) }
        header.addView(TextView(this).apply {
            text = "Widgets"; setTextColor(Color.WHITE); textSize = 28f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
        }, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.START or Gravity.CENTER_VERTICAL))
        header.addView(TextView(this).apply {
            text = "Done"; setTextColor(0xFF0A84FF.toInt()); textSize = 17f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setPadding(dp(8), dp(8), dp(4), dp(8))
            setOnClickListener { close() }
        }, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.END or Gravity.CENTER_VERTICAL))
        sheet.addView(header)
        val search = glassField("").apply { hint = "Search Widgets"; setSingleLine() }
        sheet.addView(search, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { setMargins(dp(16), dp(10), dp(16), dp(6)) })

        val listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(6), dp(16), insetBottom + dp(24)) }
        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(listBox) }
        sheet.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        val avail = resources.displayMetrics.widthPixels - dp(32) - dp(32)
        fun card(name: String, desc: String, size: String, preview: View?, onAdd: () -> Unit): View {
            val c = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(16), dp(16), dp(14))
                background = GradientDrawable().apply { cornerRadius = dp(26).toFloat(); setColor(0xFF2C2C2E.toInt()) }
            }
            if (preview != null) c.addView(preview, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(12)
            })
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(TextView(this).apply {
                text = "$name\n$desc · $size"; setTextColor(Color.WHITE); textSize = 15f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            row.addView(TextView(this).apply {
                text = "+ Add"; setTextColor(Color.WHITE); textSize = 15f; gravity = Gravity.CENTER
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                background = Glass.pill(0xFF0A84FF.toInt(), dp(18).toFloat())
                setPadding(dp(16), dp(8), dp(16), dp(8))
                setOnClickListener { haptic(it, HapticFeedbackConstants.CONFIRM); closed = true; root.removeView(layer); animateBlur(0f, dur(200)); rebuild(); onAdd() }
            })
            c.addView(row)
            c.setOnClickListener { v -> v.animate().scaleX(0.97f).scaleY(0.97f).setDuration(80).withEndAction { Motion.scaleTo(v, 1f, Motion.BOUNCY) }.start() }
            return c
        }

        /** The real widget, drawn at its true size and shrunk to fit the card. Taps are blocked. */
        fun preview(type: WidgetType): View? = runCatching {
            val (w, h) = builtInSize(type)
            val realW = w * cellW()
            val realH = h * dp(CELL_H)
            val widget = buildWidget(PlacedWidget(type, 0, 0, w, h, "preview_${type.name}", 0), 0)
            val scale = minOf(1f, avail / realW.toFloat())
            val holder = FrameLayout(this).apply { clipChildren = false }
            val inner = FrameLayout(this).apply { pivotX = 0f; pivotY = 0f; scaleX = scale; scaleY = scale }
            inner.addView(widget, FrameLayout.LayoutParams(realW, realH))
            holder.addView(inner, FrameLayout.LayoutParams(realW, realH))
            holder.addView(View(this).apply { isClickable = true }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            holder.layoutParams = LinearLayout.LayoutParams((realW * scale).toInt(), (realH * scale).toInt())
            FrameLayout(this).apply {
                addView(holder, FrameLayout.LayoutParams((realW * scale).toInt(), (realH * scale).toInt()))
            }
        }.getOrNull()

        fun fill(q: String) {
            listBox.removeAllViews()
            val query = q.trim().lowercase()
            if (query.isEmpty() || "installed apps".contains(query) || "app widgets".contains(query)) {
                listBox.addView(card("Widgets from Your Apps", "Add a widget made by an installed app", "various", null) {
                    showInstalledWidgets()
                }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(12) })
            }
            for ((type, name, desc) in gallery) {
                if (query.isNotEmpty() && !name.lowercase().contains(query) && !desc.lowercase().contains(query)) continue
                val (w, h) = builtInSize(type)
                listBox.addView(card(name, desc, "${w}×${h}", preview(type)) { addBuiltInWidget(type) },
                    LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(12) })
            }
            // Widgets you removed from the Home Screen earlier.
            for (key in settings.hiddenWidgets.toList().sorted()) {
                val parts = key.split(':')
                val nm = when (parts.getOrNull(1)) {
                    "PHOTO" -> "Photo"; "CALENDAR" -> "Calendar"; "BATTERY" -> "Batteries"; "MUSIC" -> "Now Playing"
                    "CLOCK_ICON" -> "Clock"; "CALENDAR_ICON" -> "Calendar Icon"; else -> "Widget"
                }
                if (query.isNotEmpty() && !nm.lowercase().contains(query)) continue
                val pg = (parts.getOrNull(0)?.toIntOrNull() ?: 0) + 1
                listBox.addView(card("Restore $nm", "Put back the one you removed from page $pg", "original", null) {
                    restoreBuiltInWidget(key)
                }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(12) })
            }
        }
        fill("")
        search.addTextChangedListener(afterTextChanged = { fill(it?.toString().orEmpty()) })

        layer.addView(sheet, FrameLayout.LayoutParams(MATCH_PARENT, (resources.displayMetrics.heightPixels * 0.9f).toInt(), Gravity.BOTTOM))
        root.addView(layer, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        animateBlur(dp(22).toFloat(), dur(280))
        layer.alpha = 0f
        layer.animate().alpha(1f).setDuration(dur(220)).start()
        Motion.spring(sheet, androidx.dynamicanimation.animation.DynamicAnimation.TRANSLATION_Y, 0f, Motion.FLUID,
            start = resources.displayMetrics.heightPixels.toFloat())
    }

    @Suppress("unused")
    private fun showOldWidgetMenu() {
        val items = mutableListOf<Pair<String, () -> Unit>>()
        items += "Widgets from installed apps" to { showInstalledWidgets() }
        items += "Photo" to { addBuiltInWidget(WidgetType.PHOTO) }
        items += "Wide Photo" to { addBuiltInWidget(WidgetType.PHOTO_WIDE) }
        items += "Clock" to { addBuiltInWidget(WidgetType.CLOCK_ICON) }
        items += "Digital Clock" to { addBuiltInWidget(WidgetType.DIGITAL_CLOCK) }
        items += "Wide Digital Clock" to { addBuiltInWidget(WidgetType.WIDE_CLOCK) }
        items += "World Clock" to { addBuiltInWidget(WidgetType.WORLD_CLOCK) }
        items += "Calendar icon" to { addBuiltInWidget(WidgetType.CALENDAR_ICON) }
        items += "Calendar" to { addBuiltInWidget(WidgetType.CALENDAR) }
        items += "Date Card" to { addBuiltInWidget(WidgetType.DATE_CARD) }
        items += "Editable Quote" to { addBuiltInWidget(WidgetType.QUOTE) }
        items += "Batteries" to { addBuiltInWidget(WidgetType.BATTERY) }
        items += "Now Playing" to { addBuiltInWidget(WidgetType.MUSIC) }
        items += "Smart Stack (swipe between widgets)" to { addBuiltInWidget(WidgetType.STACK) }
        items += "Timer (Live Activity)" to { addBuiltInWidget(WidgetType.TIMER) }
        items += "Weather" to { addBuiltInWidget(WidgetType.WEATHER) }
        items += "Steps" to { addBuiltInWidget(WidgetType.STEPS) }
        val hidden = settings.hiddenWidgets.toList().sorted()
        for (key in hidden) {
            val parts = key.split(':')
            val name = when (parts.getOrNull(1)) {
                "PHOTO" -> "Photo"
                "CALENDAR" -> "Calendar"
                "BATTERY" -> "Batteries"
                "MUSIC" -> "Now Playing"
                "CLOCK_ICON" -> "Clock"
                "CALENDAR_ICON" -> "Calendar Icon"
                else -> "Widget"
            }
            val page = (parts.getOrNull(0)?.toIntOrNull() ?: 0) + 1
            items += "Restore $name (page $page)" to { restoreBuiltInWidget(key) }
        }
        AlertDialog.Builder(this, R.style.Theme_Luncher_Dialog).setTitle("Add widgets")
            .setItems(items.map { it.first }.toTypedArray()) { _, which -> items[which].second() }
            .setNegativeButton("Cancel", null).show()
    }

    private fun findWidgetSpace(width: Int, height: Int): WidgetGrid.Placement? {
        val last = maxOf(MIN_PAGES - 1, homePageCount() - 1, lastWidgetPage()).coerceAtMost(99)
        val preferred = pager.currentItem.coerceIn(0, last)
        val pagesToTry = listOf(preferred) + (0..(last + 1)).filter { it != preferred }
        val occupied = (0..last).flatMap { page ->
            widgetsFor(page).filter { widgetVisible(page, it) && it.col + it.w <= cols && it.row + it.h <= rowsPerPage() }
                .map { WidgetGrid.Placement(page, it.col, it.row, it.w, it.h) }
        }
        return WidgetGrid.firstFit(cols, rowsPerPage(), width, height, pagesToTry, occupied)
    }

    private fun restoreBuiltInWidget(key: String) {
        val maxPage = maxOf(MIN_PAGES - 1, lastWidgetPage())
        val found = (0..maxPage).asSequence().flatMap { page ->
            widgetsFor(page).asSequence().filter { widgetKey(page, it) == key }.map { page to it }
        }.firstOrNull() ?: return
        val (page, widget) = found
        val typeKey = if (widget.type == WidgetType.CLOCK_ICON || widget.type == WidgetType.CALENDAR_ICON) "ICONS" else widget.type.name
        settings.setWidgetEnabled(typeKey, true)
        val current = WidgetGrid.Placement(page, widget.col, widget.row, widget.w, widget.h)
        val occupied = widgetsFor(page).filter { widgetKey(page, it) != key && widgetVisible(page, it) }
            .map { WidgetGrid.Placement(page, it.col, it.row, it.w, it.h) }
        val place = if (WidgetGrid.fits(current, cols, rowsPerPage(), occupied)) current
            else findWidgetSpace(widget.w, widget.h)
        if (place == null) { toast("There is not enough room for this widget"); return }
        val positions = widgetPositions()
        positions.put(key, JSONObject().put("page", place.page).put("col", place.col).put("row", place.row)
            .put("w", widget.w).put("h", widget.h))
        prefs.edit().putString("widget_positions", positions.toString()).apply()
        settings.hiddenWidgets = settings.hiddenWidgets - key
        rebuild()
        pager.setCurrentItem(place.page, anim > 0f)
    }

    private fun addBuiltInWidget(type: WidgetType) {
        val (width, height) = builtInSize(type)
        val typeKey = when (type) {
            WidgetType.CLOCK_ICON, WidgetType.CALENDAR_ICON -> "ICONS"
            WidgetType.PHOTO_WIDE -> "PHOTO"
            else -> type.name
        }
        settings.setWidgetEnabled(typeKey, true)
        val place = findWidgetSpace(width, height)
        if (place == null) { toast("There is not enough room for this widget"); return }
        val id = "extra_" + UUID.randomUUID().toString().replace("-", "")
        val widget = PlacedWidget(type, place.col, place.row, width, height, id, place.page)
        saveAdditionalWidget(widget, place.page)
        rebuild()
        pager.setCurrentItem(place.page, anim > 0f)
    }

    private fun widgetCellSize(info: AppWidgetProviderInfo): Pair<Int, Int> {
        val inset = ((cellW() - dp(iconDp)) / 2).coerceAtLeast(0)
        val minWidth = (info.minWidth + inset * 2).coerceAtLeast(1)
        val minHeight = (info.minHeight + dp(38)).coerceAtLeast(1)
        val width = (minWidth + cellW() - 1) / cellW().coerceAtLeast(1)
        val height = (minHeight + dp(CELL_H) - 1) / dp(CELL_H).coerceAtLeast(1)
        val targetWidth = if (Build.VERSION.SDK_INT >= 31) info.targetCellWidth.takeIf { it <= cols } ?: 0 else 0
        val targetHeight = if (Build.VERSION.SDK_INT >= 31) info.targetCellHeight.takeIf { it <= rowsPerPage() } ?: 0 else 0
        return maxOf(1, width, targetWidth) to maxOf(1, height, targetHeight)
    }

    private fun showInstalledWidgets() {
        val profiles = runCatching { getSystemService(UserManager::class.java).userProfiles }.getOrDefault(emptyList())
        val byProfile = profiles.flatMap { profile ->
            runCatching { appWidgetManager.getInstalledProvidersForProfile(profile) }.getOrDefault(emptyList())
        }
        val installed = if (byProfile.isNotEmpty()) byProfile
            else runCatching { appWidgetManager.installedProviders }.getOrDefault(emptyList())
        val providers = installed
            .distinctBy { "${it.provider.flattenToString()}:${it.profile}" }
            .filter { it.widgetCategory == 0 || (it.widgetCategory and AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN) != 0 }
            .sortedBy { runCatching { it.loadLabel(packageManager) }.getOrDefault(it.provider.packageName).lowercase() }
        if (providers.isEmpty()) {
            toast("No home-screen widgets were found in your installed apps")
            return
        }
        val labels = providers.map { info ->
            val name = runCatching { info.loadLabel(packageManager) }.getOrDefault(info.provider.packageName)
            "$name  ·  ${info.provider.packageName}"
        }
        AlertDialog.Builder(this, R.style.Theme_Luncher_Dialog).setTitle("Widgets from apps")
            .setItems(labels.toTypedArray()) { _, index -> beginAppWidget(providers[index]) }
            .setNegativeButton("Cancel", null).show()
    }

    @Suppress("DEPRECATION")
    private fun beginAppWidget(info: AppWidgetProviderInfo) {
        val (width, height) = widgetCellSize(info)
        if (findWidgetSpace(width, height) == null) {
            toast("This widget is too large for this home-screen grid")
            return
        }
        val previous = prefs.getInt("pending_widget_id", AppWidgetManager.INVALID_APPWIDGET_ID)
        if (previous != AppWidgetManager.INVALID_APPWIDGET_ID) cancelPendingAppWidget(previous)
        val id = runCatching { appWidgetHost.allocateAppWidgetId() }.getOrElse {
            toast("Could not add this widget")
            return
        }
        prefs.edit().putInt("pending_widget_id", id).apply()
        val allowed = runCatching {
            appWidgetManager.bindAppWidgetIdIfAllowed(id, info.profile, info.provider, null)
        }.getOrDefault(false)
        if (allowed) {
            configureOrAddAppWidget(id)
            return
        }
        val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, info.profile)
        runCatching { startActivityForResult(intent, REQ_WIDGET_BIND) }.onFailure {
            cancelPendingAppWidget(id)
            toast("Android could not open widget permission")
        }
    }

    private fun configureOrAddAppWidget(id: Int) {
        val info = appWidgetManager.getAppWidgetInfo(id)
        if (info == null) {
            cancelPendingAppWidget(id)
            toast("This widget is no longer available")
            return
        }
        if (info.configure == null) {
            finishAddingAppWidget(id)
            return
        }
        runCatching {
            appWidgetHost.startAppWidgetConfigureActivityForResult(this, id, 0, REQ_WIDGET_CONFIGURE, null)
        }.onFailure {
            cancelPendingAppWidget(id)
            toast("Could not open this widget's setup")
        }
    }

    private fun cancelPendingAppWidget(id: Int) {
        if (id != AppWidgetManager.INVALID_APPWIDGET_ID) runCatching { appWidgetHost.deleteAppWidgetId(id) }
        prefs.edit().remove("pending_widget_id").apply()
    }

    private fun finishAddingAppWidget(id: Int) {
        val info = appWidgetManager.getAppWidgetInfo(id)
        if (info == null) { cancelPendingAppWidget(id); toast("This widget is no longer available"); return }
        val (width, height) = widgetCellSize(info)
        val place = findWidgetSpace(width, height)
        if (place == null) { cancelPendingAppWidget(id); toast("There is not enough room for this widget"); return }
        val widget = PlacedWidget(WidgetType.APPWIDGET, place.col, place.row, width, height, "appwidget_$id", place.page)
        saveAdditionalWidget(widget, place.page)
        prefs.edit().remove("pending_widget_id").apply()
        rebuild()
        pager.setCurrentItem(place.page, anim > 0f)
    }

    @SuppressLint("ClickableViewAccessibility")
    private inner class DragHandler(
        private val app: AppInfo,
        private val fromDock: Boolean,
        private val holder: View
    ) : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var dragging = false

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    dragging = false
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!dragging && hypot(e.rawX - downX, e.rawY - downY) > dp(6)) {
                        dragging = true
                        startDrag(app, fromDock, holder, e.rawX, e.rawY)
                    }
                    if (dragging) moveDrag(e.rawX, e.rawY)
                }
                MotionEvent.ACTION_UP -> {
                    v.parent?.requestDisallowInterceptTouchEvent(false)
                    if (dragging) endDrag(e.rawX, e.rawY)
                    dragging = false
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.parent?.requestDisallowInterceptTouchEvent(false)
                    // RecyclerView can detach the source while rendering a drag preview and
                    // send a synthetic cancel. The activity owns the live drag and handles
                    // real cancellation; rebuilding here would re-enter its layout/draw pass.
                    dragging = false
                }
            }
            return true
        }
    }

    private fun rootOffset(): IntArray {
        val loc = IntArray(2)
        root.getLocationOnScreen(loc)
        return loc
    }

    private fun cancelHomeHold() {
        pendingHomeHold?.let { mainHandler.removeCallbacks(it) }
        pendingHomeHold = null
    }

    private fun currentPageFrame(): FrameLayout? {
        val rv = pager.getChildAt(0) as? RecyclerView ?: return null
        val holder = rv.findViewHolderForAdapterPosition(pager.currentItem)?.itemView as? FrameLayout ?: return null
        return holder.getChildAt(0) as? FrameLayout
    }

    private fun resizeHandleAt(rawX: Float, rawY: Float): Pair<View, View.OnTouchListener>? {
        val page = pages.getOrNull(pager.currentItem) as? Page.Home ?: return null
        val frame = currentPageFrame() ?: return null
        for (i in page.widgets.indices) {
            val wrapper = frame.getChildAt(i) as? FrameLayout ?: continue
            val handle = wrapper.getChildAt(1) ?: continue
            val listener = handle.tag as? View.OnTouchListener ?: continue
            val loc = IntArray(2)
            handle.getLocationOnScreen(loc)
            if (handle.isShown && rawX >= loc[0] && rawX < loc[0] + handle.width &&
                rawY >= loc[1] && rawY < loc[1] + handle.height) return handle to listener
        }
        return null
    }

    /** Hold an icon or built-in widget directly on the home screen to pick it up. */
    private fun scheduleHomeHold(rawX: Float, rawY: Float) {
        cancelHomeHold()
        holdX = rawX; holdY = rawY
        if (!::pager.isInitialized || overlayOpen()) return
        val pageIndex = pager.currentItem
        val page = pages.getOrNull(pageIndex) as? Page.Home ?: return
        val frame = currentPageFrame() ?: return
        fun contains(view: View?): Boolean {
            if (view == null) return false
            val loc = IntArray(2)
            view.getLocationOnScreen(loc)
            return view.width > 0 && view.height > 0 && rawX >= loc[0] && rawX < loc[0] + view.width &&
                rawY >= loc[1] && rawY < loc[1] + view.height
        }
        val dock = dockApps()
        val di = dock.indices.firstOrNull { contains(dockRow.getChildAt(it)) }
        val wi = if (di == null) page.widgets.indices.firstOrNull { contains(frame.getChildAt(it)) } else null
        val ai = if (di == null) page.apps.indices.firstOrNull { contains(frame.getChildAt(page.widgets.size + it)) } else null
        val source = (when {
            di != null -> dockRow.getChildAt(di)
            wi != null -> frame.getChildAt(wi)
            ai != null -> frame.getChildAt(page.widgets.size + ai)
            else -> return
        }) ?: return
        val task = Runnable {
            pendingHomeHold = null
            if (!isActive || overlayOpen() || !source.isAttachedToWindow) return@Runnable
            source.cancelLongPress()
            enterEditMode(refresh = false)
            if (di != null) startDrag(dock[di], true, source, holdX, holdY)
            else if (wi != null) startWidgetDrag(page.widgets[wi], pageIndex, source, holdX, holdY)
            else if (ai != null) startDrag(page.apps[ai].first, false, source, holdX, holdY)
        }
        pendingHomeHold = task
        mainHandler.postDelayed(task, (ViewConfiguration.getLongPressTimeout() - 40L).coerceAtLeast(200L))
    }

    private fun startWidgetDrag(widget: PlacedWidget, page: Int, source: View, rawX: Float, rawY: Float) {
        val bitmap = runCatching { source.drawToBitmap() }.getOrNull() ?: return
        temporaryDragPageCount = 0
        val width = source.width.coerceAtLeast(1)
        val height = source.height.coerceAtLeast(1)
        val sourceLoc = IntArray(2)
        source.getLocationOnScreen(sourceLoc)
        val grabX = (rawX - sourceLoc[0]).coerceIn(0f, width.toFloat())
        val grabY = (rawY - sourceLoc[1]).coerceIn(0f, height.toFloat())
        val floating = ImageView(this).apply {
            setImageBitmap(bitmap)
            elevation = dp(14).toFloat()
            alpha = 0.9f
        }
        root.addView(floating, FrameLayout.LayoutParams(width, height))
        val off = rootOffset()
        floating.x = rawX - off[0] - grabX
        floating.y = rawY - off[1] - grabY
        source.alpha = 0.2f
        pager.isUserInputEnabled = false
        haptic(source)
        widgetDrag = WidgetDragState(widget, page, source, floating, width, height, grabX, grabY)
    }

    private fun moveWidgetDrag(rawX: Float, rawY: Float) {
        val d = widgetDrag ?: return
        val off = rootOffset()
        d.floating.x = rawX - off[0] - d.grabX
        d.floating.y = rawY - off[1] - d.grabY
        updateDragEdge(rawX, rawY)
    }

    private fun finishWidgetDrag(d: WidgetDragState) {
        widgetDrag = null
        temporaryDragPageCount = 0
        edgeDir = 0
        mainHandler.removeCallbacks(edgeFlip)
        pager.isUserInputEnabled = true
        d.source.alpha = 1f
        root.removeView(d.floating)
    }

    private fun cancelWidgetDrag() {
        val d = widgetDrag ?: return
        finishWidgetDrag(d)
        rebuild()
    }

    private fun endWidgetDrag(rawX: Float, rawY: Float) {
        val d = widgetDrag ?: return
        val targetPage = pager.currentItem
        val target = pages.getOrNull(targetPage) as? Page.Home
        val frame = currentPageFrame()
        val onNewPage = temporaryDragPageCount > 0 && targetPage == temporaryDragPageCount - 1
        finishWidgetDrag(d)
        if (target == null || (frame == null && !onNewPage) || overDock(rawX, rawY)) { rebuild(); return }
        val loc = IntArray(2)
        if (frame != null) frame.getLocationOnScreen(loc) else pager.getLocationOnScreen(loc)
        val w = d.widget
        val col = ((rawX - loc[0] - (frame?.paddingLeft ?: dp(PAGE_PAD)) - d.grabX) / cellW())
            .roundToInt().coerceIn(0, (cols - w.w).coerceAtLeast(0))
        val row = ((rawY - loc[1] - (frame?.paddingTop ?: dp(6)) - d.grabY) / dp(CELL_H))
            .roundToInt().coerceIn(0, (rowsPerPage() - w.h).coerceAtLeast(0))
        val occupied = target.widgets.filter { widgetKey(targetPage, it) != widgetKey(d.page, w) }
            .map { WidgetGrid.Placement(targetPage, it.col, it.row, it.w, it.h) }
        val blocked = !WidgetGrid.fits(WidgetGrid.Placement(targetPage, col, row, w.w, w.h), cols, rowsPerPage(), occupied)
        if (blocked) {
            toast("Choose a space without another widget")
        } else {
            if (w.id.startsWith("extra_") || w.id.startsWith("appwidget_")) {
                saveAdditionalWidget(w.copy(col = col, row = row, originPage = targetPage), targetPage)
            } else {
                val positions = widgetPositions()
                positions.put(widgetKey(d.page, w), JSONObject().put("page", targetPage).put("col", col).put("row", row)
                    .put("w", w.w).put("h", w.h))
                prefs.edit().putString("widget_positions", positions.toString()).apply()
            }
        }
        rebuild()
    }

    private fun startDrag(app: AppInfo, fromDock: Boolean, source: View, rawX: Float, rawY: Float,
                          originFolderId: String? = null) {
        if (drag != null) return
        clearDragGhost()
        temporaryDragPageCount = 0
        previewOrder = currentOrder()
        val size = dp(iconDp)
        val floating = ImageView(this).apply {
            setImageBitmap(app.icon)
            contentDescription = "Moving ${app.label}"
            outlineProvider = roundedOutline()
            clipToOutline = true
            elevation = dp(14).toFloat()
        }
        root.addView(floating, FrameLayout.LayoutParams(size, size))
        dragGhost = floating
        val off = rootOffset()
        floating.x = rawX - off[0] - size / 2f
        floating.y = rawY - off[1] - size / 2f
        Motion.scaleTo(floating, 1.18f, Motion.BOUNCY)
        source.animate().alpha(0.2f).setDuration(dur(120)).start()
        pager.isUserInputEnabled = false
        haptic(source)
        drag = DragState(app, fromDock, source, floating, size, originFolderId)
    }

    private fun moveDrag(rawX: Float, rawY: Float) {
        val d = drag ?: return
        val off = rootOffset()
        val x = rawX - off[0]
        val y = rawY - off[1]
        d.floating.x = x - d.size / 2f
        d.floating.y = y - d.size / 2f
        updateDragEdge(rawX, rawY)
        previewAppReorder(rawX, rawY)
    }

    private fun updateDragEdge(rawX: Float, rawY: Float) {
        val off = rootOffset()
        val x = rawX - off[0]
        val edge = dp(28)
        val dir = when {
            overDock(rawX, rawY) -> 0
            x < edge -> -1
            x > root.width - edge -> 1
            else -> 0
        }
        if (dir != edgeDir) {
            edgeDir = dir
            mainHandler.removeCallbacks(edgeFlip)
            if (dir != 0) mainHandler.postDelayed(edgeFlip, 650)
        }
    }

    private fun flipPageDuringDrag() {
        if ((drag == null && widgetDrag == null) || edgeDir == 0) return
        val target = PageDragNavigation.next(pager.currentItem, homePageCount(), edgeDir,
            temporaryDragPageCount > 0) ?: return
        cancelPendingReorder()
        highlightFolderTarget(null)
        if (target.createPage) {
            temporaryDragPageCount = target.page + 1
            rebuild()
            // Select it immediately so a quick release still drops on the new page.
            pager.setCurrentItem(target.page, false)
            haptic(root, HapticFeedbackConstants.CONTEXT_CLICK)
            return // Do not create many pages while the finger stays at the edge.
        }
        pager.setCurrentItem(target.page, anim > 0f)
        haptic(root, HapticFeedbackConstants.CONTEXT_CLICK)
        mainHandler.postDelayed(edgeFlip, 900)
    }

    private fun homePageCount() = pages.count { it is Page.Home }

    private fun minimumHomePages() = prefs.getInt(KEY_HOME_PAGES, MIN_PAGES).coerceIn(1, MAX_HOME_PAGES)

    private class PageSheetAction(
        val title: String,
        val detail: String,
        val symbol: String,
        val destructive: Boolean = false,
        val run: () -> Unit
    )

    private fun pageSheetBackground() = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(24).toFloat()
        setColor(0xF526262B.toInt())
        setStroke(maxOf(1, dp(0.7f)), 0x25FFFFFF)
    }

    /** A compact, bottom-aligned action sheet matching the launcher's dark glass style. */
    private fun showPageSheet(title: String, subtitle: String, actions: List<PageSheetAction>) {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = pageSheetBackground()
            outlineProvider = roundedOutline(24f)
            clipToOutline = true
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(16))
        }
        header.addView(TextView(this).apply {
            text = title
            textSize = 20f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(Color.WHITE)
            maxLines = 2
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        header.addView(TextView(this).apply {
            text = subtitle
            textSize = 13f
            setTextColor(0xFFB4B4BC.toInt())
            setLineSpacing(dp(2).toFloat(), 1f)
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(5) })
        card.addView(header)
        actions.forEach { action ->
            card.addView(View(this).apply { setBackgroundColor(0x24FFFFFF) },
                LinearLayout.LayoutParams(MATCH_PARENT, maxOf(1, dp(0.6f))).apply {
                    marginStart = dp(20)
                    marginEnd = dp(20)
                })
            val tint = if (action.destructive) 0xFFFF6961.toInt() else 0xFF64ADFF.toInt()
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(20), dp(10), dp(20), dp(10))
                minimumHeight = dp(66)
                isClickable = true
                isFocusable = true
                background = android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(0x22FFFFFF), null, null
                )
                setOnClickListener { dialog.dismiss(); action.run() }
            }
            row.addView(TextView(this).apply {
                text = action.symbol
                textSize = 22f
                gravity = Gravity.CENTER
                setTextColor(tint)
                background = Glass.circle(if (action.destructive) 0x33FF6961 else 0x3364ADFF)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(14) })
            val labels = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
            }
            labels.addView(TextView(this).apply {
                text = action.title
                textSize = 16f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(if (action.destructive) tint else Color.WHITE)
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            labels.addView(TextView(this).apply {
                text = action.detail
                textSize = 12f
                setTextColor(0xFFB4B4BC.toInt())
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(2) })
            row.addView(labels, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            row.addView(TextView(this).apply {
                text = "›"
                textSize = 24f
                setTextColor(0xFF8E8E98.toInt())
                gravity = Gravity.CENTER
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(18), dp(36)))
            card.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        sheet.addView(card, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        val cancel = TextView(this).apply {
            text = "Cancel"
            textSize = 16f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(0xFF64ADFF.toInt())
            background = pageSheetBackground()
            setOnClickListener { dialog.dismiss() }
        }
        sheet.addView(cancel, LinearLayout.LayoutParams(MATCH_PARENT, dp(54)).apply { topMargin = dp(8) })
        dialog.setContentView(sheet)
        dialog.setCanceledOnTouchOutside(true)
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.60f)
            setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
            setLayout(minOf(dp(440), (resources.displayMetrics.widthPixels - dp(32)).coerceAtLeast(1)), WRAP_CONTENT)
            attributes = attributes.apply { y = dp(12) }
        }
        if (anim > 0f) {
            sheet.alpha = 0f
            sheet.translationY = dp(14).toFloat()
            sheet.animate().alpha(1f).translationY(0f).setDuration(dur(240)).setInterpolator(EASE).start()
        }
    }

    private fun showPageMenu() {
        val count = homePageCount()
        val index = pager.currentItem
        val subtitle = if (pages.getOrNull(index) is Page.Home) "Page ${index + 1} of $count"
            else "$count home pages"
        showPageSheet("Home Pages", subtitle, listOf(
            PageSheetAction("Add Page", "Create a new home screen", "＋") { addHomePage() },
            PageSheetAction("Remove This Page", "Move its items to other pages", "−", destructive = true) {
                confirmRemoveHomePage()
            }
        ))
    }

    private fun addHomePage() {
        val count = homePageCount()
        if (count >= MAX_HOME_PAGES) { toast("Maximum number of pages reached"); return }
        prefs.edit().putInt(KEY_HOME_PAGES, count + 1).apply()
        rebuild()
        pager.setCurrentItem(count, false)
        toast("Page added")
    }

    private fun confirmRemoveHomePage() {
        val page = pager.currentItem
        if (pages.getOrNull(page) !is Page.Home) { toast("Choose a home page first"); return }
        if (homePageCount() <= 1) { toast("Keep at least one home page"); return }
        showPageSheet(
            "Remove this page?",
            "Apps and widgets will move to other pages if there is room. Apps will not be uninstalled.",
            listOf(PageSheetAction("Remove Page", "Move items and remove this page", "−", destructive = true) {
                removeHomePage(page)
            })
        )
    }

    /** Plan first, then persist all positions together so nothing is lost if a page won't fit. */
    private fun removeHomePage(page: Int) {
        if (!appsReady) { toast("Apps are still loading. Try again in a moment."); return }
        val homePages = pages.filterIsInstance<Page.Home>()
        if (page !in homePages.indices || homePages.size <= 1) return
        val rows = rowsPerPage()
        val snapshots = homePages.indices.flatMap { oldPage ->
            widgetsFor(oldPage).map { widget -> Triple(oldPage, widget, widgetKey(oldPage, widget)) }
        }
        val widgets = snapshots.map { (oldPage, widget, key) ->
            HomePageEditor.Widget(key, WidgetGrid.Placement(oldPage, widget.col, widget.row, widget.w, widget.h),
                widgetVisible(oldPage, widget) && widget.col + widget.w <= cols && widget.row + widget.h <= rows)
        }
        val icons = homePages.flatMapIndexed { oldPage, home ->
            home.apps.map { (app, cell) -> HomePageEditor.Icon(app.key, oldPage, cell) }
        }
        val plan = HomePageEditor.remove(homePages.size, page, cols, rows, widgets, icons)
        if (plan == null) {
            toast("Not enough room on the other pages. Move or remove some items first.")
            return
        }
        val positions = widgetPositions()
        // Old hidden or orphaned positions must not keep a removed page alive.
        for (key in positions.keys().asSequence().toList()) {
            val value = positions.optJSONObject(key) ?: continue
            val oldPage = value.optInt("page", 0)
            val shifted = when { oldPage < page -> oldPage; oldPage > page -> oldPage - 1; else -> page }
            value.put("page", shifted.coerceIn(0, plan.pageCount - 1))
        }
        val added = additionalWidgetData()
        for ((_, widget, key) in snapshots) {
            val dest = plan.widgets[key] ?: return
            if (widget.id.startsWith("extra_") || widget.id.startsWith("appwidget_")) {
                val item = added.optJSONObject(widget.id) ?: return
                item.put("page", dest.page).put("col", dest.col).put("row", dest.row)
                    .put("w", dest.width).put("h", dest.height)
            } else {
                positions.put(key, JSONObject().put("page", dest.page).put("col", dest.col).put("row", dest.row)
                    .put("w", dest.width).put("h", dest.height))
            }
        }
        previewOrder = null
        prefs.edit().putString("widget_positions", positions.toString())
            .putString("additional_widgets", added.toString())
            .putString("s_order", plan.order.joinToString("|"))
            .putInt(KEY_HOME_PAGES, plan.pageCount)
            .putInt("s_version", settings.version + 1).apply()
        rebuild()
        pager.setCurrentItem(page.coerceAtMost(plan.pageCount - 1), false)
        toast("Page removed; its apps and widgets were moved")
    }

    private fun overDock(rawX: Float, rawY: Float): Boolean {
        val loc = IntArray(2)
        dockRow.getLocationOnScreen(loc)
        // The invisible drop area includes one extra slot on each side while there is room.
        val extra = if (displayedDockKeys.size < cols) dp((iconDp + 12) / 2) else 0
        return rawX >= loc[0] - extra && rawX <= loc[0] + dockRow.width + extra &&
            rawY >= loc[1] - dp(10) && rawY <= loc[1] + dockRow.height + dp(10)
    }

    private fun endDrag(rawX: Float, rawY: Float) {
        val d = drag ?: return
        val onNewPage = temporaryDragPageCount > 0 && pager.currentItem == temporaryDragPageCount - 1
        finishDragVisuals(d)
        if (overDock(rawX, rawY)) {
            if (detachFolderMemberForDrag(d)) dropIntoDock(d, rawX) else { previewOrder = null; rebuild() }
        } else dropOnHome(d, rawX, rawY, onNewPage)
    }

    private fun cancelDrag() {
        val d = drag ?: return
        finishDragVisuals(d)
        previewOrder = null
        rebuild()
    }

    private fun finishDragVisuals(d: DragState) {
        drag = null
        temporaryDragPageCount = 0
        cancelPendingReorder()
        highlightFolderTarget(null)
        edgeDir = 0
        mainHandler.removeCallbacks(edgeFlip)
        pager.isUserInputEnabled = true
        d.source.animate().cancel()
        d.source.alpha = 1f
        clearDragGhost()
        haptic(root, HapticFeedbackConstants.CONTEXT_CLICK)
    }

    /** Remove the drag image immediately even if an animation or touch is cancelled. */
    private fun clearDragGhost() {
        dragGhost?.let { ghost ->
            ghost.animate().cancel()
            if (::root.isInitialized) root.removeView(ghost)
        }
        dragGhost = null
    }

    private fun dropIntoDock(d: DragState, rawX: Float) {
        if (folderId(d.app) != null) {
            previewOrder = null
            toast("Move folders on the Home Screen")
            rebuild()
            return
        }
        val dock = dockKeys().filter { k -> allApps.any { it.key == k } }.toMutableList()
        val loc = IntArray(2)
        dockRow.getLocationOnScreen(loc)
        val slots = if (d.fromDock) dock.size else dock.size + 1
        val idx = (((rawX - loc[0]) / dockRow.width.coerceAtLeast(1)) * slots).toInt().coerceIn(0, slots - 1)
        if (d.fromDock) {
            dock.remove(d.app.key)
            dock.add(idx.coerceIn(0, dock.size), d.app.key)
        } else if (dock.size >= cols) {
            // Dock full: swap with the app under the finger, which moves to the dragged app's spot.
            val swapIdx = idx.coerceIn(0, dock.size - 1)
            val replaced = dock[swapIdx]
            dock[swapIdx] = d.app.key
            settings.order = currentOrder().map { if (it == d.app.key) replaced else it }
        } else {
            dock.add(idx.coerceIn(0, dock.size), d.app.key)
        }
        settings.hiddenApps = settings.hiddenApps - d.app.key
        previewOrder = null
        setDock(dock)
    }

    /** Full current home order, preserving explicitly empty cells. */
    private fun currentOrder(): List<String> =
        HomeGridOrder.normalize(previewOrder ?: settings.order, homeApps(dockKeys().toSet()).map { it.key })

    /** Map a visible grid cell to its position among cells not occupied by widgets. */
    private fun slotIndexFor(pageIndex: Int, targetCell: Int): Int? {
        val rows = rowsPerPage()
        if (targetCell !in 0 until rows * cols) return null
        var index = 0
        for (p in 0..pageIndex) {
            val page = pages.getOrNull(p) as? Page.Home ?: return null
            val taken = BooleanArray(rows * cols)
            page.widgets.forEach { w ->
                for (r in w.row until minOf(rows, w.row + w.h)) {
                    for (c in w.col until minOf(cols, w.col + w.w)) taken[r * cols + c] = true
                }
            }
            for (cell in taken.indices) {
                if (p == pageIndex && cell == targetCell) return if (taken[cell]) null else index
                if (!taken[cell]) index++
            }
        }
        return null
    }

    private data class GridHit(
        val page: Page.Home, val frame: FrameLayout, val cell: Int,
        val appIndex: Int, val centered: Boolean, val localX: Float
    )

    private fun gridHit(rawX: Float, rawY: Float, allowEdgeDrop: Boolean = false): GridHit? {
        val page = pages.getOrNull(pager.currentItem) as? Page.Home ?: return null
        val frame = currentPageFrame() ?: return null
        val loc = IntArray(2)
        frame.getLocationOnScreen(loc)
        var x = rawX - loc[0] - frame.paddingLeft
        val y = rawY - loc[1] - frame.paddingTop
        val gridWidth = cellW() * cols
        if (allowEdgeDrop && x >= -dp(28) && x < gridWidth + dp(28)) {
            x = x.coerceIn(0f, (gridWidth - 1).toFloat())
        }
        if (x < 0 || y < 0 || x >= gridWidth || y >= dp(CELL_H) * rowsPerPage()) return null
        val col = (x / cellW()).toInt()
        val row = (y / dp(CELL_H)).toInt()
        val cell = row * cols + col
        val index = page.apps.indexOfFirst { it.second == cell }
        val cx = (col + 0.5f) * cellW()
        val cy = row * dp(CELL_H) + dp(4) + dp(iconDp) / 2f
        val centered = abs(x - cx) < dp(iconDp * 0.34f) && abs(y - cy) < dp(iconDp * 0.34f)
        return GridHit(page, frame, cell, index, centered, x - col * cellW())
    }

    private fun highlightFolderTarget(view: View?) {
        if (folderHoverView === view) return
        folderHoverView?.animate()?.scaleX(1f)?.scaleY(1f)?.setDuration(120)?.start()
        folderHoverView = view
        view?.animate()?.scaleX(1.08f)?.scaleY(1.08f)?.setDuration(120)?.start()
    }

    private fun cancelPendingReorder() {
        pendingReorder?.let { mainHandler.removeCallbacks(it) }
        pendingReorder = null
        pendingReorderKey = null
    }

    /** Reflow other icons as the dragged app crosses a cell boundary. */
    private fun previewAppReorder(rawX: Float, rawY: Float) {
        val d = drag ?: return
        if (overDock(rawX, rawY)) { cancelPendingReorder(); highlightFolderTarget(null); return }
        val hit = gridHit(rawX, rawY) ?: run { cancelPendingReorder(); highlightFolderTarget(null); return }
        val target = hit.page.apps.getOrNull(hit.appIndex)?.first
        if (target != null && target.key != d.app.key && hit.centered && folderId(d.app) == null) {
            cancelPendingReorder()
            highlightFolderTarget(hit.frame.getChildAt(hit.page.widgets.size + hit.appIndex))
            return
        }
        highlightFolderTarget(null)
        if (d.fromDock || d.originFolderId != null || target == null || target.key == d.app.key) {
            cancelPendingReorder(); return
        }
        val after = hit.localX > cellW() / 2f
        val key = "${d.app.key}|${target.key}|$after|${pager.currentItem}"
        if (pendingReorderKey == key) return
        cancelPendingReorder()
        pendingReorderKey = key
        val task = Runnable {
            pendingReorder = null; pendingReorderKey = null
            if (drag?.app?.key != d.app.key) return@Runnable
            val order = HomeGridOrder.moveAround(currentOrder(), d.app.key, target.key, after)
            if (order != previewOrder) {
                previewOrder = order
                rebuild()
            }
        }
        pendingReorder = task
        mainHandler.postDelayed(task, 150)
    }

    private fun putIntoFolder(d: DragState, target: AppInfo): Boolean {
        if (folderId(d.app) != null || d.app.key == target.key) return false
        val groups = folders().toMutableList()
        val oldOrder = currentOrder().filter { it != d.app.key }.toMutableList()
        val targetId = folderId(target)
        val folder = if (targetId != null) {
            val index = groups.indexOfFirst { it.id == targetId }
            if (index < 0) return false
            groups[index] = groups[index].copy(keys = (groups[index].keys + d.app.key).distinct())
            groups[index]
        } else {
            val group = HomeFolder(UUID.randomUUID().toString().replace("-", ""), "Folder", listOf(target.key, d.app.key))
            groups += group
            val index = oldOrder.indexOf(target.key)
            if (index >= 0) oldOrder[index] = folderComponent(group.id).flattenToString()
            else oldOrder.add(folderComponent(group.id).flattenToString())
            group
        }
        if (d.fromDock) saveDock(dockKeys().filter { it != d.app.key })
        saveFolders(groups)
        settings.hiddenApps = settings.hiddenApps - d.app.key - target.key
        previewOrder = null
        settings.order = oldOrder
        rebuild()
        toast(if (targetId == null) "Folder created" else "Added to ${folder.name}")
        return true
    }

    /** The folder and app picker use the same three-column icon-and-name tiles. */
    private inner class FolderGridAdapter(
        private var apps: List<AppInfo>,
        private val selected: Set<String> = emptySet(),
        private val onTap: (AppInfo, View) -> Unit,
        private val onHold: ((AppInfo, View) -> Unit)? = null
    ) : RecyclerView.Adapter<FolderGridAdapter.Holder>() {
        inner class Holder(val tile: LinearLayout, val icon: ImageView, val badge: TextView,
                           val label: TextView) : RecyclerView.ViewHolder(tile)

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): Holder {
            val tile = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                setPadding(dp(4), dp(9), dp(4), dp(7))
                layoutParams = RecyclerView.LayoutParams(MATCH_PARENT, dp(108))
                isClickable = true
                isFocusable = true
            }
            val imageWrap = FrameLayout(this@MainActivity)
            val icon = ImageView(this@MainActivity).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                outlineProvider = roundedOutline()
                clipToOutline = true
            }
            imageWrap.addView(icon, FrameLayout.LayoutParams(dp(60), dp(60)))
            val badge = TextView(this@MainActivity).apply {
                text = "✓"
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                typeface = Typeface.DEFAULT_BOLD
                background = Glass.circle(0xFF0A84FF.toInt())
                visibility = View.GONE
            }
            imageWrap.addView(badge, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.TOP or Gravity.END))
            tile.addView(imageWrap, LinearLayout.LayoutParams(dp(64), dp(64)))
            val label = TextView(this@MainActivity).apply {
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                textSize = 12f
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setShadowLayer(dp(2).toFloat(), 0f, 1f, 0xA0000000.toInt())
            }
            tile.addView(label, LinearLayout.LayoutParams(MATCH_PARENT, dp(28)).apply { topMargin = dp(4) })
            return Holder(tile, icon, badge, label)
        }

        override fun getItemCount() = apps.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val app = apps[position]
            holder.label.text = app.label
            // Use the same selected style as the home screen, including original colors.
            holder.icon.setImageBitmap(app.icon)
            holder.badge.visibility = if (app.key in selected) View.VISIBLE else View.GONE
            holder.tile.contentDescription = if (app.key in selected) "${app.label}, added" else app.label
            holder.tile.setOnClickListener { onTap(app, holder.icon) }
            holder.tile.setOnLongClickListener {
                if (onHold == null) false else { onHold.invoke(app, holder.icon); true }
            }
        }

        @Suppress("NotifyDataSetChanged")
        fun replaceApps(newApps: List<AppInfo>) { apps = newApps; notifyDataSetChanged() }
    }

    /** Keep the original gesture in the dialog window while the icon moves over the home screen. */
    private inner class FolderDialog : Dialog(this@MainActivity) {
        var lastRawX = 0f
        var lastRawY = 0f
        var draggingMember = false
        var dragStartX = 0f
        var dragStartY = 0f
        var movedMember = false

        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            lastRawX = event.rawX
            lastRawY = event.rawY
            if (draggingMember) {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        draggingMember = false
                        cancelDrag()
                        dismiss()
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (hypot(event.rawX - dragStartX, event.rawY - dragStartY) > dp(8)) movedMember = true
                        moveDrag(event.rawX, event.rawY)
                    }
                    MotionEvent.ACTION_UP -> {
                        draggingMember = false
                        if (movedMember) endDrag(event.rawX, event.rawY) else cancelDrag()
                        dismiss()
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        draggingMember = false
                        cancelDrag()
                        dismiss()
                    }
                }
                return true
            }
            return super.dispatchTouchEvent(event)
        }
    }

    /** A compact, rounded panel over the home screen, with system back/outside tap to close. */
    private fun folderDialog(panel: View, widthFraction: Float = 0.88f): FolderDialog {
        val dialog = FolderDialog()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setContentView(panel)
        dialog.setCanceledOnTouchOutside(true)
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.62f)
            setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
            setLayout((resources.displayMetrics.widthPixels * widthFraction).toInt(), WRAP_CONTENT)
        }
        return dialog
    }

    private fun folderHeaderButton(symbol: String, description: String) = TextView(this).apply {
        text = symbol
        contentDescription = description
        textSize = 24f
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        background = Glass.circle(0x557F7F86)
        isClickable = true
        isFocusable = true
    }

    private fun openFolder(app: AppInfo) {
        val id = folderId(app) ?: return
        val group = folders().firstOrNull { it.id == id } ?: return
        val byKey = allApps.associateBy { it.key }
        val members = group.keys.mapNotNull { byKey[it] }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Glass.folder(this@MainActivity, 30f)
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val title = TextView(this).apply {
            text = group.name
            textSize = 20f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Color.WHITE)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        header.addView(title, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        val more = folderHeaderButton("⋯", "Folder options")
        val add = folderHeaderButton("+", "Add apps to folder")
        header.addView(more, LinearLayout.LayoutParams(dp(38), dp(38)).apply { marginEnd = dp(8) })
        header.addView(add, LinearLayout.LayoutParams(dp(38), dp(38)))
        panel.addView(header, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        // Adaptive size: small folders open compact, big ones open wide and tall.
        val folderCols = if (members.size <= 4) 2 else 3
        val grid = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@MainActivity, folderCols)
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            clipToPadding = false
            setPadding(0, dp(8), 0, 0)
        }
        val gridHeight = minOf(dp(108) * maxOf(1, (members.size + folderCols - 1) / folderCols) + dp(8),
            (resources.displayMetrics.heightPixels * 0.55f).toInt())
        panel.addView(grid, LinearLayout.LayoutParams(MATCH_PARENT, gridHeight))
        val dialog = folderDialog(panel, when {
            members.size <= 2 -> 0.58f
            members.size <= 4 -> 0.66f
            else -> 0.88f
        })
        activeFolderDialog = dialog
        haptic(root, HapticFeedbackConstants.CONTEXT_CLICK)
        if (anim > 0f) {
            panel.alpha = 0f
            Motion.scaleTo(panel, 1f, Motion.BOUNCY, start = 0.72f)
            Motion.alphaTo(panel, 1f, Motion.SNAPPY)
        }
        grid.adapter = FolderGridAdapter(members, onTap = { member, _ ->
            dialog.dismiss()
            launch(member, null)
        }, onHold = { member, source ->
            // Do not remove the member until a valid drop; cancellation leaves the folder intact.
            enterEditMode(refresh = false)
            startDrag(member, false, source, dialog.lastRawX, dialog.lastRawY, id)
            dialog.dragStartX = dialog.lastRawX
            dialog.dragStartY = dialog.lastRawY
            dialog.movedMember = false
            dialog.draggingMember = true
            panel.visibility = View.INVISIBLE
            dialog.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        })
        dialog.setOnDismissListener {
            if (activeFolderDialog === dialog) activeFolderDialog = null
            if (dialog.draggingMember) {
                dialog.draggingMember = false
                cancelDrag()
            }
        }
        add.setOnClickListener {
            dialog.dismiss()
            showFolderAppPicker(id)
        }
        more.setOnClickListener { anchor ->
            PopupMenu(this, anchor).apply {
                menu.add("Rename folder").setOnMenuItemClickListener {
                    dialog.dismiss(); renameFolder(app); true
                }
                menu.add("Remove an app").setOnMenuItemClickListener {
                    AlertDialog.Builder(this@MainActivity, R.style.Theme_Luncher_Dialog).setTitle("Remove app from folder")
                        .setItems(members.map { it.label }.toTypedArray()) { _, index ->
                            dialog.dismiss(); removeFolderMember(app, members[index].key)
                        }.setNegativeButton("Cancel", null).show()
                    true
                }
                menu.add("Ungroup apps").setOnMenuItemClickListener {
                    dialog.dismiss(); ungroupFolder(app); true
                }
                show()
            }
        }
    }

    private fun showFolderAppPicker(folderId: String) {
        if (folders().none { it.id == folderId }) return
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Glass.folder(this@MainActivity, 30f)
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(this).apply {
            text = "Add apps"
            textSize = 20f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Color.WHITE)
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        val done = TextView(this).apply {
            text = "Done"
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(0xFF70B7FF.toInt())
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        header.addView(done, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        panel.addView(header)
        val search = EditText(this).apply {
            hint = "Search apps"
            setSingleLine()
            textSize = 15f
            setTextColor(Color.WHITE)
            setHintTextColor(0xFFB8B8BE.toInt())
            background = Glass.pill(0x553F3F46, dp(12).toFloat())
            setPadding(dp(12), 0, dp(12), 0)
            imeOptions = EditorInfo.IME_ACTION_DONE
        }
        panel.addView(search, LinearLayout.LayoutParams(MATCH_PARENT, dp(43)).apply {
            topMargin = dp(12); bottomMargin = dp(8)
        })
        val grid = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@MainActivity, 3)
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        }
        panel.addView(grid, LinearLayout.LayoutParams(MATCH_PARENT,
            (resources.displayMetrics.heightPixels * 0.54f).toInt().coerceAtLeast(dp(160))))
        val dialog = folderDialog(panel, 0.92f)
        lateinit var adapter: FolderGridAdapter
        adapter = FolderGridAdapter(availableFolderApps(""), onTap = { selectedApp, _ ->
            if (addAppToFolder(folderId, selectedApp.key)) {
                adapter.replaceApps(availableFolderApps(search.text?.toString().orEmpty()))
                toast("Added ${selectedApp.label}")
            }
        })
        grid.adapter = adapter
        search.addTextChangedListener { text ->
            adapter.replaceApps(availableFolderApps(text?.toString().orEmpty()))
        }
        done.setOnClickListener {
            dialog.dismiss()
            val refreshed = folders().firstOrNull { it.id == folderId }
            if (refreshed != null) openFolder(folderApp(refreshed, allApps.associateBy { it.key }))
        }
    }

    private fun availableFolderApps(queryText: String): List<AppInfo> {
        val grouped = folders().flatMap { it.keys }.toSet()
        val available = FolderMembership.available(allApps.map { it.key }, grouped).toSet()
        val query = queryText.trim()
        return allApps.filter { it.key in available && (query.isEmpty() ||
            it.label.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true)) }
    }

    private fun addAppToFolder(folderId: String, key: String): Boolean {
        val groups = folders()
        val group = groups.firstOrNull { it.id == folderId } ?: return false
        if (groups.any { existing -> existing.keys.any { it.substringBefore('/') == key.substringBefore('/') } }) return false
        val members = FolderMembership.add(group.keys, key, allApps.map { it.key }.toSet())
        if (members == group.keys) return false
        saveFolders(groups.map { if (it.id == folderId) it.copy(keys = members) else it })
        if (key in dockKeys()) saveDock(dockKeys().filter { it != key })
        settings.hiddenApps = settings.hiddenApps - key
        previewOrder = null
        settings.order = currentOrder().filter { it != key }
        rebuild()
        return true
    }

    private fun renameFolder(app: AppInfo) {
        val id = folderId(app) ?: return
        val input = glassField(app.label)
        AlertDialog.Builder(this, R.style.Theme_Luncher_Dialog).setTitle("Folder name").setView(wrapField(input))
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim().take(40).ifEmpty { "Folder" }
                saveFolders(folders().map { if (it.id == id) it.copy(name = name) else it })
                rebuild()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun ungroupFolder(app: AppInfo) {
        val group = folders().firstOrNull { it.id == folderId(app) } ?: return
        val order = currentOrder().toMutableList()
        val index = order.indexOf(app.key).let { if (it < 0) order.size else it }
        order.remove(app.key)
        order.addAll(index.coerceIn(0, order.size), group.keys.filter { it !in order })
        saveFolders(folders().filter { it.id != group.id })
        settings.hiddenApps = settings.hiddenApps - group.keys.toSet()
        previewOrder = null
        settings.order = order
        rebuild()
    }

    private fun removeFolderMember(app: AppInfo, key: String) {
        val group = folders().firstOrNull { it.id == folderId(app) } ?: return
        val remaining = group.keys.filter { it != key }
        val order = currentOrder().toMutableList()
        val index = order.indexOf(app.key).let { if (it < 0) order.size else it }
        if (remaining.size < 2) {
            order.remove(app.key)
            order.addAll(index.coerceIn(0, order.size), (remaining + key).filter { it !in order })
            saveFolders(folders().filter { it.id != group.id })
        } else {
            order.add((index + 1).coerceIn(0, order.size), key)
            saveFolders(folders().map { if (it.id == group.id) it.copy(keys = remaining) else it })
        }
        settings.hiddenApps = settings.hiddenApps - key
        previewOrder = null
        settings.order = order
        rebuild()
    }

    /** Commit removal only after the drag has a valid destination. */
    private fun detachFolderMemberForDrag(d: DragState): Boolean {
        val id = d.originFolderId ?: return true
        val groups = folders()
        val group = groups.firstOrNull { it.id == id } ?: return false
        val extraction = FolderExtraction.take(group.keys, d.app.key,
            folderComponent(id).flattenToString(), currentOrder()) ?: return false
        saveFolders(if (extraction.dissolve) groups.filter { it.id != id }
            else groups.map { if (it.id == id) it.copy(keys = extraction.remaining) else it })
        settings.hiddenApps = settings.hiddenApps - d.app.key
        previewOrder = null
        settings.order = extraction.order
        return true
    }

    private fun dropOnHome(d: DragState, rawX: Float, rawY: Float, onNewPage: Boolean = false) {
        val hit = gridHit(rawX, rawY, allowEdgeDrop = onNewPage)
        val targetApp = if (hit == null) null else hit.page.apps.getOrNull(hit.appIndex)?.first
        // The new page can still be laying out when the finger is released.
        val cell = hit?.cell ?: if (onNewPage) newPageCellAt(rawX, rawY) else null
        val targetSlot = cell?.let { slotIndexFor(pager.currentItem, it) }
        if (targetSlot == null) {
            previewOrder = null
            if (d.fromDock && folderId(d.app) == null) {
                // Pulled out of the dock onto open space: it leaves the dock (which shrinks to
                // fit) and lands in the first free spot on the Home Screen, like iOS.
                saveDock(dockKeys().filter { it != d.app.key })
                settings.hiddenApps = settings.hiddenApps - d.app.key
                settings.order = currentOrder().filter { it != d.app.key } + d.app.key
            } else {
                toast("Choose a free app space")
            }
            rebuild(); return
        }
        // Dropping back on the source folder leaves it unchanged.
        if (d.originFolderId != null && targetApp != null && folderId(targetApp) == d.originFolderId) {
            previewOrder = null
            rebuild(); return
        }
        if (!detachFolderMemberForDrag(d)) { previewOrder = null; rebuild(); return }
        if (hit != null && hit.centered && targetApp != null && putIntoFolder(d, targetApp)) return
        val order = currentOrder()
        val moved = if (targetApp == null) HomeGridOrder.moveToEmpty(order, d.app.key, targetSlot)
            else HomeGridOrder.moveAround(order, d.app.key, targetApp.key, hit!!.localX > cellW() / 2f)
        if (d.fromDock) saveDock(dockKeys().filter { it != d.app.key })
        settings.hiddenApps = settings.hiddenApps - d.app.key
        previewOrder = null
        settings.order = moved
        rebuild()
    }

    /** Resolve a drop even if ViewPager2 has not attached the newly added page yet. */
    private fun newPageCellAt(rawX: Float, rawY: Float): Int? {
        val loc = IntArray(2)
        pager.getLocationOnScreen(loc)
        val width = cellW() * cols
        val x = rawX - loc[0] - dp(PAGE_PAD)
        val y = rawY - loc[1] - dp(6)
        if (x < -dp(28) || x >= width + dp(28) || y < 0 || y >= dp(CELL_H) * rowsPerPage()) return null
        val col = (x.coerceIn(0f, (width - 1).toFloat()) / cellW()).toInt()
        val row = (y / dp(CELL_H)).toInt()
        return row * cols + col
    }

    // ================================================================ gestures

    private fun onSwipeDown() {
        when (settings.swipeDown) {
            "search" -> openSearch()
            "notifications" -> openNotifications()
        }
    }

    private fun onSwipeUp() {
        when (settings.swipeUp) {
            "library" -> if (settings.libraryOn) pager.setCurrentItem(pages.size - 1, anim > 0f)
        }
    }

    private fun lockScreen() {
        if (Build.VERSION.SDK_INT >= 28 &&
            LuncherAccessibility.perform(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
        ) return
        askForGestureHelper("Double-Tap to Lock")
    }

    private fun askForGestureHelper(feature: String) {
        showContextMenu(null, listOf(
            MenuItem("Turn on Apple Launcher Gestures for $feature…") {
                runCatching { startActivity(Intent(SysSettings.ACTION_ACCESSIBILITY_SETTINGS)) }
            },
            MenuItem("Not Now") {}
        ))
    }

    @SuppressLint("WrongConstant")
    private fun openNotifications() {
        if (LuncherAccessibility.perform(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)) return
        runCatching {
            val sbs = getSystemService("statusbar")
            val cls = Class.forName("android.app.StatusBarManager")
            cls.getMethod("expandNotificationsPanel").invoke(sbs)
        }.onFailure { openSearch() }
    }

    // ================================================================ App Switcher

    private fun openSwitcher() {
        val entries = switcherEntries()
        haptic(root, HapticFeedbackConstants.CONTEXT_CLICK)
        animateBlur(dp(24).toFloat(), dur(300))
        if (anim > 0f) {
            contentAnimate().scaleX(0.92f).scaleY(0.92f).alpha(0.6f).setDuration(dur(300)).setInterpolator(EASE).start()
        }
        switcher.show(entries, if (anim == 0f) 0.01f else anim, insetTop, insetBottom)
    }

    private fun switcherEntries(): List<AppSwitcher.Entry> {
        val byPkg = allApps.groupBy { it.packageName }
        val dismissed = { pkg: String -> prefs.getLong("sw_dismiss_$pkg", 0L) }
        if (SettingsActivity.usageAccessGranted(this)) {
            runCatching {
                val usm = getSystemService(UsageStatsManager::class.java)
                val end = System.currentTimeMillis()
                val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, end - 3L * 24 * 3600 * 1000, end)
                val last = HashMap<String, Long>()
                for (st in stats) {
                    if (st.lastTimeUsed <= 0L || st.totalTimeInForeground <= 0L) continue
                    if (st.packageName == packageName) continue
                    last[st.packageName] = maxOf(last[st.packageName] ?: 0L, st.lastTimeUsed)
                }
                return last.entries
                    .filter { (pkg, t) -> byPkg.containsKey(pkg) && t > dismissed(pkg) }
                    .sortedByDescending { it.value }
                    .take(15)
                    .mapNotNull { (pkg, t) -> byPkg[pkg]?.firstOrNull()?.let { AppSwitcher.Entry(it, t) } }
            }
        }
        val byKey = allApps.associateBy { it.key }
        return recentKeys().mapNotNull { k ->
            byKey[k]?.let { AppSwitcher.Entry(it, prefs.getLong("rt_$k", 0L)) }
        }
    }

    private fun dismissFromSwitcher(app: AppInfo) {
        prefs.edit()
            .putLong("sw_dismiss_${app.packageName}", System.currentTimeMillis())
            .putString(KEY_RECENT, recentKeys().filter { it != app.key }.joinToString("|"))
            .apply()
    }

    // ================================================================ Now Playing

    private fun hasNotificationAccess(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)

    private val listenerComponent by lazy { ComponentName(this, NotificationService::class.java) }

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list -> pickSession(list) }

    private val mediaCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = updateMusic()
        override fun onPlaybackStateChanged(state: PlaybackState?) = updateMusic()
        override fun onSessionDestroyed() {
            mediaController?.unregisterCallback(this)
            mediaController = null
            updateMusic()
        }
    }

    private fun setupMedia() {
        if (!hasNotificationAccess()) {
            updateMusic()
            return
        }
        val msm = getSystemService(MediaSessionManager::class.java) ?: return
        runCatching {
            if (!mediaListening) {
                msm.addOnActiveSessionsChangedListener(sessionsListener, listenerComponent, mainHandler)
                mediaListening = true
            }
            pickSession(msm.getActiveSessions(listenerComponent))
        }
    }

    private fun pickSession(list: List<MediaController>?) {
        val playing = list?.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
        val chosen = playing ?: list?.firstOrNull()
        if (chosen?.sessionToken != mediaController?.sessionToken) {
            mediaController?.unregisterCallback(mediaCallback)
            mediaController = chosen
            chosen?.registerCallback(mediaCallback, mainHandler)
        }
        updateMusic()
    }

    private fun updateMusic() {
        refreshIslandMusic()
        val v = musicViews ?: return
        val c = mediaController
        val meta = c?.metadata
        when {
            !hasNotificationAccess() -> {
                v.body.visibility = View.INVISIBLE
                v.hint.visibility = View.VISIBLE
                v.hint.text = "Tap to show your music here"
                v.bg.setImageDrawable(null)
            }
            (c == null || meta == null) && settings.musicKeepLast && prefs.getString("last_song_title", null) != null -> {
                // Music stopped (or the phone just started): keep the last song, ready to resume.
                v.body.visibility = View.VISIBLE
                v.hint.visibility = View.GONE
                v.title.text = prefs.getString("last_song_title", "")
                v.artist.text = prefs.getString("last_song_artist", "")
                val art = lastSongArt()
                applyPlayerTone(art)
                if (art != null) { v.art.clearColorFilter(); v.art.setImageBitmap(art); v.bg.setImageBitmap(art) }
                else { v.art.setImageResource(R.drawable.ic_music); v.art.setColorFilter(0xB3FFFFFF.toInt()); v.bg.setImageDrawable(null) }
                val pkg = prefs.getString("last_song_pkg", null)
                v.source.text = pkg?.let { p -> runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(p, 0)) }.getOrNull() } ?: ""
                v.play.setImageResource(R.drawable.ic_play)
            }
            c == null || meta == null -> {
                v.body.visibility = View.INVISIBLE
                v.hint.visibility = View.VISIBLE
                v.hint.text = "Not Playing"
                v.bg.setImageDrawable(null)
            }
            else -> {
                v.body.visibility = View.VISIBLE
                v.hint.visibility = View.GONE
                v.title.text = meta.getString(MediaMetadata.METADATA_KEY_TITLE)
                    ?: meta.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: "Unknown"
                v.artist.text = meta.getString(MediaMetadata.METADATA_KEY_ARTIST)
                    ?: meta.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: ""
                val art = meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                    ?: meta.getBitmap(MediaMetadata.METADATA_KEY_ART)
                    ?: meta.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
                applyPlayerTone(art)
                if (art != null) {
                    v.art.clearColorFilter()
                    v.art.setImageBitmap(art)
                    v.bg.setImageBitmap(art)
                } else {
                    v.art.setImageResource(R.drawable.ic_music)
                    v.art.setColorFilter(0xB3FFFFFF.toInt())
                    v.bg.setImageDrawable(null)
                }
                v.source.text = runCatching {
                    packageManager.getApplicationLabel(packageManager.getApplicationInfo(c.packageName, 0))
                }.getOrNull() ?: ""
                rememberSong(v.title.text.toString(), v.artist.text.toString(), c.packageName, art)
                val playing = c.playbackState?.state == PlaybackState.STATE_PLAYING
                v.play.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
            }
        }
        // Vinyl player: the record spins only while music plays.
        vinylSpin?.let { a ->
            val playing = mediaController?.playbackState?.state == PlaybackState.STATE_PLAYING
            if (playing) { if (a.isPaused) a.resume() else if (!a.isStarted) a.start() } else if (a.isRunning) a.pause()
        }
    }

    /** Remember the song so the widget can keep showing it after the music stops. */
    private fun rememberSong(title: String, artist: String, pkg: String, art: android.graphics.Bitmap?) {
        GilbertMemory.song(this, title, artist)
        val changed = prefs.getString("last_song_title", null) != title || prefs.getString("last_song_pkg", null) != pkg
        if (!changed) return
        prefs.edit().putString("last_song_title", title).putString("last_song_artist", artist).putString("last_song_pkg", pkg).apply()
        lastArtCache = art
        val f = java.io.File(filesDir, "last_song_art.png")
        if (art == null) { f.delete(); return }
        val copy = runCatching { android.graphics.Bitmap.createScaledBitmap(art, minOf(art.width, 400), minOf(art.height, 400), true) }.getOrNull() ?: return
        Thread { runCatching { f.outputStream().use { copy.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, it) } } }.start()
    }
    private var lastArtCache: android.graphics.Bitmap? = null
    private fun lastSongArt(): android.graphics.Bitmap? = lastArtCache ?: runCatching {
        android.graphics.BitmapFactory.decodeFile(java.io.File(filesDir, "last_song_art.png").path)
    }.getOrNull()?.also { lastArtCache = it }

    /** Send a play / next / previous key to the phone's last music app (when no player is active). */
    private fun mediaKey(code: Int) {
        val am = getSystemService(android.media.AudioManager::class.java) ?: return
        runCatching {
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        }
    }

    private fun togglePlay() {
        val c = mediaController ?: run {
            // Nothing active: resume the last song in its app.
            mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)
            musicViews?.play?.setImageResource(R.drawable.ic_pause)
            return
        }
        if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause()
        else c.transportControls.play()
    }

    private fun musicTapped(from: View) {
        when {
            !hasNotificationAccess() -> runCatching {
                startActivity(Intent(SysSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                toast("Turn on Apple Launcher to see music and badges")
            }
            mediaController != null -> {
                val launch = packageManager.getLaunchIntentForPackage(mediaController!!.packageName)
                if (launch != null) openIntent(launch, from)
            }
            settings.musicKeepLast && prefs.getString("last_song_pkg", null) != null -> {
                val launch = packageManager.getLaunchIntentForPackage(prefs.getString("last_song_pkg", null)!!)
                if (launch != null) openIntent(launch, from)
                else openIntent(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MUSIC), from)
            }
            else -> openIntent(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MUSIC), from)
        }
    }

    // ================================================================ photos

    private fun photoTapped(id: String, anchor: View) {
        if (prefs.getString("photo_$id", null) == null) pickPhoto(id) else showPhotoMenu(id, anchor)
    }

    @Suppress("DEPRECATION")
    private fun pickPhoto(id: String) {
        pendingPhotoId = id
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
        }
        runCatching { startActivityForResult(i, REQ_PHOTO) }
            .onFailure { toast("No photo picker available") }
    }

    private fun showPhotoMenu(id: String, anchor: View) {
        val bw = prefs.getBoolean("photo_bw_$id", true)
        val items = mutableListOf(
            MenuItem("Choose Photo") { pickPhoto(id) },
            MenuItem(if (bw) "Show in Color" else "Black & White") {
                prefs.edit().putBoolean("photo_bw_$id", !bw).apply(); refreshPages()
            },
            MenuItem(if (prefs.getBoolean("photo_spatial_$id", true)) "Turn Off Spatial Scene" else "Turn On Spatial Scene") {
                prefs.edit().putBoolean("photo_spatial_$id", !prefs.getBoolean("photo_spatial_$id", true)).apply()
                refreshPages()
            }
        )
        if (prefs.getString("photo_$id", null) != null) {
            items += MenuItem("Remove Photo", destructive = true) {
                prefs.edit().remove("photo_$id").apply()
                photoCache.remove(id)
                refreshPages()
            }
        }
        items += MenuItem("Edit Home Screen") { enterEditMode() }
        showContextMenu(anchor, items)
    }

    private fun decodeSampled(uri: Uri, maxPx: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxPx && bounds.outHeight / (sample * 2) >= maxPx) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }.getOrNull()

    // ================================================================ context menu (iOS-style)

    private class MenuItem(val title: String, val destructive: Boolean = false, val action: () -> Unit)

    private fun showContextMenu(anchor: View?, items: List<MenuItem>, lift: Boolean = true) {
        if (menuLayer != null) dismissMenu(immediate = true)
        haptic(anchor ?: root)

        val layer = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(70, 0, 0, 0))
            alpha = 0f
            isClickable = true
            setOnClickListener { dismissMenu() }
        }

        var anchorX = root.width / 2
        var anchorY = root.height / 2
        var anchorW = 0
        var anchorH = 0
        if (anchor != null && anchor.isAttachedToWindow && anchor.width > 0) {
            val loc = IntArray(2)
            val rootLoc = IntArray(2)
            anchor.getLocationInWindow(loc)
            root.getLocationInWindow(rootLoc)
            anchorX = loc[0] - rootLoc[0]
            anchorY = loc[1] - rootLoc[1]
            anchorW = anchor.width
            anchorH = anchor.height
            if (lift) runCatching {
                anchor.scaleX = 1f; anchor.scaleY = 1f; anchor.alpha = 1f
                val bmp = anchor.drawToBitmap()
                val preview = ImageView(this).apply {
                    setImageBitmap(bmp)
                    outlineProvider = roundedOutline(if (anchor.width > dp(iconDp + 10)) 22f else null)
                    clipToOutline = true
                    elevation = dp(12).toFloat()
                }
                layer.addView(preview, FrameLayout.LayoutParams(anchor.width, anchor.height).apply {
                    leftMargin = anchorX; topMargin = anchorY
                })
                if (anim > 0f) {
                    preview.animate().scaleX(1.08f).scaleY(1.08f).setDuration(dur(320)).setInterpolator(SPRING).start()
                }
            }
        }

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Glass.menu(this@MainActivity, 14f)
            outlineProvider = roundedOutline(14f)
            clipToOutline = true
            elevation = dp(16).toFloat()
        }
        items.forEachIndexed { i, item ->
            if (i > 0) panel.addView(View(this).apply { setBackgroundColor(0x2EFFFFFF) },
                LinearLayout.LayoutParams(MATCH_PARENT, maxOf(1, dp(0.6f))))
            panel.addView(TextView(this).apply {
                text = item.title
                textSize = 16f
                setTextColor(if (item.destructive) 0xFFFF453A.toInt() else Color.WHITE)
                setPadding(dp(16), dp(13), dp(16), dp(13))
                setOnClickListener {
                    dismissMenu()
                    mainHandler.postDelayed({ item.action() }, dur(140).coerceAtLeast(1))
                }
                background = android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(0x22FFFFFF), null, null
                )
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }

        val menuW = dp(260)
        panel.measure(
            View.MeasureSpec.makeMeasureSpec(menuW, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val menuH = panel.measuredHeight
        val gap = dp(12)
        val margin = dp(14)
        val hasAnchor = anchor != null && anchorW > 0
        val below = anchorY + anchorH + gap + menuH < root.height - dp(40)
        val top = if (!hasAnchor) (root.height - menuH) / 2
        else if (below) anchorY + anchorH + gap else (anchorY - gap - menuH).coerceAtLeast(margin)
        val left = if (!hasAnchor) (root.width - menuW) / 2
        else anchorX.coerceIn(margin, (root.width - menuW - margin).coerceAtLeast(margin))
        layer.addView(panel, FrameLayout.LayoutParams(menuW, WRAP_CONTENT).apply {
            leftMargin = left; topMargin = top
        })

        root.addView(layer, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        menuLayer = layer

        if (anim > 0f) {
            panel.pivotX = (anchorX + anchorW / 2f - left).coerceIn(0f, menuW.toFloat())
            panel.pivotY = if (below || !hasAnchor) 0f else menuH.toFloat()
            panel.alpha = 0f
            Motion.scaleTo(panel, 1f, Motion.BOUNCY, start = 0.4f)
            Motion.alphaTo(panel, 1f, Motion.SNAPPY)
            layer.animate().alpha(1f).setDuration(dur(200)).setInterpolator(EASE).start()
            contentAnimate().scaleX(0.97f).scaleY(0.97f).setDuration(dur(260)).setInterpolator(EASE).start()
        } else {
            layer.alpha = 1f
        }
        animateBlur(dp(22).toFloat(), dur(260))
    }

    private fun dismissMenu(immediate: Boolean = false) {
        val layer = menuLayer ?: return
        menuLayer = null
        val fast = immediate || anim == 0f
        animateBlur(0f, if (fast) 1 else dur(220))
        contentAnimate().scaleX(1f).scaleY(1f).setDuration(if (fast) 1 else dur(260)).setInterpolator(EASE).start()
        if (fast) {
            root.removeView(layer)
            return
        }
        layer.animate().alpha(0f).setDuration(dur(180)).setInterpolator(EASE)
            .withEndAction { root.removeView(layer) }.start()
    }

    // ================================================================ search

    private fun openSearch() {
        if (::island.isInitialized) island.setSearching(true)
        ToolsBar.current?.setSearching(true)
        searchingNow = true; tapZone?.setHidden(true)
        updateGilbertMood()
        searchField.setText("")
        filterSearch("")
        searchOverlay.visibility = View.VISIBLE
        if (anim > 0f) {
            searchOverlay.alpha = 0f
            searchOverlay.translationY = -dp(30).toFloat()
            searchOverlay.animate().alpha(1f).translationY(0f).setDuration(dur(320)).setInterpolator(EASE).start()
            contentAnimate().scaleX(0.94f).scaleY(0.94f).setDuration(dur(320)).setInterpolator(EASE).start()
        } else {
            searchOverlay.alpha = 1f
            searchOverlay.translationY = 0f
        }
        animateBlur(dp(26).toFloat(), dur(320))
        searchField.requestFocus()
        searchField.postDelayed({
            imm().showSoftInput(searchField, InputMethodManager.SHOW_IMPLICIT)
        }, 150)
    }

    private fun closeSearch(animate: Boolean = true) {
        if (::island.isInitialized) island.setSearching(false)
        ToolsBar.current?.setSearching(false)
        searchingNow = false; tapZone?.setHidden(GameState.playing)
        updateGilbertMood()
        imm().hideSoftInputFromWindow(searchField.windowToken, 0)
        searchField.clearFocus()
        val fast = !animate || anim == 0f
        animateBlur(0f, if (fast) 1 else dur(260))
        if (fast) {
            searchOverlay.visibility = View.GONE
            content.scaleX = 1f; content.scaleY = 1f
            return
        }
        contentAnimate().scaleX(1f).scaleY(1f).setDuration(dur(300)).setInterpolator(EASE).start()
        searchOverlay.animate().alpha(0f).translationY(-dp(30).toFloat()).setDuration(dur(220))
            .setInterpolator(EASE).withEndAction { searchOverlay.visibility = View.GONE }.start()
    }

    private fun filterSearch(q: String) {
        // Gilbert reads along as you type, and smiles when there are results.
        if (::island.isInitialized && settings.gilbertEmotions && q.isNotEmpty()) {
            island.faceLookAt(((q.length % 8) / 3.5f) - 1f, 0.4f)
        }
        val query = q.trim()
        searchResults = if (query.isEmpty()) {
            val byKey = allApps.associateBy { it.key }
            val recents = recentKeys().mapNotNull { byKey[it] }
            val recentSet = recents.map { it.key }.toSet()
            (recents + allApps.filter { it.key !in recentSet }).take(8)
        } else {
            allApps.filter { it.label.contains(query, true) }
                .sortedBy { !it.label.startsWith(query, true) }
        }
        searchHeader.text = if (query.isEmpty()) "Suggestions" else if (searchResults.isEmpty()) "" else "Apps"
        searchAdapter.submit(searchResults)

        val math = if (Calc.looksLikeMath(query)) Calc.eval(query) else null
        if (math != null) {
            calcRow.text = "= ${Calc.format(math)}"
            calcRow.visibility = View.VISIBLE
        } else {
            calcRow.visibility = View.GONE
        }
        // Shortcuts/settings/contacts wait until typing pauses for a moment (keeps typing smooth).
        pendingExtras = query
        mainHandler.removeCallbacks(extrasTask)
        mainHandler.postDelayed(extrasTask, 90)
        if (query.isNotEmpty() && math == null) {
            webRow.text = "Search the web for “$query”"
            webRow.visibility = View.VISIBLE
        } else {
            webRow.visibility = View.GONE
        }
    }

    private fun searchWeb(q: String) {
        val query = q.trim()
        if (query.isEmpty()) return
        val web = Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, query)
        val ok = runCatching { startActivity(web.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess ||
            runCatching {
                startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(query)))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }.isSuccess
        if (ok) closeSearch(animate = false)
    }

    private fun imm() = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

    // ================================================================ actions

    private fun launch(app: AppInfo, from: View?) {
        if (editMode) return
        if (folderId(app) != null) {
            openFolder(app)
            return
        }
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(app.component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        startWithAnimation(intent, from) {
            recordRecent(app)
            if (searchOverlay.visibility == View.VISIBLE) closeSearch(animate = false)
        }
    }

    /**
     * How apps open/close. "match" (default) follows your Page Swipe style so every animation in
     * the launcher feels like one family.
     */
    private fun launchStyle(): String = when (val l = settings.launchAnim) {
        "match" -> when (settings.pageEffect) {
            "fade", "flip" -> "fade"
            "stack", "flat", "roll", "cube" -> "slide"
            else -> "zoom"   // depth, zoom, card, tilt
        }
        else -> l
    }

    private fun launchOptions(from: View?): Bundle? {
        if (anim == 0f) return ActivityOptions.makeCustomAnimation(this, 0, 0).toBundle()
        return when (launchStyle()) {
            "zoom" -> from?.let { ActivityOptions.makeScaleUpAnimation(it, 0, 0, it.width, it.height).toBundle() }
            "fade" -> ActivityOptions.makeCustomAnimation(this, android.R.anim.fade_in, android.R.anim.fade_out).toBundle()
            "slide" -> ActivityOptions.makeCustomAnimation(this, R.anim.slide_up_in, R.anim.hold).toBundle()
            else -> ActivityOptions.makeCustomAnimation(this, 0, 0).toBundle()
        }
    }

    private fun startWithAnimation(intent: Intent, from: View?, onStarted: () -> Unit = {}) {
        runCatching { startActivity(intent, launchOptions(from)) }
            .onSuccess {
                onStarted()
                if (anim > 0f && launchStyle() == "zoom") {
                    contentAnimate().scaleX(1.12f).scaleY(1.12f).alpha(0f)
                        .setDuration(dur(320)).setInterpolator(EASE).withLayer().start()
                    animateBlur(dp(18).toFloat(), dur(300))
                    mainHandler.postDelayed({ if (isActive) restoreHome(animated = true) }, 900)
                }
            }
            .onFailure { toast("Can't open this") }
    }

    private fun openIntent(intent: Intent, from: View?) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startWithAnimation(intent, from)
    }

    private fun openCalendar(from: View?) =
        openIntent(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALENDAR), from)

    private fun openSettings() {
        if (menuLayer != null) dismissMenu(immediate = true)
        runCatching { startActivity(Intent(this, SettingsActivity::class.java), launchOptions(null)) }
    }

    private fun showAppMenu(app: AppInfo, anchor: View) {
        if (folderId(app) != null) {
            showContextMenu(anchor, listOf(
                MenuItem("Open Folder") { openFolder(app) },
                MenuItem("Rename Folder") { renameFolder(app) },
                MenuItem("Ungroup Apps") { ungroupFolder(app) }
            ))
            return
        }
        val keys = dockKeys()
        val inDock = app.key in keys
        val hidden = app.key in settings.hiddenApps
        val items = mutableListOf(
            MenuItem("Edit Home Screen") { enterEditMode() },
            MenuItem(if (inDock) "Remove from Dock" else "Add to Dock") {
                if (inDock) setDock(keys - app.key)
                else if (keys.size >= cols) toast("Dock is full — remove an app from it first")
                else {
                    settings.hiddenApps = settings.hiddenApps - app.key
                    setDock(keys + app.key)
                }
            }
        )
        if (hidden) {
            items += MenuItem("Add to Home Screen") {
                settings.hiddenApps = settings.hiddenApps - app.key
                rebuild()
            }
        } else if (!inDock) {
            items += MenuItem("Remove from Home Screen") { removeFromHome(app, false) }
        }
        items += MenuItem("App Info") {
            runCatching {
                startActivity(
                    Intent(SysSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
        items += MenuItem("Delete App", destructive = true) {
            runCatching {
                startActivity(
                    Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
        showContextMenu(anchor, items)
    }

    private fun showHomeMenu(anchor: View) {
        val items = mutableListOf(
            MenuItem("Edit Home Screen") { enterEditMode() },
            MenuItem("Add Widgets…") { showRestoreWidgetsMenu(anchor) },
            MenuItem("Manage Pages…") { showPageMenu() },
            MenuItem("Apple Launcher Settings…") { openSettings() },
            MenuItem("Volume…") { showVolumePanel() },
            MenuItem("Visual Look Up") { visualLookUp() },
            MenuItem("Use Dark Glass Wallpaper") { applyGlassWallpaper() },
            MenuItem("Change Wallpaper…") {
                runCatching {
                    startActivity(Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), "Choose wallpaper"))
                }
            }
        )
        if (!hasNotificationAccess()) {
            items += MenuItem("Turn On Badges & Music") {
                runCatching { startActivity(Intent(SysSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
            }
        }
        showContextMenu(null, items)
    }

    // ================================================================ iOS 26-style extras

    private fun showVolumePanel() {
        if (menuLayer != null) dismissMenu(immediate = true)
        VolumePanel.show(this, root, insetBottom)
    }

    /** "Siri": swipe down on the island opens the chosen assistant (Google, Gemini or ChatGPT). */
    /** AI removed: swiping or holding the island no longer opens an assistant. */
    private fun openAssistant() {}

    /** Point the camera at something to search it (Google Lens), like Visual Intelligence. */
    private fun visualLookUp() {
        val lens = packageManager.getLaunchIntentForPackage("com.google.ar.lens")
        if (lens != null) { openIntent(lens, null); return }
        val ok = runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("googleapp://lens")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
        if (!ok) openIntent(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA), null)
    }

    /** Keep the island's music activity in sync with what's playing. */
    private val pausedToGilbert: Runnable = object : Runnable {
        override fun run() {
            if (!::island.isInitialized) return
            val st = mediaController?.playbackState?.state
            if (st == PlaybackState.STATE_PLAYING || st == PlaybackState.STATE_BUFFERING) return
            if (island.isExpanded() || overlayIsland?.isExpanded() == true) { mainHandler.postDelayed(this, 3000); return }
            island.remove("music")
        }
    }

    private fun refreshIslandMusic() {
        if (!::island.isInitialized) return
        val c = mediaController
        val meta = c?.metadata
        val state = c?.playbackState?.state
        val playing = state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING
        if (c == null || meta == null || !(playing || state == PlaybackState.STATE_PAUSED)) {
            mainHandler.removeCallbacks(pausedToGilbert); island.remove("music"); return
        }
        // Nothing playing (paused): after a few seconds the island goes back to being Gilbert.
        // (Not while you have the player open — you might be about to press play.)
        mainHandler.removeCallbacks(pausedToGilbert)
        if (!playing) mainHandler.postDelayed(pausedToGilbert, 4000)
        val title = meta.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: meta.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: "Now Playing"
        val artist = meta.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""
        val art = meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: meta.getBitmap(MediaMetadata.METADATA_KEY_ART)
        val pkg = c.packageName
        // Settings › Island Services › Audio Sources
        if (settings.islandAudioMode == "chosen" && pkg !in settings.islandAudioApps) { island.remove("music"); return }
        val appIcon = allApps.firstOrNull { it.packageName == pkg }?.icon
        val duration = meta.getLong(MediaMetadata.METADATA_KEY_DURATION)
        val media = buildMedia(c, appIcon, duration, pkg)
        island.put(IslandActivity(
            "music", art, R.drawable.ic_music, Color.WHITE, title, artist, null, playing, 0xFFFF9F0A.toInt(),
            media = media
        ))
    }

    // ================================================================ Island services

    private val ongoingTicker = object : Runnable {
        override fun run() {
            refreshOngoing()
        }
    }

    /** Mirror ongoing activities (navigation, clock, calls, recording, transfers, sport) into the island. */
    private fun refreshOngoing() {
        if (!::island.isInitialized) return
        mainHandler.removeCallbacks(ongoingTicker)
        val items = NotificationService.ongoing.values.filter { settings.islandAllows(it.type) }.takeLast(2)
        val wanted = items.map { "ongoing:${it.type}:${it.key}" }.toSet()
        for (id in island.ids()) if (id.startsWith("ongoing:") && id !in wanted) island.remove(id)
        var ticking = false
        for (o in items) {
            val app = allApps.firstOrNull { it.packageName == o.pkg }
            val accent = when (o.type) {
                "navigation", "call" -> 0xFF30D158.toInt()
                "clock" -> 0xFFFF9F0A.toInt()
                "recording" -> 0xFFFF453A.toInt()
                "workout" -> 0xFFA7F432.toInt()
                else -> 0xFF0A84FF.toInt()
            }
            val acts = ArrayList<Pair<String, () -> Unit>>()
            for ((name, pi) in o.actions) acts += name to { IslandServices.send(this, pi) }
            o.contentIntent?.let { pi -> acts += "Open" to { IslandServices.send(this, pi) } }
            if (o.chronoBase > 0) ticking = true
            island.put(IslandActivity("ongoing:${o.type}:${o.key}", app?.icon, R.drawable.ic_bolt, accent,
                o.title, o.text, o.trailing(), o.type == "recording", accent, acts.take(4)))
        }
        if (ticking && (isActive || overlayIsland != null)) mainHandler.postDelayed(ongoingTicker, 1000)
    }

    /** Copy & go: when something useful was just copied, offer what to do with it. */
    private fun checkCopyAndGo() {
        if (!::island.isInitialized || !settings.island || !settings.islandAllows("copy")) return
        val c = IslandServices.checkClipboard(this) ?: return
        val acts = ArrayList<Pair<String, () -> Unit>>()
        fun go(i: Intent) {
            runCatching { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                .onFailure { toast("No app can open this") }
        }
        val (title, icon) = when (c.kind) {
            "phone" -> {
                acts += "Call" to { go(IslandServices.dial(c.text)) }
                acts += "Message" to { go(IslandServices.sms(c.text)) }
                acts += "Save" to { go(IslandServices.saveContact(c.text)) }
                "Copied number" to "📞"
            }
            "meeting" -> {
                acts += "Join" to { go(IslandServices.open(c.text)) }
                acts += "Share" to { go(IslandServices.share(c.text)) }
                "Copied meeting" to "📅"
            }
            else -> {
                acts += "Open" to { go(IslandServices.open(c.text)) }
                acts += "Share" to { go(IslandServices.share(c.text)) }
                "Copied link" to "🔗"
            }
        }
        // Pinned services: send straight to your favourite apps.
        for (pkg in settings.islandPinned.take(2)) {
            val label = allApps.firstOrNull { it.packageName == pkg }?.label ?: continue
            acts += label to { go(IslandServices.share(c.text, pkg)) }
        }
        island.flash(IslandActivity("copy", null, R.drawable.ic_search, Color.WHITE,
            "$icon $title", c.text, null, false, 0xFF0A84FF.toInt(), acts.take(4)), 8000)
        haptic(root, HapticFeedbackConstants.CONTEXT_CLICK)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Android only lets the app in front read the clipboard.
        if (hasFocus) mainHandler.postDelayed({ runCatching { checkCopyAndGo() } }, 350)
    }

    private var recordingCallback: android.media.AudioManager.AudioRecordingCallback? = null
    private var cameraCallback: android.hardware.camera2.CameraManager.AvailabilityCallback? = null
    private val camerasInUse = HashSet<String>()

    /** Microphone & camera in use: like the iPhone's orange/green dots, but in the island. */
    private fun startPrivacyWatch() {
        val am = getSystemService(android.media.AudioManager::class.java)
        val rc = object : android.media.AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: MutableList<android.media.AudioRecordingConfiguration>?) {
                if (!::island.isInitialized) return
                // Our own "voice activation" listening isn't another app using the mic.
                if (!configs.isNullOrEmpty() && !VoiceService.running) island.put(IslandActivity("mic", null, R.drawable.ic_bolt,
                    0xFFFF9F0A.toInt(), "Microphone in use", "An app is recording audio", "●", true, 0xFFFF9F0A.toInt()))
                else island.remove("mic")
            }
        }
        runCatching { am.registerAudioRecordingCallback(rc, mainHandler); recordingCallback = rc }
        val cm = getSystemService(android.hardware.camera2.CameraManager::class.java)
        val cc = object : android.hardware.camera2.CameraManager.AvailabilityCallback() {
            override fun onCameraUnavailable(cameraId: String) { camerasInUse += cameraId; update() }
            override fun onCameraAvailable(cameraId: String) { camerasInUse -= cameraId; update() }
            fun update() {
                if (!::island.isInitialized) return
                if (camerasInUse.isNotEmpty()) island.put(IslandActivity("camera", null, R.drawable.ic_bolt,
                    0xFF30D158.toInt(), "Camera in use", "An app is using the camera", "●", false, 0xFF30D158.toInt()))
                else island.remove("camera")
            }
        }
        runCatching { cm.registerAvailabilityCallback(cc, mainHandler); cameraCallback = cc }
    }

    private fun stopPrivacyWatch() {
        recordingCallback?.let { cb -> runCatching { getSystemService(android.media.AudioManager::class.java).unregisterAudioRecordingCallback(cb) } }
        cameraCallback?.let { cb -> runCatching { getSystemService(android.hardware.camera2.CameraManager::class.java).unregisterAvailabilityCallback(cb) } }
    }

    private fun buildMedia(c: MediaController, appIcon: Bitmap?, duration: Long, pkg: String): IslandMedia {
        return IslandMedia(
            appIcon = appIcon,
            duration = duration,
            position = {
                val ps = c.playbackState
                if (ps == null) 0L else if (ps.state == PlaybackState.STATE_PLAYING)
                    ps.position + ((SystemClock.elapsedRealtime() - ps.lastPositionUpdateTime) * ps.playbackSpeed).toLong()
                else ps.position
            },
            playing = { c.playbackState?.state == PlaybackState.STATE_PLAYING },
            outputIconRes = if (headphonesConnected()) R.drawable.ic_headphones else R.drawable.ic_speaker,
            onPlayPause = { togglePlay() },
            onPrev = { c.transportControls.skipToPrevious() },
            onNext = { c.transportControls.skipToNext() },
            onSeek = { pos -> c.transportControls.seekTo(pos) },
            onAdd = { addCurrentSong(c) },
            onOutput = { openMediaOutput(pkg) }
        )
    }

    /** The playing song for Control Center's music tile (null when nothing is playing). */
    private fun currentIslandMedia(): IslandMedia? {
        val c = mediaController ?: return null
        val meta = c.metadata ?: return null
        val pkg = c.packageName
        return buildMedia(c, allApps.firstOrNull { it.packageName == pkg }?.icon,
            meta.getLong(MediaMetadata.METADATA_KEY_DURATION), pkg)
    }

    private fun headphonesConnected(): Boolean = runCatching {
        getSystemService(android.media.AudioManager::class.java).getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS).any {
            it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                it.type == android.media.AudioDeviceInfo.TYPE_USB_HEADSET ||
                (Build.VERSION.SDK_INT >= 31 && it.type == android.media.AudioDeviceInfo.TYPE_BLE_HEADSET)
        }
    }.getOrDefault(false)

    /** ⊕: use the player's own "like / save" action when it has one, otherwise open the app. */
    private fun addCurrentSong(c: android.media.session.MediaController) {
        val words = listOf("like", "heart", "save", "add", "favo", "library", "collection", "thumbs_up", "thumb_up")
        val action = c.playbackState?.customActions?.firstOrNull { a ->
            val n = (a.action + " " + a.name).lowercase()
            words.any { n.contains(it) } && !n.contains("unlike") && !n.contains("remove")
        }
        if (action != null) {
            c.transportControls.sendCustomAction(action, null)
            toast("Added: ${action.name}")
        } else {
            packageManager.getLaunchIntentForPackage(c.packageName)?.let {
                runCatching { startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
        }
    }

    /** Opens Android's "play on" picker (speaker, headphones, cast) for the playing app. */
    private fun openMediaOutput(pkg: String) {
        val tries = listOf(
            Intent("com.android.settings.panel.action.MEDIA_OUTPUT")
                .putExtra("com.android.settings.panel.extra.PACKAGE_NAME", pkg),
            Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)
        )
        for (i in tries) {
            val ok = runCatching { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
            if (ok) return
        }
    }

    /** Spatial / extended wallpaper drawn by the launcher itself. */
    private fun loadSpatialWallpaper() {
        if (!::spatialWall.isInitialized) return
        val uri = settings.spatialWallpaperUri
        if (!settings.spatialWallpaper || uri == null) {
            spatialWall.visibility = View.GONE
            spatialWall.bitmap = null
            spatialWallUri = null
            return
        }
        spatialWall.extend = settings.extendWallpaper
        spatialWall.spatial = settings.wallpaperDepth && anim > 0f
        spatialWall.depth = 0.7f
        spatialWall.rotate3d = false
        spatialWall.shiftRange = dp(16).toFloat()
        spatialWall.visibility = View.VISIBLE
        if (uri == spatialWallUri && spatialWall.bitmap != null) return
        spatialWallUri = uri
        val maxPx = maxOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
        io.execute {
            val bmp = decodeSampled(Uri.parse(uri), maxPx / 2)
            mainHandler.post {
                if (bmp != null) {
                    spatialWall.alpha = 0f
                    spatialWall.bitmap = bmp
                    spatialWall.animate().alpha(1f).setDuration(dur(400)).start()
                } else toast("Couldn't open that wallpaper photo")
            }
        }
    }

    // ================================================================ Control Center, Focus, Timer, Library

    /** Start Timer card that pops up after you type "timer" and press Enter. */
    private fun showTimerPopup() {
        if (menuLayer != null) dismissMenu(immediate = true)
        val layer = FrameLayout(this).apply { setBackgroundColor(Color.argb(120, 0, 0, 0)); isClickable = true }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(18), dp(16), dp(16))
            background = Glass.menu(this@MainActivity, 32f)
            isClickable = true
        }
        var closed = false
        lateinit var onTimer: () -> Unit
        fun close() {
            if (closed) return
            closed = true
            TimerState.remove(onTimer)
            animateBlur(0f, dur(220))
            layer.animate().alpha(0f).setDuration(dur(200)).withEndAction { root.removeView(layer) }.start()
        }
        // Close by itself once a timer starts (the countdown carries on in the island).
        val wasRunning = TimerState.running
        onTimer = { if (!wasRunning && TimerState.running) layer.postDelayed({ close() }, 450) }
        TimerState.add(onTimer)
        layer.setOnClickListener { close() }
        card.addView(TextView(this).apply {
            text = "Start Timer"; setTextColor(Color.WHITE); textSize = 22f
            typeface = Typeface.create("sans-serif", Typeface.BOLD); setPadding(dp(6), 0, 0, dp(4))
        })
        card.addView(TextView(this).apply {
            text = "Swipe the ruler to pick a time, then tap it to start."
            setTextColor(0x99FFFFFF.toInt()); textSize = 13f; setPadding(dp(6), 0, 0, dp(12))
        })
        card.addView(controlCenter.timerDial(), LinearLayout.LayoutParams(MATCH_PARENT, dp(96)))
        // Quick picks
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (m in listOf(1, 5, 10, 15, 30)) {
            chips.addView(TextView(this).apply {
                text = "${m}m"; gravity = Gravity.CENTER; setTextColor(Color.WHITE); textSize = 14f
                background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(0x26FFFFFF) }
                setOnClickListener { haptic(it, HapticFeedbackConstants.CONTEXT_CLICK); TimerState.start(this@MainActivity, m) }
            }, LinearLayout.LayoutParams(0, dp(40), 1f).apply { setMargins(dp(4), dp(12), dp(4), 0) })
        }
        card.addView(chips)
        layer.addView(card, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.CENTER).apply {
            setMargins(dp(14), 0, dp(14), 0)
        })
        root.addView(layer, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        animateBlur(dp(22).toFloat(), dur(260))
        haptic(root, HapticFeedbackConstants.CONTEXT_CLICK)
        layer.alpha = 0f
        layer.animate().alpha(1f).setDuration(dur(200)).start()
        card.alpha = 0f
        Motion.scaleTo(card, 1f, Motion.BOUNCY, start = 0.8f)
        Motion.alphaTo(card, 1f, Motion.SNAPPY)
    }

    private fun openControlCenter() {
        if (!::controlCenter.isInitialized) return
        if (menuLayer != null) dismissMenu(immediate = true)
        if (searchOverlay.visibility == View.VISIBLE) closeSearch(false)
        island.collapse(false)
        controlCenter.show(insetTop, insetBottom)
    }

    private fun currentMusic(): Triple<String, String, Bitmap?>? {
        val meta = mediaController?.metadata ?: return null
        val title = meta.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: meta.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: return null
        val art = meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: meta.getBitmap(MediaMetadata.METADATA_KEY_ART)
        return Triple(title, meta.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: "", art)
    }

    private fun badgesOn() = settings.badges && !Focus.hidesBadges(focusId)

    /** Each Focus keeps its own dock (copied from the normal dock the first time). */
    private fun dockPrefKey(): String {
        val id = focusId ?: return KEY_DOCK
        val key = "dock_focus_$id"
        if (!prefs.contains(key) && prefs.contains(KEY_DOCK)) {
            prefs.edit().putString(key, prefs.getString(KEY_DOCK, "")).apply()
        }
        return key
    }

    /** Rounded glass text field used by the launcher's dialogs (iOS style). */
    private fun glassField(value: String): EditText = EditText(this).apply {
        setText(value)
        setSingleLine()
        selectAll()
        setTextColor(Color.WHITE)
        setHintTextColor(0x80FFFFFF.toInt())
        textSize = 17f
        background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(Color.argb(60, 255, 255, 255))
            setStroke(maxOf(1, dp(1)), Color.argb(60, 255, 255, 255))
        }
        setPadding(dp(14), dp(11), dp(14), dp(11))
    }

    private fun wrapField(field: View): View = FrameLayout(this).apply {
        setPadding(dp(20), dp(6), dp(20), dp(2))
        addView(field, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }

    private fun setFocus(id: String?) {
        Focus.set(this, id)
        prefs.edit().putBoolean("focus_auto", false).apply()
        applyFocusState(announce = true)
    }

    private fun applyFocusState(announce: Boolean) {
        focusId = Focus.current(this)?.id
        if (announce && settings.sideHud && settings.sideShowFocus && ::sideHud.isInitialized) {
            val f = Focus.current(this)
            if (f != null) sideHud.status(listOf(R.drawable.ic_cc_moon to f.color, R.drawable.ic_cc_person to 0xFFBF5AF2.toInt()), f.name.uppercase())
            else sideHud.status(listOf(R.drawable.ic_cc_moon to 0xFF8E8E93.toInt()), "FOCUS OFF")
        }
        restylePersistent()
        rebuild()
        haptic(root, HapticFeedbackConstants.CONFIRM)
        val f = Focus.current(this)
        pager.setCurrentItem(homePageIndex(), anim > 0f)
        if (announce && ::island.isInitialized) {
            island.flash(IslandActivity("focus", null, R.drawable.ic_bolt, f?.color ?: Color.WHITE,
                if (f == null) "Focus Off" else "${f.emoji} ${f.name}", f?.desc ?: "",
                if (f == null) null else "On", false, f?.color ?: Color.WHITE), 2500)
        }
        if (f != null && Focus.silences(this, f.id) && !Focus.hasDndAccess(this)) {
            toast("Allow Do Not Disturb access so ${f.name} can silence notifications")
        }
    }

    /** Focus sheet: pick a mode, its home page, notification silencing and the Sleep schedule. */
    private fun showFocusSheet() {
        if (menuLayer != null) dismissMenu(immediate = true)
        val layer = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(120, 0, 0, 0)); isClickable = true
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(16))
            background = Glass.menu(this@MainActivity, 30f)
            isClickable = true
        }
        fun close() {
            animateBlur(0f, dur(220))
            layer.animate().alpha(0f).setDuration(dur(200)).withEndAction { root.removeView(layer) }.start()
        }
        layer.setOnClickListener { close() }
        card.addView(TextView(this).apply {
            text = "Focus"; setTextColor(Color.WHITE); textSize = 26f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
        })
        val cur = Focus.current(this)?.id
        for (m in Focus.modes) {
            val on = m.id == cur
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = GradientDrawable().apply {
                    cornerRadius = dp(18).toFloat(); setColor(Color.argb(40, 255, 255, 255))
                    if (on) setStroke(dp(2), m.color)
                }
                setOnClickListener { close(); setFocus(if (on) null else m.id) }
            }
            row.addView(TextView(this).apply {
                text = m.emoji; textSize = 18f; gravity = Gravity.CENTER
                background = Glass.circle(m.color)
            }, LinearLayout.LayoutParams(dp(42), dp(42)))
            val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
            texts.addView(TextView(this).apply {
                text = m.name; setTextColor(Color.WHITE); textSize = 16f
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            })
            texts.addView(TextView(this).apply { text = m.desc; setTextColor(0x99FFFFFF.toInt()); textSize = 12f })
            row.addView(texts, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            if (on) row.addView(TextView(this).apply { text = "On"; setTextColor(m.color); textSize = 15f })
            card.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(10) })
        }
        if (cur != null) {
            val mode = Focus.modes.first { it.id == cur }
            card.addView(TextView(this).apply {
                text = "Home page for ${mode.name}"; setTextColor(0xB3FFFFFF.toInt()); textSize = 13f
                setPadding(dp(4), dp(16), 0, dp(6))
            })
            val pagesRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val chosen = Focus.homePage(this, cur)
            for (pIdx in 0 until homePageCount().coerceIn(1, 6)) {
                pagesRow.addView(TextView(this).apply {
                    text = "${pIdx + 1}"; gravity = Gravity.CENTER; setTextColor(Color.WHITE); textSize = 15f
                    background = GradientDrawable().apply {
                        cornerRadius = dp(12).toFloat(); setColor(Color.argb(40, 255, 255, 255))
                        if (pIdx == chosen) setStroke(dp(2), mode.color)
                    }
                    setOnClickListener {
                        Focus.setHomePage(this@MainActivity, cur, pIdx); close()
                        pager.setCurrentItem(pIdx, anim > 0f); haptic(root, HapticFeedbackConstants.CONTEXT_CLICK)
                    }
                }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { setMargins(dp(3), 0, dp(3), 0) })
            }
            card.addView(pagesRow)
            val silence = Focus.silences(this, cur)
            card.addView(TextView(this).apply {
                text = if (silence) "🔕 Silencing notifications · tap to allow" else "🔔 Allowing notifications · tap to silence"
                setTextColor(Color.WHITE); textSize = 14f; setPadding(dp(4), dp(14), 0, dp(4))
                setOnClickListener {
                    Focus.setSilences(this@MainActivity, cur, !silence); close()
                    if (!silence && !Focus.hasDndAccess(this@MainActivity)) {
                        runCatching { startActivity(Focus.dndSettingsIntent()) }
                    }
                }
            })
        }
        val sched = Focus.sleepSchedule(this)
        card.addView(TextView(this).apply {
            text = if (sched) "🌙 Sleep schedule 10:30 PM–7 AM: On" else "🌙 Sleep schedule 10:30 PM–7 AM: Off"
            setTextColor(0xB3FFFFFF.toInt()); textSize = 13f; setPadding(dp(4), dp(10), 0, 0)
            setOnClickListener { Focus.setSleepSchedule(this@MainActivity, !sched); close() }
        })
        layer.addView(card, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM).apply {
            setMargins(dp(10), 0, dp(10), insetBottom + dp(10))
        })
        root.addView(layer, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        animateBlur(dp(22).toFloat(), dur(260))
        layer.alpha = 0f
        layer.animate().alpha(1f).setDuration(dur(200)).start()
        Motion.spring(card, androidx.dynamicanimation.animation.DynamicAnimation.TRANSLATION_Y, 0f, Motion.FLUID,
            start = dp(600).toFloat())
    }

    private fun showTimerPicker(anchor: View?) {
        val items = mutableListOf<MenuItem>()
        if (TimerState.running) items += MenuItem("Cancel Timer (${TimerState.format(TimerState.remaining)} left)", destructive = true) {
            TimerState.cancel(this)
        }
        for (m in listOf(1, 3, 5, 10, 15, 30, 60)) items += MenuItem(if (m == 60) "1 hour" else "$m minutes") {
            TimerState.start(this, m)
        }
        showContextMenu(anchor, items)
    }

    /** Live Activity: the running timer counts down in the Dynamic Island. */
    private fun refreshIslandTimer() {
        if (!::island.isInitialized) return
        if (TimerState.running) {
            timerWasRunning = true
            island.put(IslandActivity("timer", null, R.drawable.ic_bolt, 0xFFFF9F0A.toInt(), "Timer",
                "Ends in ${TimerState.format(TimerState.remaining)}", TimerState.format(TimerState.remaining),
                false, 0xFFFF9F0A.toInt(), listOf(
                    "Cancel" to { TimerState.cancel(this) },
                    "+1 min" to { TimerState.start(this, ((TimerState.remaining / 60000L).toInt() + 1).coerceAtLeast(1)) }
                )))
        } else {
            island.remove("timer")
            if (timerWasRunning) {
                timerWasRunning = false
                haptic(root, HapticFeedbackConstants.CONFIRM)
                island.flash(IslandActivity("timerdone", null, R.drawable.ic_bolt, 0xFFFF9F0A.toInt(),
                    "Timer done", "", "00:00", false, 0xFFFF9F0A.toInt()), 4000)
            }
        }
    }

    /** New notifications appear briefly in the Dynamic Island (not during a silencing Focus). */
    private val lastIslandNote = HashMap<String, Long>()
    private fun showNotificationInIsland(pkg: String, title: String, text: String) {
        if (!::island.isInitialized) return
        if (settings.gilbertQuietInApp && !isActive && GameState.foreground == pkg) return
        val f = focusId
        // Messages still show during Do Not Disturb / Focus (Settings › Gilbert › Always Show Messages).
        val isMessage = NotificationService.lastChat[pkg] != null && title.isNotBlank()
        val dnd = runCatching { getSystemService(android.app.NotificationManager::class.java).currentInterruptionFilter }.getOrDefault(1) > 1
        if (!(isMessage && settings.alwaysShowMessages)) {
            if (f != null && Focus.silences(this, f)) return
            if (dnd && !settings.alwaysShowMessages) return
        }
        if (mediaController?.packageName == pkg) return
        val now = System.currentTimeMillis()
        if (now - (lastIslandNote[pkg] ?: 0L) < 4000L) return
        lastIslandNote[pkg] = now
        val app = allApps.firstOrNull { it.packageName == pkg }
        // Messages come out of the side island; the top island stays for music, timers and calls.
        if (settings.sideHud && settings.sideMessages && ::sideHud.isInitialized &&
            (settings.sideMsgMode == "all" || pkg in settings.sideMsgApps)) {
            val openApp = {
                if (app != null) runCatching {
                    startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(app.component)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
                }
                Unit
            }
            val chat = NotificationService.lastChat[pkg]
            sideHud.countNew()
            if (settings.sideChatCard && chat != null) {
                // Comes in as a small notification only; tap it to open the chat window
                // (it no longer jumps open by itself and covers what you're doing).
                sideHud.message(app?.icon, chat.title.ifBlank { title.ifBlank { app?.label ?: "Message" } }, text) {
                    sideHud.chat(app?.icon, app?.label ?: "Messages", chat) {
                        val pi = chat.open
                        if (pi != null) IslandServices.send(this, pi) else openApp()
                    }
                }
            } else sideHud.message(app?.icon, title.ifBlank { app?.label ?: "Message" }, text) { openApp() }
            return
        }
        // Top island (when messages aren't going to the side island): its own app filter applies.
        if (!settings.islandAllows("note")) return
        if (settings.islandNoteMode == "chosen" && pkg !in settings.islandNoteApps) return
        if (!settings.island || (!isActive && overlayIsland == null)) return
        island.flash(IslandActivity("note", app?.icon, R.drawable.ic_music, Color.WHITE,
            title.ifBlank { app?.label ?: "Notification" }, text, null, false, Color.WHITE,
            if (app != null) listOf("Open" to { launch(app, null) }) else emptyList()), 4500)
        haptic(root, HapticFeedbackConstants.CONTEXT_CLICK)
    }

    /** App Library smart folders: 3 big icons + a mini cluster that opens the whole folder. */
    private fun buildLibraryFolders(): View {
        val groups = AppCategories.build(this, allApps, recentKeys())
        val grid = GridLayout(this).apply { columnCount = 2 }
        val half = (resources.displayMetrics.widthPixels - dp(32) - dp(16)) / 2
        val big = (half - dp(12) * 2 - dp(20)) / 2
        for ((name, apps) in groups) {
            val cell = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
            val box = GridLayout(this).apply {
                columnCount = 2
                background = Glass.panel(this@MainActivity, 26f)
                setPadding(dp(12), dp(12), dp(12), dp(12))
            }
            val showBig = if (apps.size <= 4) apps.size else 3
            for (k in 0 until showBig) {
                val a = apps[k]
                val iv = ImageView(this).apply {
                    setImageBitmap(a.icon)
                    outlineProvider = roundedOutline(); clipToOutline = true
                    contentDescription = a.label
                    setOnClickListener { launch(a, it) }
                    setOnLongClickListener { showAppMenu(a, it); true }
                }
                iv.setOnTouchListener(pressEffect(iv))
                box.addView(iv, GridLayout.LayoutParams().apply { width = big; height = big; setMargins(dp(5), dp(5), dp(5), dp(5)) })
            }
            if (apps.size > 4) {
                val mini = GridLayout(this).apply {
                    columnCount = 2
                    setOnClickListener { showLibraryFolder(name, apps) }
                }
                val small = (big - dp(6)) / 2
                for (a in apps.drop(3).take(4)) {
                    mini.addView(ImageView(this).apply {
                        setImageBitmap(a.icon); outlineProvider = roundedOutline(); clipToOutline = true
                    }, GridLayout.LayoutParams().apply { width = small; height = small; setMargins(dp(1), dp(1), dp(1), dp(1)) })
                }
                mini.setOnTouchListener(pressEffect(mini))
                box.addView(mini, GridLayout.LayoutParams().apply { width = big; height = big; setMargins(dp(5), dp(5), dp(5), dp(5)) })
            }
            cell.addView(box)
            cell.addView(TextView(this).apply {
                text = name; setTextColor(Color.WHITE); textSize = 12f; gravity = Gravity.CENTER
                setShadowLayer(6f, 0f, 1f, 0x99000000.toInt()); setPadding(0, dp(6), 0, dp(12))
                setOnClickListener { showLibraryFolder(name, apps) }
            })
            grid.addView(cell, GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED),
                GridLayout.spec(GridLayout.UNDEFINED, 1f)).apply { width = 0 })
        }
        return grid
    }

    private fun showLibraryFolder(name: String, apps: List<AppInfo>) {
        haptic(root, HapticFeedbackConstants.CONTEXT_CLICK)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Glass.folder(this@MainActivity, 30f)
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        panel.addView(TextView(this).apply {
            text = name; textSize = 20f; setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        })
        val colsN = if (apps.size <= 4) 2 else 3
        val grid = RecyclerView(this).apply { layoutManager = GridLayoutManager(this@MainActivity, colsN) }
        panel.addView(grid, LinearLayout.LayoutParams(MATCH_PARENT,
            minOf(dp(108) * ((apps.size + colsN - 1) / colsN) + dp(8), (resources.displayMetrics.heightPixels * 0.6f).toInt())))
        val dialog = folderDialog(panel, if (apps.size <= 4) 0.66f else 0.88f)
        grid.adapter = AppListAdapter(true, { a, v -> dialog.dismiss(); launch(a, v) }, { a, v -> showAppMenu(a, v) })
            .apply { submit(apps) }
        if (anim > 0f) {
            panel.alpha = 0f
            Motion.scaleTo(panel, 1f, Motion.BOUNCY, start = 0.72f)
            Motion.alphaTo(panel, 1f, Motion.SNAPPY)
        }
    }

    /** Spotlight: quick actions, settings and contacts above the app results. */
    private var pendingExtras = ""
    private val extrasTask = Runnable { renderSearchExtras(pendingExtras) }

    private fun renderSearchExtras(query: String) {
        if (!::searchExtras.isInitialized) return
        searchExtras.removeAllViews()
        if (query.length < 2) return
        fun section(title: String) = searchExtras.addView(TextView(this).apply {
            text = title; setTextColor(0xB3FFFFFF.toInt()); textSize = 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); setPadding(dp(4), dp(14), 0, dp(6))
        })
        fun row(icon: String, title: String, sub: String, onClick: () -> Unit) {
            val r = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(9), dp(12), dp(9))
                background = Glass.panel(this@MainActivity, 16f)
            }
            r.addView(TextView(this).apply {
                text = icon; textSize = 18f; gravity = Gravity.CENTER; background = Glass.circle(0x33FFFFFF)
            }, LinearLayout.LayoutParams(dp(40), dp(40)))
            r.addView(TextView(this).apply {
                text = if (sub.isEmpty()) title else "$title\n$sub"; setTextColor(Color.WHITE); textSize = 15f
                setPadding(dp(12), 0, 0, 0)
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            r.setOnClickListener { haptic(r, HapticFeedbackConstants.CONTEXT_CLICK); closeSearch(false); onClick() }
            searchExtras.addView(r, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(6) })
        }

        // Typing "timer" shows a Start Timer entry: tap it (or press Enter) to pop up the dial.
        val tq = query.trim().lowercase()
        if (tq.length >= 3 && ("timer".startsWith(tq) || tq.contains("timer"))) {
            section("Timer")
            row("⏱", "Start Timer", "Tap or press Enter to adjust the time") { showTimerPopup() }
            searchExtras.getChildAt(searchExtras.childCount - 1)?.let { r ->
                ((r as? LinearLayout)?.getChildAt(0))?.background = Glass.circle(0xFFFF9F0A.toInt())
            }
        }
        val shortcuts = SpotlightExtras.shortcuts(query, { m -> TimerState.start(this, m) }, { openControlCenter() },
            { id -> setFocus(id) })
        if (shortcuts.isNotEmpty()) { section("Shortcuts"); shortcuts.forEach { r -> row(r.icon, r.title, r.subtitle, r.action) } }
        val sets = SpotlightExtras.settings(query) { intent ->
            runCatching { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
        if (sets.isNotEmpty()) { section("Settings"); sets.forEach { r -> row(r.icon, r.title, r.subtitle, r.action) } }
        if (SpotlightExtras.hasContactsPermission(this)) {
            // Look contacts up in the background, then add them if the query hasn't changed.
            io.execute {
                val people = runCatching { SpotlightExtras.contacts(this, query) }.getOrDefault(emptyList())
                mainHandler.post { if (pendingExtras == query && !isDestroyed) addContactRows(people, ::section, ::row) }
            }
        } else if (!prefs.getBoolean("asked_contacts", false)) {
            section("Contacts")
            row("👤", "Show contacts in Search", "Tap to allow") {
                prefs.edit().putBoolean("asked_contacts", true).apply()
                requestPermissions(arrayOf(android.Manifest.permission.READ_CONTACTS), 4400)
            }
        }
    }

    private fun addContactRows(
        people: List<SpotlightExtras.Contact>,
        section: (String) -> Unit,
        row: (String, String, String, () -> Unit) -> Unit
    ) {
        run {
            if (people.isNotEmpty()) {
                section("Contacts")
                for (c in people) row("👤", c.name, c.number ?: "") {
                    val num = c.number
                    runCatching {
                        startActivity(if (num != null) Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(num)))
                            else Intent(Intent.ACTION_VIEW, c.uri))
                    }
                }
            }
        }
    }

    private fun applyGlassWallpaper() {
        val m = resources.displayMetrics
        val w = m.widthPixels
        val h = (m.heightPixels * 1.1f).toInt()
        io.execute {
            val ok = runCatching {
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val c = Canvas(bmp)
                c.drawColor(0xFF141416.toInt())
                val p = Paint(Paint.ANTI_ALIAS_FLAG)
                p.shader = RadialGradient(
                    w * 0.3f, h * 0.3f, w * 1.1f,
                    intArrayOf(0xFF8A8A8F.toInt(), 0xFF3C3C40.toInt(), 0x00000000),
                    floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
                )
                c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
                p.shader = RadialGradient(
                    w * 0.85f, h * 0.78f, w * 0.85f,
                    intArrayOf(0xB06A6A6E.toInt(), 0x00000000), null, Shader.TileMode.CLAMP
                )
                c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
                WallpaperManager.getInstance(this).setBitmap(bmp, null, true, WallpaperManager.FLAG_SYSTEM)
            }.isSuccess
            mainHandler.post { toast(if (ok) "Wallpaper applied" else "Couldn't set wallpaper") }
        }
    }

    private fun isDefaultHome(): Boolean {
        val ri = packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
            PackageManager.MATCH_DEFAULT_ONLY
        )
        return ri?.activityInfo?.packageName == packageName
    }

    private fun openHomeSettings() {
        runCatching { startActivity(Intent(SysSettings.ACTION_HOME_SETTINGS)) }
            .onFailure { runCatching { startActivity(Intent(SysSettings.ACTION_SETTINGS)) } }
    }

    // ================================================================ receivers

    private fun registerReceivers() {
        val pf = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(this, packageReceiver, pf, ContextCompat.RECEIVER_EXPORTED)
        ContextCompat.registerReceiver(
            this, batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_EXPORTED
        )?.let { updateBattery(it) }
    }

    private fun updateBattery(i: Intent) {
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (level >= 0 && scale > 0) batteryLevel = (level * 100f / scale).roundToInt()
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        batteryCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        batteryRing?.let {
            it.level = batteryLevel
            it.charging = batteryCharging
        }
        batteryText?.text = "$batteryLevel%"
        if (::island.isInitialized && wasCharging == false && batteryCharging) {
            if (settings.gilbertEmotions) island.faceReact("excited", 3000)
            if (settings.islandFace) { SoundFX.play(this, "happy"); island.say(listOf("Charging! ⚡ Thank you~", "Yay, power! 🔋", "Mmm, electricity 😋").random()) }
            island.flash(IslandActivity(
                "charging", null, R.drawable.ic_bolt, 0xFF30D158.toInt(), "Charging", "Battery $batteryLevel%",
                "$batteryLevel%", false, 0xFF30D158.toInt()
            ), 4500)
        }
        wasCharging = batteryCharging
    }

    // ================================================================ utils

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()
    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).roundToInt()
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
