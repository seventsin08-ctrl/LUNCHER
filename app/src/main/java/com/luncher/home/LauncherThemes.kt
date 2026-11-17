package com.luncher.home

/** Appearance presets only change visual settings, never the user's wallpaper or layout. */
object LauncherThemes {
    data class Theme(val id: String, val label: String, val iconStyle: String,
                     val tint: Int, val glass: Boolean, val tintPanels: Boolean)

    val presets = listOf(
        Theme("glass", "Glass", "clear", 0xFFFFFFFF.toInt(), true, false),
        Theme("navy", "Navy", "tinted", 0xFF8DAACB.toInt(), true, true),
        Theme("sage", "Sage", "tinted", 0xFF9FAF95.toInt(), true, true),
        Theme("sand", "Sand", "frost", 0xFFD5BCA4.toInt(), true, true)
    )

    fun matching(iconStyle: String, tint: Int, glass: Boolean, tintPanels: Boolean): String? =
        presets.firstOrNull {
            it.iconStyle == iconStyle && it.glass == glass && it.tintPanels == tintPanels &&
                (it.id == "glass" || it.tint == tint)
        }?.id
}
