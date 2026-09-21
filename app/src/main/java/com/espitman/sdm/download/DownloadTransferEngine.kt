package com.espitman.sdm.download

import com.espitman.sdm.data.DownloadRepository
import com.espitman.sdm.domain.DownloadState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import okio.buffer
import okio.sink
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

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
    ) = withContext(ioDispatcher) {
        require(downloadId.isNotBlank()) { "Download ID cannot be blank" }
        require(url.isNotBlank()) { "URL cannot be blank" }

        // Transition QUEUED -> CONNECTING
        val connectingTime = clock.currentTimeMillis()
        repository.transition(
            id = downloadId,
            to = DownloadState.CONNECTING,
            nowEpochMillis = connectingTime,
        )

        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        val call = okHttpClient.newCall(request)

        var response: Response? = null
        try {
            response = call.execute()

            if (!response.isSuccessful) {
                val statusCode = response.code
                val statusMessage = response.message.ifBlank { "HTTP $statusCode error" }
                val errorMessage = "HTTP $statusCode: $statusMessage"
                repository.transition(
                    id = downloadId,
                    to = DownloadState.FAILED,
                    nowEpochMillis = clock.currentTimeMillis(),
                    error = errorMessage,
                )
                return@withContext
            }

            val body = response.body ?: run {
                repository.transition(
                    id = downloadId,
                    to = DownloadState.FAILED,
                    nowEpochMillis = clock.currentTimeMillis(),
                    error = "Response body was empty",
                )
                return@withContext
            }

            // Transition CONNECTING -> DOWNLOADING
            val downloadingTime = clock.currentTimeMillis()
            repository.transition(
                id = downloadId,
                to = DownloadState.DOWNLOADING,
                nowEpochMillis = downloadingTime,
            )

            // Ensure parent directory exists
            tempFile.parentFile?.mkdirs()

            // Stream responseBody byteStream to FileOutputStream using bounded ByteArray buffer
            var totalBytesRead = 0L
            var lastReportedBytes = 0L

            FileOutputStream(tempFile, false).use { fileOutputStream ->
                body.byteStream().use { inputStream ->
                    val buffer = ByteArray(bufferSizeBytes)

                    while (coroutineContext.isActive) {
                        val bytesRead = inputStream.read(buffer)
                        if (bytesRead == -1) {
                            break
                        }

                        onChunkRead(bytesRead)

                        fileOutputStream.write(buffer, 0, bytesRead)
                        totalBytesRead += bytesRead

                        if (totalBytesRead - lastReportedBytes >= progressUpdateIntervalBytes) {
                            fileOutputStream.flush()
                            repository.updateProgress(
                                id = downloadId,
                                downloadedBytes = totalBytesRead,
                                nowEpochMillis = clock.currentTimeMillis(),
                            )
                            lastReportedBytes = totalBytesRead
                        }
                    }

                    fileOutputStream.flush()
                }
            }

            // Check if cancelled before final progress update
            if (!coroutineContext.isActive) {
                throw CancellationException("Transfer was cancelled")
            }

            // Final progress update if bytes advanced monotonically
            if (totalBytesRead != lastReportedBytes) {
                repository.updateProgress(
                    id = downloadId,
                    downloadedBytes = totalBytesRead,
                    nowEpochMillis = clock.currentTimeMillis(),
                )
            }
        } catch (cancellation: CancellationException) {
            call.cancel()
            // On cancellation close and rethrow while preserving partial temp
            throw cancellation
        } catch (e: Throwable) {
            val safeMessage = when (e) {
                is IOException -> e.message?.takeIf { it.isNotBlank() } ?: "Network I/O failure"
                else -> e.message?.takeIf { it.isNotBlank() } ?: "Download transfer failure"
            }
            try {
                repository.transition(
                    id = downloadId,
                    to = DownloadState.FAILED,
                    nowEpochMillis = clock.currentTimeMillis(),
                    error = safeMessage,
                )
            } catch (_: Throwable) {
                // Ignore secondary failure on repository reporting
            }
        } finally {
            response?.close()
        }
    }

    companion object {
        const val DEFAULT_BUFFER_SIZE_BYTES = 8 * 1024 // 8 KB bounded buffer
        const val DEFAULT_PROGRESS_UPDATE_INTERVAL_BYTES = 64 * 1024L // 64 KB
    }
}
