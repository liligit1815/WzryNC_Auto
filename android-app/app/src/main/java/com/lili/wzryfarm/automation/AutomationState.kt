package com.lili.wzryfarm.automation

enum class AutomationState {
    IDLE,
    PREPARING,
    CHECKING_GAME,
    LAUNCHING_GAME,
    WAITING_LOGIN,
    CLOSING_STARTUP_POPUPS,
    CLICKING_START_GAME,
    WAITING_LOBBY,
    CLOSING_LOBBY_POPUPS,
    ENTERING_FARM,
    RESETTING_POSITION,
    MOVING_TO_STATUE,
    VERIFYING_ONE_CLICK_FARM,
    ONE_CLICK_FARMING,
    HANDLING_HARVEST,
    MOVING_TO_FARMLAND,
    VERIFYING_FARMLAND,
    READING_MATURITY,
    CALCULATING_SCHEDULE,
    STOPPING_GAME,
    WAITING_NEXT_RUN,
    PAUSED,
    STOPPING,
    COMPLETED,
    ERROR,
}

sealed interface StepResult {
    data class Success(val next: AutomationState) : StepResult
    data class Retry(val delayMs: Long, val reason: String) : StepResult
    data class Failure(val recoverable: Boolean, val reason: String) : StepResult
}

/**
 * Explicit transition policy prevents step 9 from running at the farm spawn.
 * MOVING_TO_FARMLAND is reachable only after the statue one-click flow.
 */
object AutomationTransitionPolicy {
    private val allowed = mapOf(
        AutomationState.IDLE to setOf(AutomationState.PREPARING),
        AutomationState.PREPARING to setOf(AutomationState.CHECKING_GAME),
        AutomationState.CHECKING_GAME to setOf(
            AutomationState.LAUNCHING_GAME,
            AutomationState.ENTERING_FARM,
        ),
        AutomationState.LAUNCHING_GAME to setOf(AutomationState.WAITING_LOGIN),
        AutomationState.WAITING_LOGIN to setOf(AutomationState.CLOSING_STARTUP_POPUPS),
        AutomationState.CLOSING_STARTUP_POPUPS to setOf(AutomationState.CLICKING_START_GAME),
        AutomationState.CLICKING_START_GAME to setOf(AutomationState.WAITING_LOBBY),
        AutomationState.WAITING_LOBBY to setOf(AutomationState.CLOSING_LOBBY_POPUPS),
        AutomationState.CLOSING_LOBBY_POPUPS to setOf(AutomationState.ENTERING_FARM),
        AutomationState.ENTERING_FARM to setOf(AutomationState.RESETTING_POSITION),
        AutomationState.RESETTING_POSITION to setOf(AutomationState.MOVING_TO_STATUE),
        AutomationState.MOVING_TO_STATUE to setOf(AutomationState.VERIFYING_ONE_CLICK_FARM),
        AutomationState.VERIFYING_ONE_CLICK_FARM to setOf(AutomationState.ONE_CLICK_FARMING),
        AutomationState.ONE_CLICK_FARMING to setOf(AutomationState.HANDLING_HARVEST),
        AutomationState.HANDLING_HARVEST to setOf(AutomationState.MOVING_TO_FARMLAND),
        AutomationState.MOVING_TO_FARMLAND to setOf(AutomationState.VERIFYING_FARMLAND),
        AutomationState.VERIFYING_FARMLAND to setOf(AutomationState.READING_MATURITY),
        AutomationState.READING_MATURITY to setOf(AutomationState.CALCULATING_SCHEDULE),
        AutomationState.CALCULATING_SCHEDULE to setOf(AutomationState.STOPPING_GAME),
        AutomationState.STOPPING_GAME to setOf(
            AutomationState.WAITING_NEXT_RUN,
            AutomationState.COMPLETED,
        ),
        AutomationState.WAITING_NEXT_RUN to setOf(AutomationState.CHECKING_GAME),
        AutomationState.STOPPING to setOf(AutomationState.IDLE),
    )

    fun canTransition(from: AutomationState, to: AutomationState): Boolean {
        if (to == AutomationState.ERROR || to == AutomationState.STOPPING) return true
        if (to == AutomationState.PAUSED) return from !in terminalStates
        if (from == AutomationState.PAUSED) return to !in terminalStates
        return to in allowed[from].orEmpty()
    }

    fun requireTransition(from: AutomationState, to: AutomationState) {
        require(canTransition(from, to)) {
            "Illegal automation transition: $from -> $to"
        }
    }

    private val terminalStates = setOf(
        AutomationState.IDLE,
        AutomationState.COMPLETED,
        AutomationState.ERROR,
    )
}
