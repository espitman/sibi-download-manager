package com.espitman.sdm.ui

import com.espitman.sdm.domain.Download
import com.espitman.sdm.domain.DownloadState
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.CancellationException

internal data class DownloadStatusCardValues(
    val activeCount: Int,
    val speedValue: String,
    val downloadedToday: String,
    val remaining: String,
    val connections: String,
)

internal fun downloadStatusCardValues(
    records: List<Download>,
    downloadedTodayBytes: Long,
    recentBytesPerSecond: Long,
): DownloadStatusCardValues {
    val activeRecords = records.filter { it.state == DownloadState.DOWNLOADING || it.state == DownloadState.CONNECTING }
    val activeCount = activeRecords.size
    val connections = activeRecords.count { it.state == DownloadState.DOWNLOADING }
    val remaining = when {
        activeRecords.isEmpty() -> formatBytes(0L)
        activeRecords.any { it.totalBytes == null } -> "—"
        else -> formatBytes(remainingBytesOf(activeRecords))
    }
    val speedValue = if (activeCount == 0) {
        "0"
    } else {
        String.format(Locale.US, "%.1f", recentBytesPerSecond.coerceAtLeast(0L) / (1024.0 * 1024.0))
    }
    return DownloadStatusCardValues(
        activeCount = activeCount,
        speedValue = speedValue,
        downloadedToday = formatBytes(downloadedTodayBytes.coerceAtLeast(0L)),
        remaining = remaining,
        connections = connections.toString(),
    )
}

internal fun nextDownloadsStatusRefreshDelayMillis(
    nowEpochMillis: Long,
    zoneId: ZoneId,
    hasActiveTransfers: Boolean,
    maxIdleDelayMillis: Long = DEFAULT_IDLE_STATUS_REFRESH_MAX_DELAY_MILLIS,
): Long {
    if (hasActiveTransfers) return ACTIVE_STATUS_REFRESH_DELAY_MILLIS
    val untilNextLocalDay = startOfNextLocalDay(nowEpochMillis, zoneId) - nowEpochMillis
    if (untilNextLocalDay <= 0L) return 1L
    return max(1L, min(untilNextLocalDay, maxIdleDelayMillis))
}

internal fun startOfNextLocalDay(nowEpochMillis: Long, zoneId: ZoneId): Long =
    Instant.ofEpochMilli(nowEpochMillis)
        .atZone(zoneId)
        .toLocalDate()
        .plusDays(1)
        .atStartOfDay(zoneId)
        .toInstant()
        .toEpochMilli()

internal fun remainingBytesContribution(totalBytes: Long, downloadedBytes: Long): Long {
    if (downloadedBytes >= totalBytes) return 0L
    return totalBytes - downloadedBytes
}

private fun remainingBytesOf(activeRecords: List<Download>): Long = activeRecords.fold(0L) { acc, record ->
    val total = record.totalBytes ?: return@fold acc
    saturatingAdd(acc, remainingBytesContribution(total, record.downloadedBytes))
}

internal fun saturatingAdd(left: Long, right: Long): Long {
    val sum = left + right
    return if (left xor right < 0L || left xor sum >= 0L) sum else Long.MAX_VALUE
}

internal const val ACTIVE_STATUS_REFRESH_DELAY_MILLIS = 1_000L
internal const val DEFAULT_IDLE_STATUS_REFRESH_MAX_DELAY_MILLIS = 15 * 60 * 1000L

internal suspend fun transferredBytesForLocalDayOrZero(query: suspend () -> Long): Long {
    return try {
        query()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        0L
    }
}
