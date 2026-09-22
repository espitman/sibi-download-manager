package com.espitman.sdm.data.settings

enum class SettingApplicationTiming {
    IMMEDIATE,
    NEXT_QUEUE_ADMISSION,
    NEXT_TRANSFER,
    FUTURE_DOWNLOADS,
}

enum class DownloadSettingKey {
    CONNECTIONS,
    SIMULTANEOUS,
    AUTO_RESUME,
    WIFI_ONLY,
    DOWNLOAD_COMPLETE,
    SPEED_ALERTS,
    SPEED_LIMIT,
    SAVE_LOCATION,
    THEME,
}

/** Single contract used by documentation and regression tests for settings timing. */
object DownloadSettingApplicationPolicy {
    fun timing(key: DownloadSettingKey): SettingApplicationTiming = when (key) {
        DownloadSettingKey.WIFI_ONLY,
        DownloadSettingKey.AUTO_RESUME,
        DownloadSettingKey.DOWNLOAD_COMPLETE,
        DownloadSettingKey.SPEED_ALERTS,
        DownloadSettingKey.SPEED_LIMIT,
        DownloadSettingKey.THEME,
        -> SettingApplicationTiming.IMMEDIATE

        DownloadSettingKey.SIMULTANEOUS -> SettingApplicationTiming.NEXT_QUEUE_ADMISSION
        DownloadSettingKey.CONNECTIONS -> SettingApplicationTiming.NEXT_TRANSFER
        DownloadSettingKey.SAVE_LOCATION -> SettingApplicationTiming.FUTURE_DOWNLOADS
    }
}
