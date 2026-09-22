package com.espitman.sdm.download

import com.espitman.sdm.data.DownloadRepository
import com.espitman.sdm.domain.Download
import com.espitman.sdm.domain.DownloadState
import com.espitman.sdm.network.DownloadFilenameResolver
import com.espitman.sdm.storage.CompletedDestinationPresence
import com.espitman.sdm.storage.CompletedFileUserMessages
import com.espitman.sdm.storage.ContentDocumentMutation
import com.espitman.sdm.storage.ContentDocumentStore
import com.espitman.sdm.storage.DownloadDestinationRef
import com.espitman.sdm.storage.renameMessage
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed interface DownloadRenameResult {
    data class Success(val download: Download) : DownloadRenameResult
    data class Failure(val message: String, val cause: Throwable? = null) : DownloadRenameResult
    data class PauseRequired(
        val message: String = "Pause the download before renaming",
    ) : DownloadRenameResult
}

object DownloadRenameCoordinator {
    private val ACTIVE = setOf(DownloadState.CONNECTING, DownloadState.DOWNLOADING)
    private val INCOMPLETE = setOf(
        DownloadState.QUEUED,
        DownloadState.PAUSED,
        DownloadState.FAILED,
        DownloadState.CANCELLED,
    )

    suspend fun rename(
        downloadId: String,
        rawFilename: String,
        repository: DownloadRepository,
        clock: Clock,
        contentDocuments: ContentDocumentStore? = null,
    ): DownloadRenameResult = withContext(Dispatchers.IO) {
        val current = repository.get(downloadId)
            ?: return@withContext DownloadRenameResult.Failure(CompletedFileUserMessages.DOWNLOAD_NOT_FOUND)
        if (current.state in ACTIVE) {
            return@withContext DownloadRenameResult.PauseRequired()
        }
        val destinationPath = current.destinationPath
        if (destinationPath.isNullOrBlank()) {
            return@withContext DownloadRenameResult.Failure(CompletedFileUserMessages.DESTINATION_REQUIRED)
        }
        val validated = validateFilename(rawFilename)
            ?: return@withContext filenameFailure(rawFilename)
        if (DownloadDestinationRef.isContentUri(destinationPath)) {
            return@withContext renameContentDocument(
                current = current,
                validated = validated,
                repository = repository,
                clock = clock,
                contentDocuments = contentDocuments,
            )
        }
        val sourceDestination = File(destinationPath)
        val parent = sourceDestination.parentFile
            ?: return@withContext DownloadRenameResult.Failure(CompletedFileUserMessages.DESTINATION_REQUIRED)
        val targetDestination = File(parent, validated)
        if (!samePath(sourceDestination, targetDestination) && targetDestination.exists()) {
            return@withContext DownloadRenameResult.Failure(CompletedFileUserMessages.COLLISION)
        }

        val plannedMoves = plannedMoves(current.state, sourceDestination, targetDestination)
            ?: return@withContext DownloadRenameResult.Failure(CompletedFileUserMessages.MISSING)
        if (plannedMoves.any { (from, to) -> to.exists() && !samePath(from, to) }) {
            return@withContext DownloadRenameResult.Failure(CompletedFileUserMessages.COLLISION)
        }

        val completedMoves = ArrayList<Pair<File, File>>(plannedMoves.size)
        try {
            for ((from, to) in plannedMoves) {
                moveWithoutOverwrite(from, to)
                completedMoves.add(from to to)
            }
            val updated = try {
                repository.renameRecord(
                    id = downloadId,
                    fileName = validated,
                    destinationPath = targetDestination.absolutePath,
                    nowEpochMillis = clock.currentTimeMillis(),
                )
            } catch (cancelled: CancellationException) {
                rollback(completedMoves)
                throw cancelled
            } catch (failure: Throwable) {
                rollback(completedMoves)
                return@withContext DownloadRenameResult.Failure(
                    CompletedFileUserMessages.RECORD_UPDATE_FAILED,
                    failure,
                )
            }
            DownloadRenameResult.Success(updated)
        } catch (cancelled: CancellationException) {
            rollback(completedMoves)
            throw cancelled
        } catch (failure: Throwable) {
            rollback(completedMoves)
            val message = if (failure is FileAlreadyExistsException) {
                CompletedFileUserMessages.COLLISION
            } else {
                CompletedFileUserMessages.RENAME_FAILED
            }
            DownloadRenameResult.Failure(message, failure)
        }
    }

