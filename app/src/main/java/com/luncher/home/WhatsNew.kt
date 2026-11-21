package com.luncher.home

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * Every update's changes, shown once after the launcher updates ("What's New"), and any time
 * from Settings › General. Add the new version's notes at the top of [notes] with each release.
 */
object WhatsNew {
    /** Launcher number → what changed in it. */
    val notes: Map<Int, List<String>> = linkedMapOf(
        164 to listOf("⬆ New first window 00 UPDATES: everything since your last briefing, most important first — Priority (mentions, possible tasks/dates, battery), Messages & activity, Other — tap one to open that chat; ▶ Brief me / ✓ Mark as seen", "⬆ Gilbert talks like a composed AI assistant: calls you by name (and “sir” now and then), short and precise, with a little dry humour", "🔧 He only asks when you open him (“Would you like the update, John?”) and stays quiet until you tap him or the HUD — no more talking on every window or message", "⬆ About you (04→05 NOTES): your name and profile, saved only on this phone; Gilbert and his brain use it"),
        163 to listOf("⬆ Mute button: a 🔊 appears next to ✕ while Gilbert speaks — tap to mute him (🔇 stays so you can turn him back on)", "⬆ Tap his globe in the middle of the HUD: hushes him if he's talking, otherwise he tells you about the window you're on"),
        162 to listOf("⬆ Gilbert's greeting is short and never the same twice: he only tells you what's NEW since he last briefed you, picks the one or two things that matter most (mentions, a possible task, who messaged), and says “Welcome back” if you were just here"),
        161 to listOf("⬆ Pick Gilbert's voice: console › 06 SYSTEM › VOICE lists your phone's English voices (British first) — tap one to hear it", "⬆ AI echo: a soft room echo on his voice so he sounds like he's speaking through speakers (on/off in the same place)", "🔧 New-message announcements now wait a random 40 s – 2½ min between each, and group what came in (“3 new messages, from Jia and Contra”)"),
        160 to listOf("⬆ Gilbert speaks in his console: “Good afternoon, sir.” then a quick briefing (messages, who's most active, mentions, things that look like tasks, battery) and “What's the task for today?”", "⬆ He talks you through each window you open, reads out new messages as they arrive and reads your digest; his globe pulses while he talks and his words show as subtitles", "⬆ Settings › Gilbert: Gilbert's Voice on/off and Call Me (Sir, Ma'am, Boss or your name)"),
        159 to listOf("🔧 Gilbert's console closes the moment you swipe up or press Home / Recents, and any leftover is cleared from recent apps when you're back home"),
        158 to listOf("⬆ Files can now show photos from Messenger: allow saved photos and turn on Messenger's Save photos — each one lands on its “Sent a photo” and gets scanned", "⬆ Share → Gilbert: share any photo from any app and he keeps and scans it", "⬆ Photos that haven't arrived yet show the sender's picture with a 📷 tag instead of an empty camera"),
        157 to listOf("🔧 Gilbert's console no longer stays behind in the recent-apps screen after you leave it"),
        156 to listOf("🔧 Tapping Gilbert inside other apps now opens his HUD console too (it used to open the Notifications list)"),
        155 to listOf("🔧 Gilbert's red notification count is no longer cut off — it now sits fully inside his round face"),
        154 to listOf("⬆ One place for Gilbert: tapping him opens his HUD console straight away (the separate card is gone)", "⬆ New window 00 NOW: status, battery & calm meters, notifications he's holding (open / inbox / clear), noisiest apps with Normal › Priority › Quiet tags, phone check, and quick actions (Tools, Inbox, Scan image)"),
        153 to listOf("⬆ Gilbert's card now matches his HUD console: dark panel, glowing corner brackets, scan line and HUD labels", "⬆ The HUD (console, card, brain room and his globe face) follows your wallpaper / theme colour (cyan on Mono)", "🔧 New chats come in as a small notification only — tap it to open the chat window (it no longer pops open and covers the screen)", "🔧 The console title no longer hides behind the island"),
        152 to listOf("⬆ Gilbert is back (no more Pocher) with a new face: a cyan particle globe with a glowing core", "⬆ His console is now a full-screen HUD: rotating rings, side brackets and dot grids that boot up when you open it, then his globe forms in the middle", "⬆ Windows open like a HUD panel: the frame draws itself in, a scan line sweeps down and the rows slide in one by one", "⬆ Live updates: when a message or file comes in, a pulse ring flies out, a ticker types what arrived and the row glows cyan", "⬆ Summary has animated ring gauges; Names shows the people who message you most orbiting a particle planet"),
        151 to listOf("⬆ Gilbert is now Pocher — he doesn't chat anymore, he gives you data", "⬆ Pocher's console has 6 windows, one per program: Summary (today's counts, top chats, by app, a written digest), Chats (every conversation — tap to read it), Names (who messages you, ranked), Notes (your notes + mentions, dates and tasks he flagged), Files (photos & attachments from chats, scanned for text and what's in them — or scan any image yourself) and System (his brain & storage)", "⬇ Removed: chatting, the 🎤 voice and his talk bubbles (the talk bubbles can be turned back on in Settings)"),
        150 to listOf("🔧 Gilbert's brain now runs in its own space: if it needs more memory than the phone has free, only the brain closes — the launcher stays open and Gilbert tells you what happened", "⬆ If the launcher ever closes because of a bug, Gilbert tells you why the next time you open his chat"),
        149 to listOf("🔧 Gilbert's brain no longer switches off by mistake after an update — it only turns off if it really crashes twice, and the brain room has a “Try Again” button", "🔧 Gilbert understands “how do you feel?” (he used to mistake “you” for “yo” and just say hey)", "🔧 Cleaner names in his briefing: no more “jxhn.mrqs:” in front, and your own replies aren't counted as messages"),
        148 to listOf("⬇ Much smaller download: the app now only carries the parts for your phone's chip (about 4× smaller), so updates download fast"),
        147 to listOf("🧠 Brain room: tap 🧠 in Gilbert's chat — the card flips into his brain: his face in a glowing ring, what brain he's running, his memory and archive",
            "Give him a brain right there: he calibrates with an animation (waking up neurons → reading memory → indexing archive → loading → tuning voice) and wakes up happy",
            "Gilbert's Archive: every notification and chat is saved in a folder (one file per day, 60 days), and he searches it to answer about anything that came in",
            "All chats open in the same tidy window (Home Screen, over apps, Sidebar, Settings, Quick Settings tile)"),
        146 to listOf("Talk to Gilbert with your voice: tap 🎤 in his chat, speak, and he answers — out loud too (Settings › Gilbert › Gilbert Speaks)",
            "Sound effects keep playing during Do Not Disturb (Settings › Sounds › Sounds in Do Not Disturb)"),
        145 to listOf("Do Not Disturb: messages from your chat apps still show while it's on (Settings › Gilbert › Always Show Messages)",
            "The clipboard strip now sits joined to the top of the chat card, like one piece",
            "What's New shows every version: newest first, with ⬆ new/upgraded, 🔧 fixed and ⬇ removed/changed marks"),
        144 to listOf("Everything from 142–143 (those builds didn't finish): learning, search, chat anywhere, messages on silent; his replies type out word by word"),
        143 to listOf("Chat with Gilbert from anywhere: tap Gilbert over any app, use the Gilbert button at the top of the Sidebar, or add the “Gilbert” Quick Settings tile",
            "Messages from every chat app now always pop up — even when the phone is on silent, vibrate or Calm Mode (Settings › Gilbert › Always Show Messages)"),
        142 to listOf("Gilbert learns about you: say “remember that…”, or just talk (“I'm a BSIT student”, “I love NF”) — he remembers and uses it",
            "“search exam” / “find Jia” looks through everything he's seen; “search the web for …” opens a web search; “forget …” removes a fact",
            "Faster replies: his words now appear as he thinks, and his brain starts answering sooner"),
        141 to listOf("You can now use heavy brains (like the 1.99 GB “f32” file) — Gilbert warns you first and stays crash-safe"),
        140 to listOf("Gilbert's brain is crash-safe: too-heavy files (like the 2 GB “f32” ones) are refused, free memory and storage are checked first, your old brain is kept until the new one is fully copied, and a brain that ever crashes is switched off automatically"),
        139 to listOf("Gilbert talks like a professional personal assistant who's also your best friend — clear, helpful and warm",
            "Say “report” (or “brief”, “summary”) for a daily briefing: messages, mentions, what's waiting, your song on repeat and your phone",
            "Downloads and system notifications no longer count as messages; fixed stray “\\n” in his replies"),
        138 to listOf("Gilbert answers fast: time, “what did I miss?”, music, apps, battery and “who am I?” are answered instantly",
            "His brain now gets a short, proper chat prompt — quicker and real replies instead of repeating facts; it starts loading as soon as you open the chat, with typing dots while he thinks"),
        137 to listOf("Talk to Gilbert: tap him, then 💬 — ask “what did I miss?”, “what am I listening to?”, “who am I?” and more",
            "Gilbert's memory: he learns your favourite songs and artists, the apps you open most and who messages you — all kept on your phone (Settings › Gilbert › Brain & Memory, with Forget Everything)",
            "Gilbert's brain (optional): add a small offline AI model and he chats about anything — privately, on the phone, no internet needed"),
        136 to listOf("Fixed page transitions: pages are never left half-turned or see-through after opening an app",
            "Opening and closing apps now matches your Page Swipe style (Settings › Animations › Opening Apps › Match Page Swipe)"),
        135 to listOf("Headphones card: smaller and minimal like iPhone's AirPods card, never stretches across the screen (even in games / landscape), and takes your wallpaper / theme colour"),
        134 to listOf("Gilbert follows your wallpaper: his particles, his ring and his card take your wallpaper / theme colour and change when you change the wallpaper (Settings › Gilbert › Colours › Wallpaper)"),
        133 to listOf("The waiting-app logos next to Gilbert now pop up like a reminder for 5 seconds when something new arrives, then tuck away (Gilbert's red badge and his card still have them)"),
        132 to listOf("The music player keeps your last song (art, title, artist) after you close the music app — tap ▶ to pick up where you left off"),
        131 to listOf("Gilbert's waiting-app logos sit neatly in a small black capsule next to him ([logo] 2), never overlapping each other or your apps"),
        130 to listOf("Beat Animation on/off right in the island player: tap the ♫ button next to the speaker (also in Settings › Dynamic Island)"),
        129 to listOf("When music is paused, the island goes back to Gilbert after a few seconds (press play and the player comes right back)"),
        128 to listOf("Smoother everywhere: the launcher asks the screen for its fastest refresh rate (90/120 Hz when your phone has it)",
            "Snappier animations by default, plus a new “Instant” speed (Settings › Animations › Speed)",
            "Gilbert's orb only animates while it's on screen, freeing the phone for everything else",
            "Animations keep the same speed on 60, 90 and 120 Hz screens"),
        127 to listOf("Tools button by the camera: now a full circle with a ripple when you tap it",
            "It opens the tools even while music is in the island (the music comes back after a few seconds)",
            "Hold the button and drag it to move it — or move it in Settings › Adjust the Features"),
        126 to listOf("Beat Animation: the island pops to the beat of your music and its bars dance to the real sound (album art bounces when it's open)",
            "Sleeker lyrics: each new line glides up and sharpens out of a soft blur, Apple Music–style",
            "Settings › Dynamic Island › Beat Animation (asks for the Microphone permission so it can hear the music — nothing is recorded)"),
        125 to listOf("Fixed: long lyrics pushed the open island's buttons out of view — lyrics now have a fixed space and the island always fits its buttons"),
        124 to listOf("The island over apps always stays fully on screen: drag it to an edge or let it grow for a long lyric and it glides back in so every word shows"),
        123 to listOf("Over other apps, hold the island and drag it anywhere so it doesn't cover an app's buttons — it remembers the spot (the Home Screen island keeps its own place)"),
        122 to listOf("Long lyric lines: the small island grows a little taller and shows the whole sentence on two lines"),
        121 to listOf("Lyrics in the Home Screen player always fit now — long lines shrink a little instead of being cut off at the bottom"),
        120 to listOf("The small island now fits the whole lyric line (it still cut the end off with “…”)"),
        119 to listOf("Fixed: lyrics and the time froze in the open island after a few seconds",
            "Fixed: the small island flickered while music played",
            "The small island now widens smoothly to show the whole lyric line — no more scrolling back and forth"),
        118 to listOf("Fixed: the island flickered every 2–3 seconds while music played (it rebuilt itself on every music update)",
            "Lyrics Timing slider (Settings › Home Screen & Widgets): choose how far ahead of the singer the lyrics show",
            "The island over other apps always takes taps now",
            "Small-island lyrics keep following the song smoothly"),
        117 to listOf("Lyrics no longer flicker in the island or the player — lines glide in smoothly",
            "Whole lyric lines: long lines glide sideways in the small island, and the player shows up to 3 lines",
            "Lyrics run a little ahead of the song so you can read what's coming next"),
        116 to listOf("Lyrics in the small island too: while music plays, the line being sung shows right next to the album art"),
        115 to listOf("Lyrics now land on time (shown a touch early, updated 5× a second)",
            "Spotify / ArchiveTune look: three lyric lines — the one being sung is bright, the ones around it are blurred — and the album art glows blurred behind the player",
            "The island player has the same lyrics and blurred art too (it grows to fit them when the song has lyrics)",
            "Settings › Widgets › Lyrics turns them on or off"),
        114 to listOf("31 Now Playing styles: Vinyl, Vinyl Right, Album, Album Right, Disc, Poster and Minimal — in Dark, Light, Glass, Album Color and Theme Color",
            "Every player style now shows ⏮ ⏯ ⏭ buttons all the time",
            "Live lyrics in the player: the line being sung, sliding up as the song plays (from the free LRCLIB library)"),
        113 to listOf("Adjust the Features (top of Settings): drag the Dynamic Island, Sidebar, Side Island and Game Ball on a live phone preview",
            "Page transitions: Android, Roll, Flip, Card, Tilt, Fade, Cube, Depth, Zoom",
            "Layouts: 4×5, 4×6, 4×7, 5×6, 5×7 or Auto",
            "Icons: rounded-corner slider (square to circle) and app name size",
            "Featured colour themes: Coastal, Alpine, Trendy, Sunset, Rose, Lavender, Gold, Mint",
            "Notification Style: Launcher, Original (Android's own) or Both",
            "Sidebar in 1 or 2 columns",
            "Music player styles: Classic, Vinyl and Vinyl Light — tap the vinyl player and its buttons slide in",
            "Fixed: versions 110–111 didn't build",
            "Fixed: the open island player was see-through over apps — it's now solid so the song and buttons are easy to read"),
        111 to listOf("Moving Gilbert to the top no longer pushes your apps down",
            "Dock: turn it on/off, pick Glass, Dark, Light or Clear, and change roundness and icon size",
            "App Library: turn it on/off, choose Folders or an A–Z list, and rename it (Settings › Home Screen & Widgets)"),
        110 to listOf("Fixed: the 4th column of apps could run off the right edge of the screen — the grid now measures the real screen width and icons never grow bigger than their space"),
        109 to listOf("Game Ball: while gaming, a little movable orb (like Gilbert) replaces the Sidebar handle — no more clashing with the phone's game panel",
            "Tap the ball: choose who may pop up during games (Everyone / ★ Priority / No one), see the messages you missed, and open your Sidebar apps in a small window",
            "Allowed pop-ups show as a small banner beside the ball, never over your game",
            "Better game detection: game engines, big game makers and the phone's own game helper are recognised automatically. Not a game? Turn it off from the ball"),
        108 to listOf("Simple Mode (top of Settings): one switch turns off Gilbert, the Dynamic Island, Sidebar, Side Island and pop-ups — your look, icons, widgets and theme stay. Switch it off to get everything back exactly as it was"),
        107 to listOf("Quiet in the app you're using: no pop-ups or chat windows from an app while it's open on screen — other apps still pop up (Settings › Gilbert)"),
        106 to listOf("Gilbert's card opens only once — tap Gilbert again, press Back or Home to close it"),
        105 to listOf("Gilbert's card is redesigned: brushed graphite tiles with status chips (Ready, Holding, Mentions)",
            "Striped meters for battery, calm, temperature, storage and memory that fill in as the card opens",
            "Held notifications as a collapsible checklist with Open Inbox and Clear buttons",
            "Tiles rise in one after another, with soft open and close sounds"),
        104 to listOf("Gilbert talks: check-ins like “Have you eaten?”, water and break reminders, battery/heat warnings, steps, weather and more",
            "Held apps sit beside Gilbert with their own counts — tap a logo to see what it sent",
            "Gilbert's card: Mentions of you and Gilbert's Notes of every message",
            "Sound effects everywhere: pops, chirps, a sparkly hello chime (Settings › Sounds)"),
        103 to listOf("Gilbert has feelings: he watches videos with you, bops to music, reads along when you search, smiles at chats, gets sleepy at night, sad on low battery, excited when charging and surprised by new messages",
            "His eyes follow your finger on the Home Screen"),
        102 to listOf("Meet Gilbert — the living orb in the island. Tap him for his card: notifications he held, today's noisiest apps (make them Priority or Quiet) and a phone check",
            "Calm Mode: only Priority apps pop up; Gilbert holds the rest with a red bubble — no more screen full of pop-ups",
            "Customise Gilbert: colours, face (°~° ^‿^ •‿• -o-), size, particles and speed (Settings › Gilbert)"),
        101 to listOf("The island comes alive: a spinning sphere of glowing particles slowly gathers into the face, blinks, looks around, breathes and sometimes melts back to “think”",
            "New messages and updates: the particles swirl around the app's logo, then become the face again"),
        100 to listOf("The island is a cute round face  °~°  when nothing is happening — it blinks, looks around and wiggles",
            "New messages, notifications and app updates pop their app's logo in the face"),
        99 to listOf("Dynamic Info in the island: weather (tap for a temperature gauge with low/high and feels-like), steps, calories, date and today's events",
            "New Weather and Steps widgets",
            "Settings › Dynamic Island › Island Info to choose what shows"),
        98 to listOf("Shorter tap line at the camera, with a Tap Line Length slider in Settings › Island Tools"),
        97 to listOf("Tap the status bar strip just left of the camera to open the tools — it works even on the status bar",
            "A faint ring and line at the camera show where to tap (Settings › Island Tools › Show Tap Spot)"),
        96 to listOf("Chat windows look more like Apple: iMessage-style reply bar (send arrow appears when you type), line icons instead of emoji, solid background and an iPadOS-style resize corner"),
        95 to listOf("Clipboard strip: ✕ on every item to delete it, Clear to empty it, and it lines up with the chat window"),
        94 to listOf("Island tools tap area moved up and to the left, and you can fine-tune it in Settings › Island Tools"),
        93 to listOf("Copy & paste in chat windows: hold a message to copy it, 📋 pastes into the reply",
            "Clipboard strip above the chat window with everything you copied — tap to paste, hold to remove, drag to move"),
        92 to listOf("After “hello”, a short tour shows everything the launcher can do",
            "Links in chat windows are tappable and open right away",
            "Side island message text sits on its own bubble — no more mixing with the app underneath"),
        91 to listOf("Easier to open the island tools: bigger tap area around and below the camera"),
        90 to listOf("Gaming: the islands, chat windows and pop-ups hide while a game is on screen — the Sidebar stays",
            "Apps opened from the Sidebar during a game open in a small floating window"),
        89 to listOf("Tools are back in the Dynamic Island: hidden around the front camera — tap it to open the tools island",
            "Camera added to the island tools; the separate floating tools bar is gone"),
        88 to listOf("Tools on the side can look like the Sidebar: a panel with labelled tiles (Settings › Island Tools › On the Side)",
            "The tools can't dock on the Sidebar's side"),
        87 to listOf("Only one tools bar: the island no longer has its own pull-down controls"),
        86 to listOf("Tools bar never covers anything: it steps aside for the Sidebar and the open side island, and slides below the open Dynamic Island"),
        85 to listOf("What's New: every update's changes show once after updating (and any time in Settings › General)",
            "The “hello” screen plays once after every update"),
        84 to listOf("📷 Camera in the tools bar"),
        83 to listOf("Tools circle opening animations: Ripple, Blossom, Spin or Fade",
            "Push any part of the tools bar past a side and it becomes the half circle on that edge"),
        82 to listOf("Edge half circle is always complete — even while you drag it",
            "Tools spread evenly around a centred ✕, smoother opening and docking"),
        81 to listOf("Drag the tools bar to a side: it blossoms into a half circle with ✕ in the middle"),
        80 to listOf("In the launcher's Settings the tools bar turns into a ✕ that closes Settings"),
        79 to listOf("Tools bar always on screen — Home Screen and over every app",
            "Arrow to fold it into a small tab and open it again"),
        78 to listOf("Move the tools bar anywhere (hold, then drag)",
            "Tools bar size, colour, opacity, shape and angle in Settings"),
        77 to listOf("Classic Settings design is back; ⚙ in the tools opens the launcher's Settings"),
        74 to listOf("Tools when nothing is playing: Flashlight, Brightness, Silent, Game Mode, Lock Screen, Settings"),
        73 to listOf("Now Playing widget: keep the last song, oblong shape (optional)"),
        72 to listOf("Smoother Dynamic Island animations", "Move the open Sidebar (hold, then drag)"),
        71 to listOf("Smoother Sidebar and side island opening and closing"),
        70 to listOf("Sidebar is only as tall as its apps", "All AI features removed"),
        68 to listOf("Smooth switching between apps in Notifications history"),
        67 to listOf("Notifications history: every message kept, latest 3 per chat, blue dots for new, filters per app, Clear"),
        66 to listOf("Connected headphones (with battery) and phone temperature in the side island"),
        65 to listOf("Island, Sidebar, side island and chat windows make room for each other"),
        64 to listOf("Resize chat and Message History windows with a corner grip"),
        63 to listOf("Choose how many recent apps show in the side island, and their size"),
        62 to listOf("Recent apps under the side island — scroll and tap to switch apps")
    )

