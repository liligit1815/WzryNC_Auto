package com.lispace.wzryncauto.device

object DeviceCommands {
    const val GAME_PACKAGE = "com.tencent.tmgp.sgame"
    const val GAME_ACTIVITY =
        "com.tencent.tmgp.sgame/com.tencent.tmgp.sgame.SGameActivity"

    fun tap(x: Int, y: Int): String {
        require(x >= 0 && y >= 0) { "Tap coordinates must be non-negative" }
        return "input tap $x $y"
    }

    fun swipe(
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
        durationMs: Int,
    ): String {
        require(minOf(x1, y1, x2, y2) >= 0) {
            "Swipe coordinates must be non-negative"
        }
        require(durationMs in 1..60_000) {
            "Swipe duration must be between 1 and 60000ms"
        }
        return "input swipe $x1 $y1 $x2 $y2 $durationMs"
    }

    fun launchGame(): String = "am start -n $GAME_ACTIVITY"

    fun stopGame(): String = "am force-stop $GAME_PACKAGE"

    fun processId(): String = "pidof $GAME_PACKAGE"

    fun foregroundActivity(): String =
        "dumpsys activity activities | " +
            "grep -m 1 -E 'topResumedActivity|mResumedActivity'"

    fun wakeScreen(): String = "input keyevent KEYCODE_WAKEUP"

    fun dismissKeyguard(): String = "wm dismiss-keyguard"

    fun pressBack(): String = "input keyevent KEYCODE_BACK"
}
