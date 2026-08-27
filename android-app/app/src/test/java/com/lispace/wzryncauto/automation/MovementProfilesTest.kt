package com.lispace.wzryncauto.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MovementProfilesTest {
    @Test
    fun `uses exact 2560 by 1600 tablet profile`() {
        val profile = MovementProfiles.requireFor(2560, 1600)

        assertEquals(SwipeGesture(385, 1165, 200, 844, 1500), profile.spawnToStatue)
        assertEquals(SwipeGesture(385, 1165, 385, 869, 1200), profile.statueToFarmland)
    }

    @Test
    fun `uses exact 2560 by 1564 capture profile`() {
        val profile = MovementProfiles.requireFor(2560, 1564)

        assertEquals(SwipeGesture(385, 1138, 200, 825, 1500), profile.spawnToStatue)
        assertEquals(SwipeGesture(385, 1138, 385, 849, 1200), profile.statueToFarmland)
    }

    @Test
    fun `scaled profile always remains inside target screen`() {
        val profile = MovementProfiles.requireFor(1920, 1200)

        listOf(profile.spawnToStatue, profile.statueToFarmland).forEach { gesture ->
            assertTrue(gesture.startX in 0 until profile.screenWidth)
            assertTrue(gesture.endX in 0 until profile.screenWidth)
            assertTrue(gesture.startY in 0 until profile.screenHeight)
            assertTrue(gesture.endY in 0 until profile.screenHeight)
        }
    }
}
