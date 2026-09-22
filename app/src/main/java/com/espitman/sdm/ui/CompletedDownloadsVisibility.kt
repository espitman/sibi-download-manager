package com.espitman.sdm.ui

import android.content.Context

/** Keeps Completed history independent from the downloaded files shown in Files. */
internal class CompletedDownloadsVisibility(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun hiddenIds(): Set<String> = preferences.getStringSet(HIDDEN_IDS_KEY, emptySet()).orEmpty().toSet()

    fun hide(ids: Collection<String>): Set<String> {
        val updated = hiddenIds() + ids
        preferences.edit().putStringSet(HIDDEN_IDS_KEY, updated).apply()
        return updated
    }

    private companion object {
        const val PREFERENCES_NAME = "sdm.completed_downloads_visibility"
        const val HIDDEN_IDS_KEY = "hidden_ids"
    }
}

internal fun visibleDownloadCards(
    cards: List<DownloadCardModel>,
    hiddenCompletedIds: Set<String>,
): List<DownloadCardModel> = cards.filterNot { card ->
    card.category == DownloadCategory.Completed && card.id in hiddenCompletedIds
}
