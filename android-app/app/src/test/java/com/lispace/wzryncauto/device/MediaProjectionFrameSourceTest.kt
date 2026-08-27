package com.lispace.wzryncauto.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class MediaProjectionFrameSourceTest {
    @Test
    fun `published stream frames receive monotonic sequence and capture id`() {
        var now = 5_000_000_000L
        val store = LatestStreamFrameStore { now }

        val first = store.publish(byteArrayOf(1), 2400, 1080, now)
        now += 1_000_000L
        val second = store.publish(byteArrayOf(2), 2400, 1080, now)

        assertEquals(1L, first.sequence)
        assertEquals("stream-1", first.captureId)
        assertEquals(2L, second.sequence)
        assertEquals("stream-2", second.captureId)
        assertSame(second, store.latest(maxAgeMs = 1_000))
    }

    @Test
    fun `latest frame store rejects stale and future timestamps`() {
        var now = 10_000_000_000L
        val store = LatestStreamFrameStore { now }

        store.publish(byteArrayOf(1), 2400, 1080, now)
        now += 1_001_000_000L
        assertNull(store.latest(maxAgeMs = 1_000))

        store.publish(byteArrayOf(2), 2400, 1080, now + 1)
        assertNull(store.latest(maxAgeMs = 1_000))
    }
}
