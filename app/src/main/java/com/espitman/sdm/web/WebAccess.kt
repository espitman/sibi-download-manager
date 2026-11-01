package com.espitman.sdm.web

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Application-owned access state. Secrets never appear in browser listings or snapshots. */
class WebAccess(private val now: () -> Long = System::currentTimeMillis) {
    data class Request(val id: String, val browser: String, val address: String, val expiresAt: Long)
    data class Browser(val id: String, val browser: String, val address: String, val approvedAt: Long)
    data class Code(val value: String, val expiresAt: Long)
    private data class Session(val browser: Browser, val secret: String)
    private data class Pending(val request: Request, val ticket: String, var session: Session? = null)
    private data class Attempts(var start: Long, var count: Int)
    private val random = SecureRandom()
    private val pending = linkedMapOf<String, Pending>()
    private val sessions = linkedMapOf<String, Session>()
    private val attempts = linkedMapOf<String, Attempts>()
    private val requestsFlow = MutableStateFlow<List<Request>>(emptyList())
    private val browsersFlow = MutableStateFlow<List<Browser>>(emptyList())
    val requests: StateFlow<List<Request>> = requestsFlow.asStateFlow()
    val browsers: StateFlow<List<Browser>> = browsersFlow.asStateFlow()
    private var pairingCode: Code? = null
    private var store: WebSessionStore? = null
    @Synchronized internal fun initialize(persistence: WebSessionStore) {
        if (store != null) return
        val restored = persistence.load()
        sessions.clear()
        restored.forEach { sessions[it.browser.id] = Session(it.browser, it.secret) }
        store = persistence
        publish()
    }
    private fun persist(values: Collection<Session>) { store?.save(values.map { StoredWebBrowser(it.browser, it.secret) }) }
    @Synchronized fun cancelPairing() { pending.clear(); attempts.clear(); pairingCode = null; publish() }

    @Synchronized fun regenerateCode(): Code {
        return Code(random.nextInt(1_000_000).toString().padStart(6, '0'), now() + 120_000).also { pairingCode = it }
    }
    @Synchronized fun code(): Code? = pairingCode?.takeIf { it.expiresAt > now() }
    @Synchronized fun request(browser: String, address: String): Pair<Request, String> {
        clean()
        throttle(address)
        require(browser.length in 1..80 && browser.none { it.code < 32 }) { "Invalid browser name" }
        check(pending.size < 16) { "Too many pending requests" }
        val request = Request(secret(), browser, address, now() + 120_000)
        val ticket = secret()
        pending[request.id] = Pending(request, ticket)
        publish()
        return request to ticket
    }
    @Synchronized fun approve(id: String): Boolean {
        clean()
        val request = pending[id] ?: return false
        if (request.session != null) return false
        request.session = createSession(request.request.browser, request.request.address)
        publish()
        return true
    }
    @Synchronized fun deny(id: String) { pending.remove(id); publish() }
    /** Only the browser holding the random request ticket can collect its approved session. */
    @Synchronized fun collect(id: String, ticket: String): String? {
        clean()
        val request = pending[id] ?: throw AccessDenied()
        if (!equal(request.ticket, ticket)) throw AccessDenied()
        val session = request.session ?: return null
        pending.remove(id)
        publish()
        return session.secret
    }
    @Synchronized fun pair(code: String, browser: String, address: String): String {
        clean()
        throttle(address)
        require(browser.length in 1..80 && browser.none { it.code < 32 }) { "Invalid browser name" }
        val current = pairingCode ?: throw AccessDenied()
        if (current.expiresAt <= now() || !equal(current.value, code)) throw AccessDenied()
        val session = createSession(browser, address)
        pairingCode = null // One successfully persisted pairing consumes the code.
        return session.secret
    }
    @Synchronized fun authorize(secret: String?): Browser {
        clean()
        if (secret == null) throw AccessDenied()
        return sessions.values.firstOrNull { equal(it.secret, secret) }?.browser ?: throw AccessDenied()
    }
    @Synchronized fun revoke(id: String) {
        persist(sessions.filterKeys { it != id }.values)
        sessions.remove(id)
        pending.entries.removeAll { it.value.session?.browser?.id == id }
        publish()
    }
    @Synchronized fun reset() {
        persist(emptyList()); sessions.clear(); cancelPairing()
    }
    private fun createSession(browser: String, address: String): Session {
        check(sessions.size < 32) { "Too many approved browsers" }
        val entry = Browser(secret(), browser, address, now())
        val session = Session(entry, secret())
        persist(sessions.values + session)
        sessions[entry.id] = session
        publish()
        return session
    }
    @Synchronized fun expire() { clean() }
    private fun clean() {
        val time = now()
        if (pairingCode?.expiresAt?.let { it <= time } == true) pairingCode = null
        pending.entries.removeAll { it.value.request.expiresAt <= time }
        attempts.entries.removeAll { time - it.value.start >= 60_000 }
        publish()
    }
    private fun throttle(address: String) {
        val attempt = attempts.getOrPut(address) { Attempts(now(), 0) }
        if (++attempt.count > 5) throw PairingRateLimited()
        if (attempts.size > 256) attempts.remove(attempts.keys.first())
    }
    private fun publish() {
        requestsFlow.value = pending.values.filter { it.session == null }.map { it.request }
        browsersFlow.value = sessions.values.map { it.browser }
    }
    private fun secret(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))
    private fun equal(a: String, b: String): Boolean = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())
}
class AccessDenied : RuntimeException("Browser access is not authorized")
class PairingRateLimited : RuntimeException("Too many pairing attempts; wait one minute")
