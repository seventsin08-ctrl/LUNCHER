package com.luncher.home

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeGridOrderTest {
    private val gap = HomeGridOrder.EMPTY

    @Test fun keepsEmptyCellsAndAppendsNewApps() {
        assertEquals(listOf("a", gap, "b", "c"), HomeGridOrder.normalize(listOf("a", gap, "b"), listOf("a", "b", "c")))
    }

    @Test fun movesAnAppToAnEmptyCellWithoutLosingOthers() {
        assertEquals(listOf(gap, "b", gap, "a"), HomeGridOrder.moveToEmpty(listOf("a", "b"), "a", 3))
    }

    @Test fun willNotOverwriteAnOccupiedCell() {
        assertEquals(listOf("a", "b"), HomeGridOrder.moveToEmpty(listOf("a", "b"), "a", 1))
    }

    @Test fun reordersAppsAroundAnotherApp() {
        assertEquals(listOf("b", "c", "a"), HomeGridOrder.moveAround(listOf("a", "b", "c"), "a", "c", true))
    }

    @Test fun removesUnavailableAndDuplicateApps() {
        assertEquals(listOf("a", gap, "b"), HomeGridOrder.normalize(listOf("a", "gone", "a", gap), listOf("a", "b")))
    }

    @Test fun placingAnAppOnANewPageKeepsOtherIconsAndThePageGap() {
        val moved = HomeGridOrder.moveToEmpty(listOf("a", "b", "c"), "b", 8)
        assertEquals(listOf("a", gap, "c", gap, gap, gap, gap, gap, "b"), moved)
        assertEquals(moved, HomeGridOrder.normalize(moved, listOf("a", "b", "c")))
    }

    @Test fun anAppDraggedFromTheDockCanBePlacedOnANewPage() {
        val moved = HomeGridOrder.moveToEmpty(listOf("a", "b"), "dockApp", 5)
        assertEquals(listOf("a", "b", gap, gap, gap, "dockApp"), moved)
        assertEquals(moved, HomeGridOrder.normalize(moved, listOf("a", "b", "dockApp")))
    }
}
