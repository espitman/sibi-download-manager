package com.espitman.sdm.storage

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StorageAccessFrameworkTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val treeUri = "content://com.android.externalstorage.documents/tree/primary%3ADownload"

    @Test
    fun defaultDirectoryIsWritableAppSpecificDownloadsWithoutFallback() {
        val external = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        val resolved = AppSpecificDownloadsDirectory.from(context)
        assertTrue(resolved.isDirectory)
        assertTrue(resolved.canRead())
        assertTrue(resolved.canWrite())
        if (external != null) {
            assertEquals(external.canonicalFile, resolved.canonicalFile)
            assertTrue(
                resolved.absolutePath.contains("/Android/data/${context.packageName}/files/Download"),
            )
        }
        val probe = File(resolved, "sdm-039-probe.txt")
        probe.writeText("ok")
        assertEquals("ok", probe.readText())
        assertTrue(probe.delete())
    }

    @Test
    fun pickerIntentMatchesOpenDocumentTreePersistableReadWriteContract() {
        val intent = OpenDocumentTreeAccess.createPickerIntent()
        assertEquals(Intent.ACTION_OPEN_DOCUMENT_TREE, intent.action)
        assertEquals(Intent.ACTION_OPEN_DOCUMENT_TREE, StorageAccessPolicy.OPEN_DOCUMENT_TREE_ACTION)
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, StorageAccessPolicy.GRANT_READ)
        assertEquals(Intent.FLAG_GRANT_WRITE_URI_PERMISSION, StorageAccessPolicy.GRANT_WRITE)
        assertEquals(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION, StorageAccessPolicy.GRANT_PERSISTABLE)
        assertEquals(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION, StorageAccessPolicy.GRANT_PREFIX)
        assertEquals(DocumentsContract.EXTRA_INITIAL_URI, StorageAccessPolicy.EXTRA_INITIAL_URI)
        val frameworkPickerFlags =
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        assertEquals(frameworkPickerFlags, intent.flags and frameworkPickerFlags)
        assertEquals(frameworkPickerFlags, StorageAccessPolicy.PICKER_INTENT_FLAGS)
        assertEquals(StorageAccessPolicy.PICKER_INTENT_FLAGS, intent.flags and StorageAccessPolicy.PICKER_INTENT_FLAGS)
        assertFalse(intent.hasExtra(DocumentsContract.EXTRA_INITIAL_URI))

        val withInitial = OpenDocumentTreeAccess.createPickerIntent(treeUri)
        val initialUri = requireNotNull(
            @Suppress("DEPRECATION")
            withInitial.getParcelableExtra(DocumentsContract.EXTRA_INITIAL_URI) as Uri?,
        )
        assertEquals(treeUri, initialUri.toString())
        assertTrue(DocumentsContract.isTreeUri(initialUri))
        val ignored = OpenDocumentTreeAccess.createPickerIntent("file:///storage/emulated/0/Download")
        assertFalse(ignored.hasExtra(DocumentsContract.EXTRA_INITIAL_URI))
    }

    @Test
    fun persistableGrantsRequireARealTreeGrantAndRejectInvalidUris() {
        val grants = PersistableTreeUriGrants(context.contentResolver)
        val invalid = grants.takeReadWrite("content://com.android.externalstorage.documents/document/primary%3ADownload")
        assertTrue(invalid is PersistableGrantResult.InvalidTree)
        assertFalse(grants.hasReadWrite("content://com.android.externalstorage.documents/document/primary%3ADownload"))

        val denied = grants.takeReadWrite(treeUri)
        assertTrue(denied is PersistableGrantResult.Failure)
        assertEquals(treeUri, (denied as PersistableGrantResult.Failure).uriString)
        assertTrue(denied.cause is SecurityException)
        assertFalse(grants.hasReadWrite(treeUri))
        assertFalse(grants.persistedReadWriteTrees().contains(treeUri))

        val released = grants.releaseReadWrite(treeUri)
        assertEquals(PersistableGrantResult.Success(treeUri), released)
        val persisted = context.contentResolver.persistedUriPermissions
        assertFalse(persisted.any { it.uri == Uri.parse(treeUri) })
        assertEquals(
            persisted.filter { it.isReadPermission && it.isWritePermission }
                .map { it.uri.toString() }
                .filter { StorageAccessPolicy.validateTreeUri(it) is TreeUriValidation.Valid },
            grants.persistedReadWriteTrees(),
        )
    }

    @Test
    fun packageDoesNotRequestBroadStoragePermissions() {
        val info = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS,
        )
        val requested = info.requestedPermissions?.toSet().orEmpty()
        StorageAccessPolicy.DISALLOWED_BROAD_STORAGE_PERMISSIONS.forEach { permission ->
            assertFalse("declared $permission", requested.contains(permission))
        }
    }

    @Test
    fun saveLocationStoreSurvivesProcessRecreationAndFallsBackWithoutAGrant() {
        val preferences = context.getSharedPreferences("sdm-040-save-location", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        val first = SaveLocationStore(preferences)
        assertEquals(PersistedSaveLocation.DEFAULT, first.read())
        first.persistUserTree(treeUri, "Download")
        assertEquals(treeUri, first.read().treeUri)
        assertEquals("Download", first.read().displayLabel)
        assertTrue(preferences.edit().commit())

        val restored = SaveLocationStore(preferences)
        assertEquals(treeUri, restored.read().treeUri)
        assertEquals("Download", restored.read().displayLabel)

        val coordinator = SaveLocationCoordinator(
            store = restored,
            grants = PersistableTreeUriGrants(context.contentResolver),
            trees = DocumentsContractTreeAccess(context.contentResolver),
        )
        val recovered = coordinator.validatePersisted()
        assertEquals(SaveLocationRecovery.PermissionRevoked, recovered)
        assertNull(restored.read().treeUri)
        assertEquals(SaveLocationLabels.DEFAULT, restored.read().displayLabel)
        preferences.edit().clear().commit()
    }
}
