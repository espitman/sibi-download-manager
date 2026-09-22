package com.espitman.sdm.storage

import android.content.Context
import android.os.Environment
import android.os.StatFs
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StorageCapacityQueryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var probe: AndroidStorageCapacityProbe

    @Before
    fun setUp() {
        SdmTestDocumentsProvider.reset(context)
        probe = AndroidStorageCapacityProbe(context)
    }

    @After
    fun tearDown() {
        SdmTestDocumentsProvider.reset(context)
    }

    @Test
    fun appSpecificDownloadsStatFsMatchesTheDirectoryFilesystem() {
        val directory = AppSpecificDownloadsDirectory.from(context)
        assertTrue(directory.isDirectory)
        val capacity = probe.queryLocalPath(directory)
        val stat = StatFs(directory.absolutePath)
        assertEquals(stat.totalBytes, capacity.totalBytes)
        assertEquals(stat.availableBytes, capacity.availableBytes)
        assertEquals(stat.totalBytes - stat.availableBytes, capacity.usedBytes)
        assertTrue(capacity.totalBytes!! > 0L)
        assertTrue(capacity.availableBytes!! >= 0L)
        assertTrue(capacity.availableBytes!! <= capacity.totalBytes!!)
        val external = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        if (external != null) {
            assertEquals(external.canonicalFile, directory.canonicalFile)
        }
        val missingChild = File(directory, "sdm-044-missing.bin")
        assertEquals(capacity, probe.queryLocalPath(missingChild))
    }

    @Test
    fun documentsProviderWithoutAvailableBytesIsUnknown() {
        val capacity = probe.queryTree(SdmTestDocumentsProvider.treeUri().toString())
        assertTrue(capacity.isUnknown)
        assertNull(capacity.availableBytes)
        assertNull(capacity.totalBytes)
        assertNull(capacity.usedBytes)
    }

    @Test
    fun documentsProviderAvailableBytesAreQueriedFromTheMatchingRoot() {
        SdmTestDocumentsProvider.rootAvailableBytes = 1_024L
        SdmTestDocumentsProvider.rootCapacityBytes = 4_096L
        val known = probe.queryTree(SdmTestDocumentsProvider.treeUri().toString())
        assertEquals(4_096L, known.totalBytes)
        assertEquals(1_024L, known.availableBytes)
        assertEquals(3_072L, known.usedBytes)

        SdmTestDocumentsProvider.rootCapacityBytes = null
        val availableOnly = probe.queryTree(SdmTestDocumentsProvider.treeUri().toString())
        assertNull(availableOnly.totalBytes)
        assertEquals(1_024L, availableOnly.availableBytes)
        assertNull(availableOnly.usedBytes)

        SdmTestDocumentsProvider.rootAvailableBytes = -1L
        SdmTestDocumentsProvider.rootCapacityBytes = 4_096L
        val unknownAvailable = probe.queryTree(SdmTestDocumentsProvider.treeUri().toString())
        assertEquals(4_096L, unknownAvailable.totalBytes)
        assertNull(unknownAvailable.availableBytes)
        assertNull(unknownAvailable.usedBytes)
        assertTrue(probe.queryTree("content://com.espitman.sdm.test.documents/document/root").isUnknown)
        assertTrue(probe.queryTree("file:///storage/emulated/0/Download").isUnknown)
    }

    @Test
    fun documentsProviderCapacityUsesRootWhenSelectedTreeIsADescendant() {
        SdmTestDocumentsProvider.queryRootId = "primary"
        SdmTestDocumentsProvider.queryRootDocumentId = "primary:"
        SdmTestDocumentsProvider.rootAvailableBytes = 2_048L
        SdmTestDocumentsProvider.rootCapacityBytes = 8_192L

        val selectedDownload = SdmTestDocumentsProvider.treeUriFor("primary:Download")
        val nested = SdmTestDocumentsProvider.treeUriFor("primary:Download/SDM")
        val expected = StorageCapacity.from(totalBytes = 8_192L, availableBytes = 2_048L)
        assertEquals("primary:Download", DocumentsContract.getTreeDocumentId(selectedDownload))
        assertEquals(expected, probe.queryTree(selectedDownload.toString()))
        assertEquals(expected, probe.queryTree(nested.toString()))
        assertEquals(expected, probe.queryTree(SdmTestDocumentsProvider.treeUriFor("primary:").toString()))
        assertTrue(probe.queryTree(SdmTestDocumentsProvider.treeUriFor("home:Download").toString()).isUnknown)
        assertTrue(probe.queryTree(SdmTestDocumentsProvider.treeUriFor("primary2:Download").toString()).isUnknown)
        assertTrue(probe.queryTree(SdmTestDocumentsProvider.treeUriFor("root/Download").toString()).isUnknown)
    }

    @Test
    fun localExternalStorageTreeUsesTheStorageVolumeFilesystem() {
        val tree = "content://com.android.externalstorage.documents/tree/primary%3ADownload%2FSDM-QA"
        val capacity = probe.queryTree(tree)
        val manager = context.getSystemService(android.os.storage.StorageManager::class.java)
        val primaryVolume = manager.storageVolumes.first { it.isPrimary }
        @Suppress("DEPRECATION")
        val directory = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            primaryVolume.directory ?: Environment.getExternalStorageDirectory()
        } else {
            Environment.getExternalStorageDirectory()
        }
        val stat = StatFs(directory.absolutePath)
        assertTrue(directory.isDirectory)
        assertFalse(capacity.isUnknown)
        assertEquals(stat.totalBytes, capacity.totalBytes)
        assertEquals(stat.availableBytes, capacity.availableBytes)
        assertEquals(stat.totalBytes - stat.availableBytes, capacity.usedBytes)
        assertTrue(capacity.totalBytes!! > 0L)
        assertTrue(capacity.availableBytes!! >= 0L)
        assertTrue(capacity.availableBytes!! <= capacity.totalBytes!!)
        assertEquals("primary", ExternalStorageVolume.volumeIdFromDocumentId("primary:Download/SDM-QA"))
        val info = context.packageManager.getPackageInfo(
            context.packageName,
            android.content.pm.PackageManager.GET_PERMISSIONS,
        )
        val requested = info.requestedPermissions?.toSet().orEmpty()
        assertFalse(requested.contains("android.permission.MANAGE_DOCUMENTS"))
        StorageAccessPolicy.DISALLOWED_BROAD_STORAGE_PERMISSIONS.forEach { permission ->
            assertFalse("declared $permission", requested.contains(permission))
        }
    }
}
