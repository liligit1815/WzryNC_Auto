package com.lili.wzryfarm

import org.junit.Assert.assertEquals
import org.junit.Test

class ProjectConfigurationTest {
    @Test
    fun packageNameIsStable() {
        assertEquals("com.lili.wzryfarm", BuildConfig.APPLICATION_ID)
    }
}
