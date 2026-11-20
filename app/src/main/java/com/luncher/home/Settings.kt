package com.luncher.home

import android.content.Context
import android.content.SharedPreferences

/** All user-tweakable launcher settings, stored in SharedPreferences. */
class Settings(ctx: Context) {
    private val p: SharedPreferences = ctx.getSharedPreferences("luncher", Context.MODE_PRIVATE)

    /** Bumped on every change so the home screen knows to refresh. */
    val version: Int get() = p.getInt("s_version", 0)

    // ---- Appearance
    /** clear | tinted | dark | light | color | mono | frost */
    var iconStyle: String
        get() = p.getString("s_icon_style", "clear") ?: "clear"
        set(v) = put("s_icon_style", v)
    var tint: Int
        get() = p.getInt("s_tint", 0xFFFFFFFF.toInt())
        set(v) = put("s_tint", v)
    /** true = see-through glass panels, false = solid panels */
    var glass: Boolean
        get() = p.getBoolean("s_glass", true)
        set(v) = put("s_glass", v)
    /** Tint widgets/dock with the accent color too. */
    var tintPanels: Boolean
        get() = p.getBoolean("s_tint_panels", false)
        set(v) = put("s_tint_panels", v)
    /** 0..100 darkening over the wallpaper */
    var dim: Int
        get() = p.getInt("s_dim", 30)
        set(v) = put("s_dim", v)

    // ---- Home screen
    var iconSize: Int
        get() = p.getInt("s_icon_size", 60)
        set(v) = put("s_icon_size", v)
    var showLabels: Boolean
        get() = p.getBoolean("s_labels", true)
        set(v) = put("s_labels", v)
    var columns: Int
        get() = p.getInt("s_columns", 4)
        set(v) = put("s_columns", v)
    var badges: Boolean
        get() = p.getBoolean("s_badges", true)
        set(v) = put("s_badges", v)

    // ---- Animations
    var animations: Boolean
        get() = p.getBoolean("s_anim", true)
        set(v) = put("s_anim", v)
    /** slow | normal | fast */
    var animSpeed: String
        get() = p.getString("s_anim_speed", "normal") ?: "normal"
        set(v) = put("s_anim_speed", v)
    /** zoom | fade | slide | none */
    var launchAnim: String
        get() = p.getString("s_launch_anim", "zoom") ?: "zoom"
        set(v) = put("s_launch_anim", v)
    /** depth | flat | cube | zoom | roll | flip | card | tilt | stack | fade */
    var pageEffect: String
        get() = p.getString("s_page_effect", "depth") ?: "depth"
        set(v) = put("s_page_effect", v)
    var blur: Boolean
        get() = p.getBoolean("s_blur", true)
        set(v) = put("s_blur", v)
    var haptics: Boolean
        get() = p.getBoolean("s_haptics", true)
        set(v) = put("s_haptics", v)

    val animScale: Float
        get() = if (!animations) 0f else when (animSpeed) {
            "slow" -> 1.5f
            "fast" -> 0.6f
            else -> 1f
        }

    // ---- Gestures
    /** search | notifications | none */
    var swipeDown: String
        get() = p.getString("s_swipe_down", "search") ?: "search"
        set(v) = put("s_swipe_down", v)
    // ---- Customise: grid, icons, notifications, sidebar
    /** Home Screen rows: 0 = automatic (fits the screen), otherwise 4..7. */
    var rows: Int
        get() = p.getInt("s_rows", 0)
        set(v) = put("s_rows", v)
    /** Icon corner roundness, % of the icon (0 = square, 50 = circle). Default 22 like iPhone. */
    var iconCorner: Int
        get() = p.getInt("s_icon_corner", 22)
        set(v) = put("s_icon_corner", v)
    /** App name text size (sp ×10, 90..150). */
    var labelSize: Int
        get() = p.getInt("s_label_size", 115)
        set(v) = put("s_label_size", v)
    /** "launcher" (island / side island pop-ups), "original" (Android's own banners only) or "both". */
    var notifStyle: String
        get() = p.getString("s_notif_style", "launcher") ?: "launcher"
        set(v) = put("s_notif_style", v)
    /** Sidebar apps in 1 or 2 columns. */
    var sidebarCols: Int
        get() = p.getInt("s_sidebar_cols", 1)
        set(v) = put("s_sidebar_cols", v)

