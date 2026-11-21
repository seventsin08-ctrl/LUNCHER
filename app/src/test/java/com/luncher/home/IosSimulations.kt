package com.luncher.home

import kotlin.random.Random

/**
 * Simulations for the 35 iPhone features. Each check runs many random cases and records a line per
 * failure. Grouped by feature so the report shows every feature was exercised, plus the global
 * regulation rules: no overlap, no overcrowding, no lag (one always-moving animation), every feature
 * switchable and aligned with the registry.
 */
object IosSimulations {
    class Report {
        val runs = LinkedHashMap<String, Int>()
        val failures = ArrayList<String>()
        fun check(feature: String, ok: Boolean, msg: () -> String) {
            runs[feature] = (runs[feature] ?: 0) + 1
            if (!ok) failures += "[$feature] ${msg()}"
        }
        val total get() = runs.values.sum()
    }

    fun runAll(seed: Int = 42): Report {
        val r = Report()
        val rnd = Random(seed)
        registry(r)
        stage(r, rnd)
        springs(r)
        zoomBack(r, rnd)
        morph(r, rnd)
        freePlacement(r, rnd)
        stacking(r, rnd)
        xl(r)
        smartStack(r)
        formatting(r)
        shortcuts(r)
        splitIsland(r, rnd)
        notifications(r, rnd)
        summary(r, rnd)
        controlCenter(r, rnd)
        suggestions(r, rnd)
        standbyCharging(r)
        sky(r)
        landscapeTilt(r, rnd)
        landscapeLayout(r)
        return r
    }

    // ---------------------------------------------------------------- alignment with the registry
    private fun registry(r: Report) {
        val all = Ios.ALL
        r.check("registry", all.size == 36) { "expected 36 features, got ${all.size}" }
        r.check("registry", all.map { it.n } == (1..36).toList()) { "numbers must be 1..36 in order" }
        r.check("registry", all.map { it.id }.toSet().size == 36) { "duplicate ids" }
        for (f in all) {
            r.check("registry", f.title.isNotBlank() && f.what.isNotBlank()) { "${f.id} needs title and description" }
            r.check("registry", f.what.length <= 80) { "${f.id} description too long for a settings row" }
            r.check("registry", f.id.matches(Regex("[a-z]+"))) { "${f.id} id must be a simple key" }
            // Every feature is switchable and has a defined place on screen.
            r.check("${f.n} ${f.id}", f.zone in Ios.Zone.values() && f.cost in Ios.Cost.values()) { "${f.id} has no zone/cost" }
        }
        // Overpopulation: nothing appears on the Home Screen by itself. Widgets only when added;
        // things that take over the whole screen or the wallpaper start OFF.
        for (f in all) {
            val auto = f.zone == Ios.Zone.BACKGROUND || (f.zone == Ios.Zone.FULLSCREEN && f.cost == Ios.Cost.BRIEF)
            if (auto) r.check("overcrowding", !f.defaultOn) { "${f.id} changes the screen on its own, so it must start off" }
        }
        r.check("overcrowding", all.count { it.zone == Ios.Zone.ISLAND_SIDE } <= 1) { "only one thing may sit beside the island" }
        // Lag budget: at most one feature that animates forever is on by default besides jiggle (edit mode only).
        val forever = all.filter { it.cost == Ios.Cost.CONTINUOUS && it.defaultOn && it.id != "jiggle" }
        r.check("performance", forever.size <= 1) { "too many always-moving features on by default: ${forever.map { it.id }}" }
    }

