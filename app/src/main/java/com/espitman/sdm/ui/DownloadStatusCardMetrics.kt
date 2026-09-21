package com.espitman.sdm.ui

import com.espitman.sdm.domain.Download
import com.espitman.sdm.domain.DownloadState
import java.time.Instant
import java.time.ZoneId

internal data class DownloadStatusCardValues(
    val activeCount: Int,
    val speedValue: String,
    val downloadedToday: String,
    val remaining: String,
    val connections: String,
)

internal fun downloadStatusCardValues(
    records: List<Download>,
    nowEpochMillis: Long,
    zoneId: ZoneId = ZoneId.systemDefault(),
): DownloadStatusCardValues {
    val activeRecords = records.filter { it.state == DownloadState.DOWNLOADING || it.state == DownloadState.CONNECTING }
    val activeCount = activeRecords.size
    val remainingBytes = remainingBytesOf(activeRecords)
    val remaining = when {
        activeRecords.any { it.totalBytes == null } -> "—"
        else -> formatBytes(remainingBytes)
    }
    val totalBytesPerSecond = activeRecords.fold(0L) { acc, record ->
        saturatingAdd(acc, calculateDownloadProgressMetrics(record, nowEpochMillis).bytesPerSecond)
    }
    val speedValue = if (activeCount == 0) {
        "0"
    } else {
        String.format(java.util.Locale.US, "%.1f", totalBytesPerSecond / (1024.0 * 1024.0))
    }
    return DownloadStatusCardValues(
        activeCount = activeCount,
        speedValue = speedValue,
        downloadedToday = formatBytes(sumDownloadedTodayBytes(records, nowEpochMillis, zoneId)),
        remaining = remaining,
        connections = activeCount.toString(),
    )
}

internal fun sumDownloadedTodayBytes(
    records: List<Download>,
    nowEpochMillis: Long,
    zoneId: ZoneId = ZoneId.systemDefault(),
): Long {
    // Records store only a running downloadedBytes total, not per-day deltas.
    val dayStart = startOfLocalDay(nowEpochMillis, zoneId)
    val dayEndExclusive = startOfLocalDayExclusiveEnd(nowEpochMillis, zoneId)
    return records.fold(0L) { acc, record ->
        if (!countsTowardDownloadedToday(record, dayStart, dayEndExclusive)) acc
        else saturatingAdd(acc, record.downloadedBytes)
    }
}

internal fun countsTowardDownloadedToday(
    record: Download,
    dayStartEpochMillis: Long,
    dayEndExclusiveEpochMillis: Long,
): Boolean {
    if (record.state == DownloadState.CONNECTING || record.state == DownloadState.DOWNLOADING) return true
    val completedAt = record.completedAtEpochMillis
    if (completedAt != null && inLocalDay(completedAt, dayStartEpochMillis, dayEndExclusiveEpochMillis)) {
        return true
    }
    return inLocalDay(record.updatedAtEpochMillis, dayStartEpochMillis, dayEndExclusiveEpochMillis)
}

internal fun startOfLocalDay(nowEpochMillis: Long, zoneId: ZoneId): Long =
    Instant.ofEpochMilli(nowEpochMillis)
        .atZone(zoneId)
        .toLocalDate()
        .atStartOfDay(zoneId)
        .toInstant()
        .toEpochMilli()

internal fun startOfLocalDayExclusiveEnd(nowEpochMillis: Long, zoneId: ZoneId): Long =
    Instant.ofEpochMilli(nowEpochMillis)
        .atZone(zoneId)
        .toLocalDate()
        .plusDays(1)
        .atStartOfDay(zoneId)
        .toInstant()
        .toEpochMilli()

private fun inLocalDay(epochMillis: Long, dayStart: Long, dayEndExclusive: Long): Boolean =
    epochMillis >= dayStart && epochMillis < dayEndExclusive

private fun remainingBytesOf(activeRecords: List<Download>): Long = activeRecords.fold(0L) { acc, record ->
    val total = record.totalBytes ?: return@fold acc
    saturatingAdd(acc, total - record.downloadedBytes)
}

internal fun saturatingAdd(left: Long, right: Long): Long {
    val sum = left + right
    return if (left xor right < 0L || left xor sum >= 0L) sum else Long.MAX_VALUE
}
