package com.espitman.sdm.storage

import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SaveLocationCoordinatorTest {
    private val treeUri = "content://com.android.externalstorage.documents/tree/primary%3ADownload"
    private lateinit var store: SaveLocationStore
    private lateinit var grants: FakeTreeUriGrantStore
    private lateinit var trees: FakeUserTreeAccess
    private lateinit var coordinator: SaveLocationCoordinator

    @Before
    fun setUp() {
        store = SaveLocationStore(InMemoryPreferences())
        grants = FakeTreeUriGrantStore()
        trees = FakeUserTreeAccess()
        coordinator = SaveLocationCoordinator(store, grants, trees)
    }

    @Test
    fun blankPersistedLocationStaysAppSpecific() {
        val resolved = coordinator.resolveForNewDownload()
        assertNull(resolved.recovery)
        assertEquals(ActiveSaveLocation.AppSpecific(), resolved.location)
        assertEquals(SaveLocationLabels.DEFAULT, resolved.displayLabel)
    }

    @Test
    fun validGrantedWritableTreeIsUsed() {
        store.persistUserTree(treeUri, "Download")
        grants.granted.add(treeUri)
        trees.inspections[treeUri] = UserTreeInspection(UserTreeState.Writable, "Download", setOf("a.bin"))
        val resolved = coordinator.resolveForNewDownload()
        assertNull(resolved.recovery)
        assertEquals(ActiveSaveLocation.UserTree(treeUri, "Download"), resolved.location)
        assertEquals(treeUri, store.read().treeUri)
    }

    @Test
    fun revokedGrantClearsTheSetting() {
        store.persistUserTree(treeUri, "Download")
        trees.inspections[treeUri] = UserTreeInspection(UserTreeState.Writable, "Download")
        val resolved = coordinator.resolveForNewDownload()
        assertEquals(SaveLocationRecovery.PermissionRevoked, resolved.recovery)
        assertEquals(ActiveSaveLocation.AppSpecific(), resolved.location)
        assertNull(store.read().treeUri)
        assertEquals(SaveLocationLabels.DEFAULT, store.read().displayLabel)
    }

    @Test
    fun providerRevocationClearsTheSettingAndReleasesThePersistedGrant() {
        store.persistUserTree(treeUri, "Download")
        grants.granted.add(treeUri)
        trees.inspections[treeUri] = UserTreeInspection(UserTreeState.PermissionRevoked, "Download")

        val resolved = coordinator.resolveForNewDownload()

        assertEquals(SaveLocationRecovery.PermissionRevoked, resolved.recovery)
        assertEquals(ActiveSaveLocation.AppSpecific(), resolved.location)
        assertNull(store.read().treeUri)
        assertFalse(grants.granted.contains(treeUri))
    }

    @Test
    fun deletedTreeClearsTheSettingAndReleasesTheGrant() {
        store.persistUserTree(treeUri, "Download")
        grants.granted.add(treeUri)
        trees.inspections[treeUri] = UserTreeInspection(UserTreeState.Missing)
        val resolved = coordinator.resolveForNewDownload()
        assertEquals(SaveLocationRecovery.TreeDeleted, resolved.recovery)
        assertNull(store.read().treeUri)
        assertFalse(grants.granted.contains(treeUri))
        assertNull(coordinator.validatePersisted())
    }

    @Test
    fun unavailableProviderAndUnwritableTreesFallback() {
        store.persistUserTree(treeUri, "Download")
        grants.granted.add(treeUri)
        trees.inspections[treeUri] = UserTreeInspection(UserTreeState.ProviderUnavailable)
        assertEquals(SaveLocationRecovery.ProviderUnavailable, coordinator.resolveForNewDownload().recovery)

        store.persistUserTree(treeUri, "Download")
        grants.granted.add(treeUri)
        trees.inspections[treeUri] = UserTreeInspection(UserTreeState.NotWritable, "Download")
        assertEquals(SaveLocationRecovery.NotWritable, coordinator.resolveForNewDownload().recovery)
    }

    @Test
    fun invalidStoredUriClearsTheSetting() {
        val prefs = InMemoryPreferences()
        prefs.edit().putString(SaveLocationStore.KEY_TREE_URI, "file:///Download").apply()
        val invalidStore = SaveLocationStore(prefs)
        val invalidCoordinator = SaveLocationCoordinator(invalidStore, grants, trees)
        val resolved = invalidCoordinator.resolveForNewDownload()
        assertEquals(SaveLocationRecovery.InvalidUri, resolved.recovery)
        assertNull(invalidStore.read().treeUri)
        assertEquals(SaveLocationLabels.DEFAULT, invalidStore.read().displayLabel)
    }

    @Test
    fun pickerCancelAndInvalidTreeDoNotChangeTheSetting() {
        assertEquals(
            SaveLocationPickerResult.Canceled,
            coordinator.applyPickerResult(0, treeUri, StorageAccessPolicy.TAKE_PERSISTABLE_FLAGS),
        )
        assertNull(store.read().treeUri)
        val invalid = coordinator.applyPickerResult(
            StorageAccessPolicy.RESULT_OK,
            "content://downloads/document/1",
        )
        assertTrue(invalid is SaveLocationPickerResult.Failed)
        assertNull(store.read().treeUri)
    }

    @Test
    fun acceptedPickerTakesTheGrantAndPersistsTheLabel() {
        grants.takeSucceeds = true
        trees.inspections[treeUri] = UserTreeInspection(UserTreeState.Writable, "Download")
        val accepted = coordinator.applyPickerResult(
            StorageAccessPolicy.RESULT_OK,
            " $treeUri ",
            StorageAccessPolicy.TAKE_PERSISTABLE_FLAGS,
        )
        assertEquals(SaveLocationPickerResult.Accepted("Download"), accepted)
        assertEquals(treeUri, store.read().treeUri)
        assertEquals("Download", store.read().displayLabel)
        assertTrue(grants.granted.contains(treeUri))
    }

    @Test
    fun acceptedPickerReleasesThePreviousTree() {
        val previous = "content://com.android.externalstorage.documents/tree/primary%3AMovies"
        store.persistUserTree(previous, "Movies")
        grants.granted.add(previous)
        grants.takeSucceeds = true
        trees.inspections[treeUri] = UserTreeInspection(UserTreeState.Writable, "Download")
        coordinator.applyPickerResult(StorageAccessPolicy.RESULT_OK, treeUri)
        assertFalse(grants.granted.contains(previous))
        assertTrue(grants.granted.contains(treeUri))
        assertEquals("Download", store.read().displayLabel)
    }

    @Test
    fun unwritablePickerResultDoesNotPersistTheUri() {
        grants.takeSucceeds = true
        trees.inspections[treeUri] = UserTreeInspection(UserTreeState.NotWritable, "Download")
        val failed = coordinator.applyPickerResult(StorageAccessPolicy.RESULT_OK, treeUri)
        assertEquals(
            SaveLocationPickerResult.Failed(SaveLocationRecovery.NotWritable.message),
            failed,
        )
        assertNull(store.read().treeUri)
        assertFalse(grants.granted.contains(treeUri))
    }
}

