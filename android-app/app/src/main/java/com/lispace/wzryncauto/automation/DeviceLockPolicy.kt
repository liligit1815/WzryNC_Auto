package com.lispace.wzryncauto.automation

internal enum class LockedDeviceAction {
    READY,
    DISMISS_NON_SECURE_KEYGUARD,
    BLOCK_SECURE_KEYGUARD,
}

internal object DeviceLockPolicy {
    fun forCurrentState(
        isKeyguardLocked: Boolean,
        isDeviceLocked: Boolean,
        isKeyguardSecure: Boolean,
    ): LockedDeviceAction = when {
        !isKeyguardLocked && !isDeviceLocked -> LockedDeviceAction.READY
        isDeviceLocked && isKeyguardSecure -> LockedDeviceAction.BLOCK_SECURE_KEYGUARD
        else -> LockedDeviceAction.DISMISS_NON_SECURE_KEYGUARD
    }

    fun multiRoundBlockReason(
        infinite: Boolean,
        targetRounds: Int,
        isKeyguardSecure: Boolean,
        keepScreenAwake: Boolean,
    ): String? = if ((infinite || targetRounds > 1) && isKeyguardSecure) {
        if (keepScreenAwake) {
            null
        } else {
            "系统已设置密码锁，无法在无人值守时安全解锁；请开启运行期间保持屏幕常亮，或改为单轮"
        }
    } else {
        null
    }
}
