package com.lispace.wzryncauto.automation

import com.lispace.wzryncauto.device.RootDeviceController
import com.lispace.wzryncauto.device.SafeScreenshotCapture
import com.lispace.wzryncauto.ocr.MaturityOcrEngine
import com.lispace.wzryncauto.ocr.MaturityReading
import com.lispace.wzryncauto.ocr.HarvestInfo
import com.lispace.wzryncauto.vision.OpenCvTemplateMatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class RootAutomationRuntime(
    private val device: RootDeviceController,
    private val screenshotCapture: SafeScreenshotCapture,
    private val matcher: OpenCvTemplateMatcher,
    private val ocrEngine: MaturityOcrEngine,
    private val hideOverlay: () -> Unit = {},
    private val restoreOverlay: () -> Unit = {},
) : AutomationRuntime {
    override suspend fun checkRoot(): Boolean = device.checkRoot().isRoot

    override suspend fun isGameForeground(): Boolean =
        device.foregroundActivity().contains(GAME_PACKAGE)

    override suspend fun isGameRunning(): Boolean = device.isGameRunning()

    override suspend fun launchGame() {
        device.launchGame().requireSuccess("启动游戏")
    }

    override suspend fun stopGame() {
        device.stopGame().requireSuccess("停止游戏")
    }

    override suspend fun observe(templateNames: List<String>): ScreenObservation {
        val capture = screenshotCapture.capture().getOrElse {
            throw AutomationFailure("截图失败：${it.message}")
        }
        if (capture.width <= capture.height) {
            return ScreenObservation(
                templates = templateNames.associateWith { name ->
                    TemplateObservation(
                        templateName = name,
                        matched = false,
                        score = -1.0,
                        centerX = 0,
                        centerY = 0,
                        screenshotId = "${capture.file.lastModified()}-${capture.byteCount}",
                    )
                },
                width = capture.width,
                height = capture.height,
            )
        }
        val screenshot = withContext(Dispatchers.IO) { capture.file.readBytes() }
        val screenshotId = "${capture.file.lastModified()}-${capture.byteCount}"
        val observations = withContext(Dispatchers.Default) {
            templateNames.associateWith { name ->
                val result = matcher.match(screenshot, name, screenshotId).getOrElse {
                    throw AutomationFailure("模板 $name 识别失败：${it.message}")
                }
                TemplateObservation(
                    templateName = result.templateName,
                    matched = result.matched,
                    score = result.score,
                    centerX = result.centerX,
                    centerY = result.centerY,
                    screenshotId = result.screenshotId,
                )
            }
        }
        return ScreenObservation(
            templates = observations,
            width = capture.width,
            height = capture.height,
        )
    }

    override suspend fun tap(x: Int, y: Int) {
        withOverlayHidden {
            device.tap(x, y).requireSuccess("点击 ($x, $y)")
        }
    }

    override suspend fun swipe(gesture: SwipeGesture) {
        withOverlayHidden {
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

    override suspend fun readMaturity(): MaturityReading {
        val capture = screenshotCapture.capture().getOrElse {
            throw AutomationFailure("OCR 截图失败：${it.message}")
        }
        check(capture.width > capture.height) {
            "OCR 画面不是横屏：${capture.width}×${capture.height}"
        }
        val screenshot = withContext(Dispatchers.IO) { capture.file.readBytes() }
        return withContext(Dispatchers.Default) {
            ocrEngine.recognize(screenshot).getOrElse {
                throw AutomationFailure("成熟时间 OCR 失败：${it.message}")
            }.reading
        }
    }

    override suspend fun readHarvestInfo(): HarvestInfo? {
        val capture = screenshotCapture.capture().getOrElse {
            throw AutomationFailure("收获 OCR 截图失败：${it.message}")
        }
        check(capture.width > capture.height) {
            "收获 OCR 画面不是横屏：${capture.width}×${capture.height}"
        }
        val screenshot = withContext(Dispatchers.IO) { capture.file.readBytes() }
        return withContext(Dispatchers.Default) {
            ocrEngine.recognizeHarvest(screenshot).getOrElse {
                throw AutomationFailure("收获内容 OCR 失败：${it.message}")
            }.parsed
        }
    }

    override suspend fun delayMs(milliseconds: Long) = delay(milliseconds)

    private suspend fun withOverlayHidden(block: suspend () -> Unit) {
        hideOverlay()
        try {
            // Give WindowManager time to remove the overlay's touch region
            // before injecting input into the game underneath it.
            delay(100)
            block()
        } finally {
            delay(100)
            restoreOverlay()
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
    }
}
