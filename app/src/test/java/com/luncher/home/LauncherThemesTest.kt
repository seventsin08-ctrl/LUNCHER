package com.luncher.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LauncherThemesTest {
    @Test fun presetsCanBeRecognizedAfterApplyingTheirSettings() {
        for (theme in LauncherThemes.presets) {
            assertEquals(theme.id, LauncherThemes.matching(theme.iconStyle, theme.tint, theme.glass, theme.tintPanels))
        }
    }

    @Test fun customCombinationIsNotMistakenForAPreset() {
        assertNull(LauncherThemes.matching("tinted", 123, true, true))
    }
}
