package com.lispace.wzryncauto.schedule

import java.time.Duration
import java.time.LocalDateTime

data class FarmWateringEvidence(
    val batchCompatible: Boolean,
    val wateringConfirmed: Boolean,
    val reason: String,
)

/** Compares observations without treating a button tap as a successful watering. */
object FarmWateringPolicy {
    const val OCR_TOLERANCE_SECONDS = 60L

    fun evaluate(
        cycleMinutes: Int,
        batchStartedAt: LocalDateTime,
        previousMaturityAt: LocalDateTime?,
        previousObservedAt: LocalDateTime,
        observedMaturityAt: LocalDateTime,
        observedAt: LocalDateTime,
        lastConfirmedWateringAt: LocalDateTime? = null,
        lastAttemptAt: LocalDateTime? = null,
    ): FarmWateringEvidence {
        fun incompatible(reason: String) = FarmWateringEvidence(false, false, reason)
        if (cycleMinutes !in setOf(5, 60, 480, 960, 1920)) {
            return incompatible("作物周期不受支持")
        }
        if (batchStartedAt.isAfter(previousObservedAt) ||
            previousObservedAt.isAfter(observedAt) ||
            lastConfirmedWateringAt?.let {
                it.isBefore(batchStartedAt) || it.isAfter(observedAt)
            } == true ||
            lastAttemptAt?.let {
                it.isBefore(batchStartedAt) || it.isAfter(observedAt)
            } == true ||
            (lastConfirmedWateringAt != null && lastAttemptAt != null &&
                lastConfirmedWateringAt.isAfter(lastAttemptAt))
        ) {
            return incompatible("作物记录时间顺序异常")
        }
        val cycleSeconds = cycleMinutes * 60L
        if (Duration.between(batchStartedAt, observedAt) > Duration.ofSeconds(cycleSeconds * 2L)) {
            return incompatible("作物记录已超过两个原始周期")
        }
        if (observedMaturityAt.isBefore(batchStartedAt.minusSeconds(OCR_TOLERANCE_SECONDS)) ||
            observedMaturityAt.isAfter(batchStartedAt.plusSeconds(cycleSeconds + OCR_TOLERANCE_SECONDS))
        ) {
            return incompatible("成熟时间超出当前作物周期")
        }
        if (previousMaturityAt == null) {
            return FarmWateringEvidence(true, false, "缺少上次成熟时间，尚不能确认浇水生效")
        }
        if (previousMaturityAt.isBefore(batchStartedAt.minusSeconds(OCR_TOLERANCE_SECONDS)) ||
            previousMaturityAt.isAfter(batchStartedAt.plusSeconds(cycleSeconds + OCR_TOLERANCE_SECONDS))
        ) {
            return incompatible("上次成熟时间超出当前作物周期")
        }

        val advancement = Duration.between(observedMaturityAt, previousMaturityAt)
        val tolerance = Duration.ofSeconds(OCR_TOLERANCE_SECONDS)
        if (advancement < tolerance.negated()) {
            return incompatible("成熟时间明显变晚，需重新确认作物")
        }
        // A full watering reduces T / 12: for a 32-hour crop this is 160 minutes.
        if (advancement > Duration.ofSeconds(cycleSeconds / 12L + OCR_TOLERANCE_SECONDS)) {
            return incompatible("成熟时间变化超过单次浇水上限")
        }
        if (lastConfirmedWateringAt != null && lastAttemptAt != null) {
            val sinceConfirmed = Duration.between(lastConfirmedWateringAt, lastAttemptAt)
            if (sinceConfirmed < Duration.ofSeconds(cycleSeconds / 30L)) {
                return FarmWateringEvidence(true, false, "未达到最短有效浇水间隔，保留上次有效浇水时间")
            }
            val possibleReduction = minOf(sinceConfirmed, Duration.ofSeconds(cycleSeconds / 3L))
                .dividedBy(4)
            if (advancement > possibleReduction.plus(tolerance)) {
                return FarmWateringEvidence(true, false, "提前幅度超过本次间隔可产生的减时，尚不能归因于本次浇水")
            }
        }
        val confirmed = advancement > tolerance
        return FarmWateringEvidence(
            batchCompatible = true,
            wateringConfirmed = confirmed,
            reason = if (confirmed) "成熟时间已明显提前，确认浇水生效" else "变化未超过分钟误差，保留上次有效浇水时间",
        )
    }
}
