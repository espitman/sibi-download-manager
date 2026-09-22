package com.espitman.sdm.download

import com.espitman.sdm.data.DownloadRepository
import com.espitman.sdm.domain.Download
import com.espitman.sdm.domain.DownloadState
import com.espitman.sdm.domain.DownloadUrl
import com.espitman.sdm.domain.DownloadUrlResult
import com.espitman.sdm.network.DownloadFilenameResolver
import com.espitman.sdm.network.DownloadMetadata
import com.espitman.sdm.network.DownloadMetadataResult
import com.espitman.sdm.network.DownloadMetadataRetriever
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

sealed interface SubmissionResult {
    data class Success(val download: Download) : SubmissionResult
    data class Failure(val message: String, val cause: Throwable? = null) : SubmissionResult
}

fun interface DirectoryProvider {
    fun getDownloadsDirectory(): File
}

fun interface TransferStarter {
    fun startTransfer(download: Download, tempFile: File)
}

fun interface IdFactory {
    fun createId(): String

    object Default : IdFactory {
        override fun createId(): String = UUID.randomUUID().toString()
    }
}

class DownloadSubmissionCoordinator(
    private val metadataRetriever: DownloadMetadataRetriever,
    private val repository: DownloadRepository,
    private val directoryProvider: DirectoryProvider,
    private val queueScheduler: DownloadQueueScheduler,
    private val clock: Clock = Clock.SystemClock,
    private val idFactory: IdFactory = IdFactory.Default,
) {
    private val dedupeMutex = Mutex()
    private val inFlightSubmissions = mutableMapOf<String, Deferred<SubmissionResult>>()

    suspend fun submit(
        url: String,
        startNow: Boolean,
    ): SubmissionResult {
        val trimmedUrl = url.trim()
        val validatedUrl = when (val validation = DownloadUrl.validate(trimmedUrl)) {
            is DownloadUrlResult.Valid -> validation.url
            is DownloadUrlResult.Invalid -> {
                return SubmissionResult.Failure(DownloadUrl.errorMessage(validation.error))
            }
        }

        val dedupeKey = "$validatedUrl|$startNow"
        return coroutineScope {
            var myDeferred: Deferred<SubmissionResult>? = null
            var isLeader = false

            dedupeMutex.withLock {
                val existing = inFlightSubmissions[dedupeKey]
                if (existing != null) {
                    myDeferred = existing
                    isLeader = false
                } else {
                    val newDeferred = async {
                        performSubmission(validatedUrl)
                    }
                    inFlightSubmissions[dedupeKey] = newDeferred
                    myDeferred = newDeferred
                    isLeader = true
                }
            }

            try {
                myDeferred!!.await()
            } finally {
                if (isLeader) {
                    dedupeMutex.withLock {
                        inFlightSubmissions.remove(dedupeKey)
                    }
                }
            }
        }
    }

    private suspend fun performSubmission(
        validatedUrl: String,
    ): SubmissionResult {
        // 1. Retrieve metadata
        val metadataResult = try {
            metadataRetriever.retrieve(validatedUrl)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: Throwable) {
            val msg = e.message?.takeIf { it.isNotBlank() } ?: "Network error retrieving metadata"
            return SubmissionResult.Failure(msg, e)
        }

        val metadata: DownloadMetadata = when (metadataResult) {
            is DownloadMetadataResult.Success -> metadataResult.metadata
            is DownloadMetadataResult.Failure -> {
                return SubmissionResult.Failure(metadataResult.message, metadataResult.cause)
            }
        }

        // 2. Determine target directory and atomically reserve .part file
        var reservedTempFile: File? = null
        var persistedDownloadId: String? = null
        try {
            val baseDir = withContext(Dispatchers.IO) {
                val dir = directoryProvider.getDownloadsDirectory()
                if (!dir.exists()) {
                    val created = dir.mkdirs()
                    if (!created && !dir.exists()) {
                        throw IOException("Failed to create download directory: ${dir.absolutePath}")
                    }
                }
                if (!dir.isDirectory) {
                    throw IOException("Download target path is not a directory: ${dir.absolutePath}")
                }
                dir
            }

            val candidateFilename = metadata.suggestedFilename
            val (resolvedFilename, tempFile) = withContext(Dispatchers.IO) {
                reserveFileReservation(baseDir, candidateFilename)
            }
            reservedTempFile = tempFile

            val finalDestination = File(baseDir, resolvedFilename)
            val now = clock.currentTimeMillis()
            val downloadId = idFactory.createId()

            val download = Download(
                id = downloadId,
                url = validatedUrl,
                fileName = resolvedFilename,
                mimeType = metadata.contentType,
                etag = metadata.etag,
                lastModified = metadata.lastModified,
                destinationPath = finalDestination.absolutePath,
                totalBytes = metadata.contentLength,
                downloadedBytes = 0L,
                state = DownloadState.QUEUED,
                error = null,
                priority = 0,
                createdAtEpochMillis = now,
                updatedAtEpochMillis = now,
                startedAtEpochMillis = null,
                completedAtEpochMillis = null,
                acceptsRanges = metadata.acceptsRanges,
            )

            // 3. Persist exactly one QUEUED download
            repository.insert(download)
            persistedDownloadId = downloadId

            queueScheduler.schedule()

            return SubmissionResult.Success(download)
        } catch (cancellation: CancellationException) {
            // If cancelled, rollback database insertion and clean up temp file
            if (persistedDownloadId != null) {
                try {
                    repository.delete(persistedDownloadId)
                } catch (_: Throwable) {
                    // Ignore rollback failure
                }
            }
            cleanupTempFile(reservedTempFile)
            throw cancellation
        } catch (e: Throwable) {
            // On pre-insert, insert, or transfer starter errors, clean up inserted record and temp file
            if (persistedDownloadId != null) {
                try {
                    repository.delete(persistedDownloadId)
                } catch (_: Throwable) {
                    // Ignore rollback failure
                }
            }
            cleanupTempFile(reservedTempFile)
            val msg = e.message?.takeIf { it.isNotBlank() } ?: "Failed to submit download"
            return SubmissionResult.Failure(msg, e)
        }
    }

    private fun reserveFileReservation(
        baseDir: File,
        baseFilename: String,
    ): Pair<String, File> {
        if (!baseDir.exists()) {
            val created = baseDir.mkdirs()
            if (!created && !baseDir.exists()) {
                throw IOException("Download directory does not exist and could not be created: ${baseDir.absolutePath}")
            }
        }
        if (!baseDir.isDirectory) {
            throw IOException("Download directory is not a directory: ${baseDir.absolutePath}")
        }

        val sanitized = DownloadFilenameResolver.sanitize(baseFilename) ?: DownloadFilenameResolver.DEFAULT_FALLBACK_FILENAME

        val lastDotIndex = sanitized.lastIndexOf('.')
        val (stem, extension) = if (lastDotIndex > 0 && lastDotIndex < sanitized.length - 1) {
            Pair(sanitized.substring(0, lastDotIndex), sanitized.substring(lastDotIndex))
        } else {
            Pair(sanitized, "")
        }

        val maxAttempts = 1000
        for (attempt in 0 until maxAttempts) {
            val candidateFinalName = if (attempt == 0) {
                sanitized
            } else {
                DownloadFilenameResolver.truncateUtf8CodePoints(stem, DownloadFilenameResolver.MAX_FILENAME_BYTES - " ($attempt)$extension".toByteArray(Charsets.UTF_8).size).trimEnd('.', ' ') + " ($attempt)$extension"
            }

            val finalFile = File(baseDir, candidateFinalName)
            if (finalFile.exists()) {
                continue
            }

            val partFile = DownloadPartFile.forResolvedFilename(baseDir, candidateFinalName)
            try {
                if (partFile.createNewFile()) {
                    return Pair(candidateFinalName, partFile)
                } else {
                    // Collision: file already exists
                    continue
                }
            } catch (ioe: IOException) {
                throw IOException("Failed to reserve .part file in ${baseDir.absolutePath}: ${ioe.message}", ioe)
            }
        }

        throw IOException("Failed to reserve a unique .part file for $baseFilename in ${baseDir.absolutePath} after $maxAttempts attempts")
    }

    private fun cleanupTempFile(file: File?) {
        if (file != null && file.exists()) {
            try {
                file.delete()
            } catch (_: Throwable) {
                // Ignore cleanup error
            }
        }
    }
}
