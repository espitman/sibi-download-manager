package com.espitman.sdm.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadResumeMutationTest {
    private fun queued() = Download(
        id = "download-1",
        url = "https://example.com/file.zip",
        fileName = "file.zip",
        totalBytes = 10_000,
        downloadedBytes = 1_000,
        createdAtEpochMillis = 1_000,
    )

    @Test
    fun pausedRecordMovesToQueuedWithoutChangingOffset() {
        val paused = DownloadStateMachine.transition(
            DownloadStateMachine.transition(
                DownloadStateMachine.transition(queued(), DownloadState.CONNECTING, 2_000),
                DownloadState.DOWNLOADING,
                3_000,
            ).copy(downloadedBytes = 4_000L),
            DownloadState.PAUSED,
            4_000,
        )

        val queuedAgain = DownloadResumeMutation.apply(paused, nowEpochMillis = 3_500L)

        assertEquals(DownloadState.QUEUED, queuedAgain!!.state)
        assertEquals(4_000L, queuedAgain.downloadedBytes)
        assertEquals(4_000L, queuedAgain.updatedAtEpochMillis)
        assertEquals(null, queuedAgain.error)
        assertEquals(null, queuedAgain.pauseCause)
    }

    @Test
    fun resumeClearsNetworkPolicyCause() {
        val paused = DownloadStateMachine.transition(
            DownloadStateMachine.transition(
                DownloadStateMachine.transition(queued(), DownloadState.CONNECTING, 2_000),
                DownloadState.DOWNLOADING,
                3_000,
            ).copy(downloadedBytes = 4_000L),
            DownloadState.PAUSED,
            4_000,
        ).copy(pauseCause = DownloadPauseCause.NETWORK_POLICY)

        val queuedAgain = DownloadResumeMutation.apply(paused, nowEpochMillis = 5_000L)

        assertEquals(DownloadState.QUEUED, queuedAgain!!.state)
        assertEquals(null, queuedAgain.pauseCause)
        assertEquals(4_000L, queuedAgain.downloadedBytes)
    }

    @Test
    fun nonPausedRecordsDoNotMutate() {
        val originalQueued = queued()
        val connecting = DownloadStateMachine.transition(queued(), DownloadState.CONNECTING, 2_000)
        val failed = queued().copy(state = DownloadState.FAILED, error = "boom")

        assertNull(DownloadResumeMutation.apply(originalQueued, 9_000L))
        assertNull(DownloadResumeMutation.apply(connecting, 9_000L))
        assertNull(DownloadResumeMutation.apply(failed, 9_000L))
    }
}
