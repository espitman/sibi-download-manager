package com.espitman.sdm.torrent

import android.content.Context
import com.espitman.sdm.data.AppRepositories
import com.espitman.sdm.domain.Download
import com.espitman.sdm.storage.FolderMutationGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID

object TorrentSubmission {
    suspend fun submit(context: Context, contents: TorrentContents, selected: Set<Int>, seed: Boolean, startNow: Boolean,
        folderUri: String? = null, folderLabel: String? = null): Download = withContext(Dispatchers.IO) {
        val rows = contents.files.filter { it.index in selected }
        require(rows.isNotEmpty() && rows.size == selected.size) { "Select at least one file" }
        val repository = AppRepositories.downloads(context)
        repository.awaitInitialized()
        val id = UUID.randomUUID().toString()
        val store = TorrentStore(context)
        val download = FolderMutationGate.mutex.withLock {
            check(!com.espitman.sdm.storage.FolderRenameCoordinator.isPending(context)) { "Wait for folder changes to finish" }
            check(repository.downloads.value.none { it.isTorrent && TorrentMagnet.identities(it.url).intersect(TorrentMagnet.identities(contents.magnet)).isNotEmpty() }) { "This torrent is already in Downloads" }
            val destination = AppRepositories.destinationAllocator(context).allocate(contents.name, "application/x-bittorrent")
            val directory = File(destination.destinationPath)
            var ownsDirectory = false
            try {
                check(destination.partFile.delete() && directory.mkdir()) { "Unable to create torrent folder" }
                ownsDirectory = true
                val total = rows.fold(0L) { sum, file -> Math.addExact(sum, file.size) }
                check(directory.usableSpace >= total) { "Not enough storage" }
                store.create(id, contents, TorrentSelection(selected, seed), directory)
                val now = System.currentTimeMillis()
                val record = Download(id = id, url = contents.magnet, fileName = destination.fileName,
                    mimeType = "application/x-bittorrent", destinationPath = directory.path,
                    destinationTreeUri = folderUri ?: destination.destinationTreeUri,
                    destinationDisplayLabel = folderLabel ?: destination.destinationDisplayLabel,
                    totalBytes = total, acceptsRanges = true, createdAtEpochMillis = now,
                    sortOrder = (repository.downloads.value.maxOfOrNull { it.sortOrder } ?: -1L) + 1)
                withContext(kotlinx.coroutines.NonCancellable) { repository.insert(record) }
                record
            } catch (failure: Throwable) { store.remove(id); if (ownsDirectory) directory.deleteRecursively(); destination.partFile.delete(); throw failure }
        }
        if (startNow) AppRepositories.queueScheduler(context).schedule()
        download
    }
}
