package com.espitman.sdm.data

import com.espitman.sdm.domain.Download
import com.espitman.sdm.domain.DownloadState
import com.espitman.sdm.storage.CompletedFileReconciliationTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DownloadRepositoryAlignContractTest {
    @Test
    fun defaultAlignFailsClosedEvenWhenOffsetsAlreadyMatch() {
        val repo = CompletedFileReconciliationTest.FakeRepository(
            listOf(
                Download(
                    id = "keep",
                    url = "https://example.com/keep.bin",
                    fileName = "keep.bin",
                    destinationPath = "/tmp/keep.bin",
                    totalBytes = 4L,
                    downloadedBytes = 4L,
                    state = DownloadState.COMPLETED,
                    createdAtEpochMillis = 1_000L,
                    completedAtEpochMillis = 1_000L,
                ),
            ),
        )
        val thrown = assertThrows(IllegalStateException::class.java) {
            runBlocking { repo.alignDownloadedBytes("keep", 4L, 2_000L) }
        }
        assertEquals("alignDownloadedBytes is not implemented", thrown.message)
    }
}
