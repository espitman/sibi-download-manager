package com.espitman.sdm.storage

import com.espitman.sdm.domain.Download
import com.espitman.sdm.domain.DownloadState
import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SafDownloadDestinationPublisherTest {
    private val treeUri = "content://com.android.externalstorage.documents/tree/primary%3ADownload"
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("saf-publisher").toFile()
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun keepLocalLeavesAppPrivateDestinationsUnchanged() {
        val local = File(tempDir, "file.bin").apply { writeText("abc") }
        val download = record(destinationPath = local.absolutePath)
        val published = DownloadDestinationPublisher.KeepLocal.afterLocalFinalize(download, local)
        assertEquals(local.absolutePath, published.destinationPath)
        assertNull(published.destinationTreeUri)
        assertEquals("file.bin", published.fileName)
        assertTrue(local.exists())
    }

    @Test
    fun writableTreeReceivesTheCompletedFile() {
        val stagingDir = File(tempDir, SaveLocationDestinationAllocator.STAGING_DIRECTORY_NAME).apply { mkdirs() }
        val local = File(stagingDir, "clip.bin").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val store = SaveLocationStore(InMemoryPreferences())
        val grants = FakeTreeUriGrantStore()
        val trees = FakeUserTreeAccess()
        store.persistUserTree(treeUri, "Download")
        grants.granted.add(treeUri)
        trees.inspections[treeUri] = UserTreeInspection(UserTreeState.Writable, "Download", setOf("clip.bin"))
        val publisher = SafDownloadDestinationPublisher(
            trees = trees,
            coordinator = SaveLocationCoordinator(store, grants, trees),
            appSpecificDirectory = { tempDir },
        )
        val published = publisher.afterLocalFinalize(
            record(
                destinationPath = local.absolutePath,
                destinationTreeUri = treeUri,
                destinationDisplayLabel = "Download",
                fileName = "clip.bin",
            ),
            local,
        )
        assertEquals("content://created/clip (1).bin", published.destinationPath)
        assertEquals(treeUri, published.destinationTreeUri)
        assertEquals("Download", published.destinationDisplayLabel)
        assertEquals("clip (1).bin", published.fileName)
        assertEquals(listOf("content://created/clip (1).bin" to local), trees.written)
        assertFalse(local.exists())
        assertEquals(treeUri, store.read().treeUri)
    }

    @Test
    fun selectedProviderDirectoryIsUsedForInspectionAndPublication() {
        val local = File(tempDir, "selected.bin").apply { writeText("selected bytes") }
        val parent = "content://provider/tree/root/document/child"
        val delegate = FakeUserTreeAccess()
        var inspected: String? = null
        var created: String? = null
        val trees = object : UserTreeAccess by delegate {
            override fun inspectDirectory(treeUri: String, parentDocumentUri: String?): UserTreeInspection {
                inspected = parentDocumentUri
                return UserTreeInspection(UserTreeState.Writable)
            }
            override fun createFileInDirectory(treeUri: String, parentDocumentUri: String?, mimeType: String, displayName: String): CreatedTreeDocument {
                created = parentDocumentUri
                return delegate.createFile(treeUri, mimeType, displayName)
            }
        }
        val store = SaveLocationStore(InMemoryPreferences())
        val publisher = SafDownloadDestinationPublisher(trees,
            SaveLocationCoordinator(store, FakeTreeUriGrantStore(), trees), { tempDir })
        publisher.afterLocalFinalize(record(destinationPath = local.path, destinationTreeUri = treeUri)
            .copy(destinationParentDocumentUri = parent), local)
        assertEquals(parent, inspected)
        assertEquals(parent, created)
        assertFalse(local.exists())
    }

    @Test
    fun unavailableExplicitDirectoryRetainsBytesInsteadOfPublishingElsewhere() {
        val local = File(tempDir, "held.bin").apply { writeText("retained") }
        val trees = FakeUserTreeAccess()
        val publisher = SafDownloadDestinationPublisher(trees,
            SaveLocationCoordinator(SaveLocationStore(InMemoryPreferences()), FakeTreeUriGrantStore(), trees), { tempDir })
        var failed = false
        try {
            publisher.afterLocalFinalize(record(destinationPath = local.path, destinationTreeUri = treeUri)
                .copy(destinationParentDocumentUri = "content://provider/tree/root/document/child"), local)
        } catch (_: IOException) { failed = true }
        assertTrue(failed)
        assertEquals("retained", local.readText())
        assertTrue(trees.written.isEmpty())
    }

    @Test
    fun missingTreePromotesTheLocalFileAndClearsTheMatchingSetting() {
        val stagingDir = File(tempDir, SaveLocationDestinationAllocator.STAGING_DIRECTORY_NAME).apply { mkdirs() }
        val local = File(stagingDir, "keep.bin").apply { writeText("kept") }
        val store = SaveLocationStore(InMemoryPreferences())
        val grants = FakeTreeUriGrantStore()
        val trees = FakeUserTreeAccess()
        store.persistUserTree(treeUri, "Download")
        grants.granted.add(treeUri)
        trees.inspections[treeUri] = UserTreeInspection(UserTreeState.Missing)
        val publisher = SafDownloadDestinationPublisher(
            trees = trees,
            coordinator = SaveLocationCoordinator(store, grants, trees),
            appSpecificDirectory = { tempDir },
        )
        val published = publisher.afterLocalFinalize(
            record(
                destinationPath = local.absolutePath,
                destinationTreeUri = treeUri,
                destinationDisplayLabel = "Download",
                fileName = "keep.bin",
            ),
            local,
        )
        val promoted = File(tempDir, "keep.bin")
        assertEquals(promoted.absolutePath, published.destinationPath)
        assertNull(published.destinationTreeUri)
        assertNull(published.destinationDisplayLabel)
        assertEquals("keep.bin", published.fileName)
        assertTrue(promoted.isFile)
        assertEquals("kept", promoted.readText())
        assertFalse(local.exists())
        assertNull(store.read().treeUri)
    }

    @Test
    fun revokedTreePromotesCompletedBytesToAppSpecificStorage() {
        val stagingDir = File(tempDir, SaveLocationDestinationAllocator.STAGING_DIRECTORY_NAME).apply { mkdirs() }
        val local = File(stagingDir, "revoked.bin").apply { writeText("completed payload") }
        val store = SaveLocationStore(InMemoryPreferences())
        val grants = FakeTreeUriGrantStore()
        val trees = FakeUserTreeAccess()
        store.persistUserTree(treeUri, "Download")
        grants.granted.add(treeUri)
        trees.inspections[treeUri] = UserTreeInspection(UserTreeState.PermissionRevoked, "Download")
        val publisher = SafDownloadDestinationPublisher(
            trees = trees,
            coordinator = SaveLocationCoordinator(store, grants, trees),
            appSpecificDirectory = { tempDir },
        )

        val published = publisher.afterLocalFinalize(
            record(
                destinationPath = local.absolutePath,
                destinationTreeUri = treeUri,
                destinationDisplayLabel = "Download",
                fileName = "revoked.bin",
            ),
            local,
        )

        val promoted = File(tempDir, "revoked.bin")
        assertEquals(promoted.absolutePath, published.destinationPath)
        assertNull(published.destinationTreeUri)
        assertNull(published.destinationDisplayLabel)
        assertEquals("completed payload", promoted.readText())
        assertFalse(local.exists())
        assertNull(store.read().treeUri)
        assertFalse(grants.granted.contains(treeUri))
    }

    @Test
    fun writeFailureDeletesTheCreatedDocumentAndFallsBack() {
        val stagingDir = File(tempDir, SaveLocationDestinationAllocator.STAGING_DIRECTORY_NAME).apply { mkdirs() }
        val local = File(stagingDir, "fail.bin").apply { writeText("data") }
        val store = SaveLocationStore(InMemoryPreferences())
        val grants = FakeTreeUriGrantStore()
        val trees = FakeUserTreeAccess()
        store.persistUserTree(treeUri, "Download")
        grants.granted.add(treeUri)
        trees.inspections[treeUri] = UserTreeInspection(UserTreeState.Writable, "Download")
        trees.writeError = IOException("broken")
        val publisher = SafDownloadDestinationPublisher(
            trees = trees,
            coordinator = SaveLocationCoordinator(store, grants, trees),
            appSpecificDirectory = { tempDir },
        )
        val published = publisher.afterLocalFinalize(
            record(
                destinationPath = local.absolutePath,
                destinationTreeUri = treeUri,
                fileName = "fail.bin",
            ),
            local,
        )
        assertEquals(File(tempDir, "fail.bin").absolutePath, published.destinationPath)
        assertNull(published.destinationTreeUri)
        assertEquals(listOf("content://created/fail.bin"), trees.deleted)
        assertTrue(File(tempDir, "fail.bin").isFile)
    }

    @Test
    fun aDifferentActiveTreeIsNotClearedWhenPublishFails() {
        val otherTree = "content://com.android.externalstorage.documents/tree/primary%3AMovies"
        val stagingDir = File(tempDir, SaveLocationDestinationAllocator.STAGING_DIRECTORY_NAME).apply { mkdirs() }
        val local = File(stagingDir, "old.bin").apply { writeText("old") }
        val store = SaveLocationStore(InMemoryPreferences())
        val grants = FakeTreeUriGrantStore()
        val trees = FakeUserTreeAccess()
        store.persistUserTree(otherTree, "Movies")
        grants.granted.add(otherTree)
        trees.inspections[otherTree] = UserTreeInspection(UserTreeState.Writable, "Movies")
        trees.inspections[treeUri] = UserTreeInspection(UserTreeState.Missing)
        val publisher = SafDownloadDestinationPublisher(
            trees = trees,
            coordinator = SaveLocationCoordinator(store, grants, trees),
            appSpecificDirectory = { tempDir },
        )
        publisher.afterLocalFinalize(
            record(
                destinationPath = local.absolutePath,
                destinationTreeUri = treeUri,
                fileName = "old.bin",
            ),
            local,
        )
        assertEquals(otherTree, store.read().treeUri)
        assertEquals("Movies", store.read().displayLabel)
    }

    private fun record(
        destinationPath: String,
        destinationTreeUri: String? = null,
        destinationDisplayLabel: String? = null,
        fileName: String = "file.bin",
    ) = Download(
        id = "dl",
        url = "https://example.com/$fileName",
        fileName = fileName,
        destinationPath = destinationPath,
        destinationTreeUri = destinationTreeUri,
        destinationDisplayLabel = destinationDisplayLabel,
        state = DownloadState.DOWNLOADING,
        createdAtEpochMillis = 1,
        updatedAtEpochMillis = 1,
    )
}
