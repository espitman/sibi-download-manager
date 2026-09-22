package com.espitman.sdm.ui

import com.espitman.sdm.domain.DownloadFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadFailurePresentationTest {
    @Test
    fun classifiedLabelsCoverEveryFailureCategory() {
        val samples = listOf(
            "java.net.UnknownHostException: Unable to resolve host" to DownloadFailure.NETWORK_LOSS,
            "SocketTimeoutException: timeout" to DownloadFailure.TIMEOUT,
            "HTTP 410: Gone" to DownloadFailure.EXPIRED_LINK,
            "ENOSPC" to DownloadFailure.INSUFFICIENT_STORAGE,
            "HTTP 429: Too Many Requests" to DownloadFailure.TRANSIENT_HTTP,
            "HTTP 404: Not Found" to DownloadFailure.HTTP_ERROR,
            "Interrupted when the app process stopped" to DownloadFailure.OTHER,
        )
        assertEquals(DownloadFailure.entries.toSet(), samples.map { it.second }.toSet())

        val labels = samples.map { (error, failure) ->
            val label = failedDownloadCardLabel(error)
            assertEquals(failure.label, label)
            label
        }.toSet()
        assertEquals(DownloadFailure.entries.size, labels.size)
        assertTrue(labels.containsAll(setOf("Network lost", "Timed out", "Link expired", "Not enough storage")))
    }

    @Test
    fun lastErrorJoinsClassifiedLabelWithSanitizedDetail() {
        assertEquals(
            "Network lost · Connection reset by peer",
            failedDownloadLastError("Connection reset by peer"),
        )
        assertEquals(
            "Timed out · SocketTimeoutException: timeout",
            failedDownloadLastError("SocketTimeoutException: timeout"),
        )
        assertEquals(
            "Link expired · HTTP 403: Forbidden",
            failedDownloadLastError("HTTP 403: Forbidden"),
        )
        assertEquals(
            "Not enough storage · java.io.IOException: No space left on device",
            failedDownloadLastError("java.io.IOException: No space left on device"),
        )
    }

    @Test
    fun sanitizationCollapsesNewlinesAndBoundsLength() {
        assertEquals(
            "Not enough storage · java.io.IOException: No space left on device",
            failedDownloadLastError("java.io.IOException:\nNo space left on device"),
        )
        assertEquals("line1 line2 line3", sanitizePersistedErrorDetail("line1\nline2\r\nline3"))
        assertFalse(failedDownloadLastError("timeout\nwhile waiting").contains("\n"))

        val long = "x".repeat(FAILED_DOWNLOAD_ERROR_DETAIL_MAX_LENGTH + 40)
        val sanitized = sanitizePersistedErrorDetail(long)
        assertEquals(FAILED_DOWNLOAD_ERROR_DETAIL_MAX_LENGTH, sanitized.length)
        assertTrue(sanitized.endsWith("…"))
        assertEquals("x".repeat(FAILED_DOWNLOAD_ERROR_DETAIL_MAX_LENGTH - 1) + "…", sanitized)
        assertFalse(sanitizePersistedErrorDetail("first\nsecond").contains("\n"))
        assertEquals("", sanitizePersistedErrorDetail("   \n\t  "))
        assertEquals("Download failed", failedDownloadLastError("   \n  "))
    }

    @Test
    fun lastErrorOmitsUrlsTokensAndFilesystemPaths() {
        val signed = failedDownloadLastError(
            "Redirect cycle detected: https://cdn.example.com/file.bin?token=s3cret",
        )
        assertTrue(signed.startsWith("Download failed · "))
        assertFalse(signed.contains("cdn.example.com"))
        assertFalse(signed.contains("s3cret"))
        assertFalse(signed.contains("token="))

        val path = failedDownloadLastError(
            "Destination already exists: /storage/emulated/0/Android/data/com.espitman.sdm/files/Download/secret.pdf",
        )
        assertEquals("Download failed · Destination already exists", path)
        assertFalse(path.contains("/storage"))
        assertFalse(path.contains("secret.pdf"))

        val host = failedDownloadLastError("Failed to connect to files.internal/10.0.0.8:443")
        assertEquals("Network lost · Failed to connect to", host)
        assertFalse(host.contains("files.internal"))
        assertFalse(host.contains("10.0.0.8"))
    }
}