    private suspend fun renameContentDocument(
        current: Download,
        validated: String,
        repository: DownloadRepository,
        clock: Clock,
        contentDocuments: ContentDocumentStore?,
    ): DownloadRenameResult {
        val store = contentDocuments
            ?: return DownloadRenameResult.Failure(CompletedFileUserMessages.ACCESS_UNAVAILABLE)
        val destinationPath = current.destinationPath
            ?: return DownloadRenameResult.Failure(CompletedFileUserMessages.DESTINATION_REQUIRED)
        val presence = store.presence(destinationPath, current.destinationTreeUri)
        when (presence) {
            CompletedDestinationPresence.Missing ->
                return DownloadRenameResult.Failure(CompletedFileUserMessages.MISSING)
            CompletedDestinationPresence.AccessUnavailable ->
                return DownloadRenameResult.Failure(CompletedFileUserMessages.ACCESS_UNAVAILABLE)
            CompletedDestinationPresence.Readable -> Unit
        }
        if (validated == current.fileName) {
            return DownloadRenameResult.Success(current)
        }
        val renamed = when (val mutation = store.rename(destinationPath, current.destinationTreeUri, validated)) {
            is ContentDocumentMutation.Failure ->
                return DownloadRenameResult.Failure(mutation.renameMessage(), mutation.cause)
            is ContentDocumentMutation.Success -> mutation
        }
        val updated = try {
            repository.renameRecord(
                id = current.id,
                fileName = validated,
                destinationPath = renamed.documentUri,
                nowEpochMillis = clock.currentTimeMillis(),
            )
        } catch (cancelled: CancellationException) {
            store.rename(renamed.documentUri, current.destinationTreeUri, current.fileName)
            throw cancelled
        } catch (failure: Throwable) {
            store.rename(renamed.documentUri, current.destinationTreeUri, current.fileName)
            return DownloadRenameResult.Failure(CompletedFileUserMessages.RECORD_UPDATE_FAILED, failure)
        }
        return DownloadRenameResult.Success(updated)
    }

    private fun validateFilename(rawFilename: String): String? {
        if (rawFilename.isBlank() || '/' in rawFilename || '\\' in rawFilename) return null
        val sanitized = DownloadFilenameResolver.sanitize(rawFilename) ?: return null
        return sanitized.takeIf { it == rawFilename }
    }

    private fun filenameFailure(rawFilename: String): DownloadRenameResult.Failure {
        val message = when {
            rawFilename.isBlank() -> CompletedFileUserMessages.FILENAME_BLANK
            '/' in rawFilename || '\\' in rawFilename -> CompletedFileUserMessages.FILENAME_SEPARATOR
            else -> CompletedFileUserMessages.FILENAME_UNSAFE
        }
        return DownloadRenameResult.Failure(message)
    }

    private fun plannedMoves(
        state: DownloadState,
        sourceDestination: File,
        targetDestination: File,
    ): List<Pair<File, File>>? {
        if (samePath(sourceDestination, targetDestination)) return emptyList()
        return when (state) {
            DownloadState.COMPLETED -> {
                if (!sourceDestination.exists()) null else listOf(sourceDestination to targetDestination)
            }
            in INCOMPLETE -> buildList {
                val oldPart = DownloadPartFile.forDestination(sourceDestination)
                val newPart = DownloadPartFile.forDestination(targetDestination)
                if (oldPart.exists() && !samePath(oldPart, newPart)) {
                    add(oldPart to newPart)
                }
                val oldRestart = DownloadPartFile.restartForDestination(sourceDestination)
                val newRestart = DownloadPartFile.restartForDestination(targetDestination)
                if (oldRestart.exists() && !samePath(oldRestart, newRestart)) {
                    add(oldRestart to newRestart)
                }
            }
            else -> emptyList()
        }
    }

    private fun moveWithoutOverwrite(from: File, to: File) {
        val parent = to.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            error("Could not create destination directory: ${parent.path}")
        }
        try {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(from.toPath(), to.toPath())
        }
    }

    private fun rollback(moves: List<Pair<File, File>>) {
        for ((from, to) in moves.asReversed()) {
            if (!to.exists() || from.exists()) continue
            try {
                moveWithoutOverwrite(to, from)
            } catch (_: Throwable) {
            }
        }
    }

    private fun samePath(left: File, right: File): Boolean =
        left.absoluteFile.normalize() == right.absoluteFile.normalize()
}