    // ---- Dock & App Library
    var dockOn: Boolean
        get() = p.getBoolean("s_dock_on", true)
        set(v) = put("s_dock_on", v)
    /** "glass" (Liquid Glass), "dark", "light" or "clear" (no background). */
    var dockStyle: String
        get() = p.getString("s_dock_style", "glass") ?: "glass"
        set(v) = put("s_dock_style", v)
    /** Corner roundness of the dock, in dp (12..40). */
    var dockRadius: Int
        get() = p.getInt("s_dock_radius", 36)
        set(v) = put("s_dock_radius", v)
    /** Dock icon size as % of the Home Screen icons (70..110). */
    var dockIconPct: Int
        get() = p.getInt("s_dock_icon_pct", 100)
        set(v) = put("s_dock_icon_pct", v)
    var libraryOn: Boolean
        get() = p.getBoolean("s_library_on", true)
        set(v) = put("s_library_on", v)
    /** "folders" (smart folders like iPhone) or "list" (A–Z list of every app). */
    var libraryStyle: String
        get() = p.getString("s_library_style", "folders") ?: "folders"
        set(v) = put("s_library_style", v)
    var libraryTitle: String
        get() = p.getString("s_library_title", "App Library") ?: "App Library"
        set(v) = put("s_library_title", v)
    /** library | none. Old recent-apps settings are treated as off. */
    var swipeUp: String
        get() = when (p.getString("s_swipe_up", "none")) {
            "library" -> "library"
            else -> "none"
        }
        set(v) = put("s_swipe_up", v)
    /** Liquid Glass: 0 = hazy/frosted, 100 = crystal clear. */
    var glassClarity: Int
        get() = p.getInt("s_glass_clarity", 50)
        set(v) = put("s_glass_clarity", v)

    /** Dynamic Island pill at the top of the Home Screen. */
    var island: Boolean
        get() = p.getBoolean("s_island", true)
        set(v) = put("s_island", v)

    // ---- Sidebar
    var sidebarOn: Boolean
        get() = p.getBoolean("s_sidebar", true)
        set(v) = put("s_sidebar", v)
    var sidebarOverApps: Boolean
        get() = p.getBoolean("s_sidebar_over", true)
        set(v) = put("s_sidebar_over", v)
    var sidebarRight: Boolean
        get() = p.getBoolean("s_sidebar_right", true)
        set(v) = put("s_sidebar_right", v)
    /** Handle position from the top, in dp (saved without refreshing the Home Screen). */
    var sidebarY: Int
        get() = p.getInt("s_sidebar_y", 260)
        set(v) { p.edit().putInt("s_sidebar_y", v).apply() }
    /** Where the open Sidebar panel's centre sits, in dp from the top (-1 = middle of the screen). */
    var sidebarPanelY: Int
        get() = p.getInt("s_sidebar_panel_y", -1)
        set(v) { p.edit().putInt("s_sidebar_panel_y", v).apply() }
    /** 60..140 % — panel width and app size. Saved without refreshing the Home Screen. */
    var sidebarScale: Int
        get() = p.getInt("s_sidebar_scale", 85)
        set(v) { p.edit().putInt("s_sidebar_scale", v).apply() }
    /** Panel height as % of the screen, 35..90. */
    var sidebarHeight: Int
        get() = p.getInt("s_sidebar_height", 72)
        set(v) { p.edit().putInt("s_sidebar_height", v).apply() }
    var sidebarLabels: Boolean
        get() = p.getBoolean("s_sidebar_labels", true)
        set(v) { p.edit().putBoolean("s_sidebar_labels", v).apply() }
    var sidebarApps: List<String>
        get() = (p.getString("s_sidebar_apps", "") ?: "").split('|').filter { it.isNotEmpty() }
        set(v) = put("s_sidebar_apps", v.joinToString("|"))

    /** Keep a small island at the top even when nothing is playing (pull it down for quick controls). */
    var islandAlways: Boolean
        get() = p.getBoolean("s_isl_always", true)
        set(v) = put("s_isl_always", v)
    /** Show volume / silent / Focus as a shape growing from the screen edge. */
    var sideHud: Boolean
        get() = p.getBoolean("s_side_hud", true)
        set(v) = put("s_side_hud", v)