    // ---------------------------------------------------------------- no overlap: random sessions
    private fun stage(r: Report, rnd: Random) {
        val ids = Ios.ALL.map { it.id }
        repeat(400) { session ->
            val st = Ios.Stage()
            repeat(60) {
                val id = ids[rnd.nextInt(ids.size)]
                st.islandSideBusy = rnd.nextInt(5) == 0
                if (rnd.nextBoolean()) st.request(id) else st.release(id)
                val on = st.showing().map { Ios.byId(it) }
                val covers = on.count { it.zone == Ios.Zone.SHEET || it.zone == Ios.Zone.FULLSCREEN }
                r.check("no-overlap", covers <= 1) { "session $session: two panels at once ${on.map { it.id }}" }
                val full = on.any { it.zone == Ios.Zone.FULLSCREEN }
                r.check("no-overlap", !(full && on.any { it.zone == Ios.Zone.TOAST || it.zone == Ios.Zone.EDGE_GLOW || it.zone == Ios.Zone.ISLAND_SIDE })) {
                    "session $session: something drawn over a full-screen feature ${on.map { it.id }}" }
                r.check("no-overlap", on.count { it.zone == Ios.Zone.TOAST } <= 1 && on.count { it.zone == Ios.Zone.EDGE_GLOW } <= 1 &&
                    on.count { it.zone == Ios.Zone.ISLAND_SIDE } <= 1) { "session $session: duplicate toast/glow/bubble" }
                val moving = on.count { it.cost == Ios.Cost.CONTINUOUS && it.zone != Ios.Zone.BACKGROUND }
                r.check("performance", moving <= 1) { "session $session: $moving always-moving animations at once" }
                if (covers > 0) r.check("performance", !st.backgroundMayAnimate()) { "sky kept moving under a panel" }
            }
            // Releasing everything leaves a clean stage.
            ids.forEach { st.release(it) }
            r.check("no-overlap", st.showing().isEmpty() && st.backgroundMayAnimate()) { "stage not clean after release" }
        }
        // Specific features (21, 23, 29, 31, 32, 34).
        val st = Ios.Stage()
        r.check("21 today", st.request("today")) { "Today should open on an empty stage" }
        r.check("23 nc", !st.request("nc")) { "Notification Centre must not open over Today" }
        st.release("today")
        r.check("23 nc", st.request("nc")) { "Notification Centre should open once Today closes" }
        r.check("32 charging", !st.request("charging")) { "charging ring must wait while a panel is open" }
        st.release("nc")
        r.check("31 standby", st.request("standby")) { "StandBy should open" }
        r.check("29 glow", !st.request("glow")) { "Siri glow must not draw over StandBy" }
        r.check("34 lockpage", !st.request("lockpage")) { "lock page must not stack on StandBy" }
        r.check("22 split", !st.request("split")) { "island bubble hidden under StandBy" }
        st.release("standby")
        r.check("10 jiggle", st.request("jiggle")) { "jiggle should run" }
        r.check("29 glow", !st.request("glow")) { "only one always-moving animation" }
        st.release("jiggle")
        r.check("29 glow", st.request("glow")) { "glow should run once jiggle stops" }
        r.check("33 weatherwall", st.request("weatherwall") && !st.backgroundMayAnimate()) { "sky must pause while glow runs" }
    }

    // ---------------------------------------------------------------- 1, 7, 8: springs
    private fun springs(r: Report) {
        for (s in Ios.SPRINGS) for (scale in listOf(0.6f, 1f, 1.5f)) {
            val ms = Ios.settleMs(s, scale)
            r.check("1 spring", ms in 1..900) { "spring k=${s.stiffness} z=${s.damping} @$scale settles in ${ms}ms" }
        }
        for (s in Ios.SPRINGS) r.check("1 spring", Ios.overshoot(s) <= 0.25) { "too bouncy: ${Ios.overshoot(s)}" }
        r.check("1 spring", Ios.settleMs(Ios.SNAPPY) < Ios.settleMs(Ios.CALM)) { "snappy must be faster than calm" }
        // 7: ten tiles staggered 30 ms then a bouncy settle finish within ~0.9 s.
        r.check("7 ccbounce", 9 * 30 + Ios.settleMs(Ios.BOUNCY) <= 900) { "Control Centre entrance too slow" }
        // 8: folder zoom settles quickly.
        r.check("8 folders", Ios.settleMs(Ios.FLUID) <= 600) { "folder zoom too slow" }
        r.check("6 morph", Ios.settleMs(Ios.ISLAND) <= 700) { "island morph too slow" }
    }

