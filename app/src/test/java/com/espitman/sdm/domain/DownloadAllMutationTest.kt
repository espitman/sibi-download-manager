package com.espitman.sdm.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadAllMutationTest {
    @Test
    fun requeuesPausedFailedAndCancelledWhilePreservingOffsetsAndClearingErrors() {
        val paused = download("paused", DownloadState.PAUSED, downloadedBytes = 1_234L)
        val failed = download("failed", DownloadState.FAILED, downloadedBytes = 50L, error = "stale link")
        val cancelled = download("cancelled", DownloadState.CANCELLED, downloadedBytes = 9L)

        val requeuedPaused = DownloadAllMutation.apply(paused, nowEpochMillis = 500L)!!
        val requeuedFailed = DownloadAllMutation.apply(failed, nowEpochMillis = 4_000L)!!
        val requeuedCancelled = DownloadAllMutation.apply(cancelled, nowEpochMillis = 4_000L)!!

        assertEquals(DownloadState.QUEUED, requeuedPaused.state)
        assertEquals(1_234L, requeuedPaused.downloadedBytes)
        assertNull(requeuedPaused.error)
        assertEquals(1_000L, requeuedPaused.updatedAtEpochMillis)

        assertEquals(DownloadState.QUEUED, requeuedFailed.state)
        assertEquals(50L, requeuedFailed.downloadedBytes)
        assertNull(requeuedFailed.error)
        assertEquals(4_000L, requeuedFailed.updatedAtEpochMillis)

        assertEquals(DownloadState.QUEUED, requeuedCancelled.state)
        assertEquals(9L, requeuedCancelled.downloadedBytes)
        assertNull(requeuedCancelled.error)
    }

    @Test
    fun activeQueuedAndCompletedAreNoOps() {
        val queued = download("queued", DownloadState.QUEUED, downloadedBytes = 3L)
        val connecting = DownloadStateMachine.transition(queued.copy(id = "connecting"), DownloadState.CONNECTING, 2_000)
        val downloading = DownloadStateMachine.transition(connecting.copy(id = "downloading"), DownloadState.DOWNLOADING, 3_000)
            .copy(downloadedBytes = 8L)
        val completed = download(
            "completed",
            DownloadState.COMPLETED,
            downloadedBytes = 100L,
            totalBytes = 100L,
            completedAt = 5_000L,
        )

        assertNull(DownloadAllMutation.apply(queued, 9_000L))
        assertNull(DownloadAllMutation.apply(connecting, 9_000L))
        assertNull(DownloadAllMutation.apply(downloading, 9_000L))
        assertNull(DownloadAllMutation.apply(completed, 9_000L))
        assertEquals(100L, completed.downloadedBytes)
        assertEquals(DownloadState.COMPLETED, completed.state)
    }

    @Test
    fun selectionMatchesRequeueStatesOnly() {
        assertTrue(DownloadAllMutation.shouldRequeue(DownloadState.PAUSED))
        assertTrue(DownloadAllMutation.shouldRequeue(DownloadState.FAILED))
        assertTrue(DownloadAllMutation.shouldRequeue(DownloadState.CANCELLED))
        assertFalse(DownloadAllMutation.shouldRequeue(DownloadState.QUEUED))
        assertFalse(DownloadAllMutation.shouldRequeue(DownloadState.CONNECTING))
        assertFalse(DownloadAllMutation.shouldRequeue(DownloadState.DOWNLOADING))
        assertFalse(DownloadAllMutation.shouldRequeue(DownloadState.COMPLETED))
    }

    private fun download(
        id: String,
        state: DownloadState,
        downloadedBytes: Long = 0L,
        totalBytes: Long? = 10_000L,
        error: String? = null,
        completedAt: Long? = null,
    ) = Download(
        id = id,
        url = "https://example.com/$id.bin",
        fileName = "$id.bin",
        totalBytes = totalBytes,
        downloadedBytes = downloadedBytes,
        state = state,
        error = error,
        createdAtEpochMillis = 1_000L,
        updatedAtEpochMillis = 1_000L,
        completedAtEpochMillis = completedAt,
    )
}
