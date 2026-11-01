package com.espitman.sdm.data.settings

import com.espitman.sdm.data.DownloadBackupCodec
import com.espitman.sdm.storage.CategoryFolderStore

/** Restore preferences without creating grants or discarding unavailable folder rules. */
internal object BackupSettingsRestore {
    fun apply(backup: DownloadBackupCodec.Backup, settings: SettingsRepository,
        categories: CategoryFolderStore, hasReadWrite: (String) -> Boolean) {
        val value = backup.settings ?: return
        settings.update { value }
        backup.categoryFolders?.let { folders ->
            categories.replace(folders)
            folders.rules.forEach { (category, rule) ->
                if (!hasReadWrite(rule.treeUri)) categories.markUnavailable(category)
            }
        }
    }
}
