package com.espitman.sdm.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadAutoRetryPolicyTest {
    @Test
    fun networkTimeoutAndTransientHttpRetryWithOneThenTwoSecondBackoff() {
        assertTrue(DownloadAutoRetryPolicy.isAutomaticallyRetryable(DownloadFailure.NETWORK_LOSS))
        assertTrue(DownloadAutoRetryPolicy.isAutomaticallyRetryable(DownloadFailure.TIMEOUT))
        assertTrue(DownloadAutoRetryPolicy.isAutomaticallyRetryable(DownloadFailure.TRANSIENT_HTTP))

        assertEquals(1_000L, DownloadAutoRetryPolicy.delayBeforeAutomaticRetryMs(0))
        assertEquals(2_000L, DownloadAutoRetryPolicy.delayBeforeAutomaticRetryMs(1))
        assertNull(DownloadAutoRetryPolicy.delayBeforeAutomaticRetryMs(2))
        assertNull(DownloadAutoRetryPolicy.delayBeforeAutomaticRetryMs(Int.MAX_VALUE))

        assertTrue(DownloadAutoRetryPolicy.shouldAutomaticallyRetry(failed("UnknownHostException", count = 0)))
        assertTrue(DownloadAutoRetryPolicy.shouldAutomaticallyRetry(failed("SocketTimeoutException: timeout", count = 1)))
        assertTrue(DownloadAutoRetryPolicy.shouldAutomaticallyRetry(failed("HTTP 503: Service Unavailable", count = 0)))
        assertFalse(DownloadAutoRetryPolicy.shouldAutomaticallyRetry(failed("HTTP 500: Internal Server Error", count = 2)))
    }

    @Test
    fun expiredForbiddenAndStorageFailuresDoNotRetryAutomatically() {
        assertFalse(DownloadAutoRetryPolicy.isAutomaticallyRetryable(DownloadFailure.EXPIRED_LINK))
        assertFalse(DownloadAutoRetryPolicy.isAutomaticallyRetryable(DownloadFailure.INSUFFICIENT_STORAGE))
        assertFalse(DownloadAutoRetryPolicy.isAutomaticallyRetryable(DownloadFailure.HTTP_ERROR))
        assertFalse(DownloadAutoRetryPolicy.isAutomaticallyRetryable(DownloadFailure.OTHER))

        assertFalse(DownloadAutoRetryPolicy.shouldAutomaticallyRetry(failed("HTTP 410: Gone")))
        assertFalse(DownloadAutoRetryPolicy.shouldAutomaticallyRetry(failed("HTTP 403: Forbidden")))
        assertFalse(DownloadAutoRetryPolicy.shouldAutomaticallyRetry(failed("ENOSPC")))
        assertFalse(
            DownloadAutoRetryPolicy.shouldAutomaticallyRetry(
                failed("javax.net.ssl.SSLHandshakeException: Handshake failed"),
            ),
        )
        assertFalse(
            DownloadAutoRetryPolicy.shouldAutomaticallyRetry(
                failed("javax.net.ssl.SSLHandshakeException: Read error: I/O error during system call"),
            ),
        )
        assertFalse(
            DownloadAutoRetryPolicy.shouldAutomaticallyRetry(
                failed("SSLHandshakeException: SSL handshake timed out"),
            ),
        )
    }

    @Test
    fun nonFailedRecordsAreNotAutomaticallyRetried() {
        val queued = Download(
            id = "queued",
            url = "https://example.com/queued.bin",
            fileName = "queued.bin",
            createdAtEpochMillis = 1_000L,
        )
        assertFalse(DownloadAutoRetryPolicy.shouldAutomaticallyRetry(queued))
    }

    private fun failed(error: String, count: Int = 0) = Download(
        id = "failed",
        url = "https://example.com/file.bin",
        fileName = "file.bin",
        destinationPath = "/tmp/file.bin",
        state = DownloadState.FAILED,
        error = error,
        createdAtEpochMillis = 1_000L,
        automaticRetryCount = count,
    )
}
