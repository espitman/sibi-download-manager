package com.espitman.sdm.web

import com.espitman.sdm.data.settings.SdmSettings
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WebSettingsPatchTest {
    @Test fun partialPatchPreservesUnrelatedPhonePreferences() {
        val current = SdmSettings(dailyBulkScheduleEnabled = true, dailyResumeMinute = 200, autoResume = false)
        val result = WebSettingsPatch.apply(current, JSONObject().put("connections", 32).put("retryDelaySeconds", 180))
        assertEquals(current.copy(connections = 32, retryDelaySeconds = 180), result)
    }
    @Test fun rejectsCoercionRemovedOptionsAndUnsupportedValues() {
        val patches = listOf(
            JSONObject().put("connections", "32"), JSONObject().put("connections", 31),
            JSONObject().put("simultaneous", 1.5), JSONObject().put("wifiOnly", "false"),
            JSONObject().put("retryDelaySeconds", 147), JSONObject().put("retryDelaySeconds", 190),
            JSONObject().put("keepActive", true), JSONObject().put("theme", "system"),
            JSONObject().put("speedLimitMbps", 0), JSONObject().put("connections", JSONObject.NULL),
            JSONObject().put("dailyResumeMinute", 1440), JSONObject().put("dailyPauseMinute", 60),
            JSONObject().put("dailyResumeMinute", 1.5), JSONObject().put("dailyBulkScheduleEnabled", "true"),
        )
        patches.forEach { patch ->
            try { WebSettingsPatch.apply(SdmSettings(), patch); fail("Accepted invalid patch: $patch") }
            catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun dailyWindowAllowsOvernightAndPreservesOtherPreferences() {
        val result = WebSettingsPatch.apply(SdmSettings(), JSONObject().put("dailyBulkScheduleEnabled", true).put("dailyResumeMinute", 1380).put("dailyPauseMinute", 60))
        assertEquals(SdmSettings(dailyBulkScheduleEnabled = true, dailyResumeMinute = 1380, dailyPauseMinute = 60), result)
    }
}
