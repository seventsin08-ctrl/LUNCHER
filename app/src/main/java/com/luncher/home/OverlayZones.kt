package com.luncher.home

/**
 * Where the floating pieces are on screen, so they can make room for each other instead of
 * overlapping: the expanded island at the top, and the open sidebar on one edge.
 */
object OverlayZones {
    /** Bottom of the expanded island in screen pixels (0 when it's a small pill or hidden). */
    var islandBottom = 0
        private set
    /** Width taken by the open sidebar (0 when closed), and which edge it's on. */
    var sidebarWidth = 0
        private set
    var sidebarRight = true
        private set

    private val listeners = LinkedHashSet<() -> Unit>()
    fun listen(fn: () -> Unit) { listeners += fn }
    fun unlisten(fn: () -> Unit) { listeners -= fn }
    private fun fire() { for (l in listeners.toList()) runCatching { l() } }

    /** Screen area of the open side island (null while it's just the resting sliver). */
    var sideHud: android.graphics.Rect? = null
        private set
    fun setSideHud(r: android.graphics.Rect?) { if (r != sideHud) { sideHud = r; fire() } }

    fun setIsland(bottom: Int) { if (bottom != islandBottom) { islandBottom = bottom; fire() } }
    fun setSidebar(width: Int, right: Boolean) {
        if (width != sidebarWidth || right != sidebarRight) { sidebarWidth = width; sidebarRight = right; fire() }
    }
}
