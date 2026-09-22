package com.espitman.sdm.ui

import com.espitman.sdm.domain.Download
import com.espitman.sdm.domain.DownloadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadDetailsTelemetryTest {

    private fun record(
        id: String = "details",
        url: String = "https://media.example.net/file.bin",
        fileName: String = "file.bin",
        destinationPath: String? = "/Download/SDM/file.bin",
        state: DownloadState = DownloadState.DOWNLOADING,
        totalBytes: Long? = 2_342_215_680L,
        downloadedBytes: Long = 1_685_577_270L,
        etag: String? = "\"file-v1\"",
        lastModified: String? = "Thu, 22 Oct 2015 07:28:00 GMT",
        acceptsRanges: Boolean? = true,
        createdAt: Long = 1_000L,
        startedAt: Long? = 1_000L,
        completedAt: Long? = null,
        error: String? = null,
    ) = Download(
        id = id,
        url = url,
        fileName = fileName,
        etag = etag,
        lastModified = lastModified,
        destinationPath = destinationPath,
        totalBytes = totalBytes,
        downloadedBytes = downloadedBytes,
        state = state,
        error = error,
        createdAtEpochMillis = createdAt,
        updatedAtEpochMillis = createdAt,
        startedAtEpochMillis = startedAt,
        completedAtEpochMillis = completedAt,
        acceptsRanges = acceptsRanges,
    )

    private fun speed(
        currentBytesPerSecond: Long = 0L,
        samples: List<Long> = emptyList(),
    ) = DownloadDetailsSpeedSnapshot(
        currentBytesPerSecond = currentBytesPerSecond,
        samples = samples,
    )

    @Test
    fun unknownTotalUsesClearUnknownSizeAndNoLifetimeEta() {
        val presentation = mapDownloadDetailsTelemetry(
            record(
                totalBytes = null,
                downloadedBytes = 1_572_864L,
                startedAt = 0L,
            ),
            speed(currentBytesPerSecond = 1_048_576L, samples = listOf(0L, 1_048_576L)),
        )

        assertEquals("—", presentation.metrics.sizeValue)
        assertEquals("—", presentation.metrics.sizeUnit)
        assertEquals("—", presentation.metrics.remaining)
        assertEquals("1.0", presentation.metrics.speedValue)
        assertEquals("MB/s", presentation.metrics.speedUnit)
        assertEquals(listOf(0L, 1_048_576L), presentation.speedSamples)
    }

    @Test
    fun knownSizeSharesOneUnitAndSplitsCurrentSpeed() {
        val recent = 13_001_523L
        val presentation = mapDownloadDetailsTelemetry(
            record(
                downloadedBytes = 1_685_577_270L,
                totalBytes = 2_342_215_680L,
                startedAt = 0L,
            ),
            speed(currentBytesPerSecond = recent, samples = listOf(0L, recent)),
        )

        assertEquals("1.57/2.18", presentation.metrics.sizeValue)
        assertEquals("GB", presentation.metrics.sizeUnit)
        assertEquals("12.4", presentation.metrics.speedValue)
        assertEquals("MB/s", presentation.metrics.speedUnit)
    }

    @Test
    fun remainingUsesRecentSpeedNotLifetimeAverage() {
        val recentOnly = mapDownloadDetailsTelemetry(
            record(downloadedBytes = 36_000L, totalBytes = 100_000L, startedAt = 0L),
            speed(currentBytesPerSecond = 1_000L, samples = listOf(0L, 1_000L)),
        )
        assertEquals("01:04", recentOnly.metrics.remaining)

        val lifetimeWouldDiffer = mapDownloadDetailsTelemetry(
            record(downloadedBytes = 36_000L, totalBytes = 100_000L, startedAt = 0L),
            speed(currentBytesPerSecond = 2_000L, samples = listOf(0L, 2_000L)),
        )
        assertEquals("00:32", lifetimeWouldDiffer.metrics.remaining)
    }

    @Test
    fun zeroRecentSpeedDoesNotInventEtaFromElapsedLifetime() {
        val presentation = mapDownloadDetailsTelemetry(
            record(downloadedBytes = 500L, totalBytes = 1_000L, startedAt = 0L),
            speed(currentBytesPerSecond = 0L, samples = listOf(0L)),
        )
        assertEquals("—", presentation.metrics.remaining)
        assertEquals("0", presentation.metrics.speedValue)
        assertEquals("B/s", presentation.metrics.speedUnit)
    }

    @Test
    fun httpAndHttpsSecurityNeverInventTlsVersion() {
        val https = mapDownloadDetailsTelemetry(
            record(url = "https://media.sibicdn.net/releases/Dune.mkv"),
            speed(),
        )
        val http = mapDownloadDetailsTelemetry(
            record(url = "http://cdn.example.com:8080/archive.zip"),
            speed(),
        )

        assertEquals("media.sibicdn.net", https.technical.sourceHost)
        assertEquals("HTTPS", https.technical.security)
        assertEquals("cdn.example.com", http.technical.sourceHost)
        assertEquals("HTTP", http.technical.security)
        assertEquals(false, https.technical.security.contains("TLS", ignoreCase = true))
        assertEquals(false, http.technical.security.contains("TLS", ignoreCase = true))
    }

    @Test
    fun resumeAvailableOnlyWithAcceptsRangesAndUsableValidator() {
        val available = mapDownloadDetailsTelemetry(
            record(acceptsRanges = true, etag = "\"strong\"", lastModified = null),
            speed(),
        )
        val lastModifiedOnly = mapDownloadDetailsTelemetry(
            record(acceptsRanges = true, etag = null, lastModified = "Thu, 22 Oct 2015 07:28:00 GMT"),
            speed(),
        )
        assertEquals("Available", available.technical.resumeSupport)
        assertEquals("Available", lastModifiedOnly.technical.resumeSupport)
    }

    @Test
    fun resumeUnavailableWhenFalseOrEvidenceRulesItOut() {
        val rejected = mapDownloadDetailsTelemetry(
            record(acceptsRanges = false, etag = "\"strong\""),
            speed(),
        )
        val missingValidator = mapDownloadDetailsTelemetry(
            record(acceptsRanges = true, etag = null, lastModified = null),
            speed(),
        )
        val weakEtag = mapDownloadDetailsTelemetry(
            record(acceptsRanges = true, etag = "W/\"weak\"", lastModified = null),
            speed(),
        )
        assertEquals("Unavailable", rejected.technical.resumeSupport)
        assertEquals("Unavailable", missingValidator.technical.resumeSupport)
        assertEquals("Unavailable", weakEtag.technical.resumeSupport)
    }

    @Test
    fun resumeUnknownWhenAcceptsRangesIsMissing() {
        val unknown = mapDownloadDetailsTelemetry(
            record(acceptsRanges = null, etag = "\"strong\""),
            speed(),
        )
        assertEquals("—", unknown.technical.resumeSupport)
    }

    @Test
    fun connectionsAndStreamsFollowConnectingDownloadingVersusIdleStates() {
        val downloading = mapDownloadDetailsTelemetry(record(state = DownloadState.DOWNLOADING), speed())
        val connecting = mapDownloadDetailsTelemetry(record(state = DownloadState.CONNECTING), speed())
        val paused = mapDownloadDetailsTelemetry(record(state = DownloadState.PAUSED), speed())
        val queued = mapDownloadDetailsTelemetry(
            record(state = DownloadState.QUEUED, startedAt = null),
            speed(),
        )
        val completed = mapDownloadDetailsTelemetry(
            record(
                state = DownloadState.COMPLETED,
                downloadedBytes = 1_000L,
                totalBytes = 1_000L,
                completedAt = 2_000L,
            ),
            speed(),
        )

        assertEquals("1", downloading.metrics.connections)
        assertEquals("one active stream", downloading.technical.connectionThreads)
        assertEquals("1", connecting.metrics.connections)
        assertEquals("one active stream", connecting.technical.connectionThreads)
        assertEquals("0", paused.metrics.connections)
        assertEquals("zero active streams", paused.technical.connectionThreads)
        assertEquals("0", queued.metrics.connections)
        assertEquals("zero active streams", queued.technical.connectionThreads)
        assertEquals("0", completed.metrics.connections)
        assertEquals("zero active streams", completed.technical.connectionThreads)
    }

    @Test
    fun savePathUsesParentFolderAndUnknownMarker() {
        val withPath = mapDownloadDetailsTelemetry(
            record(destinationPath = "/storage/emulated/0/Download/SDM/file.bin"),
            speed(),
        )
        val missing = mapDownloadDetailsTelemetry(record(destinationPath = null), speed())
        assertEquals("/storage/emulated/0/Download/SDM", withPath.technical.savePath)
        assertEquals("—", missing.technical.savePath)
    }

    @Test
    fun headersAndSegmentsStayExplicitlyUnavailableWithoutInventedValues() {
        val presentation = mapDownloadDetailsTelemetry(record(), speed(samples = listOf(0L, 1_000L)))

        assertFalse(presentation.requestHeaders.available)
        assertEquals("Unavailable", presentation.requestHeaders.summary)
        assertTrue(presentation.requestHeaders.entries.isEmpty())
        assertFalse(presentation.segments.available)
        assertEquals("Unavailable", presentation.segments.summary)
        assertTrue(presentation.segments.segments.isEmpty())
        assertEquals(false, presentation.requestHeaders.summary.contains("2"))
        assertEquals(false, presentation.segments.summary.contains("16"))
        assertEquals(listOf(0L, 1_000L), presentation.speedSamples)
        assertEquals(false, presentation.speedSamples.size == 16)
    }

    @Test
    fun byteSizedPairAndHourEtaStayHonest() {
        val small = mapDownloadDetailsTelemetry(
            record(downloadedBytes = 200L, totalBytes = 800L),
            speed(currentBytesPerSecond = 1L),
        )
        assertEquals("200/800", small.metrics.sizeValue)
        assertEquals("B", small.metrics.sizeUnit)
        assertEquals("10:00", small.metrics.remaining)

        val longEta = mapDownloadDetailsTelemetry(
            record(downloadedBytes = 0L, totalBytes = 7_200L),
            speed(currentBytesPerSecond = 1L),
        )
        assertEquals("02:00:00", longEta.metrics.remaining)
    }
}
