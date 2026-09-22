package com.espitman.sdm.storage

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CompletedDownloadFileAccessTest {
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("completed-file-access").toFile()
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun localReadableFileIsAccepted() {
        val file = File(tempDir, "done.bin").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        assertTrue(FilesystemCompletedFileProbe.isReadableDocument(file.absolutePath))
        assertTrue(isReadableLocalFile(file.absolutePath))
    }

    @Test
    fun missingBlankAndDirectoryDestinationsAreRejected() {
        val missing = File(tempDir, "gone.bin")
        val directory = File(tempDir, "dir").apply { mkdir() }
        assertFalse(isReadableLocalFile(""))
        assertFalse(isReadableLocalFile("   "))
        assertFalse(FilesystemCompletedFileProbe.isReadableDocument(missing.absolutePath))
        assertFalse(FilesystemCompletedFileProbe.isReadableDocument(directory.absolutePath))
    }

    @Test
    fun contentUrisAreNotTreatedAsFilesystemPaths() {
        val uri = "content://com.android.externalstorage.documents/document/primary%3Adone.bin"
        assertTrue(DownloadDestinationRef.isContentUri(uri))
        assertFalse(FilesystemCompletedFileProbe.isReadableDocument(uri))
    }

    @Test
    fun unreadableLocalFileIsRejected() {
        val file = File(tempDir, "secret.bin").apply { writeBytes(byteArrayOf(9)) }
        val hidden = file.setReadable(false)
        if (!hidden || file.canRead()) return
        assertFalse(FilesystemCompletedFileProbe.isReadableDocument(file.absolutePath))
        file.setReadable(true)
    }
}
