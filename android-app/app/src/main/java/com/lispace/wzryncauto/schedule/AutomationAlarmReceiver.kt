package com.lispace.wzryncauto.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.lispace.wzryncauto.service.OverlayService

class AutomationAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AutomationAlarmScheduler.ACTION_RUN_DUE) return
        val generation = intent.getLongExtra(
            AutomationAlarmScheduler.EXTRA_GENERATION,
            Long.MIN_VALUE,
        )
        if (generation == Long.MIN_VALUE) return
        Log.i(LOG_TAG, "system alarm delivered, generation=$generation")
        val handoffWakeLock = context.getSystemService(PowerManager::class.java).newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "${context.packageName}:alarm-handoff",
        ).apply {
            setReferenceCounted(false)
            acquire(HANDOFF_WAKE_LOCK_MS)
        }
        runCatching {
            ContextCompat.startForegroundService(
                context,
                Intent(context, OverlayService::class.java).apply {
                    action = OverlayService.ACTION_RUN_DUE
                    putExtra(OverlayService.EXTRA_RUNTIME_GENERATION, generation)
                },
            )
        }.onFailure { error ->
            if (handoffWakeLock.isHeld) handoffWakeLock.release()
            Log.e(LOG_TAG, "unable to start automation service", error)
            RuntimeStateStore(context).load().taskId?.let { taskId ->
                runCatching {
                    RuntimeStateStore(context).markFailure(
                        taskId,
                        "系统闹钟已到达，但启动自动化服务失败：${error.message}",
                        recoverable = true,
                    )
                }
            }
        }
    }

    private companion object {
        const val LOG_TAG = "WzryAlarm"
        const val HANDOFF_WAKE_LOCK_MS = 30_000L
    }
}
