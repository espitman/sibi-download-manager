package com.espitman.sdm.storage

import kotlinx.coroutines.sync.Mutex

/** Serializes allocation/insertion with folder migration. Transfers use the durable journal guard. */
object FolderMutationGate {
    val mutex = Mutex()
    private val workers = java.util.concurrent.atomic.AtomicInteger()
    fun workerStarted() { workers.incrementAndGet() }
    fun workerFinished() { workers.decrementAndGet() }
    fun hasWorkers() = workers.get() != 0
}
