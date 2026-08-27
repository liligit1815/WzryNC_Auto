package com.lispace.wzryncauto.device

class RootDeviceController(
    private val executor: RootCommandExecutor,
) {
    suspend fun checkRoot(): CommandResult =
        executor.executeText("id", timeoutMs = 8_000)

    suspend fun tap(x: Int, y: Int): CommandResult =
        executor.executeText(DeviceCommands.tap(x, y))

    suspend fun swipe(
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
        durationMs: Int,
    ): CommandResult = executor.executeText(
        DeviceCommands.swipe(x1, y1, x2, y2, durationMs),
    )

    suspend fun launchGame(): CommandResult =
        executor.executeText(DeviceCommands.launchGame(), timeoutMs = 15_000)

    suspend fun stopGame(): CommandResult =
        executor.executeText(DeviceCommands.stopGame())

    suspend fun isGameRunning(): Boolean =
        executor.executeText(DeviceCommands.processId()).isSuccess

    suspend fun foregroundActivity(): String =
        executor.executeText(DeviceCommands.foregroundActivity()).stdout.trim()

    suspend fun wakeScreen(): CommandResult =
        executor.executeText(DeviceCommands.wakeScreen())

    suspend fun dismissKeyguard(): CommandResult =
        executor.executeText(DeviceCommands.dismissKeyguard())

    suspend fun pressBack(): CommandResult =
        executor.executeText(DeviceCommands.pressBack())
}
