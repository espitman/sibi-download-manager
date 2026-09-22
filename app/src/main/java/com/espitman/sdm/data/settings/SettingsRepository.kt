package com.espitman.sdm.data.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Persisted preferences shared by Settings, quick preferences, and the transfer engine. */
data class SdmSettings(
    val connections: Int = 16,
    val simultaneous: Int = 3,
    val autoResume: Boolean = true,
    val wifiOnly: Boolean = true,
    val downloadComplete: Boolean = true,
    val speedAlerts: Boolean = false,
    val theme: String = "dark",
    val keepActive: Boolean = false,
    val keepActiveDuration: String = "downloading",
    val unlimitedSpeed: Boolean = false,
    val speedLimitMbps: Float = 10f,
    val speedLimitWifiOnly: Boolean = false,
)

/** Retains the original preference file and keys, so no destructive migration is needed. */
class SettingsRepository internal constructor(private val preferences: SharedPreferences) {
    private val mutableSettings = MutableStateFlow(read())
    val settings: StateFlow<SdmSettings> = mutableSettings.asStateFlow()
    // Kept strongly referenced for the lifetime of the application singleton.
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        mutableSettings.value = read()
    }

    init { preferences.registerOnSharedPreferenceChangeListener(listener) }

    @Synchronized
    fun update(transform: (SdmSettings) -> SdmSettings) {
        val value = transform(read())
        require(value.connections in listOf(8, 16, 24, 32))
        require(value.simultaneous in 1..10)
        require(value.theme in listOf("dark", "light"))
        require(value.keepActiveDuration in listOf("downloading", "queue"))
        require(value.speedLimitMbps.isFinite() && value.speedLimitMbps in 1f..30f)
        preferences.edit()
            .putInt("connections", value.connections)
            .putInt("simultaneous", value.simultaneous)
            .putBoolean("auto_resume", value.autoResume)
            .putBoolean("wifi_only", value.wifiOnly)
            .putBoolean("download_complete", value.downloadComplete)
            .putBoolean("speed_alerts", value.speedAlerts)
            .putString("theme", value.theme)
            // Keep-active was removed from the product. Delete legacy values so an
            // old installation cannot silently reactivate it in the engine.
            .remove("keep_active")
            .remove("keep_active_duration")
            .putBoolean("unlimited_speed", value.unlimitedSpeed)
            .putFloat("speed_limit_mbps", value.speedLimitMbps)
            .putBoolean("speed_limit_wifi_only", value.speedLimitWifiOnly)
            .apply()
        mutableSettings.value = value
    }

    fun reset() = update { SdmSettings() }

    private fun read() = SdmSettings(
        connections = preferences.getInt("connections", 16),
        simultaneous = preferences.getInt("simultaneous", 3),
        autoResume = preferences.getBoolean("auto_resume", true),
        wifiOnly = preferences.getBoolean("wifi_only", true),
        downloadComplete = preferences.getBoolean("download_complete", true),
        speedAlerts = preferences.getBoolean("speed_alerts", false),
        theme = preferences.getString("theme", "dark") ?: "dark",
        keepActive = false,
        keepActiveDuration = "downloading",
        unlimitedSpeed = preferences.getBoolean("unlimited_speed", false),
        speedLimitMbps = preferences.getFloat("speed_limit_mbps", 10f),
        speedLimitWifiOnly = preferences.getBoolean("speed_limit_wifi_only", false),
    )

    companion object {
        @Volatile private var instance: SettingsRepository? = null
        fun get(context: Context): SettingsRepository = instance ?: synchronized(this) {
            instance ?: SettingsRepository(context.applicationContext.getSharedPreferences("sdm_settings", Context.MODE_PRIVATE))
                .also { instance = it }
        }
    }
}
