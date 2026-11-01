package com.espitman.sdm.web

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.provider.DocumentsContract as DC
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.espitman.sdm.storage.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class WebFolderAccessDeviceTest {
    @Test fun ownershipCancellationExpiryRevocationAndStopRejectLatePickerResults() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        var now = 1L
        val approved = mutableSetOf("owner", "other")
        val requests = WebFolderAccess({ now }, { it in approved })
        val id = requests.create("owner", null).getString("id")
        assertEquals(id, requests.create("owner", null).getString("id"))
        try { requests.status("other", id); fail() } catch (_: IllegalStateException) {}
        requests.cancel("other", id)
        assertNotNull(requests.pending.value)
        requests.cancel("owner", id)
        requests.complete(context, id, Activity.RESULT_OK, "content://invalid/tree/not-selected", 0)
        assertEquals("cancelled", requests.status("owner", id).getString("state"))
        assertNull(requests.pending.value)
        val expired = requests.create("owner", "VIDEO").getString("id")
        now += 120_001
        assertEquals("cancelled", requests.status("owner", expired).getString("state"))
        val revoked = requests.create("owner", null).getString("id")
        approved.remove("owner")
        requests.complete(context, revoked, Activity.RESULT_OK, "content://invalid/tree/not-selected", 0)
        assertEquals("cancelled", requests.status("owner", revoked).getString("state"))
        approved.add("owner")
        val stopped = requests.create("owner", null).getString("id")
        requests.cancelAll()
        assertEquals("cancelled", requests.status("owner", stopped).getString("state"))
        val cancelled = requests.create("owner", null).getString("id")
        requests.complete(context, cancelled, Activity.RESULT_CANCELED, null, 0)
        assertEquals("cancelled", requests.status("owner", cancelled).getString("state"))
    }

    @Test fun pickerResultsApplyOnlyToRequestedNativeStoreAndKeepExistingGrants() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "web-folder-access-${UUID.randomUUID()}"
        val resolver = context.contentResolver
        val root = requireNotNull(DC.createDocument(resolver, SdmTestDocumentsProvider.parentUri(), DC.Document.MIME_TYPE_DIR, name))
        val grant = SdmTestDocumentsProvider.treeUriFor(DC.getDocumentId(root))
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val savePrefs = context.getSharedPreferences("$name-save", 0)
        val categoryPrefs = context.getSharedPreferences("$name-category", 0)
        val save = SaveLocationStore(savePrefs)
        val categories = CategoryFolderStore(categoryPrefs)
        val requests = WebFolderAccess(approved = { it == "owner" }, saveStore = { save }, categoryStore = { categories })
        val grants = PersistableTreeUriGrants(resolver)
        val originalGrants = grants.persistedReadWriteTrees().toSet()
        val originalDefault = SaveLocationStore.get(context).read()
        val originalCategories = CategoryFolderStore.get(context).settings.value
        try {
            context.grantUriPermission(context.packageName, grant, flags or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
            val category = requests.create("owner", "VIDEO").getString("id")
            requests.complete(context, category, Activity.RESULT_OK, grant.toString(), flags)
            assertEquals("accepted", requests.status("owner", category).getString("state"))
            assertEquals(grant.toString(), categories.settings.value.rules[FileCategory.VIDEO]?.treeUri)
            assertNull(save.read().treeUri)
            assertTrue(grants.hasReadWrite(grant.toString()))
            val default = requests.create("owner", null).getString("id")
            requests.complete(context, default, Activity.RESULT_OK, grant.toString(), flags)
            assertEquals("accepted", requests.status("owner", default).getString("state"))
            assertEquals(grant.toString(), save.read().treeUri)
            requests.complete(context, default, Activity.RESULT_OK, "content://invalid/tree/replayed", flags)
            assertEquals(grant.toString(), save.read().treeUri)
            // Android providers may omit flags when a writable grant already exists.
            val reused = requests.create("owner", "AUDIO").getString("id")
            requests.complete(context, reused, Activity.RESULT_OK, grant.toString(), 0)
            assertEquals("accepted", requests.status("owner", reused).getString("state"))
            val failed = requests.create("owner", "ARCHIVES").getString("id")
            requests.complete(context, failed, Activity.RESULT_OK, "file:///untrusted/folder", flags)
            assertEquals("failed", requests.status("owner", failed).getString("state"))
            assertFalse(categories.settings.value.rules.containsKey(FileCategory.ARCHIVES))
            assertTrue(grants.hasReadWrite(grant.toString()))
            assertTrue(grants.persistedReadWriteTrees().containsAll(originalGrants))
            assertEquals(originalDefault, SaveLocationStore.get(context).read())
            assertEquals(originalCategories, CategoryFolderStore.get(context).settings.value)
            grants.releaseReadWrite(grant.toString())
            context.revokeUriPermission(grant, flags)
            val denied = requests.create("owner", "IMAGES").getString("id")
            requests.complete(context, denied, Activity.RESULT_OK, grant.toString(), flags)
            assertEquals("failed", requests.status("owner", denied).getString("state"))
            assertFalse(categories.settings.value.rules.containsKey(FileCategory.IMAGES))
        } finally {
            grants.releaseReadWrite(grant.toString()); context.revokeUriPermission(grant, flags)
            DC.deleteDocument(resolver, root)
            savePrefs.edit().clear().commit(); categoryPrefs.edit().clear().commit()
        }
    }
}
