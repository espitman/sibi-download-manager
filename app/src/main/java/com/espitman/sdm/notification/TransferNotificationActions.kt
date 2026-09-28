package com.espitman.sdm.notification

import com.espitman.sdm.domain.DownloadState
import com.espitman.sdm.download.DownloadTransferCommand

enum class TransferNotificationActionKind {
    PAUSE,
    RESUME,
    CANCEL,
    PAUSE_ALL,
    RESUME_ALL,
}

object TransferNotificationActions {
    const val LABEL_PAUSE = "Pause"
    const val LABEL_RESUME = "Resume"
    const val LABEL_CANCEL = "Cancel"
    const val LABEL_PAUSE_ALL = "Pause All"
    const val LABEL_RESUME_ALL = "Resume All"

    fun forState(state: DownloadState): List<TransferNotificationActionKind> = when (state) {
        DownloadState.CONNECTING, DownloadState.DOWNLOADING -> listOf(
            TransferNotificationActionKind.PAUSE,
            TransferNotificationActionKind.CANCEL,
        )
        DownloadState.PAUSED -> listOf(
            TransferNotificationActionKind.RESUME,
            TransferNotificationActionKind.CANCEL,
        )
        DownloadState.QUEUED,
        DownloadState.COMPLETED,
        DownloadState.FAILED,
        DownloadState.CANCELLED,
        -> emptyList()
    }

    internal fun forAggregate(aggregate: TransferNotificationAggregate): List<TransferNotificationActionKind> = buildList {
        if (aggregate.activeCount > 0 || aggregate.queuedCount > 0) {
            add(TransferNotificationActionKind.PAUSE_ALL)
        }
        if (aggregate.pausedCount > 0) {
            add(TransferNotificationActionKind.RESUME_ALL)
        }
    }

    fun label(kind: TransferNotificationActionKind): String = when (kind) {
        TransferNotificationActionKind.PAUSE -> LABEL_PAUSE
        TransferNotificationActionKind.RESUME -> LABEL_RESUME
        TransferNotificationActionKind.CANCEL -> LABEL_CANCEL
        TransferNotificationActionKind.PAUSE_ALL -> LABEL_PAUSE_ALL
        TransferNotificationActionKind.RESUME_ALL -> LABEL_RESUME_ALL
    }

    fun serviceAction(kind: TransferNotificationActionKind): String = when (kind) {
        TransferNotificationActionKind.PAUSE -> DownloadTransferCommand.ACTION_PAUSE_TRANSFER
        TransferNotificationActionKind.RESUME -> DownloadTransferCommand.ACTION_RESUME_TRANSFER
        TransferNotificationActionKind.CANCEL -> DownloadTransferCommand.ACTION_CANCEL_TRANSFER
        TransferNotificationActionKind.PAUSE_ALL -> DownloadTransferCommand.ACTION_PAUSE_ALL
        TransferNotificationActionKind.RESUME_ALL -> DownloadTransferCommand.ACTION_RESUME_ALL
    }
}
