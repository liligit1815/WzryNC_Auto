package com.lili.wzryfarm.automation

import com.lili.wzryfarm.device.RootDeviceController
import com.lili.wzryfarm.device.SafeScreenshotCapture
import com.lili.wzryfarm.ocr.MaturityOcrEngine
import com.lili.wzryfarm.ocr.MaturityReading
import com.lili.wzryfarm.vision.OpenCvTemplateMatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class RootAutomationRuntime(
    private val device: RootDeviceController,
    private val screenshotCapture: SafeScreenshotCapture,
    private val matcher: OpenCvTemplateMatcher,
    private val ocrEngine: MaturityOcrEngine,
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
        device.tap(x, y).requireSuccess("点击 ($x, $y)")
    }

    override suspend fun swipe(gesture: SwipeGesture) {
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

    override suspend fun delayMs(milliseconds: Long) = delay(milliseconds)

    private fun com.lili.wzryfarm.device.CommandResult.requireSuccess(action: String) {
        if (!isSuccess) {
            val detail = stderr.ifBlank { stdout }.trim()
            throw AutomationFailure("$action 失败${detail.takeIf(String::isNotBlank)?.let { "：$it" } ?: ""}")
        }
    }

    companion object {
        private const val GAME_PACKAGE = "com.tencent.tmgp.sgame"
    }
}
