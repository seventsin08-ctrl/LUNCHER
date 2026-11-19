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
    /** depth | flat | cube | zoom */
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

    /** Listen for "voice activation", then "open <app>". */
    var voiceOn: Boolean
        get() = p.getBoolean("s_voice", false)
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
