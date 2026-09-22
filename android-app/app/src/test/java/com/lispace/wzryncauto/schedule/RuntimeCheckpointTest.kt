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
    fun `failure retry preserves the sent action and exact target for observation recovery`() {
        val checkpoint = RuntimeCheckpoint(
            taskId = "task",
            phase = RuntimePhase.RUNNING,
            infinite = true,
            completedRounds = 3,
            generation = 7,
            actionRound = 4,
            pendingAction = PendingFarmAction.ONE_CLICK_CONFIRMED,
            consecutiveFailures = 1,
            actionSentAtEpochMs = 500L,
            targetAtEpochMs = 400L,
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
        assertEquals(4, retry.actionRound)
        assertEquals(PendingFarmAction.ONE_CLICK_CONFIRMED, retry.pendingAction)
        assertEquals(500L, retry.actionSentAtEpochMs)
        assertEquals(400L, retry.targetAtEpochMs)
        assertEquals(2, retry.consecutiveFailures)
        assertEquals("识别失败", retry.lastError)
        assertTrue(retry.hasPendingTask)
        assertTrue(retry.hasAlreadySentOneClick(4))
    }

    @Test
    fun `maturity harvest send boundary also blocks replaying the primary action`() {
        for (action in listOf(
            PendingFarmAction.MATURITY_HARVEST_SENT,
            PendingFarmAction.MATURITY_HARVEST_CONFIRMED,
        )) {
            val retry = RuntimeCheckpoint(
                taskId = "task", actionRound = 1, pendingAction = action,
                actionSentAtEpochMs = 1_000L,
            ).scheduleFailureRetry(62_000L, "读取失败", 2_000L)
            assertTrue(retry.hasAlreadySentOneClick(1))
            assertEquals(action, retry.pendingAction)
            assertEquals(1_000L, retry.actionSentAtEpochMs)
        }
    }

    @Test
    fun `failure before any input can safely retry the primary action`() {
        val retry = RuntimeCheckpoint(taskId = "task")
            .scheduleFailureRetry(61_000L, "进场失败", 1_000L)
        assertFalse(retry.hasAlreadySentOneClick(1))
    }

    @Test
    fun `retry retains maturity purpose so watering cooldown cannot delay harvest`() {
        val checkpoint = RuntimeCheckpoint(
            taskId = "task", wakeReason = WakeReason.MATURITY.name,
            targetAtEpochMs = 300_000L, harvestObserved = true,
        )
        val retry = checkpoint.scheduleFailureRetry(240_000L, "进场失败", 180_000L)
        assertEquals(WakeReason.MATURITY.name, retry.wakeReason)
        assertEquals(300_000L, retry.targetAtEpochMs)
        assertTrue(retry.harvestObserved)
    }
}
