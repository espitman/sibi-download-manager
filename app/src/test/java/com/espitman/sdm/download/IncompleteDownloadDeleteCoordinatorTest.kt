package com.espitman.sdm.download

import com.espitman.sdm.domain.Download
import com.espitman.sdm.domain.DownloadState
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IncompleteDownloadDeleteCoordinatorTest {
    @Test
    fun cancelledDownloadDeletesOnlyItsPartialFilesAndRecord() = runBlocking {
        val directory = Files.createTempDirectory("sdm-delete-part").toFile()
        try {
            val destination = directory.resolve("sample.bin")
            val otherFile = directory.resolve("other.bin").apply { writeText("keep") }
            val part = DownloadPartFile.forDestination(destination).apply { writeText("partial") }
            val restart = DownloadPartFile.restartForDestination(destination).apply { writeText("restart") }
            val repository = CompletedFileDeleteCoordinatorTest.FakeRepository(
                record(destination.absolutePath, DownloadState.CANCELLED),
            )

            assertTrue(IncompleteDownloadDeleteCoordinator.delete("sample", repository) { error("already cancelled") })
            assertFalse(part.exists())
            assertFalse(restart.exists())
            assertTrue(otherFile.exists())
            assertNull(repository.get("sample"))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun activeDownloadIsCancelledBeforeItsPartialIsDeleted() = runBlocking {
        val directory = Files.createTempDirectory("sdm-cancel-delete").toFile()
        try {
            val destination = directory.resolve("sample.bin")
            val part = DownloadPartFile.forDestination(destination).apply { writeText("partial") }
            val active = record(destination.absolutePath, DownloadState.DOWNLOADING)
            val repository = CompletedFileDeleteCoordinatorTest.FakeRepository(active)
            var requested = false

            val deleted = IncompleteDownloadDeleteCoordinator.delete("sample", repository) {
                requested = true
                runBlocking { repository.insert(active.copy(state = DownloadState.CANCELLED)) }
            }

            assertTrue(requested)
            assertTrue(deleted)
            assertFalse(part.exists())
            assertNull(repository.get("sample"))
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun record(path: String, state: DownloadState) = Download(
        id = "sample",
        url = "https://example.com/sample.bin",
        fileName = "sample.bin",
        destinationPath = path,
        state = state,
        createdAtEpochMillis = 1L,
    )
}
