package com.luncher.home

import android.content.ComponentName
import android.graphics.Bitmap
import android.widget.FrameLayout
import androidx.recyclerview.widget.RecyclerView

data class AppInfo(val label: String, val component: ComponentName, val icon: Bitmap) {
    val key: String get() = component.flattenToString()
    val packageName: String get() = component.packageName
}

enum class WidgetType {
    PHOTO, CALENDAR, BATTERY, CLOCK_ICON, CALENDAR_ICON, MUSIC, APPWIDGET,
    DIGITAL_CLOCK, WIDE_CLOCK, WORLD_CLOCK, DATE_CARD, QUOTE, PHOTO_WIDE, STACK, TIMER, IPOD
}

/** A widget placed on the home grid. col/row/w/h are in grid cells. */
data class PlacedWidget(
    val type: WidgetType,
    val col: Int,
    val row: Int,
    val w: Int,
    val h: Int,
    val id: String = "",
    /** Original page is a stable identity even after the widget moves. */
    val originPage: Int = -1
)

sealed class Page {
    /** apps holds each app together with the grid cell index (row * columns + col) it sits in. */
    data class Home(val widgets: List<PlacedWidget>, val apps: List<Pair<AppInfo, Int>>) : Page()
    object Library : Page()
}

class PageHolder(val frame: FrameLayout) : RecyclerView.ViewHolder(frame)
