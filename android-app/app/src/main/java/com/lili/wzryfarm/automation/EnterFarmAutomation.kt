package com.lili.wzryfarm.automation

class AutomationFailure(message: String) : IllegalStateException(message)

/**
 * Runs the real, visually verified part of a round from app launch until the
 * farm spawn is visible. Movement is intentionally owned by the next phase.
 */
class EnterFarmAutomation(
    private val runtime: AutomationRuntime,
    private val control: AutomationControl = AlwaysRunningControl,
    private val onState: (AutomationState, String) -> Unit = { _, _ -> },
    private val onLog: (String) -> Unit = {},
) {
    private var state = AutomationState.IDLE

    suspend fun run(): AutomationState {
        transition(AutomationState.PREPARING, "准备执行")
        check(runtime.checkRoot()) { "未获得 ROOT 权限" }

        transition(AutomationState.CHECKING_GAME, "检查游戏状态")
        if (runtime.isGameRunning()) {
            operation("检测到游戏进程，重新启动以确保初始状态")
            runtime.stopGame()
            wait(5_000)
        }

        transition(AutomationState.LAUNCHING_GAME, "启动王者荣耀")
        runtime.launchGame()

        transition(AutomationState.WAITING_LOGIN, "等待登录页")
        requireMatch(
            names = STARTUP_MARKERS,
            timeoutMs = 60_000,
            intervalMs = 3_000,
            failure = "等待游戏登录页超时",
        )

        transition(AutomationState.CLOSING_STARTUP_POPUPS, "处理启动弹窗")
        closePopups(untilTemplate = START_GAME, label = "启动弹窗")

        transition(AutomationState.CLICKING_START_GAME, "点击开始游戏")
        clickStartGameWithVerification()

        transition(AutomationState.WAITING_LOBBY, "等待游戏大厅")
        requireMatch(
            names = LOBBY_MARKERS,
            timeoutMs = 90_000,
            intervalMs = 3_000,
            failure = "等待游戏大厅超时",
        )

        transition(AutomationState.CLOSING_LOBBY_POPUPS, "处理大厅弹窗")
        closePopups(untilTemplate = ENTER_FARM, label = "大厅弹窗")

        transition(AutomationState.ENTERING_FARM, "进入农场")
        clickWithRetry(ENTER_FARM, attempts = 10, retryMs = 5_000, label = "进入农场")
        requireMatch(
            names = listOf(FARM_READY),
            timeoutMs = 60_000,
            intervalMs = 3_000,
            failure = "等待农场加载超时",
        )

        transition(AutomationState.RESETTING_POSITION, "农场已就绪")
        return state
    }

    private suspend fun closePopups(untilTemplate: String, label: String) {
        var misses = 0
        repeat(10) {
            control.awaitRunnable()
            val screen = runtime.observe(POPUPS + untilTemplate)
            val popup = screen.matched(*POPUPS.toTypedArray())
            when {
                popup != null -> {
                    operation("关闭$label：${popup.templateName}")
                    runtime.tap(popup.centerX, popup.centerY)
                    misses = 0
                    wait(5_000)
                }
                screen.matched(untilTemplate) != null -> {
                    onLog("${label}处理完成，已识别 $untilTemplate")
                    return
                }
                else -> {
                    misses += 1
                    onLog("${label}未匹配（$misses/3）")
                    if (misses >= 3) return
                }
            }
        }
    }

    private suspend fun clickWithRetry(
        template: String,
        attempts: Int,
        retryMs: Long,
        label: String,
    ) {
        repeat(attempts) { index ->
            control.awaitRunnable()
            val match = runtime.observe(listOf(template)).matched(template)
            if (match != null) {
                operation("$label（${match.centerX}, ${match.centerY}）")
                runtime.tap(match.centerX, match.centerY)
                return
            }
            onLog("未找到$label（${index + 1}/$attempts）")
            if (index < attempts - 1) wait(retryMs)
        }
        throw AutomationFailure("连续 $attempts 次未找到$label")
    }

    private suspend fun clickStartGameWithVerification() {
        repeat(5) { index ->
            control.awaitRunnable()
            val before = runtime.observe(listOf(START_GAME) + LOBBY_MARKERS)
            if (before.matched(*LOBBY_MARKERS.toTypedArray()) != null) {
                onLog("已离开登录页")
                return
            }
            val start = before.matched(START_GAME)
            if (start == null) {
                onLog("未找到开始游戏（${index + 1}/5）")
                if (index < 4) wait(3_000)
                return@repeat
            }

            operation("开始游戏（${start.centerX}, ${start.centerY}）")
            runtime.tap(start.centerX, start.centerY)
            wait(5_000)

            val after = runtime.observe(listOf(START_GAME) + LOBBY_MARKERS)
            if (after.matched(*LOBBY_MARKERS.toTypedArray()) != null ||
                after.matched(START_GAME) == null
            ) {
                onLog("开始游戏点击已生效")
                return
            }
            onLog("开始游戏按钮仍存在，准备重试（${index + 1}/5）")
        }
        throw AutomationFailure("点击开始游戏 5 次后仍停留在登录页")
    }

    private suspend fun requireMatch(
        names: List<String>,
        timeoutMs: Long,
        intervalMs: Long,
        failure: String,
    ): TemplateObservation {
        var elapsed = 0L
        while (elapsed <= timeoutMs) {
            control.awaitRunnable()
            val observation = runtime.observe(names)
            if (!observation.isLandscape) {
                onLog("等待王者荣耀切换横屏（${observation.width}×${observation.height}）")
            }
            observation.matched(*names.toTypedArray())?.let {
                onLog("识别到 ${it.templateName}，score=%.3f".format(it.score))
                return it
            }
            if (elapsed == timeoutMs) break
            val delay = intervalMs.coerceAtMost(timeoutMs - elapsed)
            wait(delay)
            elapsed += delay
        }
        throw AutomationFailure(failure)
    }

    private suspend fun wait(milliseconds: Long) {
        var remaining = milliseconds
        while (remaining > 0) {
            control.awaitRunnable()
            val slice = remaining.coerceAtMost(250)
            runtime.delayMs(slice)
            remaining -= slice
        }
    }

    private fun transition(next: AutomationState, message: String) {
        AutomationTransitionPolicy.requireTransition(state, next)
        state = next
        onState(next, message)
        onLog(message)
    }

    private fun operation(message: String) {
        onLog(message)
        onState(state, message)
    }

    companion object {
        private const val START_GAME = "start_game.png"
        private const val ENTER_FARM = "lainongchang.png"
        private const val FARM_READY = "refresh_pos.png"
        private val POPUPS = listOf("close_popup.png", "close_popup_event.png")
        private val STARTUP_MARKERS = listOf(START_GAME) + POPUPS
        private val LOBBY_MARKERS = POPUPS + ENTER_FARM
    }
}
