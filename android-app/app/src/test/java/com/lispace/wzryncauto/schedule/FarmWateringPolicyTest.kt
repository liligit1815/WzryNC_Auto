package com.lispace.wzryncauto.schedule

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class FarmWateringPolicyTest {
    private val start = LocalDateTime.of(2026, 9, 22, 10, 0)

    @Test
    fun allCyclesAcceptFullNormalReductionInsteadOfFixedTenMinuteLimit() {
        for (cycle in listOf(5, 60, 480, 960, 1920)) {
            val before = start.plusSeconds(cycle * 60L * 11L / 12L)
            val result = evaluate(
                cycle = cycle,
                previous = before,
                maturity = before.minusSeconds(cycle * 60L / 12L),
                observed = start.plusSeconds(cycle * 60L / 3L + 10L),
            )
            assertTrue("cycle=$cycle", result.batchCompatible)
            if (cycle >= 60) assertTrue("cycle=$cycle", result.wateringConfirmed)
        }
    }

    @Test
    fun unchangedMaturityDoesNotConfirmWatering() {
        val result = evaluate()
        assertTrue(result.batchCompatible)
        assertFalse(result.wateringConfirmed)
    }

    @Test
    fun minutePrecisionCannotConfirmSmallReductionOrFiveMinuteCrop() {
        val result = evaluate(maturity = start.plusMinutes(54))
        assertTrue(result.batchCompatible)
        assertFalse(result.wateringConfirmed)
        val shortCrop = evaluate(
            cycle = 5,
            previous = start.plusSeconds(275),
            maturity = start.plusSeconds(250),
            observed = start.plusSeconds(110),
        )
        assertTrue(shortCrop.batchCompatible)
        assertFalse(shortCrop.wateringConfirmed)
    }

    @Test
    fun clearAdvancementConfirmsWatering() {
        val result = evaluate(maturity = start.plusMinutes(52))
        assertTrue(result.batchCompatible)
        assertTrue(result.wateringConfirmed)
    }

    @Test
    fun futureMaturityBeyondMinuteToleranceInvalidatesBatch() {
        assertFalse(evaluate(maturity = start.plusMinutes(56).plusSeconds(1)).batchCompatible)
        assertTrue(evaluate(maturity = start.plusMinutes(56)).batchCompatible)
    }

    @Test
    fun reductionGreaterThanSingleWateringCapAndToleranceInvalidatesBatch() {
        assertFalse(evaluate(maturity = start.plusMinutes(48)).batchCompatible)
    }

    @Test
    fun stateOlderThanTwoCyclesIsIncompatible() {
        assertFalse(evaluate(observed = start.plusMinutes(120).plusSeconds(1)).batchCompatible)
    }

    @Test
    fun timeReversalIsIncompatible() {
        assertFalse(evaluate(previousObserved = start.plusMinutes(30)).batchCompatible)
        assertFalse(evaluate(previousObserved = start.minusSeconds(1)).batchCompatible)
        assertFalse(evaluate(lastConfirmed = start.plusMinutes(21)).batchCompatible)
        assertFalse(evaluate(lastAttempt = start.minusSeconds(1)).batchCompatible)
        assertFalse(evaluate(lastConfirmed = start.plusMinutes(10), lastAttempt = start.plusMinutes(9)).batchCompatible)
    }

    @Test
    fun absentEarlierMaturityCannotConfirmSuccessfulWatering() {
        val result = evaluate(previous = null)
        assertTrue(result.batchCompatible)
        assertFalse(result.wateringConfirmed)
    }

    @Test
    fun attemptBeforeMinimumEffectiveIntervalCannotConfirmWatering() {
        val result = evaluate(
            maturity = start.plusMinutes(52),
            lastAttempt = start.plusSeconds(119),
        )
        assertTrue(result.batchCompatible)
        assertFalse(result.wateringConfirmed)
    }

    @Test
    fun unexplainedReductionDoesNotAttributeOtherAccelerationToThisAttempt() {
        val result = evaluate(
            maturity = start.plusMinutes(52),
            lastAttempt = start.plusMinutes(5),
        )
        assertTrue(result.batchCompatible)
        assertFalse(result.wateringConfirmed)
    }

    @Test
    fun extraWateringWithPlausiblePartialReductionCanBeConfirmed() {
        val result = evaluate(
            maturity = start.plusMinutes(52).plusSeconds(30),
            lastAttempt = start.plusMinutes(10),
        )
        assertTrue(result.batchCompatible)
        assertTrue(result.wateringConfirmed)
    }

    @Test
    fun storedCycleCannotContainMaturityOfAnotherLongerCrop() {
        assertFalse(evaluate(cycle = 5, previous = null).batchCompatible)
    }

    private fun evaluate(
        cycle: Int = 60,
        previous: LocalDateTime? = start.plusMinutes(55),
        maturity: LocalDateTime = start.plusMinutes(55),
        previousObserved: LocalDateTime = start,
        observed: LocalDateTime = start.plusMinutes(20),
        lastConfirmed: LocalDateTime? = start,
        lastAttempt: LocalDateTime? = observed,
    ) = FarmWateringPolicy.evaluate(
        cycleMinutes = cycle,
        batchStartedAt = start,
        previousMaturityAt = previous,
        previousObservedAt = previousObserved,
        observedMaturityAt = maturity,
        observedAt = observed,
        lastConfirmedWateringAt = lastConfirmed,
        lastAttemptAt = lastAttempt,
    )
}
