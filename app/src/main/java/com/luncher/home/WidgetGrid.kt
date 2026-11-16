package com.luncher.home

/** Finds widget spaces without overlapping other widgets. Apps reflow separately. */
object WidgetGrid {
    data class Placement(val page: Int, val col: Int, val row: Int, val width: Int, val height: Int)

    fun fits(candidate: Placement, columns: Int, rows: Int, occupied: List<Placement>): Boolean {
        if (candidate.page < 0 || candidate.col < 0 || candidate.row < 0 || candidate.width <= 0 || candidate.height <= 0 ||
            candidate.col + candidate.width > columns || candidate.row + candidate.height > rows) return false
        return occupied.none { other ->
            candidate.page == other.page && candidate.col < other.col + other.width &&
                candidate.col + candidate.width > other.col && candidate.row < other.row + other.height &&
                candidate.row + candidate.height > other.row
        }
    }

    fun firstFit(columns: Int, rows: Int, width: Int, height: Int,
                 pages: List<Int>, occupied: List<Placement>): Placement? {
        if (width <= 0 || height <= 0 || width > columns || height > rows) return null
        for (page in pages.distinct()) for (row in 0..(rows - height)) for (col in 0..(columns - width)) {
            val candidate = Placement(page, col, row, width, height)
            if (fits(candidate, columns, rows, occupied)) return candidate
        }
        return null
    }
}
