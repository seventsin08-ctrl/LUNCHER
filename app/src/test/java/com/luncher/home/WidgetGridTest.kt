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
}
