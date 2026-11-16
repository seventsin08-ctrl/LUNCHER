package com.luncher.home

import android.app.Activity
import android.app.ActivityOptions
import android.app.WallpaperManager
import android.content.BroadcastReceiver
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
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.BatteryManager
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
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.view.animation.DecelerateInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.Space
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
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
        private const val KEY_DOCK = "dock"
        private const val KEY_RECENT = "recent"
        private const val REQ_PHOTO = 4100
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

    private val io = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val showSearchPill = Runnable { setPillMode(search = true) }

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = reloadApps()
    }
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = updateBattery(i)
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        prefs = getSharedPreferences("luncher", MODE_PRIVATE)
        buildUi()
        setContentView(root)

        gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (e1 == null || searchOverlay.visibility == View.VISIBLE) return false
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

        registerReceivers()
        reloadApps()
    }

    override fun onResume() {
        super.onResume()
        defaultBanner.visibility = if (isDefaultHome()) View.GONE else View.VISIBLE
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (searchOverlay.visibility == View.VISIBLE) closeSearch()
        else if (pager.currentItem != 0) pager.currentItem = 0
    }

    @Deprecated("Launcher handles back itself")
    override fun onBackPressed() {
        if (searchOverlay.visibility == View.VISIBLE) closeSearch()
        else if (pager.currentItem != 0) pager.currentItem = 0
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (::gestures.isInitialized) gestures.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(packageReceiver) }
        runCatching { unregisterReceiver(batteryReceiver) }
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

    // ---------------------------------------------------------------- UI skeleton

    private fun buildUi() {
        root = FrameLayout(this)
        // A soft dark scrim keeps white labels readable on any wallpaper.
        root.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.argb(70, 0, 0, 0), Color.argb(25, 0, 0, 0), Color.argb(110, 0, 0, 0))
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
            offscreenPageLimit = 1
        }
        content.addView(pager, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        // Search pill / page dots share one spot like iOS 26: dots appear while swiping.
        val pillHolder = FrameLayout(this)
        searchPill = TextView(this).apply {
            text = "Search"
            setTextColor(0xE6FFFFFF.toInt())
            textSize = 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_search, 0, 0, 0)
            compoundDrawablePadding = dp(5)
            background = Glass.panel(this@MainActivity, 16f)
            setPadding(dp(14), 0, dp(16), 0)
            setOnClickListener { openSearch() }
        }
        pillHolder.addView(searchPill, FrameLayout.LayoutParams(WRAP_CONTENT, dp(32), Gravity.CENTER))
        dots = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = Glass.panel(this@MainActivity, 16f)
            setPadding(dp(10), 0, dp(10), 0)
            alpha = 0f
        }
        pillHolder.addView(dots, FrameLayout.LayoutParams(WRAP_CONTENT, dp(26), Gravity.CENTER))
        content.addView(pillHolder, LinearLayout.LayoutParams(MATCH_PARENT, dp(44)))

        dockRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Glass.panel(this@MainActivity, 34f)
            setPadding(dp(8), dp(13), dp(8), dp(13))
        }
        content.addView(dockRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            setMargins(dp(10), dp(4), dp(10), dp(8))
        })

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
                if (state == ViewPager2.SCROLL_STATE_IDLE) mainHandler.postDelayed(showSearchPill, 1100)
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
    }

    private fun buildSearchOverlay() {
        searchOverlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xD9101012.toInt())
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
        searchPill.animate().alpha(if (search) 1f else 0f).setDuration(180).start()
        dots.animate().alpha(if (search) 0f else 1f).setDuration(180).start()
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

    // ---------------------------------------------------------------- data

    private fun reloadApps() {
        val iconPx = dp(ICON_DP) * 2
        io.execute {
            val apps = runCatching { AppLoader.load(this, iconPx) }.getOrDefault(emptyList())
            mainHandler.post {
                if (!isDestroyed) {
                    allApps = apps
                    rebuild()
                }
            }
        }
    }

    private fun rowsPerPage(): Int {
        val m = resources.displayMetrics
        val hDp = m.heightPixels / m.density
        return ((hDp - 230f) / CELL_H).toInt().coerceIn(4, 7)
    }

    private fun widgetsFor(page: Int): List<PlacedWidget> = when (page) {
        0 -> listOf(
            PlacedWidget(WidgetType.PHOTO, 0, 0, 2, 2, "p1"),
            PlacedWidget(WidgetType.CLOCK_ICON, 2, 0, 1, 1),
            PlacedWidget(WidgetType.CALENDAR_ICON, 3, 0, 1, 1),
            PlacedWidget(WidgetType.CALENDAR, 0, 2, 4, 2)
        )
        1 -> listOf(
            PlacedWidget(WidgetType.BATTERY, 2, 0, 2, 2),
            PlacedWidget(WidgetType.PHOTO, 0, 2, 2, 2, "p2")
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
        while (page < 2 || next < home.size) {
            val widgets = widgetsFor(page)
            val taken = BooleanArray(rows * COLUMNS)
            for (w in widgets) {
                for (r in w.row until minOf(rows, w.row + w.h)) {
                    for (c in w.col until w.col + w.w) taken[r * COLUMNS + c] = true
                }
            }
            val apps = mutableListOf<Pair<AppInfo, Int>>()
            for (cell in 0 until rows * COLUMNS) {
                if (!taken[cell] && next < home.size) apps += home[next++] to cell
            }
            result += Page.Home(widgets.filter { it.row + it.h <= rows }, apps)
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
    private fun refreshPages() = pagesAdapter.notifyDataSetChanged()

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

    // ---------------------------------------------------------------- pages

    private inner class PagesAdapter : RecyclerView.Adapter<PageHolder>() {
        override fun getItemCount(): Int = pages.size

        override fun getItemViewType(position: Int): Int = if (pages[position] is Page.Library) 1 else 0

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): PageHolder =
            PageHolder(FrameLayout(parent.context).apply {
                layoutParams = android.view.ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)
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
            setOnLongClickListener { showHomeMenu(it); true }
        }
        for (w in p.widgets) frame.addView(buildWidget(w), place(w.col, w.row, w.w, w.h))
        for ((app, cell) in p.apps) {
            frame.addView(makeAppCell(app), place(cell % COLUMNS, cell / COLUMNS, 1, 1))
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
            clipToPadding = false
        }
        col.addView(list, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        return col
    }

    // ---------------------------------------------------------------- cells & widgets

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

    /** Generic icon cell: icon view + optional label, with iOS-like press feedback. */
    private fun makeCell(
        icon: View,
        text: String?,
        onClick: (View) -> Unit,
        onLong: ((View) -> Unit)?
    ): View {
        val cell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(4), 0, 0)
        }
        icon.outlineProvider = roundedOutline()
        icon.clipToOutline = true
        icon.elevation = dp(3).toFloat()
        cell.addView(icon, LinearLayout.LayoutParams(dp(ICON_DP), dp(ICON_DP)))
        if (text != null) {
            cell.addView(label(text), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                topMargin = dp(5); leftMargin = dp(2); rightMargin = dp(2)
            })
        }
        cell.setOnClickListener { onClick(icon) }
        if (onLong != null) cell.setOnLongClickListener { onLong(icon); true }
        cell.setOnTouchListener(pressEffect(icon))
        return cell
    }

    private fun makeAppCell(app: AppInfo, showLabel: Boolean = true): View {
        val icon = ImageView(this).apply { setImageBitmap(app.icon) }
        return makeCell(icon, if (showLabel) app.label else null, { launch(app, it) }, { showAppMenu(app, it) })
    }

    private fun pressEffect(target: View) = View.OnTouchListener { _, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN ->
                target.animate().scaleX(0.88f).scaleY(0.88f).alpha(0.75f).setDuration(110).start()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                target.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(200).start()
        }
        false
    }

    /** A widget: glass box spanning cells, with a small label beneath it. */
    private fun widgetFrame(inner: View, text: String, onClick: (View) -> Unit, onLong: ((View) -> Unit)? = null): View {
        val inset = ((cellW() - dp(ICON_DP)) / 2).coerceAtLeast(0)
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(inset, dp(4), inset, 0)
        }
        val box = FrameLayout(this).apply {
            background = Glass.panel(this@MainActivity, 22f)
            outlineProvider = roundedOutline(22f)
            clipToOutline = true
            elevation = dp(3).toFloat()
        }
        box.addView(inner, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        wrap.addView(box, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        wrap.addView(label(text), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            topMargin = dp(5); bottomMargin = dp(9)
        })
        box.setOnClickListener { onClick(box) }
        if (onLong != null) box.setOnLongClickListener { onLong(box); true }
        box.setOnTouchListener(pressEffectSoft(box))
        return wrap
    }

    private fun pressEffectSoft(target: View) = View.OnTouchListener { _, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> target.animate().scaleX(0.96f).scaleY(0.96f).setDuration(120).start()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                target.animate().scaleX(1f).scaleY(1f).setDuration(200).start()
        }
        false
    }

    private fun buildWidget(w: PlacedWidget): View = when (w.type) {
        WidgetType.CLOCK_ICON ->
            makeCell(AnalogClockView(this), "Clock", { openIntent(Intent(AlarmClock.ACTION_SHOW_ALARMS), it) }, null)
        WidgetType.CALENDAR_ICON ->
            makeCell(CalendarIconView(this), "Calendar", { openCalendar(it) }, null)
        WidgetType.PHOTO ->
            widgetFrame(photoWidget(w.id), "Photo", { v -> photoTapped(w.id, v) }, { v -> showPhotoMenu(w.id, v) })
        WidgetType.CALENDAR ->
            widgetFrame(calendarWidget(), "Calendar", { openCalendar(it) })
        WidgetType.BATTERY ->
            widgetFrame(batteryWidget(), "Batteries", { openIntent(Intent(Intent.ACTION_POWER_USAGE_SUMMARY), it) })
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
                        img.setImageBitmap(bmp)
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

    // ---------------------------------------------------------------- photos

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
        anchor.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        val bw = prefs.getBoolean("photo_bw_$id", true)
        val menu = PopupMenu(this, anchor)
        menu.menu.add(0, 1, 0, "Choose Photo")
        menu.menu.add(0, 2, 1, if (bw) "Show in Color" else "Black & White")
        if (prefs.getString("photo_$id", null) != null) menu.menu.add(0, 3, 2, "Remove Photo")
        menu.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> pickPhoto(id)
                2 -> { prefs.edit().putBoolean("photo_bw_$id", !bw).apply(); refreshPages() }
                3 -> {
                    prefs.edit().remove("photo_$id").apply()
                    photoCache.remove(id)
                    refreshPages()
                }
            }
            true
        }
        menu.show()
    }

    private fun decodeSampled(uri: Uri, maxPx: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxPx && bounds.outHeight / (sample * 2) >= maxPx) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }.getOrNull()

    // ---------------------------------------------------------------- search

    private fun openSearch() {
        searchField.setText("")
        filterSearch("")
        searchOverlay.alpha = 0f
        searchOverlay.translationY = -dp(40).toFloat()
        searchOverlay.visibility = View.VISIBLE
        searchOverlay.animate().alpha(1f).translationY(0f).setDuration(220)
            .setInterpolator(DecelerateInterpolator()).start()
        searchField.requestFocus()
        searchField.postDelayed({
            imm().showSoftInput(searchField, InputMethodManager.SHOW_IMPLICIT)
        }, 150)
    }

    private fun closeSearch(animate: Boolean = true) {
        imm().hideSoftInputFromWindow(searchField.windowToken, 0)
        searchField.clearFocus()
        if (!animate) {
            searchOverlay.visibility = View.GONE
            return
        }
        searchOverlay.animate().alpha(0f).translationY(-dp(40).toFloat()).setDuration(180)
            .withEndAction { searchOverlay.visibility = View.GONE }.start()
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

    // ---------------------------------------------------------------- actions

    private fun launch(app: AppInfo, from: View?) {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(app.component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        val opts = from?.let {
            ActivityOptions.makeScaleUpAnimation(it, 0, 0, it.width, it.height).toBundle()
        }
        runCatching { startActivity(intent, opts) }
            .onSuccess {
                recordRecent(app)
                if (searchOverlay.visibility == View.VISIBLE) closeSearch(animate = false)
            }
            .onFailure { toast("Can't open ${app.label}") }
    }

    private fun openIntent(intent: Intent, from: View?) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val opts = from?.let { ActivityOptions.makeScaleUpAnimation(it, 0, 0, it.width, it.height).toBundle() }
        runCatching { startActivity(intent, opts) }.onFailure { toast("No app found for this") }
    }

    private fun openCalendar(from: View?) =
        openIntent(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALENDAR), from)

    private fun showAppMenu(app: AppInfo, anchor: View) {
        anchor.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        val keys = dockKeys()
        val inDock = app.key in keys
        val menu = PopupMenu(this, anchor)
        menu.menu.add(0, 1, 0, if (inDock) "Remove from Dock" else "Add to Dock")
        menu.menu.add(0, 2, 1, "App Info")
        menu.menu.add(0, 3, 2, "Remove App")
        menu.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> if (inDock) setDock(keys - app.key)
                else if (keys.size >= DOCK_MAX) toast("Dock is full — remove an app from it first")
                else setDock(keys + app.key)
                2 -> openIntent(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.packageName}")),
                    null
                )
                3 -> openIntent(Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.packageName}")), null)
            }
            true
        }
        menu.show()
    }

    private fun showHomeMenu(anchor: View) {
        anchor.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        val menu = PopupMenu(this, anchor, Gravity.CENTER)
        menu.menu.add(0, 1, 0, "Use Dark Glass Wallpaper")
        menu.menu.add(0, 2, 1, "Change Wallpaper…")
        menu.menu.add(0, 3, 2, "Default Home App…")
        menu.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> applyGlassWallpaper()
                2 -> runCatching {
                    startActivity(Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), "Choose wallpaper"))
                }
                3 -> openHomeSettings()
            }
            true
        }
        menu.show()
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

    // ---------------------------------------------------------------- receivers

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

    // ---------------------------------------------------------------- utils

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()
    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).roundToInt()
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
