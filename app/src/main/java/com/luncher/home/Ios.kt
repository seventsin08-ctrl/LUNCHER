package com.luncher.home

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min

/**
 * The iPhone features (1–35) and the rules that keep them in line with the launcher:
 *  - every feature has a screen ZONE; the [Stage] never lets two take the same space at once,
 *  - nothing is added to the Home Screen on its own (widgets only when you add them),
 *  - at most ONE always-moving animation runs at a time,
 *  - every feature can be switched off.
 * Pure Kotlin (no Android) so the simulations in the unit tests run on it directly.
 */
object Ios {

    /** Where on screen a feature lives. */
    enum class Zone {
        /** Changes how existing things behave/look; adds nothing to the screen. */
        NONE,
        /** Behind the Home Screen (wallpaper layer). */
        BACKGROUND,
        /** Only as a widget YOU add to a page. */
        HOME_GRID,
        /** Beside the Dynamic Island. */
        ISLAND_SIDE,
        /** A thin glow around the screen edge. */
        EDGE_GLOW,
        /** A sheet/panel over the Home Screen (one at a time). */
        SHEET,
        /** Covers the whole screen (one at a time). */
        FULLSCREEN,
        /** A short centred animation that goes away by itself. */
        TOAST
    }

    enum class Cost { STATIC, ON_TOUCH, BRIEF, CONTINUOUS }

    class Feature(
        val n: Int, val id: String, val title: String, val what: String,
        val zone: Zone, val cost: Cost, val defaultOn: Boolean
    )

    val ALL = listOf(
        Feature(1, "spring", "Spring Motion", "Every animation stretches, bounces and settles like on an iPhone", Zone.NONE, Cost.ON_TOUCH, true),
        Feature(2, "zoomback", "Zoom Back to Icon", "Closing an app shrinks it back into the icon you opened it from", Zone.NONE, Cost.BRIEF, true),
        Feature(3, "shine", "Liquid Glass Shine", "Glass panels catch light on their edges", Zone.NONE, Cost.STATIC, true),
        Feature(4, "glassall", "One Glass Slider", "One slider sets every glass surface, clear to frosted", Zone.NONE, Cost.STATIC, true),
        Feature(5, "clearicons", "Clear Glass Icons", "App icons as see-through glass", Zone.NONE, Cost.STATIC, true),
        Feature(6, "morph", "Smoother Island Morphing", "The Dynamic Island stretches smoothly between shapes", Zone.NONE, Cost.BRIEF, true),
        Feature(7, "ccbounce", "Bouncy Control Center", "Tiles bounce into place when Control Center opens", Zone.NONE, Cost.BRIEF, true),
        Feature(8, "folders", "Smoother Folders", "Folders zoom open and closed from where they sit", Zone.NONE, Cost.BRIEF, true),
        Feature(9, "freeplace", "Free Icon Placement", "Icons anywhere on the page, gaps allowed", Zone.NONE, Cost.STATIC, true),
        Feature(10, "jiggle", "Jiggle Mode", "Hold the screen: icons wiggle with a – to remove", Zone.NONE, Cost.CONTINUOUS, true),
        Feature(11, "stacking", "Icon Stacking", "While dragging an icon, tap others to carry them together", Zone.NONE, Cost.ON_TOUCH, true),
        Feature(12, "searchpill", "Search Pill", "A small Search button above the dock", Zone.NONE, Cost.STATIC, true),
        Feature(13, "applock", "App Lock", "Lock chosen apps behind your fingerprint", Zone.NONE, Cost.ON_TOUCH, true),
        Feature(14, "quickglass", "Glass Quick Actions", "Hold an icon: glass shortcut menu, the rest blurs", Zone.NONE, Cost.BRIEF, true),
        Feature(15, "xlwidget", "Extra Large Widget", "One widget fills a whole page", Zone.HOME_GRID, Cost.STATIC, true),
        Feature(16, "smartstack", "Auto Smart Stack", "The stack shows the most useful widget for the moment", Zone.NONE, Cost.STATIC, true),
        Feature(17, "interactive", "Interactive Widgets", "Tick reminders without opening an app", Zone.HOME_GRID, Cost.ON_TOUCH, true),
        Feature(18, "screentime", "Screen Time Widget", "Today's phone time and top apps", Zone.HOME_GRID, Cost.STATIC, true),
        Feature(19, "stocks", "Stocks Widget", "Live prices for stocks you pick", Zone.HOME_GRID, Cost.STATIC, true),
        Feature(20, "shortcuts", "Shortcuts Grid Widget", "Up to 16 buttons for your favourite actions", Zone.HOME_GRID, Cost.STATIC, true),
        Feature(21, "today", "Today View", "Swipe right on the first page: a column of widgets", Zone.SHEET, Cost.BRIEF, true),
        Feature(22, "split", "Split Island", "Two live things at once: the pill plus a small bubble", Zone.ISLAND_SIDE, Cost.STATIC, true),
        Feature(23, "nc", "Notification Center", "Pull down from the top: notifications grouped by app", Zone.SHEET, Cost.BRIEF, true),
        Feature(24, "summary", "Scheduled Summary", "Quiet apps' notifications arrive together at set times", Zone.NONE, Cost.STATIC, false),
        Feature(25, "ccpages", "Multi-Page Control Center", "Swipe between pages of controls", Zone.NONE, Cost.STATIC, true),
        Feature(26, "ccresize", "Resizable Controls", "Hold a control to make it small, wide or big", Zone.NONE, Cost.ON_TOUCH, true),
        Feature(27, "spotlight", "Better Spotlight", "Search also finds settings and files", Zone.NONE, Cost.STATIC, true),
        Feature(28, "siri", "Siri Suggestions", "Apps you'll probably open next, in Search", Zone.NONE, Cost.STATIC, true),
        Feature(29, "glow", "Siri Glow", "A rainbow glow round the screen when the assistant listens", Zone.EDGE_GLOW, Cost.CONTINUOUS, true),
        Feature(30, "focuspages", "Focus Home Pages", "A Focus can keep you on its own page", Zone.NONE, Cost.STATIC, true),
        Feature(31, "standby", "StandBy Mode", "Sideways while charging: big clock, date and photo", Zone.FULLSCREEN, Cost.STATIC, true),
        Feature(32, "charging", "Charging Animation", "A glowing ring when you plug in", Zone.TOAST, Cost.BRIEF, true),
        Feature(33, "weatherwall", "Live Weather Wallpaper", "The sky moves with the real weather", Zone.BACKGROUND, Cost.CONTINUOUS, false),
        Feature(34, "lockpage", "Lock-Style First Page", "After unlocking: big clock and date, swipe up to go", Zone.FULLSCREEN, Cost.BRIEF, false),
        Feature(35, "haptics", "Tuned Haptics", "Different small vibrations for taps, holds and swipes", Zone.NONE, Cost.STATIC, true),
        Feature(36, "landscape", "Landscape Home Screen", "Turn the phone sideways: widgets left, apps middle, dock right", Zone.FULLSCREEN, Cost.STATIC, true)
    )

