package com.espitman.sdm.ui

internal data class BrowserPendingNavigation(
    val tabId: String,
    val url: String,
)

/** Owns WebViewClient side effects so a background tab cannot rewrite the active chrome. */
internal object BrowserWebViewCallbackPolicy {
    fun appliesToActiveChrome(callbackTabId: String, activeTabId: String): Boolean =
        callbackTabId == activeTabId

    fun appliesToActiveLoadFailure(
        callbackTabId: String,
        activeTabId: String,
        isForMainFrame: Boolean,
    ): Boolean = isForMainFrame && appliesToActiveChrome(callbackTabId, activeTabId)

    fun shouldRecordFinishedVisit(owningTabIsPrivate: Boolean): Boolean =
        shouldRecordBrowserHistory(
            isPrivate = owningTabIsPrivate,
            persistHistory = BrowserPrivacyPolicy.forTab(owningTabIsPrivate).persistHistory,
        )

    fun resolvedTabTitle(webViewTitle: String?, existingTitle: String?, url: String): String {
        val fromView = webViewTitle?.trim()?.takeIf { it.isNotEmpty() }
        if (fromView != null) return fromView
        val existing = existingTitle?.trim()?.takeIf { it.isNotEmpty() }
        if (existing != null) return existing
        return url
    }

    /**
     * AndroidView.update may load only an explicit navigation for the visible tab.
     * A chrome URL that drifted because of a redirect or a background tab finishing
     * is not a load request.
     */
    fun urlToLoadOnComposeUpdate(
        pending: BrowserPendingNavigation?,
        visibleTabId: String,
        viewUrl: String?,
    ): String? {
        if (pending == null || pending.tabId != visibleTabId) return null
        val requested = pending.url.trim()
        if (requested.isEmpty() || requested == viewUrl) return null
        return requested
    }

    fun shouldConsumePendingNavigation(
        pending: BrowserPendingNavigation?,
        visibleTabId: String,
    ): Boolean = pending != null && pending.tabId == visibleTabId
}
