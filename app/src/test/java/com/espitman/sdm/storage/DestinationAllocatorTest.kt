package com.espitman.sdm.storage

import com.espitman.sdm.download.DownloadPartFile
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DestinationAllocatorTest {
    private val treeUri = "content://com.android.externalstorage.documents/tree/primary%3ADownload"
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("destination-allocator").toFile()
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun appPrivateAllocatorReservesUniquePartFiles() {
        File(tempDir, "notes.txt").writeText("taken")
        val allocated = AppPrivateDestinationAllocator(directory = { tempDir }).allocate("notes.txt")
        assertEquals("notes (1).txt", allocated.fileName)
        assertEquals(File(tempDir, "notes (1).txt").absolutePath, allocated.destinationPath)
        assertNull(allocated.destinationTreeUri)
        assertNull(allocated.destinationDisplayLabel)
        assertTrue(allocated.partFile.exists())
        assertEquals(DownloadPartFile.forResolvedFilename(tempDir, "notes (1).txt"), allocated.partFile)
        assertFalse(File(tempDir, "notes (1).txt").exists())
    }

    @Test
    fun userTreeAllocatorStagesLocallyAndSkipsTreeCollisions() {
        val store = SaveLocationStore(InMemoryPreferences())
        val grants = FakeTreeUriGrantStore()
        val trees = FakeUserTreeAccess()
        store.persistUserTree(treeUri, "Download")
        grants.granted.add(treeUri)
        trees.inspections[treeUri] = UserTreeInspection(
            UserTreeState.Writable,
            "Download",
            setOf("movie.mkv"),
        )
        val allocator = SaveLocationDestinationAllocator(
            coordinator = SaveLocationCoordinator(store, grants, trees),
            appSpecificDirectory = { tempDir },
            trees = trees,
        )
        val allocated = allocator.allocate("movie.mkv")
        assertEquals("movie (1).mkv", allocated.fileName)
        assertEquals(treeUri, allocated.destinationTreeUri)
        assertEquals("Download", allocated.destinationDisplayLabel)
        val staging = File(tempDir, SaveLocationDestinationAllocator.STAGING_DIRECTORY_NAME)
        assertEquals(File(staging, "movie (1).mkv").absolutePath, allocated.destinationPath)
        assertTrue(allocated.partFile.exists())
        assertEquals(staging, allocated.partFile.parentFile)
        assertTrue(store.read().treeUri == treeUri)
    }

    @Test
    fun userTreeAllocatorFallsBackWhenTheTreeIsGone() {
        val store = SaveLocationStore(InMemoryPreferences())
        val grants = FakeTreeUriGrantStore()
        val trees = FakeUserTreeAccess()
        store.persistUserTree(treeUri, "Download")
        grants.granted.add(treeUri)
        trees.inspections[treeUri] = UserTreeInspection(UserTreeState.Missing)
        val allocator = SaveLocationDestinationAllocator(
            coordinator = SaveLocationCoordinator(store, grants, trees),
            appSpecificDirectory = { tempDir },
            trees = trees,
        )
        val allocated = allocator.allocate("clip.bin")
        assertEquals("clip.bin", allocated.fileName)
        assertNull(allocated.destinationTreeUri)
        assertEquals(File(tempDir, "clip.bin").absolutePath, allocated.destinationPath)
        assertNull(store.read().treeUri)
    }
}
