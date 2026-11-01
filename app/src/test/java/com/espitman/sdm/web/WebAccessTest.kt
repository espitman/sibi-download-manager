package com.espitman.sdm.web

import org.junit.Assert.*
import org.junit.Test

class WebAccessTest {
    @Test fun approvalRequiresPhoneDecisionAndMatchingTicket() {
        val access = WebAccess()
        val (request, ticket) = access.request("Chrome on Mac", "192.168.1.5")
        assertNull(access.collect(request.id, ticket))
        assertThrows(AccessDenied::class.java) { access.collect(request.id, "wrong") }
        assertTrue(access.approve(request.id))
        val session = access.collect(request.id, ticket)!!
        assertEquals("Chrome on Mac", access.authorize(session).browser)
        assertThrows(AccessDenied::class.java) { access.collect(request.id, ticket) }
        assertTrue(access.requests.value.isEmpty())
        access.revoke(access.authorize(session).id)
        assertThrows(AccessDenied::class.java) { access.authorize(session) }
    }
    @Test fun pairingCodeExpiresIsSingleUseAndCannotAuthorizeDirectly() {
        var time = 1_000L
        val access = WebAccess { time }
        val expired = access.regenerateCode()
        time += 120_000
        assertThrows(AccessDenied::class.java) { access.pair(expired.value, "Chrome", "host") }
        val code = access.regenerateCode()
        assertThrows(AccessDenied::class.java) { access.authorize(code.value) }
        val session = access.pair(code.value, "Chrome", "host")
        assertNotNull(access.authorize(session))
        assertThrows(AccessDenied::class.java) { access.pair(code.value, "Chrome", "host") }
        time += 86_400_000 * 365L
        assertNotNull(access.authorize(session))
    }
    @Test fun guessingIsRateLimitedAndDenialExpiresRequests() {
        var time = 0L
        val access = WebAccess { time }
        repeat(5) { assertThrows(AccessDenied::class.java) { access.pair("000000", "Chrome", "host") } }
        assertThrows(PairingRateLimited::class.java) { access.request("Chrome", "host") }
        time += 60_000
        val (request, ticket) = access.request("Chrome", "host")
        access.deny(request.id)
        assertThrows(AccessDenied::class.java) { access.collect(request.id, ticket) }
        val (expired, expiredTicket) = access.request("Chrome", "host")
        time += 120_000
        assertThrows(AccessDenied::class.java) { access.collect(expired.id, expiredTicket) }
    }
    @Test fun explicitRevokeAllInvalidatesAllAccess() {
        val access = WebAccess()
        val session = access.pair(access.regenerateCode().value, "Firefox", "host")
        val (request, ticket) = access.request("Chrome", "host")
        access.reset()
        assertTrue(access.browsers.value.isEmpty())
        assertThrows(AccessDenied::class.java) { access.authorize(session) }
        assertThrows(AccessDenied::class.java) { access.collect(request.id, ticket) }
    }
    @Test fun approvalSurvivesStoppingPanelAndProcessRestartUntilPhoneRevokesIt() {
        var stored = emptyList<StoredWebBrowser>()
        val store = object : WebSessionStore {
            override fun load() = stored
            override fun save(sessions: List<StoredWebBrowser>) { stored = sessions.toList() }
        }
        val first = WebAccess().apply { initialize(store) }
        val secret = first.pair(first.regenerateCode().value, "Chrome", "host")
        val id = first.authorize(secret).id
        first.request("Other browser", "other")
        first.cancelPairing()
        assertTrue(first.requests.value.isEmpty())
        assertEquals(id, first.authorize(secret).id)
        val restarted = WebAccess().apply { initialize(store) }
        assertEquals(id, restarted.authorize(secret).id)
        restarted.revoke(id)
        val afterRevocation = WebAccess().apply { initialize(store) }
        assertThrows(AccessDenied::class.java) { afterRevocation.authorize(secret) }
    }
    @Test fun failedPersistenceDoesNotGrantAccessOrConsumePairingCode() {
        val store = object : WebSessionStore {
            override fun load() = emptyList<StoredWebBrowser>()
            override fun save(sessions: List<StoredWebBrowser>) { throw IllegalStateException("Disk unavailable") }
        }
        val access = WebAccess().apply { initialize(store) }
        val code = access.regenerateCode()
        assertThrows(IllegalStateException::class.java) { access.pair(code.value, "Chrome", "host") }
        assertTrue(access.browsers.value.isEmpty())
        assertEquals(code, access.code())
    }

}
