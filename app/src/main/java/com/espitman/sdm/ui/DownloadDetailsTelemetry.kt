package com.espitman.sdm.ui

import com.espitman.sdm.download.HttpRangeResume
import com.espitman.sdm.domain.Download
import com.espitman.sdm.domain.DownloadState
import java.net.URI
import java.util.Locale
import kotlin.math.max

internal data class DownloadDetailsMetricValues(
    val speedValue: String,
    val speedUnit: String,
    val sizeValue: String,
    val sizeUnit: String,
    val remaining: String,
    val connections: String,
)

internal data class DownloadDetailsTechnicalValues(
    val sourceHost: String,
    val savePath: String,
    val security: String,
    val resumeSupport: String,
    val connectionThreads: String,
    val lastError: String? = null,
)

internal data class DownloadDetailsRequestHeaders(
    val available: Boolean,
    val summary: String,
    val entries: List<Pair<String, String>>,
)

internal data class DownloadDetailsSegmentBreakdown(
    val available: Boolean,
    val summary: String,
    val segments: List<DownloadDetailsSegment>,
)

internal data class DownloadDetailsSegment(
    val label: String,
    val fraction: Float,
    val percentLabel: String,
)

internal data class DownloadDetailsTelemetryPresentation(
    val metrics: DownloadDetailsMetricValues,
    val speedSamples: List<Long>,
    val technical: DownloadDetailsTechnicalValues,
    val requestHeaders: DownloadDetailsRequestHeaders,
    val segments: DownloadDetailsSegmentBreakdown,
)

internal data class DownloadDetailsSpeedChartPoint(
    val x: Float,
    val y: Float,
)

internal fun mapDownloadDetailsTelemetry(
    download: Download,
    speed: DownloadDetailsSpeedSnapshot,
): DownloadDetailsTelemetryPresentation {
    val connections = detailsConnectionCount(download.state)
    return DownloadDetailsTelemetryPresentation(
        metrics = DownloadDetailsMetricValues(
            speedValue = splitSpeedValue(speed.currentBytesPerSecond),
            speedUnit = splitSpeedUnit(speed.currentBytesPerSecond),
            sizeValue = sharedSizeValue(download.downloadedBytes, download.totalBytes),
            sizeUnit = sharedSizeUnit(download.downloadedBytes, download.totalBytes),
            remaining = formatDetailsRemaining(
                downloadedBytes = download.downloadedBytes,
                totalBytes = download.totalBytes,
                recentBytesPerSecond = speed.currentBytesPerSecond,
            ),
            connections = connections.toString(),
        ),
        speedSamples = speed.samples,
        technical = DownloadDetailsTechnicalValues(
            sourceHost = detailsSourceHost(download.url),
            savePath = detailsDestinationDisplay(download.destinationPath),
            security = detailsSecurityLabel(download.url),
            resumeSupport = detailsResumeSupportLabel(download),
            connectionThreads = if (connections == 1) "one active stream" else "zero active streams",
            lastError = if (download.state == DownloadState.FAILED) {
                failedDownloadLastError(download.error)
            } else {
                null
            },
        ),
        requestHeaders = UNAVAILABLE_REQUEST_HEADERS,
        segments = UNAVAILABLE_SEGMENTS,
    )
}

internal fun detailsConnectionCount(state: DownloadState): Int = when (state) {
    DownloadState.CONNECTING, DownloadState.DOWNLOADING -> 1
    else -> 0
}

internal fun detailsSourceHost(url: String): String {
    val host = runCatching { URI(url).host }.getOrNull()
    return host?.takeIf { it.isNotBlank() } ?: "—"
}

internal fun detailsSecurityLabel(url: String): String {
    val scheme = runCatching { URI(url).scheme }.getOrNull()?.lowercase(Locale.US)
    return when (scheme) {
        "https" -> "HTTPS"
        "http" -> "HTTP"
        else -> "—"
    }
}

internal fun detailsResumeSupportLabel(download: Download): String {
    val usableValidator = HttpRangeResume.ifRangeHeaderValue(
        HttpRangeResume.ResumeValidators(
            etag = download.etag,
            lastModified = download.lastModified,
        ),
    ) != null
    return when (download.acceptsRanges) {
        true -> if (usableValidator) "Available" else "Unavailable"
        false -> "Unavailable"
        null -> "—"
    }
}

