package com.lispace.wzryncauto.schedule

import com.lispace.wzryncauto.ocr.MaturityReading
import java.time.Duration
import java.time.LocalDateTime

data class FarmSchedule(
    val cycleMinutes: Int,
    val observedMaturityAt: LocalDateTime,
    val batchStartedAt: LocalDateTime,
    val watering2At: LocalDateTime,
    val watering3At: LocalDateTime,
    val watering4At: LocalDateTime,
    val nextWateringAt: LocalDateTime?,
    val targetAt: LocalDateTime,
    val wakeAt: LocalDateTime,
    val reason: WakeReason,
    val cycleEstimated: Boolean = false,
)

enum class WakeReason { WATERING, MATURITY }

object FarmScheduleCalculator {
    private val cropRules = listOf(
        CropRule(cycleMinutes = 5),
        CropRule(cycleMinutes = 60),
        CropRule(cycleMinutes = 480),
        CropRule(cycleMinutes = 960),
        CropRule(cycleMinutes = 1920),
    )

    fun resolveObservedMaturity(
        reading: MaturityReading.Time,
        firstWaterAt: LocalDateTime,
    ): LocalDateTime {
        reading.relativeMinutes?.let { relativeMinutes ->
            val observedAt = reading.observedAt ?: firstWaterAt
            return observedAt.plusMinutes(relativeMinutes.toLong()).withNano(0)
        }
        val observedAt = reading.observedAt ?: firstWaterAt
        var resolved = observedAt
            .plusDays(reading.dayOffset.toLong())
            .withHour(reading.hour)
            .withMinute(reading.minute)
            .withSecond(0)
            .withNano(0)
        // A minute-only reading may name the minute that is already in progress.
        // Explicit "today" must never silently become a different day's crop.
        val withinCurrentMinute = Duration.between(resolved, observedAt).abs() <=
            Duration.ofMinutes(1)
        if (reading.dayOffset == 0 && "今天" !in reading.rawText &&
            resolved.isBefore(observedAt) && !withinCurrentMinute
        ) {
            resolved = resolved.plusDays(1)
        }
        return resolved
    }

    fun calculate(
        firstWaterAt: LocalDateTime,
        observedMaturityAt: LocalDateTime,
        now: LocalDateTime,
        storedCycleMinutes: Int? = null,
        batchStartedAt: LocalDateTime? = null,
        wakeLeadSeconds: Long = 120,
        lastConfirmedWateringAt: LocalDateTime = firstWaterAt,
        lastAttemptAt: LocalDateTime = firstWaterAt,
        freshBatch: Boolean = false,
        storedCycleEstimated: Boolean = false,
        maturityPrecisionSeconds: Long = 0,
    ): FarmSchedule {
        require(wakeLeadSeconds >= 0)
        require(maturityPrecisionSeconds >= 0)

        storedCycleMinutes?.also {
            require(cropRules.any { rule -> rule.cycleMinutes == it }) {
                "Unsupported stored crop cycle: $it"
            }
        }
        val batchStart = if (freshBatch) firstWaterAt else batchStartedAt ?: firstWaterAt
        val remainingSeconds = Duration.between(firstWaterAt, observedMaturityAt).seconds
        val freshRule = if (freshBatch) cropRules.minByOrNull {
            kotlin.math.abs(remainingSeconds - it.cycleMinutes * 60L * 11L / 12L)
        }?.takeIf {
            kotlin.math.abs(remainingSeconds - it.cycleMinutes * 60L * 11L / 12L) <=
                OCR_MINUTE_TOLERANCE_SECONDS
        } else null
        // A shortened remainder does not identify a different crop. Stored
        // state is validated by FarmWateringPolicy before it reaches this API.
        val cycle = when {
            freshRule != null -> freshRule.cycleMinutes
            !freshBatch && storedCycleMinutes != null -> storedCycleMinutes
            else -> (cropRules.firstOrNull {
                remainingSeconds <= it.cycleMinutes * 60L + OCR_MINUTE_TOLERANCE_SECONDS
            } ?: cropRules.last()).cycleMinutes
        }
        val estimated = when {
            freshRule != null -> false
            !freshBatch && storedCycleMinutes != null -> storedCycleEstimated
            else -> true
        }
        val water2 = batchStart.plusSeconds(cycle * 60L / 3L)
        val water3 = batchStart.plusSeconds(cycle * 60L * 2L / 3L)
        val water4 = batchStart.plusSeconds(cycle * 60L * 11L / 15L)

        // The game shows only HH:mm. Keep that original value for crop
        // classification and evidence, but use its upper bound before a tap.
        val maturityUpperBound = observedMaturityAt.plusSeconds(maturityPrecisionSeconds)
        val fullWaterAt = lastConfirmedWateringAt.plusSeconds(cycle * 60L / 3L)
        val remainingFromWater = Duration.between(lastConfirmedWateringAt, maturityUpperBound)
        // t - w >= 4 * (m - w) / 5 makes the reduction at t enough to mature.
        // Round upward to a whole second: never schedule a fraction too early.
        val finishSeconds = if (remainingFromWater.isNegative || remainingFromWater.isZero) {
            0L
        } else {
            val seconds = remainingFromWater.seconds
            val numerator = ((seconds % 5L) * 1_000_000_000L + remainingFromWater.nano) * 4L
            seconds / 5L * 4L + (numerator + 4_999_999_999L) / 5_000_000_000L
        }
        val finishWaterAt = lastConfirmedWateringAt.plusSeconds(finishSeconds)
        val effectiveAfter = maxOf(lastConfirmedWateringAt, lastAttemptAt)
            .plusSeconds(cycle * 60L / 30L)
        val wateringTarget = maxOf(minOf(fullWaterAt, finishWaterAt), effectiveAfter)
        val nextWatering = wateringTarget.takeIf {
            observedMaturityAt.isAfter(now) && it.isBefore(maturityUpperBound)
        }
        val target = nextWatering ?: maturityUpperBound
        val reason = if (nextWatering != null) WakeReason.WATERING else WakeReason.MATURITY
        val proposedWake = target.minusSeconds(wakeLeadSeconds)
        val wake = if (proposedWake.isAfter(now)) proposedWake else now

        check(!target.isAfter(maturityUpperBound)) {
            "Schedule target exceeded OCR maturity safety limit"
        }
        return FarmSchedule(
            cycleMinutes = cycle,
            observedMaturityAt = observedMaturityAt,
            batchStartedAt = batchStart,
            watering2At = water2,
            watering3At = water3,
            watering4At = water4,
            nextWateringAt = nextWatering,
            targetAt = target,
            wakeAt = wake,
            reason = reason,
            cycleEstimated = estimated,
        )
    }

    private data class CropRule(val cycleMinutes: Int)

    private const val OCR_MINUTE_TOLERANCE_SECONDS = 60L
}
