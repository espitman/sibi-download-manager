package com.espitman.sdm.notification

import com.espitman.sdm.domain.DownloadState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferNotificationPresentationPolicyTest {
    @Test
    fun activeChildrenStayInTheForegroundGroup() {
        assertTrue(
            TransferNotificationPresentationPolicy.belongsToForegroundGroup(DownloadState.CONNECTING),
        )
        assertTrue(
            TransferNotificationPresentationPolicy.belongsToForegroundGroup(DownloadState.DOWNLOADING),
        )
    }

    @Test
    fun pausedChildrenAreStandaloneAfterSummaryRemoval() {
        assertFalse(
            TransferNotificationPresentationPolicy.belongsToForegroundGroup(DownloadState.PAUSED),
        )
    }

    @Test
    fun terminalAndQueuedChildrenAreNotGrouped() {
        listOf(
            DownloadState.QUEUED,
            DownloadState.COMPLETED,
            DownloadState.FAILED,
            DownloadState.CANCELLED,
        ).forEach { state ->
            assertFalse(
                "state $state must not use the foreground group",
                TransferNotificationPresentationPolicy.belongsToForegroundGroup(state),
            )
        }
    }
}
