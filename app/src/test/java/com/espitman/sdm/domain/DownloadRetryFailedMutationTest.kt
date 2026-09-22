package com.espitman.sdm.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class DownloadRetryFailedMutationTest {
    @Test
    fun successfulFreshDownloadStartsAtZeroAutomaticRetries() {
        val queued = Download(
            id = "fresh",
            url = "https://example.com/fresh.bin",
            fileName = "fresh.bin",
            createdAtEpochMillis = 1_000L,
        )
        assertEquals(0, queued.automaticRetryCount)
    }

    @Test
    fun negativeAutomaticRetryCountIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            Download(
                id = "bad",
                url = "https://example.com/bad.bin",
                fileName = "bad.bin",
                createdAtEpochMillis = 1_000L,
                automaticRetryCount = -1,
            )
        }
    }

    @Test
    fun automaticRetryIncrementsOnceAndPreservesProgress() {
        val failed = failed(downloadedBytes = 77L, automaticRetryCount = 2)
        val retried = DownloadRetryFailedMutation.apply(failed, automatic = true, nowEpochMillis = 4_000L)!!

        assertEquals(DownloadState.QUEUED, retried.state)
        assertNull(retried.error)
        assertEquals(77L, retried.downloadedBytes)
        assertEquals("/tmp/file.bin", retried.destinationPath)
        assertEquals(3, retried.automaticRetryCount)
        assertEquals(4_000L, retried.updatedAtEpochMillis)
        assertEquals(failed.url, retried.url)
    }

    @Test
    fun manualRetryResetsCountAndUsesMonotonicTimestamp() {
        val failed = failed(automaticRetryCount = 4, updatedAt = 5_000L)
        val retried = DownloadRetryFailedMutation.apply(failed, automatic = false, nowEpochMillis = 1_000L)!!

        assertEquals(DownloadState.QUEUED, retried.state)
        assertNull(retried.error)
        assertEquals(0, retried.automaticRetryCount)
        assertEquals(5_000L, retried.updatedAtEpochMillis)
        assertEquals(12L, retried.downloadedBytes)
    }

    @Test
    fun nonFailedRecordsAreUnchanged() {
        val queued = Download(
            id = "queued",
            url = "https://example.com/queued.bin",
            fileName = "queued.bin",
            destinationPath = "/tmp/queued.bin",
            downloadedBytes = 3L,
            createdAtEpochMillis = 1_000L,
            automaticRetryCount = 2,
        )
        assertNull(DownloadRetryFailedMutation.apply(queued, automatic = true, nowEpochMillis = 9_000L))
        assertEquals(2, queued.automaticRetryCount)
        assertEquals(DownloadState.QUEUED, queued.state)
    }

    private fun failed(
        downloadedBytes: Long = 12L,
        automaticRetryCount: Int = 1,
        updatedAt: Long = 1_000L,
    ) = Download(
        id = "failed",
        url = "https://example.com/file.bin",
        fileName = "file.bin",
        destinationPath = "/tmp/file.bin",
        totalBytes = 100L,
        downloadedBytes = downloadedBytes,
        state = DownloadState.FAILED,
        error = "HTTP 503: Service Unavailable",
        createdAtEpochMillis = 1_000L,
        updatedAtEpochMillis = updatedAt,
        automaticRetryCount = automaticRetryCount,
    )
}
