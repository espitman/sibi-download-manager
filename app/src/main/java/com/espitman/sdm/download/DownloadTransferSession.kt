package com.espitman.sdm.download

class DownloadTransferSession {
    private val lock = Any()
    private val activeDownloadIds = LinkedHashSet<String>()
    private var latestStartId: Int = 0

    fun handleCommand(startId: Int, command: StartTransferCommand?): StartTransferCommand? = synchronized(lock) {
        latestStartId = startId
        if (command != null && activeDownloadIds.add(command.downloadId)) {
            return@synchronized command
        }
        null
    }

    fun startIdIfIdle(): Int? = synchronized(lock) {
        if (activeDownloadIds.isEmpty()) latestStartId else null
    }

    fun onTransferFinished(downloadId: String): Int? = synchronized(lock) {
        activeDownloadIds.remove(downloadId)
        if (activeDownloadIds.isEmpty()) latestStartId else null
    }
}