    /** Notes for every version after [from] up to [to] (newest first). */
    /** ⬆ new/upgraded, 🔧 fixed, ⬇ removed/changed back — guessed from each note's wording. */
    fun mark(line: String): String {
        val l = line.lowercase()
        return when {
            l.startsWith("fix") || l.contains("fixed") || l.contains("no longer") && l.contains("crash") -> "🔧"
            l.contains("removed") || l.contains("reverted") || l.contains("back to") || l.contains("turned off") ||
                l.contains("no longer") || l.contains("hidden") || l.startsWith("off by default") -> "⬇"
            else -> "⬆"
        }
    }

    fun between(from: Int, to: Int): List<Pair<Int, List<String>>> =
        notes.entries.filter { it.key in (from + 1)..to }.sortedByDescending { it.key }.map { it.key to it.value }

    /** The card: "What's New in Apple Launcher N" with each version's changes, and Continue. */
    fun show(act: Activity, root: FrameLayout, from: Int, to: Int, onDone: (() -> Unit)? = null) {
        fun dp(v: Int) = (v * act.resources.displayMetrics.density).roundToInt()
        // Every version, newest first (what's new in this update is highlighted at the top).
        val fresh = between(from, to).ifEmpty { between(to - 1, to) }.map { it.first }.toSet()
        val items = notes.entries.sortedByDescending { it.key }.map { it.key to it.value }
            .ifEmpty { listOf(to to listOf("Improvements and fixes")) }
        val layer = FrameLayout(act).apply {
            setBackgroundColor(Color.argb(150, 0, 0, 0)); isClickable = true
        }
        val card = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(24), dp(22), dp(18))
            background = GradientDrawable().apply {
                cornerRadius = dp(30).toFloat(); setColor(0xF21C1C1E.toInt()); setStroke(maxOf(1, dp(1)), 0x33FFFFFF)
            }
            elevation = dp(16).toFloat()
        }
        card.addView(TextView(act).apply {
            text = "What's New"; textSize = 28f; setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
        })
        card.addView(TextView(act).apply {
            text = "Apple Launcher $to"; textSize = 14f; setTextColor(0x99FFFFFF.toInt()); setPadding(0, dp(2), 0, dp(14))
        })
        val list = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        val accent = ThemeEngine.accent.takeIf { ThemeEngine.colorful } ?: 0xFF0A84FF.toInt()
        // Legend: what each mark means.
        list.addView(TextView(act).apply {
            text = "⬆ new / upgraded    🔧 fixed    ⬇ removed / changed back"; textSize = 12f; setTextColor(0x99FFFFFF.toInt())
            setPadding(0, 0, 0, dp(4))
        })
        var shownEarlier = false
        for ((ver, lines) in items) {
            if (ver !in fresh && !shownEarlier) {
                shownEarlier = true
                list.addView(TextView(act).apply {
                    text = "EARLIER VERSIONS"; textSize = 12f; setTextColor(0x80FFFFFF.toInt()); letterSpacing = 0.1f
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD); setPadding(0, dp(18), 0, dp(2))
                })
            }
            list.addView(TextView(act).apply {
                text = if (ver in fresh) "VERSION $ver  ·  NEW" else "VERSION $ver"; textSize = 12f; setTextColor(if (ver in fresh) accent else 0xB3FFFFFF.toInt())
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD); letterSpacing = 0.08f
                setPadding(0, dp(10), 0, dp(6))
            })
            for (line in lines) list.addView(TextView(act).apply {
                text = "${mark(line)}  $line"; textSize = 15f; setTextColor(if (ver in fresh) 0xF2FFFFFF.toInt() else 0xB3FFFFFF.toInt())
                setLineSpacing(0f, 1.15f); setPadding(0, dp(3), 0, dp(3))
            })
        }
        val scroll = object : ScrollView(act) {
            override fun onMeasure(w: Int, h: Int) = super.onMeasure(w,
                View.MeasureSpec.makeMeasureSpec((act.resources.displayMetrics.heightPixels * 0.5f).toInt(), View.MeasureSpec.AT_MOST))
        }.apply { isVerticalScrollBarEnabled = false; addView(list) }
        card.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        lateinit var close: () -> Unit
        card.addView(TextView(act).apply {
            text = "Continue"; textSize = 17f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(0xFF0A84FF.toInt()) }
            setOnClickListener { close() }
        }, LinearLayout.LayoutParams(MATCH_PARENT, dp(52)).apply { topMargin = dp(18) })
        layer.addView(card, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.CENTER).apply {
            leftMargin = dp(18); rightMargin = dp(18)
        })
        close = {
            card.animate().alpha(0f).scaleX(0.92f).scaleY(0.92f).setDuration(200).start()
            layer.animate().alpha(0f).setDuration(240).withEndAction { root.removeView(layer); onDone?.invoke() }.start()
        }
        root.addView(layer, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        layer.alpha = 0f; layer.animate().alpha(1f).setDuration(260).start()
        card.scaleX = 0.88f; card.scaleY = 0.88f; card.alpha = 0f; card.translationY = dp(30).toFloat()
        card.animate().alpha(1f).translationY(0f).setDuration(280).start()
        Motion.scaleTo(card, 1f, Motion.BOUNCY)
    }

    /** Everything the launcher can do — shown as a short tour right after "hello". */
    private val features = listOf(
        Triple("🏝️", "Dynamic Island", "Music, timers, calls and notifications live at the top. With nothing playing it hides around your camera — tap there for your tools: Flashlight, Brightness, Camera, Silent, Game Mode, Lock and Settings."),
        Triple("💬", "Side Island & Chats", "Messages pop out of the side of the screen. Reply right from the chat window, open links, download attachments, and see every message in Notifications."),
        Triple("🔊", "Smart Side Panel", "Swipe the side island for volume, your recent apps, connected headphones (with battery) and the phone's temperature."),
        Triple("📱", "Sidebar", "Your favourite apps on the edge of the screen — even over games. While gaming, apps open in a small window."),
        Triple("🎮", "Made for Gaming", "When a game is on screen the islands and pop-ups step away, so nothing covers your game."),
        Triple("🧩", "Widgets & Home Screen", "Photos, calendar, clocks, Now Playing, iPod and more — plus folders, an iPhone-style dock and a widget gallery with previews."),
        Triple("🎨", "Make It Yours", "Colours follow your wallpaper. Change the island, side island, sidebar and tools: size, colour, shape and animations in Settings."),
        Triple("💾", "Backup & Transfer", "Export your whole setup and bring it back on any phone — layout, folders, widgets and settings.")
    )

    /** The feature tour: swipe-free pages with Next / Skip, then [onDone]. */
    fun showTour(act: Activity, root: FrameLayout, onDone: (() -> Unit)? = null) {
        fun dp(v: Int) = (v * act.resources.displayMetrics.density).roundToInt()
        var page = 0
        val layer = FrameLayout(act).apply { setBackgroundColor(Color.argb(170, 0, 0, 0)); isClickable = true }
        val card = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(28), dp(24), dp(18))
            background = GradientDrawable().apply {
                cornerRadius = dp(32).toFloat(); setColor(0xF21C1C1E.toInt()); setStroke(maxOf(1, dp(1)), 0x33FFFFFF)
            }
            elevation = dp(16).toFloat()
        }
        val head = TextView(act).apply {
            text = "What you can do"; textSize = 13f; setTextColor(0x99FFFFFF.toInt()); letterSpacing = 0.08f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        val emoji = TextView(act).apply { textSize = 54f; gravity = Gravity.CENTER }
        val title = TextView(act).apply {
            textSize = 24f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; typeface = Typeface.create("sans-serif", Typeface.BOLD)
        }
        val body = TextView(act).apply {
            textSize = 15.5f; setTextColor(0xD9FFFFFF.toInt()); gravity = Gravity.CENTER; setLineSpacing(0f, 1.2f)
        }
        val dots = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val next = TextView(act).apply {
            textSize = 17f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(0xFF0A84FF.toInt()) }
        }
        val skip = TextView(act).apply {
            text = "Skip"; textSize = 15f; setTextColor(0x99FFFFFF.toInt()); gravity = Gravity.CENTER; setPadding(0, dp(12), 0, dp(2))
        }
        card.addView(head)
        card.addView(emoji, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(14) })
        card.addView(title, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) })
        card.addView(body, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(10) })
        card.addView(dots, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(20) })
        card.addView(next, LinearLayout.LayoutParams(MATCH_PARENT, dp(52)).apply { topMargin = dp(18) })
        card.addView(skip, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        fun bind(animate: Boolean) {
            val (e, t, b) = features[page]
            val apply = {
                emoji.text = e; title.text = t; body.text = b
                next.text = if (page == features.lastIndex) "Get Started" else "Next"
                dots.removeAllViews()
                for (i in features.indices) dots.addView(View(act).apply {
                    background = GradientDrawable().apply { cornerRadius = dp(4).toFloat(); setColor(if (i == page) Color.WHITE else 0x44FFFFFF) }
                }, LinearLayout.LayoutParams(if (i == page) dp(18) else dp(7), dp(7)).apply { marginStart = dp(3); marginEnd = dp(3) })
            }
            if (!animate) { apply(); return }
            for (v in listOf(emoji, title, body)) v.animate().alpha(0f).translationX(-dp(24).toFloat()).setDuration(130).start()
            emoji.postDelayed({
                apply()
                for ((i, v) in listOf(emoji, title, body).withIndex()) {
                    v.translationX = dp(28).toFloat()
                    v.animate().alpha(1f).translationX(0f).setStartDelay(i * 40L).setDuration(260).start()
                }
                emoji.scaleX = 0.6f; emoji.scaleY = 0.6f; Motion.scaleTo(emoji, 1f, Motion.BOUNCY)
            }, 140)
        }
        lateinit var close: () -> Unit
        next.setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
            if (page < features.lastIndex) { page++; bind(true); SoundFX.play(act, "tick") } else { SoundFX.play(act, "success"); close() }
        }
        skip.setOnClickListener { close() }
        layer.addView(card, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.CENTER).apply { leftMargin = dp(20); rightMargin = dp(20) })
        close = {
            card.animate().alpha(0f).scaleX(0.92f).scaleY(0.92f).setDuration(200).start()
            layer.animate().alpha(0f).setDuration(240).withEndAction { root.removeView(layer); onDone?.invoke() }.start()
        }
        bind(false)
        root.addView(layer, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        layer.alpha = 0f; layer.animate().alpha(1f).setDuration(260).start()
        card.scaleX = 0.88f; card.scaleY = 0.88f; card.alpha = 0f; card.translationY = dp(30).toFloat()
        card.animate().alpha(1f).translationY(0f).setDuration(280).start()
        Motion.scaleTo(card, 1f, Motion.BOUNCY)
    }

    /** This build's launcher number (e.g. 85). */
    fun current(act: Activity): Int = runCatching {
        act.packageManager.getPackageInfo(act.packageName, 0).versionName?.toInt()
    }.getOrNull() ?: 0
}
