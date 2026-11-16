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
}
