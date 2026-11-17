package com.luncher.home

import org.junit.Assert.*
import org.junit.Test

class FolderExtractionTest {
    @Test fun removalFromLargeFolderLeavesFolderInPlace() {
        val result = FolderExtraction.take(listOf("a", "b", "c"), "b", "folder", listOf("x", "folder", "y"))!!
        assertEquals(listOf("a", "c"), result.remaining)
        assertEquals(listOf("x", "folder", "y"), result.order)
        assertFalse(result.dissolve)
    }

    @Test fun twoMemberFolderBecomesRemainingAppInSameSlot() {
        val result = FolderExtraction.take(listOf("a", "b"), "a", "folder", listOf("x", "folder", "y"))!!
        assertTrue(result.dissolve)
        assertEquals(listOf("x", "b", "y"), result.order)
    }

    @Test fun lastMemberLeavesEmptySlotAndUnknownMemberChangesNothing() {
        val result = FolderExtraction.take(listOf("a"), "a", "folder", listOf("folder", "x"))!!
        assertEquals(listOf(HomeGridOrder.EMPTY, "x"), result.order)
        assertNull(FolderExtraction.take(listOf("a"), "missing", "folder", listOf("folder")))
    }
}
