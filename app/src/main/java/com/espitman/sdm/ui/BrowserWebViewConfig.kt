package com.espitman.sdm.ui

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.FrameLayout

@SuppressLint("SetJavaScriptEnabled")
internal fun configurePrivateBrowserWebView(webView: WebView) {
    configureBrowserWebView(webView, isPrivate = true)
}

@SuppressLint("SetJavaScriptEnabled")
internal fun configureBrowserWebView(
    webView: WebView,
    isPrivate: Boolean,
    desktopSite: Boolean = false,
) {
    webView.settings.javaScriptEnabled = BrowserWebViewSecurityPolicy.JAVASCRIPT_ENABLED
    webView.settings.domStorageEnabled = BrowserWebViewSecurityPolicy.DOM_STORAGE_ENABLED
    webView.settings.cacheMode =
        if (BrowserPrivacyPolicy.forTab(isPrivate).persistCache) {
            WebSettings.LOAD_DEFAULT
        } else {
            WebSettings.LOAD_NO_CACHE
        }
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
    applyBrowserWebViewDisplayMode(webView, desktopSite)
    if (isPrivate) webView.clearCache(false)
}

internal fun applyBrowserWebViewDisplayMode(webView: WebView, desktopSite: Boolean) {
    val settings = webView.settings
    val defaultUserAgent = WebSettings.getDefaultUserAgent(webView.context)
    settings.userAgentString = BrowserWebViewDisplayPolicy.userAgent(desktopSite, defaultUserAgent)
    settings.useWideViewPort = BrowserWebViewDisplayPolicy.USE_WIDE_VIEWPORT
    settings.loadWithOverviewMode = BrowserWebViewDisplayPolicy.loadWithOverviewMode(desktopSite)
    settings.builtInZoomControls = BrowserWebViewDisplayPolicy.BUILT_IN_ZOOM_CONTROLS
    settings.displayZoomControls = BrowserWebViewDisplayPolicy.DISPLAY_ZOOM_CONTROLS
}

internal fun constrainBrowserWebViewWidthSpec(widthMeasureSpec: Int, screenWidthPx: Int): Int {
    val width = BrowserWebViewDisplayPolicy.constrainedWidthPx(
        requestedPx = View.MeasureSpec.getSize(widthMeasureSpec),
        unbounded = View.MeasureSpec.getMode(widthMeasureSpec) == View.MeasureSpec.UNSPECIFIED,
        screenWidthPx = screenWidthPx,
    )
    return View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY)
}

internal fun hostPrivateBrowserWebView(context: Context, webView: WebView): FrameLayout {
    (webView.parent as? ViewGroup)?.removeView(webView)
    return object : FrameLayout(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(
                constrainBrowserWebViewWidthSpec(
                    widthMeasureSpec,
                    resources.displayMetrics.widthPixels,
                ),
                heightMeasureSpec,
            )
        }
    }.apply {
        clipChildren = BrowserWebViewLayoutPolicy.CLIP_HOST_CHILDREN
        clipToPadding = BrowserWebViewLayoutPolicy.CLIP_HOST_CHILDREN
        addView(
            webView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
    }
}
