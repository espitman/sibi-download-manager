package com.espitman.sdm.download

import com.espitman.sdm.domain.Download
import com.espitman.sdm.domain.DownloadState
import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class DownloadChecksumVerifierTest {

    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("checksum-verifier-test").toFile()
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun noReferenceWhenChecksumMissingEvenIfEtagLooksLikeSha256() = runBlocking {
        val file = File(tempDir, "etag.bin")
        file.writeBytes(byteArrayOf(1, 2, 3, 4))
        var opened = false
        val result = DownloadChecksumVerifier.verify(
            download = record(
                file = file,
                state = DownloadState.COMPLETED,
                referenceSha256 = null,
                etag = "\"${sha256Hex(file.readBytes())}\"",
            ),
            openStream = {
                opened = true
                FileInputStream(it)
            },
        )
        assertEquals(ChecksumVerificationResult.NoReference, result)
        assertEquals("No reference checksum available", result.message)
        assertTrue(!opened)
    }

    @Test
    fun notCompletedDoesNotReadDestinationOrPartFile() = runBlocking {
        val destination = File(tempDir, "movie.mkv")
        destination.writeBytes(byteArrayOf(9, 8, 7))
        val part = DownloadPartFile.forDestination(destination)
        part.writeBytes(byteArrayOf(1, 1, 1))
        var openedPath: String? = null
        val result = DownloadChecksumVerifier.verify(
            download = record(
                file = destination,
                state = DownloadState.PAUSED,
                referenceSha256 = sha256Hex(destination.readBytes()),
            ),
            openStream = {
                openedPath = it.path
                FileInputStream(it)
            },
        )
        assertEquals(ChecksumVerificationResult.NotCompleted, result)
        assertEquals("Complete the download before verification", result.message)
        assertEquals(null, openedPath)
    }

    @Test
    fun missingFileWhenCompletedDestinationIsAbsent() = runBlocking {
        val missing = File(tempDir, "gone.bin")
        val result = DownloadChecksumVerifier.verify(
            record(
                file = missing,
                state = DownloadState.COMPLETED,
                referenceSha256 = EMPTY_SHA256_HEX,
            ),
        )
        assertEquals(ChecksumVerificationResult.MissingFile, result)
        assertEquals("Downloaded file is missing", result.message)
    }

    @Test
    fun matchComparesNormalizedHexOfCompletedDestination() = runBlocking {
        val file = File(tempDir, "ok.bin")
        file.writeBytes("sdm-checksum".toByteArray())
        val result = DownloadChecksumVerifier.verify(
            record(
                file = file,
                state = DownloadState.COMPLETED,
                referenceSha256 = sha256Hex(file.readBytes()),
            ),
        )
        assertEquals(ChecksumVerificationResult.Match, result)
        assertEquals("Checksum verified", result.message)
    }

    @Test
    fun mismatchWhenFileHashDiffersFromReference() = runBlocking {
        val file = File(tempDir, "changed.bin")
        file.writeBytes("original".toByteArray())
        val result = DownloadChecksumVerifier.verify(
            record(
                file = file,
                state = DownloadState.COMPLETED,
                referenceSha256 = EMPTY_SHA256_HEX,
            ),
        )
        assertEquals(ChecksumVerificationResult.Mismatch, result)
        assertEquals("Checksum mismatch", result.message)
    }

    @Test
    fun failureWhenStreamingTheCompletedFileThrows() = runBlocking {
        val file = File(tempDir, "unreadable.bin")
        file.writeBytes(byteArrayOf(0, 1))
        val result = DownloadChecksumVerifier.verify(
            download = record(
                file = file,
                state = DownloadState.COMPLETED,
                referenceSha256 = sha256Hex(file.readBytes()),
            ),
            openStream = { throw IOException("read failed") },
        )
        assertEquals(ChecksumVerificationResult.Failure, result)
        assertEquals("Could not verify checksum", result.message)
    }

    @Test
    fun streamsLargeFileWithBoundedBuffer() = runBlocking {
        val file = File(tempDir, "large.bin")
        val payload = ByteArray(2 * 1024 * 1024) { index -> (index % 251).toByte() }
        file.writeBytes(payload)
        var maxReadRequest = 0
        val bufferSize = DownloadChecksumVerifier.DEFAULT_BUFFER_SIZE_BYTES
        val result = DownloadChecksumVerifier.verify(
            download = record(
                file = file,
                state = DownloadState.COMPLETED,
                referenceSha256 = sha256Hex(payload),
            ),
            bufferSizeBytes = bufferSize,
            openStream = { destination ->
                object : FilterInputStream(FileInputStream(destination)) {
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        maxReadRequest = maxOf(maxReadRequest, len)
                        return super.read(b, off, len)
                    }
                }
            },
        )
        assertEquals(ChecksumVerificationResult.Match, result)
        assertTrue(payload.size > bufferSize)
        assertTrue(maxReadRequest in 1..bufferSize)
    }

    @Test
    fun cancellationPropagatesDuringStreaming() = runBlocking {
        val file = File(tempDir, "slow.bin")
        file.writeBytes(ByteArray(64 * 1024) { 3 })
        try {
            DownloadChecksumVerifier.verify(
                download = record(
                    file = file,
                    state = DownloadState.COMPLETED,
                    referenceSha256 = sha256Hex(file.readBytes()),
                ),
                bufferSizeBytes = 32,
                openStream = { destination ->
                    object : FilterInputStream(FileInputStream(destination)) {
                        private var reads = 0
                        override fun read(b: ByteArray, off: Int, len: Int): Int {
                            if (reads++ > 0) {
                                throw CancellationException("cancelled during checksum stream")
                            }
                            return super.read(b, off, len)
                        }
                    }
                },
            )
            fail("Expected CancellationException")
        } catch (cancelled: CancellationException) {
            assertEquals("cancelled during checksum stream", cancelled.message)
        }
    }

    private fun record(
        file: File,
        state: DownloadState,
        referenceSha256: String?,
        etag: String? = "\"not-a-checksum\"",
    ) = Download(
        id = "checksum-${file.name}",
        url = "https://example.com/${file.name}",
        fileName = file.name,
        etag = etag,
        destinationPath = file.absolutePath,
        totalBytes = if (state == DownloadState.COMPLETED) file.length().coerceAtLeast(0L) else null,
        downloadedBytes = if (state == DownloadState.COMPLETED) file.length().coerceAtLeast(0L) else 0L,
        state = state,
        createdAtEpochMillis = 1_000L,
        updatedAtEpochMillis = 2_000L,
        completedAtEpochMillis = if (state == DownloadState.COMPLETED) 2_000L else null,
        referenceSha256 = referenceSha256,
    )

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        const val EMPTY_SHA256_HEX =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    }
}
