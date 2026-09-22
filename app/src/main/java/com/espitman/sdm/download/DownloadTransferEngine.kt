package com.espitman.sdm.download

import com.espitman.sdm.data.DownloadRepository
import com.espitman.sdm.domain.Download
import com.espitman.sdm.domain.DownloadPauseCause
import com.espitman.sdm.domain.DownloadState
import com.espitman.sdm.domain.ErrorReportSanitizer
import com.espitman.sdm.storage.DownloadDestinationPublisher
import com.espitman.sdm.storage.DownloadDestinationRef
import com.espitman.sdm.storage.StorageCapacity
import com.espitman.sdm.storage.StorageCapacityProbe
import com.espitman.sdm.storage.TransferSpacePreflight
import com.espitman.sdm.storage.TransferSpacePreflightResult
import com.espitman.sdm.network.ScopedRequestContext
import com.espitman.sdm.network.ScopedRequestContextInterceptor
import com.espitman.sdm.network.BrowserRequestContextRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import javax.net.ssl.SSLException
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
    okHttpClient: OkHttpClient = OkHttpClient(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: Clock = Clock.SystemClock,
    private val bufferSizeBytes: Int = DEFAULT_BUFFER_SIZE_BYTES,
    private val progressUpdateIntervalBytes: Long = DEFAULT_PROGRESS_UPDATE_INTERVAL_BYTES,
    private val onChunkRead: (Int) -> Unit = {},
    private val destinationPublisher: DownloadDestinationPublisher = DownloadDestinationPublisher.KeepLocal,
    private val storageCapacity: StorageCapacityProbe = StorageCapacityProbe.Unknown,
    private val speedLimiter: SpeedLimiter = SpeedLimiter.Unlimited,
    private val segmentCount: () -> Int = { SegmentedTransferPolicy.INITIAL_SEGMENT_COUNT },
    private val requestContext: (String) -> ScopedRequestContext? = { null },
) {
    private val okHttpClient = okHttpClient.newBuilder()
        .addNetworkInterceptor(ScopedRequestContextInterceptor())
        .build()
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
        pauseCause: () -> DownloadPauseCause? = { null },
    ) = withContext(ioDispatcher) {
        require(downloadId.isNotBlank()) { "Download ID cannot be blank" }
        require(url.isNotBlank()) { "URL cannot be blank" }

        var existingDownload = repository.get(downloadId)
            ?: throw IllegalArgumentException("Download not found: $downloadId")
        val scopedRequestContext = requestContext(downloadId)

        repository.transition(
            id = downloadId,
            to = DownloadState.CONNECTING,
            nowEpochMillis = validTimestamp(existingDownload.updatedAtEpochMillis),
        )

        val destinationPath = existingDownload.destinationPath
        if (destinationPath.isNullOrBlank() || DownloadDestinationRef.isContentUri(destinationPath)) {
            reportFailure(repository, downloadId, "Destination path is missing")
            return@withContext
        }

        val destinationFile = File(destinationPath)
        if (destinationFile.exists()) {
            reportFailure(repository, downloadId, "Destination already exists")
            return@withContext
        }

        recoverSegmentArtifacts(tempFile)
        val resumeOffset = if (tempFile.exists()) tempFile.length().coerceAtLeast(0L) else 0L
        existingDownload = repository.alignDownloadedBytes(
            downloadId,
            resumeOffset,
            validTimestamp(existingDownload.updatedAtEpochMillis),
        )
        if (
            resumeOffset > 0L &&
            existingDownload.totalBytes == resumeOffset &&
            existingDownload.downloadedBytes == resumeOffset
        ) {
            val connecting = repository.get(downloadId)
                ?: throw IllegalArgumentException("Download not found: $downloadId")
            repository.transition(
                id = downloadId,
                to = DownloadState.DOWNLOADING,
                nowEpochMillis = validTimestamp(connecting.updatedAtEpochMillis),
            )
            try {
                finalizeCompletedPart(repository, downloadId, tempFile, destinationFile)
                BrowserRequestContextRegistry.remove(downloadId)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (e: Throwable) {
                reportFailure(
                    repository,
                    downloadId,
                    e.message?.takeIf { it.isNotBlank() } ?: "Could not save completed download",
                )
            }
            return@withContext
        }
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

        val segmentPlan = SegmentedTransferPolicy.plan(
            download = existingDownload,
            resumeOffset = resumeOffset,
            segmentCount = segmentCount(),
        )
        if (segmentPlan != null) {
            val segmentedTotalBytes = existingDownload.totalBytes
                ?: throw IllegalStateException("Segmented transfer requires a known size")
            val localCapacity = queryLocalCapacity(tempFile)
            val requiredLocal = SegmentedTransferPolicy.requiredLocalBytes(
                segmentedTotalBytes,
                segmentPlan,
            )
            if (localCapacity.availableBytes?.let { it < requiredLocal } == true) {
                reportFailure(repository, downloadId, TransferSpacePreflight.INSUFFICIENT_STORAGE_ERROR)
                return@withContext
            }
            when (
                TransferSpacePreflight.evaluate(
                    knownFinalSizeBytes = segmentedTotalBytes,
                    existingValidPartBytes = 0L,
                    restartingFresh = false,
                    localCapacity = localCapacity,
                    destinationTreeUri = existingDownload.destinationTreeUri,
                    treeCapacity = queryTreeCapacity(existingDownload.destinationTreeUri),
                )
            ) {
                TransferSpacePreflightResult.Allowed -> Unit
                is TransferSpacePreflightResult.Insufficient -> {
                    reportFailure(repository, downloadId, TransferSpacePreflight.INSUFFICIENT_STORAGE_ERROR)
                    return@withContext
                }
            }
            val connecting = repository.get(downloadId)
                ?: throw IllegalArgumentException("Download not found: $downloadId")
            repository.transition(
                id = downloadId,
                to = DownloadState.DOWNLOADING,
                nowEpochMillis = validTimestamp(connecting.updatedAtEpochMillis),
            )
            try {
                val segmented = downloadSegments(
                    url = url,
                    ranges = segmentPlan,
                    totalBytes = segmentedTotalBytes,
                    validators = storedValidators,
                    tempFile = tempFile,
                    repository = repository,
                    downloadId = downloadId,
                    requestContext = scopedRequestContext,
                )
                if (segmented) {
                    withContext(NonCancellable) {
                        updateProgress(repository, downloadId, segmentedTotalBytes)
                        finalizeCompletedPart(repository, downloadId, tempFile, destinationFile)
                        BrowserRequestContextRegistry.remove(downloadId)
                    }
                    return@withContext
                }
            } catch (cancellation: CancellationException) {
                persistPausedIfRequested(repository, downloadId, tempFile, pauseRequested, pauseCause)
                throw cancellation
            } catch (e: Throwable) {
                reportFailure(repository, downloadId, transferFailureMessage(e, "Segmented transfer failure"))
                return@withContext
            }
        }

        var writeFile = tempFile
        var appendToWriteFile = isResume
        var startOffset = resumeOffset
        var restartFile: File? = null
        var restartAccepted = false
        var completedSuccessfully = false
        var sendRange = isResume
        var fallbackUsed = false
        var activeCall = okHttpClient.newCall(
            buildTransferRequest(url, sendRange, resumeOffset, storedValidators, scopedRequestContext),
        )
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

                    var pendingFreshRestart = false
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
                        val contentLength = response.body?.contentLength() ?: -1L
                        expectedTotal = contentLength.takeIf { it >= 0L }
                        resumeContentRange = null
                        pendingFreshRestart = true
                    }

                    val body = response.body ?: run {
                        reportFailure(repository, downloadId, "Response body was empty")
                        return@withContext
                    }

                    val knownFinalSize = expectedTotal
                        ?: body.contentLength().takeIf { it >= 0L && !sendRange }
                    val localProbePath = if (pendingFreshRestart) {
                        DownloadPartFile.restartForDestination(destinationFile)
                    } else {
                        writeFile
                    }
                    when (
                        TransferSpacePreflight.evaluate(
                            knownFinalSizeBytes = knownFinalSize,
                            existingValidPartBytes = startOffset,
                            restartingFresh = pendingFreshRestart,
                            localCapacity = queryLocalCapacity(localProbePath),
                            destinationTreeUri = existingDownload.destinationTreeUri,
                            treeCapacity = queryTreeCapacity(existingDownload.destinationTreeUri),
                        )
                    ) {
                        TransferSpacePreflightResult.Allowed -> Unit
                        is TransferSpacePreflightResult.Insufficient -> {
                            reportFailure(
                                repository,
                                downloadId,
                                TransferSpacePreflight.INSUFFICIENT_STORAGE_ERROR,
                            )
                            return@withContext
                        }
                    }

                    if (pendingFreshRestart) {
                        val restartTarget = DownloadPartFile.restartForDestination(destinationFile)
                        val restarted = beginFreshRestart(
                            repository = repository,
                            downloadId = downloadId,
                            responseEtag = response.header(HttpRangeResume.HEADER_ETAG),
                            responseLastModified = response.header(HttpRangeResume.HEADER_LAST_MODIFIED),
                            totalBytes = knownFinalSize,
                        ) ?: return@withContext
                        restartFile = restartTarget
                        writeFile = restartTarget
                        appendToWriteFile = false
                        startOffset = 0L
                        expectedTotal = restarted.totalBytes
                        restartAccepted = true
                    }

                    val connectingDownload = repository.get(downloadId)
                        ?: throw IllegalArgumentException("Download not found: $downloadId")
                    if (connectingDownload.state != DownloadState.DOWNLOADING) {
                        repository.transition(
                            id = downloadId,
                            to = DownloadState.DOWNLOADING,
                            nowEpochMillis = validTimestamp(connectingDownload.updatedAtEpochMillis),
                        )
                    }

                    val tempParent = writeFile.parentFile
                    if (tempParent != null && !tempParent.exists() && !tempParent.mkdirs()) {
                        throw IOException("Could not create temporary download directory: ${tempParent.path}")
                    }

                    var totalBytesRead = startOffset
                    var lastReportedBytes = startOffset
                    var lastReportedAtEpochMillis = clock.currentTimeMillis()
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

                                var chunkOffset = 0
                                while (chunkOffset < allowedBytes) {
                                    currentCoroutineContext().ensureActive()
                                    val remaining = allowedBytes - chunkOffset
                                    val admitted = speedLimiter.acquire(remaining)
                                    currentCoroutineContext().ensureActive()
                                    require(admitted > 0) { "Speed limiter admitted no bytes" }
                                    val toWrite = min(admitted, remaining)
                                    fileOutputStream.write(buffer, chunkOffset, toWrite)
                                    totalBytesRead += toWrite
                                    onChunkRead(toWrite)
                                    currentCoroutineContext().ensureActive()
                                    chunkOffset += toWrite

                                    if (shouldPublishProgress(totalBytesRead, lastReportedBytes, lastReportedAtEpochMillis, expectedTotal)) {
                                        fileOutputStream.flush()
                                        updateProgress(repository, downloadId, totalBytesRead)
                                        lastReportedBytes = totalBytesRead
                                        lastReportedAtEpochMillis = clock.currentTimeMillis()
                                    }
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
                        val published = destinationPublisher.afterLocalFinalize(
                            downloadingDownload,
                            destinationFile,
                        )
                        val destinationChanged =
                            published.destinationPath != downloadingDownload.destinationPath ||
                                published.destinationTreeUri != downloadingDownload.destinationTreeUri ||
                                published.destinationDisplayLabel != downloadingDownload.destinationDisplayLabel ||
                                published.fileName != downloadingDownload.fileName
                        val completedSource = if (destinationChanged) {
                            repository.updateDestination(
                                id = downloadId,
                                destinationPath = published.destinationPath,
                                destinationTreeUri = published.destinationTreeUri,
                                destinationDisplayLabel = published.destinationDisplayLabel,
                                fileName = published.fileName,
                                nowEpochMillis = validTimestamp(downloadingDownload.updatedAtEpochMillis),
                            )
                        } else {
                            downloadingDownload
                        }
                        repository.transition(
                            id = downloadId,
                            to = DownloadState.COMPLETED,
                            nowEpochMillis = validTimestamp(completedSource.updatedAtEpochMillis),
                        )
                    }
                    completedSuccessfully = true
                    BrowserRequestContextRegistry.remove(downloadId)
                }
                if (completedSuccessfully || sendRange || !fallbackUsed) {
                    return@withContext
                }
                activeCall = okHttpClient.newCall(
                    buildTransferRequest(
                        url,
                        sendRange = false,
                        resumeOffset = 0L,
                        storedValidators,
                        scopedRequestContext,
                    ),
                )
            }
        } catch (cancellation: CancellationException) {
            activeCall.cancel()
            persistPausedIfRequested(repository, downloadId, writeFile, pauseRequested, pauseCause)
            commitRestartPartial(tempFile, restartFile, restartAccepted)
            throw cancellation
        } catch (e: Throwable) {
            try {
                currentCoroutineContext().ensureActive()
            } catch (cancellation: CancellationException) {
                persistPausedIfRequested(repository, downloadId, writeFile, pauseRequested, pauseCause)
                commitRestartPartial(tempFile, restartFile, restartAccepted)
                throw cancellation
            }
            try {
                reportFailure(repository, downloadId, transferFailureMessage(e, "Download transfer failure"))
            } catch (_: Throwable) {
                // Ignore secondary failure on repository reporting
            }
        } finally {
            cancellationHandle.dispose()
            persistPausedIfRequested(repository, downloadId, writeFile, pauseRequested, pauseCause)
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
        requestContext: ScopedRequestContext?,
    ): Request {
        val requestBuilder = Request.Builder().url(url).get()
        requestContext?.let { requestBuilder.tag(ScopedRequestContext::class.java, it) }
        if (sendRange) {
            HttpRangeResume.requestHeaders(resumeOffset, storedValidators).forEach { (name, value) ->
                requestBuilder.header(name, value)
            }
        }
        return requestBuilder.build()
    }

    private suspend fun downloadSegments(
        url: String,
        ranges: List<TransferByteRange>,
        totalBytes: Long,
        validators: HttpRangeResume.ResumeValidators,
        tempFile: File,
        repository: DownloadRepository,
        downloadId: String,
        requestContext: ScopedRequestContext?,
    ): Boolean {
        val segmentFiles = ranges.map { segmentFile(tempFile, it) }
        segmentFiles.forEach(::deleteQuietly)
        deleteQuietly(tempFile)
        val progress = LongArray(ranges.size)
        val progressMutex = Mutex()
        var lastReportedBytes = 0L
        var lastReportedAt = clock.currentTimeMillis()
        return try {
            coroutineScope {
                ranges.mapIndexed { index, range ->
                    async {
                        downloadSegment(
                            url = url,
                            range = range,
                            totalBytes = totalBytes,
                            validators = validators,
                            output = segmentFiles[index],
                            requestContext = requestContext,
                        ) { written ->
                            progressMutex.withLock {
                                progress[index] += written
                                val aggregate = progress.sum()
                                if (shouldPublishProgress(aggregate, lastReportedBytes, lastReportedAt, totalBytes)) {
                                    updateProgress(repository, downloadId, aggregate)
                                    lastReportedBytes = aggregate
                                    lastReportedAt = clock.currentTimeMillis()
                                }
                            }
                        }
                    }
                }.awaitAll()
            }
            val parent = tempFile.parentFile
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                throw IOException("Could not create temporary download directory: ${parent.path}")
            }
            FileOutputStream(tempFile, false).use { merged ->
                segmentFiles.forEach { segment ->
                    segment.inputStream().use { it.copyTo(merged, bufferSizeBytes) }
                    deleteQuietly(segment)
                }
                merged.flush()
            }
            if (tempFile.length() != totalBytes) {
                throw IOException("Segment merge length mismatch: expected $totalBytes, received ${tempFile.length()}")
            }
            true
        } catch (_: SegmentFallbackException) {
            segmentFiles.forEach(::deleteQuietly)
            deleteQuietly(tempFile)
            val current = repository.get(downloadId)
                ?: throw IllegalArgumentException("Download not found: $downloadId")
            repository.beginFreshRestart(
                id = downloadId,
                nowEpochMillis = validTimestamp(current.updatedAtEpochMillis),
                etag = validators.etag,
                lastModified = validators.lastModified,
                totalBytes = totalBytes,
            )
            false
        } catch (cancellation: CancellationException) {
            recoverSegmentArtifacts(tempFile)
            throw cancellation
        } catch (error: Throwable) {
            throw error
        }
    }

    private fun segmentFile(tempFile: File, range: TransferByteRange): File =
        File(tempFile.path + ".segment-${range.start}-${range.endInclusive}")

    /**
     * Converts only the verified contiguous prefix of interrupted segment files into the
     * ordinary part file. The existing If-Range resume path validates it on the next request.
     */
    private fun recoverSegmentArtifacts(tempFile: File): Long {
        val parent = tempFile.parentFile ?: return tempFile.length().coerceAtLeast(0L)
        val prefix = tempFile.name + ".segment-"
        val artifacts = parent.listFiles().orEmpty().mapNotNull { file ->
            if (!file.name.startsWith(prefix)) return@mapNotNull null
            val bounds = file.name.removePrefix(prefix).split('-')
            if (bounds.size != 2) return@mapNotNull null
            val start = bounds[0].toLongOrNull() ?: return@mapNotNull null
            val end = bounds[1].toLongOrNull() ?: return@mapNotNull null
            if (start < 0L || end < start) return@mapNotNull null
            Triple(start, end, file)
        }.sortedBy { it.first }
        if (artifacts.isEmpty()) return tempFile.length().coerceAtLeast(0L)
        if (tempFile.exists() && tempFile.length() > 0L) {
            artifacts.forEach { deleteQuietly(it.third) }
            return tempFile.length()
        }

        var expectedStart = 0L
        FileOutputStream(tempFile, false).use { output ->
            for ((start, end, artifact) in artifacts) {
                if (start != expectedStart) break
                val expectedLength = end - start + 1L
                val usableLength = artifact.length().coerceAtMost(expectedLength)
                artifact.inputStream().use { input ->
                    var remaining = usableLength
                    val buffer = ByteArray(bufferSizeBytes)
                    while (remaining > 0L) {
                        val read = input.read(buffer, 0, min(buffer.size.toLong(), remaining).toInt())
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        remaining -= read
                    }
                }
                expectedStart += usableLength
                if (usableLength != expectedLength) break
            }
            output.flush()
        }
        artifacts.forEach { deleteQuietly(it.third) }
        if (expectedStart == 0L) deleteQuietly(tempFile)
        return expectedStart
    }

    private suspend fun downloadSegment(
        url: String,
        range: TransferByteRange,
        totalBytes: Long,
        validators: HttpRangeResume.ResumeValidators,
        output: File,
        requestContext: ScopedRequestContext?,
        onBytesWritten: suspend (Long) -> Unit,
    ) {
        val validator = HttpRangeResume.ifRangeHeaderValue(validators)
            ?: throw SegmentFallbackException()
        val requestBuilder = Request.Builder()
            .url(url)
            .get()
            .header(HttpRangeResume.HEADER_RANGE, range.headerValue())
            .header(HttpRangeResume.HEADER_IF_RANGE, validator)
        requestContext?.let { requestBuilder.tag(ScopedRequestContext::class.java, it) }
        val request = requestBuilder.build()
        val call = okHttpClient.newCall(request)
        val cancellationHandle = currentCoroutineContext().job.invokeOnCompletion { cause ->
            if (cause is CancellationException) call.cancel()
        }
        try {
            call.execute().use { response ->
                if (response.code != HttpRangeResume.HTTP_PARTIAL_CONTENT) {
                    throw SegmentFallbackException()
                }
                val parsed = try {
                    HttpRangeResume.validateContentRange(
                        response.header(HttpRangeResume.HEADER_CONTENT_RANGE),
                        range.start,
                        totalBytes,
                    )
                } catch (_: IllegalArgumentException) {
                    throw SegmentFallbackException()
                }
                if (parsed.end != range.endInclusive) throw SegmentFallbackException()
                if (
                    HttpRangeResume.validateStoredValidators(
                        validators,
                        response.header(HttpRangeResume.HEADER_ETAG),
                        response.header(HttpRangeResume.HEADER_LAST_MODIFIED),
                    ) !is HttpRangeResume.ResumeValidation.Ok
                ) {
                    throw SegmentFallbackException()
                }
                val body = response.body ?: throw SegmentFallbackException()
                try {
                    HttpRangeResume.validateDeclaredBodyLength(range.length, body.contentLength())
                } catch (_: IllegalArgumentException) {
                    throw SegmentFallbackException()
                }
                output.parentFile?.mkdirs()
                var received = 0L
                FileOutputStream(output, false).use { stream ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(bufferSizeBytes)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read == -1) break
                            if (received + read > range.length) throw SegmentFallbackException()
                            var offset = 0
                            while (offset < read) {
                                val admitted = speedLimiter.acquire(read - offset)
                                currentCoroutineContext().ensureActive()
                                val written = min(admitted, read - offset)
                                stream.write(buffer, offset, written)
                                offset += written
                                received += written
                                onChunkRead(written)
                                onBytesWritten(written.toLong())
                            }
                        }
                        stream.flush()
                    }
                }
                if (received != range.length) throw SegmentFallbackException()
            }
        } finally {
            cancellationHandle.dispose()
        }
    }

    private suspend fun finalizeCompletedPart(
        repository: DownloadRepository,
        downloadId: String,
        tempFile: File,
        destinationFile: File,
    ) {
        finalizeWithoutOverwrite(tempFile, destinationFile)
        val downloading = repository.get(downloadId)
            ?: throw IllegalArgumentException("Download not found: $downloadId")
        val published = destinationPublisher.afterLocalFinalize(downloading, destinationFile)
        val changed = published.destinationPath != downloading.destinationPath ||
            published.destinationTreeUri != downloading.destinationTreeUri ||
            published.destinationDisplayLabel != downloading.destinationDisplayLabel ||
            published.fileName != downloading.fileName
        val source = if (changed) {
            repository.updateDestination(
                id = downloadId,
                destinationPath = published.destinationPath,
                destinationTreeUri = published.destinationTreeUri,
                destinationDisplayLabel = published.destinationDisplayLabel,
                fileName = published.fileName,
                nowEpochMillis = validTimestamp(downloading.updatedAtEpochMillis),
            )
        } else {
            downloading
        }
        repository.transition(
            id = downloadId,
            to = DownloadState.COMPLETED,
            nowEpochMillis = validTimestamp(source.updatedAtEpochMillis),
        )
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
        persistPausedIfRequested(repository, downloadId, tempFile, pauseRequested = { true })
    }

    private fun shouldPublishProgress(
        totalBytesRead: Long,
        lastReportedBytes: Long,
        lastReportedAtEpochMillis: Long,
        expectedTotal: Long?,
    ): Boolean {
        val unread = totalBytesRead - lastReportedBytes
        if (unread <= 0L) return false
        if (expectedTotal != null && totalBytesRead > expectedTotal) return false
        val elapsedMillis = clock.currentTimeMillis() - lastReportedAtEpochMillis
        return unread >= progressUpdateIntervalBytes ||
            elapsedMillis >= DEFAULT_PROGRESS_UPDATE_INTERVAL_MILLIS
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
        pauseCause: () -> DownloadPauseCause? = { null },
    ) {
        if (!pauseRequested()) return
        withContext(NonCancellable) {
            val fileLength = if (tempFile.exists()) tempFile.length().coerceAtLeast(0L) else 0L
            val current = repository.get(downloadId) ?: return@withContext
            repository.pauseAtExactOffset(
                id = downloadId,
                fileLengthBytes = fileLength,
                nowEpochMillis = validTimestamp(current.updatedAtEpochMillis),
                pauseCause = pauseCause(),
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

    private fun queryLocalCapacity(path: File): StorageCapacity {
        return try {
            storageCapacity.queryLocalPath(path)
        } catch (_: Exception) {
            StorageCapacity.Unknown
        }
    }

    private fun queryTreeCapacity(treeUri: String?): StorageCapacity {
        if (treeUri.isNullOrBlank()) return StorageCapacity.Unknown
        return try {
            storageCapacity.queryTree(treeUri)
        } catch (_: Exception) {
            StorageCapacity.Unknown
        }
    }

    private fun transferFailureMessage(error: Throwable, fallback: String): String {
        val detail = error.message?.takeIf { it.isNotBlank() }
        return when (error) {
            is SSLException -> {
                val typeName = error.javaClass.simpleName
                if (detail == null) typeName else "$typeName: $detail"
            }
            is IOException -> detail ?: "Network I/O failure"
            else -> detail ?: fallback
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
            error = ErrorReportSanitizer.sanitize(error).ifBlank { "Download failed" },
        )
    }

    private fun validTimestamp(previousTimestamp: Long): Long =
        max(clock.currentTimeMillis(), previousTimestamp)

    private fun finalizeWithoutOverwrite(tempFile: File, destinationFile: File) {
        require(tempFile.exists()) { "Temporary download file is missing" }
        if (destinationFile.exists()) {
            throw IOException("Destination already exists")
        }

        val parent = destinationFile.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw IOException("Could not create destination directory")
        }
        if (parent != null && !parent.isDirectory) {
            throw IOException("Destination parent is not a directory")
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
        const val DEFAULT_PROGRESS_UPDATE_INTERVAL_MILLIS = 1_000L
    }
}

private class SegmentFallbackException : IOException()