    /** Keep a small side-island bump on the screen edge (tap it for volume). */
    var sideRest: Boolean
        get() = p.getBoolean("s_side_rest", true)
        set(v) = put("s_side_rest", v)
    /** New messages pop out of the side island instead of the top island. */
    var sideMessages: Boolean
        get() = p.getBoolean("s_side_msgs", true)
        set(v) = put("s_side_msgs", v)

    var sideShowVolume: Boolean
        get() = p.getBoolean("s_side_vol", true)
        set(v) = put("s_side_vol", v)
    var sideShowRinger: Boolean
        get() = p.getBoolean("s_side_ring", true)
        set(v) = put("s_side_ring", v)
    var sideShowFocus: Boolean
        get() = p.getBoolean("s_side_focus", true)
        set(v) = put("s_side_focus", v)
    /** all | chosen */
    var sideMsgMode: String
        get() = p.getString("s_side_msg_mode", "all") ?: "all"
        set(v) = put("s_side_msg_mode", v)
    var sideMsgApps: Set<String>
        get() = p.getStringSet("s_side_msg_apps", emptySet())?.toSet() ?: emptySet()
        set(v) = putSet("s_side_msg_apps", v)
    /** volume | music | none — tapping the resting sliver */
    var sideTap: String
        get() = p.getString("s_side_tap", "volume") ?: "volume"
        set(v) = put("s_side_tap", v)
    /** Vertical position on the edge, % from the top. */
    var sideY: Int
        get() = p.getInt("s_side_y", 30)
        set(v) { p.edit().putInt("s_side_y", v).apply() }
    /** Show a small scrollable chat window beside the side island for new messages. */
    var sideChatCard: Boolean
        get() = p.getBoolean("s_side_chat", true)
        set(v) = put("s_side_chat", v)
    /** Show photos and attachments in the chat window. */
    var sideMedia: Boolean
        get() = p.getBoolean("s_side_media", true)
        set(v) = put("s_side_media", v)
    /** Small button with the latest app's logo that opens recent chats. */
    var sideHistory: Boolean
        get() = p.getBoolean("s_side_history", true)
        set(v) = put("s_side_history", v)
    /** Saved window sizes in dp (0 = default). Saved without redrawing the launcher. */
    fun winSize(key: String): Pair<Int, Int> = p.getInt("s_win_${key}_w", 0) to p.getInt("s_win_${key}_h", 0)
    fun setWinSize(key: String, w: Int, h: Int) { p.edit().putInt("s_win_${key}_w", w).putInt("s_win_${key}_h", h).apply() }
    /** When Message History was last opened (newer messages get a blue dot). */
    var historySeen: Long
        get() = p.getLong("s_hist_seen", 0L)
        set(v) { p.edit().putLong("s_hist_seen", v).apply() }
    fun resetWinSizes() { p.edit().remove("s_win_chat_w").remove("s_win_chat_h").remove("s_win_hist_w").remove("s_win_hist_h").apply() }
    /** Connected headphones / earbuds (with battery) in the side island. */
    var sideDevice: Boolean
        get() = p.getBoolean("s_side_device", true)
        set(v) = put("s_side_device", v)
    /** Phone temperature in the side island. */
    var sideTemp: Boolean
        get() = p.getBoolean("s_side_temp", true)
        set(v) = put("s_side_temp", v)
    /** Show temperature in °F instead of °C. */
    var sideTempF: Boolean
        get() = p.getBoolean("s_side_temp_f", false)
        set(v) = put("s_side_temp_f", v)
    /** Recently used apps under the side island's chat button. */
    var sideRecents: Boolean
        get() = p.getBoolean("s_side_recents", true)
        set(v) = put("s_side_recents", v)
    /** How many recent apps are visible at once (the rest scroll). */
    var sideRecentsShown: Int
        get() = p.getInt("s_side_recents_shown", 3)
        set(v) = put("s_side_recents_shown", v)
    /** Recent app icon size: "s", "m" or "l". */
    var sideRecentsSize: String
        get() = p.getString("s_side_recents_size", "m") ?: "m"
        set(v) = put("s_side_recents_size", v)
    /** Reply box in the chat window. */
    var sideReply: Boolean
        get() = p.getBoolean("s_side_reply", true)
        set(v) = put("s_side_reply", v)
    /** Photo viewer: below | center | full */
    var viewerPos: String
        get() = p.getString("s_viewer_pos", "below") ?: "below"
        set(v) = put("s_viewer_pos", v)
    /** zoom | slide | fade | flip */
    var viewerAnim: String
        get() = p.getString("s_viewer_anim", "zoom") ?: "zoom"
        set(v) = put("s_viewer_anim", v)
    /** rounded | squircle | circle | square */
    var viewerShape: String
        get() = p.getString("s_viewer_shape", "rounded") ?: "rounded"
        set(v) = put("s_viewer_shape", v)
    /** dark | glass | theme | custom */
    var viewerBg: String
        get() = p.getString("s_viewer_bg", "dark") ?: "dark"
        set(v) = put("s_viewer_bg", v)
    var viewerColor: Int
        get() = p.getInt("s_viewer_color", 0xF01C1C1E.toInt())
        set(v) = put("s_viewer_color", v)
    /** Which edge: false = left, true = right. */
    var sideRight: Boolean
        get() = p.getBoolean("s_side_right", false)
        set(v) = put("s_side_right", v)
    /** black | glass | theme | custom */
    var sideColor: String
        get() = p.getString("s_side_color", "black") ?: "black"
        set(v) = put("s_side_color", v)
    var sideCustomColor: Int
        get() = p.getInt("s_side_custom", 0xFF1C1C1E.toInt())
        set(v) = put("s_side_custom", v)
    /** How solid the shape is, 30..100 %. */
    var sideOpacity: Int
        get() = p.getInt("s_side_opacity", 100)
        set(v) = put("s_side_opacity", v)
    /** How long volume / status stay (ms); messages stay a bit longer. */
    var sideMs: Int
        get() = p.getInt("s_side_ms", 1800)
        set(v) = put("s_side_ms", v)

