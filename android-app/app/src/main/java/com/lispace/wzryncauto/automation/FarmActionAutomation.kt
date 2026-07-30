package com.lispace.wzryncauto.automation

import com.lispace.wzryncauto.ocr.MaturityReading
import com.lispace.wzryncauto.ocr.HarvestInfo
import java.time.LocalDateTime
import kotlin.math.max

data class FarmActionResult(
    val harvested: Boolean,
    val harvestInfo: HarvestInfo?,
    val maturity: MaturityReading,
    val firstWaterAt: LocalDateTime,
)

/**
 * Continues a round from the verified farm spawn to the farmland.
 *
 * The second movement is structurally unreachable until the statue and
 * one-click-farm states have completed.
 */
class FarmActionAutomation(
    private val runtime: AutomationRuntime,
    private val control: AutomationControl = AlwaysRunningControl,
    private val onState: (AutomationState, String) -> Unit = { _, _ -> },
    private val onLog: (String) -> Unit = {},
    private val now: () -> LocalDateTime = LocalDateTime::now,
) {
    private var state = AutomationState.RESETTING_POSITION

    suspend fun run(): FarmActionResult {
        val spawn = observeAfterDismissingRestPopup(listOf(REFRESH_POSITION))
        requireLandscape(spawn)
        checkNotNull(spawn.matched(REFRESH_POSITION)) {
            "未确认农场初始位置，禁止执行摇杆移动"
        }
        val profile = MovementProfiles.requireFor(spawn.width, spawn.height)

        transition(AutomationState.MOVING_TO_STATUE, "从初始位置移动到雕像")
        operation(describe(profile.spawnToStatue))
        runtime.swipe(profile.spawnToStatue)
        wait(2_000)

        transition(AutomationState.VERIFYING_ONE_CLICK_FARM, "确认一键务农按钮")
        requireMatch(ONE_CLICK_FARM, timeoutMs = 12_000, failure = "移动后未找到一键务农按钮")

        transition(AutomationState.ONE_CLICK_FARMING, "执行一键务农")
        checkNotNull(observeAfterDismissingRestPopup(listOf(ONE_CLICK_FARM)).matched(ONE_CLICK_FARM)) {
            "一键务农按钮已消失"
        }
        wait(500)
        val freshButton = checkNotNull(
            observeAfterDismissingRestPopup(listOf(ONE_CLICK_FARM)).matched(ONE_CLICK_FARM),
        ) { "等待后未找到一键务农按钮" }
        operation("点击一键务农（${freshButton.centerX}, ${freshButton.centerY}）")
        val firstWaterAt = now()
        runtime.tap(freshButton.centerX, freshButton.centerY)
        wait(1_500)

        transition(AutomationState.HANDLING_HARVEST, "处理收获弹窗")
        val harvestInfo = handleHarvestPopup()

        transition(AutomationState.MOVING_TO_FARMLAND, "从雕像移动到土地")
        operation(describe(profile.statueToFarmland))
        runtime.swipe(profile.statueToFarmland)
        wait(2_000)

        transition(AutomationState.VERIFYING_FARMLAND, "确认已到达土地")
        val maturity = requireMaturityReading()

        transition(AutomationState.READING_MATURITY, "已到达土地，等待成熟时间识别")
        return FarmActionResult(
            harvested = harvestInfo.detected,
            harvestInfo = harvestInfo.info,
            maturity = maturity,
            firstWaterAt = firstWaterAt,
        )
    }

    private suspend fun handleHarvestPopup(): HarvestOutcome {
        repeat(3) { attempt ->
            control.awaitRunnable()
            val popup = observeAfterDismissingRestPopup(
                listOf(HARVEST_CONTINUE),
            ).matched(HARVEST_CONTINUE)
            if (popup == null) {
                onLog("未找到收获弹窗（${attempt + 1}/3）")
                wait(3_000)
                return@repeat
            }

            operation("检测到收获弹窗")
            wait(3_000)
            val readings = buildList {
                repeat(HARVEST_OCR_ATTEMPTS) { index ->
                    runtime.readHarvestInfo()?.let(::add)
                    if (index < HARVEST_OCR_ATTEMPTS - 1) wait(HARVEST_OCR_RETRY_MS)
                }
            }
            val info = mergeHarvestReadings(readings)
            onLog(
                "收获 OCR（${readings.size}/$HARVEST_OCR_ATTEMPTS 帧）：" +
                    "${info?.rawText?.replace('\n', ' ')?.take(200) ?: "<未解析>"}",
            )
            val freshPopup = checkNotNull(
                observeAfterDismissingRestPopup(
                    listOf(HARVEST_CONTINUE),
                ).matched(HARVEST_CONTINUE),
            ) { "收获弹窗在点击前消失" }
            operation("关闭收获弹窗（${freshPopup.centerX}, ${freshPopup.centerY}）")
            runtime.tap(freshPopup.centerX, freshPopup.centerY)
            wait(5_000)
            check(
                observeAfterDismissingRestPopup(
                    listOf(HARVEST_CONTINUE),
                ).matched(HARVEST_CONTINUE) == null,
            ) {
                "收获弹窗点击后仍然存在"
            }
            return HarvestOutcome(detected = true, info = info)
        }
        onLog("连续3次未找到收获弹窗，继续移动到土地")
        return HarvestOutcome(detected = false, info = null)
    }

    private suspend fun requireMatch(
        template: String,
        timeoutMs: Long,
        failure: String,
    ): TemplateObservation {
        var elapsed = 0L
        while (elapsed <= timeoutMs) {
            control.awaitRunnable()
            val screen = observeAfterDismissingRestPopup(listOf(template))
            requireLandscape(screen)
            screen.matched(template)?.let {
                onLog("识别到 $template，score=%.3f".format(it.score))
                return it
            }
            if (elapsed == timeoutMs) break
            val delay = 1_000L.coerceAtMost(timeoutMs - elapsed)
            wait(delay)
            elapsed += delay
        }
        throw AutomationFailure(failure)
    }

    private suspend fun requireMaturityReading(): MaturityReading {
        repeat(3) { attempt ->
            control.awaitRunnable()
            observeAfterDismissingRestPopup(emptyList())
            when (val reading = runtime.readMaturity()) {
                is MaturityReading.Time -> {
                    onLog("土地成熟时间：%02d:%02d".format(reading.hour, reading.minute))
                    return reading
                }
                is MaturityReading.Mature -> {
                    onLog("土地作物已成熟")
                    return reading
                }
                is MaturityReading.Unrecognized -> {
                    val raw = reading.rawText
                        .replace('\n', ' ')
                        .trim()
                        .take(120)
                        .ifBlank { "<空>" }
                    onLog(
                        "土地 OCR 未识别（${attempt + 1}/3）：${reading.reason}；原文：$raw",
                    )
                    if (attempt < 2) wait(3_000)
                }
            }
        }
        throw AutomationFailure("移动后未识别到土地成熟信息")
    }

    private suspend fun observeAfterDismissingRestPopup(
        templateNames: List<String>,
    ): ScreenObservation {
        repeat(3) {
            control.awaitRunnable()
            val screen = runtime.observe(templateNames + REST_REMINDER_CONFIRM)
            val reminder = screen.matched(REST_REMINDER_CONFIRM) ?: return screen
            operation(
                "关闭防沉迷休息提示（${reminder.centerX}, ${reminder.centerY}）",
            )
            runtime.tap(reminder.centerX, reminder.centerY)
            wait(2_000)
        }
        throw AutomationFailure("连续关闭 3 次后防沉迷休息提示仍然存在")
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
        onState(state, message)
        onLog(message)
    }

    private fun requireLandscape(screen: ScreenObservation) {
        check(screen.isLandscape) {
            "农场画面不是横屏：${screen.width}×${screen.height}"
        }
    }

    private fun describe(gesture: SwipeGesture): String =
        "摇杆（${gesture.startX},${gesture.startY}）→" +
            "（${gesture.endX},${gesture.endY}），${gesture.durationMs}ms"

    private fun mergeHarvestReadings(readings: List<HarvestInfo>): HarvestInfo? {
        if (readings.isEmpty()) return null
        val crops = linkedMapOf<String, Int>()
        readings.forEach { reading ->
            reading.crops.forEach { (crop, count) ->
                crops[crop] = max(crops[crop] ?: 0, count)
            }
        }
        return HarvestInfo(
            experience = readings.maxOf(HarvestInfo::experience),
            crops = crops,
            rawText = readings.joinToString(" | ") { it.rawText },
        )
    }

    companion object {
        private const val REFRESH_POSITION = "refresh_pos.png"
        private const val ONE_CLICK_FARM = "oneclick_farm.png"
        private const val HARVEST_CONTINUE = "harvest_continue.png"
        private const val REST_REMINDER_CONFIRM = "rest_reminder_confirm.png"
        private const val HARVEST_OCR_ATTEMPTS = 3
        private const val HARVEST_OCR_RETRY_MS = 500L
    }

    private data class HarvestOutcome(
        val detected: Boolean,
        val info: HarvestInfo?,
    )
}
