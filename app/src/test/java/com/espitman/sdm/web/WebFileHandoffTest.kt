package com.espitman.sdm.web

import com.espitman.sdm.storage.CompletedFileAction
import com.espitman.sdm.storage.CompletedFileIdentity
import org.junit.Assert.*
import org.junit.Test

class WebFileHandoffTest {
    private val identity=CompletedFileIdentity("fixture","content://fixture/file","text/plain","fixture.txt")
    @Test fun duplicatesStaySingleAndOnlyMatchingRequestCanBeConsumedOnce() {
        val requests=WebFileHandoff({1000},{true})
        val first=requests.request("owner","Browser",identity,CompletedFileAction.Open)
        assertEquals(first,requests.request("owner","Browser",identity,CompletedFileAction.Open))
        try { requests.request("other","Other",identity,CompletedFileAction.Share);fail() } catch(_:IllegalArgumentException) {}
        assertNull(requests.consume("foreign-id"))
        assertEquals(first,requests.consume(first.id))
        assertNull(requests.consume(first.id))
        assertNull(requests.pending.value)
    }
    @Test fun expirationRevocationDeclineAndPanelStopPreventLateLaunch() {
        var now=1000L;var approved=true
        val requests=WebFileHandoff({now},{approved})
        val expired=requests.request("owner","Browser",identity,CompletedFileAction.Share)
        now=expired.expires
        assertNull(requests.consume(expired.id))
        val revoked=requests.request("owner","Browser",identity,CompletedFileAction.Open)
        approved=false
        assertNull(requests.consume(revoked.id))
        try { requests.request("owner","Browser",identity,CompletedFileAction.Open);fail() } catch(_:IllegalArgumentException) {}
        approved=true
        val declined=requests.request("owner","Browser",identity,CompletedFileAction.Open)
        requests.dismiss(declined.id)
        assertNull(requests.consume(declined.id))
        val stopped=requests.request("owner","Browser",identity,CompletedFileAction.Open)
        requests.cancelAll()
        assertNull(requests.consume(stopped.id))
    }
}
