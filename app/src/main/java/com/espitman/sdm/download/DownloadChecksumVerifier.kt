package com.espitman.sdm.download

import com.espitman.sdm.domain.Download
import com.espitman.sdm.domain.DownloadState
import com.espitman.sdm.network.ReferenceSha256Parser
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

sealed interface ChecksumVerificationResult {
    val message: String

    data object NoReference : ChecksumVerificationResult {
        override val message: String = "No reference checksum available"
    }

    data object NotCompleted : ChecksumVerificationResult {
        override val message: String = "Complete the download before verification"
    }

    data object MissingFile : ChecksumVerificationResult {
        override val message: String = "Downloaded file is missing"
    }

    data object Match : ChecksumVerificationResult {
        override val message: String = "Checksum verified"
    }

    data object Mismatch : ChecksumVerificationResult {
        override val message: String = "Checksum mismatch"
    }

    data object Failure : ChecksumVerificationResult {
        override val message: String = "Could not verify checksum"
    }
}

object DownloadChecksumVerifier {
    const val DEFAULT_BUFFER_SIZE_BYTES = 8 * 1024

    suspend fun verify(
        download: Download,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        bufferSizeBytes: Int = DEFAULT_BUFFER_SIZE_BYTES,
        openStream: (File) -> InputStream = { FileInputStream(it) },
    ): ChecksumVerificationResult = withContext(ioDispatcher) {
        require(bufferSizeBytes > 0) { "Buffer size must be greater than 0: $bufferSizeBytes" }
        val reference = ReferenceSha256Parser.parseHexHeader(download.referenceSha256)
            ?: return@withContext ChecksumVerificationResult.NoReference
        if (download.state != DownloadState.COMPLETED) {
            return@withContext ChecksumVerificationResult.NotCompleted
        }
        val path = download.destinationPath
        if (path.isNullOrBlank()) {
            return@withContext ChecksumVerificationResult.MissingFile
        }
        val file = File(path)
        if (!file.isFile) {
            return@withContext ChecksumVerificationResult.MissingFile
        }
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(bufferSizeBytes)
            openStream(file).use { input ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer, 0, buffer.size)
                    if (read < 0) break
                    if (read == 0) continue
                    digest.update(buffer, 0, read)
                }
            }
            currentCoroutineContext().ensureActive()
            val actual = digest.digest().toHexLower()
            if (actual == reference) {
                ChecksumVerificationResult.Match
            } else {
                ChecksumVerificationResult.Mismatch
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            ChecksumVerificationResult.Failure
        }
    }
}

private fun ByteArray.toHexLower(): String {
    val out = CharArray(size * 2)
    for (index in indices) {
        val value = this[index].toInt() and 0xFF
        out[index * 2] = HEX_DIGITS[value ushr 4]
        out[index * 2 + 1] = HEX_DIGITS[value and 0x0F]
    }
    return String(out)
}

private val HEX_DIGITS = charArrayOf(
    '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f',
)
