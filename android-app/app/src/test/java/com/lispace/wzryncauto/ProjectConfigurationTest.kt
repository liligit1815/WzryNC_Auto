package com.lispace.wzryncauto

import org.junit.Assert.assertEquals
import org.junit.Test

class ProjectConfigurationTest {
    @Test
    fun packageNameIsStable() {
        assertEquals("com.lispace.wzryncauto", BuildConfig.APPLICATION_ID)
    }
}
