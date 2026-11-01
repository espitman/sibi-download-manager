package com.espitman.sdm.web

import com.espitman.sdm.data.DownloadRepository

/** The repository resolves the current priority tiers and moves the group in one transaction. */
internal object WebQueueCommands {
    suspend fun moveGroupToTop(repository:DownloadRepository,ids:Set<String>,now:Long):Boolean =
        repository.moveQueuedGroupToTop(ids,now)
}
