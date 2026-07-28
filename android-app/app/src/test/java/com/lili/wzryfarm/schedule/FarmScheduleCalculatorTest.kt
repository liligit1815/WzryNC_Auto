package com.lili.wzryfarm.schedule

import com.lili.wzryfarm.ocr.MaturityReading
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

    private fun time(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
        second: Int = 0,
    ): LocalDateTime = LocalDateTime.of(year, month, day, hour, minute, second)
}
