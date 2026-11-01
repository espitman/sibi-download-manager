package com.espitman.sdm.web

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.espitman.sdm.data.AppRepositories
import com.espitman.sdm.data.settings.*
import com.espitman.sdm.storage.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class WebSettingsResetDeviceTest {
    @Test fun sharedResetRestoresDefaultsWithoutChangingProductionSettingsOrDownloads() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val key = "reset-fixture-${UUID.randomUUID()}"
        val prefs = context.getSharedPreferences(key, 0)
        val categoryPrefs = context.getSharedPreferences("$key-category", 0)
        val repository = AppRepositories.downloads(context)
        repository.awaitInitialized()
        val original = repository.downloads.value
        val production = SettingsRepository.get(context).settings.value
        val productionCategories = CategoryFolderStore.get(context).settings.value
        WebPanelRuntime.initialize(context)
        val access = WebPanelRuntime.access
        val (pending, ticket) = access.request("SDM reset fixture", "127.0.0.1")
        check(access.approve(pending.id))
        val secret = checkNotNull(access.collect(pending.id, ticket))
        val browser = access.authorize(secret)
        val bridge = WebAppBridge(context)
        val router = WebRouter(access, { "Fixture" }, { null }, bridge::handle)
        val request = LanHttpServer.Request("POST", "/api/settings/reset", mapOf("authorization" to "Bearer $secret", "idempotency-key" to UUID.randomUUID().toString(), "content-type" to "application/json"), "{}".toByteArray(), "127.0.0.1")
        val settings = SettingsRepository(prefs)
        val categories = CategoryFolderStore(categoryPrefs)
        try {
            assertEquals(400, router.handle(request).status)
            assertEquals(401, router.handle(request.copy(headers=request.headers - "authorization")).status)
            settings.update { it.copy(theme="light", connections=32, retryCount=0, dailyBulkScheduleEnabled=true) }
            categories.setEnabled(true)
            categories.save(FileCategory.VIDEO, CategoryFolderRule("content://fixture/tree/video", "Video"))
            SettingsReset.apply(settings, categories)
            assertEquals(SdmSettings(), settings.settings.value)
            assertEquals(CategoryFolderSettings(), categories.settings.value)
            SettingsReset.apply(settings, categories)
            assertEquals(SdmSettings(), settings.settings.value)
            assertEquals(production, SettingsRepository.get(context).settings.value)
            assertEquals(productionCategories, CategoryFolderStore.get(context).settings.value)
            assertEquals(original, repository.downloads.value)
        } finally { access.revoke(browser.id); bridge.close(); prefs.edit().clear().commit(); categoryPrefs.edit().clear().commit() }
    }
}
