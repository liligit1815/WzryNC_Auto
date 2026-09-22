package com.lispace.wzryncauto.schedule

import com.lispace.wzryncauto.ocr.MaturityReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.LocalDateTime

class FarmScheduleCalculatorTest {
    private val start = LocalDateTime.of(2026, 9, 22, 10, 0)
    private val cycles = listOf(5, 60, 480, 960, 1920)

    @Test
    fun allCropCyclesFollowFourIdealWateringsWithoutReclassifying() {
        for (cycle in cycles) {
            val total = cycle * 60L
            var lastWater = start
            var maturity = start.plusSeconds(total * 11L / 12L)
            for (offset in listOf(total / 3L, total * 2L / 3L, total * 11L / 15L)) {
                val schedule = FarmScheduleCalculator.calculate(
                    firstWaterAt = lastWater,
                    observedMaturityAt = maturity,
                    now = lastWater.plusSeconds(1),
                    storedCycleMinutes = cycle,
                    batchStartedAt = start,
                    lastConfirmedWateringAt = lastWater,
                )
                val next = start.plusSeconds(offset)
                assertEquals("cycle=$cycle offset=$offset", next, schedule.targetAt)
                assertEquals(WakeReason.WATERING, schedule.reason)
                assertEquals(cycle, schedule.cycleMinutes)
                assertFalse(schedule.cycleEstimated)
                assertEquals(start, schedule.batchStartedAt)
                val elapsed = Duration.between(lastWater, next).seconds.coerceAtMost(total / 3L)
                maturity = maturity.minusSeconds(elapsed / 4L)
                lastWater = next
            }
            assertEquals("fourth watering matures cycle=$cycle", lastWater, maturity)
        }
    }

    @Test
    fun identifiesFreshBatchFromFirstWaterReductionForEveryCycle() {
        for (cycle in cycles) {
            val schedule = FarmScheduleCalculator.calculate(
                firstWaterAt = start,
                observedMaturityAt = start.plusSeconds(cycle * 60L * 11L / 12L).minusSeconds(37),
                now = start.plusSeconds(15),
                freshBatch = true,
            )
            assertEquals(cycle, schedule.cycleMinutes)
            assertFalse(schedule.cycleEstimated)
        }
    }

    @Test
    fun freshBatchReplacesPreviousCropAndBatchStart() {
        val schedule = FarmScheduleCalculator.calculate(
            firstWaterAt = start,
            observedMaturityAt = start.plusMinutes(55),
            now = start,
            storedCycleMinutes = 480,
            batchStartedAt = start.minusHours(5),
            freshBatch = true,
        )
        assertEquals(60, schedule.cycleMinutes)
        assertEquals(start, schedule.batchStartedAt)
        assertFalse(schedule.cycleEstimated)
    }

    @Test
    fun unrecognizedFreshRemainderIsStillMarkedEstimated() {
        val schedule = FarmScheduleCalculator.calculate(
            firstWaterAt = start,
            observedMaturityAt = start.plusMinutes(30),
            now = start,
            freshBatch = true,
        )
        assertEquals(60, schedule.cycleMinutes)
        assertTrue(schedule.cycleEstimated)
    }

    @Test
    fun unknownMidBatchUsesContainingCycleAndMarksItEstimated() {
        val schedule = FarmScheduleCalculator.calculate(
            firstWaterAt = start,
            observedMaturityAt = start.plusMinutes(25),
            now = start,
        )
        assertEquals(60, schedule.cycleMinutes)
        assertTrue(schedule.cycleEstimated)
    }

    @Test
    fun keepsStoredLongCycleEvenWithShortRemainder() {
        val batch = start.minusHours(7)
        val schedule = FarmScheduleCalculator.calculate(
            firstWaterAt = start,
            observedMaturityAt = start.plusMinutes(25),
            now = start,
            storedCycleMinutes = 480,
            batchStartedAt = batch,
        )
        assertEquals(480, schedule.cycleMinutes)
        assertEquals(batch, schedule.batchStartedAt)
        assertFalse(schedule.cycleEstimated)
    }

