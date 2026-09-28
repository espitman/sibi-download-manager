package com.espitman.sdm.ui

internal data class BrowserTab(
    val id: String,
    val title: String,
    val url: String?,
    val isPrivate: Boolean,
    val desktopSite: Boolean = false,
)

internal data class BrowserTabsSnapshot(
    val tabs: List<BrowserTab>,
    val activeId: String,
) {
    init {
        require(tabs.isNotEmpty())
        require(tabs.any { it.id == activeId })
        require(tabs.map { it.id }.distinct().size == tabs.size)
    }

    val active: BrowserTab get() = tabs.first { it.id == activeId }

    fun select(id: String): BrowserTabsSnapshot =
        if (tabs.none { it.id == id }) this else copy(activeId = id)

    fun add(tab: BrowserTab): BrowserTabsSnapshot =
        copy(tabs = tabs + tab, activeId = tab.id)

    fun close(id: String): BrowserTabsSnapshot {
        if (tabs.size == 1 || tabs.none { it.id == id }) return this
        val index = tabs.indexOfFirst { it.id == id }
        val remaining = tabs.filterNot { it.id == id }
        val nextActive = if (activeId != id) activeId else remaining[(index - 1).coerceAtLeast(0)].id
        return BrowserTabsSnapshot(remaining, nextActive)
    }

    fun update(id: String, url: String?, title: String): BrowserTabsSnapshot = copy(
        tabs = tabs.map { tab ->
            if (tab.id == id) tab.copy(url = url, title = title.ifBlank { tab.title }) else tab
        },
    )

    fun setDesktopSite(id: String, desktopSite: Boolean): BrowserTabsSnapshot = copy(
        tabs = tabs.map { tab ->
            if (tab.id == id) tab.copy(desktopSite = desktopSite) else tab
        },
    )
}

internal fun initialBrowserTabs(): BrowserTabsSnapshot = BrowserTabsSnapshot(
    tabs = listOf(
        BrowserTab("tab-1", "Sibi Media", "https://media.sibicdn.net", isPrivate = false),
        BrowserTab("tab-2", "Internet Archive", "https://archive.org", isPrivate = false),
    ),
    activeId = "tab-1",
)

internal fun createBrowserTab(
    id: String,
    explicitPrivate: Boolean,
): BrowserTab = BrowserTab(
    id,
    if (explicitPrivate) "Private tab" else "New tab",
    null,
    explicitPrivate,
)

internal fun persistableBrowserTabs(tabs: List<BrowserTab>): List<BrowserTab> =
    tabs.filterNot(BrowserTab::isPrivate)

internal fun persistableActiveId(tabs: List<BrowserTab>, previousActiveId: String): String? {
    if (tabs.isEmpty()) return null
    return if (tabs.any { it.id == previousActiveId }) previousActiveId else tabs.last().id
}
