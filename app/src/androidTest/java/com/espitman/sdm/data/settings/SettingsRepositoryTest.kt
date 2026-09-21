package com.espitman.sdm.data.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val preferences = context.getSharedPreferences("settings_repository_test", Context.MODE_PRIVATE)

    @Before fun clearBefore() { preferences.edit().clear().commit() }
    @After fun clearAfter() { preferences.edit().clear().commit() }

    @Test fun existingValuesAreRetainedAndUpdatesSurviveRepositoryRecreation() {
        preferences.edit().putInt("connections", 24).putInt("simultaneous", 5)
            .putBoolean("wifi_only", false).putBoolean("auto_resume", false)
            .putBoolean("download_complete", false).putBoolean("speed_alerts", true)
            .putString("theme", "light").commit()
        val repository = SettingsRepository(preferences)
        assertEquals(SdmSettings(connections = 24, simultaneous = 5, wifiOnly = false,
            autoResume = false, downloadComplete = false, speedAlerts = true, theme = "light"), repository.settings.value)
        repository.update { it.copy(keepActiveDuration = "queue", speedLimitMbps = 20f, speedLimitWifiOnly = true) }
        // commit flushes all previously scheduled apply writes before recreating the reader.
        assertTrue(preferences.edit().commit())
        assertEquals(repository.settings.value, SettingsRepository(preferences).settings.value)
        assertEquals(24, preferences.getInt("connections", 0))
        assertFalse(preferences.getBoolean("wifi_only", true))
    }

    @Test fun resetPublishesAllDefaultsToEveryConsumerAndPersistsThem() {
        val first = SettingsRepository(preferences)
        val second = SettingsRepository(preferences)
        // SharedPreferences listeners run on the main thread, as do app preference actions.
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync {
            first.update { it.copy(theme = "light", connections = 32, keepActive = false,
                unlimitedSpeed = true, speedLimitMbps = 25f) }
            assertEquals(first.settings.value, second.settings.value)
            second.reset()
            assertEquals(SdmSettings(), first.settings.value)
            assertEquals(SdmSettings(), second.settings.value)
        }
        assertTrue(preferences.edit().commit())
        assertEquals(SdmSettings(), SettingsRepository(preferences).settings.value)
    }
}