    fun byId(id: String): Feature = ALL.first { it.id == id }

    // ------------------------------------------------------------------ Stage: no overlaps

    /**
     * Decides what may appear. Rules:
     *  1. One SHEET or FULLSCREEN at a time (they'd cover each other).
     *  2. TOAST and EDGE_GLOW never draw over a FULLSCREEN feature; one of each at a time.
     *  3. ISLAND_SIDE: one bubble beside the island (and not while [islandSideBusy]).
     *  4. Only one CONTINUOUS animation; the BACKGROUND sky pauses while anything covers it.
     */
    class Stage {
        private val showing = LinkedHashSet<String>()
        var islandSideBusy = false
        /** Told when a feature had to make room (so the app can hide it). */
        var onEvict: ((String) -> Unit)? = null

        fun isShowing(id: String) = id in showing
        fun showing(): Set<String> = showing.toSet()
        private fun zoneOf(id: String) = byId(id).zone
        private fun anyIn(vararg z: Zone) = showing.any { zoneOf(it) in z }

        fun request(id: String): Boolean {
            if (id in showing) return true
            val f = byId(id)
            val ok = when (f.zone) {
                Zone.NONE, Zone.HOME_GRID -> true
                Zone.SHEET, Zone.FULLSCREEN -> !anyIn(Zone.SHEET, Zone.FULLSCREEN)
                Zone.TOAST -> !anyIn(Zone.FULLSCREEN, Zone.TOAST, Zone.SHEET)
                Zone.EDGE_GLOW -> !anyIn(Zone.FULLSCREEN, Zone.EDGE_GLOW)
                Zone.ISLAND_SIDE -> !islandSideBusy && !anyIn(Zone.ISLAND_SIDE, Zone.FULLSCREEN)
                Zone.BACKGROUND -> !anyIn(Zone.BACKGROUND)
            }
            if (!ok) return false
            // A panel clears the small things it would otherwise cover (charging ring, glow, island bubble).
            val evict = when (f.zone) {
                Zone.FULLSCREEN -> setOf(Zone.TOAST, Zone.EDGE_GLOW, Zone.ISLAND_SIDE)
                Zone.SHEET -> setOf(Zone.TOAST)
                else -> emptySet()
            }
            if (f.cost == Cost.CONTINUOUS && f.zone != Zone.BACKGROUND && continuousFg() != null) return false
            for (other in showing.filter { zoneOf(it) in evict }) { showing -= other; onEvict?.invoke(other) }
            // Only one always-moving animation: a new one pauses the background sky.
            if (f.cost == Cost.CONTINUOUS && f.zone != Zone.BACKGROUND && continuousFg() != null) return false
            showing += id
            return true
        }

