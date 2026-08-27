package com.lispace.wzryncauto.permission

data class PermissionSnapshot(
    val notificationGranted: Boolean,
    val overlayGranted: Boolean,
    val exactAlarmGranted: Boolean,
    val batteryOptimizationIgnored: Boolean,
    val miuiDevice: Boolean,
    val miuiAutoStartGranted: Boolean,
)

enum class LaunchPermissionStep {
    NOTIFICATION,
    OVERLAY,
    EXACT_ALARM,
    BATTERY_OPTIMIZATION,
    MIUI_AUTO_START,
    ROOT,
}

object LaunchPermissionPolicy {
    fun nextStep(
        snapshot: PermissionSnapshot,
        attempted: Set<LaunchPermissionStep>,
    ): LaunchPermissionStep? = orderedSteps(snapshot)
        .firstOrNull { it !in attempted }

    private fun orderedSteps(snapshot: PermissionSnapshot) = buildList {
        if (!snapshot.notificationGranted) add(LaunchPermissionStep.NOTIFICATION)
        if (!snapshot.overlayGranted) add(LaunchPermissionStep.OVERLAY)
        if (!snapshot.exactAlarmGranted) add(LaunchPermissionStep.EXACT_ALARM)
        if (!snapshot.batteryOptimizationIgnored) {
            add(LaunchPermissionStep.BATTERY_OPTIMIZATION)
        }
        if (snapshot.miuiDevice && !snapshot.miuiAutoStartGranted) {
            add(LaunchPermissionStep.MIUI_AUTO_START)
        }
        add(LaunchPermissionStep.ROOT)
    }
}
