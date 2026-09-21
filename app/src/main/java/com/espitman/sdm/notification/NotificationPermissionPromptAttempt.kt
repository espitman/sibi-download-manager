package com.espitman.sdm.notification

/**
 * Tracks one in-flight POST_NOTIFICATIONS Activity Result launch.
 *
 * The transfer proceeds exactly once: after the system grant/deny result, or
 * immediately if launching the prompt throws / is unavailable. A late system
 * result after an unavailable launch must not invoke the callback again.
 */
internal class NotificationPermissionPromptAttempt {
    var awaiting: Boolean = false
        private set

    private var callbackDelivered: Boolean = false

    fun beginLaunch() {
        awaiting = true
    }

    /** @return true when the caller must invoke the proceed callback now. */
    fun onLaunchFailed(): Boolean = completeOnce()

    /** @return true when the caller must invoke the proceed callback now. */
    fun onSystemResult(): Boolean = completeOnce()

    private fun completeOnce(): Boolean {
        awaiting = false
        if (callbackDelivered) return false
        callbackDelivered = true
        return true
    }
}
