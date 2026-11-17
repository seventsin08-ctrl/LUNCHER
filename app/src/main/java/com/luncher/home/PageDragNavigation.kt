package com.luncher.home

/** Home-page navigation while an item is held at a screen edge. */
object PageDragNavigation {
    data class Destination(val page: Int, val createPage: Boolean)

    fun next(current: Int, count: Int, direction: Int, createdDuringDrag: Boolean): Destination? {
        if (count <= 0 || current !in 0 until count) return null
        return when {
            direction < 0 && current > 0 -> Destination(current - 1, false)
            direction > 0 && current + 1 < count -> Destination(current + 1, false)
            direction > 0 && current == count - 1 && !createdDuringDrag -> Destination(count, true)
            else -> null
        }
    }
}
