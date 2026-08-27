package com.lispace.wzryncauto.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DeviceLockPolicyTest {
    @Test
    fun `unlocked device is ready regardless of configured credential`() {
        assertEquals(
            LockedDeviceAction.READY,
            DeviceLockPolicy.forCurrentState(
                isKeyguardLocked = false,
                isDeviceLocked = false,
                isKeyguardSecure = true,
            ),
        )
    }

    @Test
    fun `non-secure lock screen may be dismissed`() {
        assertEquals(
            LockedDeviceAction.DISMISS_NON_SECURE_KEYGUARD,
            DeviceLockPolicy.forCurrentState(
                isKeyguardLocked = true,
                isDeviceLocked = true,
                isKeyguardSecure = false,
            ),
        )
    }

    @Test
    fun `secure lock screen is never bypassed`() {
        assertEquals(
            LockedDeviceAction.BLOCK_SECURE_KEYGUARD,
            DeviceLockPolicy.forCurrentState(
                isKeyguardLocked = true,
                isDeviceLocked = true,
                isKeyguardSecure = true,
            ),
        )
    }

    @Test
    fun `visible trusted keyguard still must be dismissed`() {
        assertEquals(
            LockedDeviceAction.DISMISS_NON_SECURE_KEYGUARD,
            DeviceLockPolicy.forCurrentState(
                isKeyguardLocked = true,
                isDeviceLocked = false,
                isKeyguardSecure = true,
            ),
        )
    }

    @Test
    fun `secure credential blocks unattended multi-round mode when screen wake is disabled`() {
        assertNotNull(
            DeviceLockPolicy.multiRoundBlockReason(
                infinite = false,
                targetRounds = 2,
                isKeyguardSecure = true,
                keepScreenAwake = false,
            ),
        )
        assertNotNull(
            DeviceLockPolicy.multiRoundBlockReason(
                infinite = true,
                targetRounds = 1,
                isKeyguardSecure = true,
                keepScreenAwake = false,
            ),
        )
        assertNull(
            DeviceLockPolicy.multiRoundBlockReason(
                infinite = false,
                targetRounds = 1,
                isKeyguardSecure = true,
                keepScreenAwake = false,
            ),
        )
    }

    @Test
    fun `screen wake allows secure credential for multi-round start`() {
        assertNull(
            DeviceLockPolicy.multiRoundBlockReason(
                infinite = true,
                targetRounds = 1,
                isKeyguardSecure = true,
                keepScreenAwake = true,
            ),
        )
    }
}
