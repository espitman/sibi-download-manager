package com.espitman.sdm.ui

import com.espitman.sdm.domain.Download
import com.espitman.sdm.domain.DownloadState
import java.util.Locale

internal enum class DownloadCategory(val label: String) {
    All("All"),
    Queued("Queue"),
    Completed("Completed"),
}

internal val downloadStatusCategories: List<DownloadCategory> =
    DownloadCategory.entries.filter { it != DownloadCategory.All }

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
    val isQueued: Boolean,
)

internal fun mapDownloadToCard(
    download: Download,
    nowEpochMillis: Long,
    recentBytesPerSecond: Long = 0L,
): DownloadCardModel {
    val metrics = calculateDownloadProgressMetrics(download, nowEpochMillis, recentBytesPerSecond)
    val category = when (download.state) {
        DownloadState.COMPLETED -> DownloadCategory.Completed
        else -> DownloadCategory.Queued
    }
    val progressLabel = "${metrics.percentLabel} · ${formatBytes(download.downloadedBytes)}"

    val metadataValue: String
    val trailing: String
    when (download.state) {
        DownloadState.QUEUED -> {
            val waitingForTime = download.schedule?.isOpen(nowEpochMillis) == false
            metadataValue = if (waitingForTime) "Scheduled" else "Queued"
            trailing = if (waitingForTime) scheduleSummary(download.schedule, nowEpochMillis) else "Wi-Fi only"
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
            metadataValue = if (download.pauseCause == com.espitman.sdm.domain.DownloadPauseCause.SCHEDULE) "Scheduled" else "Paused"
            trailing = if (download.pauseCause == com.espitman.sdm.domain.DownloadPauseCause.SCHEDULE)
                scheduleSummary(download.schedule, nowEpochMillis) else "Paused"
        }
        DownloadState.COMPLETED -> {
            metadataValue = "Completed"
            trailing = "Completed"
        }
        DownloadState.FAILED -> {
            metadataValue = "Error · ${failedDownloadCardLabel(download.error)}"
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
            DownloadState.QUEUED -> if (download.schedule?.isOpen(nowEpochMillis) == false) "Waiting for schedule" else "Next in queue"
            else -> progressLabel
        },
        trailing = trailing,
        category = category,
        showPlayAction = (download.schedule?.isOpen(nowEpochMillis) != false) && download.state in setOf(
            DownloadState.QUEUED,
            DownloadState.PAUSED,
            DownloadState.FAILED,
            DownloadState.CANCELLED,
        ),
        isQueued = download.state == DownloadState.QUEUED,
    )
}

internal fun filterDownloadCards(
    cards: List<DownloadCardModel>,
    category: DownloadCategory,
    query: String,
): List<DownloadCardModel> = cards.filter { card ->
    matchesDownloadCategory(card, category) && card.name.contains(query, ignoreCase = true)
}

internal fun orderDownloadCards(cards: List<DownloadCardModel>): List<DownloadCardModel> =
    cards.filter { it.category != DownloadCategory.Completed } +
        cards.filter { it.category == DownloadCategory.Completed }

internal fun canReorderDownloadCard(
    card: DownloadCardModel,
    category: DownloadCategory,
    query: String,
    selectionMode: Boolean,
    reorderMode: Boolean,
): Boolean = reorderMode && !selectionMode && query.isBlank() &&
    category != DownloadCategory.Completed && card.category == DownloadCategory.Queued

internal fun matchesDownloadCategory(
    card: DownloadCardModel,
    category: DownloadCategory,
): Boolean = category == DownloadCategory.All || card.category == category

internal fun downloadTabCounts(cards: List<DownloadCardModel>): Map<DownloadCategory, Int> {
    val statusCounts = cards.groupingBy { it.category }.eachCount()
    return buildMap {
        put(DownloadCategory.All, cards.size)
        downloadStatusCategories.forEach { category ->
            put(category, statusCounts[category] ?: 0)
        }
    }
}

internal fun downloadTabTitle(category: DownloadCategory, count: Int): String =
    "${category.label} $count"

internal fun emptyDownloadsTitle(category: DownloadCategory): String = when (category) {
    DownloadCategory.All -> "No downloads"
    DownloadCategory.Completed -> "No completed downloads"
    else -> "No ${category.label.lowercase()} downloads"
}

internal fun emptyDownloadsDescription(category: DownloadCategory): String =
    when (category) {
        DownloadCategory.All -> "Downloads you add will appear here."
        DownloadCategory.Queued -> "Downloads waiting, paused, or in progress will appear here."
        DownloadCategory.Completed -> "Finished files will appear here."
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
    return decimalSpeedDisplay(bytesPerSecond).formatted
}
