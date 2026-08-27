package com.lispace.wzryncauto.automation

import android.os.SystemClock
import com.lispace.wzryncauto.device.RootDeviceController
import com.lispace.wzryncauto.device.SafeScreenshotCapture
import com.lispace.wzryncauto.ocr.FarmlandState
import com.lispace.wzryncauto.ocr.FarmlandStateParser
import com.lispace.wzryncauto.ocr.MaturityOcrEngine
import com.lispace.wzryncauto.ocr.HarvestOcrObservation
import com.lispace.wzryncauto.ocr.HarvestInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference

internal data class ObservedScreenContext(
    val width: Int,
    val height: Int,
    val captureId: String,
    val capturedAtElapsedRealtimeNanos: Long,
)

internal object RootInputSafetyPolicy {
    fun violation(
        screen: ObservedScreenContext?,
        points: List<Pair<Int, Int>>,
        nowElapsedRealtimeNanos: Long,
        maxObservationAgeNanos: Long,
    ): String? {
        if (screen == null) return "没有可用于输入校验的最新画面"
        if (screen.width <= 0 || screen.height <= 0 || screen.width <= screen.height) {
            return "最新画面不是有效横屏：${screen.width}×${screen.height}"
        }
        val ageNanos = nowElapsedRealtimeNanos - screen.capturedAtElapsedRealtimeNanos
        if (ageNanos < 0 || ageNanos > maxObservationAgeNanos) {
            return "最新画面已过期：captureId=${screen.captureId}"
        }
        points.firstOrNull { (x, y) ->
            x !in 0 until screen.width || y !in 0 until screen.height
        }?.let { (x, y) ->
            return "输入坐标越界：($x,$y)，画面=${screen.width}×${screen.height}"
        }
        return null
    }
}

internal suspend fun <T> withReliableOverlayHidden(
    hideOverlay: () -> Unit,
    restoreOverlay: () -> Unit,
    settleBeforeInputMs: Long,
    settleBeforeRestoreMs: Long,
    block: suspend () -> T,
): T {
    try {
        hideOverlay()
        delay(settleBeforeInputMs)
        return block()
    } finally {
        // A cancelled coroutine cannot safely call delay in an ordinary
        // finally block. Restore the overlay from a non-cancellable context.
        withContext(NonCancellable) {
            delay(settleBeforeRestoreMs)
            restoreOverlay()
        }
    }
}

