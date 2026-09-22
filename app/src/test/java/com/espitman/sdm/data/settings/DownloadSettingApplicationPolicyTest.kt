package com.espitman.sdm.data.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadSettingApplicationPolicyTest {
    @Test
    fun activePoliciesApplyImmediately() {
        listOf(
            DownloadSettingKey.WIFI_ONLY,
            DownloadSettingKey.AUTO_RESUME,
            DownloadSettingKey.DOWNLOAD_COMPLETE,
            DownloadSettingKey.SPEED_ALERTS,
            DownloadSettingKey.SPEED_LIMIT,
            DownloadSettingKey.THEME,
        ).forEach { key ->
            assertEquals(SettingApplicationTiming.IMMEDIATE, DownloadSettingApplicationPolicy.timing(key))
        }
    }

    @Test
    fun admissionTransferAndSubmissionPoliciesHaveExplicitBoundaries() {
        assertEquals(
            SettingApplicationTiming.NEXT_QUEUE_ADMISSION,
            DownloadSettingApplicationPolicy.timing(DownloadSettingKey.SIMULTANEOUS),
        )
        assertEquals(
            SettingApplicationTiming.NEXT_TRANSFER,
            DownloadSettingApplicationPolicy.timing(DownloadSettingKey.CONNECTIONS),
        )
        assertEquals(
            SettingApplicationTiming.FUTURE_DOWNLOADS,
            DownloadSettingApplicationPolicy.timing(DownloadSettingKey.SAVE_LOCATION),
        )
    }
}
