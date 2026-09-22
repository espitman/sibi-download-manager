package com.espitman.sdm.ui

internal data class BrowserPrivacyPolicy(
    val persistHistory: Boolean,
    val persistCache: Boolean,
    val clearCookiesAtSessionEnd: Boolean,
    val clearWebStorageAtSessionEnd: Boolean,
) {
    companion object {
        val Private = BrowserPrivacyPolicy(
            persistHistory = false,
            persistCache = false,
            clearCookiesAtSessionEnd = true,
            clearWebStorageAtSessionEnd = true,
        )
    }
}
