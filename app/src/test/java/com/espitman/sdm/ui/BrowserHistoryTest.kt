package com.espitman.sdm.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserHistoryTest {
    @Test fun regularMainFrameVisitsAreRecordedNewestFirst() {
        val first = recordVisit(emptyList(), "https://example.com/one", "One", visitedAt = 10L)
        val second = recordVisit(first, "https://archive.org", "Archive", visitedAt = 20L)
        assertEquals(
            listOf(
                BrowserHistoryEntry("https://archive.org", "Archive", 20L),
                BrowserHistoryEntry("https://example.com/one", "One", 10L),
            ),
            second,
        )
        assertEquals("example.com", browserHistoryDisplayHost("https://example.com/one"))
    }

    @Test fun privateTabsAndDisabledPersistenceNeverWriteHistory() {
        val existing = listOf(BrowserHistoryEntry("https://example.com", "Example", 1L))
        assertEquals(
            existing,
            recordBrowserHistoryVisit(
                existing,
                "https://secret.example",
                "Secret",
                isPrivate = true,
                persistHistory = true,
                visitedAt = 2L,
            ),
        )
        assertEquals(
            existing,
            recordBrowserHistoryVisit(
                existing,
                "https://regular.example",
                "Regular",
                isPrivate = false,
                persistHistory = false,
                visitedAt = 2L,
            ),
        )
        assertFalse(shouldRecordBrowserHistory(isPrivate = true, persistHistory = true))
        assertFalse(shouldRecordBrowserHistory(isPrivate = false, persistHistory = false))
        assertTrue(shouldRecordBrowserHistory(isPrivate = false, persistHistory = true))
        assertFalse(browserHistoryMenuOpensSheet(isPrivate = true))
        assertTrue(browserHistoryMenuOpensSheet(isPrivate = false))
        assertTrue(shouldRecordBrowserHistory(createBrowserTab("tab-r", false).isPrivate, true))
        assertFalse(shouldRecordBrowserHistory(createBrowserTab("tab-p", true).isPrivate, true))
    }

    @Test fun unsupportedAndBlankAddressesAreIgnored() {
        val existing = emptyList<BrowserHistoryEntry>()
        assertEquals(existing, recordVisit(existing, "about:blank", "Blank"))
        assertEquals(existing, recordVisit(existing, "javascript:alert(1)", "Alert"))
        assertEquals(existing, recordVisit(existing, "file:///sdcard/private", "File"))
        assertEquals(existing, recordVisit(existing, "  ", "Empty"))
        assertFalse(isRecordableBrowserHistoryUrl("chrome-error://chromewebdata"))
    }

    @Test fun revisitingAUrlMovesItToTheFrontAndBlankTitlesUseTheHost() {
        val first = recordVisit(emptyList(), "https://example.com/page", "", visitedAt = 1L)
        assertEquals("example.com", first.single().title)
        val other = recordVisit(first, "https://archive.org", "Archive", visitedAt = 2L)
        val revisited = recordVisit(other, "https://example.com/page", "Example", visitedAt = 3L)
        assertEquals(
            listOf(
                BrowserHistoryEntry("https://example.com/page", "Example", 3L),
                BrowserHistoryEntry("https://archive.org", "Archive", 2L),
            ),
            revisited,
        )
    }

    @Test fun historyIsBoundedAndRoundTripsReservedCharacters() {
        val overflowed = (1..3).fold(emptyList<BrowserHistoryEntry>()) { entries, index ->
            recordBrowserHistoryVisit(
                entries,
                "https://example.com/$index",
                "Page $index",
                isPrivate = false,
                persistHistory = true,
                visitedAt = index.toLong(),
                limit = 2,
            )
        }
        assertEquals(
            listOf("https://example.com/3", "https://example.com/2"),
            overflowed.map { it.url },
        )
        val entry = BrowserHistoryEntry(
            url = "https://example.com/q?a=1&b=two three",
            title = "A & B\tC",
            visitedAt = 42L,
        )
        val decoded = decodeBrowserHistory(encodeBrowserHistory(listOf(entry)))
        assertEquals(listOf(entry), decoded)
        assertEquals(emptyList<BrowserHistoryEntry>(), decodeBrowserHistory(encodeBrowserHistory(emptyList())))
        assertNull(decodeBrowserHistory("not-history"))
    }

    @Test fun clearOnExitClearsHistoryAndPrivateByDefaultDoesNot() {
        val keep = BrowserPreferences(privateByDefault = true, clearOnExit = false)
        val clear = BrowserPreferences(privateByDefault = false, clearOnExit = true)
        assertFalse(shouldClearBrowserHistoryOnExit(keep))
        assertTrue(shouldClearBrowserHistoryOnExit(clear))
        assertTrue(BrowserPrivacyPolicy.Regular.persistHistory)
        assertFalse(BrowserPrivacyPolicy.Private.persistHistory)
    }

    private fun recordVisit(
        entries: List<BrowserHistoryEntry>,
        url: String,
        title: String,
        visitedAt: Long = 1L,
    ): List<BrowserHistoryEntry> = recordBrowserHistoryVisit(
        entries,
        url,
        title,
        isPrivate = false,
        persistHistory = true,
        visitedAt = visitedAt,
    )
}