        fun release(id: String) { showing -= id }

        private fun continuousFg() = showing.firstOrNull { byId(it).cost == Cost.CONTINUOUS && byId(it).zone != Zone.BACKGROUND }

        /** The live sky may move only when nothing covers it and nothing else is animating forever. */
        fun backgroundMayAnimate(): Boolean =
            !anyIn(Zone.SHEET, Zone.FULLSCREEN) && continuousFg() == null

        /** Things a new one-of-a-kind feature must close first (used by "close others then open"). */
        fun blockers(id: String): List<String> {
            val z = byId(id).zone
            return when (z) {
                Zone.SHEET, Zone.FULLSCREEN -> showing.filter { zoneOf(it) in setOf(Zone.SHEET, Zone.FULLSCREEN, Zone.TOAST) }
                else -> emptyList()
            }
        }
    }

    // ------------------------------------------------------------------ 1. springs

    class Spring(val stiffness: Float, val damping: Float)
    val SNAPPY = Spring(1400f, 0.9f)
    val BOUNCY = Spring(520f, 0.62f)
    val FLUID = Spring(420f, 0.82f)
    val CALM = Spring(260f, 0.95f)
    val ISLAND = Spring(340f, 0.84f)
    val SPRINGS = listOf(SNAPPY, BOUNCY, FLUID, CALM, ISLAND)

    /** Time (ms) for a spring to settle within 1% — iOS motions settle in well under a second. */
    fun settleMs(s: Spring, scale: Float = 1f): Int {
        val k = (s.stiffness / (scale * scale)).coerceIn(30f, 10000f)
        val w = kotlin.math.sqrt(k.toDouble())                 // natural frequency (mass 1)
        val z = s.damping.toDouble()
        val decay = if (z < 1) z * w else w                     // envelope rate
        return (kotlin.math.ln(100.0) / decay * 1000).toInt()
    }

    /** Biggest overshoot (fraction of the move) — keeps bounces subtle. */
    fun overshoot(s: Spring): Double {
        val z = s.damping.toDouble()
        if (z >= 1) return 0.0
        return exp(-z * Math.PI / kotlin.math.sqrt(1 - z * z))
    }

    // ------------------------------------------------------------------ 2. zoom back

    /** Zoom back only right after an app YOU opened from an icon, never over a panel or while editing. */
    fun zoomBack(enabled: Boolean, animOn: Boolean, launchedAgoMs: Long, overlay: Boolean, editing: Boolean) =
        enabled && animOn && launchedAgoMs in 0..30 * 60_000L && !overlay && !editing

    // ------------------------------------------------------------------ 6. island morph

    /** Gooey squash for a big width change: returns the start scaleY, or null for small changes. */
    fun squash(fromW: Int, toW: Int, threshold: Int): Float? = when {
        kotlin.math.abs(toW - fromW) <= threshold -> null
        toW > fromW -> 0.94f
        else -> 1.05f
    }

    // ------------------------------------------------------------------ 11. stacking

    /** Put the carried icons right after the one you dropped, in the order you picked them. */
    fun reorderStack(order: List<String>, main: String, stack: List<String>): List<String> {
        val carried = stack.filter { it != main && it != HomeGridOrder.EMPTY }.distinct()
        if (carried.isEmpty() || main !in order) return order
        val out = order.map { if (it in carried) HomeGridOrder.EMPTY else it }.toMutableList()
        val at = out.indexOf(main)
        // Fill the gaps right after the dropped icon first, then insert the rest.
        var pos = at + 1
        for (k in carried) {
            if (pos < out.size && out[pos] == HomeGridOrder.EMPTY) out[pos] = k else out.add(pos.coerceAtMost(out.size), k)
            pos++
        }
        while (out.lastOrNull() == HomeGridOrder.EMPTY) out.removeAt(out.lastIndex)
        return out
    }

