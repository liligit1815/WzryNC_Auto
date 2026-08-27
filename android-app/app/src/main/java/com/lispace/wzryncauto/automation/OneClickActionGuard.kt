package com.lispace.wzryncauto.automation

import java.time.LocalDateTime

data class VerifiedActionTarget(
    val label: String,
    val centerX: Int,
    val centerY: Int,
)

interface OneClickActionGuard {
    /** Must durably record the send boundary before returning true. */
    suspend fun beforeTap(target: VerifiedActionTarget): Boolean

    suspend fun afterTapAccepted(acceptedAt: LocalDateTime)
}

object AllowOneClickActionGuard : OneClickActionGuard {
    override suspend fun beforeTap(target: VerifiedActionTarget): Boolean = true

    override suspend fun afterTapAccepted(acceptedAt: LocalDateTime) = Unit
}
