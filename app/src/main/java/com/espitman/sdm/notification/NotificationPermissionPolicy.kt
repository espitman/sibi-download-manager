package com.espitman.sdm.notification

/**
 * Pure POST_NOTIFICATIONS policy for starting a real foreground transfer.
 *
 * The transfer always proceeds. A runtime prompt is issued at most once on
 * API 33+ when the permission is not already granted.
 */
data class NotificationPermissionDecision(
    val shouldRequestRuntimePermission: Boolean,
    val proceedWithTransfer: Boolean,
)

object NotificationPermissionPolicy {
    const val RUNTIME_PERMISSION_MIN_SDK = 33

    fun decide(
        sdkInt: Int,
        permissionGranted: Boolean,
        runtimePromptAlreadyIssued: Boolean,
    ): NotificationPermissionDecision {
        val runtimePermissionRequired = sdkInt >= RUNTIME_PERMISSION_MIN_SDK
        if (!runtimePermissionRequired || permissionGranted || runtimePromptAlreadyIssued) {
            return NotificationPermissionDecision(
                shouldRequestRuntimePermission = false,
                proceedWithTransfer = true,
            )
        }
        return NotificationPermissionDecision(
            shouldRequestRuntimePermission = true,
            proceedWithTransfer = true,
        )
    }
}