    // ------------------------------------------------------------------ 15. XL widget

    /** Extra Large = full width, 4 rows (or all rows on short screens). Never larger than a page. */
    fun xlSize(cols: Int, rows: Int): Pair<Int, Int> = cols to min(4, rows).coerceAtLeast(1)

    // ------------------------------------------------------------------ 16. smart stack

    /** Stack pages: 0 date, 1 batteries, 2 timer, 3 clock. */
    fun stackPick(hour: Int, timerRunning: Boolean, battery: Int, charging: Boolean, current: Int): Int = when {
        timerRunning -> 2
        battery in 0..20 && !charging -> 1
        charging && battery < 100 -> 1
        hour in 5..11 -> 0
        hour >= 20 || hour < 5 -> 3
        else -> current
    }

    // ------------------------------------------------------------------ 18/19 formatting

    fun duration(ms: Long): String {
        val m = (ms / 60_000).coerceAtLeast(0)
        return if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m"
    }

    /** Reads price and previous close out of a Yahoo chart reply without a JSON library. */
    fun parseQuote(json: String): Pair<Double, Double>? {
        fun num(key: String) = Regex("\"$key\"\\s*:\\s*(-?[0-9.]+)").find(json)?.groupValues?.get(1)?.toDoubleOrNull()
        val price = num("regularMarketPrice") ?: return null
        val prev = num("chartPreviousClose") ?: num("previousClose") ?: price
        return price to prev
    }

    fun change(price: Double, prev: Double): String {
        if (prev == 0.0) return "0.00%"
        val p = (price - prev) / prev * 100
        return (if (p >= 0) "+" else "−") + String.format(java.util.Locale.US, "%.2f%%", abs(p))
    }

    fun cleanTickers(raw: String): List<String> =
        raw.split(',', ' ', ';').map { it.trim().uppercase() }.filter { it.matches(Regex("[A-Z0-9.^=-]{1,10}")) }.distinct().take(4)

    // ------------------------------------------------------------------ 20. shortcuts grid

    /** Up to 16 buttons; the grid is 4 wide and only as tall as needed. */
    fun shortcutGrid(count: Int): Pair<Int, Int> { val n = count.coerceIn(0, 16); return 4 to ((n + 3) / 4).coerceAtLeast(1) }

    // ------------------------------------------------------------------ 22. split island

    /**
     * Where the small bubble goes (centre x, relative to the screen centre), or null if it doesn't fit.
     * It sits right of the pill, or left if the right edge is too close; never over the pill.
     */
    fun splitX(pillCenter: Float, pillW: Float, bubble: Float, gap: Float, screenW: Float, margin: Float): Float? {
        val half = screenW / 2f
        val right = pillCenter + pillW / 2f + gap + bubble / 2f
        if (half + right + bubble / 2f <= screenW - margin) return right
        val left = pillCenter - pillW / 2f - gap - bubble / 2f
        if (half + left - bubble / 2f >= margin) return left
        return null
    }

    // ------------------------------------------------------------------ 23/24. notifications

    class Note(val pkg: String, val title: String, val time: Long)

    /** Groups by app, newest app first, at most [perApp] shown per group. */
    fun group(notes: List<Note>, perApp: Int = 3): List<Pair<String, List<Note>>> =
        notes.groupBy { it.pkg }.map { (k, v) -> k to v.sortedByDescending { it.time } }
            .sortedByDescending { it.second.first().time }.map { (k, v) -> k to v.take(perApp) }

    /** Minutes until the next summary delivery (times are minutes after midnight). */
    fun nextSummary(times: List<Int>, nowMin: Int): Int {
        val t = times.filter { it in 0 until 1440 }.sorted().ifEmpty { listOf(8 * 60, 18 * 60) }
        val next = t.firstOrNull { it > nowMin } ?: (t.first() + 1440)
        return next - nowMin
    }

    /** A summary is due when a delivery time passed since we last delivered. */
    fun summaryDue(times: List<Int>, lastMin: Long, nowMin: Long): Boolean {
        if (nowMin <= lastMin) return false
        val t = times.filter { it in 0 until 1440 }.ifEmpty { listOf(8 * 60, 18 * 60) }
        val dayStart = nowMin - nowMin % 1440
        return t.any { at -> val abs = dayStart + at; abs in (lastMin + 1)..nowMin } ||
            t.any { at -> val abs = dayStart - 1440 + at; abs in (lastMin + 1)..nowMin }
    }

    // ------------------------------------------------------------------ 25/26. control center page

