package com.espitman.sdm.ui

import android.annotation.SuppressLint
import android.webkit.WebSettings
import android.webkit.WebView

@SuppressLint("SetJavaScriptEnabled")
internal fun configurePrivateBrowserWebView(webView: WebView) {
    webView.settings.javaScriptEnabled = BrowserWebViewSecurityPolicy.JAVASCRIPT_ENABLED
    webView.settings.domStorageEnabled = BrowserWebViewSecurityPolicy.DOM_STORAGE_ENABLED
    webView.settings.cacheMode = WebSettings.LOAD_NO_CACHE
    webView.settings.setSupportMultipleWindows(BrowserWebViewSecurityPolicy.SUPPORT_MULTIPLE_WINDOWS)
    webView.settings.allowFileAccess = BrowserWebViewSecurityPolicy.ALLOW_FILE_ACCESS
    webView.settings.allowContentAccess = BrowserWebViewSecurityPolicy.ALLOW_CONTENT_ACCESS
    @Suppress("DEPRECATION")
    webView.settings.allowFileAccessFromFileURLs =
        BrowserWebViewSecurityPolicy.ALLOW_FILE_ACCESS_FROM_FILE_URLS
    @Suppress("DEPRECATION")
    webView.settings.allowUniversalAccessFromFileURLs =
        BrowserWebViewSecurityPolicy.ALLOW_UNIVERSAL_ACCESS_FROM_FILE_URLS
    @Suppress("DEPRECATION")
    webView.settings.saveFormData = BrowserWebViewSecurityPolicy.SAVE_FORM_DATA
    webView.clearCache(false)
}
