package com.lispace.wzryncauto.automation

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenStabilityTest {
    @Test
    fun `unchanged coarse samples are stable`() {
        val first = sample(80)
        val second = sample(80)

        val comparison = ScreenStabilityEvaluator.compare(first, second)

        assertTrue(comparison != null)
        assertTrue(ScreenStabilityEvaluator.isStable(comparison!!))
    }

    @Test
    fun `large scene change is not stable`() {
        val comparison = ScreenStabilityEvaluator.compare(sample(20), sample(220))

        assertTrue(comparison != null)
        assertFalse(ScreenStabilityEvaluator.isStable(comparison!!))
    }

    @Test
    fun `detector requires two consecutive quiet comparisons`() = runBlocking {
        val base = sample(80)
        val changed = sample(220)
        val frames = listOf(base, changed, base, base, base)
        var index = 0
        var nowNanos = 0L

        val result = ScreenStabilityDetector(
            capture = { frames.getOrNull(index++) },
            wait = { milliseconds -> nowNanos += milliseconds * 1_000_000L },
            nowNanos = { nowNanos },
        ).await(
            timeoutMs = 100,
            sampleIntervalMs = 10,
            requiredStableComparisons = 2,
        )

        assertTrue(result.stable)
        assertTrue(result.samples >= 5)
    }

    private fun sample(value: Int) = ScreenStabilitySample(
        width = 2_400,
        height = 1_080,
        luminance = IntArray(
            ScreenStabilitySample.GRID_WIDTH * ScreenStabilitySample.GRID_HEIGHT,
        ) { value },
    )
}
