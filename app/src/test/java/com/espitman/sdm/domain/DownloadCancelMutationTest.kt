package com.espitman.sdm.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class DownloadCancelMutationTest {
    private fun queued() = Download(
        id = "download-1",
        url = "https://example.com/file.zip",
        fileName = "file.zip",
        totalBytes = 10_000,
        createdAtEpochMillis = 1_000,
    )

    @Test
    fun cancelsQueuedConnectingDownloadingPausedAndFailedAtBoundedOffset() {
        val queued = queued().copy(downloadedBytes = 250L)
        val connecting = DownloadStateMachine.transition(queued(), DownloadState.CONNECTING, 2_000)
            .copy(downloadedBytes = 1_000L)
        val downloading = DownloadStateMachine.transition(
            DownloadStateMachine.transition(queued(), DownloadState.CONNECTING, 2_000),
            DownloadState.DOWNLOADING,
            3_000,
        ).copy(downloadedBytes = 5_000L, updatedAtEpochMillis = 3_000L)
        val paused = DownloadStateMachine.transition(downloading, DownloadState.PAUSED, 4_000)
        val failed = queued().copy(
            downloadedBytes = 800L,
            state = DownloadState.FAILED,
            error = "boom",
            updatedAtEpochMillis = 2_000L,
        )

        val cancelledQueued = DownloadCancelMutation.apply(queued, fileLengthBytes = 400L, nowEpochMillis = 500L)
        val cancelledConnecting = DownloadCancelMutation.apply(connecting, 2_500L, 2_500L)
        val cancelledDownloading = DownloadCancelMutation.apply(downloading, 3_000L, 2_500L)
        val cancelledPaused = DownloadCancelMutation.apply(paused, 12_000L, 5_000L)
        val cancelledFailed = DownloadCancelMutation.apply(failed, 100L, 3_000L)

        assertEquals(DownloadState.CANCELLED, cancelledQueued.state)
        assertEquals(400L, cancelledQueued.downloadedBytes)
        assertEquals(1_000L, cancelledQueued.updatedAtEpochMillis)
        assertNull(cancelledQueued.error)

        assertEquals(DownloadState.CANCELLED, cancelledConnecting.state)
        assertEquals(2_500L, cancelledConnecting.downloadedBytes)
        assertNull(cancelledConnecting.error)

        assertEquals(DownloadState.CANCELLED, cancelledDownloading.state)
        assertEquals(3_000L, cancelledDownloading.downloadedBytes)
        assertEquals(3_000L, cancelledDownloading.updatedAtEpochMillis)
        assertNull(cancelledDownloading.error)

        assertEquals(DownloadState.CANCELLED, cancelledPaused.state)
        assertEquals(10_000L, cancelledPaused.downloadedBytes)
        assertEquals(5_000L, cancelledPaused.updatedAtEpochMillis)
        assertNull(cancelledPaused.error)

        assertEquals(DownloadState.CANCELLED, cancelledFailed.state)
        assertEquals(100L, cancelledFailed.downloadedBytes)
        assertNull(cancelledFailed.error)
    }

    @Test
    fun isIdempotentForCompletedAndCancelled() {
        val completed = queued().copy(
            downloadedBytes = 10_000,
            state = DownloadState.COMPLETED,
            completedAtEpochMillis = 5_000,
        )
        val cancelled = DownloadStateMachine.transition(queued(), DownloadState.CANCELLED, 2_000)

        assertSame(completed, DownloadCancelMutation.apply(completed, 1L, 9_000L))
        assertSame(cancelled, DownloadCancelMutation.apply(cancelled, 9_000L, 9_000L))
        assertEquals(10_000L, completed.downloadedBytes)
        assertEquals(0L, cancelled.downloadedBytes)
    }

    @Test
    fun rejectsNegativeFileLengthWhileCancellable() {
        val connecting = DownloadStateMachine.transition(queued(), DownloadState.CONNECTING, 2_000)
        assertThrows(IllegalArgumentException::class.java) {
            DownloadCancelMutation.apply(connecting, fileLengthBytes = -1L, nowEpochMillis = 3_000L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            DownloadCancelMutation.apply(queued(), fileLengthBytes = -1L, nowEpochMillis = 3_000L)
        }
    }
}
