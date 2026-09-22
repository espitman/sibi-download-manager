package com.espitman.sdm.ui

import com.espitman.sdm.domain.Download
import com.espitman.sdm.domain.DownloadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadProgressMetricsTest {

    private fun createDownload(
        totalBytes: Long? = 1000L,
        downloadedBytes: Long = 500L,
        state: DownloadState = DownloadState.DOWNLOADING,
        createdAt: Long = 1000L,
        startedAt: Long? = 1000L,
        completedAt: Long? = null,
        error: String? = null,
    ): Download {
        return Download(
            id = "test-download-id",
            url = "https://example.com/file.zip",
            fileName = "file.zip",
            totalBytes = totalBytes,
            downloadedBytes = downloadedBytes,
            state = state,
            error = error,
            createdAtEpochMillis = createdAt,
            updatedAtEpochMillis = createdAt,
            startedAtEpochMillis = startedAt,
            completedAtEpochMillis = completedAt,
        )
    }

    @Test
    fun testKnownSizeProgress() {
        val download = createDownload(
            totalBytes = 2000L,
            downloadedBytes = 500L,
            startedAt = 1000L,
        )
        // Elapsed: 2000ms = 2.0s. Speed = 500 / 2 = 250 B/s.
        // Remaining = 1500 bytes. ETA = ceil(1500 / 250) = 6s.
        val metrics = calculateDownloadProgressMetrics(download, nowEpochMillis = 3000L)

        assertEquals(0.25f, metrics.fraction!!, 0.0001f)
        assertEquals("25%", metrics.percentLabel)
        assertEquals(250L, metrics.bytesPerSecond)
        assertEquals(6L, metrics.etaSeconds)
    }

    @Test
    fun testUnknownSizeProgress() {
        val download = createDownload(
            totalBytes = null,
            downloadedBytes = 1024L,
            startedAt = 1000L,
        )
        // Elapsed: 1000ms = 1.0s. Speed = 1024 B/s.
        val metrics = calculateDownloadProgressMetrics(download, nowEpochMillis = 2000L)

        assertNull(metrics.fraction)
        assertEquals("—", metrics.percentLabel)
        assertEquals(1024L, metrics.bytesPerSecond)
        assertNull(metrics.etaSeconds)
    }

    @Test
    fun testZeroTotalSizeProgress() {
        val download = createDownload(
            totalBytes = 0L,
            downloadedBytes = 0L,
            startedAt = 1000L,
        )
        val metrics = calculateDownloadProgressMetrics(download, nowEpochMillis = 2000L)

        assertNull(metrics.fraction)
        assertEquals("—", metrics.percentLabel)
        assertEquals(0L, metrics.bytesPerSecond)
        assertNull(metrics.etaSeconds)
    }

    @Test
    fun testZeroElapsedSeconds() {
        val download = createDownload(
            totalBytes = 1000L,
            downloadedBytes = 500L,
            startedAt = 1000L,
        )
        // now == startedAt -> elapsed = 0
        val metrics = calculateDownloadProgressMetrics(download, nowEpochMillis = 1000L)

        assertEquals(0.5f, metrics.fraction!!, 0.0001f)
        assertEquals("50%", metrics.percentLabel)
        assertEquals(0L, metrics.bytesPerSecond)
        assertNull(metrics.etaSeconds)

        // startedAt is null
        val noStartDownload = createDownload(
            totalBytes = 1000L,
            downloadedBytes = 500L,
            startedAt = null,
        )
        val metricsNoStart = calculateDownloadProgressMetrics(noStartDownload, nowEpochMillis = 2000L)
        assertEquals(0L, metricsNoStart.bytesPerSecond)
        assertNull(metricsNoStart.etaSeconds)
    }

    @Test
    fun testCompletedStateProgress() {
        val download = createDownload(
            totalBytes = 1000L,
            downloadedBytes = 1000L,
            state = DownloadState.COMPLETED,
            startedAt = 1000L,
            completedAt = 2000L,
        )
        val metrics = calculateDownloadProgressMetrics(download, nowEpochMillis = 3000L)

        assertEquals(1.0f, metrics.fraction!!, 0.0001f)
        assertEquals("100%", metrics.percentLabel)
        assertNull(metrics.etaSeconds)

        // Completed with unknown total bytes
        val completedUnknown = createDownload(
            totalBytes = null,
            downloadedBytes = 500L,
            state = DownloadState.COMPLETED,
            startedAt = 1000L,
            completedAt = 2000L,
        )
        val metricsUnknown = calculateDownloadProgressMetrics(completedUnknown, nowEpochMillis = 3000L)
        assertEquals(1.0f, metricsUnknown.fraction!!, 0.0001f)
        assertEquals("100%", metricsUnknown.percentLabel)
        assertNull(metricsUnknown.etaSeconds)
    }

    @Test
    fun testFractionClampingThroughValidInputs() {
        val zeroDownload = createDownload(
            totalBytes = 1000L,
            downloadedBytes = 0L,
        )
        val zeroMetrics = calculateDownloadProgressMetrics(zeroDownload, nowEpochMillis = 2000L)
        assertEquals(0.0f, zeroMetrics.fraction!!, 0.0001f)
        assertEquals("0%", zeroMetrics.percentLabel)

        val fullDownload = createDownload(
            totalBytes = 1000L,
            downloadedBytes = 1000L,
        )
        val fullMetrics = calculateDownloadProgressMetrics(fullDownload, nowEpochMillis = 2000L)
        assertEquals(1.0f, fullMetrics.fraction!!, 0.0001f)
        assertEquals("100%", fullMetrics.percentLabel)
    }

    @Test
    fun testOverflowSafeEta() {
        // Very large remaining bytes and relatively small speed, or large values where addition might overflow Long.MAX_VALUE
        // Long.MAX_VALUE is ~9.22e18.
        val largeRemaining = Long.MAX_VALUE - 10L
        val download = Download(
            id = "test-id",
            url = "https://example.com/big.iso",
            fileName = "big.iso",
            totalBytes = Long.MAX_VALUE,
            downloadedBytes = 10L,
            state = DownloadState.DOWNLOADING,
            createdAtEpochMillis = 1000L,
            startedAtEpochMillis = 1000L,
        )
        // Elapsed: 10s -> speed = 1 B/s
        val metrics = calculateDownloadProgressMetrics(download, nowEpochMillis = 11000L)
        assertEquals(1L, metrics.bytesPerSecond)
        assertEquals(largeRemaining, metrics.etaSeconds)

        // Test ceiling division specifically: remaining = 10, speed = 3 -> ceil(10/3) = 4
        val ceilDownload = createDownload(
            totalBytes = 100L,
            downloadedBytes = 90L,
            startedAt = 1000L,
        )
        // remaining = 10. Elapsed = 30s -> speed = 90 / 30 = 3 B/s.
        val ceilMetrics = calculateDownloadProgressMetrics(ceilDownload, nowEpochMillis = 31000L)
        assertEquals(3L, ceilMetrics.bytesPerSecond)
        assertEquals(4L, ceilMetrics.etaSeconds)
    }

    @Test
    fun testFormatting() {
        // Speed formatting
        assertEquals("—", formatDownloadSpeed(0L))
        assertEquals("—", formatDownloadSpeed(-100L))
        assertEquals("0 MB/s", formatDownloadSpeed(500L))
        assertEquals("0 MB/s", formatDownloadSpeed(1024L))

        // ETA formatting
        assertEquals("—", formatEta(null))
        assertEquals("—", formatEta(-1L))
        assertEquals("45s", formatEta(45L))
        assertEquals("0s", formatEta(0L))
        assertEquals("5m 20s", formatEta(320L))
        assertEquals("1h 15m", formatEta(4500L))
        assertEquals("2h 0m", formatEta(7200L))
    }
}