    /** Now Playing widget keeps showing the last song after the music stops (and after a restart). */
    var musicKeepLast: Boolean
        get() = p.getBoolean("s_music_keep", false)
        set(v) = put("s_music_keep", v)
    /** Now Playing widget as an oblong (pill / stadium shape) instead of a rounded square. */
    /** Now Playing widget look: "classic", "vinyl" (dark record player) or "vinyl_light". */
    var musicStyle: String
        get() = p.getString("s_music_style", "classic") ?: "classic"
        set(v) = put("s_music_style", v)
    /** How far ahead of the singer the lyrics show, in ms (Settings › Widgets › Lyrics Timing). */
    var lyricsLead: Int
        get() = p.getInt("s_lyrics_lead", 900)
        set(v) = put("s_lyrics_lead", v)
    /** The island pops to the beat of the music (and the bars follow the real sound). */
    var islandBeats: Boolean
        get() = p.getBoolean("s_isl_beats", true)
        set(v) = put("s_isl_beats", v)
    var lyrics: Boolean
        get() = p.getBoolean("s_lyrics", true)
        set(v) = put("s_lyrics", v)
    var musicOblong: Boolean
        get() = p.getBoolean("s_music_oblong", false)
        set(v) = put("s_music_oblong", v)
    /** When nothing is playing, the island turns into an oblong row of tools (flashlight, brightness…). */
    var islandTools: Boolean
        get() = p.getBoolean("s_island_tools", true)
        set(v) = put("s_island_tools", v)
    // ---- Island tools bar look (size %, colour, shape, angle, where you dragged it)
    var toolsScale: Int
        get() = p.getInt("s_tools_scale", 100)
        set(v) = put("s_tools_scale", v)
    /** "black", "glass", "theme", "white", "custom". */
    var toolsColor: String
        get() = p.getString("s_tools_color", "black") ?: "black"
        set(v) = put("s_tools_color", v)
    var toolsCustomColor: Int
        get() = p.getInt("s_tools_custom", 0xFF0A84FF.toInt())
        set(v) = put("s_tools_custom", v)
    /** "pill", "rounded", "square", "leaf". */
    var toolsShape: String
        get() = p.getString("s_tools_shape", "pill") ?: "pill"
        set(v) = put("s_tools_shape", v)
    /** Tilt in degrees (-30…30). */
    var toolsAngle: Int
        get() = p.getInt("s_tools_angle", 0)
        set(v) = put("s_tools_angle", v)
    /** 60…100 % opacity of the bar. */
    var toolsOpacity: Int
        get() = p.getInt("s_tools_opacity", 100)
        set(v) = put("s_tools_opacity", v)
    /** Tools bar docked to a screen edge as a half circle: "none", "left" or "right". */
    var toolsEdge: String
        get() = p.getString("s_tools_edge", "none") ?: "none"
        set(v) { p.edit().putString("s_tools_edge", v).apply() }
    /** Look of the tools when docked to a side: "circle" (half circle) or "panel" (like the Sidebar). */
    var toolsEdgeStyle: String
        get() = p.getString("s_tools_edge_style", "circle") ?: "circle"
        set(v) = put("s_tools_edge_style", v)
    /** How the tools circle opens: "blossom", "ripple", "spin" or "fade". */
    var toolsAnim: String
        get() = p.getString("s_tools_anim", "ripple") ?: "ripple"
        set(v) = put("s_tools_anim", v)
    /** Tools bar folded into its small arrow tab. Saved without refreshing. */
    var toolsCollapsed: Boolean
        get() = p.getBoolean("s_tools_collapsed", false)
        set(v) { p.edit().putBoolean("s_tools_collapsed", v).apply() }
    /** Where you dragged the tools bar (dp from its normal spot). Saved without refreshing. */
    var toolsDx: Int
        get() = p.getInt("s_tools_dx", 0)
        set(v) { p.edit().putInt("s_tools_dx", v).apply() }
    var toolsDy: Int
        get() = p.getInt("s_tools_dy", 0)
        set(v) { p.edit().putInt("s_tools_dy", v).apply() }
    /** Game Mode (Do Not Disturb while playing), toggled from the island tools. */
    var gameMode: Boolean
        get() = p.getBoolean("s_game_mode", false)
        set(v) { p.edit().putBoolean("s_game_mode", v).apply() }
    /** Settings pages drawn as a hologram in the theme colour. */
    var holoSettings: Boolean
        get() = false // hologram look removed: classic Settings design
        set(v) = put("s_holo_settings", v)
    /** While a game is on screen, hide the islands, chat windows and pop-ups (the Sidebar stays). */
    var gameHide: Boolean
        get() = p.getBoolean("s_game_hide", true)
        set(v) = put("s_game_hide", v)
    /** While gaming: a movable ball (like Gilbert) with game tools, instead of the Sidebar handle. */
    var gameBall: Boolean
        get() = p.getBoolean("s_game_ball", true)
        set(v) = put("s_game_ball", v)
    /** Which pop-ups may show while gaming: "all", "priority" (Gilbert's Priority apps) or "off". */
    var gamePopups: String
        get() = p.getString("s_game_popups", "priority") ?: "priority"
        set(v) { p.edit().putString("s_game_popups", v).apply() }
    var gameBallRight: Boolean
        get() = p.getBoolean("s_game_ball_right", true)
        set(v) { p.edit().putBoolean("s_game_ball_right", v).apply() }
    /** Ball position from the top, as a fraction of the screen height. */
    var gameBallY: Float
        get() = p.getFloat("s_game_ball_y", 0.3f)
        set(v) { p.edit().putFloat("s_game_ball_y", v).apply() }
    /** Apps you (or the phone's game helper) marked as games. */
    var gameApps: Set<String>
        get() = p.getStringSet("s_game_apps", emptySet())?.toSet() ?: emptySet()
        set(v) { p.edit().putStringSet("s_game_apps", HashSet(v)).apply() }
    var notGameApps: Set<String>
        get() = p.getStringSet("s_not_game_apps", emptySet())?.toSet() ?: emptySet()
        set(v) { p.edit().putStringSet("s_not_game_apps", HashSet(v)).apply() }
    /** While gaming, apps opened from the Sidebar open in a small floating window. */
    var gameSmallWindow: Boolean
        get() = p.getBoolean("s_game_small_window", true)
        set(v) = put("s_game_small_window", v)
    /** Nudge of the tools' tap area around the camera, in dp (left -, right +; up -, down +). */
    var hotspotDx: Int
        get() = p.getInt("s_hot_dx", -12)
        set(v) = put("s_hot_dx", v)
    var hotspotDy: Int
        get() = p.getInt("s_hot_dy", -10)
        set(v) = put("s_hot_dy", v)
    /** Faint ring + line at the camera showing where to tap for the tools. */
    var tapZoneHint: Boolean
        get() = p.getBoolean("s_tap_hint", true)
        set(v) = put("s_tap_hint", v)
    /** How far the tap line reaches left of the camera, in dp. */
    var tapZoneLen: Int
        get() = p.getInt("s_tap_len", 66)
        set(v) = put("s_tap_len", v)
    /** What the island shows when nothing else is on: off, cycle, weather, steps, date, events, calories. */
    var islandInfo: String
        get() = p.getString("s_island_info", "off") ?: "off"
        set(v) = put("s_island_info", v)
    // ---- Gilbert (the living orb)
    /** ember, ocean, aurora, rose, mono, theme */
    var gilbertColors: String
        get() = p.getString("s_gil_colors", "ember") ?: "ember"
        set(v) = put("s_gil_colors", v)
    /** classic °~°, happy ^‿^, dots •‿•, sleepy -o- */
    var gilbertFace: String
        get() = p.getString("s_gil_face", "classic") ?: "classic"
        set(v) = put("s_gil_face", v)
    var gilbertDensity: Int
        get() = p.getInt("s_gil_density", 240)
        set(v) = put("s_gil_density", v)
    var gilbertSpeed: Int
        get() = p.getInt("s_gil_speed", 100)
        set(v) = put("s_gil_speed", v)
    var gilbertSize: Int
        get() = p.getInt("s_gil_size", 46)
        set(v) = put("s_gil_size", v)
    /** Gilbert shows emotions, reacts to what you do and looks where you look. */
    var gilbertEmotions: Boolean
        get() = p.getBoolean("s_gil_emotions", true)
        set(v) = put("s_gil_emotions", v)
    /** Gilbert says things now and then (check-ins, reminders, updates). */
    var gilbertTalk: Boolean
        get() = p.getBoolean("s_gil_talk", true)
        set(v) = put("s_gil_talk", v)
    var gilbertTalkMin: Int
        get() = p.getInt("s_gil_talk_min", 12)
        set(v) = put("s_gil_talk_min", v)
    /** Soft sound effects (pops, chirps, the hello chime). */
    var soundFx: Boolean
        get() = p.getBoolean("s_sfx", true)
        set(v) = put("s_sfx", v)
    var soundFxVolume: Int
        get() = p.getInt("s_sfx_vol", 60)
        set(v) = put("s_sfx_vol", v)
    /** No pop-ups from the app you're using right now (other apps still pop up). */
    var gilbertQuietInApp: Boolean
        get() = p.getBoolean("s_gil_inapp", true)
        set(v) = put("s_gil_inapp", v)
    /** Calm Mode: only priority apps pop up; Gilbert holds the rest. */
    var gilbertCalm: Boolean
        get() = p.getBoolean("s_gil_calm", true)
        set(v) = put("s_gil_calm", v)
    var gilbertMaxPerMin: Int
        get() = p.getInt("s_gil_max", 3)
        set(v) = put("s_gil_max", v)
    var gilbertPriority: Set<String>
        get() = p.getStringSet("s_gil_priority", emptySet())?.toSet() ?: emptySet()
        set(v) = putSet("s_gil_priority", v)
    var gilbertQuiet: Set<String>
        get() = p.getStringSet("s_gil_quiet", emptySet())?.toSet() ?: emptySet()
        set(v) = putSet("s_gil_quiet", v)
    /** Round island with a cute face  °~°  when nothing is happening. */
    var islandFace: Boolean
        get() = p.getBoolean("s_island_face", true)
        set(v) = put("s_island_face", v)
    /** Assistant button at the top of the Sidebar. */
    var sidebarAssistant: Boolean
        get() = p.getBoolean("s_sidebar_ai", true)
        set(v) = put("s_sidebar_ai", v)
    /** The assistant answers out loud. */
    var assistantVoice: Boolean
        get() = p.getBoolean("s_ai_voice", true)
        set(v) = put("s_ai_voice", v)

