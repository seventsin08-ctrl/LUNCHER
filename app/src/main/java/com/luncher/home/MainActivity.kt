package com.luncher.home

import android.animation.ValueAnimator
import android.app.Activity
import android.app.ActivityOptions
import android.app.WallpaperManager
import android.content.BroadcastReceiver
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
import android.graphics.RadialGradient
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import android.text.TextUtils
import android.view.GestureDetector
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.ViewOutlineProvider
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.drawToBitmap
import androidx.core.widget.addTextChangedListener
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.roundToInt

class MainActivity : Activity() {

    companion object {
        private const val COLUMNS = 4
        private const val CELL_H = 94
        private const val ICON_DP = 60
        private const val PAGE_PAD = 14
        private const val DOCK_MAX = 4
        private const val MIN_PAGES = 3
        private const val KEY_DOCK = "dock"
        private const val KEY_RECENT = "recent"
        private const val REQ_PHOTO = 4100

        /** iOS-like "ease out" curve used for most motion. */
        private val EASE = PathInterpolator(0.2f, 0.9f, 0.1f, 1f)
        private val SPRING = OvershootInterpolator(1.6f)
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var root: FrameLayout
    private lateinit var content: LinearLayout
    private lateinit var pager: ViewPager2
    private lateinit var dots: LinearLayout
    private lateinit var searchPill: TextView
    private lateinit var dockRow: LinearLayout
    private lateinit var defaultBanner: TextView
    private lateinit var searchOverlay: LinearLayout
    private lateinit var searchField: EditText
    private lateinit var searchHeader: TextView
    private lateinit var gestures: GestureDetector

    private val searchAdapter by lazy {
        AppListAdapter(false, { a, v -> launch(a, v) }, { a, v -> showAppMenu(a, v) })
    }
    private val pagesAdapter = PagesAdapter()

    private var allApps: List<AppInfo> = emptyList()
    private var pages: List<Page> = emptyList()
    private var searchResults: List<AppInfo> = emptyList()

    private var batteryLevel = 0
    private var batteryCharging = false
    private var batteryRing: BatteryRingView? = null
    private var batteryText: TextView? = null

    private val photoCache = HashMap<String, Bitmap>()
    private var pendingPhotoId: String? = null

    /** Badge views currently on screen, keyed by package name. */
    private val badgeViews = ArrayList<Pair<String, TextView>>()

    // Now Playing
    private var mediaController: MediaController? = null
    private var mediaListening = false
    private var musicViews: MusicViews? = null

    // Motion state
    private var menuLayer: FrameLayout? = null
    private var currentBlur = 0f
    private var blurAnimator: ValueAnimator? = null
    private var entrancePending = true
    private var isActive = false

    private val io = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val showSearchPill = Runnable { setPillMode(search = true) }

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = reloadApps()
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

    // ================================================================ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        prefs = getSharedPreferences("luncher", MODE_PRIVATE)
        buildUi()
        setContentView(root)

        gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (e1 == null || searchOverlay.visibility == View.VISIBLE || menuLayer != null) return false
                if (pages.getOrNull(pager.currentItem) is Page.Library) return false
                val dy = e2.y - e1.y
                val dx = e2.x - e1.x
                if (dy > dp(70) && abs(dy) > abs(dx) * 1.5f && velocityY > 700) {
                    openSearch()
                    return true
                }
                return false
            }
        })

        NotificationService.onChange = { updateBadges() }
        registerReceivers()
        reloadApps()
    }

    override fun onResume() {
        super.onResume()
        isActive = true
        defaultBanner.visibility = if (isDefaultHome()) View.GONE else View.VISIBLE
        setupMedia()
        updateBadges()
        // Safety: never leave the home screen faded out.
        if (content.alpha < 1f && !content.hasTransientState()) restoreHome(animated = true)
    }

    override fun onPause() {
        isActive = false
        super.onPause()
    }

    override fun onRestart() {
        super.onRestart()
        // iOS-style "zoom back in" when you return home from an app.
        content.scaleX = 1.12f
        content.scaleY = 1.12f
        content.alpha = 0f
        restoreHome(animated = true)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        when {
            menuLayer != null -> dismissMenu()
            searchOverlay.visibility == View.VISIBLE -> closeSearch()
            pager.currentItem != 0 -> pager.currentItem = 0
        }
    }

    @Deprecated("Launcher handles back itself")
    override fun onBackPressed() {
        when {
            menuLayer != null -> dismissMenu()
            searchOverlay.visibility == View.VISIBLE -> closeSearch()
            pager.currentItem != 0 -> pager.currentItem = 0
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (::gestures.isInitialized) gestures.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    override fun onDestroy() {
        NotificationService.onChange = null
        runCatching { unregisterReceiver(packageReceiver) }
        runCatching { unregisterReceiver(batteryReceiver) }
        if (mediaListening) {
            runCatching {
                getSystemService(MediaSessionManager::class.java).removeOnActiveSessionsChangedListener(sessionsListener)
            }
        }
        mediaController?.unregisterCallback(mediaCallback)
        io.shutdownNow()
        super.onDestroy()
    }

    @Deprecated("Uses the classic result API, which is fine for a plain Activity")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
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

    // ================================================================ UI skeleton

    private fun buildUi() {
        root = FrameLayout(this)
        root.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.argb(60, 0, 0, 0), Color.argb(15, 0, 0, 0), Color.argb(90, 0, 0, 0))
        )

        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        defaultBanner = TextView(this).apply {
            text = "Tap here to make Luncher your Home app"
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            background = Glass.panel(this@MainActivity, 18f)
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
        // No Android edge glow: iOS pages just stop.
        (pager.getChildAt(0) as? RecyclerView)?.overScrollMode = View.OVER_SCROLL_NEVER
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
            background = Glass.panel(this@MainActivity, 16f)
            setPadding(dp(13), 0, dp(15), 0)
            setOnClickListener { openSearch() }
        }
        searchPill.setOnTouchListener(pressEffect(searchPill, 0.92f))
        pillHolder.addView(searchPill, FrameLayout.LayoutParams(WRAP_CONTENT, dp(30), Gravity.CENTER))
        dots = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = Glass.panel(this@MainActivity, 16f)
            setPadding(dp(9), 0, dp(9), 0)
            alpha = 0f
        }
        pillHolder.addView(dots, FrameLayout.LayoutParams(WRAP_CONTENT, dp(24), Gravity.CENTER))
        content.addView(pillHolder, LinearLayout.LayoutParams(MATCH_PARENT, dp(42)))

        dockRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Glass.panel(this@MainActivity, 36f)
            setPadding(dp(8), dp(13), dp(8), dp(13))
            clipChildren = false
        }
        content.addView(dockRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            setMargins(dp(10), dp(2), dp(10), dp(8))
        })
        content.clipChildren = false

        root.addView(content, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        buildSearchOverlay()

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            content.setPadding(0, bars.top, 0, bars.bottom)
            searchOverlay.setPadding(dp(16), bars.top + dp(10), dp(16), maxOf(bars.bottom, ime.bottom))
            insets
        }

        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) = updateDots(position)

            override fun onPageScrollStateChanged(state: Int) {
                mainHandler.removeCallbacks(showSearchPill)
                if (state == ViewPager2.SCROLL_STATE_IDLE) mainHandler.postDelayed(showSearchPill, 1000)
                else setPillMode(search = false)
            }

            override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {
                val n = pages.size
                val token = root.windowToken ?: return
                if (n < 2) return
                runCatching {
                    val wm = WallpaperManager.getInstance(this@MainActivity)
                    wm.setWallpaperOffsetSteps(1f / (n - 1), 0f)
                    wm.setWallpaperOffsets(token, (position + positionOffset) / (n - 1), 0f)
                }
            }
        })

        // Subtle depth while swiping: the outgoing page shrinks and fades a touch, like iOS.
        pager.setPageTransformer { page, position ->
            val p = abs(position).coerceAtMost(1f)
            page.alpha = 1f - p * 0.35f
            val s = 1f - p * 0.06f
            page.scaleX = s
            page.scaleY = s
        }
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
            hint = "Search"
            setHintTextColor(0x99FFFFFF.toInt())
            setTextColor(Color.WHITE)
            textSize = 17f
            background = Glass.panel(this@MainActivity, 14f)
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
        }
        searchOverlay.addView(results, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        root.addView(searchOverlay, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        searchField.addTextChangedListener(afterTextChanged = { filterSearch(it?.toString().orEmpty()) })
        searchField.setOnEditorActionListener { _, _, _ ->
            searchResults.firstOrNull()?.let { launch(it, searchField) }
            true
        }
    }

    private fun setPillMode(search: Boolean) {
        searchPill.animate().alpha(if (search) 1f else 0f).setDuration(220).setInterpolator(EASE).start()
        dots.animate().alpha(if (search) 0f else 1f).setDuration(220).setInterpolator(EASE).start()
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

    /** Blurs everything on the home screen (API 31+); used behind search and menus. */
    private fun animateBlur(to: Float, duration: Long) {
        if (Build.VERSION.SDK_INT < 31) return
        blurAnimator?.cancel()
        blurAnimator = ValueAnimator.ofFloat(currentBlur, to).apply {
            this.duration = duration
            interpolator = EASE
            addUpdateListener {
                val v = it.animatedValue as Float
                currentBlur = v
                content.setRenderEffect(
                    if (v < 0.5f) null else RenderEffect.createBlurEffect(v, v, Shader.TileMode.CLAMP)
                )
            }
            start()
        }
    }

    private fun restoreHome(animated: Boolean) {
        content.animate().cancel()
        if (!animated) {
            content.scaleX = 1f; content.scaleY = 1f; content.alpha = 1f
            return
        }
        content.animate().scaleX(1f).scaleY(1f).alpha(1f)
            .setDuration(420).setInterpolator(EASE).start()
    }

    private fun pressEffect(target: View, scale: Float = 0.88f) = View.OnTouchListener { _, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN ->
                target.animate().scaleX(scale).scaleY(scale).alpha(0.82f)
                    .setDuration(120).setInterpolator(EASE).start()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                target.animate().scaleX(1f).scaleY(1f).alpha(1f)
                    .setDuration(380).setInterpolator(SPRING).start()
        }
        false
    }

    /** Icons pop in one after another the first time the home screen appears. */
    private fun entrance(v: View, col: Int, row: Int) {
        if (!entrancePending) return
        v.alpha = 0f
        v.scaleX = 0.82f
        v.scaleY = 0.82f
        v.animate().alpha(1f).scaleX(1f).scaleY(1f)
            .setStartDelay((row * 45L + col * 22L))
            .setDuration(420).setInterpolator(SPRING).start()
    }

    // ================================================================ data

    private fun reloadApps() {
        val iconPx = dp(ICON_DP) * 2
        io.execute {
            val apps = runCatching { AppLoader.load(this, iconPx) }.getOrDefault(emptyList())
            mainHandler.post {
                if (!isDestroyed) {
                    allApps = apps
                    rebuild()
                    mainHandler.postDelayed({ entrancePending = false }, 1200)
                }
            }
        }
    }

    private fun rowsPerPage(): Int {
        val m = resources.displayMetrics
        val hDp = m.heightPixels / m.density
        return ((hDp - 230f) / CELL_H).toInt().coerceIn(4, 7)
    }

    /** Page layouts modelled on the reference screens. Widgets that don't fit are dropped. */
    private fun widgetsFor(page: Int): List<PlacedWidget> = when (page) {
        0 -> listOf(
            PlacedWidget(WidgetType.PHOTO, 0, 0, 2, 2, "p1"),
            PlacedWidget(WidgetType.CLOCK_ICON, 2, 1, 1, 1),
            PlacedWidget(WidgetType.CALENDAR_ICON, 3, 1, 1, 1),
            PlacedWidget(WidgetType.PHOTO, 2, 2, 2, 2, "p2"),
            PlacedWidget(WidgetType.MUSIC, 0, 4, 4, 2)
        )
        1 -> listOf(
            PlacedWidget(WidgetType.PHOTO, 2, 0, 2, 2, "p3"),
            PlacedWidget(WidgetType.CALENDAR, 0, 2, 4, 2),
            PlacedWidget(WidgetType.PHOTO, 0, 4, 2, 2, "p4")
        )
        2 -> listOf(
            PlacedWidget(WidgetType.PHOTO, 0, 0, 4, 2, "p5"),
            PlacedWidget(WidgetType.BATTERY, 2, 2, 2, 2)
        )
        else -> emptyList()
    }

    private fun rebuild() {
        val dock = dockApps()
        val dockKeys = dock.map { it.key }.toSet()
        val home = allApps.filter { it.key !in dockKeys }
        val rows = rowsPerPage()

        val result = mutableListOf<Page>()
        var next = 0
        var page = 0
        while (page < MIN_PAGES || next < home.size) {
            val widgets = widgetsFor(page).filter { it.row + it.h <= rows }
            val taken = BooleanArray(rows * COLUMNS)
            for (w in widgets) {
                for (r in w.row until w.row + w.h) {
                    for (c in w.col until w.col + w.w) taken[r * COLUMNS + c] = true
                }
            }
            val apps = mutableListOf<Pair<AppInfo, Int>>()
            for (cell in 0 until rows * COLUMNS) {
                if (!taken[cell] && next < home.size) apps += home[next++] to cell
            }
            result += Page.Home(widgets, apps)
            page++
        }
        result += Page.Library

        pages = result
        refreshPages()
        if (pager.currentItem >= pages.size) pager.setCurrentItem(0, false)
        updateDots(pager.currentItem)
        buildDock(dock)
        filterSearch(searchField.text?.toString().orEmpty())
    }

    @Suppress("NotifyDataSetChanged")
    private fun refreshPages() {
        badgeViews.clear()
        musicViews = null
        pagesAdapter.notifyDataSetChanged()
    }

    private fun dockKeys(): List<String> =
        prefs.getString(KEY_DOCK, "").orEmpty().split('|').filter { it.isNotEmpty() }

    private fun dockApps(): List<AppInfo> {
        val byKey = allApps.associateBy { it.key }
        if (prefs.contains(KEY_DOCK)) return dockKeys().mapNotNull { byKey[it] }
        val defaults = defaultDock()
        if (defaults.isNotEmpty()) prefs.edit().putString(KEY_DOCK, defaults.joinToString("|") { it.key }).apply()
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
            if (result.size >= DOCK_MAX) break
            if (result.none { it.key == a.key }) result += a
        }
        return result.take(DOCK_MAX)
    }

    private fun setDock(keys: List<String>) {
        prefs.edit().putString(KEY_DOCK, keys.distinct().joinToString("|")).apply()
        rebuild()
    }

    private fun recentKeys(): List<String> =
        prefs.getString(KEY_RECENT, "").orEmpty().split('|').filter { it.isNotEmpty() }

    private fun recordRecent(app: AppInfo) {
        val list = (listOf(app.key) + recentKeys().filter { it != app.key }).take(8)
        prefs.edit().putString(KEY_RECENT, list.joinToString("|")).apply()
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
                is Page.Home -> buildHomePage(p)
                Page.Library -> buildLibraryPage()
            }
            holder.frame.addView(view, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
    }

    private fun cellW(): Int = (resources.displayMetrics.widthPixels - dp(PAGE_PAD) * 2) / COLUMNS

    private fun place(col: Int, row: Int, w: Int, h: Int): FrameLayout.LayoutParams {
        val cw = cellW()
        return FrameLayout.LayoutParams(cw * w, dp(CELL_H) * h).apply {
            leftMargin = cw * col
            topMargin = dp(CELL_H) * row
        }
    }

    private fun buildHomePage(p: Page.Home): View {
        val frame = FrameLayout(this).apply {
            setPadding(dp(PAGE_PAD), dp(6), dp(PAGE_PAD), 0)
            clipChildren = false
            clipToPadding = false
            setOnLongClickListener { showHomeMenu(it); true }
        }
        for (w in p.widgets) {
            val v = buildWidget(w)
            frame.addView(v, place(w.col, w.row, w.w, w.h))
            entrance(v, w.col, w.row)
        }
        for ((app, cell) in p.apps) {
            val v = makeAppCell(app)
            frame.addView(v, place(cell % COLUMNS, cell / COLUMNS, 1, 1))
            entrance(v, cell % COLUMNS, cell / COLUMNS)
        }
        return frame
    }

    private fun buildLibraryPage(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(6), dp(16), 0)
        }
        val title = TextView(this).apply {
            text = "App Library"
            setTextColor(Color.WHITE)
            textSize = 30f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setShadowLayer(8f, 0f, 1f, 0x80000000.toInt())
        }
        col.addView(title, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

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

        val libAdapter = AppListAdapter(true, { a, v -> launch(a, v) }, { a, v -> showAppMenu(a, v) })
        libAdapter.submit(allApps)
        field.addTextChangedListener(afterTextChanged = { s ->
            val q = s?.toString().orEmpty().trim()
            libAdapter.submit(if (q.isEmpty()) allApps else allApps.filter { it.label.contains(q, true) })
        })
        val list = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@MainActivity, COLUMNS)
            adapter = libAdapter
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        col.addView(list, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
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
        textSize = 11.5f
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        gravity = Gravity.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setShadowLayer(6f, 0f, 1f, 0x99000000.toInt())
    }

    /** Generic icon cell: icon + optional badge + optional label, with springy press feedback. */
    private fun makeCell(
        icon: View,
        text: String?,
        badgePackage: String?,
        onClick: (View) -> Unit,
        onLong: ((View) -> Unit)?
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
        if (badgePackage != null) {
            val badge = makeBadge()
            holder.addView(badge, FrameLayout.LayoutParams(WRAP_CONTENT, dp(21), Gravity.TOP or Gravity.END).apply {
                topMargin = -dp(7); marginEnd = -dp(8)
            })
            badgeViews += badgePackage to badge
            applyBadge(badge, NotificationService.counts[badgePackage] ?: 0)
        }
        cell.addView(holder, LinearLayout.LayoutParams(dp(ICON_DP), dp(ICON_DP)))
        if (text != null) {
            cell.addView(label(text), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                topMargin = dp(5); leftMargin = dp(2); rightMargin = dp(2)
            })
        }
        cell.setOnClickListener { onClick(holder) }
        if (onLong != null) cell.setOnLongClickListener { onLong(holder); true }
        cell.setOnTouchListener(pressEffect(holder))
        return cell
    }

    private fun makeAppCell(app: AppInfo, showLabel: Boolean = true): View {
        val icon = ImageView(this).apply { setImageBitmap(app.icon) }
        return makeCell(
            icon, if (showLabel) app.label else null, app.packageName,
            { launch(app, it) }, { showAppMenu(app, it) }
        )
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
        if (count <= 0) {
            badge.visibility = View.GONE
            return
        }
        val txt = if (count > 999) "999+" else count.toString()
        val wasHidden = badge.visibility != View.VISIBLE
        badge.text = txt
        badge.visibility = View.VISIBLE
        if (wasHidden && badge.isAttachedToWindow) {
            badge.scaleX = 0f; badge.scaleY = 0f
            badge.animate().scaleX(1f).scaleY(1f).setDuration(380).setInterpolator(SPRING).start()
        }
    }

    private fun updateBadges() {
        badgeViews.removeAll { !it.second.isAttachedToWindow && it.second.parent == null }
        for ((pkg, view) in badgeViews) applyBadge(view, NotificationService.counts[pkg] ?: 0)
    }

    /** A widget: clear-glass box spanning cells, with a small label beneath it. */
    private fun widgetFrame(
        inner: View,
        text: String,
        onClick: (View) -> Unit,
        onLong: ((View) -> Unit)? = null
    ): View {
        val inset = ((cellW() - dp(ICON_DP)) / 2).coerceAtLeast(0)
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(inset, dp(4), inset, 0)
        }
        val box = FrameLayout(this).apply {
            background = Glass.panel(this@MainActivity, 22f)
            outlineProvider = roundedOutline(22f)
            clipToOutline = true
        }
        box.addView(inner, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        // Glass rim on top of photos so they read as glass panels too.
        box.addView(View(this).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(22).toFloat()
                setStroke(maxOf(1, dp(1)), Color.argb(80, 255, 255, 255))
            }
        }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        wrap.addView(box, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        wrap.addView(label(text), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            topMargin = dp(5); bottomMargin = dp(9)
        })
        box.setOnClickListener { onClick(box) }
        if (onLong != null) box.setOnLongClickListener { onLong(box); true }
        box.setOnTouchListener(pressEffect(box, 0.95f))
        return wrap
    }

    private fun buildWidget(w: PlacedWidget): View = when (w.type) {
        WidgetType.CLOCK_ICON ->
            makeCell(AnalogClockView(this), "Clock", null,
                { openIntent(Intent(AlarmClock.ACTION_SHOW_ALARMS), it) }, null)
        WidgetType.CALENDAR_ICON ->
            makeCell(CalendarIconView(this), "Calendar", null, { openCalendar(it) }, null)
        WidgetType.PHOTO ->
            widgetFrame(photoWidget(w.id), "Photo", { v -> photoTapped(w.id, v) }, { v -> showPhotoMenu(w.id, v) })
        WidgetType.CALENDAR ->
            widgetFrame(calendarWidget(), "Calendar", { openCalendar(it) })
        WidgetType.BATTERY ->
            widgetFrame(batteryWidget(), "Batteries", { openIntent(Intent(Intent.ACTION_POWER_USAGE_SUMMARY), it) })
        WidgetType.MUSIC ->
            widgetFrame(musicWidget(), "Now Playing", { musicTapped(it) })
    }

    private fun photoWidget(id: String): View {
        val frame = FrameLayout(this)
        val img = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        if (prefs.getBoolean("photo_bw_$id", true)) img.colorFilter = IconFactory.grayscale()
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
            img.setImageBitmap(cached); hint.visibility = View.GONE
        } else if (uri != null) {
            hint.visibility = View.GONE
            io.execute {
                val bmp = decodeSampled(Uri.parse(uri), dp(360))
                mainHandler.post {
                    if (bmp != null) {
                        photoCache[id] = bmp
                        img.alpha = 0f
                        img.setImageBitmap(bmp)
                        img.animate().alpha(1f).setDuration(300).setInterpolator(EASE).start()
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
                        setTextColor(Color.BLACK)
                        background = Glass.circle(Color.WHITE)
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
            setOnClickListener { performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK); onClick() }
            setOnTouchListener(pressEffect(this, 0.82f))
        }

    private fun musicWidget(): View {
        val frame = FrameLayout(this)
        val bg = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            alpha = 0.32f
            colorFilter = IconFactory.grayscale(0.9f)
        }
        frame.addView(bg, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(12))
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val art = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            outlineProvider = roundedOutline(10f)
            clipToOutline = true
            background = Glass.panel(this@MainActivity, 10f)
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
            mediaController?.transportControls?.skipToPrevious()
        }, LinearLayout.LayoutParams(dp(36), dp(36)))
        controls.addView(roundIconButton(R.drawable.ic_next, 36, false) {
            mediaController?.transportControls?.skipToNext()
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

    private fun buildDock(dock: List<AppInfo>) {
        dockRow.removeAllViews()
        if (dock.isEmpty()) {
            dockRow.addView(TextView(this).apply {
                text = "Long-press an app → Add to Dock"
                setTextColor(0xB3FFFFFF.toInt())
                gravity = Gravity.CENTER
                textSize = 13f
            }, LinearLayout.LayoutParams(MATCH_PARENT, dp(ICON_DP)))
            return
        }
        for (app in dock) {
            val cell = makeAppCell(app, showLabel = false)
            (cell as LinearLayout).setPadding(0, 0, 0, 0)
            dockRow.addView(cell, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }
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
                val playing = c.playbackState?.state == PlaybackState.STATE_PLAYING
                v.play.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
            }
        }
    }

    private fun togglePlay() {
        val c = mediaController ?: return
        if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause()
        else c.transportControls.play()
    }

    private fun musicTapped(from: View) {
        when {
            !hasNotificationAccess() -> runCatching {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                toast("Turn on Luncher to see music and badges")
            }
            mediaController != null -> {
                val launch = packageManager.getLaunchIntentForPackage(mediaController!!.packageName)
                if (launch != null) openIntent(launch, from)
            }
            else -> openIntent(
                Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MUSIC), from
            )
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
            }
        )
        if (prefs.getString("photo_$id", null) != null) {
            items += MenuItem("Remove Photo", destructive = true) {
                prefs.edit().remove("photo_$id").apply()
                photoCache.remove(id)
                refreshPages()
            }
        }
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

    /**
     * Blurs the home screen, lifts a copy of the pressed item, and springs a glass
     * menu out from it — like the iOS long-press menu.
     */
    private fun showContextMenu(anchor: View?, items: List<MenuItem>) {
        if (menuLayer != null) dismissMenu(immediate = true)
        anchor?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)

        val layer = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(70, 0, 0, 0))
            alpha = 0f
            isClickable = true
            setOnClickListener { dismissMenu() }
        }

        // Lifted preview of the pressed item.
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
            anchorW = (anchor.width * anchor.scaleX).toInt()
            anchorH = (anchor.height * anchor.scaleY).toInt()
            runCatching {
                anchor.scaleX = 1f; anchor.scaleY = 1f; anchor.alpha = 1f
                val bmp = anchor.drawToBitmap()
                val preview = ImageView(this).apply {
                    setImageBitmap(bmp)
                    outlineProvider = roundedOutline(if (anchor.width > dp(ICON_DP + 10)) 22f else null)
                    clipToOutline = true
                    elevation = dp(12).toFloat()
                }
                layer.addView(preview, FrameLayout.LayoutParams(anchor.width, anchor.height).apply {
                    leftMargin = anchorX; topMargin = anchorY
                })
                anchorW = anchor.width; anchorH = anchor.height
                preview.animate().scaleX(1.08f).scaleY(1.08f).setDuration(320).setInterpolator(SPRING).start()
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
                    mainHandler.postDelayed({ item.action() }, 140)
                }
                background = android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(0x22FFFFFF), null, null
                )
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }

        val menuW = dp(250)
        panel.measure(
            View.MeasureSpec.makeMeasureSpec(menuW, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val menuH = panel.measuredHeight
        val gap = dp(12)
        val margin = dp(14)
        val below = anchorY + anchorH + gap + menuH < root.height - dp(40)
        val top = if (anchor == null) (root.height - menuH) / 2
        else if (below) anchorY + anchorH + gap else (anchorY - gap - menuH).coerceAtLeast(margin)
        val left = if (anchor == null) (root.width - menuW) / 2
        else anchorX.coerceIn(margin, (root.width - menuW - margin).coerceAtLeast(margin))
        layer.addView(panel, FrameLayout.LayoutParams(menuW, WRAP_CONTENT).apply {
            leftMargin = left; topMargin = top
        })

        panel.pivotX = (anchorX + anchorW / 2f - left).coerceIn(0f, menuW.toFloat())
        panel.pivotY = if (below || anchor == null) 0f else menuH.toFloat()
        panel.scaleX = 0.5f; panel.scaleY = 0.5f; panel.alpha = 0f
        panel.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(340)
            .setInterpolator(OvershootInterpolator(1.1f)).start()

        root.addView(layer, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        menuLayer = layer
        layer.animate().alpha(1f).setDuration(200).setInterpolator(EASE).start()
        animateBlur(dp(22).toFloat(), 260)
        content.animate().scaleX(0.97f).scaleY(0.97f).setDuration(260).setInterpolator(EASE).start()
    }

    private fun dismissMenu(immediate: Boolean = false) {
        val layer = menuLayer ?: return
        menuLayer = null
        animateBlur(0f, if (immediate) 1 else 220)
        content.animate().scaleX(1f).scaleY(1f).setDuration(if (immediate) 1 else 260).setInterpolator(EASE).start()
        if (immediate) {
            root.removeView(layer)
            return
        }
        layer.animate().alpha(0f).setDuration(180).setInterpolator(EASE)
            .withEndAction { root.removeView(layer) }.start()
    }

    // ================================================================ search

    private fun openSearch() {
        searchField.setText("")
        filterSearch("")
        searchOverlay.alpha = 0f
        searchOverlay.translationY = -dp(30).toFloat()
        searchOverlay.visibility = View.VISIBLE
        searchOverlay.animate().alpha(1f).translationY(0f).setDuration(320).setInterpolator(EASE).start()
        animateBlur(dp(26).toFloat(), 320)
        content.animate().scaleX(0.94f).scaleY(0.94f).setDuration(320).setInterpolator(EASE).start()
        searchField.requestFocus()
        searchField.postDelayed({
            imm().showSoftInput(searchField, InputMethodManager.SHOW_IMPLICIT)
        }, 150)
    }

    private fun closeSearch(animate: Boolean = true) {
        imm().hideSoftInputFromWindow(searchField.windowToken, 0)
        searchField.clearFocus()
        animateBlur(0f, if (animate) 260 else 1)
        if (!animate) {
            searchOverlay.visibility = View.GONE
            content.scaleX = 1f; content.scaleY = 1f
            return
        }
        content.animate().scaleX(1f).scaleY(1f).setDuration(300).setInterpolator(EASE).start()
        searchOverlay.animate().alpha(0f).translationY(-dp(30).toFloat()).setDuration(220)
            .setInterpolator(EASE).withEndAction { searchOverlay.visibility = View.GONE }.start()
    }

    private fun filterSearch(q: String) {
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
        searchHeader.text = if (query.isEmpty()) "Suggestions" else "Apps"
        searchAdapter.submit(searchResults)
    }

    private fun imm() = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

    // ================================================================ actions

    /** Launch with a zoom-from-icon animation while the home screen zooms past you. */
    private fun launch(app: AppInfo, from: View?) {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(app.component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        startWithZoom(intent, from) {
            recordRecent(app)
            if (searchOverlay.visibility == View.VISIBLE) closeSearch(animate = false)
        }
    }

    private fun startWithZoom(intent: Intent, from: View?, onStarted: () -> Unit = {}) {
        val opts = from?.let {
            ActivityOptions.makeScaleUpAnimation(it, 0, 0, it.width, it.height).toBundle()
        }
        runCatching { startActivity(intent, opts) }
            .onSuccess {
                onStarted()
                content.animate().scaleX(1.1f).scaleY(1.1f).alpha(0f)
                    .setDuration(300).setInterpolator(EASE).start()
                // If nothing actually covered us (e.g. a dialog), come back.
                mainHandler.postDelayed({ if (isActive) restoreHome(animated = true) }, 900)
            }
            .onFailure { toast("Can't open this") }
    }

    private fun openIntent(intent: Intent, from: View?) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startWithZoom(intent, from)
    }

    private fun openCalendar(from: View?) =
        openIntent(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALENDAR), from)

    private fun showAppMenu(app: AppInfo, anchor: View) {
        val keys = dockKeys()
        val inDock = app.key in keys
        val items = listOf(
            MenuItem(if (inDock) "Remove from Dock" else "Add to Dock") {
                if (inDock) setDock(keys - app.key)
                else if (keys.size >= DOCK_MAX) toast("Dock is full — remove an app from it first")
                else setDock(keys + app.key)
            },
            MenuItem("App Info") {
                runCatching {
                    startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.packageName}"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            },
            MenuItem("Remove App", destructive = true) {
                runCatching {
                    startActivity(
                        Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.packageName}"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
        )
        showContextMenu(anchor, items)
    }

    private fun showHomeMenu(anchor: View) {
        anchor.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        val items = mutableListOf(
            MenuItem("Use Dark Glass Wallpaper") { applyGlassWallpaper() },
            MenuItem("Change Wallpaper…") {
                runCatching {
                    startActivity(Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), "Choose wallpaper"))
                }
            }
        )
        if (!hasNotificationAccess()) {
            items += MenuItem("Turn On Badges & Music") {
                runCatching { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
            }
        }
        items += MenuItem("Default Home App…") { openHomeSettings() }
        showContextMenu(null, items)
    }

    /** Generates the smoky dark-gray gradient wallpaper from the reference look. */
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
        runCatching { startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) }
            .onFailure { runCatching { startActivity(Intent(Settings.ACTION_SETTINGS)) } }
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
    }

    // ================================================================ utils

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()
    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).roundToInt()
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
