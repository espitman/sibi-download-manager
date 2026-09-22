package com.espitman.sdm.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageAccessPolicyTest {
    private val treeUri = "content://com.android.externalstorage.documents/tree/primary%3ADownload"

    @Test
    fun blankAndNonContentUrisAreRejected() {
        assertInvalid(null, "URI is missing")
        assertInvalid("", "URI is missing")
        assertInvalid("   ", "URI is missing")
        assertInvalid("not a uri", "URI is malformed")
        assertInvalid("/storage/emulated/0/Download", "Folder access requires a content tree URI")
        assertInvalid("file:///storage/emulated/0/Download", "Folder access requires a content tree URI")
        assertInvalid("https://example.com/tree/primary", "Folder access requires a content tree URI")
        assertInvalid("content:///tree/primary%3ADownload", "Tree URI is missing an authority")
    }

    @Test
    fun documentAndChildUrisAreRejectedWhileExactTreesAreAccepted() {
        assertInvalid(
            "content://com.android.externalstorage.documents/document/primary%3ADownload",
            "URI is not an OpenDocumentTree folder tree",
        )
        assertInvalid(
            "content://com.android.externalstorage.documents/tree/primary%3ADownload/document/primary%3ADownload%2Ffile.bin",
            "URI is not an OpenDocumentTree folder tree",
        )
        assertInvalid(
            "content://com.android.externalstorage.documents/tree/",
            "URI is not an OpenDocumentTree folder tree",
        )
        val valid = StorageAccessPolicy.validateTreeUri(treeUri)
        assertEquals(TreeUriValidation.Valid(treeUri), valid)
        assertEquals(
            TreeUriValidation.Valid(treeUri),
            StorageAccessPolicy.validateTreeUri("  $treeUri  "),
        )
    }

    @Test
    fun configuredLocationDefaultsUntilAValidTreeIsSupplied() {
        assertEquals(
            StorageLocation.AppSpecificDownloads,
            StorageAccessPolicy.classifyConfiguredLocation(null),
        )
        assertEquals(
            StorageLocation.AppSpecificDownloads,
            StorageAccessPolicy.classifyConfiguredLocation(" "),
        )
        assertEquals(
            StorageLocation.UserSelectedTree(treeUri),
            StorageAccessPolicy.classifyConfiguredLocation(treeUri),
        )
        val invalid = StorageAccessPolicy.classifyConfiguredLocation("file:///Download")
        assertTrue(invalid is StorageLocation.InvalidConfiguredUri)
        assertEquals("file:///Download", (invalid as StorageLocation.InvalidConfiguredUri).uriString)
    }

    @Test
    fun pickerIntentFlagsRequestPersistableReadWritePrefixGrants() {
        assertEquals(0x00000001, StorageAccessPolicy.GRANT_READ)
        assertEquals(0x00000002, StorageAccessPolicy.GRANT_WRITE)
        assertEquals(0x00000040, StorageAccessPolicy.GRANT_PERSISTABLE)
        assertEquals(0x00000080, StorageAccessPolicy.GRANT_PREFIX)
        assertEquals(
            StorageAccessPolicy.GRANT_READ or StorageAccessPolicy.GRANT_WRITE or
                StorageAccessPolicy.GRANT_PERSISTABLE or StorageAccessPolicy.GRANT_PREFIX,
            StorageAccessPolicy.PICKER_INTENT_FLAGS,
        )
        assertEquals(
            StorageAccessPolicy.GRANT_READ or StorageAccessPolicy.GRANT_WRITE,
            StorageAccessPolicy.TAKE_PERSISTABLE_FLAGS,
        )
        assertTrue(StorageAccessPolicy.hasReadAndWrite(StorageAccessPolicy.TAKE_PERSISTABLE_FLAGS))
        assertFalse(StorageAccessPolicy.hasReadAndWrite(StorageAccessPolicy.GRANT_READ))
        assertFalse(StorageAccessPolicy.hasReadAndWrite(StorageAccessPolicy.GRANT_WRITE))
        assertFalse(StorageAccessPolicy.hasReadAndWrite(0))
    }

    @Test
    fun takeFlagsFallBackToReadWriteWhenTheResultOmitsGrantBits() {
        assertEquals(
            StorageAccessPolicy.TAKE_PERSISTABLE_FLAGS,
            StorageAccessPolicy.takeFlagsForResult(0),
        )
        assertEquals(
            StorageAccessPolicy.TAKE_PERSISTABLE_FLAGS,
            StorageAccessPolicy.takeFlagsForResult(StorageAccessPolicy.GRANT_READ),
        )
        assertEquals(
            StorageAccessPolicy.TAKE_PERSISTABLE_FLAGS,
            StorageAccessPolicy.takeFlagsForResult(StorageAccessPolicy.PICKER_INTENT_FLAGS),
        )
    }

    @Test
    fun pickerResultsRequireOkAndAValidTree() {
        assertEquals(
            OpenDocumentTreeOutcome.Canceled,
            StorageAccessPolicy.interpretPickerResult(0, treeUri, StorageAccessPolicy.TAKE_PERSISTABLE_FLAGS),
        )
        assertEquals(
            OpenDocumentTreeOutcome.Canceled,
            StorageAccessPolicy.interpretPickerResult(StorageAccessPolicy.RESULT_OK, null),
        )
        val invalid = StorageAccessPolicy.interpretPickerResult(
            StorageAccessPolicy.RESULT_OK,
            "content://downloads/document/1",
        )
        assertTrue(invalid is OpenDocumentTreeOutcome.InvalidTree)
        assertEquals(
            OpenDocumentTreeOutcome.Accepted(treeUri, StorageAccessPolicy.TAKE_PERSISTABLE_FLAGS),
            StorageAccessPolicy.interpretPickerResult(StorageAccessPolicy.RESULT_OK, " $treeUri "),
        )
    }

    @Test
    fun hybridModelDoesNotIncludeBroadStoragePermissions() {
        assertEquals("android.intent.action.OPEN_DOCUMENT_TREE", StorageAccessPolicy.OPEN_DOCUMENT_TREE_ACTION)
        assertEquals("android.provider.extra.INITIAL_URI", StorageAccessPolicy.EXTRA_INITIAL_URI)
        assertTrue(
            StorageAccessPolicy.DISALLOWED_BROAD_STORAGE_PERMISSIONS.containsAll(
                listOf(
                    "android.permission.READ_EXTERNAL_STORAGE",
                    "android.permission.WRITE_EXTERNAL_STORAGE",
                    "android.permission.MANAGE_EXTERNAL_STORAGE",
                ),
            ),
        )
    }

    private fun assertInvalid(uriString: String?, reason: String) {
        assertEquals(TreeUriValidation.Invalid(reason), StorageAccessPolicy.validateTreeUri(uriString))
    }
}
