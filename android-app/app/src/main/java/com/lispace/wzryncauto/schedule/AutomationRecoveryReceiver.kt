package com.lispace.wzryncauto.schedule

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class AutomationRecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in SUPPORTED_ACTIONS) return
        val checkpoint = RuntimeStateStore(context).load()
        if (checkpoint.phase != RuntimePhase.WAITING_ALARM ||
            checkpoint.nextRunAtEpochMs == null
        ) {
            return
        }
        runCatching { AutomationAlarmScheduler(context).schedule(checkpoint) }
            .onFailure { error ->
                checkpoint.taskId?.let { taskId ->
                    RuntimeStateStore(context).markFailure(
                        taskId,
                        "系统恢复定时任务失败：${error.message}",
                        recoverable = true,
                    )
                }
            }
    }

    private companion object {
        val SUPPORTED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
        )
    }
}