    /** Listen for "voice activation", then "open <app>". */
    var voiceOn: Boolean
        get() = false // the voice assistant has been removed
        set(v) = put("s_voice", v)

    // ---- Hello screen
    var helloOnUnlock: Boolean
        get() = p.getBoolean("s_hello_unlock", false)
        set(v) = put("s_hello_unlock", v)

    // ---- Theme (whole launcher)
    /** wallpaper | system | custom | mono */
    var themeSource: String
        get() = p.getString("s_theme_src", "wallpaper") ?: "wallpaper"
        set(v) = put("s_theme_src", v)
    var themeColor: Int
        get() = p.getInt("s_theme_color", 0xFF0A84FF.toInt())
        set(v) = put("s_theme_color", v)
    /** Tint glass panels, dock and widgets with the theme colour. */
    var themeSurfaces: Boolean
        get() = p.getBoolean("s_theme_surfaces", true)
        set(v) = put("s_theme_surfaces", v)
    /** dark | glass — Control Center tiles */
    var ccStyle: String
        get() = p.getString("s_cc_style", "dark") ?: "dark"
        set(v) = put("s_cc_style", v)

    // ---- Island look
    /** 80..130 % */
    var islandScale: Int
        get() = p.getInt("s_isl_scale", 100)
        set(v) = put("s_isl_scale", v)
    /** black | glass | outline | color */
    var islandStyle: String
        get() = p.getString("s_isl_style", "black") ?: "black"
        set(v) = put("s_isl_style", v)
    /** spring | fade | slide | zoom */
    var islandAnim: String
        get() = p.getString("s_isl_anim", "3d") ?: "3d"
        set(v) = put("s_isl_anim", v)
    /** Keep showing the island on top of other apps. */
    var islandOverApps: Boolean
        get() = p.getBoolean("s_isl_over_apps", true)
        set(v) = put("s_isl_over_apps", v)
    /** Sideways offset from the centre, in dp. */
    var islandX: Int
        get() = p.getInt("s_isl_x", 0)
        set(v) = put("s_isl_x", v)

