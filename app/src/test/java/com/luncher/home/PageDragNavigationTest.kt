package com.luncher.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PageDragNavigationTest {
    @Test fun movesAcrossExistingHomePages() {
        assertEquals(PageDragNavigation.Destination(2, false), PageDragNavigation.next(1, 3, 1, false))
        assertEquals(PageDragNavigation.Destination(0, false), PageDragNavigation.next(1, 3, -1, false))
    }

    @Test fun createsOnePageAtTheRightEnd() {
        assertEquals(PageDragNavigation.Destination(3, true), PageDragNavigation.next(2, 3, 1, false))
        assertNull(PageDragNavigation.next(3, 4, 1, true))
        // Once the first drag ends, another drag can create another page.
        assertEquals(PageDragNavigation.Destination(4, true), PageDragNavigation.next(3, 4, 1, false))
    }

    @Test fun noPageIsCreatedAtTheLeftEndOrWithInvalidInput() {
        assertNull(PageDragNavigation.next(0, 3, -1, false))
        assertNull(PageDragNavigation.next(0, 0, 1, false))
        assertNull(PageDragNavigation.next(3, 3, 1, false))
        assertNull(PageDragNavigation.next(1, 3, 0, false))
    }
}
