package com.lispace.wzryncauto.automation

import com.lispace.wzryncauto.ocr.FarmlandState
import com.lispace.wzryncauto.ocr.HarvestOcrObservation
import com.lispace.wzryncauto.ocr.HarvestInfo

interface AutomationRuntime {
    suspend fun checkRoot(): Boolean
    suspend fun isGameForeground(): Boolean
    suspend fun isGameRunning(): Boolean
    suspend fun launchGame()
    suspend fun stopGame()
    suspend fun tap(x: Int, y: Int)
    suspend fun swipe(gesture: SwipeGesture)
    suspend fun pressBack()
    suspend fun readFarmland(): FarmlandState
    /**
     * Waits for the visual frame to quiet down. Page-specific OCR confirmation
     * remains the caller's responsibility. Test runtimes may use the safe
     * default because they do not have real screenshots.
     */
    suspend fun awaitScreenStable(
        timeoutMs: Long,
        label: String,
    ): ScreenStabilityResult = ScreenStabilityResult(
        stable = true,
        samples = 0,
        elapsedMs = 0,
        lastComparison = null,
    )
    /** Finds a close-image target in a newly captured frame, if present. */
    suspend fun findPopupCloseTarget(
        profile: PopupCloseSearchProfile = PopupCloseSearchProfile.DEFAULT,
    ): PopupCloseTarget? = null
    /** Finds the current start-button graphic in a newly captured frame. */
    suspend fun findStartGameTarget(): StartGameVisualTarget? = null
    /**
     * Reads the current harvest UI. A null result means that this runtime
     * cannot prove either presence or absence and must never be treated as
     * an absent popup.
     */
    suspend fun readHarvestUi(): HarvestOcrObservation? = null
    /**
     * Reads the same UI from a forced lossless root screenshot. Implementations
     * that cannot force a source return null and must not silently reuse a
     * projection frame.
     */
    suspend fun readHarvestUiFromRoot(): HarvestOcrObservation? = null
    suspend fun readHarvestInfo(): HarvestInfo? = null
    suspend fun delayMs(milliseconds: Long)
}

data class StartGameVisualTarget(
    val centerX: Int,
    val centerY: Int,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val score: Double,
)

interface AutomationControl {
    suspend fun awaitRunnable()
}

object AlwaysRunningControl : AutomationControl {
    override suspend fun awaitRunnable() = Unit
}
