package com.espitman.sdm.download

import kotlinx.coroutines.Job

sealed interface SessionCommandResult {
    data class StartJob(val command: TransferCommand) : SessionCommandResult
    data class CancelJob(val downloadId: String, val job: Job) : SessionCommandResult
    data object None : SessionCommandResult
}

class DownloadTransferSession {
    private val lock = Any()
    private val active = LinkedHashMap<String, ActiveTransfer>()
    private var latestStartId: Int = 0

    fun handleCommand(startId: Int, command: TransferCommand?): SessionCommandResult = synchronized(lock) {
        latestStartId = startId
        when (command) {
            is StartTransferCommand, is ResumeTransferCommand -> {
                if (active.containsKey(command.downloadId)) return@synchronized SessionCommandResult.None
                active[command.downloadId] = ActiveTransfer(command)
                SessionCommandResult.StartJob(command)
            }
            is PauseTransferCommand -> {
                val transfer = active[command.downloadId] ?: return@synchronized SessionCommandResult.None
                transfer.pauseRequested = true
                cancelAttachedJobIfNeeded(command.downloadId, transfer)
            }
            is CancelTransferCommand -> {
                val transfer = active[command.downloadId] ?: return@synchronized SessionCommandResult.None
                val alreadyCancelRequested = transfer.cancelRequested
                transfer.cancelRequested = true
                if (alreadyCancelRequested) return@synchronized SessionCommandResult.None
                cancelAttachedJobIfNeeded(command.downloadId, transfer)
            }
            null -> SessionCommandResult.None
        }
    }

    fun attachJob(downloadId: String, job: Job): Boolean = synchronized(lock) {
        val transfer = active[downloadId] ?: return false
        transfer.job = job
        transfer.pauseRequested || transfer.cancelRequested
    }

    fun isPauseRequested(downloadId: String): Boolean = synchronized(lock) {
        val transfer = active[downloadId] ?: return false
        transfer.pauseRequested && !transfer.cancelRequested
    }

    fun isCancelRequested(downloadId: String): Boolean = synchronized(lock) {
        active[downloadId]?.cancelRequested == true
    }

    fun tempFilePath(downloadId: String): String? = synchronized(lock) {
        (active[downloadId]?.command as? StartTransferCommand)?.tempFilePath
    }

    fun startIdIfIdle(): Int? = synchronized(lock) {
        if (active.isEmpty()) latestStartId else null
    }

    fun onTransferFinished(downloadId: String): Int? = synchronized(lock) {
        active.remove(downloadId)
        if (active.isEmpty()) latestStartId else null
    }

    private fun cancelAttachedJobIfNeeded(downloadId: String, transfer: ActiveTransfer): SessionCommandResult {
        val job = transfer.job
        return if (job != null && !job.isCompleted) {
            SessionCommandResult.CancelJob(downloadId, job)
        } else {
            SessionCommandResult.None
        }
    }

    private class ActiveTransfer(
        val command: TransferCommand,
        var job: Job? = null,
        var pauseRequested: Boolean = false,
        var cancelRequested: Boolean = false,
    )
}
