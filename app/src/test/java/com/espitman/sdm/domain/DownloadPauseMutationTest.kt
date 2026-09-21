package com.espitman.sdm.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class DownloadPauseMutationTest {
    private fun queued() = Download(
        id = "download-1",
        url = "https://example.com/file.zip",
        fileName = "file.zip",
        totalBytes = 10_000,
        createdAtEpochMillis = 1_000,
    )

    @Test
    fun rewindsStaleProgressToExactBoundedFileLengthAndPauses() {
        val downloading = DownloadStateMachine.transition(
            DownloadStateMachine.transition(queued(), DownloadState.CONNECTING, 2_000),
            DownloadState.DOWNLOADING,
            3_000,
        ).copy(downloadedBytes = 5_000L, updatedAtEpochMillis = 3_000L)

        val paused = DownloadPauseMutation.apply(downloading, fileLengthBytes = 3_000L, nowEpochMillis = 2_500L)

        assertEquals(DownloadState.PAUSED, paused.state)
        assertEquals(3_000L, paused.downloadedBytes)
        assertEquals(3_000L, paused.updatedAtEpochMillis)
        assertEquals(null, paused.error)
    }

    @Test
    fun boundsFileLengthByKnownTotal() {
        val downloading = DownloadStateMachine.transition(
            DownloadStateMachine.transition(queued(), DownloadState.CONNECTING, 2_000),
            DownloadState.DOWNLOADING,
            3_000,
        ).copy(downloadedBytes = 1_000L)

        val paused = DownloadPauseMutation.apply(downloading, fileLengthBytes = 12_000L, nowEpochMillis = 4_000L)

        assertEquals(10_000L, paused.downloadedBytes)
        assertEquals(DownloadState.PAUSED, paused.state)
        assertEquals(4_000L, paused.updatedAtEpochMillis)
    }

    @Test
    fun isIdempotentForNonActiveStates() {
        val originalQueued = queued()
        val paused = DownloadStateMachine.transition(
            DownloadStateMachine.transition(
                DownloadStateMachine.transition(queued(), DownloadState.CONNECTING, 2_000),
                DownloadState.DOWNLOADING,
                3_000,
            ).copy(downloadedBytes = 100L),
            DownloadState.PAUSED,
            4_000,
        )
        val failed = queued().copy(state = DownloadState.FAILED, error = "boom")
        val completed = queued().copy(
            downloadedBytes = 10_000,
            state = DownloadState.COMPLETED,
            completedAtEpochMillis = 5_000,
        )
        val cancelled = DownloadStateMachine.transition(queued(), DownloadState.CANCELLED, 2_000)

        assertSame(originalQueued, DownloadPauseMutation.apply(originalQueued, 9_000L, 9_000L))
        assertSame(paused, DownloadPauseMutation.apply(paused, 9_000L, 9_000L))
        assertSame(failed, DownloadPauseMutation.apply(failed, 9_000L, 9_000L))
        assertSame(completed, DownloadPauseMutation.apply(completed, 9_000L, 9_000L))
        assertSame(cancelled, DownloadPauseMutation.apply(cancelled, 9_000L, 9_000L))
        assertEquals(100L, paused.downloadedBytes)
    }

    @Test
    fun rejectsNegativeFileLengthWhileActive() {
        val connecting = DownloadStateMachine.transition(queued(), DownloadState.CONNECTING, 2_000)
        assertThrows(IllegalArgumentException::class.java) {
            DownloadPauseMutation.apply(connecting, fileLengthBytes = -1L, nowEpochMillis = 3_000L)
        }
    }
}