    /** statusbar = in the status bar like iPhone · below = under the status bar */
    var islandPlacement: String
        get() = p.getString("s_isl_place", "below") ?: "below"
        set(v) = put("s_isl_place", v)
    /** Where you dragged the island over other apps (dp from its normal spot). */
    var islandAppsDx: Int
        get() = p.getInt("s_isl_apps_dx", 0)
        set(v) { p.edit().putInt("s_isl_apps_dx", v).apply() }
    var islandAppsDy: Int
        get() = p.getInt("s_isl_apps_dy", 0)
        set(v) { p.edit().putInt("s_isl_apps_dy", v).apply() }
    /** Extra nudge up (-) or down (+) in dp. */
    var islandNudge: Int
        get() = p.getInt("s_isl_nudge", 0)
        set(v) = put("s_isl_nudge", v)

    /** Share targets shown with Copy & go (package names, in order). */
    var islandPinned: List<String>
        get() = (p.getString("s_isl_pinned", "") ?: "").split('|').filter { it.isNotEmpty() }
        set(v) = put("s_isl_pinned", v.joinToString("|"))
    /** all | chosen — which music apps may appear in the island player. */
    var islandAudioMode: String
        get() = p.getString("s_isl_audio_mode", "all") ?: "all"
        set(v) = put("s_isl_audio_mode", v)
    var islandAudioApps: Set<String>
        get() = p.getStringSet("s_isl_audio_apps", emptySet())?.toSet() ?: emptySet()
        set(v) = putSet("s_isl_audio_apps", v)

