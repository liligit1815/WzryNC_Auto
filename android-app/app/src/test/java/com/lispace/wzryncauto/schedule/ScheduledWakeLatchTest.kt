package com.lispace.wzryncauto.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScheduledWakeLatchTest {
    @Test
    fun `alarm delivered during round cleanup is retained until cleanup finishes`() {
        val latch = ScheduledWakeLatch()

        latch.defer(7L)

        assertEquals(7L, latch.take())
        assertNull(latch.take())
    }

    @Test
    fun `newest alarm generation wins when duplicate deliveries overlap`() {
        val latch = ScheduledWakeLatch()

        latch.defer(7L)
        latch.defer(6L)
        latch.defer(8L)

        assertEquals(8L, latch.take())
    }

    @Test
    fun `clear removes a deferred alarm when the user stops the task`() {
        val latch = ScheduledWakeLatch()
        latch.defer(7L)

        latch.clear()

        assertNull(latch.take())
    }
}
