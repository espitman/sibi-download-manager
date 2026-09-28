package com.espitman.sdm.download

object TransferServiceLifecyclePolicy {
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
