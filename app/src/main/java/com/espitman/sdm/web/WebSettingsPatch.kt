package com.espitman.sdm.web

import com.espitman.sdm.data.settings.SdmSettings
import com.espitman.sdm.domain.AutomaticRetrySettings
import org.json.JSONObject

/** Validate the whole patch before writing any phone preference. */
internal object WebSettingsPatch {
    fun apply(current: SdmSettings, body: JSONObject): SdmSettings {
        val allowed = setOf("connections", "simultaneous", "retryCount", "retryDelaySeconds", "wifiOnly",
            "autoResume", "downloadComplete", "speedAlerts", "unlimitedSpeed", "speedLimitMbps", "speedLimitWifiOnly", "theme",
            "dailyBulkScheduleEnabled", "dailyResumeMinute", "dailyPauseMinute")
        require(body.keys().asSequence().all { it in allowed }) { "Unknown setting" }
        fun integer(key: String, fallback: Int): Int {
            if (!body.has(key)) return fallback
            val value = body.get(key)
            require(value is Number && value.toDouble().isFinite() && value.toDouble() == value.toInt().toDouble()) { "Invalid $key" }
            return value.toInt()
        }
        fun bool(key: String, fallback: Boolean): Boolean {
            if (!body.has(key)) return fallback
            val value = body.get(key)
            require(value is Boolean) { "Invalid $key" }
            return value
        }
        val limit = if (body.has("speedLimitMbps")) {
            val value = body.get("speedLimitMbps")
            require(value is Number) { "Invalid speed limit" }
            value.toFloat()
        } else current.speedLimitMbps
        val theme = if (body.has("theme")) {
            val value = body.get("theme")
            require(value is String) { "Invalid theme" }
            value
        } else current.theme
        val result = current.copy(
            connections = integer("connections", current.connections),
            simultaneous = integer("simultaneous", current.simultaneous),
            retryCount = integer("retryCount", current.retryCount),
            retryDelaySeconds = integer("retryDelaySeconds", current.retryDelaySeconds),
            wifiOnly = bool("wifiOnly", current.wifiOnly), autoResume = bool("autoResume", current.autoResume),
            downloadComplete = bool("downloadComplete", current.downloadComplete), speedAlerts = bool("speedAlerts", current.speedAlerts),
            unlimitedSpeed = bool("unlimitedSpeed", current.unlimitedSpeed), speedLimitMbps = limit,
            speedLimitWifiOnly = bool("speedLimitWifiOnly", current.speedLimitWifiOnly), theme = theme,
            dailyBulkScheduleEnabled = bool("dailyBulkScheduleEnabled", current.dailyBulkScheduleEnabled),
            dailyResumeMinute = integer("dailyResumeMinute", current.dailyResumeMinute),
            dailyPauseMinute = integer("dailyPauseMinute", current.dailyPauseMinute),
        )
        require(result.connections in listOf(8, 16, 24, 32)) { "Connections must be 8, 16, 24, or 32" }
        require(result.simultaneous in 1..10) { "Simultaneous downloads must be between 1 and 10" }
        require(result.retryCount in 0..10) { "Retry count must be between 0 and 10" }
        require(result.retryDelaySeconds in AutomaticRetrySettings.DELAYS) { "Choose a retry delay up to 3 minutes" }
        require(result.speedLimitMbps.isFinite() && result.speedLimitMbps in 1f..30f) { "Speed limit must be between 1 and 30 MB/s" }
        require(result.theme in listOf("dark", "light")) { "Invalid theme" }
        require(result.dailyResumeMinute in 0..1439 && result.dailyPauseMinute in 0..1439 && result.dailyResumeMinute != result.dailyPauseMinute) { "Choose different valid resume and pause times" }
        return result
    }
}
