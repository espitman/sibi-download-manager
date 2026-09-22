package com.espitman.sdm.download

import com.espitman.sdm.data.DownloadRepository
import com.espitman.sdm.domain.DownloadState
import com.espitman.sdm.storage.DownloadDestinationRef
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Stops a transfer before removing its partial data and database row. */
object IncompleteDownloadDeleteCoordinator {
    suspend fun delete(
        downloadId: String,
        repository: DownloadRepository,
        requestCancel: (String) -> Unit,
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val initial = repository.get(downloadId) ?: return@withContext true
            if (initial.state == DownloadState.COMPLETED) return@withContext false
            if (initial.state != DownloadState.CANCELLED) requestCancel(downloadId)
            val settled = withTimeoutOrNull(10_000L) {
                repository.downloads.first { rows ->
                    rows.none { it.id == downloadId } || rows.any {
                        it.id == downloadId && it.state in setOf(DownloadState.CANCELLED, DownloadState.COMPLETED)
                    }
                }
            } ?: return@withContext false
            val current = settled.firstOrNull { it.id == downloadId } ?: return@withContext true
            if (current.state != DownloadState.CANCELLED) return@withContext false
            val destination = current.destinationPath ?: return@withContext repository.delete(downloadId)
            if (DownloadDestinationRef.isContentUri(destination)) return@withContext false
            val destinationFile = File(destination)
            val partials = listOf(
                DownloadPartFile.forDestination(destinationFile),
                DownloadPartFile.restartForDestination(destinationFile),
            )
            if (partials.any { it.exists() && !it.delete() }) return@withContext false
            repository.delete(downloadId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }
}
