package com.espitman.sdm.storage

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SaveLocationStorageCapacityTest {
    @get:Rule val temp = TemporaryFolder()

    private val treeUri = "content://com.android.externalstorage.documents/tree/primary%3ADownload"
    private lateinit var store: SaveLocationStore
    private lateinit var grants: FakeTreeUriGrantStore
    private lateinit var trees: FakeUserTreeAccess
    private lateinit var coordinator: SaveLocationCoordinator
    private lateinit var downloadsDir: File
    private lateinit var probe: RecordingStorageCapacityProbe

    @Before
    fun setUp() {
        store = SaveLocationStore(InMemoryPreferences())
        grants = FakeTreeUriGrantStore()
        trees = FakeUserTreeAccess()
        coordinator = SaveLocationCoordinator(store, grants, trees)
        downloadsDir = temp.newFolder("Download")
        probe = RecordingStorageCapacityProbe()
    }

    @Test
    fun appSpecificLocationQueriesThatDirectoryNotPrimaryStorage() {
        probe.local = StorageCapacity.from(128L, 48L)
        val capacity = SaveLocationStorageCapacity(
            currentLocation = coordinator::current,
            appSpecificDirectory = { downloadsDir },
            probe = probe,
        ).queryActive()
        assertEquals(StorageCapacity.from(128L, 48L), capacity)
        assertEquals(listOf(downloadsDir), probe.localPaths)
        assertTrue(probe.treeUris.isEmpty())
        assertNull(store.read().treeUri)
    }

    @Test
    fun persistedTreeQueriesProviderCapacityWithoutRecoveringTheSetting() {
        store.persistUserTree(treeUri, "Download")
        probe.trees[treeUri] = StorageCapacity.from(totalBytes = null, availableBytes = 12L)
        val capacity = SaveLocationStorageCapacity(
            currentLocation = coordinator::current,
            appSpecificDirectory = { downloadsDir },
            probe = probe,
        ).queryActive()
        assertEquals(12L, capacity.availableBytes)
        assertNull(capacity.totalBytes)
        assertNull(capacity.usedBytes)
        assertEquals(listOf(treeUri), probe.treeUris)
        assertTrue(probe.localPaths.isEmpty())
        assertEquals(treeUri, store.read().treeUri)
        assertEquals("Download", store.read().displayLabel)
    }

    @Test
    fun unknownTreeCapacityIsNotReplacedWithAppSpecificFigures() {
        store.persistUserTree(treeUri, "Download")
        probe.local = StorageCapacity.from(999L, 1L)
        probe.trees[treeUri] = StorageCapacity.Unknown
        val capacity = SaveLocationStorageCapacity(
            currentLocation = coordinator::current,
            appSpecificDirectory = { downloadsDir },
            probe = probe,
        ).queryActive()
        assertSame(StorageCapacity.Unknown, capacity)
        assertTrue(probe.localPaths.isEmpty())
        assertEquals(treeUri, store.read().treeUri)
    }

    private class RecordingStorageCapacityProbe : StorageCapacityProbe {
        var local: StorageCapacity = StorageCapacity.Unknown
        val trees = mutableMapOf<String, StorageCapacity>()
        val localPaths = mutableListOf<File>()
        val treeUris = mutableListOf<String>()

        override fun queryLocalPath(path: File): StorageCapacity {
            localPaths.add(path)
            return local
        }

        override fun queryTree(treeUri: String): StorageCapacity {
            treeUris.add(treeUri)
            return trees[treeUri] ?: StorageCapacity.Unknown
        }
    }
}