    /** Which kinds of live activity may appear in the island. */
    fun islandAllows(type: String): Boolean = p.getBoolean("s_isl_$type", true)
    fun setIslandAllows(type: String, on: Boolean) = put("s_isl_$type", on)
    /** all | chosen — which apps' notifications may pop up in the island. */
    var islandNoteMode: String
        get() = p.getString("s_isl_note_mode", "all") ?: "all"
        set(v) = put("s_isl_note_mode", v)
    var islandNoteApps: Set<String>
        get() = p.getStringSet("s_isl_note_apps", emptySet())?.toSet() ?: emptySet()
        set(v) = putSet("s_isl_note_apps", v)
    /** How long pop-ups (notifications, charging, Focus) stay, in ms. */
    var islandPopupMs: Int
        get() = p.getInt("s_isl_popup_ms", 4500)
        set(v) = put("s_isl_popup_ms", v)

    /** system | gemini | chatgpt — opened by swiping down on the island. */
    var assistant: String
        get() = p.getString("s_assistant", "system") ?: "system"
        set(v) = put("s_assistant", v)

    /** Launcher-drawn wallpaper with the 3D spatial effect. */
    var spatialWallpaper: Boolean
        get() = p.getBoolean("s_spatial_wall", false)
        set(v) = put("s_spatial_wall", v)
    var spatialWallpaperUri: String?
        get() = p.getString("s_spatial_wall_uri", null)
        set(v) { p.edit().putString("s_spatial_wall_uri", v).putInt("s_version", version + 1).apply() }
    var extendWallpaper: Boolean
        get() = p.getBoolean("s_extend_wall", true)
        set(v) = put("s_extend_wall", v)
    var wallpaperDepth: Boolean
        get() = p.getBoolean("s_wall_depth", true)
        set(v) = put("s_wall_depth", v)

