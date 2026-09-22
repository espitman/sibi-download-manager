package com.espitman.sdm.download

import com.espitman.sdm.data.DownloadRepository
import com.espitman.sdm.domain.Download
import com.espitman.sdm.domain.DownloadState
import com.espitman.sdm.domain.DownloadUrl
import com.espitman.sdm.domain.DownloadUrlResult
import com.espitman.sdm.network.DownloadMetadata
import com.espitman.sdm.network.DownloadMetadataResult
import com.espitman.sdm.network.DownloadMetadataRetriever
import com.espitman.sdm.network.BrowserRequestContextRegistry
import com.espitman.sdm.network.ScopedRequestContext
import com.espitman.sdm.storage.AppPrivateDestinationAllocator
import com.espitman.sdm.storage.DestinationAllocator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
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
    private val destinationAllocator: DestinationAllocator = AppPrivateDestinationAllocator(
        directory = { directoryProvider.getDownloadsDirectory() },
    ),
) {
    private val dedupeMutex = Mutex()
    private val inFlightSubmissions = mutableMapOf<String, Deferred<SubmissionResult>>()

    suspend fun submit(
        url: String,
        startNow: Boolean,
        requestContext: ScopedRequestContext? = null,
    ): SubmissionResult {
        val trimmedUrl = url.trim()
        val validatedUrl = when (val validation = DownloadUrl.validate(trimmedUrl)) {
            is DownloadUrlResult.Valid -> validation.url
            is DownloadUrlResult.Invalid -> {
                return SubmissionResult.Failure(DownloadUrl.errorMessage(validation.error))
            }
        }

        val dedupeKey = "$validatedUrl|$startNow|${requestContext?.let { "${it.scopeKey}:${System.identityHashCode(it)}" } ?: "public"}"
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
                        performSubmission(validatedUrl, requestContext)
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
        requestContext: ScopedRequestContext?,
    ): SubmissionResult {
        // 1. Retrieve metadata
        val metadataResult = try {
            metadataRetriever.retrieve(validatedUrl, requestContext)
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
            val allocated = withContext(Dispatchers.IO) {
                destinationAllocator.allocate(metadata.suggestedFilename)
            }
            reservedTempFile = allocated.partFile

            val now = clock.currentTimeMillis()
            val downloadId = idFactory.createId()

            val download = Download(
                id = downloadId,
                url = metadata.url,
                fileName = allocated.fileName,
                mimeType = metadata.contentType,
                etag = metadata.etag,
                lastModified = metadata.lastModified,
                destinationPath = allocated.destinationPath,
                totalBytes = metadata.contentLength,
                downloadedBytes = 0L,
                state = DownloadState.QUEUED,
                error = null,
                priority = 0,
                sortOrder = now,
                createdAtEpochMillis = now,
                updatedAtEpochMillis = now,
                startedAtEpochMillis = null,
                completedAtEpochMillis = null,
                acceptsRanges = metadata.acceptsRanges,
                referenceSha256 = metadata.referenceSha256,
                destinationTreeUri = allocated.destinationTreeUri,
                destinationDisplayLabel = allocated.destinationDisplayLabel,
            )

            // 3. Persist exactly one QUEUED download
            repository.insert(download)
            persistedDownloadId = downloadId
            BrowserRequestContextRegistry.put(downloadId, requestContext)

            queueScheduler.schedule()

            return SubmissionResult.Success(download)
        } catch (cancellation: CancellationException) {
            // If cancelled, rollback database insertion and clean up temp file
            if (persistedDownloadId != null) {
                BrowserRequestContextRegistry.remove(persistedDownloadId)
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
                BrowserRequestContextRegistry.remove(persistedDownloadId)
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
