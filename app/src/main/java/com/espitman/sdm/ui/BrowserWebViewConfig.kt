package com.espitman.sdm.ui

import android.annotation.SuppressLint
import android.webkit.WebSettings
import android.webkit.WebView

@SuppressLint("SetJavaScriptEnabled")
internal fun configurePrivateBrowserWebView(webView: WebView) {
    webView.settings.javaScriptEnabled = true
    webView.settings.domStorageEnabled = true
    webView.settings.cacheMode = WebSettings.LOAD_NO_CACHE
    webView.settings.setSupportMultipleWindows(false)
    @Suppress("DEPRECATION")
    webView.settings.saveFormData = false
    webView.clearCache(false)
}