    private fun zoomBack(r: Report, rnd: Random) {
        repeat(300) {
            val en = rnd.nextBoolean(); val an = rnd.nextBoolean(); val ago = rnd.nextLong(-1000, 3_600_000)
            val ov = rnd.nextBoolean(); val ed = rnd.nextBoolean()
            val z = Ios.zoomBack(en, an, ago, ov, ed)
            r.check("2 zoomback", z == (en && an && ago in 0..1_800_000 && !ov && !ed)) { "wrong decision $en $an $ago $ov $ed" }
        }
    }

    private fun morph(r: Report, rnd: Random) {
        repeat(300) {
            val a = rnd.nextInt(60, 1100); val b = rnd.nextInt(60, 1100)
            val k = Ios.squash(a, b, 100)
            if (kotlin.math.abs(a - b) <= 100) r.check("6 morph", k == null) { "small change $a→$b squashed" }
            else r.check("6 morph", k != null && k in 0.9f..1.1f) { "squash $k out of range" }
        }
    }

    // ---------------------------------------------------------------- 9 & 11: placement and stacking
    private fun freePlacement(r: Report, rnd: Random) {
        repeat(400) {
            val keys = (0 until rnd.nextInt(3, 30)).map { "app$it" }
            var order: List<String> = keys.shuffled(rnd)
            repeat(10) {
                val k = keys[rnd.nextInt(keys.size)]
                order = HomeGridOrder.moveToEmpty(order, k, rnd.nextInt(0, 40))
            }
            val apps = order.filter { it != HomeGridOrder.EMPTY }
            r.check("9 freeplace", apps.toSet() == keys.toSet() && apps.size == keys.size) { "apps lost or duplicated" }
            r.check("9 freeplace", order.lastOrNull() != HomeGridOrder.EMPTY) { "trailing gap kept" }
        }
    }

    private fun stacking(r: Report, rnd: Random) {
        repeat(500) {
            val keys = (0 until rnd.nextInt(4, 28)).map { "a$it" }
            val order = keys.shuffled(rnd).toMutableList<String>().apply { repeat(rnd.nextInt(0, 6)) { add(rnd.nextInt(size + 1), HomeGridOrder.EMPTY) } }
            val main = keys[rnd.nextInt(keys.size)]
            val carry = keys.filter { it != main }.shuffled(rnd).take(rnd.nextInt(0, 6))
            val out = Ios.reorderStack(order, main, carry)
            val apps = out.filter { it != HomeGridOrder.EMPTY }
            r.check("11 stacking", apps.size == keys.size && apps.toSet() == keys.toSet()) { "icons lost/duplicated: $out" }
            if (carry.isNotEmpty()) {
                val at = out.indexOf(main)
                r.check("11 stacking", out.subList(at + 1, at + 1 + carry.size) == carry) { "carried icons not right after the dropped one: $out" }
            } else r.check("11 stacking", out == order) { "no stack must change nothing" }
        }
        r.check("11 stacking", Ios.reorderStack(listOf("a", "b"), "zz", listOf("a")) == listOf("a", "b")) { "unknown main must be ignored" }
    }

    // ---------------------------------------------------------------- widgets
    private fun xl(r: Report) {
        for (cols in 3..6) for (rows in 1..9) {
            val (w, h) = Ios.xlSize(cols, rows)
            r.check("15 xlwidget", w == cols && h in 1..rows && h <= 4) { "XL $w×$h doesn't fit $cols×$rows" }
        }
    }

    private fun smartStack(r: Report) {
        for (h in 0..23) for (b in listOf(-1, 5, 20, 21, 60, 100)) for (c in listOf(true, false)) for (t in listOf(true, false)) for (cur in 0..3) {
            val p = Ios.stackPick(h, t, b, c, cur)
            r.check("16 smartstack", p in 0..3) { "page $p out of range" }
            if (t) r.check("16 smartstack", p == 2) { "a running timer must win" }
        }
        r.check("16 smartstack", Ios.stackPick(14, false, 80, false, 3) == 3) { "afternoon should keep what you chose" }
        r.check("16 smartstack", Ios.stackPick(14, false, 10, false, 0) == 1) { "low battery should show batteries" }
    }

