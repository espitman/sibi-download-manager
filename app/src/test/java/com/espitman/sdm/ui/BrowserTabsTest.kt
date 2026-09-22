package com.espitman.sdm.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class BrowserTabsTest {
    @Test fun initialTabsAndCounterMatchTheReference() {
        val state = initialBrowserTabs()
        assertEquals(2, state.tabs.size)
        assertEquals("Sibi Media", state.active.title)
        assertEquals("https://media.sibicdn.net", state.active.url)
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

    @Test fun closingLastTabIsRejected() {
        val one = BrowserTabsSnapshot(listOf(BrowserTab("only", "New tab", null, true)), "only")
        assertEquals(one, one.close("only"))
    }
}
