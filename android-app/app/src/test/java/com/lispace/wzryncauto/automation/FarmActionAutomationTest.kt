package com.lispace.wzryncauto.automation

import kotlinx.coroutines.runBlocking
import com.lispace.wzryncauto.ocr.MaturityReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class FarmActionAutomationTest {
    @Test
    fun `cannot move to farmland before statue one click flow`() = runBlocking {
        val runtime = FakeRuntime(
            ArrayDeque(
                listOf(
                    screen(match("refresh_pos.png")),
                    screen(match("oneclick_farm.png")),
                    screen(match("oneclick_farm.png")),
                    screen(match("oneclick_farm.png", 1522, 619)),
                    screen(),
                    screen(),
                    screen(),
                    screen(),
                ),
            ),
        )
        val states = mutableListOf<AutomationState>()
        val clickedAt = LocalDateTime.of(2026, 7, 28, 12, 34, 56)

        val result = FarmActionAutomation(
            runtime = runtime,
            onState = { state, _ -> states += state },
            now = { clickedAt },
        ).run()

        assertFalse(result.harvested)
        assertEquals(clickedAt, result.firstWaterAt)
        assertEquals(
            listOf(
                AutomationState.MOVING_TO_STATUE,
                AutomationState.VERIFYING_ONE_CLICK_FARM,
                AutomationState.ONE_CLICK_FARMING,
                AutomationState.HANDLING_HARVEST,
                AutomationState.MOVING_TO_FARMLAND,
                AutomationState.VERIFYING_FARMLAND,
                AutomationState.READING_MATURITY,
            ),
            states.distinct(),
        )
        assertEquals(
            listOf(
                SwipeGesture(430, 755, 305, 538, 1500),
                SwipeGesture(430, 755, 430, 555, 1200),
            ),
            runtime.swipes,
        )
        assertEquals(listOf(1522 to 619), runtime.taps)
    }

    @Test
    fun `closes harvest popup using refreshed coordinate`() = runBlocking {
        val runtime = FakeRuntime(
            ArrayDeque(
                listOf(
                    screen(match("refresh_pos.png")),
                    screen(match("oneclick_farm.png")),
                    screen(match("oneclick_farm.png")),
                    screen(match("oneclick_farm.png", 1522, 619)),
                    screen(match("harvest_continue.png")),
                    screen(match("harvest_continue.png", 1200, 930)),
                    screen(),
                    screen(),
                ),
            ),
        )

        val result = FarmActionAutomation(runtime).run()

        assertTrue(result.harvested)
        assertEquals(listOf(1522 to 619, 1200 to 930), runtime.taps)
    }

    @Test
    fun `dismisses rest reminder before continuing farm actions`() = runBlocking {
        val runtime = FakeRuntime(
            ArrayDeque(
                listOf(
                    screen(match("rest_reminder_confirm.png", 1274, 1009)),
                    screen(match("refresh_pos.png")),
                    screen(match("oneclick_farm.png")),
                    screen(match("oneclick_farm.png")),
                    screen(match("oneclick_farm.png", 1522, 619)),
                    screen(),
                    screen(),
                    screen(),
                    screen(),
                ),
            ),
        )

        FarmActionAutomation(runtime).run()

        assertEquals(1274 to 1009, runtime.taps.first())
        assertEquals(1522 to 619, runtime.taps.last())
    }

    private class FakeRuntime(
        private val observations: ArrayDeque<ScreenObservation>,
    ) : AutomationRuntime {
        val taps = mutableListOf<Pair<Int, Int>>()
        val swipes = mutableListOf<SwipeGesture>()

        override suspend fun checkRoot() = true
        override suspend fun isGameForeground() = true
        override suspend fun isGameRunning() = true
        override suspend fun launchGame() = Unit
        override suspend fun stopGame() = Unit
        override suspend fun observe(templateNames: List<String>) =
            observations.removeFirst()
        override suspend fun tap(x: Int, y: Int) {
            taps += x to y
        }
        override suspend fun swipe(gesture: SwipeGesture) {
            swipes += gesture
        }
        override suspend fun readMaturity(): MaturityReading =
            MaturityReading.Time(11, 6, false, "11:06成熟")
        override suspend fun delayMs(milliseconds: Long) = Unit
    }

    companion object {
        private fun match(name: String, x: Int = 10, y: Int = 20) =
            TemplateObservation(name, true, 0.95, x, y, "screen")

        private fun screen(vararg matches: TemplateObservation) =
            ScreenObservation(
                templates = matches.associateBy(TemplateObservation::templateName),
                width = 2400,
                height = 1080,
            )
    }
}
