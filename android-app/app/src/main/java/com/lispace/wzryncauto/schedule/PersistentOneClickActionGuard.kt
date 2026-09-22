package com.lispace.wzryncauto.schedule

import com.lispace.wzryncauto.automation.OneClickActionGuard
import com.lispace.wzryncauto.automation.VerifiedActionTarget
import java.time.LocalDateTime
import java.time.ZoneId

class PersistentOneClickActionGuard(
    private val store: RuntimeStateStore,
    private val taskId: String,
    private val round: Int,
) : OneClickActionGuard {
    override suspend fun beforeTap(target: VerifiedActionTarget): Boolean =
        store.markOneClickSendIntent(taskId, round)

    override suspend fun afterTapAccepted(acceptedAt: LocalDateTime) {
        store.markOneClickConfirmed(taskId, round, acceptedAt.toEpochMillis())
    }

    override suspend fun onHarvestObserved() {
        store.markHarvestObserved(taskId, round)
    }

    override suspend fun beforeMaturityHarvest(target: VerifiedActionTarget): Boolean =
        store.markMaturityHarvestSendIntent(taskId, round)

    override suspend fun afterMaturityHarvestAccepted(acceptedAt: LocalDateTime) {
        store.markMaturityHarvestConfirmed(taskId, round, acceptedAt.toEpochMillis())
    }

    private fun LocalDateTime.toEpochMillis(): Long =
        atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
}
