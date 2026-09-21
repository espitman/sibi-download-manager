package com.espitman.sdm.notification

import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationPermissionHandoffTest {
    @Test
    fun grantOrDenyResultBecomesReadyOnce() {
        val waiting = NotificationPermissionHandoff.awaitingPermission(URL)

        val granted = waiting.onSystemResult()
        assertEquals(NotificationPermissionHandoff.Phase.ReadyToSubmit, granted.phase)
        assertEquals(URL, granted.pendingUrl)
        assertEquals(granted, granted.onSystemResult())

        val denied = waiting.onSystemResult()
        assertEquals(granted, denied)
    }

    @Test
    fun launcherFailureBecomesReadyOnce() {
        val waiting = NotificationPermissionHandoff.awaitingPermission(URL)

        val ready = waiting.onLaunchFailed()
        assertEquals(NotificationPermissionHandoff.Phase.ReadyToSubmit, ready.phase)
        assertEquals(URL, ready.pendingUrl)
        assertEquals(ready, ready.onLaunchFailed())
    }

    @Test
    fun duplicateResultStaysReadyWithTheSameUrl() {
        val afterGrant = NotificationPermissionHandoff.awaitingPermission(URL).onSystemResult()
        assertEquals(afterGrant, afterGrant.onSystemResult())
        assertEquals(afterGrant, afterGrant.onLaunchFailed())

        val afterFailure = NotificationPermissionHandoff.awaitingPermission(URL).onLaunchFailed()
        assertEquals(afterFailure, afterFailure.onSystemResult())
        assertEquals(afterFailure, afterFailure.onLaunchFailed())
        assertEquals(URL, afterFailure.pendingUrl)
    }

    @Test
    fun duplicateDownloadTapDoesNotReplaceThePendingUrl() {
        val waiting = NotificationPermissionHandoff.consumed()
            .onDownloadTap(URL, awaitPermission = true)
        val tappedAgain = waiting.onDownloadTap(OTHER_URL, awaitPermission = false)

        assertEquals(waiting, tappedAgain)
        assertEquals(NotificationPermissionHandoff.Phase.WaitingForPermission, tappedAgain.phase)
        assertEquals(URL, tappedAgain.pendingUrl)

        val submitting = waiting.onSystemResult().markSubmitting()
        assertEquals(submitting, submitting.onDownloadTap(OTHER_URL, awaitPermission = true))
        assertEquals(submitting, submitting.markSubmitting())
    }

    @Test
    fun recreationWhileWaitingKeepsTheUrlAndDoesNotSubmit() {
        val waiting = NotificationPermissionHandoff.awaitingPermission(URL)
        val restored = restore(waiting)

        assertEquals(waiting, restored)
        assertEquals(NotificationPermissionHandoff.Phase.WaitingForPermission, restored.phase)
        assertEquals(URL, restored.pendingUrl)
        assertEquals(restored, restored.markSubmitting())
    }

    @Test
    fun recreationAfterReadyKeepsTheUrlBeforeSubmission() {
        val ready = NotificationPermissionHandoff.awaitingPermission(URL).onSystemResult()
        val restored = restore(ready)

        assertEquals(ready, restored)
        assertEquals(NotificationPermissionHandoff.Phase.ReadyToSubmit, restored.phase)
        assertEquals(URL, restored.pendingUrl)
        assertEquals(NotificationPermissionHandoff.Phase.Submitting, restored.markSubmitting().phase)
    }

    @Test
    fun recreationDuringSubmittingKeepsThePendingUrl() {
        val submitting = NotificationPermissionHandoff.awaitingPermission(URL)
            .onSystemResult()
            .markSubmitting()
        val restored = restore(submitting)

        assertEquals(submitting, restored)
        assertEquals(NotificationPermissionHandoff.Phase.Submitting, restored.phase)
        assertEquals(URL, restored.pendingUrl)
        assertEquals(restored, restored.onSystemResult())
        assertEquals(restored, restored.markSubmitting())
    }

    @Test
    fun consumeIsTerminalAndClearsThePendingUrl() {
        val submitting = NotificationPermissionHandoff.readyToSubmit(URL).markSubmitting()
        val consumed = submitting.consume()

        assertEquals(NotificationPermissionHandoff.consumed(), consumed)
        assertEquals(NotificationPermissionHandoff.Phase.Consumed, consumed.phase)
        assertEquals(null, consumed.pendingUrl)
        assertEquals("", consumed.savedPendingUrl)
        assertEquals(consumed, consumed.consume())
        assertEquals(consumed, consumed.onSystemResult())
        assertEquals(consumed, consumed.onLaunchFailed())
        assertEquals(consumed, consumed.markSubmitting())
    }

    private fun restore(handoff: NotificationPermissionHandoff): NotificationPermissionHandoff =
        NotificationPermissionHandoff.restore(handoff.savedPhase, handoff.savedPendingUrl)

    private companion object {
        const val URL = "https://example.com/file.zip"
        const val OTHER_URL = "https://example.com/other.bin"
    }
}
