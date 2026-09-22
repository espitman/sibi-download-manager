package com.espitman.sdm.download

import com.espitman.sdm.data.DownloadRepository
import com.espitman.sdm.domain.Download
import com.espitman.sdm.domain.DownloadState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.math.max
import kotlin.math.min

interface Clock {
    fun currentTimeMillis(): Long

    object SystemClock : Clock {
        override fun currentTimeMillis(): Long = System.currentTimeMillis()
    }
}

class DownloadTransferEngine(
    private val okHttpClient: OkHttpClient = OkHttpClient(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: Clock = Clock.SystemClock,
    private val bufferSizeBytes: Int = DEFAULT_BUFFER_SIZE_BYTES,
    private val progressUpdateIntervalBytes: Long = DEFAULT_PROGRESS_UPDATE_INTERVAL_BYTES,
    private val onChunkRead: (Int) -> Unit = {},
) {
    init {
        require(bufferSizeBytes > 0) { "Buffer size must be greater than 0: $bufferSizeBytes" }
        require(progressUpdateIntervalBytes > 0) {
            "Progress update interval must be greater than 0: $progressUpdateIntervalBytes"
        }
    }

    suspend fun executeTransfer(
        downloadId: String,
        url: String,
        tempFile: File,
        repository: DownloadRepository,
        pauseRequested: () -> Boolean = { false },
    ) = withContext(ioDispatcher) {
        require(downloadId.isNotBlank()) { "Download ID cannot be blank" }
        require(url.isNotBlank()) { "URL cannot be blank" }

        val existingDownload = repository.get(downloadId)
            ?: throw IllegalArgumentException("Download not found: $downloadId")

        repository.transition(
            id = downloadId,
            to = DownloadState.CONNECTING,
            nowEpochMillis = validTimestamp(existingDownload.updatedAtEpochMillis),
        )

        val destinationPath = existingDownload.destinationPath
        if (destinationPath.isNullOrBlank()) {
            reportFailure(repository, downloadId, "Destination path is missing")
            return@withContext
        }

        val destinationFile = File(destinationPath)
        if (destinationFile.exists()) {
            reportFailure(repository, downloadId, "Destination already exists: $destinationPath")
            return@withContext
        }

        val resumeOffset = if (tempFile.exists()) tempFile.length().coerceAtLeast(0L) else 0L
        val isResume = resumeOffset > 0L
        val storedValidators = HttpRangeResume.ResumeValidators(
            etag = existingDownload.etag,
            lastModified = existingDownload.lastModified,
        )
        if (isResume && HttpRangeResume.ifRangeHeaderValue(storedValidators) == null) {
            reportFailure(
                repository,
                downloadId,
                "Resume requires a stored strong ETag or Last-Modified",
            )
            return@withContext
        }

        var writeFile = tempFile
        var appendToWriteFile = isResume
        var startOffset = resumeOffset
        var restartFile: File? = null
        var restartAccepted = false
        var completedSuccessfully = false
        var sendRange = isResume
        var fallbackUsed = false
        var activeCall = okHttpClient.newCall(buildTransferRequest(url, sendRange, resumeOffset, storedValidators))
        val cancellationHandle = coroutineContext.job.invokeOnCompletion { cause ->
            if (cause is CancellationException) activeCall.cancel()
        }
        try {
            while (true) {
                activeCall.execute().use { response ->
                    var expectedTotal = existingDownload.totalBytes
                    var resumeContentRange: HttpRangeResume.ContentRange? = null
                    var treatAsFreshRestart = fallbackUsed && !sendRange

                    if (sendRange) {
                        when (HttpRangeResume.classifyResumeStatus(response.code)) {
                            HttpRangeResume.ResumeStatusAction.ContinuePartial -> {
                                val contentRange = try {
                                    HttpRangeResume.validateContentRange(
                                        header = response.header(HttpRangeResume.HEADER_CONTENT_RANGE),
                                        expectedOffset = resumeOffset,
                                        knownTotal = existingDownload.totalBytes,
                                    )
                                } catch (e: IllegalArgumentException) {
                                    reportFailure(
                                        repository,
                                        downloadId,
                                        e.message?.takeIf { it.isNotBlank() } ?: "Invalid Content-Range",
                                    )
                                    return@withContext
                                }
                                when (
                                    val validators = HttpRangeResume.validateStoredValidators(
                                        stored = storedValidators,
                                        responseEtag = response.header(HttpRangeResume.HEADER_ETAG),
                                        responseLastModified = response.header(HttpRangeResume.HEADER_LAST_MODIFIED),
                                    )
                                ) {
                                    is HttpRangeResume.ResumeValidation.Failed -> {
                                        reportFailure(repository, downloadId, validators.reason)
                                        return@withContext
                                    }
                                    HttpRangeResume.ResumeValidation.Ok -> Unit
                                }
                                val bodyForLength = response.body
                                try {
                                    HttpRangeResume.validateDeclaredBodyLength(
                                        inclusiveLength = contentRange.inclusiveLength,
                                        contentLength = bodyForLength?.contentLength() ?: -1L,
                                    )
                                } catch (e: IllegalArgumentException) {
                                    reportFailure(
                                        repository,
                                        downloadId,
                                        e.message?.takeIf { it.isNotBlank() }
                                            ?: "Response body length does not match Content-Range",
                                    )
                                    return@withContext
                                }
                                expectedTotal = contentRange.total ?: existingDownload.totalBytes
                                resumeContentRange = contentRange
                            }
                            HttpRangeResume.ResumeStatusAction.UseFullBodyRestart -> {
                                treatAsFreshRestart = true
                                fallbackUsed = true
                            }
                            HttpRangeResume.ResumeStatusAction.FetchFreshGet -> {
                                if (fallbackUsed) {
                                    reportFailure(
                                        repository,
                                        downloadId,
                                        "HTTP ${response.code}: resume restart already attempted",
                                    )
                                    return@withContext
                                }
                                fallbackUsed = true
                                sendRange = false
                                return@use
                            }
                            HttpRangeResume.ResumeStatusAction.Fail -> {
                                reportFailure(
                                    repository,
                                    downloadId,
                                    "HTTP ${response.code}: expected 206 Partial Content",
                                )
                                return@withContext
                            }
                        }
                    } else if (!response.isSuccessful) {
                        val statusCode = response.code
                        val statusMessage = response.message.ifBlank { "HTTP $statusCode error" }
                        if (fallbackUsed) {
                            reportFailure(
                                repository,
                                downloadId,
                                "HTTP $statusCode: $statusMessage",
                            )
                            return@withContext
                        }
                        if (tempFile.exists() && tempFile.length() == 0L) {
                            try { tempFile.delete() } catch (_: Throwable) {}
                        }
                        reportFailure(repository, downloadId, "HTTP $statusCode: $statusMessage")
                        return@withContext
                    }

                    if (treatAsFreshRestart) {
                        if (response.code != HttpRangeResume.HTTP_OK) {
                            reportFailure(
                                repository,
                                downloadId,
                                "HTTP ${response.code}: expected 200 after resume restart",
                            )
                            return@withContext
                        }
                        if (response.body == null) {
                            reportFailure(repository, downloadId, "Response body was empty")
                            return@withContext
                        }
                        val restartTarget = DownloadPartFile.restartForDestination(destinationFile)
                        val contentLength = response.body?.contentLength() ?: -1L
                        val restarted = beginFreshRestart(
                            repository = repository,
                            downloadId = downloadId,
                            responseEtag = response.header(HttpRangeResume.HEADER_ETAG),
                            responseLastModified = response.header(HttpRangeResume.HEADER_LAST_MODIFIED),
                            totalBytes = contentLength.takeIf { it >= 0L },
                        ) ?: return@withContext
                        restartFile = restartTarget
                        writeFile = restartTarget
                        appendToWriteFile = false
                        startOffset = 0L
                        expectedTotal = restarted.totalBytes
                        resumeContentRange = null
                        restartAccepted = true
                    }

                    val body = response.body ?: run {
                        reportFailure(repository, downloadId, "Response body was empty")
                        return@withContext
                    }

                    val connectingDownload = repository.get(downloadId)
                        ?: throw IllegalArgumentException("Download not found: $downloadId")
                    repository.transition(
                        id = downloadId,
                        to = DownloadState.DOWNLOADING,
                        nowEpochMillis = validTimestamp(connectingDownload.updatedAtEpochMillis),
                    )

                    val tempParent = writeFile.parentFile
                    if (tempParent != null && !tempParent.exists() && !tempParent.mkdirs()) {
                        throw IOException("Could not create temporary download directory: ${tempParent.path}")
                    }

                    var totalBytesRead = startOffset
                    var lastReportedBytes = startOffset
                    var extraResumeBytes = false

                    FileOutputStream(writeFile, appendToWriteFile).use { fileOutputStream ->
                        body.byteStream().use { inputStream ->
                            val buffer = ByteArray(bufferSizeBytes)

                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val bytesRead = inputStream.read(buffer)
                                if (bytesRead == -1) break

                                val allowedBytes = resumeContentRange?.let { range ->
                                    val remaining = startOffset + range.inclusiveLength - totalBytesRead
                                    if (remaining <= 0L) {
                                        extraResumeBytes = true
                                        0
                                    } else {
                                        val writable = min(bytesRead.toLong(), remaining).toInt()
                                        if (writable < bytesRead) extraResumeBytes = true
                                        writable
                                    }
                                } ?: if (restartAccepted) {
                                    expectedTotal?.let { known ->
                                        val remaining = known - totalBytesRead
                                        if (remaining <= 0L) {
                                            extraResumeBytes = true
                                            0
                                        } else {
                                            val writable = min(bytesRead.toLong(), remaining).toInt()
                                            if (writable < bytesRead) extraResumeBytes = true
                                            writable
                                        }
                                    } ?: bytesRead
                                } else {
                                    bytesRead
                                }
                                if (allowedBytes <= 0) break

                                fileOutputStream.write(buffer, 0, allowedBytes)
                                totalBytesRead += allowedBytes
                                onChunkRead(allowedBytes)
                                currentCoroutineContext().ensureActive()

                                if (
                                    totalBytesRead - lastReportedBytes >= progressUpdateIntervalBytes &&
                                    (expectedTotal == null || totalBytesRead <= expectedTotal)
                                ) {
                                    fileOutputStream.flush()
                                    updateProgress(repository, downloadId, totalBytesRead)
                                    lastReportedBytes = totalBytesRead
                                }
                                if (extraResumeBytes) break
                            }

                            fileOutputStream.flush()
                        }
                    }

                    currentCoroutineContext().ensureActive()

                    val range = resumeContentRange
                    if (range != null) {
                        val receivedBytes = totalBytesRead - startOffset
                        if (extraResumeBytes || receivedBytes != range.inclusiveLength) {
                            try {
                                HttpRangeResume.validateReceivedBodyLength(
                                    inclusiveLength = range.inclusiveLength,
                                    receivedBytes = receivedBytes,
                                )
                            } catch (e: IllegalArgumentException) {
                                reportFailure(
                                    repository,
                                    downloadId,
                                    e.message?.takeIf { it.isNotBlank() }
                                        ?: "Response body length does not match Content-Range",
                                )
                                return@withContext
                            }
                            reportFailure(
                                repository,
                                downloadId,
                                "Response body length exceeds Content-Range length ${range.inclusiveLength}",
                            )
                            return@withContext
                        }
                    } else if (extraResumeBytes) {
                        reportFailure(
                            repository,
                            downloadId,
                            "Response body length exceeds expected ${expectedTotal ?: totalBytesRead} bytes",
                        )
                        return@withContext
                    }

                    val completedBytes = if (writeFile.exists()) writeFile.length() else totalBytesRead
                    val knownTotal = expectedTotal
                    if (knownTotal != null && completedBytes != knownTotal) {
                        if (completedBytes != lastReportedBytes && completedBytes <= knownTotal) {
                            updateProgress(repository, downloadId, completedBytes)
                        }
                        reportFailure(
                            repository,
                            downloadId,
                            "Downloaded byte count mismatch: expected $knownTotal, received $completedBytes",
                        )
                        return@withContext
                    }

                    withContext(NonCancellable) {
                        if (totalBytesRead != lastReportedBytes) {
                            updateProgress(repository, downloadId, totalBytesRead)
                        }
                        finalizeWithoutOverwrite(writeFile, destinationFile)
                        if (restartAccepted) {
                            deleteQuietly(tempFile)
                            deleteQuietly(restartFile)
                        }
                        val downloadingDownload = repository.get(downloadId)
                            ?: throw IllegalArgumentException("Download not found: $downloadId")
                        repository.transition(
                            id = downloadId,
                            to = DownloadState.COMPLETED,
                            nowEpochMillis = validTimestamp(downloadingDownload.updatedAtEpochMillis),
                        )
                    }
                    completedSuccessfully = true
                }
                if (completedSuccessfully || sendRange || !fallbackUsed) {
                    return@withContext
                }
                activeCall = okHttpClient.newCall(
                    buildTransferRequest(url, sendRange = false, resumeOffset = 0L, storedValidators),
                )
            }
        } catch (cancellation: CancellationException) {
            activeCall.cancel()
            persistPausedIfRequested(repository, downloadId, writeFile, pauseRequested)
            commitRestartPartial(tempFile, restartFile, restartAccepted)
            throw cancellation
        } catch (e: Throwable) {
            try {
                currentCoroutineContext().ensureActive()
            } catch (cancellation: CancellationException) {
                persistPausedIfRequested(repository, downloadId, writeFile, pauseRequested)
                commitRestartPartial(tempFile, restartFile, restartAccepted)
                throw cancellation
            }
            val safeMessage = when (e) {
                is IOException -> e.message?.takeIf { it.isNotBlank() } ?: "Network I/O failure"
                else -> e.message?.takeIf { it.isNotBlank() } ?: "Download transfer failure"
            }
            try {
                reportFailure(repository, downloadId, safeMessage)
            } catch (_: Throwable) {
                // Ignore secondary failure on repository reporting
            }
        } finally {
            cancellationHandle.dispose()
            persistPausedIfRequested(repository, downloadId, writeFile, pauseRequested)
            if (!completedSuccessfully && restartAccepted) {
                commitRestartPartial(tempFile, restartFile, restartAccepted)
            } else if (!completedSuccessfully && !restartAccepted) {
                deleteQuietly(restartFile)
            }
        }
    }

    private fun buildTransferRequest(
        url: String,
        sendRange: Boolean,
        resumeOffset: Long,
        storedValidators: HttpRangeResume.ResumeValidators,
    ): Request {
        val requestBuilder = Request.Builder().url(url).get()
        if (sendRange) {
            HttpRangeResume.requestHeaders(resumeOffset, storedValidators).forEach { (name, value) ->
                requestBuilder.header(name, value)
            }
        }
        return requestBuilder.build()
    }

    private suspend fun beginFreshRestart(
        repository: DownloadRepository,
        downloadId: String,
        responseEtag: String?,
        responseLastModified: String?,
        totalBytes: Long?,
    ): Download? {
        val current = repository.get(downloadId) ?: return null
        return try {
            repository.beginFreshRestart(
                id = downloadId,
                nowEpochMillis = validTimestamp(current.updatedAtEpochMillis),
                etag = responseEtag,
                lastModified = responseLastModified,
                totalBytes = totalBytes,
            )
        } catch (e: IllegalArgumentException) {
            reportFailure(
                repository,
                downloadId,
                e.message?.takeIf { it.isNotBlank() } ?: "Unable to restart download",
            )
            null
        }
    }

    suspend fun persistPausedOffset(
        downloadId: String,
        tempFile: File,
        repository: DownloadRepository,
    ) {
        persistPausedIfRequested(repository, downloadId, tempFile) { true }
    }

    private suspend fun updateProgress(
        repository: DownloadRepository,
        downloadId: String,
        downloadedBytes: Long,
    ) {
        val current = repository.get(downloadId)
            ?: throw IllegalArgumentException("Download not found: $downloadId")
        repository.updateProgress(
            id = downloadId,
            downloadedBytes = downloadedBytes,
            nowEpochMillis = validTimestamp(current.updatedAtEpochMillis),
        )
    }

    private suspend fun persistPausedIfRequested(
        repository: DownloadRepository,
        downloadId: String,
        tempFile: File,
        pauseRequested: () -> Boolean,
    ) {
        if (!pauseRequested()) return
        withContext(NonCancellable) {
            val fileLength = if (tempFile.exists()) tempFile.length().coerceAtLeast(0L) else 0L
            val current = repository.get(downloadId) ?: return@withContext
            repository.pauseAtExactOffset(
                id = downloadId,
                fileLengthBytes = fileLength,
                nowEpochMillis = validTimestamp(current.updatedAtEpochMillis),
            )
        }
    }

    private fun commitRestartPartial(originalPart: File, restartFile: File?, accepted: Boolean) {
        if (!accepted) return
        val restart = restartFile ?: return
        if (!restart.exists()) return
        if (originalPart.exists() && originalPart.canonicalFile == restart.canonicalFile) return
        try {
            if (originalPart.exists()) {
                val obsolete = File(originalPart.parentFile, originalPart.name + ".obsolete")
                deleteQuietly(obsolete)
                try {
                    Files.move(originalPart.toPath(), obsolete.toPath(), StandardCopyOption.ATOMIC_MOVE)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(originalPart.toPath(), obsolete.toPath())
                }
                deleteQuietly(obsolete)
            }
            try {
                Files.move(restart.toPath(), originalPart.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(restart.toPath(), originalPart.toPath())
            }
        } catch (_: Throwable) {
            // Best-effort isolation of the new partial from the obsolete part.
        }
    }

    private fun deleteQuietly(file: File?) {
        if (file == null || !file.exists()) return
        try {
            file.delete()
        } catch (_: Throwable) {
        }
    }

    private suspend fun reportFailure(
        repository: DownloadRepository,
        downloadId: String,
        error: String,
    ) {
        val current = repository.get(downloadId) ?: return
        if (current.state == DownloadState.FAILED) return
        repository.transition(
            id = downloadId,
            to = DownloadState.FAILED,
            nowEpochMillis = validTimestamp(current.updatedAtEpochMillis),
            error = error,
        )
    }

    private fun validTimestamp(previousTimestamp: Long): Long =
        max(clock.currentTimeMillis(), previousTimestamp)

    private fun finalizeWithoutOverwrite(tempFile: File, destinationFile: File) {
        require(tempFile.exists()) { "Temporary download file is missing: ${tempFile.path}" }
        if (destinationFile.exists()) {
            throw IOException("Destination already exists: ${destinationFile.path}")
        }

        val parent = destinationFile.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw IOException("Could not create destination directory: ${parent.path}")
        }
        if (parent != null && !parent.isDirectory) {
            throw IOException("Destination parent is not a directory: ${parent.path}")
        }

        try {
            Files.move(tempFile.toPath(), destinationFile.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tempFile.toPath(), destinationFile.toPath())
        }
    }

    companion object {
        const val DEFAULT_BUFFER_SIZE_BYTES = 8 * 1024 // 8 KB bounded buffer
        const val DEFAULT_PROGRESS_UPDATE_INTERVAL_BYTES = 64 * 1024L // 64 KB
    }
}
