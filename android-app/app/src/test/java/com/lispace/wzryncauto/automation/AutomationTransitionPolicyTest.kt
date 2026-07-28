package com.lispace.wzryncauto.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationTransitionPolicyTest {
    @Test
    fun forbidsSkippingStatueFlowAndRunningStep9AtSpawn() {
        assertFalse(
            AutomationTransitionPolicy.canTransition(
                AutomationState.ENTERING_FARM,
                AutomationState.MOVING_TO_FARMLAND,
            ),
        )
        assertFalse(
            AutomationTransitionPolicy.canTransition(
                AutomationState.MOVING_TO_STATUE,
                AutomationState.MOVING_TO_FARMLAND,
            ),
        )
    }

    @Test
    fun permitsFarmlandOnlyAfterOneClickAndHarvestHandling() {
        assertTrue(
            AutomationTransitionPolicy.canTransition(
                AutomationState.ONE_CLICK_FARMING,
                AutomationState.HANDLING_HARVEST,
            ),
        )
        assertTrue(
            AutomationTransitionPolicy.canTransition(
                AutomationState.HANDLING_HARVEST,
                AutomationState.MOVING_TO_FARMLAND,
            ),
        )
    }

    @Test
    fun containsCalibrated2400MovementSequence() {
        val profile = MovementProfiles.requireFor(2400, 1080)
        assertEquals(SwipeGesture(430, 755, 305, 538, 1500), profile.spawnToStatue)
        assertEquals(SwipeGesture(430, 755, 430, 555, 1200), profile.statueToFarmland)
    }

    @Test
    fun containsCalibrated2560x1600MovementSequence() {
        val profile = MovementProfiles.requireFor(2560, 1600)
        assertEquals(SwipeGesture(385, 1165, 200, 844, 1500), profile.spawnToStatue)
        assertEquals(SwipeGesture(385, 1165, 385, 869, 1200), profile.statueToFarmland)
    }

    @Test
    fun scalesNearestAspectMovementProfileForUnknownResolution() {
        val profile = MovementProfiles.requireFor(1920, 1080)
        assertEquals(SwipeGesture(240, 729, 90, 469, 1500), profile.spawnToStatue)
        assertEquals(SwipeGesture(240, 729, 240, 429, 1200), profile.statueToFarmland)
    }
}