class RootAutomationRuntime(
    private val device: RootDeviceController,
    private val screenshotCapture: SafeScreenshotCapture,
    private val ocrEngine: MaturityOcrEngine,
    private val popupCloseTemplateMatcher: PopupCloseTemplateMatcher? = null,
    private val startGameButtonMatcher: StartGameButtonMatcher? = StartGameButtonMatcher(),
    private val hideOverlay: () -> Unit = {},
    private val restoreOverlay: () -> Unit = {},
    private val nowElapsedRealtimeNanos: () -> Long = SystemClock::elapsedRealtimeNanos,
) : AutomationRuntime {
    private val lastObservedScreen = AtomicReference<ObservedScreenContext?>()
    private val ocrTraceLock = Any()
    private val recentOcrTrace = ArrayDeque<String>()

    override suspend fun checkRoot(): Boolean = device.checkRoot().isRoot

    override suspend fun isGameForeground(): Boolean =
        device.foregroundActivity().contains(GAME_PACKAGE)

    override suspend fun isGameRunning(): Boolean = device.isGameRunning()

    override suspend fun launchGame() {
        lastObservedScreen.set(null)
        device.launchGame().requireSuccess("启动游戏")
    }

    override suspend fun stopGame() {
        try {
            device.stopGame().requireSuccess("停止游戏")
            repeat(STOP_VERIFY_ATTEMPTS) { attempt ->
                if (!device.isGameRunning()) return
                if (attempt < STOP_VERIFY_ATTEMPTS - 1) delay(STOP_VERIFY_INTERVAL_MS)
            }
            throw AutomationFailure("停止游戏后进程仍然存在")
        } finally {
            lastObservedScreen.set(null)
        }
    }

    override suspend fun tap(x: Int, y: Int) {
        requireSafeInput(listOf(x to y))
        withOverlayHidden {
            requireGameForeground()
            requireSafeInput(listOf(x to y))
            device.tap(x, y).requireSuccess("点击 ($x, $y)")
        }
    }

    override suspend fun swipe(gesture: SwipeGesture) {
        val points = listOf(
            gesture.startX to gesture.startY,
            gesture.endX to gesture.endY,
        )
        requireSafeInput(points)
        withOverlayHidden {
            requireGameForeground()
            requireSafeInput(points)
            device.swipe(
                gesture.startX,
                gesture.startY,
                gesture.endX,
                gesture.endY,
                gesture.durationMs,
            ).requireSuccess(
                "滑动 (${gesture.startX},${gesture.startY})→" +
                    "(${gesture.endX},${gesture.endY})",
            )
        }
    }

    override suspend fun pressBack() {
        requireSafeInput(emptyList())
        withOverlayHidden {
            requireGameForeground()
            requireSafeInput(emptyList())
            device.pressBack().requireSuccess("发送返回键")
        }
    }

    override suspend fun readFarmland(): FarmlandState {
        val capture = screenshotCapture.capture().getOrElse {
            throw AutomationFailure("OCR 截图失败：${it.message}")
        }
        check(capture.width > capture.height) {
            "OCR 画面不是横屏：${capture.width}×${capture.height}"
        }
        val screenshot = capture.encoded
        return withContext(Dispatchers.Default) {
            val observation = ocrEngine.recognize(screenshot).getOrElse {
                throw AutomationFailure("成熟时间 OCR 失败：${it.message}")
            }
            FarmlandStateParser.parse(observation.rawText, observation.reading)
        }
    }

    override suspend fun awaitScreenStable(
        timeoutMs: Long,
        label: String,
    ): ScreenStabilityResult = ScreenStabilityDetector(
        capture = {
            screenshotCapture.capture().getOrNull()?.let(ScreenStabilitySample::from)
        },
        wait = ::delay,
    ).await(timeoutMs)

    override suspend fun findPopupCloseTarget(
        profile: PopupCloseSearchProfile,
    ): PopupCloseTarget? {
        val matcher = popupCloseTemplateMatcher ?: return null
        val capture = screenshotCapture.capture().getOrNull() ?: return null
        lastObservedScreen.set(
            ObservedScreenContext(
                width = capture.width,
                height = capture.height,
                captureId = capture.captureId,
                capturedAtElapsedRealtimeNanos = capture.capturedAtElapsedRealtimeNanos,
            ),
        )
        return withContext(Dispatchers.Default) {
            matcher.find(capture.encoded, capture.width, capture.height, profile)
        }
    }

    override suspend fun findStartGameTarget(): StartGameVisualTarget? {
        val matcher = startGameButtonMatcher ?: return null
        val capture = screenshotCapture.capture().getOrNull() ?: return null
        lastObservedScreen.set(
            ObservedScreenContext(
                width = capture.width,
                height = capture.height,
                captureId = capture.captureId,
                capturedAtElapsedRealtimeNanos = capture.capturedAtElapsedRealtimeNanos,
            ),
        )
        return withContext(Dispatchers.Default) {
            matcher.find(capture.encoded, capture.width, capture.height)
        }
    }

    override suspend fun readHarvestUi(): HarvestOcrObservation =
        recognizeHarvestUi(
            capture = screenshotCapture.capture(),
            failureLabel = "收获 OCR",
        )

    override suspend fun readHarvestUiFromRoot(): HarvestOcrObservation =
        recognizeHarvestUi(
            capture = screenshotCapture.captureRoot(),
            failureLabel = "ROOT OCR",
        )

    private suspend fun recognizeHarvestUi(
        capture: Result<com.lispace.wzryncauto.device.ScreenshotCaptureResult>,
        failureLabel: String,
    ): HarvestOcrObservation {
        val captured = capture.getOrElse {
            throw AutomationFailure("$failureLabel 截图失败：${it.message}")
        }
        check(captured.width > captured.height) {
            "$failureLabel 画面不是横屏：${captured.width}×${captured.height}"
        }
        val screenContext = ObservedScreenContext(
            width = captured.width,
            height = captured.height,
            captureId = captured.captureId,
            capturedAtElapsedRealtimeNanos = captured.capturedAtElapsedRealtimeNanos,
        )
        val screenshot = captured.encoded
        return withContext(Dispatchers.Default) {
            ocrEngine.recognizeHarvest(screenshot).getOrElse {
                throw AutomationFailure("$failureLabel 内容识别失败：${it.message}")
            }
        }.also {
            // New-popup coordinates are derived from this exact frame. Keep
            // it as the active safety context for the immediately following tap.
            lastObservedScreen.set(screenContext)
            recordOcrTrace(captured, it)
        }
    }

    override suspend fun readHarvestInfo(): HarvestInfo? = readHarvestUi().parsed

    fun diagnosticOcrTrace(): String = synchronized(ocrTraceLock) {
        recentOcrTrace.joinToString(separator = "\n\n", postfix = "\n")
    }

    override suspend fun delayMs(milliseconds: Long) = delay(milliseconds)

    private suspend fun withOverlayHidden(block: suspend () -> Unit) =
        withReliableOverlayHidden(
            hideOverlay = hideOverlay,
            restoreOverlay = restoreOverlay,
            settleBeforeInputMs = OVERLAY_SETTLE_MS,
            settleBeforeRestoreMs = OVERLAY_SETTLE_MS,
            block = block,
        )

    private fun requireSafeInput(points: List<Pair<Int, Int>>) {
        RootInputSafetyPolicy.violation(
            screen = lastObservedScreen.get(),
            points = points,
            nowElapsedRealtimeNanos = nowElapsedRealtimeNanos(),
            maxObservationAgeNanos = MAX_OBSERVATION_AGE_NANOS,
        )?.let { throw AutomationFailure(it) }
    }

    private fun recordOcrTrace(
        capture: com.lispace.wzryncauto.device.ScreenshotCaptureResult,
        observation: HarvestOcrObservation,
    ) {
        val entry = buildString {
            appendLine(
                "captureId=${capture.captureId} source=${capture.source} " +
                    "sourceSequence=${capture.sourceSequence ?: "-"} " +
                    "capturedAtElapsedNanos=${capture.capturedAtElapsedRealtimeNanos} " +
                    "resolution=${capture.width}x${capture.height}",
            )
            appendLine("rawText=${observation.rawText.replace('\n', ' ').take(MAX_TRACE_TEXT_LENGTH)}")
            appendLine("boxes=${observation.ui.textBoxes.joinToString(" | ") { box ->
                "${box.text.replace('|', '/')}:" +
                    "${box.left},${box.top},${box.right},${box.bottom}"
            }.take(MAX_TRACE_BOX_LENGTH)}")
        }.trimEnd()
        synchronized(ocrTraceLock) {
            recentOcrTrace.addLast(entry)
            while (recentOcrTrace.size > MAX_TRACE_FRAMES) recentOcrTrace.removeFirst()
        }
    }

    private suspend fun requireGameForeground() {
        val foreground = device.foregroundActivity()
        if (!foreground.contains(GAME_PACKAGE)) {
            throw AutomationFailure("王者荣耀不在前台，禁止注入输入")
        }
    }

    private fun com.lispace.wzryncauto.device.CommandResult.requireSuccess(action: String) {
        if (!isSuccess) {
            val detail = stderr.ifBlank { stdout }.trim()
            throw AutomationFailure("$action 失败${detail.takeIf(String::isNotBlank)?.let { "：$it" } ?: ""}")
        }
    }

    companion object {
        private const val GAME_PACKAGE = "com.tencent.tmgp.sgame"
        private const val OVERLAY_SETTLE_MS = 100L
        private const val MAX_OBSERVATION_AGE_NANOS = 8_000_000_000L
        private const val STOP_VERIFY_ATTEMPTS = 5
        private const val STOP_VERIFY_INTERVAL_MS = 300L
        private const val MAX_TRACE_FRAMES = 8
        private const val MAX_TRACE_TEXT_LENGTH = 1_000
        private const val MAX_TRACE_BOX_LENGTH = 8_000
    }
}
