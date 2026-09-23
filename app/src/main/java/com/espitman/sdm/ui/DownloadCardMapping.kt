package com.espitman.sdm.ui

import com.espitman.sdm.domain.Download
import com.espitman.sdm.domain.DownloadState
import java.util.Locale

internal enum class DownloadCategory(val label: String) {
    Downloading("Downloading"),
    Queued("Queued"),
    Completed("Completed"),
}

internal data class DownloadCardModel(
    val id: String,
    val type: String,
    val name: String,
    val size: String,
    val progress: Float,
    val metadataValue: String,
    val progressLabel: String,
    val trailing: String,
    val category: DownloadCategory,
    val showPlayAction: Boolean,
)

internal fun mapDownloadToCard(
    download: Download,
    nowEpochMillis: Long,
    recentBytesPerSecond: Long = 0L,
): DownloadCardModel {
    val metrics = calculateDownloadProgressMetrics(download, nowEpochMillis, recentBytesPerSecond)
    val category = when (download.state) {
        DownloadState.QUEUED -> DownloadCategory.Queued
        DownloadState.COMPLETED -> DownloadCategory.Completed
        else -> DownloadCategory.Downloading
    }
    val progressLabel = "${metrics.percentLabel} · ${formatBytes(download.downloadedBytes)}"

    val metadataValue: String
    val trailing: String
    when (download.state) {
        DownloadState.QUEUED -> {
            metadataValue = "Queued"
            trailing = "Wi-Fi only"
        }
        DownloadState.CONNECTING -> {
            metadataValue = "Connecting…"
            trailing = "Calculating…"
        }
        DownloadState.DOWNLOADING -> {
            metadataValue = formatCardSpeed(metrics.bytesPerSecond)
                .takeUnless { it == "—" }
                ?: "Calculating…"
            trailing = formatClockEta(metrics.etaSeconds)
        }
        DownloadState.PAUSED -> {
            metadataValue = "Paused"
            trailing = "Paused"
        }
        DownloadState.COMPLETED -> {
            metadataValue = "Completed"
            trailing = "Completed"
        }
        DownloadState.FAILED -> {
            metadataValue = failedDownloadCardLabel(download.error)
            trailing = "Retry"
        }
        DownloadState.CANCELLED -> {
            metadataValue = "Cancelled"
            trailing = "Cancelled"
        }
    }

    return DownloadCardModel(
        id = download.id,
        type = download.fileName.substringAfterLast('.', "FILE").uppercase().take(5),
        name = download.fileName,
        size = download.totalBytes?.let(::formatBytes) ?: "Unknown size",
        progress = metrics.fraction ?: 0f,
        metadataValue = metadataValue,
        progressLabel = when (download.state) {
            DownloadState.QUEUED -> "Next in queue"
            else -> progressLabel
        },
        trailing = trailing,
        category = category,
        showPlayAction = download.state in setOf(
            DownloadState.QUEUED,
            DownloadState.PAUSED,
            DownloadState.FAILED,
            DownloadState.CANCELLED,
        ),
    )
}

internal fun filterDownloadCards(
    cards: List<DownloadCardModel>,
    category: DownloadCategory,
    query: String,
): List<DownloadCardModel> = cards.filter { card ->
    card.category == category && card.name.contains(query, ignoreCase = true)
}

internal fun formatClockEta(seconds: Long?): String {
    if (seconds == null || seconds < 0L) return "Calculating…"
    val hours = seconds / 3600L
    val minutes = (seconds % 3600L) / 60L
    val remainingSeconds = seconds % 60L
    return if (hours > 0L) {
        String.format(Locale.US, "%02d:%02d:%02d left", hours, minutes, remainingSeconds)
    } else {
        String.format(Locale.US, "%02d:%02d left", minutes, remainingSeconds)
    }
}

internal fun formatCardSpeed(bytesPerSecond: Long): String {
    if (bytesPerSecond <= 0L) return "—"
    return "${displayMegabytesPerSecond(bytesPerSecond)} MB/s"
}