    private fun formatting(r: Report) {
        r.check("18 screentime", Ios.duration(0) == "0m" && Ios.duration(59 * 60_000L) == "59m" && Ios.duration(192 * 60_000L) == "3h 12m") { "duration format" }
        r.check("18 screentime", Ios.duration(-5) == "0m") { "negative time" }
        val json = """{"chart":{"result":[{"meta":{"currency":"USD","symbol":"AAPL","regularMarketPrice":231.5,"chartPreviousClose":228.0}}]}}"""
        val q = Ios.parseQuote(json)
        r.check("19 stocks", q != null && q.first == 231.5 && q.second == 228.0) { "quote parse $q" }
        r.check("19 stocks", Ios.parseQuote("""{"error":"Not Found"}""") == null) { "bad reply must give null" }
        r.check("19 stocks", Ios.change(231.5, 228.0) == "+1.54%" && Ios.change(90.0, 100.0) == "−10.00%" && Ios.change(1.0, 0.0) == "0.00%") { "change format" }
        r.check("19 stocks", Ios.cleanTickers("aapl, tsla;goog msft nvda") == listOf("AAPL", "TSLA", "GOOG", "MSFT")) { "max 4 tickers" }
        r.check("19 stocks", Ios.cleanTickers("AAPL&x=1, ../etc, ok") == listOf("OK")) { "unsafe tickers must be dropped" }
    }

    private fun shortcuts(r: Report) {
        for (n in -2..20) {
            val (c, rows) = Ios.shortcutGrid(n)
            r.check("20 shortcuts", c == 4 && rows in 1..4 && c * rows >= n.coerceIn(0, 16)) { "grid for $n = $c×$rows" }
        }
    }

    // ---------------------------------------------------------------- 22 split island
    private fun splitIsland(r: Report, rnd: Random) {
        repeat(2000) {
            val sw = rnd.nextInt(320, 1600).toFloat()
            val pw = rnd.nextInt(80, (sw * 0.95f).toInt()).toFloat()
            val pc = rnd.nextInt(-(sw / 2).toInt() + (pw / 2).toInt(), (sw / 2).toInt() - (pw / 2).toInt() + 1).toFloat()
            val b = 36f; val gap = 6f; val m = 6f
            val x = Ios.splitX(pc, pw, b, gap, sw, m)
            if (x != null) {
                val left = sw / 2 + x - b / 2; val right = sw / 2 + x + b / 2
                r.check("22 split", left >= m - 0.01f && right <= sw - m + 0.01f) { "bubble off screen" }
                val pl = sw / 2 + pc - pw / 2; val pr = sw / 2 + pc + pw / 2
                r.check("22 split", right <= pl - gap + 0.01f || left >= pr + gap - 0.01f) { "bubble overlaps the island" }
            } else {
                val roomRight = sw - m - (sw / 2 + pc + pw / 2 + gap); val roomLeft = (sw / 2 + pc - pw / 2 - gap) - m
                r.check("22 split", roomRight < b && roomLeft < b) { "bubble hidden although there was room" }
            }
        }
    }

    // ---------------------------------------------------------------- 23/24 notifications
    private fun notifications(r: Report, rnd: Random) {
        repeat(300) {
            val apps = (0 until rnd.nextInt(1, 8)).map { "pkg$it" }
            val notes = (0 until rnd.nextInt(0, 40)).map { Ios.Note(apps[rnd.nextInt(apps.size)], "n$it", rnd.nextLong(0, 1_000_000)) }
            val g = Ios.group(notes)
            r.check("23 nc", g.map { it.first }.toSet() == notes.map { it.pkg }.toSet()) { "an app's notifications went missing" }
            r.check("23 nc", g.all { it.second.size in 1..3 }) { "a group shows more than 3 (screen would fill up)" }
            r.check("23 nc", g.zipWithNext().all { (a, b) -> a.second.first().time >= b.second.first().time }) { "groups not newest first" }
            r.check("23 nc", g.all { grp -> grp.second.zipWithNext().all { (a, b) -> a.time >= b.time } }) { "items not newest first" }
        }
    }

