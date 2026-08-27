package com.lispace.wzryncauto.service

/** Stops a permanently blocked screen from creating an unbounded restart loop. */
object FailureRetryPolicy {
    const val MAX_CONSECUTIVE_FAILURES = 5

    fun shouldRetry(consecutiveFailuresBeforeCurrent: Int): Boolean {
        require(consecutiveFailuresBeforeCurrent >= 0)
        return consecutiveFailuresBeforeCurrent + 1 < MAX_CONSECUTIVE_FAILURES
    }
}
