package com.luncher.home

import org.junit.Assert.*
import org.junit.Test

class WidgetGridTest {
    @Test fun avoidsOtherWidgets() {
        val used = listOf(WidgetGrid.Placement(0, 0, 0, 2, 2))
        assertEquals(WidgetGrid.Placement(0, 2, 0, 2, 2), WidgetGrid.firstFit(4, 4, 2, 2, listOf(0), used))
    }

    @Test fun usesTheNextPageIfNecessary() {
        val used = listOf(WidgetGrid.Placement(0, 0, 0, 4, 4))
        assertEquals(WidgetGrid.Placement(1, 0, 0, 2, 2), WidgetGrid.firstFit(4, 4, 2, 2, listOf(0, 1), used))
    }

    @Test fun rejectsWidgetsThatCannotFitTheScreen() {
        assertNull(WidgetGrid.firstFit(4, 4, 5, 2, listOf(0), emptyList()))
        assertFalse(WidgetGrid.fits(WidgetGrid.Placement(0, 3, 3, 2, 2), 4, 4, emptyList()))
    }

    @Test fun resizesWithoutChangingTheWidgetsPosition() {
        val current = WidgetGrid.Placement(1, 1, 1, 2, 2)
        assertEquals(WidgetGrid.Placement(1, 1, 1, 3, 3),
            WidgetGrid.resize(current, 3, 3, 1, 1, 4, 5, emptyList()))
        assertEquals(WidgetGrid.Placement(1, 1, 1, 1, 1),
            WidgetGrid.resize(current, 1, 1, 1, 1, 4, 5, emptyList()))
    }

    @Test fun rejectsOverlapsBoundsAndMinimumSize() {
        val current = WidgetGrid.Placement(0, 0, 0, 2, 2)
        val other = WidgetGrid.Placement(0, 2, 0, 1, 2)
        assertNull(WidgetGrid.resize(current, 3, 2, 1, 1, 4, 4, listOf(other)))
        assertNull(WidgetGrid.resize(current, 5, 2, 1, 1, 4, 4, emptyList()))
        assertNull(WidgetGrid.resize(current, 1, 2, 2, 1, 4, 4, emptyList()))
        assertEquals(WidgetGrid.Placement(0, 0, 0, 2, 3),
            WidgetGrid.resize(current, 2, 3, 1, 1, 4, 4, listOf(other)))
    }
}
