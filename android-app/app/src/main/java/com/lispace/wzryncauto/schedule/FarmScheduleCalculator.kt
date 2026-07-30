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
)

enum class WakeReason { WATERING, MATURITY }

object FarmScheduleCalculator {
    private val cropRules = listOf(
        CropRule(cycleMinutes = 5, remainingAfterFirstWater = 5),
        CropRule(cycleMinutes = 60, remainingAfterFirstWater = 55),
        CropRule(cycleMinutes = 480, remainingAfterFirstWater = 400),
        CropRule(cycleMinutes = 960, remainingAfterFirstWater = 800),
        CropRule(cycleMinutes = 1920, remainingAfterFirstWater = 1600),
    )

    fun resolveObservedMaturity(
        reading: MaturityReading.Time,
        firstWaterAt: LocalDateTime,
    ): LocalDateTime {
        var resolved = firstWaterAt
            .withHour(reading.hour)
            .withMinute(reading.minute)
            .withSecond(0)
            .withNano(0)
        if (reading.tomorrowHint || !resolved.isAfter(firstWaterAt)) {
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
    ): FarmSchedule {
        require(observedMaturityAt.isAfter(firstWaterAt)) {
            "OCR maturity must be after first watering"
        }
        require(wakeLeadSeconds >= 0)

        storedCycleMinutes?.also {
            require(cropRules.any { rule -> rule.cycleMinutes == it }) {
                "Unsupported stored crop cycle: $it"
            }
        }
        // The app may be installed halfway through an existing crop batch.
        // Remaining time can only decrease, so nearest-distance matching can
        // incorrectly turn (for example) a 25-minute remainder into a 5-minute
        // crop. Keep stored state only while it is compatible with the OCR
        // remainder; otherwise choose the smallest cycle that can contain it.
        val batchStart = batchStartedAt ?: firstWaterAt
        val classificationMinutes = Duration.between(batchStart, observedMaturityAt)
            .toMillis() / 60_000.0
        val inferredRule = cropRules.firstOrNull {
            classificationMinutes <= it.remainingAfterFirstWater + OCR_MINUTE_TOLERANCE
        } ?: cropRules.last()
        // Persisted state is only a hint. Always let the current OCR remainder
        // correct a previously misclassified longer cycle (for example, a
        // stored 480-minute cycle for a roughly 51-minute batch). Classification
        // uses the known batch start rather than only the latest watering time,
        // so a nearly mature crop is not mistaken for a shorter crop.
        val cycle = inferredRule.cycleMinutes
        val water2 = batchStart.plusSeconds(cycle * 60L / 3L)
        val water3 = batchStart.plusSeconds(cycle * 60L * 2L / 3L)
        val water4 = batchStart.plusSeconds(cycle * 60L * 11L / 15L)
        val nextWatering = listOf(water2, water3, water4)
            .firstOrNull {
                it.isBefore(observedMaturityAt) &&
                    it.minusSeconds(wakeLeadSeconds).isAfter(now)
            }
        val target = nextWatering ?: observedMaturityAt
        val reason = if (nextWatering != null) WakeReason.WATERING else WakeReason.MATURITY
        val proposedWake = target.minusSeconds(wakeLeadSeconds)
        val wake = if (proposedWake.isAfter(now)) proposedWake else now

        check(!target.isAfter(observedMaturityAt)) {
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
        )
    }

    private data class CropRule(
        val cycleMinutes: Int,
        val remainingAfterFirstWater: Int,
    )

    /**
     * Maturity OCR contains hours and minutes but no seconds. The first-water
     * timestamp is captured with seconds, and the game UI can update a little
     * later than the tap. A wider boundary prevents a 60-minute crop with a
     * roughly 56-minute reading from falling through to the 480-minute rule.
     */
    private const val OCR_MINUTE_TOLERANCE = 3.0
}
