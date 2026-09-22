package com.espitman.sdm.download

import com.espitman.sdm.data.DownloadRepository
import com.espitman.sdm.domain.Download
import com.espitman.sdm.domain.DownloadState
import com.espitman.sdm.network.DownloadFilenameResolver
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
    ): DownloadRenameResult = withContext(Dispatchers.IO) {
        val current = repository.get(downloadId)
            ?: return@withContext DownloadRenameResult.Failure("Download not found")
        if (current.state in ACTIVE) {
            return@withContext DownloadRenameResult.PauseRequired()
        }
        val destinationPath = current.destinationPath
        if (destinationPath.isNullOrBlank()) {
            return@withContext DownloadRenameResult.Failure("Destination path is required")
        }
        val sourceDestination = File(destinationPath)
        val parent = sourceDestination.parentFile
            ?: return@withContext DownloadRenameResult.Failure("Destination path is required")

        val validated = validateFilename(rawFilename)
            ?: return@withContext filenameFailure(rawFilename)
        val targetDestination = File(parent, validated)
        if (!samePath(sourceDestination, targetDestination) && targetDestination.exists()) {
            return@withContext DownloadRenameResult.Failure("A file with that name already exists")
        }

        val plannedMoves = plannedMoves(current.state, sourceDestination, targetDestination)
            ?: return@withContext DownloadRenameResult.Failure("Completed file is missing")
        if (plannedMoves.any { (from, to) -> to.exists() && !samePath(from, to) }) {
            return@withContext DownloadRenameResult.Failure("A file with that name already exists")
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
                return@withContext DownloadRenameResult.Failure("Could not update download record", failure)
            }
            DownloadRenameResult.Success(updated)
        } catch (cancelled: CancellationException) {
            rollback(completedMoves)
            throw cancelled
        } catch (failure: Throwable) {
            rollback(completedMoves)
            val message = if (failure is FileAlreadyExistsException) {
                "A file with that name already exists"
            } else {
                "Could not rename file"
            }
            DownloadRenameResult.Failure(message, failure)
        }
    }

    private fun validateFilename(rawFilename: String): String? {
        if (rawFilename.isBlank() || '/' in rawFilename || '\\' in rawFilename) return null
        val sanitized = DownloadFilenameResolver.sanitize(rawFilename) ?: return null
        return sanitized.takeIf { it == rawFilename }
    }

    private fun filenameFailure(rawFilename: String): DownloadRenameResult.Failure {
        val message = when {
            rawFilename.isBlank() -> "Filename cannot be blank"
            '/' in rawFilename || '\\' in rawFilename -> "Filename cannot contain path separators"
            else -> "Filename is unsafe"
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
