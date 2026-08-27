package com.lispace.wzryncauto.schedule

import android.content.Context
import java.util.UUID

enum class RuntimePhase {
    IDLE,
    RUNNING,
    WAITING_ALARM,
    PAUSED,
    COMPLETED,
    ERROR,
    RECOVERY_REQUIRED,
}

enum class PendingFarmAction {
    NONE,
    ONE_CLICK_SENT,
    ONE_CLICK_CONFIRMED,
    HARVEST_CONTINUE_SENT,
    HARVEST_CONTINUE_CONFIRMED,
}

data class RuntimeCheckpoint(
    val taskId: String? = null,
    val phase: RuntimePhase = RuntimePhase.IDLE,
    val infinite: Boolean = false,
    val targetRounds: Int = 0,
    val completedRounds: Int = 0,
    val generation: Long = 0,
    val nextRunAtEpochMs: Long? = null,
    val wakeReason: String? = null,
    val actionRound: Int = 0,
    val pendingAction: PendingFarmAction = PendingFarmAction.NONE,
    val consecutiveFailures: Int = 0,
    val lastError: String? = null,
    val updatedAtEpochMs: Long = 0,
) {
    val hasPendingTask: Boolean
        get() = taskId != null && phase in setOf(
            RuntimePhase.RUNNING,
            RuntimePhase.WAITING_ALARM,
            RuntimePhase.PAUSED,
            RuntimePhase.RECOVERY_REQUIRED,
        )

    fun hasAlreadySentOneClick(round: Int): Boolean =
        actionRound == round && pendingAction in setOf(
            PendingFarmAction.ONE_CLICK_SENT,
            PendingFarmAction.ONE_CLICK_CONFIRMED,
            PendingFarmAction.HARVEST_CONTINUE_SENT,
            PendingFarmAction.HARVEST_CONTINUE_CONFIRMED,
        )

    /**
     * Starts a fresh attempt of the current logical round after the failed
     * attempt has been stopped and the game process has been force-stopped.
     */
    fun scheduleFailureRetry(
        triggerAtEpochMs: Long,
        reason: String,
        nowEpochMs: Long,
    ): RuntimeCheckpoint {
        require(taskId != null)
        require(triggerAtEpochMs >= nowEpochMs)
        return copy(
            phase = RuntimePhase.WAITING_ALARM,
            generation = generation + 1,
            nextRunAtEpochMs = triggerAtEpochMs,
            wakeReason = FAILURE_RETRY_WAKE_REASON,
            actionRound = 0,
            pendingAction = PendingFarmAction.NONE,
            consecutiveFailures = consecutiveFailures + 1,
            lastError = reason.take(MAX_ERROR_LENGTH),
            updatedAtEpochMs = nowEpochMs,
        )
    }
}

/**
 * A small, synchronous checkpoint store used around irreversible game input.
 * SharedPreferences.commit() is intentional here: the state must be durable
 * before a ROOT tap is sent or an alarm is registered.
 */
class RuntimeStateStore(context: Context) {
    private val preferences = context.getSharedPreferences(
        PREFERENCES,
        Context.MODE_PRIVATE,
    )

    @Synchronized
    fun load(): RuntimeCheckpoint = RuntimeCheckpoint(
        taskId = preferences.getString(KEY_TASK_ID, null),
        phase = preferences.getString(KEY_PHASE, null)
            ?.let { runCatching { RuntimePhase.valueOf(it) }.getOrNull() }
            ?: RuntimePhase.IDLE,
        infinite = preferences.getBoolean(KEY_INFINITE, false),
        targetRounds = preferences.getInt(KEY_TARGET_ROUNDS, 0),
        completedRounds = preferences.getInt(KEY_COMPLETED_ROUNDS, 0),
        generation = preferences.getLong(KEY_GENERATION, 0),
        nextRunAtEpochMs = preferences.getLong(KEY_NEXT_RUN_AT, NO_TIME)
            .takeUnless { it == NO_TIME },
        wakeReason = preferences.getString(KEY_WAKE_REASON, null),
        actionRound = preferences.getInt(KEY_ACTION_ROUND, 0),
        pendingAction = preferences.getString(KEY_PENDING_ACTION, null)
            ?.let { runCatching { PendingFarmAction.valueOf(it) }.getOrNull() }
            ?: PendingFarmAction.NONE,
        consecutiveFailures = preferences.getInt(KEY_CONSECUTIVE_FAILURES, 0),
        lastError = preferences.getString(KEY_LAST_ERROR, null),
        updatedAtEpochMs = preferences.getLong(KEY_UPDATED_AT, 0),
    )

