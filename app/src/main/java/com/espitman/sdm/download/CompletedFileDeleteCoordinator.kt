package com.espitman.sdm.download

import com.espitman.sdm.data.DownloadRepository
import com.espitman.sdm.domain.DownloadState
import com.espitman.sdm.storage.CompletedDestinationAccess
import com.espitman.sdm.storage.CompletedDestinationPresence
import com.espitman.sdm.storage.CompletedFileUserMessages
import com.espitman.sdm.storage.ContentDocumentMutation
import com.espitman.sdm.storage.ContentDocumentStore
import com.espitman.sdm.storage.DownloadDestinationRef
import com.espitman.sdm.storage.deleteMessage
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed interface CompletedFileDeleteResult {
    val message: String

    data class Deleted(
        override val message: String = CompletedFileUserMessages.DELETED,
    ) : CompletedFileDeleteResult

    data class AlreadyMissing(
        override val message: String = CompletedFileUserMessages.ALREADY_DELETED,
    ) : CompletedFileDeleteResult

    data class Failure(
        override val message: String,
        val cause: Throwable? = null,
    ) : CompletedFileDeleteResult
}

object CompletedFileDeleteCoordinator {
    suspend fun delete(
        downloadId: String,
        repository: DownloadRepository,
        contentDocuments: ContentDocumentStore? = null,
    ): CompletedFileDeleteResult = withContext(Dispatchers.IO) {
        val current = repository.get(downloadId)
            ?: return@withContext CompletedFileDeleteResult.AlreadyMissing()
        if (current.state != DownloadState.COMPLETED) {
            return@withContext CompletedFileDeleteResult.Failure(CompletedFileUserMessages.DOWNLOAD_NOT_FOUND)
        }
        val destinationPath = current.destinationPath
        if (destinationPath.isNullOrBlank()) {
            return@withContext removeStaleRecord(repository, downloadId)
        }
        val presence = CompletedDestinationAccess.classify(
            destinationPath = destinationPath,
            treeUri = current.destinationTreeUri,
            contentDocuments = contentDocuments,
        )
        when (presence) {
            CompletedDestinationPresence.AccessUnavailable ->
                return@withContext CompletedFileDeleteResult.Failure(
                    CompletedFileUserMessages.ACCESS_UNAVAILABLE,
                )
            CompletedDestinationPresence.Missing ->
                return@withContext removeStaleRecord(repository, downloadId)
            CompletedDestinationPresence.Readable -> Unit
        }
        val storage = deleteStorage(destinationPath, current.destinationTreeUri, contentDocuments)
        when (storage) {
            is ContentDocumentMutation.Failure -> {
                if (storage.reason == ContentDocumentMutation.Failure.Reason.Missing) {
                    return@withContext removeStaleRecord(repository, downloadId)
                }
                if (storage.reason == ContentDocumentMutation.Failure.Reason.AccessUnavailable) {
                    return@withContext CompletedFileDeleteResult.Failure(
                        CompletedFileUserMessages.ACCESS_UNAVAILABLE,
                        storage.cause,
                    )
                }
                return@withContext CompletedFileDeleteResult.Failure(storage.deleteMessage(), storage.cause)
            }
            is ContentDocumentMutation.Success -> Unit
        }
        try {
            if (!repository.delete(downloadId)) {
                return@withContext CompletedFileDeleteResult.Failure(
                    CompletedFileUserMessages.DELETE_INCONSISTENT,
                )
            }
            CompletedFileDeleteResult.Deleted()
        } catch (failure: Throwable) {
            CompletedFileDeleteResult.Failure(CompletedFileUserMessages.DELETE_INCONSISTENT, failure)
        }
    }

    private suspend fun removeStaleRecord(
        repository: DownloadRepository,
        downloadId: String,
    ): CompletedFileDeleteResult {
        return try {
            repository.delete(downloadId)
            CompletedFileDeleteResult.AlreadyMissing()
        } catch (failure: Throwable) {
            CompletedFileDeleteResult.Failure(CompletedFileUserMessages.RECORD_UPDATE_FAILED, failure)
        }
    }

    private fun deleteStorage(
        destinationPath: String,
        treeUri: String?,
        contentDocuments: ContentDocumentStore?,
    ): ContentDocumentMutation {
        if (DownloadDestinationRef.isContentUri(destinationPath)) {
            val store = contentDocuments
                ?: return ContentDocumentMutation.Failure(
                    ContentDocumentMutation.Failure.Reason.AccessUnavailable,
                )
            return store.delete(destinationPath, treeUri)
        }
        val file = File(destinationPath)
        return try {
            if (!file.exists()) {
                ContentDocumentMutation.Failure(ContentDocumentMutation.Failure.Reason.Missing)
            } else if (!file.isFile) {
                ContentDocumentMutation.Failure(ContentDocumentMutation.Failure.Reason.Missing)
            } else if (!file.canWrite() && file.exists()) {
                val deleted = file.delete()
                if (deleted || !file.exists()) {
                    ContentDocumentMutation.Success(destinationPath, file.name)
                } else {
                    ContentDocumentMutation.Failure(ContentDocumentMutation.Failure.Reason.AccessUnavailable)
                }
            } else {
                val deleted = file.delete()
                if (deleted || !file.exists()) {
                    ContentDocumentMutation.Success(destinationPath, file.name)
                } else {
                    ContentDocumentMutation.Failure(ContentDocumentMutation.Failure.Reason.Refused)
                }
            }
        } catch (_: SecurityException) {
            ContentDocumentMutation.Failure(ContentDocumentMutation.Failure.Reason.AccessUnavailable)
        } catch (failure: Throwable) {
            ContentDocumentMutation.Failure(ContentDocumentMutation.Failure.Reason.Generic, failure)
        }
    }
}
