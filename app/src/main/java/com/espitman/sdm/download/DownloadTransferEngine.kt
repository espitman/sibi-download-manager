package com.espitman.sdm.download

import com.espitman.sdm.data.DownloadRepository
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

        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        val call = okHttpClient.newCall(request)
        val cancellationHandle = coroutineContext.job.invokeOnCompletion { cause ->
            if (cause is CancellationException) call.cancel()
        }
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    val statusCode = response.code
                    val statusMessage = response.message.ifBlank { "HTTP $statusCode error" }
                    if (tempFile.exists() && tempFile.length() == 0L) {
                        try { tempFile.delete() } catch (_: Throwable) {}
                    }
                    reportFailure(repository, downloadId, "HTTP $statusCode: $statusMessage")
                    return@withContext
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

                val tempParent = tempFile.parentFile
                if (tempParent != null && !tempParent.exists() && !tempParent.mkdirs()) {
                    throw IOException("Could not create temporary download directory: ${tempParent.path}")
                }

                var totalBytesRead = 0L
                var lastReportedBytes = 0L

                FileOutputStream(tempFile, false).use { fileOutputStream ->
                    body.byteStream().use { inputStream ->
                        val buffer = ByteArray(bufferSizeBytes)

                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val bytesRead = inputStream.read(buffer)
                            if (bytesRead == -1) break

                            onChunkRead(bytesRead)

                            fileOutputStream.write(buffer, 0, bytesRead)
                            totalBytesRead += bytesRead

                            if (
                                totalBytesRead - lastReportedBytes >= progressUpdateIntervalBytes &&
                                (existingDownload.totalBytes == null || totalBytesRead <= existingDownload.totalBytes)
                            ) {
                                fileOutputStream.flush()
                                updateProgress(repository, downloadId, totalBytesRead)
                                lastReportedBytes = totalBytesRead
                            }
                        }

                        fileOutputStream.flush()
                    }
                }

                currentCoroutineContext().ensureActive()

                val knownTotal = existingDownload.totalBytes
                if (knownTotal != null && totalBytesRead != knownTotal) {
                    if (totalBytesRead != lastReportedBytes && totalBytesRead <= knownTotal) {
                        updateProgress(repository, downloadId, totalBytesRead)
                    }
                    reportFailure(
                        repository,
                        downloadId,
                        "Downloaded byte count mismatch: expected $knownTotal, received $totalBytesRead",
                    )
                    return@withContext
                }

                // Once EOF and length validation succeed, finish the file/state commit together.
                // Cancellation remains prompt while network I/O is active, but cannot leave a
                // finalized file stuck in DOWNLOADING between the move and state transition.
                withContext(NonCancellable) {
                    if (totalBytesRead != lastReportedBytes) {
                        updateProgress(repository, downloadId, totalBytesRead)
                    }
                    finalizeWithoutOverwrite(tempFile, destinationFile)
                    val downloadingDownload = repository.get(downloadId)
                        ?: throw IllegalArgumentException("Download not found: $downloadId")
                    repository.transition(
                        id = downloadId,
                        to = DownloadState.COMPLETED,
                        nowEpochMillis = validTimestamp(downloadingDownload.updatedAtEpochMillis),
                    )
                }
            }
        } catch (cancellation: CancellationException) {
            call.cancel()
            persistPausedIfRequested(repository, downloadId, tempFile, pauseRequested)
            throw cancellation
        } catch (e: Throwable) {
            try {
                currentCoroutineContext().ensureActive()
            } catch (cancellation: CancellationException) {
                persistPausedIfRequested(repository, downloadId, tempFile, pauseRequested)
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
            persistPausedIfRequested(repository, downloadId, tempFile, pauseRequested)
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