    @Synchronized
    fun beginTask(
        infinite: Boolean,
        targetRounds: Int,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): RuntimeCheckpoint {
        require(infinite || targetRounds in 1..99)
        return RuntimeCheckpoint(
            taskId = UUID.randomUUID().toString(),
            phase = RuntimePhase.RUNNING,
            infinite = infinite,
            targetRounds = if (infinite) 0 else targetRounds,
            updatedAtEpochMs = nowEpochMs,
        ).also(::write)
    }

    @Synchronized
    fun markRoundRunning(
        expectedTaskId: String,
        round: Int,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): RuntimeCheckpoint = update(expectedTaskId) { current ->
        require(round == current.completedRounds + 1) {
            "Unexpected round $round after ${current.completedRounds} completed rounds"
        }
        current.copy(
            phase = RuntimePhase.RUNNING,
            nextRunAtEpochMs = null,
            wakeReason = null,
            actionRound = if (current.actionRound == round) round else 0,
            pendingAction = if (current.actionRound == round) {
                current.pendingAction
            } else {
                PendingFarmAction.NONE
            },
            updatedAtEpochMs = nowEpochMs,
        )
    }

    /**
     * Returns false when this round already crossed the durable send boundary.
     * The caller must not issue another one-click-farm tap in that case.
     */
    @Synchronized
    fun markOneClickSendIntent(
        expectedTaskId: String,
        round: Int,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): Boolean {
        val current = requireTask(expectedTaskId)
        if (current.hasAlreadySentOneClick(round)) return false
        write(
            current.copy(
                actionRound = round,
                pendingAction = PendingFarmAction.ONE_CLICK_SENT,
                updatedAtEpochMs = nowEpochMs,
            ),
        )
        return true
    }

    @Synchronized
    fun markOneClickConfirmed(
        expectedTaskId: String,
        round: Int,
        nowEpochMs: Long = System.currentTimeMillis(),
    ) = update(expectedTaskId) { current ->
        require(current.actionRound == round)
        require(current.pendingAction == PendingFarmAction.ONE_CLICK_SENT)
        current.copy(
            pendingAction = PendingFarmAction.ONE_CLICK_CONFIRMED,
            updatedAtEpochMs = nowEpochMs,
        )
    }

    @Synchronized
    fun markRoundCompleted(
        expectedTaskId: String,
        completedRounds: Int,
        nowEpochMs: Long = System.currentTimeMillis(),
    ) = update(expectedTaskId) { current ->
        require(completedRounds == current.completedRounds + 1)
        current.copy(
            completedRounds = completedRounds,
            pendingAction = PendingFarmAction.NONE,
            actionRound = 0,
            consecutiveFailures = 0,
            lastError = null,
            updatedAtEpochMs = nowEpochMs,
        )
    }

    @Synchronized
    fun scheduleNext(
        expectedTaskId: String,
        triggerAtEpochMs: Long,
        wakeReason: String,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): RuntimeCheckpoint = update(expectedTaskId) { current ->
        require(triggerAtEpochMs >= nowEpochMs)
        current.copy(
            phase = RuntimePhase.WAITING_ALARM,
            generation = current.generation + 1,
            nextRunAtEpochMs = triggerAtEpochMs,
            wakeReason = wakeReason,
            updatedAtEpochMs = nowEpochMs,
        )
    }

    @Synchronized
    fun scheduleFailureRetry(
        expectedTaskId: String,
        triggerAtEpochMs: Long,
        reason: String,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): RuntimeCheckpoint = update(expectedTaskId) { current ->
        current.scheduleFailureRetry(
            triggerAtEpochMs = triggerAtEpochMs,
            reason = reason,
            nowEpochMs = nowEpochMs,
        )
    }

