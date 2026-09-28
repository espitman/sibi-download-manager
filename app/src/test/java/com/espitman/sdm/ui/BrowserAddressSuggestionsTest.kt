package com.espitman.sdm.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserAddressSuggestionsTest {
    private val archive = BrowserHistoryEntry("https://archive.org", "Internet Archive", 40L)
    private val docs = BrowserHistoryEntry("https://example.com/docs", "Example Docs", 30L)
    private val news = BrowserHistoryEntry("https://news.example.com", "Daily News", 20L)
    private val titled = BrowserHistoryEntry("https://other.com/page", "Example Corp", 10L)
    private val history = listOf(archive, docs, news, titled)

    @Test fun privateTabsNeverSurfaceRegularHistory() {
        assertFalse(shouldOfferBrowserAddressSuggestions(isPrivate = true, focused = true))
        assertTrue(shouldOfferBrowserAddressSuggestions(isPrivate = false, focused = true))
        assertFalse(shouldOfferBrowserAddressSuggestions(isPrivate = false, focused = false))
        assertEquals(
            emptyList<BrowserHistoryEntry>(),
            suggestBrowserHistory(history, "example", isPrivate = true, focused = true),
        )
        assertEquals(
            emptyList<BrowserHistoryEntry>(),
            suggestBrowserHistory(history, "", isPrivate = true, focused = true),
        )
    }

    @Test fun emptyHistoryAndUnfocusedChromeHideSuggestions() {
        assertEquals(
            emptyList<BrowserHistoryEntry>(),
            suggestBrowserHistory(emptyList(), "example", isPrivate = false, focused = true),
        )
        assertEquals(
            emptyList<BrowserHistoryEntry>(),
            suggestBrowserHistory(history, "example", isPrivate = false, focused = false),
        )
        assertFalse(shouldDismissBrowserAddressSuggestionsOnBack(visible = false))
        assertTrue(shouldDismissBrowserAddressSuggestionsOnBack(visible = true))
    }

    @Test fun emptyQueryShowsRecentBoundedEntries() {
        assertEquals(
            listOf(archive, docs, news, titled),
            suggestBrowserHistory(history, "   ", isPrivate = false, focused = true),
        )
        assertEquals(
            listOf(archive, docs),
            suggestBrowserHistory(history, "", isPrivate = false, focused = true, limit = 2),
        )
    }

    @Test fun matchingIsCaseInsensitiveAcrossUrlHostAndTitle() {
        assertEquals(listOf(archive), suggest("ARCHIVE.ORG"))
        assertEquals(listOf(docs, news), suggest("example.com"))
        assertEquals(listOf(titled), suggest("corp"))
        assertEquals(
            archive.url,
            browserAddressSuggestionDestination(suggest("https://archive.org").single()),
        )
    }

    @Test fun strongerMatchesRankAheadOfWeakerOnesThenRecency() {
        assertEquals(listOf(docs, titled, news), suggest("example"))
        assertEquals(0, browserAddressSuggestionRank(archive, "https://archive.org"))
        assertEquals(1, browserAddressSuggestionRank(docs, "example"))
        assertEquals(3, browserAddressSuggestionRank(titled, "Example"))
        assertEquals(4, browserAddressSuggestionRank(news, "example"))
        val olderHost = BrowserHistoryEntry("https://example.org", "Older host", 1L)
        val newerHost = BrowserHistoryEntry("https://example.net", "Newer host", 5L)
        assertEquals(
            listOf(newerHost, olderHost),
            suggestBrowserHistory(
                listOf(olderHost, newerHost),
                "example.",
                isPrivate = false,
                focused = true,
            ),
        )
    }

    @Test fun navigateBlurTabSwitchAndBackHideTheSurface() {
        val shown = suggestBrowserHistory(history, "arch", isPrivate = false, focused = true)
        assertEquals(listOf(archive), shown)
        assertEquals(
            emptyList<BrowserHistoryEntry>(),
            suggestBrowserHistory(history, "arch", isPrivate = false, focused = false),
        )
        assertEquals(
            emptyList<BrowserHistoryEntry>(),
            suggestBrowserHistory(history, "arch", isPrivate = true, focused = true),
        )
        assertTrue(shouldDismissBrowserAddressSuggestionsOnBack(shown.isNotEmpty()))
    }

    @Test fun unknownQueryAndZeroLimitProduceNoRows() {
        assertEquals(emptyList<BrowserHistoryEntry>(), suggest("no-such-visit"))
        assertEquals(
            emptyList<BrowserHistoryEntry>(),
            suggestBrowserHistory(history, "", isPrivate = false, focused = true, limit = 0),
        )
    }

    private fun suggest(query: String): List<BrowserHistoryEntry> =
        suggestBrowserHistory(history, query, isPrivate = false, focused = true)
}