    /** AirPods-style card when headphones connect. */
    var headsetPopup: Boolean
        get() = p.getBoolean("s_headset_popup", true)
        set(v) = put("s_headset_popup", v)

    var doubleTapLock: Boolean
        get() = p.getBoolean("s_dtap_lock", false)
        set(v) = put("s_dtap_lock", v)

    // ---- Widgets
    fun widgetEnabled(key: String): Boolean = p.getBoolean("s_w_$key", true)
    fun setWidgetEnabled(key: String, on: Boolean) = put("s_w_$key", on)

    var hiddenWidgets: Set<String>
        get() = p.getStringSet("s_hidden_widgets", emptySet())?.toSet() ?: emptySet()
        set(v) = putSet("s_hidden_widgets", v)

    // ---- Apps
    var hiddenApps: Set<String>
        get() = p.getStringSet("s_hidden_apps", emptySet())?.toSet() ?: emptySet()
        set(v) = putSet("s_hidden_apps", v)

    /** Custom home-screen order (component keys). Apps not listed go at the end. */
    var order: List<String>
        get() = (p.getString("s_order", "") ?: "").split('|').filter { it.isNotEmpty() }
        set(v) = put("s_order", v.joinToString("|"))

    /** Set by the Settings screen to ask the home screen to enter edit mode. */
    var pendingEdit: Boolean
        get() = p.getBoolean("s_pending_edit", false)
        set(v) { p.edit().putBoolean("s_pending_edit", v).apply() }

    // ---- Simple Mode: one switch that turns off all the extras and brings them back as they were.
    private val simpleKeys = listOf("s_island", "s_isl_over_apps", "s_island_face", "s_island_tools", "s_gil_talk",
        "s_sidebar", "s_side_hud", "s_side_msgs", "s_side_rest", "s_headset_popup")
    /** On = only the Home Screen (icons, dock, widgets, theme, look). No Gilbert, island, Sidebar,
     *  Side Island or pop-ups. Turning it off restores each of those exactly as you had it. */
    var simpleMode: Boolean
        get() = p.getBoolean("s_simple", false)
        set(on) {
            if (on == simpleMode) return
            val e = p.edit()
            if (on) {
                for (k in simpleKeys) { e.putBoolean("simple_was_$k", p.getBoolean(k, true)); e.putBoolean(k, false) }
            } else {
                for (k in simpleKeys) { e.putBoolean(k, p.getBoolean("simple_was_$k", true)); e.remove("simple_was_$k") }
            }
            e.putBoolean("s_simple", on).putInt("s_version", version + 1).apply()
        }

    private fun put(k: String, v: Any) {
        val e = p.edit()
        when (v) {
            is String -> e.putString(k, v)
            is Int -> e.putInt(k, v)
            is Boolean -> e.putBoolean(k, v)
            is Float -> e.putFloat(k, v)
        }
        e.putInt("s_version", version + 1).apply()
    }

    private fun putSet(k: String, v: Set<String>) {
        p.edit().putStringSet(k, HashSet(v)).putInt("s_version", version + 1).apply()
    }

    companion object {
        val TINTS = listOf(
            0xFFFFFFFF.toInt(), 0xFFFF453A.toInt(), 0xFFFF9F0A.toInt(), 0xFFFFD60A.toInt(),
            0xFF30D158.toInt(), 0xFF64D2FF.toInt(), 0xFF0A84FF.toInt(), 0xFFBF5AF2.toInt(),
            0xFFFF375F.toInt(), 0xFFAC8E68.toInt()
        )
    }
}
