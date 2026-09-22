package com.espitman.sdm.storage

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class CompletedDestinationPresenceTest {
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("completed-presence").toFile()
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun localFileIsReadableMissingOrAccessUnavailable() {
        val readable = File(tempDir, "done.bin").apply { writeBytes(byteArrayOf(1)) }
        val missing = File(tempDir, "gone.bin")
        val directory = File(tempDir, "dir").apply { mkdir() }

        assertEquals(
            CompletedDestinationPresence.Readable,
            CompletedDestinationAccess.classify(readable.absolutePath),
        )
        assertEquals(
            CompletedDestinationPresence.Missing,
            CompletedDestinationAccess.classify(missing.absolutePath),
        )
        assertEquals(
            CompletedDestinationPresence.Missing,
            CompletedDestinationAccess.classify(directory.absolutePath),
        )
        assertEquals(
            CompletedDestinationPresence.Missing,
            CompletedDestinationAccess.classify(null),
        )
        assertEquals(
            CompletedDestinationPresence.Missing,
            CompletedDestinationAccess.classify("   "),
        )

        val locked = File(tempDir, "secret.bin").apply { writeBytes(byteArrayOf(9)) }
        if (locked.setReadable(false) && !locked.canRead()) {
            assertEquals(
                CompletedDestinationPresence.AccessUnavailable,
                CompletedDestinationAccess.classify(locked.absolutePath),
            )
            locked.setReadable(true)
        }
    }

    @Test
    fun contentDocumentsUseStoreAndKeepRevokedDistinctFromMissing() {
        val uri = "content://com.android.externalstorage.documents/document/primary%3Adone.bin"
        val store = object : ContentDocumentStore {
            var presenceValue = CompletedDestinationPresence.Readable
            override fun presence(documentUri: String, treeUri: String?) = presenceValue
            override fun rename(documentUri: String, treeUri: String?, displayName: String) =
                ContentDocumentMutation.Success(documentUri, displayName)
            override fun delete(documentUri: String, treeUri: String?) =
                ContentDocumentMutation.Success(documentUri, "")
        }

        assertEquals(
            CompletedDestinationPresence.Readable,
            CompletedDestinationAccess.classify(uri, "content://tree/root", store),
        )
        store.presenceValue = CompletedDestinationPresence.Missing
        assertEquals(
            CompletedDestinationPresence.Missing,
            CompletedDestinationAccess.classify(uri, "content://tree/root", store),
        )
        store.presenceValue = CompletedDestinationPresence.AccessUnavailable
        assertEquals(
            CompletedDestinationPresence.AccessUnavailable,
            CompletedDestinationAccess.classify(uri, "content://tree/root", store),
        )
        assertEquals(
            CompletedDestinationPresence.AccessUnavailable,
            CompletedDestinationAccess.classify(uri, "content://tree/root", null),
        )
    }
}