    class Tile(val id: String, val w: Int, val h: Int)
    class Placed(val id: String, val col: Int, val row: Int, val w: Int, val h: Int)

    /** Sizes a control can take: small 1×1, wide 2×1, big 2×2. */
    val SIZES = listOf(1 to 1, 2 to 1, 2 to 2)
    fun nextSize(w: Int, h: Int): Pair<Int, Int> { val i = SIZES.indexOf(w to h); return SIZES[(i + 1) % SIZES.size] }

    /** First-fit packing into a 4-wide grid: never overlaps, never wider than the grid. */
    fun pack(tiles: List<Tile>, cols: Int = 4): List<Placed> {
        val used = HashSet<Pair<Int, Int>>()
        val out = ArrayList<Placed>()
        for (t in tiles) {
            val w = t.w.coerceIn(1, cols); val h = t.h.coerceIn(1, 2)
            var r = 0
            loop@ while (true) {
                for (c in 0..cols - w) {
                    val cells = (0 until h).flatMap { dy -> (0 until w).map { dx -> (c + dx) to (r + dy) } }
                    if (cells.none { it in used }) { used += cells; out += Placed(t.id, c, r, w, h); break@loop }
                }
                r++
            }
        }
        return out
    }

    // ------------------------------------------------------------------ 28. siri suggestions

    class Launch(val key: String, val at: Long)

    /**
     * Scores each app by how often you open it around this time of day, favouring recent days and
     * the same kind of day (weekday / weekend). Returns the top [n].
     */
    fun suggest(log: List<Launch>, now: Long, n: Int = 4, exclude: Set<String> = emptySet(), tzOffsetMs: Long = 0): List<String> {
        if (log.isEmpty()) return emptyList()
        val day = 86_400_000.0
        fun hourOf(t: Long) = (((t + tzOffsetMs) % 86_400_000L + 86_400_000L) % 86_400_000L) / 3_600_000.0
        fun weekend(t: Long) = ((((t + tzOffsetMs) / 86_400_000L) + 4) % 7).let { it == 5L || it == 6L } // 1970-01-01 = Thu
        val h = hourOf(now); val we = weekend(now)
        val score = HashMap<String, Double>()
        for (l in log) {
            if (l.key in exclude || l.at > now) continue
            val ageDays = (now - l.at) / day
            if (ageDays > 28) continue
            val dh = abs(hourOf(l.at) - h).let { min(it, 24 - it) }
            val w = exp(-ageDays / 10.0) * exp(-(dh * dh) / (2 * 1.6 * 1.6)) * (if (weekend(l.at) == we) 1.3 else 1.0) + 0.02
            score[l.key] = (score[l.key] ?: 0.0) + w
        }
        return score.entries.sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key })
            .take(n).map { it.key }
    }

    // ------------------------------------------------------------------ 31/32. standby & charging

    fun standby(enabled: Boolean, charging: Boolean, landscape: Boolean, onHome: Boolean) =
        enabled && charging && landscape && onHome

    /** Landscape Home: only with the feature on, rotation lock off, and the Home Screen free. */
    fun landscape(enabled: Boolean, autoRotate: Boolean, onHomeFree: Boolean) = enabled && autoRotate && onHomeFree

    fun chargingRing(enabled: Boolean, was: Boolean?, now: Boolean, onHome: Boolean) =
        enabled && was == false && now && onHome

    // ------------------------------------------------------------------ 33. weather sky

    enum class Sky { CLEAR_DAY, CLEAR_NIGHT, CLOUDS, RAIN, SNOW, STORM, FOG }

    /** WMO weather codes (open-meteo) + hour → which sky to draw. */
    fun sky(code: Int, hour: Int): Sky = when (code) {
        in 95..99 -> Sky.STORM
        in 71..77, 85, 86 -> Sky.SNOW
        in 51..67, in 80..82 -> Sky.RAIN
        45, 48 -> Sky.FOG
        2, 3 -> Sky.CLOUDS
        else -> if (hour in 6..18) Sky.CLEAR_DAY else Sky.CLEAR_NIGHT
    }

    /** Frames per second the sky redraws at — slow on purpose, and 0 when it must stay still. */
    fun skyFps(sky: Sky, mayAnimate: Boolean, batterySaver: Boolean): Int = when {
        !mayAnimate -> 0
        batterySaver -> 0
        sky == Sky.RAIN || sky == Sky.SNOW || sky == Sky.STORM -> 24
        else -> 12
    }

    // ------------------------------------------------------------------ 35. haptics

    enum class Touch { TAP, HOLD, SWIPE, TOGGLE, DROP, ERROR }
}
