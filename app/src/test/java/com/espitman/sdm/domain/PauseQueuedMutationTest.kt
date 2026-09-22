package com.espitman.sdm.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PauseQueuedMutationTest {
    @Test
    fun queuedMovesToPausedAtExactDownloadedOffset() {
        val queued = Download(
            id = "queued",
            url = "https://example.com/queued.bin",
            fileName = "queued.bin",
            totalBytes = 10_000L,
            downloadedBytes = 777L,
            createdAtEpochMillis = 1_000L,
            updatedAtEpochMillis = 1_500L,
        )

        val paused = PauseQueuedMutation.apply(queued, nowEpochMillis = 1_200L)!!

        assertEquals(DownloadState.PAUSED, paused.state)
        assertEquals(777L, paused.downloadedBytes)
        assertEquals(1_500L, paused.updatedAtEpochMillis)
        assertNull(paused.error)
    }

    @Test
    fun nonQueuedStatesAreUntouched() {
        val connecting = DownloadStateMachine.transition(
            download("connecting", DownloadState.QUEUED),
            DownloadState.CONNECTING,
            2_000,
        )
        val downloading = DownloadStateMachine.transition(connecting.copy(id = "downloading"), DownloadState.DOWNLOADING, 3_000)
        val paused = download("paused", DownloadState.PAUSED, downloadedBytes = 4L)
        val failed = download("failed", DownloadState.FAILED, error = "boom")
        val cancelled = download("cancelled", DownloadState.CANCELLED)
        val completed = download(
            "completed",
            DownloadState.COMPLETED,
            downloadedBytes = 10_000L,
            totalBytes = 10_000L,
            completedAt = 5_000L,
        )

        assertNull(PauseQueuedMutation.apply(connecting, 9_000L))
        assertNull(PauseQueuedMutation.apply(downloading, 9_000L))
        assertNull(PauseQueuedMutation.apply(paused, 9_000L))
        assertNull(PauseQueuedMutation.apply(failed, 9_000L))
        assertNull(PauseQueuedMutation.apply(cancelled, 9_000L))
        assertNull(PauseQueuedMutation.apply(completed, 9_000L))
        assertEquals(4L, paused.downloadedBytes)
        assertEquals(DownloadState.COMPLETED, completed.state)
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
