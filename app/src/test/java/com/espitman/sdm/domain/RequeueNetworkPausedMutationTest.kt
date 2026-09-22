package com.espitman.sdm.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RequeueNetworkPausedMutationTest {
    @Test
    fun requeuesOnlyNetworkPolicyPausesAndClearsCause() {
        val networkPaused = paused("net").copy(pauseCause = DownloadPauseCause.NETWORK_POLICY)
        val manualPaused = paused("manual")
        val queued = download("queued", DownloadState.QUEUED)

        val requeued = RequeueNetworkPausedMutation.apply(networkPaused, nowEpochMillis = 9_000L)!!
        assertEquals(DownloadState.QUEUED, requeued.state)
        assertNull(requeued.pauseCause)
        assertEquals(40L, requeued.downloadedBytes)
        assertEquals(9_000L, requeued.updatedAtEpochMillis)

        assertNull(RequeueNetworkPausedMutation.apply(manualPaused, 9_000L))
        assertNull(RequeueNetworkPausedMutation.apply(queued, 9_000L))
        assertEquals(DownloadState.PAUSED, manualPaused.state)
        assertNull(manualPaused.pauseCause)
    }

    private fun paused(id: String) = DownloadStateMachine.transition(
        DownloadStateMachine.transition(
            download(id, DownloadState.QUEUED),
            DownloadState.CONNECTING,
            2_000,
        ),
        DownloadState.PAUSED,
        3_000,
    ).copy(downloadedBytes = 40L)

    private fun download(id: String, state: DownloadState) = Download(
        id = id,
        url = "https://example.com/$id.bin",
        fileName = "$id.bin",
        totalBytes = 100L,
        state = state,
        createdAtEpochMillis = 1_000L,
        updatedAtEpochMillis = 1_000L,
    )
}