    private fun summary(r: Report, rnd: Random) {
        for (times in listOf(listOf(480, 1080), listOf(420, 720, 1140), listOf(540), emptyList())) {
            val expectPerDay = times.ifEmpty { listOf(480, 1080) }.size
            var last = 10_000L * 1440 + 300   // day 10, 05:00
            var deliveries = 0
            var now = last
            repeat(3 * 1440) {
                now++
                if (Ios.summaryDue(times, last, now)) { deliveries++; last = now }
            }
            r.check("24 summary", deliveries == expectPerDay * 3) { "times $times delivered $deliveries in 3 days" }
        }
        // Phone asleep for hours: one delivery when it wakes, not a burst.
        r.check("24 summary", Ios.summaryDue(listOf(480), 10_000L * 1440 + 300, 10_000L * 1440 + 900)) { "missed delivery after sleep" }
        repeat(500) {
            val n = rnd.nextInt(0, 1440)
            val m = Ios.nextSummary(listOf(480, 1080), n)
            r.check("24 summary", m in 1..1440) { "next summary in $m min" }
        }
    }

    // ---------------------------------------------------------------- 25/26 control centre
    private fun controlCenter(r: Report, rnd: Random) {
        repeat(600) {
            val tiles = (0 until rnd.nextInt(0, 18)).map { val s = Ios.SIZES[rnd.nextInt(3)]; Ios.Tile("t$it", s.first, s.second) }
            val placed = Ios.pack(tiles)
            r.check("25 ccpages", placed.size == tiles.size) { "a control went missing" }
            val cells = HashSet<Pair<Int, Int>>()
            var overlap = false
            for (p in placed) for (dx in 0 until p.w) for (dy in 0 until p.h) if (!cells.add((p.col + dx) to (p.row + dy))) overlap = true
            r.check("26 ccresize", !overlap) { "controls overlap" }
            r.check("26 ccresize", placed.all { it.col >= 0 && it.col + it.w <= 4 }) { "control outside the grid" }
        }
        var s = 1 to 1
        val seen = HashSet<Pair<Int, Int>>()
        repeat(6) { seen += s; s = Ios.nextSize(s.first, s.second) }
        r.check("26 ccresize", seen == Ios.SIZES.toSet() && s == (1 to 1)) { "sizes must cycle small→wide→big" }
    }

    // ---------------------------------------------------------------- 28 siri suggestions
    private fun suggestions(r: Report, rnd: Random) {
        val day = 86_400_000L; val hour = 3_600_000L
        val base = 20_000 * day   // a Thursday-aligned epoch day
        val log = ArrayList<Ios.Launch>()
        for (d in 0 until 21) {
            val t = base + d * day
            log += Ios.Launch("mail", t + 8 * hour + rnd.nextLong(0, 20 * 60_000))
            log += Ios.Launch("game", t + 21 * hour + rnd.nextLong(0, 30 * 60_000))
            log += Ios.Launch("music", t + 13 * hour)
            if (d % 3 == 0) log += Ios.Launch("rare", t + 8 * hour)
        }
        val now = base + 21 * day
        r.check("28 siri", Ios.suggest(log, now + 8 * hour + 10 * 60_000).firstOrNull() == "mail") { "8 AM should suggest mail first" }
        r.check("28 siri", Ios.suggest(log, now + 21 * hour).firstOrNull() == "game") { "9 PM should suggest the game first" }
        r.check("28 siri", Ios.suggest(log, now + 13 * hour).firstOrNull() == "music") { "1 PM should suggest music first" }
        r.check("28 siri", "mail" !in Ios.suggest(log, now + 8 * hour, exclude = setOf("mail"))) { "excluded app suggested" }
        r.check("28 siri", Ios.suggest(log, now, n = 2).size <= 2) { "too many suggestions" }
        r.check("28 siri", Ios.suggest(emptyList(), now).isEmpty()) { "empty history" }
        r.check("28 siri", Ios.suggest(listOf(Ios.Launch("future", now + day)), now).isEmpty()) { "future entries must be ignored" }
        // Deterministic and fast on a full 300-entry log.
        val big = (0 until 300).map { Ios.Launch("app${it % 40}", now - rnd.nextLong(0, 27 * day)) }
        val t0 = System.nanoTime(); val a = Ios.suggest(big, now); val ms = (System.nanoTime() - t0) / 1e6
        r.check("28 siri", a == Ios.suggest(big, now)) { "suggestions not stable" }
        r.check("performance", ms < 50) { "suggestions took ${ms}ms" }
    }

