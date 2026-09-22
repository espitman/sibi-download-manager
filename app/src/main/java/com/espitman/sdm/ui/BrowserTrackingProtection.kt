package com.espitman.sdm.ui

import java.net.URI

internal object BrowserTrackingProtection {
    private val blockedDomains = setOf(
        "google-analytics.com",
        "googletagmanager.com",
        "doubleclick.net",
        "facebook.net",
        "connect.facebook.net",
        "segment.io",
        "mixpanel.com",
        "hotjar.com",
    )

    fun shouldBlock(url: String): Boolean {
        val host = runCatching { URI(url).host?.lowercase() }.getOrNull() ?: return false
        return blockedDomains.any { host == it || host.endsWith(".$it") }
    }
}
