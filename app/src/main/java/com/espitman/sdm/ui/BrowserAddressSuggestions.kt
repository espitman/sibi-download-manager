package com.espitman.sdm.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.espitman.sdm.ui.theme.SdmGold
import com.espitman.sdm.ui.theme.SdmGoldHigh
import com.espitman.sdm.ui.theme.SdmMuted
import com.espitman.sdm.ui.theme.SdmText
import com.espitman.sdm.ui.theme.sdmColor

internal const val BROWSER_ADDRESS_SUGGESTION_LIMIT = 6

internal fun shouldOfferBrowserAddressSuggestions(isPrivate: Boolean, focused: Boolean): Boolean =
    focused && !isPrivate

internal fun shouldDismissBrowserAddressSuggestionsOnBack(visible: Boolean): Boolean = visible

internal fun browserAddressSuggestionDestination(entry: BrowserHistoryEntry): String = entry.url

internal fun suggestBrowserHistory(
    entries: List<BrowserHistoryEntry>,
    query: String,
    isPrivate: Boolean,
    focused: Boolean,
    limit: Int = BROWSER_ADDRESS_SUGGESTION_LIMIT,
): List<BrowserHistoryEntry> {
    if (!shouldOfferBrowserAddressSuggestions(isPrivate, focused)) return emptyList()
    if (entries.isEmpty() || limit <= 0) return emptyList()
    val needle = query.trim()
    if (needle.isEmpty()) return entries.take(limit)
    return entries
        .mapNotNull { entry ->
            val rank = browserAddressSuggestionRank(entry, needle) ?: return@mapNotNull null
            rank to entry
        }
        .sortedWith(
            compareBy<Pair<Int, BrowserHistoryEntry>> { it.first }
                .thenByDescending { it.second.visitedAt },
        )
        .map { it.second }
        .take(limit)
}

internal fun browserAddressSuggestionRank(entry: BrowserHistoryEntry, rawQuery: String): Int? {
    val query = rawQuery.trim()
    if (query.isEmpty()) return 0
    val url = entry.url
    val host = browserHistoryDisplayHost(url)
    val title = entry.title
    val urlNoScheme = url.substringAfter("://", url)
    return when {
        url.equals(query, ignoreCase = true) || urlNoScheme.equals(query, ignoreCase = true) -> 0
        host.equals(query, ignoreCase = true) || urlNoScheme.startsWith(query, ignoreCase = true) -> 1
        host.startsWith(query, ignoreCase = true) -> 2
        title.startsWith(query, ignoreCase = true) -> 3
        host.contains(query, ignoreCase = true) -> 4
        url.contains(query, ignoreCase = true) || urlNoScheme.contains(query, ignoreCase = true) -> 5
        title.contains(query, ignoreCase = true) -> 6
        else -> null
    }
}

@Composable
internal fun BrowserAddressSuggestionSurface(
    entries: List<BrowserHistoryEntry>,
    onSelect: (BrowserHistoryEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = sdmColor(0xFF17181A, 0xFFFFFFFF),
        contentColor = SdmText,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, SdmGold.copy(alpha = .35f)),
        shadowElevation = 12.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            entries.forEach { entry ->
                Row(
                    Modifier.fillMaxWidth().height(48.dp)
                        .background(sdmColor(0xFF111214, 0xFFFBFAF6), RoundedCornerShape(12.dp))
                        .clickable { onSelect(entry) }
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(30.dp)
                            .background(sdmColor(0xFF252318, 0xFFF2EAD2), RoundedCornerShape(9.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(SdmIcons.Browser, null, tint = SdmGoldHigh, modifier = Modifier.size(16.dp))
                    }
                    Column(Modifier.weight(1f).padding(start = 10.dp)) {
                        Text(entry.title, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                        Text(
                            browserHistoryDisplayHost(entry.url),
                            color = SdmMuted,
                            fontSize = 10.sp,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}
