package com.luncher.home

/** Plans page removal without discarding apps, folders, photos, or widgets. */
object HomePageEditor {
    data class Widget(val key: String, val placement: WidgetGrid.Placement, val visible: Boolean)
    data class Icon(val key: String, val page: Int, val cell: Int)
    data class Plan(val pageCount: Int, val widgets: Map<String, WidgetGrid.Placement>, val order: List<String>)

    fun remove(pageCount: Int, removed: Int, columns: Int, rows: Int,
               widgets: List<Widget>, icons: List<Icon>): Plan? {
        if (pageCount <= 1 || removed !in 0 until pageCount || columns <= 0 || rows <= 0) return null
        if (widgets.map { it.key }.toSet().size != widgets.size || icons.map { it.key }.toSet().size != icons.size) return null
        if (widgets.any { it.placement.page !in 0 until pageCount } ||
            icons.any { it.page !in 0 until pageCount || it.cell !in 0 until columns * rows }) return null
        val count = pageCount - 1
        val preferred = removed.coerceAtMost(count - 1)
        fun shifted(page: Int) = when {
            page < removed -> page
            page > removed -> page - 1
            else -> preferred
        }
        val result = linkedMapOf<String, WidgetGrid.Placement>()
        val occupied = mutableListOf<WidgetGrid.Placement>()
        // Keep widgets on the other pages in their original cells.
        for (widget in widgets.filter { it.visible && it.placement.page != removed }) {
            val place = widget.placement.copy(page = shifted(widget.placement.page))
            if (!WidgetGrid.fits(place, columns, rows, occupied)) return null
            result[widget.key] = place
            occupied += place
        }
        // Fit the removed page's widgets in the remaining pages, biggest first.
        val pageOrder = listOf(preferred) + (0 until count).filter { it != preferred }
        for (widget in widgets.filter { it.visible && it.placement.page == removed }
            .sortedByDescending { it.placement.width * it.placement.height }) {
            val original = widget.placement.copy(page = preferred)
            val place = if (WidgetGrid.fits(original, columns, rows, occupied)) original
                else WidgetGrid.firstFit(columns, rows, original.width, original.height, pageOrder, occupied)
                    ?: return null
            result[widget.key] = place
            occupied += place
        }
        for (widget in widgets.filter { !it.visible }) {
            result[widget.key] = widget.placement.copy(page = shifted(widget.placement.page))
        }
        val taken = Array(count) { BooleanArray(columns * rows) }
        for (w in occupied) for (row in w.row until w.row + w.height) for (col in w.col until w.col + w.width)
            taken[w.page][row * columns + col] = true
        val placed = Array(count) { arrayOfNulls<String>(columns * rows) }
        val orderedIcons = icons.sortedWith(compareBy<Icon> { it.page }.thenBy { it.cell })
        val pending = mutableListOf<Icon>()
        for (icon in orderedIcons) {
            val target = shifted(icon.page)
            if (icon.page != removed && !taken[target][icon.cell] && placed[target][icon.cell] == null)
                placed[target][icon.cell] = icon.key
            else pending += icon
        }
        for (icon in pending) {
            val target = shifted(icon.page)
            val candidates = listOf(target) + (0 until count).filter { it != target }
            val place = candidates.asSequence().flatMap { page ->
                (0 until columns * rows).asSequence().map { cell -> page to cell }
            }.firstOrNull { (page, cell) -> !taken[page][cell] && placed[page][cell] == null } ?: return null
            placed[place.first][place.second] = icon.key
        }
        val order = mutableListOf<String>()
        for (page in 0 until count) for (cell in 0 until columns * rows)
            if (!taken[page][cell]) order += placed[page][cell] ?: HomeGridOrder.EMPTY
        while (order.lastOrNull() == HomeGridOrder.EMPTY) order.removeAt(order.lastIndex)
        return Plan(count, result, order)
    }
}
