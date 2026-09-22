package com.espitman.sdm.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class DownloadProgressAlignmentTest {
    @Test
    fun lowersPersistedOffsetToAShorterPartial() {
        val connecting = DownloadStateMachine.transition(
            Download(
                id = "align",
                url = "https://example.com/file.bin",
                fileName = "file.bin",
                etag = "\"file-v1\"",
                destinationPath = "/tmp/file.bin",
                totalBytes = 8_192L,
                downloadedBytes = 4_096L,
                createdAtEpochMillis = 1_000L,
            ),
            DownloadState.CONNECTING,
            2_000L,
        )

        val aligned = DownloadProgressAlignment.apply(connecting, fileLengthBytes = 128L, nowEpochMillis = 1_500L)
        assertEquals(128L, aligned.downloadedBytes)
        assertEquals(DownloadState.CONNECTING, aligned.state)
        assertEquals(2_000L, aligned.updatedAtEpochMillis)
        assertEquals("\"file-v1\"", aligned.etag)
        assertEquals(connecting.totalBytes, aligned.totalBytes)
    }

    @Test
    fun missingPartialAlignsToZeroWithoutChangingValidators() {
        val queued = Download(
            id = "missing-part",
            url = "https://example.com/file.bin",
            fileName = "file.bin",
            etag = "\"file-v1\"",
            lastModified = "Fri, 23 Oct 2015 07:28:00 GMT",
            destinationPath = "/tmp/file.bin",
            totalBytes = 2_048L,
            downloadedBytes = 1_024L,
            createdAtEpochMillis = 1_000L,
        )

        val aligned = DownloadProgressAlignment.apply(queued, fileLengthBytes = 0L, nowEpochMillis = 3_000L)
        assertEquals(0L, aligned.downloadedBytes)
        assertEquals("\"file-v1\"", aligned.etag)
        assertEquals("Fri, 23 Oct 2015 07:28:00 GMT", aligned.lastModified)
        assertEquals(3_000L, aligned.updatedAtEpochMillis)
    }

    @Test
    fun doesNotIncreaseProgressOrMutateCompletedRecords() {
        val queued = Download(
            id = "same",
            url = "https://example.com/file.bin",
            fileName = "file.bin",
            downloadedBytes = 40L,
            createdAtEpochMillis = 1_000L,
        )
        assertSame(queued, DownloadProgressAlignment.apply(queued, fileLengthBytes = 40L, nowEpochMillis = 2_000L))
        assertSame(queued, DownloadProgressAlignment.apply(queued, fileLengthBytes = 80L, nowEpochMillis = 2_000L))

        val completed = Download(
            id = "done",
            url = "https://example.com/done.bin",
            fileName = "done.bin",
            destinationPath = "/tmp/done.bin",
            totalBytes = 8L,
            downloadedBytes = 8L,
            state = DownloadState.COMPLETED,
            createdAtEpochMillis = 1_000L,
            completedAtEpochMillis = 2_000L,
        )
        assertSame(completed, DownloadProgressAlignment.apply(completed, fileLengthBytes = 0L, nowEpochMillis = 3_000L))
    }

    @Test
    fun rejectsNegativeOffsets() {
        val queued = Download(
            id = "neg",
            url = "https://example.com/file.bin",
            fileName = "file.bin",
            createdAtEpochMillis = 1_000L,
        )
        assertThrows(IllegalArgumentException::class.java) {
            DownloadProgressAlignment.apply(queued, fileLengthBytes = -1L, nowEpochMillis = 2_000L)
        }
    }
}