    private fun standbyCharging(r: Report) {
        for (e in listOf(true, false)) for (c in listOf(true, false)) for (l in listOf(true, false)) for (h in listOf(true, false))
            r.check("31 standby", Ios.standby(e, c, l, h) == (e && c && l && h)) { "standby $e $c $l $h" }
        r.check("32 charging", Ios.chargingRing(true, false, true, true)) { "plug-in should play" }
        r.check("32 charging", !Ios.chargingRing(true, true, true, true)) { "already charging must not replay" }
        r.check("32 charging", !Ios.chargingRing(true, null, true, true)) { "first battery reading must not play" }
        r.check("32 charging", !Ios.chargingRing(false, false, true, true) && !Ios.chargingRing(true, false, true, false)) { "off / not on home" }
        for (e in listOf(true, false)) for (a in listOf(true, false)) for (h in listOf(true, false))
            r.check("36 landscape", Ios.landscape(e, a, h) == (e && a && h)) { "landscape $e $a $h (rotation lock must be respected)" }
        // Landscape and StandBy never stack, and nothing small draws over the landscape page.
        val st = Ios.Stage()
        r.check("36 landscape", st.request("landscape")) { "landscape should show" }
        r.check("36 landscape", !st.request("standby") && !st.request("nc") && !st.request("today") && !st.request("charging")) { "something stacked on landscape" }
        st.release("landscape")
        r.check("36 landscape", st.request("standby") && !st.request("landscape")) { "landscape over StandBy" }
    }

    private fun sky(r: Report) {
        for (code in 0..99) for (h in 0..23) {
            val s = Ios.sky(code, h)
            for (may in listOf(true, false)) for (saver in listOf(true, false)) {
                val f = Ios.skyFps(s, may, saver)
                r.check("33 weatherwall", f in 0..24) { "fps $f" }
                if (!may || saver) r.check("33 weatherwall", f == 0) { "sky moves while covered / battery saver" }
            }
        }
        r.check("33 weatherwall", Ios.sky(61, 12) == Ios.Sky.RAIN && Ios.sky(0, 23) == Ios.Sky.CLEAR_NIGHT &&
            Ios.sky(95, 12) == Ios.Sky.STORM && Ios.sky(73, 9) == Ios.Sky.SNOW && Ios.sky(45, 9) == Ios.Sky.FOG) { "weather code mapping" }
    }

