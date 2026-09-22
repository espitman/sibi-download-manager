package com.espitman.sdm.ui

/** Explicit WebView flags for the private Browser. JavaScript stays on for ordinary pages. */
internal object BrowserWebViewSecurityPolicy {
    const val JAVASCRIPT_ENABLED = true
    const val DOM_STORAGE_ENABLED = true
    const val ALLOW_FILE_ACCESS = false
    const val ALLOW_CONTENT_ACCESS = false
    const val ALLOW_FILE_ACCESS_FROM_FILE_URLS = false
    const val ALLOW_UNIVERSAL_ACCESS_FROM_FILE_URLS = false
    const val SUPPORT_MULTIPLE_WINDOWS = false
    const val SAVE_FORM_DATA = false
}
