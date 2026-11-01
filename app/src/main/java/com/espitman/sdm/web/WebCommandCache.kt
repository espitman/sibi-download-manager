package com.espitman.sdm.web

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Coalesce duplicate commands without blocking unrelated phone operations. */
internal class WebCommandCache(private val now: () -> Long = System::currentTimeMillis) {
    private data class Entry(val fingerprint: String, val result: CompletableDeferred<LanHttpServer.Response> = CompletableDeferred(), var expiresAt: Long? = null)
    private val mutex = Mutex()
    private val entries = linkedMapOf<String, Entry>()
    suspend fun execute(browser: String, key: String, fingerprint: String, block: suspend () -> LanHttpServer.Response): LanHttpServer.Response {
        require(key.matches(Regex("[A-Za-z0-9_-]{16,128}"))) { "Invalid command key" }
        val identity = "$browser:$key"
        var leader = false
        val entry = mutex.withLock {
            entries.entries.removeAll { it.value.expiresAt?.let { expiry -> expiry <= now() } == true }
            entries[identity]?.also {
                require(it.fingerprint == fingerprint) { "Command key was used for a different action" }
            } ?: run {
                check(entries.size < 256) { "Too many recent commands; wait before trying again" }
                leader = true
                Entry(fingerprint).also { entries[identity] = it }
            }
        }
        if (!leader) return entry.result.await()
        try {
            val result = block()
            check(result.stream == null && result.body.size <= 512 * 1024)
            withContext(NonCancellable) { mutex.withLock { entry.expiresAt = now() + 600_000; entry.result.complete(result) } }
            return result
        } catch (failure: Throwable) {
            withContext(NonCancellable) { mutex.withLock { entries.remove(identity); entry.result.completeExceptionally(failure) } }
            throw failure
        }
    }
}
