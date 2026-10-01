package com.espitman.sdm.ui

import com.espitman.sdm.domain.Download
import com.espitman.sdm.domain.DownloadState

internal enum class DownloadsSelectionResumeKind {
    StartQueued,
    ResumeOrRetry,
    None,
}

internal data class DownloadsSelectionDeleteTargets(
    val incompleteIds: List<String>,
    val completedIds: List<String>,
) {
    val isEmpty: Boolean get() = incompleteIds.isEmpty() && completedIds.isEmpty()
    val size: Int get() = incompleteIds.size + completedIds.size
}

internal data class DownloadsSelectionDeleteCopy(
    val title: String,
    val message: String,
    val dismissLabel: String,
    val confirmLabel: String,
    val deleteFileLabel: String?,
)

internal fun downloadsSelectionPauseEligible(state: DownloadState): Boolean =
    state == DownloadState.CONNECTING || state == DownloadState.DOWNLOADING

internal fun downloadsSelectionResumeKind(state: DownloadState): DownloadsSelectionResumeKind = when (state) {
    DownloadState.QUEUED -> DownloadsSelectionResumeKind.StartQueued
    DownloadState.PAUSED, DownloadState.FAILED, DownloadState.CANCELLED ->
        DownloadsSelectionResumeKind.ResumeOrRetry
    DownloadState.CONNECTING, DownloadState.DOWNLOADING, DownloadState.COMPLETED ->
        DownloadsSelectionResumeKind.None
}

internal fun toggleDownloadsSelection(selectedIds: Set<String>, id: String): Set<String> =
    if (id in selectedIds) selectedIds - id else selectedIds + id

internal fun pruneDownloadsSelection(selectedIds: Set<String>, availableIds: Set<String>): Set<String> =
    selectedIds.intersect(availableIds)

internal fun removeActedOnDownloadsSelection(
    selectedIds: Set<String>,
    succeededIds: Collection<String>,
): Set<String> = selectedIds - succeededIds.toSet()

internal fun downloadsSelectionPauseIds(
    records: List<Download>,
    selectedIds: Set<String>,
    retrySettings: com.espitman.sdm.domain.AutomaticRetrySettings? = null,
): List<String> = records
    .filter { it.id in selectedIds && (downloadsSelectionPauseEligible(it.state) || retrySettings?.dueAt(it) != null) }
    .map { it.id }

internal fun downloadsSelectionStartQueuedIds(
    records: List<Download>,
    selectedIds: Set<String>,
): List<String> = records
    .filter { it.id in selectedIds && downloadsSelectionResumeKind(it.state) == DownloadsSelectionResumeKind.StartQueued }
    .map { it.id }

internal fun downloadsSelectionResumeOrRetryIds(
    records: List<Download>,
    selectedIds: Set<String>,
): List<String> = records
    .filter { it.id in selectedIds && downloadsSelectionResumeKind(it.state) == DownloadsSelectionResumeKind.ResumeOrRetry }
    .map { it.id }

internal fun downloadsSelectionDeleteTargets(
    records: List<Download>,
    selectedIds: Set<String>,
): DownloadsSelectionDeleteTargets {
    val selected = records.filter { it.id in selectedIds }
    return DownloadsSelectionDeleteTargets(
        incompleteIds = selected.filter { it.state != DownloadState.COMPLETED }.map { it.id },
        completedIds = selected.filter { it.state == DownloadState.COMPLETED }.map { it.id },
    )
}

internal fun downloadsSelectionCountLabel(count: Int): String =
    if (count == 1) "1 selected" else "$count selected"

internal fun downloadsSelectionPauseToast(pausedCount: Int): String = when {
    pausedCount <= 0 -> "No active downloads to pause"
    pausedCount == 1 -> "Download paused"
    else -> "$pausedCount downloads paused"
}

internal fun downloadsSelectionResumeToast(resumedCount: Int): String = when {
    resumedCount <= 0 -> "No downloads to resume"
    resumedCount == 1 -> "Download resumed"
    else -> "$resumedCount downloads resumed"
}

internal fun downloadsSelectionDeleteCopy(
    targets: DownloadsSelectionDeleteTargets,
): DownloadsSelectionDeleteCopy {
    val incomplete = targets.incompleteIds.size
    val completed = targets.completedIds.size
    return when {
        incomplete > 0 && completed == 0 -> DownloadsSelectionDeleteCopy(
            title = if (incomplete == 1) "Delete download?" else "Delete downloads?",
            message = if (incomplete == 1) {
                "This removes the download and its partial file. It cannot be resumed afterward."
            } else {
                "This removes the downloads and their partial files. They cannot be resumed afterward."
            },
            dismissLabel = "Keep",
            confirmLabel = "Delete",
            deleteFileLabel = null,
        )
        incomplete == 0 && completed > 0 -> DownloadsSelectionDeleteCopy(
            title = "Remove from Completed?",
            message = if (completed == 1) {
                "Choose whether to keep the downloaded file in Files."
            } else {
                "Choose whether to keep the ${completed} downloaded files in Files."
            },
            dismissLabel = "Keep in list",
            confirmLabel = "Remove only",
            deleteFileLabel = if (completed == 1) "Remove and delete file" else "Remove and delete files",
        )
        else -> DownloadsSelectionDeleteCopy(
            title = "Delete selected downloads?",
            message = "Incomplete downloads and their partial files will be removed. For completed downloads, choose whether to keep the files in Files.",
            dismissLabel = "Keep",
            confirmLabel = "Remove only",
            deleteFileLabel = "Remove and delete files",
        )
    }
}

internal fun downloadsSelectionDeleteToast(
    incompleteSucceeded: Int,
    incompleteFailed: Int,
    completedSucceeded: Int,
    completedFailed: Int,
    deleteCompletedFiles: Boolean,
): String {
    val failed = incompleteFailed + completedFailed
    if (failed > 0) {
        return "$failed ${if (failed == 1) "item" else "items"} could not be deleted. Please try again."
    }
    val incomplete = incompleteSucceeded
    val completed = completedSucceeded
    return when {
        incomplete > 0 && completed == 0 -> if (incomplete == 1) {
            "Download and partial file deleted"
        } else {
            "Downloads and partial files deleted"
        }
        incomplete == 0 && completed > 0 && !deleteCompletedFiles -> if (completed == 1) {
            "Removed from Completed. File remains in Files."
        } else {
            "Removed from Completed. Files remain in Files."
        }
        incomplete == 0 && completed > 0 && deleteCompletedFiles -> if (completed == 1) {
            "Completed download and file deleted"
        } else {
            "Completed downloads and files deleted"
        }
        deleteCompletedFiles -> "Selected downloads and files deleted"
        else -> "Selected downloads deleted. Completed files remain in Files."
    }
}
