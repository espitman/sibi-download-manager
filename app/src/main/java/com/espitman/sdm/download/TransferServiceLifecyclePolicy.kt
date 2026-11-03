package com.espitman.sdm.download

import com.espitman.sdm.domain.Download
import com.espitman.sdm.domain.DownloadState

object TransferServiceLifecyclePolicy {
    // A queued start is posted to Android asynchronously. Do not stop the restarted
    // service between claiming that row and receiving its StartTransfer command.
    fun hasWorkAwaitingDispatch(
        downloads: List<Download>,
        nowEpochMillis: Long,
        folderMutationBlocked: Boolean = false,
    ): Boolean = downloads.any { it.state in DownloadQueuePolicy.OCCUPYING_STATES } ||
        (!folderMutationBlocked && downloads.any {
            it.state == DownloadState.QUEUED && it.schedule?.isOpen(nowEpochMillis) != false
        })

    fun claimedIdToReleaseOnRejectedForeground(
        enteredForeground: Boolean,
        command: TransferCommand?,
    ): String? {
        if (enteredForeground) return null
        return (command as? StartTransferCommand)?.downloadId
    }

    fun startQueueObserverBeforeHandling(command: TransferCommand?): Boolean {
        return when (command) {
            is PauseTransferCommand,
            is CancelTransferCommand,
            is PauseAllCommand,
            is ResumeAllCommand,
            -> false
            else -> true
        }
    }

    fun shouldRescheduleAfterSlotRelease(acceptingWork: Boolean): Boolean = acceptingWork
}
