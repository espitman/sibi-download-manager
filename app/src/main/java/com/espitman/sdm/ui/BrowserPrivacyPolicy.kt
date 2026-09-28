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
        val Regular = BrowserPrivacyPolicy(
            persistHistory = true,
            persistCache = true,
            clearCookiesAtSessionEnd = false,
            clearWebStorageAtSessionEnd = false,
        )

        fun forTab(isPrivate: Boolean): BrowserPrivacyPolicy = if (isPrivate) Private else Regular
    }
}