    @Test
    fun doesNotPromotePersistedEstimateToConfirmedCycle() {
        assertTrue(schedule(storedCycleEstimated = true).cycleEstimated)
    }

    @Test
    fun earlyArrivalKeepsUpcomingNodeWhenWakeWindowHasPassed() {
        val result = schedule(now = start.plusMinutes(19))
        assertEquals(start.plusMinutes(20), result.targetAt)
        assertEquals(start.plusMinutes(19), result.wakeAt)
    }

    @Test
    fun lateArrivalKeepsOverdueWateringForImmediateWake() {
        val result = schedule(now = start.plusMinutes(22))
        assertEquals(start.plusMinutes(20), result.targetAt)
        assertEquals(start.plusMinutes(22), result.wakeAt)
        assertEquals(WakeReason.WATERING, result.reason)
    }

    @Test
    fun delayedWateringShiftsNextFullWateringFromItsConfirmedTime() {
        val result = schedule(
            water = start.plusMinutes(22),
            maturity = start.plusMinutes(50),
            now = start.plusMinutes(23),
        )
        assertEquals(start.plusMinutes(42), result.targetAt)
        assertEquals(start.plusMinutes(40), result.watering3At)
    }

    @Test
    fun finalWateringSolvesRemainingTimeAfterEarlierDelay() {
        val result = schedule(
            water = start.plusMinutes(42),
            maturity = start.plusMinutes(45),
            now = start.plusMinutes(42).plusSeconds(10),
        )
        assertEquals(start.plusMinutes(44).plusSeconds(24), result.targetAt)
        assertEquals(WakeReason.WATERING, result.reason)
    }

    @Test
    fun extraEffectiveWateringStartsNewFullWaterInterval() {
        val result = schedule(
            water = start.plusMinutes(10),
            maturity = start.plusMinutes(52).plusSeconds(30),
            now = start.plusMinutes(11),
        )
        assertEquals(start.plusMinutes(30), result.targetAt)
    }

    @Test
    fun ineffectiveAttemptOnlyAppliesMinimumInterval() {
        val result = schedule(
            water = start.plusMinutes(10),
            attempt = start.plusMinutes(29),
            maturity = start.plusMinutes(52).plusSeconds(30),
            now = start.plusMinutes(29).plusSeconds(10),
        )
        assertEquals(start.plusMinutes(31), result.targetAt)
    }

    @Test
    fun selectsNaturalMaturityIfMinimumIntervalCannotBeMet() {
        val result = schedule(maturity = start.plusSeconds(90))
        assertEquals(start.plusSeconds(90), result.targetAt)
        assertEquals(WakeReason.MATURITY, result.reason)
        assertEquals(null, result.nextWateringAt)
    }

    @Test
    fun minuteOnlyMaturityAlreadyDueNeverSchedulesWateringOrTomorrow() {
        val result = schedule(maturity = start, now = start.plusSeconds(20))
        assertEquals(start, result.targetAt)
        assertEquals(start.plusSeconds(20), result.wakeAt)
        assertEquals(WakeReason.MATURITY, result.reason)
    }

    @Test
    fun roundsFinalWateringUpIncludingFractionalSeconds() {
        val result = schedule(maturity = start.plusSeconds(181).plusNanos(900_000_000))
        assertEquals(start.plusSeconds(146), result.targetAt)
        assertFalse(result.targetAt.isAfter(result.observedMaturityAt))
    }

    @Test
    fun minutePrecisionProtectsFinalWateringWithoutChangingObservation() {
        val maturity = start.plusMinutes(45)
        val result = schedule(
            water = start.plusMinutes(40), maturity = maturity,
            now = start.plusMinutes(41), precision = 59,
        )
        assertEquals(maturity, result.observedMaturityAt)
        assertEquals(start.plusMinutes(44).plusSeconds(48), result.targetAt)
    }

