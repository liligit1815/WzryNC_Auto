package com.lispace.wzryncauto.automation

import com.lispace.wzryncauto.ocr.MaturityReading
import com.lispace.wzryncauto.ocr.HarvestInfo

data class TemplateObservation(
    val templateName: String,
    val matched: Boolean,
    val score: Double,
    val centerX: Int,
    val centerY: Int,
    val screenshotId: String,
)

data class ScreenObservation(
    val templates: Map<String, TemplateObservation>,
    val width: Int = 0,
    val height: Int = 0,
) {
    val isLandscape: Boolean
        get() = width <= 0 || height <= 0 || width > height

    fun matched(vararg names: String): TemplateObservation? =
        names.firstNotNullOfOrNull { templates[it]?.takeIf(TemplateObservation::matched) }
}

interface AutomationRuntime {
    suspend fun checkRoot(): Boolean
    suspend fun isGameForeground(): Boolean
    suspend fun isGameRunning(): Boolean
    suspend fun launchGame()
    suspend fun stopGame()
    suspend fun observe(templateNames: List<String>): ScreenObservation
    suspend fun tap(x: Int, y: Int)
    suspend fun swipe(gesture: SwipeGesture)
    suspend fun readMaturity(): MaturityReading
    suspend fun readHarvestInfo(): HarvestInfo? = null
    suspend fun delayMs(milliseconds: Long)
}

interface AutomationControl {
    suspend fun awaitRunnable()
}

object AlwaysRunningControl : AutomationControl {
    override suspend fun awaitRunnable() = Unit
}
