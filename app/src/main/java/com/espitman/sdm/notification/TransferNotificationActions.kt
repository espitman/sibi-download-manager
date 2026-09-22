package com.espitman.sdm.notification

import com.espitman.sdm.domain.DownloadState
import com.espitman.sdm.download.DownloadTransferCommand

enum class TransferNotificationActionKind {
    PAUSE,
    RESUME,
    CANCEL,
}

object TransferNotificationActions {
    const val LABEL_PAUSE = "Pause"
    const val LABEL_RESUME = "Resume"
    const val LABEL_CANCEL = "Cancel"

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

    fun label(kind: TransferNotificationActionKind): String = when (kind) {
        TransferNotificationActionKind.PAUSE -> LABEL_PAUSE
        TransferNotificationActionKind.RESUME -> LABEL_RESUME
        TransferNotificationActionKind.CANCEL -> LABEL_CANCEL
    }

    fun serviceAction(kind: TransferNotificationActionKind): String = when (kind) {
        TransferNotificationActionKind.PAUSE -> DownloadTransferCommand.ACTION_PAUSE_TRANSFER
        TransferNotificationActionKind.RESUME -> DownloadTransferCommand.ACTION_RESUME_TRANSFER
        TransferNotificationActionKind.CANCEL -> DownloadTransferCommand.ACTION_CANCEL_TRANSFER
    }
}