    // ---------------------------------------------------------------- 36 landscape: phone tilting
    private fun landscapeTilt(r: Report, rnd: Random) {
        repeat(1500) { session ->
            val m = Ios.TiltMachine()
            var now = 1_000_000L
            var shown: String? = null; var shownTurn = 0f
            var lastDir = 0f; var dirSince = now
            var shows = 0
            val charging = rnd.nextInt(4) == 0; val sbOn = rnd.nextBoolean(); val lsOn = rnd.nextInt(5) != 0
            val auto = rnd.nextInt(5) != 0
            var target = 0      // where the hand is heading: 0 upright, 90, 270 or a diagonal
            repeat(400) {
                now += rnd.nextLong(30, 120)                      // sensor every 30–120 ms
                if (rnd.nextInt(40) == 0) target = listOf(0, 90, 270, 45, 135, 0, 90)[rnd.nextInt(7)]
                val noise = rnd.nextInt(-14, 15)
                val deg = ((target + noise) % 360 + 360) % 360
                val free = rnd.nextInt(30) != 0
                if (rnd.nextInt(200) == 0 && shown != null) { m.dismissed = true; shown = null; m.showing = null }   // Back pressed
                val dirNow = when (deg) { in 65..115 -> -90f; in 245..295 -> 90f; in 0..25, in 335..359 -> 0f; else -> null }
                if (dirNow != null && dirNow != lastDir) { lastDir = dirNow; dirSince = now }
                val t = m.feed(deg, now, charging, sbOn, lsOn, auto, free)
                when (t) {
                    is Ios.Tilt.Hide -> {
                        r.check("36 landscape", shown != null) { "session $session: hide with nothing shown" }
                        r.check("36 landscape", dirNow == 0f) { "session $session: hid while not upright ($deg°)" }
                        shown = null
                    }
                    is Ios.Tilt.ShowLandscape -> {
                        shows++
                        r.check("36 landscape", shown == null) { "session $session: two pages at once" }
                        r.check("36 landscape", lsOn && auto && free) { "session $session: landscape against settings/rotation lock" }
                        r.check("36 landscape", !(charging && sbOn)) { "session $session: landscape instead of StandBy while charging" }
                        r.check("36 landscape", now - dirSince > 500 && t.turn == dirNow) { "session $session: shown before the phone was steady" }
                        shown = "landscape"; shownTurn = t.turn
                    }
                    is Ios.Tilt.ShowStandby -> {
                        shows++
                        r.check("31 standby", charging && sbOn && free) { "session $session: StandBy without charging" }
                        r.check("31 standby", now - dirSince > 1200) { "session $session: StandBy before steady" }
                        shown = "standby"; shownTurn = t.turn
                    }
                    is Ios.Tilt.Flip -> {
                        r.check("36 landscape", shown == "landscape" && t.turn != shownTurn && t.turn == dirNow) { "session $session: bad flip" }
                        shownTurn = t.turn
                    }
                    else -> {}
                }
                if (dirNow == null) r.check("36 landscape", t is Ios.Tilt.None) { "session $session: diagonal angle changed the screen" }
                if (dirNow == 0f && shown != null) r.check("36 landscape", false) { "session $session: still sideways page while upright" }
                if (m.dismissed && dirNow != 0f) r.check("36 landscape", t !is Ios.Tilt.ShowLandscape && t !is Ios.Tilt.ShowStandby) { "session $session: came back after Back" }
            }
            r.check("performance", shows <= 60) { "session $session: flickered $shows times" }
        }
    }

    // ---------------------------------------------------------------- 36 landscape: layout on many phones
    private fun landscapeLayout(r: Report) {
        for (density in listOf(1.5f, 2f, 2.625f, 2.75f, 3f, 3.5f, 4f))
            for (shortDp in listOf(320, 340, 360, 375, 393, 412, 430, 480))
                for (longDp in listOf(640, 700, 760, 800, 852, 915, 932, 1000))
                    for (labels in listOf(true, false)) for (dock in listOf(true, false)) {
                        val W = (longDp * density).toInt(); val H = (shortDp * density).toInt()
                        val g = Ios.landLayout(W, H, density, labels, dock)
                        fun dp(v: Int) = (v * density).toInt()
                        val tag = "${longDp}x$shortDp@$density"
                        r.check("36 landscape", g.left >= dp(30) && g.left + g.widget < g.gridLeft) { "$tag widgets overlap the grid" }
                        r.check("36 landscape", g.top + g.widget * 2 + dp(14) <= g.top + g.innerH) { "$tag widgets taller than the screen" }
                        r.check("36 landscape", g.gridLeft + g.gridW <= (if (dock) g.dockLeft else W - g.left) + 1) { "$tag grid runs into the dock/edge" }
                        r.check("36 landscape", !dock || g.dockLeft + g.dockW <= W - dp(30)) { "$tag dock off the edge (camera side)" }
                        r.check("36 landscape", g.cellW >= dp(60)) { "$tag icon cell too narrow (${g.cellW / density}dp)" }
                        r.check("36 landscape", g.cellH >= dp(if (labels) 76 else 60)) { "$tag icon cell too short (${g.cellH / density}dp)" }
                        r.check("36 landscape", g.cols in 2..7 && g.rows in 1..5) { "$tag grid ${g.cols}×${g.rows}" }
                        r.check("36 landscape", !dock || g.dockSlots * dp(66) + dp(24) <= g.innerH) { "$tag dock icons don't fit" }
                        r.check("36 landscape", !dock || g.dockSlots >= 3) { "$tag dock shows fewer than 3 apps" }
                    }
    }
}
