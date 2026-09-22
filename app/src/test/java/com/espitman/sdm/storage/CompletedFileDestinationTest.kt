package com.espitman.sdm.storage

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CompletedFileDestinationTest {
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("completed-file-destination").toFile()
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun contentUrisArePreservedWhenTheyHaveAnAuthority() {
        val uri = "content://com.android.externalstorage.documents/document/primary%3Aguide.pdf"
        val kind = CompletedFileDestination.classify(uri)
        assertTrue(kind is CompletedFileDestinationKind.ContentDocument)
        assertEquals(uri, (kind as CompletedFileDestinationKind.ContentDocument).uriString)
        assertTrue(CompletedFileDestination.isShareableContentUri(uri))
    }

    @Test
    fun localFilesAreClassifiedWithoutBecomingFileUris() {
        val file = File(tempDir, "done.bin").apply { writeBytes(byteArrayOf(1)) }
        val kind = CompletedFileDestination.classify(file.absolutePath)
        assertTrue(kind is CompletedFileDestinationKind.LocalFile)
        assertEquals(file.absolutePath, (kind as CompletedFileDestinationKind.LocalFile).file.absolutePath)
        assertFalse(kind.file.toURI().scheme.equals("content", ignoreCase = true))
    }

    @Test
    fun fileSchemePathsConvertToLocalFilesAndStayOffOutgoingUris() {
        val file = File(tempDir, "legacy.bin").apply { writeBytes(byteArrayOf(2)) }
        val kind = CompletedFileDestination.classify(file.toURI().toString())
        assertTrue(kind is CompletedFileDestinationKind.LocalFile)
        assertEquals(file.absolutePath, (kind as CompletedFileDestinationKind.LocalFile).file.absolutePath)
        assertFalse(CompletedFileDestination.isShareableContentUri(file.toURI().toString()))
    }

    @Test
    fun blankMalformedAndNonContentSchemesAreUnavailable() {
        assertEquals(CompletedFileDestinationKind.Unavailable, CompletedFileDestination.classify(null))
        assertEquals(CompletedFileDestinationKind.Unavailable, CompletedFileDestination.classify("  "))
        assertEquals(CompletedFileDestinationKind.Unavailable, CompletedFileDestination.classify("content:///missing-authority"))
        assertEquals(CompletedFileDestinationKind.Unavailable, CompletedFileDestination.classify("https://example.com/file.bin"))
        assertFalse(CompletedFileDestination.isShareableContentUri("file:///tmp/done.bin"))
    }
}
