package com.espitman.sdm.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SplashLaunchPolicyTest {
    @Test
    fun splashRunsOnlyForFirstFreshActivityInProcess() {
        val policy = SplashLaunchPolicy()
        assertTrue(policy.shouldShow(restoredActivity = false))
        assertFalse(policy.shouldShow(restoredActivity = false))
        assertFalse(policy.shouldShow(restoredActivity = true))
    }

    @Test
    fun restoredActivitySkipsSplashEvenInNewProcess() {
        val policy = SplashLaunchPolicy()
        assertFalse(policy.shouldShow(restoredActivity = true))
        assertFalse(policy.shouldShow(restoredActivity = false))
    }
}
