package com.espitman.sdm.web

import com.espitman.sdm.torrent.TorrentContents
import com.espitman.sdm.torrent.TorrentFile
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class WebTorrentDraftsTest {
    private val contents = TorrentContents("Fixture", "magnet:?xt=urn:btih:" + "a".repeat(40), byteArrayOf(1), listOf(TorrentFile(0, "Fixture/a.bin", 3)))
    @Test fun resolvesWithoutSubmittingAndIsScopedToTheApprovedBrowser() = runBlocking {
        val drafts = WebTorrentDrafts(scope = CoroutineScope(coroutineContext + SupervisorJob()))
        try {
            val release = CompletableDeferred<Unit>()
            val id = drafts.start("browser-a") { release.await(); contents }
            assertEquals("resolving", drafts.status("browser-a", id).getString("status"))
            try { drafts.status("browser-b", id); fail("Another browser read metadata") } catch (_: IllegalArgumentException) {}
            release.complete(Unit)
            withTimeout(1000) { while (drafts.status("browser-a", id).getString("status") != "ready") yield() }
            assertSame(contents, drafts.ready("browser-a", id))
            drafts.remove("browser-a", id)
            try { drafts.ready("browser-a", id); fail("Consumed metadata remained available") } catch (_: IllegalArgumentException) {}
        } finally { drafts.close() }
    }
    @Test fun cancellationExpiryAndRevocationCancelMetadataWork() = runBlocking {
        var now = 0L
        val drafts = WebTorrentDrafts({ now }, CoroutineScope(coroutineContext + SupervisorJob()))
        try {
            val started = CompletableDeferred<Unit>(); val cancelled = CompletableDeferred<Unit>()
            val id = drafts.start("a") { try { started.complete(Unit); awaitCancellation() } finally { cancelled.complete(Unit) } }
            started.await(); drafts.remove("a", id); withTimeout(1000) { cancelled.await() }
            val expired = drafts.start("a") { awaitCancellation() }
            now = 600_001; drafts.expire()
            try { drafts.status("a", expired); fail("Expired draft available") } catch (_: IllegalArgumentException) {}
            val revoked = drafts.start("a") { awaitCancellation() }; drafts.expire(emptySet())
            try { drafts.status("a", revoked); fail("Revoked draft available") } catch (_: IllegalArgumentException) {}
        } finally { drafts.close() }
    }
    @Test fun boundsOutstandingRequestsAndReportsResolutionFailures() = runBlocking {
        val drafts = WebTorrentDrafts(scope = CoroutineScope(coroutineContext + SupervisorJob()))
        try {
            repeat(4) { drafts.start("a") { awaitCancellation() } }
            try { drafts.start("a") { contents }; fail("Per-browser bound bypassed") } catch (_: IllegalStateException) {}
            val failed = drafts.start("b") { throw IllegalArgumentException("Invalid torrent") }
            withTimeout(1000) { while (drafts.status("b", failed).getString("status") != "failed") yield() }
            assertEquals("Invalid torrent", drafts.status("b", failed).getString("error"))
        } finally { drafts.close() }
    }
    @Test fun committingDraftCannotBeSubmittedTwiceOrCancelledDuringCommit() = runBlocking {
        val drafts = WebTorrentDrafts(scope = CoroutineScope(coroutineContext + SupervisorJob()))
        try {
            val id = drafts.start("a") { contents }
            withTimeout(1000) { while (drafts.status("a", id).getString("status") != "ready") yield() }
            val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val commit = async { drafts.submit("a", id) { started.complete(Unit); release.await(); "created" } }
            started.await()
            try { drafts.submit("a", id) { fail("Second submission ran") }; fail() } catch (_: IllegalStateException) {}
            try { drafts.remove("a", id); fail("Cancelled commit") } catch (_: IllegalStateException) {}
            release.complete(Unit); assertEquals("created", commit.await())
            try { drafts.ready("a", id); fail("Committed draft remained") } catch (_: IllegalArgumentException) {}
        } finally { drafts.close() }
    }
}