internal fun splitSpeedValue(bytesPerSecond: Long): String {
    val rate = bytesPerSecond.coerceAtLeast(0L)
    val (value, index) = scaledByteValue(rate)
    return if (index == 0) {
        rate.toString()
    } else {
        String.format(Locale.US, "%.1f", value)
    }
}

internal fun splitSpeedUnit(bytesPerSecond: Long): String {
    val index = scaledByteValue(bytesPerSecond.coerceAtLeast(0L)).second
    return BYTE_UNITS[index] + "/s"
}

internal fun sharedSizeValue(downloadedBytes: Long, totalBytes: Long?): String {
    if (totalBytes == null) return "—"
    val downloaded = downloadedBytes.coerceAtLeast(0L)
    val total = totalBytes.coerceAtLeast(0L)
    val index = scaledByteValue(max(downloaded, total)).second
    return if (index == 0) {
        "$downloaded/$total"
    } else {
        val divisor = unitDivisor(index)
        String.format(Locale.US, "%.2f/%.2f", downloaded / divisor, total / divisor)
    }
}

internal fun sharedSizeUnit(downloadedBytes: Long, totalBytes: Long?): String {
    if (totalBytes == null) return "—"
    val index = scaledByteValue(max(downloadedBytes.coerceAtLeast(0L), totalBytes.coerceAtLeast(0L))).second
    return BYTE_UNITS[index]
}

internal fun formatDetailsRemaining(
    downloadedBytes: Long,
    totalBytes: Long?,
    recentBytesPerSecond: Long,
): String {
    if (totalBytes == null || recentBytesPerSecond <= 0L) return "—"
    val remainingBytes = totalBytes - downloadedBytes
    if (remainingBytes <= 0L) return "—"
    val etaSeconds = ceilDivNonNegative(remainingBytes, recentBytesPerSecond)
    val hours = etaSeconds / 3600L
    val minutes = (etaSeconds % 3600L) / 60L
    val seconds = etaSeconds % 60L
    return if (hours > 0L) {
        String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}

private fun scaledByteValue(bytes: Long): Pair<Double, Int> {
    var value = bytes.toDouble()
    var index = 0
    while (value >= 1024.0 && index < BYTE_UNITS.lastIndex) {
        value /= 1024.0
        index++
    }
    return value to index
}

private fun unitDivisor(index: Int): Double {
    var divisor = 1.0
    repeat(index) { divisor *= 1024.0 }
    return divisor
}

private fun ceilDivNonNegative(numerator: Long, denominator: Long): Long {
    val div = numerator / denominator
    return if (numerator % denominator > 0L) div + 1L else div
}

private val BYTE_UNITS = arrayOf("B", "KB", "MB", "GB", "TB")

private val UNAVAILABLE_REQUEST_HEADERS = DownloadDetailsRequestHeaders(
    available = false,
    summary = "Unavailable",
    entries = emptyList(),
)

private val UNAVAILABLE_SEGMENTS = DownloadDetailsSegmentBreakdown(
    available = false,
    summary = "Unavailable",
    segments = emptyList(),
)

internal const val DOWNLOAD_DETAILS_SPEED_CHART_UNAVAILABLE: String = "Unavailable"

internal fun downloadDetailsSpeedChartCaption(
    samples: List<Long>,
    speedValue: String,
    speedUnit: String,
): String {
    if (samples.isEmpty()) return DOWNLOAD_DETAILS_SPEED_CHART_UNAVAILABLE
    return "Last 60 sec · $speedValue $speedUnit"
}

internal fun normalizeDownloadDetailsSpeedChartPoints(samples: List<Long>): List<DownloadDetailsSpeedChartPoint> {
    if (samples.isEmpty()) return emptyList()
    val peak = samples.maxOrNull()?.coerceAtLeast(0L) ?: 0L
    val lastIndex = (samples.size - 1).coerceAtLeast(1)
    return samples.mapIndexed { index, sample ->
        DownloadDetailsSpeedChartPoint(
            x = index.toFloat() / lastIndex.toFloat(),
            y = downloadDetailsSpeedChartYFraction(sample, peak),
        )
    }
}

internal fun downloadDetailsSpeedChartYFraction(sample: Long, peak: Long): Float {
    if (peak <= 0L || sample <= 0L) return 1f
    val ratio = sample.toDouble() / peak.toDouble()
    return (1.0 - ratio.coerceIn(0.0, 1.0)).toFloat()
}
