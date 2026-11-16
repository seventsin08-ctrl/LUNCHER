package com.luncher.home

import org.junit.Assert.assertEquals
import org.junit.Test

class FolderMembershipTest {
    @Test fun addsInstalledAppAfterExistingMembers() {
        assertEquals(listOf("clock", "camera", "music"),
            FolderMembership.add(listOf("clock", "camera"), "music", setOf("clock", "camera", "music")))
    }

    @Test fun repeatedTapDoesNotDuplicateAnApp() {
        assertEquals(listOf("clock", "camera"),
            FolderMembership.add(listOf("clock", "camera"), "camera", setOf("clock", "camera")))
    }

    @Test fun doesNotAddAnAppThatIsNoLongerInstalled() {
        assertEquals(listOf("clock"), FolderMembership.add(listOf("clock"), "removed", setOf("clock")))
    }
}
