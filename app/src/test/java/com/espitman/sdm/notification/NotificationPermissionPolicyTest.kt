package com.espitman.sdm.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationPermissionPolicyTest {
    @Test
    fun api32AndBelowNeverRequestRuntimePermission() {
        listOf(26, 31, 32).forEach { sdk ->
            val decision = NotificationPermissionPolicy.decide(
                sdkInt = sdk,
                permissionGranted = false,
                runtimePromptAlreadyIssued = false,
            )
            assertFalse("sdk $sdk should not request POST_NOTIFICATIONS", decision.shouldRequestRuntimePermission)
            assertTrue("sdk $sdk must still start the transfer", decision.proceedWithTransfer)
        }
    }

    @Test
    fun api33PlusRequestsOnlyWhenUngrantedAndNotYetPrompted() {
        val decision = NotificationPermissionPolicy.decide(
            sdkInt = 33,
            permissionGranted = false,
            runtimePromptAlreadyIssued = false,
        )
        assertTrue(decision.shouldRequestRuntimePermission)
        assertTrue(decision.proceedWithTransfer)

        val laterSdk = NotificationPermissionPolicy.decide(
            sdkInt = 35,
            permissionGranted = false,
            runtimePromptAlreadyIssued = false,
        )
        assertTrue(laterSdk.shouldRequestRuntimePermission)
        assertTrue(laterSdk.proceedWithTransfer)
    }

    @Test
    fun grantedPermissionNeverRerequestsIncludingAfterAPriorPrompt() {
        val firstGrant = NotificationPermissionPolicy.decide(
            sdkInt = 33,
            permissionGranted = true,
            runtimePromptAlreadyIssued = false,
        )
        val afterPrompt = NotificationPermissionPolicy.decide(
            sdkInt = 35,
            permissionGranted = true,
            runtimePromptAlreadyIssued = true,
        )
        assertFalse(firstGrant.shouldRequestRuntimePermission)
        assertFalse(afterPrompt.shouldRequestRuntimePermission)
        assertTrue(firstGrant.proceedWithTransfer)
        assertTrue(afterPrompt.proceedWithTransfer)
    }

    @Test
    fun deniedOrUnavailablePermissionDoesNotPromptAgainAndStillStartsTransfer() {
        val denied = NotificationPermissionPolicy.decide(
            sdkInt = 33,
            permissionGranted = false,
            runtimePromptAlreadyIssued = true,
        )
        assertFalse(denied.shouldRequestRuntimePermission)
        assertTrue(denied.proceedWithTransfer)
    }

    @Test
    fun everyDocumentedStateAllowsTheForegroundTransfer() {
        val sdkValues = listOf(26, 32, 33, 35)
        val flags = listOf(false, true)
        sdkValues.forEach { sdk ->
            flags.forEach { granted ->
                flags.forEach { alreadyPrompted ->
                    val decision = NotificationPermissionPolicy.decide(sdk, granted, alreadyPrompted)
                    assertTrue(
                        "transfer must proceed for sdk=$sdk granted=$granted prompted=$alreadyPrompted",
                        decision.proceedWithTransfer,
                    )
                }
            }
        }
    }
}
