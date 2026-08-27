package com.lispace.wzryncauto.schedule

import com.lispace.wzryncauto.ocr.MaturityReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class FarmScheduleCalculatorTest {
    @Test
    fun resolvesCrossDayMaturity() {
        val first = time(2026, 7, 27, 23, 58)
        val reading = MaturityReading.Time(0, 2, false, "00:02成熟")
        assertEquals(
            time(2026, 7, 28, 0, 2),
            FarmScheduleCalculator.resolveObservedMaturity(reading, first),
        )
    }

    @Test
    fun resolvesExplicitDayAfterMaturity() {
        val first = time(2026, 7, 27, 10, 0)
        val reading = MaturityReading.Time(
            hour = 8,
            minute = 30,
            tomorrowHint = true,
            rawText = "后天08:30成熟",
            dayOffset = 2,
        )

        assertEquals(
            time(2026, 7, 29, 8, 30),
            FarmScheduleCalculator.resolveObservedMaturity(reading, first),
        )
    }

    @Test
    fun anchorsExplicitDayOffsetToOcrDateAcrossMidnight() {
        val observedAt = time(2026, 7, 28, 0, 1)
        val reading = MaturityReading.Time(
            hour = 8,
            minute = 30,
            tomorrowHint = true,
            rawText = "明天08:30成熟",
            dayOffset = 1,
            observedAt = observedAt,
        )

        assertEquals(
            time(2026, 7, 29, 8, 30),
            FarmScheduleCalculator.resolveObservedMaturity(
                reading,
                firstWaterAt = time(2026, 7, 27, 23, 59),
            ),
        )
    }

    @Test
    fun resolvesRelativeMaturityFromOcrObservationTime() {
        val observedAt = time(2026, 7, 27, 23, 30, 12)
        val reading = MaturityReading.Time(
            hour = 1,
            minute = 45,
            tomorrowHint = true,
            rawText = "2小时15分钟后成熟",
            dayOffset = 1,
            relativeMinutes = 135,
            observedAt = observedAt,
        )

        assertEquals(
            time(2026, 7, 28, 1, 45, 12),
            FarmScheduleCalculator.resolveObservedMaturity(
                reading,
                firstWaterAt = time(2026, 7, 27, 23, 28),
            ),
        )
    }

    @Test
    fun neverSchedulesAfterOcrMaturity() {
        val first = time(2026, 7, 27, 11, 55, 28)
        val maturity = time(2026, 7, 27, 12, 0)
        val schedule = FarmScheduleCalculator.calculate(
            firstWaterAt = first,
            observedMaturityAt = maturity,
            now = first,
            storedCycleMinutes = 60,
            batchStartedAt = time(2026, 7, 27, 11, 47, 8),
        )
        assertEquals(WakeReason.MATURITY, schedule.reason)
        assertEquals(maturity, schedule.targetAt)
        assertFalse(schedule.targetAt.isAfter(maturity))
    }

    @Test
    fun selectsUpcomingWateringBeforeMaturity() {
        val first = time(2026, 7, 27, 10, 0)
        val maturity = time(2026, 7, 27, 10, 55)
        val schedule = FarmScheduleCalculator.calculate(
            firstWaterAt = first,
            observedMaturityAt = maturity,
            now = time(2026, 7, 27, 10, 5),
            storedCycleMinutes = 60,
            batchStartedAt = first,
        )
        assertEquals(WakeReason.WATERING, schedule.reason)
        assertEquals(time(2026, 7, 27, 10, 20), schedule.targetAt)
        assertEquals(time(2026, 7, 27, 10, 18), schedule.wakeAt)
        assertTrue(schedule.targetAt.isBefore(maturity))
    }

    @Test
    fun skipsWateringWhenItsEarlyWakeWindowAlreadyPassed() {
        val first = time(2026, 7, 27, 10, 0)
        val maturity = time(2026, 7, 27, 10, 55)
        val schedule = FarmScheduleCalculator.calculate(
            firstWaterAt = first,
            observedMaturityAt = maturity,
            now = time(2026, 7, 27, 10, 19),
            storedCycleMinutes = 60,
            batchStartedAt = first,
        )

        assertEquals(time(2026, 7, 27, 10, 40), schedule.targetAt)
        assertEquals(time(2026, 7, 27, 10, 38), schedule.wakeAt)
    }

    @Test
    fun rejectsIncompatibleFiveMinuteStateForMidBatchCrop() {
        val first = time(2026, 7, 28, 10, 36)
        val maturity = time(2026, 7, 28, 11, 1)
        val schedule = FarmScheduleCalculator.calculate(
            firstWaterAt = first,
            observedMaturityAt = maturity,
            now = time(2026, 7, 28, 10, 37),
            storedCycleMinutes = 5,
            batchStartedAt = first,
        )

        assertEquals(60, schedule.cycleMinutes)
    }

    @Test
    fun keepsSixtyMinuteCropAcrossMinuteOnlyOcrBoundary() {
        val first = time(2026, 7, 28, 17, 36, 52)
        val schedule = FarmScheduleCalculator.calculate(
            firstWaterAt = first,
            observedMaturityAt = time(2026, 7, 28, 18, 33),
            now = time(2026, 7, 28, 17, 37, 29),
        )

        assertEquals(60, schedule.cycleMinutes)
        assertEquals(WakeReason.WATERING, schedule.reason)
        assertEquals(time(2026, 7, 28, 17, 56, 52), schedule.targetAt)
        assertEquals(time(2026, 7, 28, 17, 54, 52), schedule.wakeAt)
    }

    @Test
    fun classifiesObservedFourHundredFortyMinuteGrapeAsEightHourCrop() {
        val first = time(2026, 8, 10, 14, 27)
        val schedule = FarmScheduleCalculator.calculate(
            firstWaterAt = first,
            observedMaturityAt = time(2026, 8, 10, 21, 47),
            now = first.plusMinutes(1),
        )

        assertEquals(480, schedule.cycleMinutes)
        assertEquals(time(2026, 8, 10, 17, 7), schedule.targetAt)
    }

    @Test
    fun correctsPersistedLongCycleUsingCurrentOcrRemainder() {
        val schedule = FarmScheduleCalculator.calculate(
            firstWaterAt = time(2026, 7, 28, 17, 54, 32),
            observedMaturityAt = time(2026, 7, 28, 18, 28),
            now = time(2026, 7, 28, 17, 55, 13),
            storedCycleMinutes = 480,
            batchStartedAt = time(2026, 7, 28, 17, 36, 52),
        )

        assertEquals(60, schedule.cycleMinutes)
        assertEquals(WakeReason.WATERING, schedule.reason)
        assertEquals(time(2026, 7, 28, 18, 16, 52), schedule.targetAt)
        assertEquals(time(2026, 7, 28, 18, 14, 52), schedule.wakeAt)
    }

    @Test
    fun schedulesClickFiveSecondsAfterTargetWithNegativeSafetyMargin() {
        val target = time(2026, 7, 30, 12, 0)
        val measuredSecondsToClick = 66L
        val negativeSafetyMarginSeconds = -5L
        val schedule = FarmScheduleCalculator.calculate(
            firstWaterAt = time(2026, 7, 30, 11, 55),
            observedMaturityAt = target,
            now = time(2026, 7, 30, 11, 56),
            batchStartedAt = time(2026, 7, 30, 11, 2),
            wakeLeadSeconds = measuredSecondsToClick + negativeSafetyMarginSeconds,
        )

        assertEquals(time(2026, 7, 30, 11, 58, 59), schedule.wakeAt)
        assertEquals(target.plusSeconds(5), schedule.wakeAt.plusSeconds(measuredSecondsToClick))
    }

    private fun time(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
        second: Int = 0,
    ): LocalDateTime = LocalDateTime.of(year, month, day, hour, minute, second)
}
