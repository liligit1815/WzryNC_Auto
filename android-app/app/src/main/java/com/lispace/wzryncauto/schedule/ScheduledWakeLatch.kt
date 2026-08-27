package com.lispace.wzryncauto.schedule

/**
 * Remembers an alarm that arrives while the previous round is still leaving
 * its coroutine. AlarmManager alarms are one-shot, so simply ignoring that
 * delivery would leave the task stuck in WAITING_ALARM forever.
 */
class ScheduledWakeLatch {
    private var queuedGeneration: Long? = null

    @Synchronized
    fun defer(generation: Long) {
        val current = queuedGeneration
        if (current == null || generation > current) {
            queuedGeneration = generation
        }
    }

    @Synchronized
    fun take(): Long? = queuedGeneration.also {
        queuedGeneration = null
    }

    @Synchronized
    fun clear() {
        queuedGeneration = null
    }
}
