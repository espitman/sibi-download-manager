package com.espitman.sdm.ui

import com.espitman.sdm.domain.Download
import com.espitman.sdm.domain.DownloadState

internal data class DownloadProgressMetrics(
    val fraction: Float?,
    val percentLabel: String,
    val bytesPerSecond: Long,
    val etaSeconds: Long?,
)

internal fun calculateDownloadProgressMetrics(
    download: Download,
    nowEpochMillis: Long,
    recentBytesPerSecond: Long? = null,
): DownloadProgressMetrics {
    val totalBytes = download.totalBytes
    val downloadedBytes = download.downloadedBytes

    val (fraction, percentLabel) = when {
        download.state == DownloadState.COMPLETED -> {
            1.0f to "100%"
        }
        totalBytes != null && totalBytes > 0L -> {
            val clampedFraction = (downloadedBytes.toDouble() / totalBytes.toDouble()).toFloat().coerceIn(0f, 1f)
            val percentInt = (clampedFraction * 100).toInt().coerceIn(0, 100)
            clampedFraction to "$percentInt%"
        }
        else -> {
            null to "—"
        }
    }

    val startedAt = download.startedAtEpochMillis
    val elapsedMillis = if (startedAt != null) nowEpochMillis - startedAt else 0L
    val elapsedSeconds = elapsedMillis / 1000.0

    val lifetimeBytesPerSecond = if (elapsedSeconds > 0.0 && downloadedBytes > 0L) {
        (downloadedBytes / elapsedSeconds).toLong()
    } else {
        0L
    }
    val bytesPerSecond = recentBytesPerSecond?.coerceAtLeast(0L) ?: lifetimeBytesPerSecond

    val remainingBytes = if (totalBytes != null) totalBytes - downloadedBytes else null
    val etaSeconds = if (
        download.state != DownloadState.COMPLETED &&
        remainingBytes != null &&
        remainingBytes > 0L &&
        bytesPerSecond > 0L
    ) {
        // Overflow-safe ceiling division: (remainingBytes + bytesPerSecond - 1) / bytesPerSecond
        val div = remainingBytes / bytesPerSecond
        val rem = remainingBytes % bytesPerSecond
        if (rem > 0L) div + 1L else div
    } else {
        null
    }

    return DownloadProgressMetrics(
        fraction = fraction,
        percentLabel = percentLabel,
        bytesPerSecond = bytesPerSecond,
        etaSeconds = etaSeconds,
    )
}

internal fun formatDownloadSpeed(bytesPerSecond: Long): String {
    if (bytesPerSecond <= 0L) return "—"
    return "${formatBytes(bytesPerSecond)}/s"
}

internal fun formatEta(seconds: Long?): String {
    if (seconds == null || seconds < 0L) return "—"
    val h = seconds / 3600L
    val m = (seconds % 3600L) / 60L
    val s = seconds % 60L

    return if (h > 0L) {
        "${h}h ${m}m"
    } else if (m > 0L) {
        "${m}m ${s}s"
    } else {
        "${s}s"
    }
}
