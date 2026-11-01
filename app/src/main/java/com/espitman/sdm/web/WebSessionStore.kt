package com.espitman.sdm.web

internal data class StoredWebBrowser(val browser: WebAccess.Browser, val secret: String)
internal interface WebSessionStore {
    fun load(): List<StoredWebBrowser>
    fun save(sessions: List<StoredWebBrowser>)
}
