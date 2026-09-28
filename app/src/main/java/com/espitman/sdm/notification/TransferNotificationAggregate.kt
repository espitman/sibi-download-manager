package com.espitman.sdm.notification

import com.espitman.sdm.domain.Download
import com.espitman.sdm.domain.DownloadState
import com.espitman.sdm.ui.decimalSpeedDisplay

/** The single visible notification represents all unfinished downloads. */
internal data class TransferNotificationAggregate(
    val activeCount: Int,
    val queuedCount: Int,
    val pausedCount: Int,
    val progress: TransferNotificationProgress,
    val bytesPerSecond: Long = 0L,
) {
    val pendingCount: Int get() = activeCount + queuedCount + pausedCount

    val speedText: String get() = decimalSpeedDisplay(bytesPerSecond).formatted

    val statusText: String
        get() = buildList {
            if (activeCount > 0) add("$activeCount active")
            if (queuedCount > 0) add("$queuedCount queued")
            if (pausedCount > 0) add("$pausedCount paused")
        }.joinToString(" | ")

    fun compactText(completedSinceStart: Int = 0, stalledCount: Int = 0): String {
        if (pendingCount == 0) return ""
        return buildList {
            add(speedText)
            add(progress.text)
            add(statusText)
            if (completedSinceStart > 0) add("$completedSinceStart completed")
            if (stalledCount > 0) add("$stalledCount stalled")
        }.filter { it.isNotEmpty() }.joinToString(" | ")
    }

    companion object {
        fun from(downloads: List<Download>, bytesPerSecond: Long = 0L): TransferNotificationAggregate {
            val pending = downloads.filter {
                it.state == DownloadState.CONNECTING ||
                    it.state == DownloadState.DOWNLOADING ||
                    it.state == DownloadState.QUEUED ||
                    it.state == DownloadState.PAUSED
            }
            val downloaded = pending.fold(0L) { sum, item ->
                saturatingAdd(sum, item.downloadedBytes.coerceAtLeast(0L))
            }
            val knownTotal = pending.all { (it.totalBytes ?: 0L) > 0L }
            val total = if (knownTotal) pending.fold(0L) { sum, item ->
                saturatingAdd(sum, (item.totalBytes ?: 0L).coerceAtLeast(item.downloadedBytes))
            } else null
            return TransferNotificationAggregate(
                activeCount = pending.count { it.state == DownloadState.CONNECTING || it.state == DownloadState.DOWNLOADING },
                queuedCount = pending.count { it.state == DownloadState.QUEUED },
                pausedCount = pending.count { it.state == DownloadState.PAUSED },
                progress = TransferNotificationProgress.from(downloaded, total),
                bytesPerSecond = bytesPerSecond.coerceAtLeast(0L),
            )
        }

        private fun saturatingAdd(left: Long, right: Long): Long =
            if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right
    }
}
