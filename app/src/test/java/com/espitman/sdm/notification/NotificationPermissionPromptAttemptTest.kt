package com.espitman.sdm.notification

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationPermissionPromptAttemptTest {
    @Test
    fun systemGrantOrDenyClearsAwaitingAndDeliversOnce() {
        val attempt = NotificationPermissionPromptAttempt()
        attempt.beginLaunch()
        assertTrue(attempt.awaiting)

        assertTrue(attempt.onSystemResult())
        assertFalse(attempt.awaiting)
        assertFalse(attempt.onSystemResult())
    }

    @Test
    fun unavailableLaunchClearsAwaitingAndDeliversImmediately() {
        val attempt = NotificationPermissionPromptAttempt()
        attempt.beginLaunch()

        assertTrue(attempt.onLaunchFailed())
        assertFalse(attempt.awaiting)
        assertFalse(attempt.onLaunchFailed())
    }

    @Test
    fun lateSystemResultAfterUnavailableLaunchDoesNotDeliverAgain() {
        val attempt = NotificationPermissionPromptAttempt()
        attempt.beginLaunch()

        assertTrue(attempt.onLaunchFailed())
        assertFalse(attempt.onSystemResult())
        assertFalse(attempt.awaiting)
    }

    @Test
    fun launchFailureAfterSystemResultDoesNotDeliverAgain() {
        val attempt = NotificationPermissionPromptAttempt()
        attempt.beginLaunch()

        assertTrue(attempt.onSystemResult())
        assertFalse(attempt.onLaunchFailed())
        assertFalse(attempt.awaiting)
    }
}
