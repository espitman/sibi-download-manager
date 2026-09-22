package com.espitman.sdm.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferServiceLifecyclePolicyTest {
    @Test
    fun rejectedForegroundReleasesOnlySchedulerClaimedStarts() {
        val start = StartTransferCommand("queued-1", "/tmp/a.part")
        assertEquals(
            "queued-1",
            TransferServiceLifecyclePolicy.claimedIdToReleaseOnRejectedForeground(
                enteredForeground = false,
                command = start,
            ),
        )
        assertNull(
            TransferServiceLifecyclePolicy.claimedIdToReleaseOnRejectedForeground(
                enteredForeground = true,
                command = start,
            ),
        )
        assertNull(
            TransferServiceLifecyclePolicy.claimedIdToReleaseOnRejectedForeground(
                enteredForeground = false,
                command = PauseTransferCommand("queued-1"),
            ),
        )
        assertNull(
            TransferServiceLifecyclePolicy.claimedIdToReleaseOnRejectedForeground(
                enteredForeground = false,
                command = CancelTransferCommand("queued-1"),
            ),
        )
        assertNull(
            TransferServiceLifecyclePolicy.claimedIdToReleaseOnRejectedForeground(
                enteredForeground = false,
                command = ResumeTransferCommand("queued-1"),
            ),
        )
        assertNull(
            TransferServiceLifecyclePolicy.claimedIdToReleaseOnRejectedForeground(
                enteredForeground = false,
                command = null,
            ),
        )
    }

    @Test
    fun pauseAndCancelDoNotObserveTheQueueUntilHandled() {
        assertFalse(
            TransferServiceLifecyclePolicy.startQueueObserverBeforeHandling(
                PauseTransferCommand("id"),
            ),
        )
        assertFalse(
            TransferServiceLifecyclePolicy.startQueueObserverBeforeHandling(
                CancelTransferCommand("id"),
            ),
        )
        assertTrue(
            TransferServiceLifecyclePolicy.startQueueObserverBeforeHandling(
                StartTransferCommand("id", "/tmp/a.part"),
            ),
        )
        assertTrue(
            TransferServiceLifecyclePolicy.startQueueObserverBeforeHandling(
                ResumeTransferCommand("id"),
            ),
        )
        assertTrue(TransferServiceLifecyclePolicy.startQueueObserverBeforeHandling(null))
    }

    @Test
    fun destroyedServiceDoesNotRescheduleAfterReleasingAClaim() {
        assertTrue(TransferServiceLifecyclePolicy.shouldRescheduleAfterSlotRelease(true))
        assertFalse(TransferServiceLifecyclePolicy.shouldRescheduleAfterSlotRelease(false))
    }
}
