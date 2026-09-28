package com.espitman.sdm.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class BrowserTabsTest {
    @Test fun initialTabsAndCounterMatchTheReference() {
        val state = initialBrowserTabs()
        assertEquals(2, state.tabs.size)
        assertEquals("Sibi Media", state.active.title)
        assertEquals("https://media.sibicdn.net", state.active.url)
        assertEquals(false, state.tabs.any { it.isPrivate })
    }

    @Test fun addSelectUpdateAndCloseMaintainARealActiveTab() {
        val initial = initialBrowserTabs()
        val added = initial.add(BrowserTab("tab-3", "New tab", null, isPrivate = false))
        assertEquals(3, added.tabs.size)
        assertEquals("tab-3", added.activeId)

        val updated = added.update("tab-3", "https://example.com", "Example")
        assertEquals("Example", updated.active.title)
        assertEquals("https://example.com", updated.active.url)

        val selected = updated.select("tab-2")
        assertEquals("tab-2", selected.activeId)
        val closed = selected.close("tab-2")
        assertEquals("tab-1", closed.activeId)
        assertEquals(2, closed.tabs.size)
    }

    @Test fun updateWritesOnlyTheOwningTab() {
        val selected = initialBrowserTabs().select("tab-2")
        val updated = selected.update("tab-1", "https://slow.example", "Slow")
        assertEquals("tab-2", updated.activeId)
        assertEquals("https://archive.org", updated.active.url)
        assertEquals("Internet Archive", updated.active.title)
        assertEquals("https://slow.example", updated.tabs.first { it.id == "tab-1" }.url)
        assertEquals("Slow", updated.tabs.first { it.id == "tab-1" }.title)
    }

    @Test fun closingLastTabIsRejected() {
        val one = BrowserTabsSnapshot(listOf(BrowserTab("only", "New tab", null, true)), "only")
        assertEquals(one, one.close("only"))
    }

    @Test fun persistableTabsDropPrivateSessionsAndKeepTheRegularActiveTab() {
        val snapshot = BrowserTabsSnapshot(
            listOf(
                BrowserTab("tab-1", "Example", "https://example.com", isPrivate = false),
                BrowserTab("tab-2", "Private tab", "https://secret.example", isPrivate = true),
            ),
            "tab-2",
        )
        val persistable = persistableBrowserTabs(snapshot.tabs)
        assertEquals(listOf("tab-1"), persistable.map { it.id })
        assertEquals("tab-1", persistableActiveId(persistable, snapshot.activeId))
        assertEquals(listOf("tab-1", "tab-2"), persistableBrowserTabs(initialBrowserTabs().tabs).map { it.id })
    }

    @Test fun newTabIsAlwaysStandardAndPrivateTabIsAlwaysPrivate() {
        val standard = createBrowserTab("tab-3", explicitPrivate = false)
        assertEquals(false, standard.isPrivate)
        assertEquals("New tab", standard.title)
        assertEquals(false, standard.desktopSite)
        val privateTab = createBrowserTab("tab-5", explicitPrivate = true)
        assertEquals(true, privateTab.isPrivate)
        assertEquals("Private tab", privateTab.title)
        assertEquals(false, privateTab.desktopSite)
    }

    @Test fun desktopSiteStaysOnTheOwningTabWhenSwitching() {
        val initial = initialBrowserTabs()
        val desktop = initial.setDesktopSite("tab-1", true)
        assertEquals(true, desktop.tabs.first { it.id == "tab-1" }.desktopSite)
        assertEquals(false, desktop.tabs.first { it.id == "tab-2" }.desktopSite)
        val selected = desktop.select("tab-2")
        assertEquals("tab-2", selected.activeId)
        assertEquals(false, selected.active.desktopSite)
        assertEquals(true, selected.tabs.first { it.id == "tab-1" }.desktopSite)
        val added = selected.add(createBrowserTab("tab-3", explicitPrivate = false))
        assertEquals(false, added.active.desktopSite)
        assertEquals(true, added.tabs.first { it.id == "tab-1" }.desktopSite)
        val updated = added.update("tab-1", "https://example.com", "Example")
        assertEquals(true, updated.tabs.first { it.id == "tab-1" }.desktopSite)
        assertEquals("Example", updated.tabs.first { it.id == "tab-1" }.title)
    }
}
