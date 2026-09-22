package com.espitman.sdm.storage

import java.io.File

/**
 * Reports capacity for the persisted active save location without changing it.
 * Invalid or unreadable trees stay [StorageCapacity.Unknown] instead of
 * substituting a different volume's figures.
 */
class SaveLocationStorageCapacity(
    private val currentLocation: () -> PersistedSaveLocation,
    private val appSpecificDirectory: () -> File,
    private val probe: StorageCapacityProbe,
) {
    fun queryActive(): StorageCapacity {
        val persisted = currentLocation()
        val treeUri = persisted.treeUri
        if (!treeUri.isNullOrBlank()) {
            return probe.queryTree(treeUri)
        }
        return try {
            probe.queryLocalPath(appSpecificDirectory())
        } catch (_: Exception) {
            StorageCapacity.Unknown
        }
    }
}
