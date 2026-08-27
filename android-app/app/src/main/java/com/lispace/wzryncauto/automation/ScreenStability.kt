package com.lispace.wzryncauto.automation

import android.graphics.BitmapFactory
import com.lispace.wzryncauto.device.ScreenshotCaptureResult
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.min

/**
 * A small, deliberately lossy representation of a screenshot. Comparing a
 * coarse luminance grid is much less sensitive to text anti-aliasing and
 * particle effects than comparing every source pixel.
 */
internal data class ScreenStabilitySample(
    val width: Int,
    val height: Int,
    val luminance: IntArray,
) {
    init {
        require(width > height && height > 0)
        require(luminance.size == GRID_WIDTH * GRID_HEIGHT)
    }

    companion object {
        const val GRID_WIDTH = 32
        const val GRID_HEIGHT = 18

        fun from(capture: ScreenshotCaptureResult): ScreenStabilitySample? {
            val bitmap = BitmapFactory.decodeByteArray(
                capture.encoded,
                0,
                capture.encoded.size,
            ) ?: return null
            return try {
                val pixels = IntArray(GRID_WIDTH * GRID_HEIGHT)
                // Ignore the very top and bottom strips, where status bars,
                // system gestures and small counters tend to change. Keep the
                // middle of the game visible so page transitions and dialogs
                // still produce a clear difference.
                val left = bitmap.width * 0.04
                val top = bitmap.height * 0.08
                val sampledWidth = bitmap.width * 0.92
                val sampledHeight = bitmap.height * 0.84
                for (row in 0 until GRID_HEIGHT) {
                    val y = (top + sampledHeight * (row + 0.5) / GRID_HEIGHT)
                        .toInt()
                        .coerceIn(0, bitmap.height - 1)
                    for (column in 0 until GRID_WIDTH) {
                        val x = (left + sampledWidth * (column + 0.5) / GRID_WIDTH)
                            .toInt()
                            .coerceIn(0, bitmap.width - 1)
                        val color = bitmap.getPixel(x, y)
                        pixels[row * GRID_WIDTH + column] = luminance(color)
                    }
                }
                ScreenStabilitySample(capture.width, capture.height, pixels)
            } finally {
                bitmap.recycle()
            }
        }

        private fun luminance(color: Int): Int {
            val red = (color shr 16) and 0xff
            val green = (color shr 8) and 0xff
            val blue = color and 0xff
            return ( red * 299 + green * 587 + blue * 114) / 1000
        }
    }
}

data class ScreenStabilityComparison(
    val changedRatio: Double,
    val averageDifference: Double,
)

internal object ScreenStabilityEvaluator {
    /**
     * Compares the coarse game-body samples. Small luminance changes are
     * ignored because animated text and particles should not block forever.
     */
    fun compare(
        first: ScreenStabilitySample,
        second: ScreenStabilitySample,
    ): ScreenStabilityComparison? {
        if (
            first.width != second.width ||
            first.height != second.height ||
            first.luminance.size != second.luminance.size
        ) {
            return null
        }
        var changed = 0
        var totalDifference = 0L
        first.luminance.indices.forEach { index ->
            val difference = abs(first.luminance[index] - second.luminance[index])
            totalDifference += difference
            if (difference > LUMINANCE_CHANGE_THRESHOLD) changed += 1
        }
        return ScreenStabilityComparison(
            changedRatio = changed.toDouble() / first.luminance.size,
            averageDifference = totalDifference.toDouble() / first.luminance.size,
        )
    }

    fun isStable(comparison: ScreenStabilityComparison): Boolean =
        comparison.changedRatio <= MAX_CHANGED_RATIO &&
            comparison.averageDifference <= MAX_AVERAGE_DIFFERENCE

    private const val LUMINANCE_CHANGE_THRESHOLD = 18
    private const val MAX_CHANGED_RATIO = 0.12
    private const val MAX_AVERAGE_DIFFERENCE = 9.0
}

data class ScreenStabilityResult(
    val stable: Boolean,
    val samples: Int,
    val elapsedMs: Long,
    val lastComparison: ScreenStabilityComparison?,
)

/**
 * Waits for two consecutive quiet comparisons. It is intentionally a gate,
 * not a replacement for OCR: callers still need their page-specific text and
 * coordinate quorum after this returns.
 */
internal class ScreenStabilityDetector(
    private val capture: suspend () -> ScreenStabilitySample?,
    private val wait: suspend (Long) -> Unit = ::delay,
    private val nowNanos: () -> Long = System::nanoTime,
) {
    suspend fun await(
        timeoutMs: Long,
        sampleIntervalMs: Long = DEFAULT_SAMPLE_INTERVAL_MS,
        requiredStableComparisons: Int = DEFAULT_REQUIRED_STABLE_COMPARISONS,
    ): ScreenStabilityResult {
        require(timeoutMs >= 0)
        require(sampleIntervalMs > 0)
        require(requiredStableComparisons > 0)

        val startedAt = nowNanos()
        var previous: ScreenStabilitySample? = null
        var stableComparisons = 0
        var samples = 0
        var lastComparison: ScreenStabilityComparison? = null

        while (elapsedMs(startedAt) <= timeoutMs) {
            val current = capture()
            samples += 1
            if (current != null) {
                val comparison = previous?.let { ScreenStabilityEvaluator.compare(it, current) }
                if (comparison != null) {
                    lastComparison = comparison
                    stableComparisons = if (ScreenStabilityEvaluator.isStable(comparison)) {
                        stableComparisons + 1
                    } else {
                        0
                    }
                    if (stableComparisons >= requiredStableComparisons) {
                        return ScreenStabilityResult(
                            stable = true,
                            samples = samples,
                            elapsedMs = elapsedMs(startedAt),
                            lastComparison = comparison,
                        )
                    }
                }
                previous = current
            } else {
                stableComparisons = 0
                previous = null
            }

            val remaining = timeoutMs - elapsedMs(startedAt)
            if (remaining <= 0) break
            wait(min(sampleIntervalMs, remaining))
        }
        return ScreenStabilityResult(
            stable = false,
            samples = samples,
            elapsedMs = elapsedMs(startedAt).coerceAtMost(timeoutMs),
            lastComparison = lastComparison,
        )
    }

    private fun elapsedMs(startedAtNanos: Long): Long =
        (nowNanos() - startedAtNanos) / 1_000_000L

    companion object {
        private const val DEFAULT_SAMPLE_INTERVAL_MS = 220L
        private const val DEFAULT_REQUIRED_STABLE_COMPARISONS = 2
    }
}
