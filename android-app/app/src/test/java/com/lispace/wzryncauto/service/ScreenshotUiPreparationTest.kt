package com.lispace.wzryncauto.service

import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenshotUiPreparationTest {
    @Test
    fun cancelsToastBeforeHidingOverlay() {
        val actions = mutableListOf<String>()

        prepareUiForScreenshot(
            cancelToast = { actions += "cancel-toast" },
            hideOverlay = { actions += "hide-overlay" },
        )

        assertEquals(listOf("cancel-toast", "hide-overlay"), actions)
    }
}
