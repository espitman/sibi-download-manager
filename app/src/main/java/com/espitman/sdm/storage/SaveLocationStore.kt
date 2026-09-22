package com.espitman.sdm.storage

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SaveLocationStore internal constructor(private val preferences: SharedPreferences) {
    private val mutableLocation = MutableStateFlow(read())
    val location: StateFlow<PersistedSaveLocation> = mutableLocation.asStateFlow()

    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key == KEY_TREE_URI || key == KEY_LABEL) {
            mutableLocation.value = read()
        }
    }

    init {
        preferences.registerOnSharedPreferenceChangeListener(listener)
    }

    fun read(): PersistedSaveLocation {
        val treeUri = preferences.getString(KEY_TREE_URI, null)?.trim()?.takeIf { it.isNotEmpty() }
        val label = preferences.getString(KEY_LABEL, null)?.trim()?.takeIf { it.isNotEmpty() }
            ?: SaveLocationLabels.DEFAULT
        return PersistedSaveLocation(
            treeUri = treeUri,
            displayLabel = if (treeUri == null) SaveLocationLabels.DEFAULT else label,
        )
    }

    @Synchronized
    fun persistUserTree(treeUri: String, displayLabel: String) {
        val valid = StorageAccessPolicy.validateTreeUri(treeUri) as? TreeUriValidation.Valid
            ?: error("Save location requires a valid OpenDocumentTree URI")
        val label = displayLabel.trim().ifEmpty { SaveLocationLabels.SELECTED_FOLDER_FALLBACK }
        preferences.edit()
            .putString(KEY_TREE_URI, valid.uriString)
            .putString(KEY_LABEL, label)
            .apply()
        mutableLocation.value = PersistedSaveLocation(valid.uriString, label)
    }

    @Synchronized
    fun persistAppSpecific() {
        preferences.edit()
            .remove(KEY_TREE_URI)
            .putString(KEY_LABEL, SaveLocationLabels.DEFAULT)
            .apply()
        mutableLocation.value = PersistedSaveLocation.DEFAULT
    }

    companion object {
        const val KEY_TREE_URI = "save_location_tree_uri"
        const val KEY_LABEL = "save_location_label"

        @Volatile
        private var instance: SaveLocationStore? = null

        fun get(context: Context): SaveLocationStore = instance ?: synchronized(this) {
            instance ?: SaveLocationStore(
                context.applicationContext.getSharedPreferences("sdm_settings", Context.MODE_PRIVATE),
            ).also { instance = it }
        }
    }
}
