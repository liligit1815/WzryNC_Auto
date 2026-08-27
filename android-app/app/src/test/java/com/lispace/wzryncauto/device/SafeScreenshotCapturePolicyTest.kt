package com.lispace.wzryncauto.device

import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class SafeScreenshotCapturePolicyTest {
    @Test
    fun `stream frame must be captured strictly after overlay is hidden`() {
        val hiddenAt = 1_000L
        val old = frame(capturedAt = hiddenAt)
        val fresh = frame(capturedAt = hiddenAt + 1)

        assertNull(eligibleStreamFrame(old, hiddenAt))
        assertSame(fresh, eligibleStreamFrame(fresh, hiddenAt))
    }

    @Test
    fun `portrait stream falls back to root screenshot`() {
        val portrait = frame(capturedAt = 2_000L).copy(width = 1080, height = 2400)

        assertNull(eligibleStreamFrame(portrait, 1_000L))
    }

    private fun frame(capturedAt: Long) = StreamFrame(
        encoded = byteArrayOf(1),
        width = 2400,
        height = 1080,
        capturedAtElapsedRealtimeNanos = capturedAt,
        sequence = 1,
        captureId = "stream-1",
    )
}
