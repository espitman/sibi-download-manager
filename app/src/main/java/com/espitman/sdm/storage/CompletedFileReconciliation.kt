package com.espitman.sdm.storage

import com.espitman.sdm.data.DownloadRepository
import com.espitman.sdm.domain.Download
import com.espitman.sdm.domain.DownloadState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class CompletedFileReconciliationResult(
    val prunedIds: Set<String>,
    val readableIds: Set<String>,
    val unavailableIds: Set<String>,
)

object CompletedFileReconciliation {
    suspend fun reconcile(
        records: List<Download>,
        repository: DownloadRepository,
        contentDocuments: ContentDocumentStore? = null,
        classify: (Download) -> CompletedDestinationPresence = { download ->
            CompletedDestinationAccess.classify(
                destinationPath = download.destinationPath,
                treeUri = download.destinationTreeUri,
                contentDocuments = contentDocuments,
            )
        },
    ): CompletedFileReconciliationResult = withContext(Dispatchers.IO) {
        val readable = LinkedHashSet<String>()
        val missing = LinkedHashSet<String>()
        val unavailable = LinkedHashSet<String>()
        for (download in records) {
            if (download.state != DownloadState.COMPLETED) continue
            when (classify(download)) {
                CompletedDestinationPresence.Readable -> readable += download.id
                CompletedDestinationPresence.Missing -> missing += download.id
                CompletedDestinationPresence.AccessUnavailable -> unavailable += download.id
            }
        }
        val pruned = LinkedHashSet<String>()
        for (id in missing) {
            val removed = try {
                repository.delete(id)
            } catch (_: Throwable) {
                false
            }
            if (removed) pruned += id
        }
        CompletedFileReconciliationResult(
            prunedIds = pruned,
            readableIds = readable,
            unavailableIds = unavailable,
        )
    }
}
