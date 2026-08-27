package com.lispace.wzryncauto.schedule

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeCheckpointTest {
    @Test
    fun `one click send boundary blocks a duplicate in the same round`() {
        val checkpoint = RuntimeCheckpoint(
            taskId = "task",
            phase = RuntimePhase.RUNNING,
            actionRound = 2,
            pendingAction = PendingFarmAction.ONE_CLICK_SENT,
        )

        assertTrue(checkpoint.hasAlreadySentOneClick(2))
        assertFalse(checkpoint.hasAlreadySentOneClick(3))
    }

    @Test
    fun `waiting alarm is considered a recoverable pending task`() {
        assertTrue(
            RuntimeCheckpoint(
                taskId = "task",
                phase = RuntimePhase.WAITING_ALARM,
                nextRunAtEpochMs = 123L,
            ).hasPendingTask,
        )
        assertFalse(RuntimeCheckpoint().hasPendingTask)
    }

    @Test
    fun `failure retry keeps the logical round and starts a fresh action attempt`() {
        val checkpoint = RuntimeCheckpoint(
            taskId = "task",
            phase = RuntimePhase.RUNNING,
            infinite = true,
            completedRounds = 3,
            generation = 7,
            actionRound = 4,
            pendingAction = PendingFarmAction.ONE_CLICK_CONFIRMED,
            consecutiveFailures = 1,
        )

        val retry = checkpoint.scheduleFailureRetry(
            triggerAtEpochMs = 61_000L,
            reason = "识别失败",
            nowEpochMs = 1_000L,
        )

        assertEquals(RuntimePhase.WAITING_ALARM, retry.phase)
        assertEquals(3, retry.completedRounds)
        assertEquals(8L, retry.generation)
        assertEquals(61_000L, retry.nextRunAtEpochMs)
        assertEquals(FAILURE_RETRY_WAKE_REASON, retry.wakeReason)
        assertEquals(0, retry.actionRound)
        assertEquals(PendingFarmAction.NONE, retry.pendingAction)
        assertEquals(2, retry.consecutiveFailures)
        assertEquals("识别失败", retry.lastError)
        assertTrue(retry.hasPendingTask)
        assertFalse(retry.hasAlreadySentOneClick(4))
    }
}