class FakeTreeUriGrantStore : TreeUriGrantStore {
    val granted = mutableSetOf<String>()
    var takeSucceeds = false

    override fun takeReadWrite(uriString: String, takeFlags: Int): PersistableGrantResult {
        val valid = StorageAccessPolicy.validateTreeUri(uriString) as? TreeUriValidation.Valid
            ?: return PersistableGrantResult.InvalidTree(uriString, "invalid")
        if (!takeSucceeds) {
            return PersistableGrantResult.Failure(valid.uriString, "denied")
        }
        granted.add(valid.uriString)
        return PersistableGrantResult.Success(valid.uriString)
    }

    override fun releaseReadWrite(uriString: String): PersistableGrantResult {
        val valid = StorageAccessPolicy.validateTreeUri(uriString) as? TreeUriValidation.Valid
            ?: return PersistableGrantResult.InvalidTree(uriString, "invalid")
        granted.remove(valid.uriString)
        return PersistableGrantResult.Success(valid.uriString)
    }

    override fun hasReadWrite(uriString: String): Boolean {
        val valid = StorageAccessPolicy.validateTreeUri(uriString) as? TreeUriValidation.Valid
            ?: return false
        return granted.contains(valid.uriString)
    }
}

class FakeUserTreeAccess : UserTreeAccess {
    val inspections = mutableMapOf<String, UserTreeInspection>()
    val created = mutableListOf<CreatedTreeDocument>()
    val written = mutableListOf<Pair<String, File>>()
    val deleted = mutableListOf<String>()
    var createError: IOException? = null
    var writeError: IOException? = null

