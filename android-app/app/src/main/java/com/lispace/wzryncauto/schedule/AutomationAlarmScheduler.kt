package com.lispace.wzryncauto.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

data class AlarmRegistration(
    val triggerAtEpochMs: Long,
    val exact: Boolean,
)

class AutomationAlarmScheduler(private val context: Context) {
    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    fun schedule(checkpoint: RuntimeCheckpoint): AlarmRegistration {
        require(checkpoint.phase == RuntimePhase.WAITING_ALARM)
        val triggerAt = requireNotNull(checkpoint.nextRunAtEpochMs)
        val operation = pendingIntent(checkpoint.generation)
        check(canScheduleExact()) {
            "精确闹钟未授权，不能可靠安排无人值守任务"
        }
        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            triggerAt,
            operation,
        )
        return AlarmRegistration(triggerAt, exact = true)
    }

    fun cancel() {
        alarmManager.cancel(pendingIntent(generation = 0))
    }

    fun canScheduleExact(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()

    private fun pendingIntent(generation: Long): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, AutomationAlarmReceiver::class.java).apply {
            action = ACTION_RUN_DUE
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            putExtra(EXTRA_GENERATION, generation)
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        const val ACTION_RUN_DUE = "com.lispace.wzryncauto.RUN_DUE"
        const val EXTRA_GENERATION = "runtime_generation"
        private const val REQUEST_CODE = 19081
    }
}