    @Test
    fun minutePrecisionDoesNotChangeFreshCropClassification() {
        for (cycle in cycles) {
            val result = FarmScheduleCalculator.calculate(
                firstWaterAt = start,
                observedMaturityAt = start.plusSeconds(cycle * 60L * 11L / 12L - 50L),
                now = start,
                freshBatch = true,
                maturityPrecisionSeconds = 59,
            )
            assertEquals(cycle, result.cycleMinutes)
            assertFalse(result.cycleEstimated)
        }
    }

    @Test
    fun wakeLeadChangesStartupButNotWateringTarget() {
        val early = schedule(wakeLead = 120)
        val later = schedule(wakeLead = 61)
        assertEquals(early.targetAt, later.targetAt)
        assertEquals(start.plusMinutes(18), early.wakeAt)
        assertEquals(start.plusMinutes(18).plusSeconds(59), later.wakeAt)
    }

    @Test
    fun resolvesImplicitCrossDayMaturity() {
        val observed = start.withHour(23).withMinute(58)
        val reading = MaturityReading.Time(0, 2, false, "00:02成熟", observedAt = observed)
        assertEquals(
            start.plusDays(1).withHour(0).withMinute(2),
            FarmScheduleCalculator.resolveObservedMaturity(reading, observed),
        )
    }

    @Test
    fun keepsMaturityWithinCurrentMinuteOnSameDay() {
        val observed = start.plusSeconds(50)
        val reading = MaturityReading.Time(10, 0, false, "10:00成熟", observedAt = observed)
        assertEquals(start, FarmScheduleCalculator.resolveObservedMaturity(reading, observed))
    }

    @Test
    fun explicitTodayInPastIsNeverShiftedToTomorrow() {
        val observed = start.plusHours(1)
        val reading = MaturityReading.Time(10, 0, false, "今天10:00成熟", observedAt = observed)
        assertEquals(start, FarmScheduleCalculator.resolveObservedMaturity(reading, observed))
    }

    @Test
    fun anchorsDayHintsToObservationDateEvenWhenClickWasYesterday() {
        val observed = start.plusDays(1).withHour(0).withMinute(1)
        for (offset in 1..2) {
            val reading = MaturityReading.Time(
                8, 30, true, if (offset == 1) "明天08:30成熟" else "后天08:30成熟",
                dayOffset = offset, observedAt = observed,
            )
            assertEquals(
                observed.plusDays(offset.toLong()).withHour(8).withMinute(30),
                FarmScheduleCalculator.resolveObservedMaturity(reading, start.withHour(23).withMinute(59)),
            )
        }
    }

    @Test
    fun resolvesRelativeMaturityFromObservationRatherThanClick() {
        val observed = start.withHour(23).withMinute(30).withSecond(12)
        val reading = MaturityReading.Time(
            1, 45, true, "2小时15分钟后成熟",
            dayOffset = 1, relativeMinutes = 135, observedAt = observed,
        )
        assertEquals(
            observed.plusMinutes(135),
            FarmScheduleCalculator.resolveObservedMaturity(reading, observed.minusMinutes(2)),
        )
    }

    private fun schedule(
        water: LocalDateTime = start,
        attempt: LocalDateTime = water,
        maturity: LocalDateTime = start.plusMinutes(55),
        now: LocalDateTime = water,
        wakeLead: Long = 120,
        storedCycleEstimated: Boolean = false,
        precision: Long = 0,
    ): FarmSchedule = FarmScheduleCalculator.calculate(
        firstWaterAt = attempt,
        observedMaturityAt = maturity,
        now = now,
        storedCycleMinutes = 60,
        batchStartedAt = start,
        wakeLeadSeconds = wakeLead,
        lastConfirmedWateringAt = water,
        lastAttemptAt = attempt,
        storedCycleEstimated = storedCycleEstimated,
        maturityPrecisionSeconds = precision,
    )
}
