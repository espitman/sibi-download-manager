package com.espitman.sdm.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecoverInterruptedActiveMutationTest {
    @Test
    fun requeuesConnectingAndDownloadingWithoutConsumingRetryBudget() {
        val connecting = DownloadStateMachine.transition(
            download("connecting", DownloadState.QUEUED),
            DownloadState.CONNECTING,
            2_000,
        ).copy(
            downloadedBytes = 12L,
            destinationPath = "/tmp/connecting.bin",
            etag = "\"abc\"",
            lastModified = "Wed, 21 Oct 2015 07:28:00 GMT",
            acceptsRanges = true,
            automaticRetryCount = 2,
        )
        val downloading = DownloadStateMachine.transition(connecting.copy(id = "downloading"), DownloadState.DOWNLOADING, 3_000)
            .copy(downloadedBytes = 40L, destinationPath = "/tmp/downloading.bin")

        val recoveredConnecting = RecoverInterruptedActiveMutation.apply(connecting, nowEpochMillis = 9_000L)!!
        assertEquals(DownloadState.QUEUED, recoveredConnecting.state)
        assertEquals(12L, recoveredConnecting.downloadedBytes)
        assertEquals("/tmp/connecting.bin", recoveredConnecting.destinationPath)
        assertEquals("\"abc\"", recoveredConnecting.etag)
        assertEquals("Wed, 21 Oct 2015 07:28:00 GMT", recoveredConnecting.lastModified)
        assertEquals(true, recoveredConnecting.acceptsRanges)
        assertEquals(2, recoveredConnecting.automaticRetryCount)
        assertNull(recoveredConnecting.error)
        assertNull(recoveredConnecting.pauseCause)
        assertEquals(9_000L, recoveredConnecting.updatedAtEpochMillis)

        val recoveredDownloading = RecoverInterruptedActiveMutation.apply(downloading, nowEpochMillis = 1_500L)!!
        assertEquals(DownloadState.QUEUED, recoveredDownloading.state)
        assertEquals(40L, recoveredDownloading.downloadedBytes)
        assertEquals("/tmp/downloading.bin", recoveredDownloading.destinationPath)
        assertEquals(2, recoveredDownloading.automaticRetryCount)
        assertEquals(3_000L, recoveredDownloading.updatedAtEpochMillis)
    }

    @Test
    fun nonActiveAndTerminalRecordsDoNotMutate() {
        val queued = download("queued", DownloadState.QUEUED)
        val paused = DownloadStateMachine.transition(
            DownloadStateMachine.transition(queued.copy(id = "paused"), DownloadState.CONNECTING, 2_000),
            DownloadState.PAUSED,
            3_000,
        )
        val manualPaused = paused
        val networkPaused = paused.copy(id = "network", pauseCause = DownloadPauseCause.NETWORK_POLICY)
        val failed = download("failed", DownloadState.FAILED, error = "HTTP 404: Not Found")
        val cancelled = download("cancelled", DownloadState.CANCELLED)
        val completed = download(
            "completed",
            DownloadState.COMPLETED,
            downloadedBytes = 100L,
            completedAt = 5_000L,
        )

        assertNull(RecoverInterruptedActiveMutation.apply(queued, 9_000L))
        assertNull(RecoverInterruptedActiveMutation.apply(manualPaused, 9_000L))
        assertNull(RecoverInterruptedActiveMutation.apply(networkPaused, 9_000L))
        assertNull(RecoverInterruptedActiveMutation.apply(failed, 9_000L))
        assertNull(RecoverInterruptedActiveMutation.apply(cancelled, 9_000L))
        assertNull(RecoverInterruptedActiveMutation.apply(completed, 9_000L))
        assertEquals(DownloadState.FAILED, failed.state)
        assertEquals("HTTP 404: Not Found", failed.error)
        assertEquals(DownloadState.PAUSED, manualPaused.state)
        assertNull(manualPaused.pauseCause)
    }

    private fun download(
        id: String,
        state: DownloadState,
        downloadedBytes: Long = 0L,
        error: String? = null,
        completedAt: Long? = null,
    ) = Download(
        id = id,
        url = "https://example.com/$id.bin",
        fileName = "$id.bin",
        totalBytes = 100L,
        downloadedBytes = downloadedBytes,
        state = state,
        error = error,
        createdAtEpochMillis = 1_000L,
        updatedAtEpochMillis = 1_000L,
        completedAtEpochMillis = completedAt,
    )
}
