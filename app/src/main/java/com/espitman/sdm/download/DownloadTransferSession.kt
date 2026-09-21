package com.espitman.sdm.download

import kotlinx.coroutines.Job

sealed interface SessionCommandResult {
    data class StartJob(val command: StartTransferCommand) : SessionCommandResult
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
            is StartTransferCommand -> {
                if (active.containsKey(command.downloadId)) return@synchronized SessionCommandResult.None
                active[command.downloadId] = ActiveTransfer(command)
                SessionCommandResult.StartJob(command)
            }
            is PauseTransferCommand -> {
                val transfer = active[command.downloadId] ?: return@synchronized SessionCommandResult.None
                transfer.pauseRequested = true
                val job = transfer.job
                if (job != null && !job.isCompleted) {
                    SessionCommandResult.CancelJob(command.downloadId, job)
                } else {
                    SessionCommandResult.None
                }
            }
            null -> SessionCommandResult.None
        }
    }

    fun attachJob(downloadId: String, job: Job): Boolean = synchronized(lock) {
        val transfer = active[downloadId] ?: return false
        transfer.job = job
        transfer.pauseRequested
    }

    fun isPauseRequested(downloadId: String): Boolean = synchronized(lock) {
        active[downloadId]?.pauseRequested == true
    }

    fun tempFilePath(downloadId: String): String? = synchronized(lock) {
        active[downloadId]?.command?.tempFilePath
    }

    fun startIdIfIdle(): Int? = synchronized(lock) {
        if (active.isEmpty()) latestStartId else null
    }

    fun onTransferFinished(downloadId: String): Int? = synchronized(lock) {
        active.remove(downloadId)
        if (active.isEmpty()) latestStartId else null
    }

    private class ActiveTransfer(
        val command: StartTransferCommand,
        var job: Job? = null,
        var pauseRequested: Boolean = false,
    )
}
