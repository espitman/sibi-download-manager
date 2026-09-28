package com.espitman.sdm.ui

internal object BrowserWebViewDisplayPolicy {
    const val USE_WIDE_VIEWPORT = true
    const val BUILT_IN_ZOOM_CONTROLS = true
    const val DISPLAY_ZOOM_CONTROLS = false

    const val DESKTOP_USER_AGENT =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    const val FALLBACK_MOBILE_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Version/4.0 Chrome/124.0.0.0 Mobile Safari/537.36"

    fun isMobileUserAgent(userAgent: String): Boolean =
        userAgent.contains("Mobile", ignoreCase = true)

    fun isDesktopUserAgent(userAgent: String): Boolean =
        userAgent.contains("X11", ignoreCase = true) ||
            (userAgent.contains("Windows", ignoreCase = true) && !isMobileUserAgent(userAgent)) ||
            (userAgent.contains("Macintosh", ignoreCase = true) && !isMobileUserAgent(userAgent)) ||
            (userAgent.contains("Linux x86_64", ignoreCase = true) && !isMobileUserAgent(userAgent))

    fun mobileUserAgent(defaultUserAgent: String): String {
        val ua = defaultUserAgent.trim().ifEmpty { FALLBACK_MOBILE_USER_AGENT }
        if (isMobileUserAgent(ua)) return ua
        return if (ua.contains("Safari", ignoreCase = true)) {
            ua.replace(SAFARI_TOKEN, "Mobile Safari")
        } else {
            "$ua Mobile"
        }
    }

    fun userAgent(desktopSite: Boolean, defaultUserAgent: String): String =
        if (desktopSite) DESKTOP_USER_AGENT else mobileUserAgent(defaultUserAgent)

    fun loadWithOverviewMode(desktopSite: Boolean): Boolean = !desktopSite

    fun constrainedWidthPx(requestedPx: Int, unbounded: Boolean, screenWidthPx: Int): Int {
        val screen = screenWidthPx.coerceAtLeast(1)
        if (unbounded || requestedPx <= 0 || requestedPx > screen) return screen
        return requestedPx
    }
}

private val SAFARI_TOKEN = Regex("Safari", RegexOption.IGNORE_CASE)
