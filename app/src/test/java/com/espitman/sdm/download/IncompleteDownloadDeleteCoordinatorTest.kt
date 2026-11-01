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
    fun deletionRemovesSegmentCheckpointsSoReaddingSameNameCannotResume() = runBlocking {
        val directory = Files.createTempDirectory("sdm-delete-segments").toFile()
        try {
            val destination = directory.resolve("sample.bin")
            val part = DownloadPartFile.forDestination(destination)
            val restart = DownloadPartFile.restartForDestination(destination)
            val ranges = listOf(TransferByteRange(0,49),TransferByteRange(50,99))
            val old = record(destination.path,DownloadState.CANCELLED).copy(totalBytes=100,etag="\"same-file\"")
            val repository = CompletedFileDeleteCoordinatorTest.FakeRepository(old)
            for (base in listOf(part,restart)) {
                base.writeText("partial")
                FolderSegmentCheckpoint.save(base,ranges,old)
                ranges.forEach { FolderSegmentCheckpoint.file(base,it).writeText("retained range") }
            }
            val unrelated=directory.resolve(part.name+".segment-0-49.backup").apply{writeText("keep")}
            val other=DownloadPartFile.forDestination(directory.resolve("other.bin"))
            val otherSegment=FolderSegmentCheckpoint.file(other,ranges[0]).apply{writeText("other download")}
            assertTrue(IncompleteDownloadDeleteCoordinator.delete("sample",repository){error("already cancelled")})
            for (base in listOf(part,restart)) {
                assertFalse(base.exists());assertFalse(FolderSegmentCheckpoint.marker(base).exists())
                ranges.forEach { assertFalse(FolderSegmentCheckpoint.file(base,it).exists()) }
                assertNull(FolderSegmentCheckpoint.load(base,old.copy(id="new-download")))
            }
            assertTrue(unrelated.exists());assertTrue(otherSegment.exists());assertNull(repository.get("sample"))
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun undeletableSegmentKeepsRecordAndReportsFailure() = runBlocking {
        val directory=Files.createTempDirectory("sdm-delete-blocked-segment").toFile()
        try {
            val destination=directory.resolve("sample.bin")
            val part=DownloadPartFile.forDestination(destination)
            val blocked=FolderSegmentCheckpoint.file(part,TransferByteRange(0,9)).apply{mkdir();resolve("content").writeText("keep")}
            val repository=CompletedFileDeleteCoordinatorTest.FakeRepository(record(destination.path,DownloadState.CANCELLED))
            assertFalse(IncompleteDownloadDeleteCoordinator.delete("sample",repository){error("already cancelled")})
            assertTrue(blocked.exists());assertTrue(repository.get("sample")!=null)
        } finally { directory.deleteRecursively() }
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

    @Test
    fun recordRemainsWhenPartialFileCannotBeDeleted() = runBlocking {
        val directory = Files.createTempDirectory("sdm-delete-failure").toFile()
        try {
            val destination = directory.resolve("sample.bin")
            val blockedPart = DownloadPartFile.forDestination(destination).apply {
                mkdir()
                resolve("content").writeText("partial")
            }
            val repository = CompletedFileDeleteCoordinatorTest.FakeRepository(
                record(destination.absolutePath, DownloadState.CANCELLED),
            )

            assertFalse(IncompleteDownloadDeleteCoordinator.delete("sample", repository) { error("already cancelled") })
            assertTrue(blockedPart.exists())
            assertTrue(repository.get("sample") != null)
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
