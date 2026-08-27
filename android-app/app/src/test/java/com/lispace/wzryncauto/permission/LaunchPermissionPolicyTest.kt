package com.lispace.wzryncauto.permission

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LaunchPermissionPolicyTest {
    @Test
    fun `requests missing permissions in safe order`() {
        val snapshot = PermissionSnapshot(
            notificationGranted = false,
            overlayGranted = false,
            exactAlarmGranted = false,
            batteryOptimizationIgnored = false,
            miuiDevice = true,
            miuiAutoStartGranted = false,
        )

        assertEquals(
            LaunchPermissionStep.NOTIFICATION,
            LaunchPermissionPolicy.nextStep(snapshot, emptySet()),
        )
        assertEquals(
            LaunchPermissionStep.OVERLAY,
            LaunchPermissionPolicy.nextStep(
                snapshot,
                setOf(LaunchPermissionStep.NOTIFICATION),
            ),
        )
        assertEquals(
            LaunchPermissionStep.EXACT_ALARM,
            LaunchPermissionPolicy.nextStep(
                snapshot,
                setOf(
                    LaunchPermissionStep.NOTIFICATION,
                    LaunchPermissionStep.OVERLAY,
                ),
            ),
        )
    }

    @Test
    fun `does not request miui autostart on other manufacturers`() {
        val snapshot = PermissionSnapshot(
            notificationGranted = true,
            overlayGranted = true,
            exactAlarmGranted = true,
            batteryOptimizationIgnored = true,
            miuiDevice = false,
            miuiAutoStartGranted = false,
        )

        assertEquals(
            LaunchPermissionStep.ROOT,
            LaunchPermissionPolicy.nextStep(snapshot, emptySet()),
        )
    }

    @Test
    fun `flow finishes after root check when permissions are ready`() {
        val snapshot = PermissionSnapshot(
            notificationGranted = true,
            overlayGranted = true,
            exactAlarmGranted = true,
            batteryOptimizationIgnored = true,
            miuiDevice = true,
            miuiAutoStartGranted = true,
        )

        assertNull(
            LaunchPermissionPolicy.nextStep(
                snapshot,
                setOf(LaunchPermissionStep.ROOT),
            ),
        )
    }
}
