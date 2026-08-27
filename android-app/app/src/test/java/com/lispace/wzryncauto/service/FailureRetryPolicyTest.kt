package com.lispace.wzryncauto.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FailureRetryPolicyTest {
    @Test
    fun `allows early retries but stops at fifth consecutive failure`() {
        assertTrue(FailureRetryPolicy.shouldRetry(0))
        assertTrue(FailureRetryPolicy.shouldRetry(3))
        assertFalse(FailureRetryPolicy.shouldRetry(4))
        assertFalse(FailureRetryPolicy.shouldRetry(282))
    }
}
