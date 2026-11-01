package com.espitman.sdm.data.settings

import com.espitman.sdm.storage.CategoryFolderSettings
import com.espitman.sdm.storage.CategoryFolderStore
import com.espitman.sdm.storage.FolderMutationGate
import kotlinx.coroutines.sync.withLock

/** The native and web reset use the same scope: preferences and category rules only. */
object SettingsReset {
    suspend fun apply(settings: SettingsRepository, categories: CategoryFolderStore) {
        FolderMutationGate.mutex.withLock {
            categories.replace(CategoryFolderSettings())
            settings.reset()
        }
    }
}
