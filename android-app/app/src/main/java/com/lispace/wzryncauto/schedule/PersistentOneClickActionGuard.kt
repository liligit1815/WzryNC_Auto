package com.lispace.wzryncauto.schedule

import com.lispace.wzryncauto.automation.OneClickActionGuard
import com.lispace.wzryncauto.automation.VerifiedActionTarget
import java.time.LocalDateTime

class PersistentOneClickActionGuard(
    private val store: RuntimeStateStore,
    private val taskId: String,
    private val round: Int,
) : OneClickActionGuard {
    override suspend fun beforeTap(target: VerifiedActionTarget): Boolean =
        store.markOneClickSendIntent(taskId, round)

    override suspend fun afterTapAccepted(acceptedAt: LocalDateTime) {
        store.markOneClickConfirmed(taskId, round)
    }
}
