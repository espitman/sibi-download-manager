package com.espitman.sdm.notification

import android.content.Context

/** Persists that the POST_NOTIFICATIONS system prompt was already issued. */
class NotificationPermissionStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE,
    )

    fun hasIssuedRuntimePrompt(): Boolean =
        preferences.getBoolean(KEY_RUNTIME_PROMPT_ISSUED, false)

    fun markRuntimePromptIssued() {
        preferences.edit().putBoolean(KEY_RUNTIME_PROMPT_ISSUED, true).apply()
    }

    private companion object {
        const val PREFS_NAME = "sdm_notification_permission"
        const val KEY_RUNTIME_PROMPT_ISSUED = "runtime_prompt_issued"
    }
}
