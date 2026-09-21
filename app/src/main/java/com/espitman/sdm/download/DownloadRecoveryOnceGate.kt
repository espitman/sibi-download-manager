package com.espitman.sdm.download

import kotlinx.coroutines.CompletableDeferred

class DownloadRecoveryOnceGate {
    private val lock = Any()
    private var completion: CompletableDeferred<Unit>? = null

    suspend fun runOnce(recover: suspend () -> Unit) {
        val deferred: CompletableDeferred<Unit>
        val isFirst: Boolean
        synchronized(lock) {
            val existing = completion
            if (existing != null) {
                deferred = existing
                isFirst = false
            } else {
                deferred = CompletableDeferred()
                completion = deferred
                isFirst = true
            }
        }
        if (isFirst) {
            try {
                recover()
                deferred.complete(Unit)
            } catch (thrown: Throwable) {
                deferred.completeExceptionally(thrown)
            }
        }
        deferred.await()
    }

    companion object {
        val shared = DownloadRecoveryOnceGate()
    }
}
