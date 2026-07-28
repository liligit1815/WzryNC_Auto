package com.lispace.wzryncauto.automation

import kotlinx.coroutines.runBlocking
import com.lispace.wzryncauto.ocr.MaturityReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EnterFarmAutomationTest {
    @Test
    fun `runs launch login lobby and enter farm in strict order`() = runBlocking {
        val runtime = FakeRuntime(
            observations = ArrayDeque(
                listOf(
                    screen(match("start_game.png")),
                    screen(match("start_game.png")),
                    screen(match("start_game.png", x = 1190, y = 842)),
                    screen(),
                    screen(match("lainongchang.png")),
                    screen(match("lainongchang.png")),
                    screen(match("lainongchang.png", x = 725, y = 800)),
                    screen(match("refresh_pos.png")),
                ),
            ),
        )
        val states = mutableListOf<AutomationState>()

        val finalState = EnterFarmAutomation(
            runtime = runtime,
            onState = { state, _ -> states += state },
        ).run()

        assertEquals(AutomationState.RESETTING_POSITION, finalState)
        assertEquals(
            listOf(
                AutomationState.PREPARING,
                AutomationState.CHECKING_GAME,
                AutomationState.LAUNCHING_GAME,
                AutomationState.WAITING_LOGIN,
                AutomationState.CLOSING_STARTUP_POPUPS,
                AutomationState.CLICKING_START_GAME,
                AutomationState.WAITING_LOBBY,
                AutomationState.CLOSING_LOBBY_POPUPS,
                AutomationState.ENTERING_FARM,
                AutomationState.RESETTING_POSITION,
            ),
            states.distinct(),
        )
        assertTrue(runtime.launched)
        assertEquals(listOf(1190 to 842, 725 to 800), runtime.taps)
    }

    @Test
    fun `closes popup using coordinate from current observation`() = runBlocking {
        val runtime = FakeRuntime(
            observations = ArrayDeque(
                listOf(
                    screen(match("close_popup_event.png")),
                    screen(match("close_popup_event.png", x = 2200, y = 120)),
                    screen(match("start_game.png")),
                    screen(match("start_game.png")),
                    screen(),
                    screen(match("lainongchang.png")),
                    screen(match("lainongchang.png")),
                    screen(match("lainongchang.png")),
                    screen(match("refresh_pos.png")),
                ),
            ),
        )

        EnterFarmAutomation(runtime).run()

        assertEquals(2200 to 120, runtime.taps.first())
    }

    @Test
    fun `retries start game when button remains after tap`() = runBlocking {
        val runtime = FakeRuntime(
            observations = ArrayDeque(
                listOf(
                    screen(match("start_game.png")),
                    screen(match("start_game.png")),
                    screen(match("start_game.png", x = 1190, y = 842)),
                    screen(match("start_game.png")),
                    screen(match("start_game.png", x = 1190, y = 842)),
                    screen(),
                    screen(match("lainongchang.png")),
                    screen(match("lainongchang.png")),
                    screen(match("lainongchang.png", x = 725, y = 800)),
                    screen(match("refresh_pos.png")),
                ),
            ),
        )

        EnterFarmAutomation(runtime).run()

        assertEquals(
            listOf(1190 to 842, 1190 to 842, 725 to 800),
            runtime.taps,
        )
    }

    private class FakeRuntime(
        private val observations: ArrayDeque<ScreenObservation>,
    ) : AutomationRuntime {
        var launched = false
        val taps = mutableListOf<Pair<Int, Int>>()

        override suspend fun checkRoot() = true
        override suspend fun isGameForeground() = false
        override suspend fun isGameRunning() = false
        override suspend fun launchGame() {
            launched = true
        }
        override suspend fun stopGame() = Unit
        override suspend fun observe(templateNames: List<String>): ScreenObservation =
            observations.removeFirst()
        override suspend fun tap(x: Int, y: Int) {
            taps += x to y
        }
        override suspend fun swipe(gesture: SwipeGesture) = Unit
        override suspend fun readMaturity(): MaturityReading =
            MaturityReading.Time(12, 0, false, "12:00成熟")
        override suspend fun delayMs(milliseconds: Long) = Unit
    }

    companion object {
        private fun match(name: String, x: Int = 10, y: Int = 20) =
            TemplateObservation(name, true, 0.95, x, y, "screen")

        private fun screen(vararg matches: TemplateObservation) =
            ScreenObservation(matches.associateBy(TemplateObservation::templateName))
    }
}
