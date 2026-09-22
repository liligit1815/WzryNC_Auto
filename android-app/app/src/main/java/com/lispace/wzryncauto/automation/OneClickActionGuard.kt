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

    /** Persist direct harvest evidence before dismissing its reward modal. */
    suspend fun onHarvestObserved() = Unit

    /** A distinct, bounded harvest after watering has visibly matured a crop. */
    suspend fun beforeMaturityHarvest(target: VerifiedActionTarget): Boolean = false

    suspend fun afterMaturityHarvestAccepted(acceptedAt: LocalDateTime) = Unit
}

object AllowOneClickActionGuard : OneClickActionGuard {
    override suspend fun beforeTap(target: VerifiedActionTarget): Boolean = true

    override suspend fun afterTapAccepted(acceptedAt: LocalDateTime) = Unit

    override suspend fun beforeMaturityHarvest(target: VerifiedActionTarget): Boolean = true
}