    override fun inspect(treeUri: String): UserTreeInspection =
        inspections[treeUri] ?: UserTreeInspection(UserTreeState.Missing)

    override fun createFile(treeUri: String, mimeType: String, displayName: String): CreatedTreeDocument {
        createError?.let { throw it }
        val createdDocument = CreatedTreeDocument("content://created/$displayName", displayName)
        created.add(createdDocument)
        return createdDocument
    }

    override fun writeFrom(documentUri: String, source: File) {
        writeError?.let { throw it }
        written.add(documentUri to source)
    }

    override fun deleteDocument(documentUri: String): Boolean {
        deleted.add(documentUri)
        return true
    }
}

/**
 * SharedPreferences stand-in for JVM tests. Only the string keys used by
 * [SaveLocationStore] are implemented.
 */
class InMemoryPreferences : android.content.SharedPreferences {
    private val values = mutableMapOf<String, String>()
    private val listeners = mutableSetOf<android.content.SharedPreferences.OnSharedPreferenceChangeListener>()

    override fun getAll(): MutableMap<String, *> = values.toMutableMap()
    override fun getString(key: String?, defValue: String?): String? = values[key] ?: defValue
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
    override fun getInt(key: String?, defValue: Int): Int = defValue
    override fun getLong(key: String?, defValue: Long): Long = defValue
    override fun getFloat(key: String?, defValue: Float): Float = defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = defValue
    override fun contains(key: String?): Boolean = values.containsKey(key)
    override fun edit(): android.content.SharedPreferences.Editor = Editor()
    override fun registerOnSharedPreferenceChangeListener(
        listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener?,
    ) {
        if (listener != null) listeners.add(listener)
    }
    override fun unregisterOnSharedPreferenceChangeListener(
        listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener?,
    ) {
        listeners.remove(listener)
    }

    private inner class Editor : android.content.SharedPreferences.Editor {
        private val pending = mutableMapOf<String, String?>()
        override fun putString(key: String?, value: String?): android.content.SharedPreferences.Editor {
            if (key != null) pending[key] = value
            return this
        }
        override fun putStringSet(key: String?, values: MutableSet<String>?) = this
        override fun putInt(key: String?, value: Int) = this
        override fun putLong(key: String?, value: Long) = this
        override fun putFloat(key: String?, value: Float) = this
        override fun putBoolean(key: String?, value: Boolean) = this
        override fun remove(key: String?): android.content.SharedPreferences.Editor {
            if (key != null) pending[key] = null
            return this
        }
        override fun clear(): android.content.SharedPreferences.Editor {
            values.clear()
            return this
        }
        override fun commit(): Boolean {
            apply()
            return true
        }
        override fun apply() {
            for ((key, value) in pending) {
                if (value == null) values.remove(key) else values[key] = value
                listeners.forEach { it.onSharedPreferenceChanged(this@InMemoryPreferences, key) }
            }
            pending.clear()
        }
    }
}