    @Synchronized
    fun markCompleted(
        expectedTaskId: String,
        nowEpochMs: Long = System.currentTimeMillis(),
    ) = update(expectedTaskId) { current ->
        current.copy(
            phase = RuntimePhase.COMPLETED,
            nextRunAtEpochMs = null,
            wakeReason = null,
            pendingAction = PendingFarmAction.NONE,
            actionRound = 0,
            updatedAtEpochMs = nowEpochMs,
        )
    }

    @Synchronized
    fun markFailure(
        expectedTaskId: String,
        reason: String,
        recoverable: Boolean,
        nowEpochMs: Long = System.currentTimeMillis(),
    ) = update(expectedTaskId) { current ->
        current.copy(
            phase = if (recoverable) RuntimePhase.RECOVERY_REQUIRED else RuntimePhase.ERROR,
            nextRunAtEpochMs = null,
            wakeReason = null,
            consecutiveFailures = current.consecutiveFailures + 1,
            lastError = reason.take(MAX_ERROR_LENGTH),
            updatedAtEpochMs = nowEpochMs,
        )
    }

    @Synchronized
    fun markPaused(
        expectedTaskId: String,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): RuntimeCheckpoint = update(expectedTaskId) { current ->
        current.copy(
            phase = RuntimePhase.PAUSED,
            generation = current.generation + 1,
            updatedAtEpochMs = nowEpochMs,
        )
    }

    @Synchronized
    fun clear(nowEpochMs: Long = System.currentTimeMillis()) {
        write(RuntimeCheckpoint(updatedAtEpochMs = nowEpochMs))
    }

    @Synchronized
    private fun update(
        expectedTaskId: String,
        transform: (RuntimeCheckpoint) -> RuntimeCheckpoint,
    ): RuntimeCheckpoint = transform(requireTask(expectedTaskId)).also(::write)

    private fun requireTask(expectedTaskId: String): RuntimeCheckpoint {
        val current = load()
        check(current.taskId == expectedTaskId) {
            "Runtime task changed while automation was running"
        }
        return current
    }

    private fun write(value: RuntimeCheckpoint) {
        check(
            preferences.edit()
                .clear()
                .putInt(KEY_SCHEMA_VERSION, SCHEMA_VERSION)
                .putString(KEY_TASK_ID, value.taskId)
                .putString(KEY_PHASE, value.phase.name)
                .putBoolean(KEY_INFINITE, value.infinite)
                .putInt(KEY_TARGET_ROUNDS, value.targetRounds)
                .putInt(KEY_COMPLETED_ROUNDS, value.completedRounds)
                .putLong(KEY_GENERATION, value.generation)
                .putLong(KEY_NEXT_RUN_AT, value.nextRunAtEpochMs ?: NO_TIME)
                .putString(KEY_WAKE_REASON, value.wakeReason)
                .putInt(KEY_ACTION_ROUND, value.actionRound)
                .putString(KEY_PENDING_ACTION, value.pendingAction.name)
                .putInt(KEY_CONSECUTIVE_FAILURES, value.consecutiveFailures)
                .putString(KEY_LAST_ERROR, value.lastError)
                .putLong(KEY_UPDATED_AT, value.updatedAtEpochMs)
                .commit(),
        ) { "Unable to persist automation runtime checkpoint" }
    }

    private companion object {
        const val PREFERENCES = "automation_runtime_state"
        const val SCHEMA_VERSION = 1
        const val NO_TIME = -1L
        const val KEY_SCHEMA_VERSION = "schema_version"
        const val KEY_TASK_ID = "task_id"
        const val KEY_PHASE = "phase"
        const val KEY_INFINITE = "infinite"
        const val KEY_TARGET_ROUNDS = "target_rounds"
        const val KEY_COMPLETED_ROUNDS = "completed_rounds"
        const val KEY_GENERATION = "generation"
        const val KEY_NEXT_RUN_AT = "next_run_at"
        const val KEY_WAKE_REASON = "wake_reason"
        const val KEY_ACTION_ROUND = "action_round"
        const val KEY_PENDING_ACTION = "pending_action"
        const val KEY_CONSECUTIVE_FAILURES = "consecutive_failures"
        const val KEY_LAST_ERROR = "last_error"
        const val KEY_UPDATED_AT = "updated_at"
    }
}

const val FAILURE_RETRY_WAKE_REASON = "FAILURE_RETRY"
private const val MAX_ERROR_LENGTH = 1_000
