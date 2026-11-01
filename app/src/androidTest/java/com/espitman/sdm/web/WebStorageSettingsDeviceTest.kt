package com.espitman.sdm.web

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract as DC
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.espitman.sdm.domain.Download
import com.espitman.sdm.storage.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class WebStorageSettingsDeviceTest {
    @Test fun settingsAreBrowserOwnedAndInvalidSelectionsNeverChangeDefaults() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "web-storage-fixture-${UUID.randomUUID()}"
        val savePrefs = context.getSharedPreferences("$name-save", 0)
        val categoryPrefs = context.getSharedPreferences("$name-category", 0)
        val save = SaveLocationStore(savePrefs)
        val categories = CategoryFolderStore(categoryPrefs)
        val folders = WebFolders(context)
        val storage = WebStorageSettings(context, folders, save, categories)
        try {
            val token = folders.list("owner", null).getJSONArray("folders").getJSONObject(0).getString("id")
            val command = JSONObject().put("action", "default").put("folder", token)
            try { storage.update("other", command); fail("Foreign folder must be rejected") } catch (_: IllegalArgumentException) {}
            assertNull(save.read().treeUri)
            save.persistUserTree("content://fixture/tree/old", "Previous folder")
            try { storage.update("owner", JSONObject().put("action", "default").put("folder", "invalid")); fail() } catch (_: IllegalArgumentException) {}
            assertEquals("Previous folder", save.read().displayLabel)
            storage.update("owner", command)
            assertNull(save.read().treeUri)
            categories.save(FileCategory.VIDEO, CategoryFolderRule("content://fixture/tree/old", "Video folder"))
            storage.update("owner", JSONObject().put("action", "enabled").put("enabled", true))
            storage.update("owner", JSONObject().put("action", "clear").put("category", "VIDEO"))
            assertTrue(categories.settings.value.enabled)
            assertTrue(categories.settings.value.rules.isEmpty())
            assertEquals(6, storage.snapshot().getJSONArray("categories").length())
        } finally { savePrefs.edit().clear().commit(); categoryPrefs.edit().clear().commit() }
    }

    @Test fun renameRemapsSelectedProviderParentWhilePreservingProgress() {
        val old = DC.buildTreeDocumentUri("fixture", "old").toString()
        val new = DC.buildTreeDocumentUri("fixture", "new").toString()
        val parent = DC.buildDocumentUriUsingTree(Uri.parse(old), "old/child").toString()
        val download = Download(url="https://example.invalid/file", fileName="file", createdAtEpochMillis=1,
            destinationPath="/staging/file", destinationTreeUri=old, destinationParentDocumentUri=parent,
            downloadedBytes=123, totalBytes=456)
        val mapped = FolderRenameCoordinator.remap(download, JSONObject().put("oldTree", old).put("newTree", new).put("name", "new"))
        assertEquals(new, mapped.destinationTreeUri)
        assertEquals("new/child", DC.getDocumentId(Uri.parse(mapped.destinationParentDocumentUri)))
        assertEquals(123L, mapped.downloadedBytes)
        assertEquals(download.destinationPath, mapped.destinationPath)
        assertEquals(download.id, mapped.id)
    }

    @Test fun grantedRootSelectionPreservesGrantsAndRevokedOrChildSelectionsPreserveSettings() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "web-storage-provider-${UUID.randomUUID()}"
        val resolver = context.contentResolver
        val root = requireNotNull(DC.createDocument(resolver, SdmTestDocumentsProvider.parentUri(), DC.Document.MIME_TYPE_DIR, name))
        val grant = SdmTestDocumentsProvider.treeUriFor(DC.getDocumentId(root))
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val grants = PersistableTreeUriGrants(resolver)
        val savePrefs = context.getSharedPreferences("$name-save", 0)
        val categoryPrefs = context.getSharedPreferences("$name-category", 0)
        val save = SaveLocationStore(savePrefs)
        val categories = CategoryFolderStore(categoryPrefs)
        val folders = WebFolders(context)
        val storage = WebStorageSettings(context, folders, save, categories)
        val originals = grants.persistedReadWriteTrees().toSet()
        try {
            context.grantUriPermission(context.packageName, grant, flags or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
            grants.takeReadWrite(grant.toString())
            val roots = folders.list("owner", null).getJSONArray("folders")
            val token = (0 until roots.length()).map { roots.getJSONObject(it) }.single { it.getString("name") == name }.getString("id")
            storage.update("owner", JSONObject().put("action", "default").put("folder", token))
            storage.update("owner", JSONObject().put("action", "category").put("category", "VIDEO").put("folder", token))
            assertEquals(grant.toString(), save.read().treeUri)
            assertEquals(grant.toString(), categories.settings.value.rules[FileCategory.VIDEO]?.treeUri)
            assertTrue(grants.persistedReadWriteTrees().containsAll(originals))
            val child = folders.create("owner", token, "Child").getString("id")
            try { storage.update("owner", JSONObject().put("action", "default").put("folder", child)); fail() } catch (_: IllegalArgumentException) {}
            assertEquals(grant.toString(), save.read().treeUri)
            grants.releaseReadWrite(grant.toString())
            try { storage.update("owner", JSONObject().put("action", "category").put("category", "AUDIO").put("folder", token)); fail() } catch (_: IllegalArgumentException) {}
            assertFalse(categories.settings.value.rules.containsKey(FileCategory.AUDIO))
            assertEquals(grant.toString(), categories.settings.value.rules[FileCategory.VIDEO]?.treeUri)
        } finally {
            grants.releaseReadWrite(grant.toString())
            context.revokeUriPermission(grant, flags)
            DC.deleteDocument(resolver, root)
            savePrefs.edit().clear().commit(); categoryPrefs.edit().clear().commit()
        }
    }
}
