package com.espitman.sdm.notification

import com.espitman.sdm.domain.Download
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
    fun aggregateShowsPauseAllAndResumeAllAsAppropriate() {
        val mixed = TransferNotificationAggregate.from(
            listOf(
                record(DownloadState.DOWNLOADING),
                record(DownloadState.QUEUED),
                record(DownloadState.PAUSED),
            ),
        )
        assertEquals(
            listOf(TransferNotificationActionKind.PAUSE_ALL, TransferNotificationActionKind.RESUME_ALL),
            TransferNotificationActions.forAggregate(mixed),
        )
        assertEquals(
            listOf(TransferNotificationActionKind.PAUSE_ALL),
            TransferNotificationActions.forAggregate(
                TransferNotificationAggregate.from(listOf(record(DownloadState.QUEUED))),
            ),
        )
        assertEquals(
            listOf(TransferNotificationActionKind.RESUME_ALL),
            TransferNotificationActions.forAggregate(
                TransferNotificationAggregate.from(listOf(record(DownloadState.PAUSED))),
            ),
        )
        assertEquals(
            emptyList<TransferNotificationActionKind>(),
            TransferNotificationActions.forAggregate(
                TransferNotificationAggregate.from(listOf(record(DownloadState.COMPLETED))),
            ),
        )
    }

    @Test
    fun labelsAreExactEnglishPauseResumeCancel() {
        assertEquals("Pause", TransferNotificationActions.label(TransferNotificationActionKind.PAUSE))
        assertEquals("Resume", TransferNotificationActions.label(TransferNotificationActionKind.RESUME))
        assertEquals("Cancel", TransferNotificationActions.label(TransferNotificationActionKind.CANCEL))
        assertEquals("Pause", TransferNotificationActions.LABEL_PAUSE)
        assertEquals("Resume", TransferNotificationActions.LABEL_RESUME)
        assertEquals("Cancel", TransferNotificationActions.LABEL_CANCEL)
        assertEquals("Pause All", TransferNotificationActions.label(TransferNotificationActionKind.PAUSE_ALL))
        assertEquals("Resume All", TransferNotificationActions.label(TransferNotificationActionKind.RESUME_ALL))
        assertEquals("Pause All", TransferNotificationActions.LABEL_PAUSE_ALL)
        assertEquals("Resume All", TransferNotificationActions.LABEL_RESUME_ALL)
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
        assertEquals(
            DownloadTransferCommand.ACTION_PAUSE_ALL,
            TransferNotificationActions.serviceAction(TransferNotificationActionKind.PAUSE_ALL),
        )
        assertEquals(
            DownloadTransferCommand.ACTION_RESUME_ALL,
            TransferNotificationActions.serviceAction(TransferNotificationActionKind.RESUME_ALL),
        )
        assertTrue(
            TransferNotificationActions.serviceAction(TransferNotificationActionKind.PAUSE)
                .startsWith("com.espitman.sdm.download.action."),
        )
    }

    private fun record(state: DownloadState) = Download(
        id = state.name.lowercase(),
        url = "https://example.com/${state.name}",
        fileName = "${state.name}.bin",
        destinationPath = "/downloads/${state.name}.bin",
        totalBytes = 100L,
        downloadedBytes = if (state == DownloadState.COMPLETED) 100L else 10L,
        state = state,
        error = if (state == DownloadState.FAILED) "failed" else null,
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 1L,
        completedAtEpochMillis = if (state == DownloadState.COMPLETED) 2L else null,
    )
}
