package com.espitman.sdm.ui

import com.espitman.sdm.domain.Download
import com.espitman.sdm.domain.DownloadState
import java.io.File

internal enum class DownloadDetailsStateTone {
    Success,
    Danger,
    Muted,
}

internal data class DownloadDetailsPresentation(
    val id: String,
    val fileName: String,
    val percentLabel: String,
    val ringSweepDegrees: Float,
    val stateLabel: String,
    val stateTone: DownloadDetailsStateTone,
    val destinationDisplay: String,
    val sourceUrl: String,
)

internal fun mapDownloadToDetailsPresentation(
    download: Download,
    nowEpochMillis: Long,
): DownloadDetailsPresentation {
    val metrics = calculateDownloadProgressMetrics(download, nowEpochMillis)
    val fraction = metrics.fraction
    return DownloadDetailsPresentation(
        id = download.id,
        fileName = download.fileName,
        percentLabel = metrics.percentLabel,
        ringSweepDegrees = if (fraction == null) 0f else (fraction * 360f).coerceIn(0f, 360f),
        stateLabel = detailsStateLabel(download.state),
        stateTone = detailsStateTone(download.state),
        destinationDisplay = detailsDestinationDisplay(download.destinationPath),
        sourceUrl = download.url,
    )
}

internal fun detailsStateLabel(state: DownloadState): String = when (state) {
    DownloadState.QUEUED -> "QUEUED"
    DownloadState.CONNECTING, DownloadState.DOWNLOADING -> "ACTIVE"
    DownloadState.PAUSED -> "PAUSED"
    DownloadState.COMPLETED -> "COMPLETED"
    DownloadState.FAILED -> "FAILED"
    DownloadState.CANCELLED -> "CANCELLED"
}

internal fun detailsStateTone(state: DownloadState): DownloadDetailsStateTone = when (state) {
    DownloadState.FAILED, DownloadState.CANCELLED -> DownloadDetailsStateTone.Danger
    DownloadState.QUEUED -> DownloadDetailsStateTone.Muted
    DownloadState.CONNECTING,
    DownloadState.DOWNLOADING,
    DownloadState.PAUSED,
    DownloadState.COMPLETED -> DownloadDetailsStateTone.Success
}

internal fun detailsDestinationDisplay(destinationPath: String?): String {
    if (destinationPath == null) return "—"
    val parent = File(destinationPath).parent
    return if (parent.isNullOrBlank()) destinationPath else parent
}

internal fun detailsOpenFolderToast(destinationPath: String?): String {
    if (destinationPath == null) return "Destination folder unavailable"
    return "Opening ${detailsDestinationDisplay(destinationPath)}"
}
