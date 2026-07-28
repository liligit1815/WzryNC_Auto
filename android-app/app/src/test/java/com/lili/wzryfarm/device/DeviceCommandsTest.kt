package com.lili.wzryfarm.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceCommandsTest {
    @Test
    fun buildsValidatedInputCommands() {
        assertEquals("input tap 120 340", DeviceCommands.tap(120, 340))
        assertEquals(
            "input swipe 10 20 30 40 1500",
            DeviceCommands.swipe(10, 20, 30, 40, 1500),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNegativeTapCoordinates() {
        DeviceCommands.tap(-1, 10)
    }

    @Test
    fun recognizesRootIdentity() {
        val result = CommandResult(
            command = "id",
            exitCode = 0,
            stdout = "uid=0(root) gid=0(root)",
            stderr = "",
            durationMs = 1,
            timedOut = false,
        )
        assertTrue(result.isRoot)
        assertTrue(result.isSuccess)
    }

    @Test
    fun validatesPngSignature() {
        val png = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1,
        )
        assertTrue(RootScreenshotProvider.isPng(png))
        assertFalse(RootScreenshotProvider.isPng(byteArrayOf(1, 2, 3)))
    }
}
