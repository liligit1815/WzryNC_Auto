package com.lispace.wzryncauto.automation

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RootAutomationRuntimeSafetyTest {
    @Test
    fun `input safety accepts fresh in-bounds landscape coordinates`() {
        val screen = ObservedScreenContext(
            width = 2400,
            height = 1080,
            captureId = "capture-1",
            capturedAtElapsedRealtimeNanos = 1_000,
        )

        assertNull(
            RootInputSafetyPolicy.violation(
                screen = screen,
                points = listOf(0 to 0, 2399 to 1079),
                nowElapsedRealtimeNanos = 2_000,
                maxObservationAgeNanos = 10_000,
            ),
        )
    }

    @Test
    fun `input safety rejects portrait stale and out-of-bounds observations`() {
        val portrait = ObservedScreenContext(1080, 2400, "portrait", 1_000)
        val landscape = ObservedScreenContext(2400, 1080, "landscape", 1_000)

        assertTrue(
            RootInputSafetyPolicy.violation(
                portrait,
                listOf(10 to 10),
                2_000,
                10_000,
            )!!.contains("不是有效横屏"),
        )
        assertTrue(
            RootInputSafetyPolicy.violation(
                landscape,
                listOf(2400 to 100),
                2_000,
                10_000,
            )!!.contains("坐标越界"),
        )
        assertTrue(
            RootInputSafetyPolicy.violation(
                landscape,
                listOf(10 to 10),
                20_000,
                10_000,
            )!!.contains("已过期"),
        )
    }

    @Test
    fun `overlay is restored when hidden operation is cancelled`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        var hideCount = 0
        var restoreCount = 0
        val job = launch {
            withReliableOverlayHidden(
                hideOverlay = { hideCount += 1 },
                restoreOverlay = { restoreCount += 1 },
                settleBeforeInputMs = 0,
                settleBeforeRestoreMs = 0,
            ) {
                entered.complete(Unit)
                awaitCancellation()
            }
        }

        entered.await()
        job.cancelAndJoin()

        assertEquals(1, hideCount)
        assertEquals(1, restoreCount)
    }
}
