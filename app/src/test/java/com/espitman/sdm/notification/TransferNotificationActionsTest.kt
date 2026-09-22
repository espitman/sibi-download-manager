package com.espitman.sdm.notification

import com.espitman.sdm.domain.DownloadState
import com.espitman.sdm.download.DownloadTransferCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferNotificationActionsTest {
    @Test
    fun connectingAndDownloadingExposePauseAndCancel() {
        val expected = listOf(
            TransferNotificationActionKind.PAUSE,
            TransferNotificationActionKind.CANCEL,
        )
        assertEquals(expected, TransferNotificationActions.forState(DownloadState.CONNECTING))
        assertEquals(expected, TransferNotificationActions.forState(DownloadState.DOWNLOADING))
        assertEquals(
            listOf("Pause", "Cancel"),
            expected.map(TransferNotificationActions::label),
        )
    }

    @Test
    fun pausedExposesResumeAndCancel() {
        val expected = listOf(
            TransferNotificationActionKind.RESUME,
            TransferNotificationActionKind.CANCEL,
        )
        assertEquals(expected, TransferNotificationActions.forState(DownloadState.PAUSED))
        assertEquals(
            listOf("Resume", "Cancel"),
            expected.map(TransferNotificationActions::label),
        )
    }

    @Test
    fun terminalAndQueuedStatesExposeNoActions() {
        listOf(
            DownloadState.QUEUED,
            DownloadState.COMPLETED,
            DownloadState.FAILED,
            DownloadState.CANCELLED,
        ).forEach { state ->
            assertEquals(
                "state $state must not expose notification actions",
                emptyList<TransferNotificationActionKind>(),
                TransferNotificationActions.forState(state),
            )
        }
    }

    @Test
    fun labelsAreExactEnglishPauseResumeCancel() {
        assertEquals("Pause", TransferNotificationActions.label(TransferNotificationActionKind.PAUSE))
        assertEquals("Resume", TransferNotificationActions.label(TransferNotificationActionKind.RESUME))
        assertEquals("Cancel", TransferNotificationActions.label(TransferNotificationActionKind.CANCEL))
        assertEquals("Pause", TransferNotificationActions.LABEL_PAUSE)
        assertEquals("Resume", TransferNotificationActions.LABEL_RESUME)
        assertEquals("Cancel", TransferNotificationActions.LABEL_CANCEL)
    }

    @Test
    fun serviceActionsMatchInAppTransferCommands() {
        assertEquals(
            DownloadTransferCommand.ACTION_PAUSE_TRANSFER,
            TransferNotificationActions.serviceAction(TransferNotificationActionKind.PAUSE),
        )
        assertEquals(
            DownloadTransferCommand.ACTION_RESUME_TRANSFER,
            TransferNotificationActions.serviceAction(TransferNotificationActionKind.RESUME),
        )
        assertEquals(
            DownloadTransferCommand.ACTION_CANCEL_TRANSFER,
            TransferNotificationActions.serviceAction(TransferNotificationActionKind.CANCEL),
        )
        assertTrue(
            TransferNotificationActions.serviceAction(TransferNotificationActionKind.PAUSE)
                .startsWith("com.espitman.sdm.download.action."),
        )
    }
}
