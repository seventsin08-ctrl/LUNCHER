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
