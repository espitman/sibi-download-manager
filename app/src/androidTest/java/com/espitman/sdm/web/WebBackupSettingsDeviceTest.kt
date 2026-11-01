package com.espitman.sdm.web

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.espitman.sdm.data.AppRepositories
import com.espitman.sdm.data.DownloadBackupCodec
import com.espitman.sdm.data.settings.*
import com.espitman.sdm.storage.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class WebBackupSettingsDeviceTest {
    @Test fun sharedRestoreKeepsUnavailableRulesWithoutChangingProductionData() = runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val key="backup-settings-${UUID.randomUUID()}"
        val prefs=context.getSharedPreferences(key,0)
        val categoryPrefs=context.getSharedPreferences("$key-categories",0)
        val settings=SettingsRepository(prefs)
        val categories=CategoryFolderStore(categoryPrefs)
        val repo=AppRepositories.downloads(context);repo.awaitInitialized()
        val originals=repo.downloads.value
        val production=SettingsRepository.get(context).settings.value
        val productionCategories=CategoryFolderStore.get(context).settings.value
        try {
            val restoredSettings=SdmSettings().copy(theme="light",connections=32,retryCount=0)
            val rules=CategoryFolderSettings(enabled=true,rules=mapOf(
                FileCategory.VIDEO to CategoryFolderRule("content://fixture/tree/video","Video"),
                FileCategory.AUDIO to CategoryFolderRule("content://fixture/tree/audio","Audio")))
            val encoded=DownloadBackupCodec.encode(emptyList(),restoredSettings,System.currentTimeMillis(),rules)
            val backup=DownloadBackupCodec.decode(encoded)
            val checked=mutableListOf<String>()
            BackupSettingsRestore.apply(backup,settings,categories) { uri -> checked+=uri;uri.endsWith("video") }
            assertEquals(restoredSettings,settings.settings.value)
            assertEquals(rules,categories.settings.value)
            assertEquals(setOf(FileCategory.AUDIO),categories.unavailable.value)
            val storage=WebStorageSettings(context,WebFolders(context),categoryStore=categories).snapshot().getJSONArray("categories")
            val audio=(0 until storage.length()).map{storage.getJSONObject(it)}.single{it.getString("id")=="AUDIO"}
            assertTrue(audio.getBoolean("unavailable"))
            assertEquals(rules.rules.values.map { it.treeUri }.toSet(),checked.toSet())
            // Restoring after access becomes available clears the old unavailable indication.
            BackupSettingsRestore.apply(backup,settings,categories) { true }
            assertTrue(categories.unavailable.value.isEmpty())
            // Legacy settings-only backups retain existing category rules.
            BackupSettingsRestore.apply(DownloadBackupCodec.Backup(emptyList(),SdmSettings()),settings,categories) { error("No rule to probe") }
            assertEquals(SdmSettings(),settings.settings.value);assertEquals(rules,categories.settings.value)
            // Download-only backups do not reset preferences or category folders.
            BackupSettingsRestore.apply(DownloadBackupCodec.Backup(emptyList(),null),settings,categories) { error("No settings selected") }
            assertEquals(SdmSettings(),settings.settings.value);assertEquals(rules,categories.settings.value)
            assertEquals(production,SettingsRepository.get(context).settings.value)
            assertEquals(productionCategories,CategoryFolderStore.get(context).settings.value)
            assertEquals(originals,repo.downloads.value)
        } finally { prefs.edit().clear().commit();categoryPrefs.edit().clear().commit() }
    }
}
