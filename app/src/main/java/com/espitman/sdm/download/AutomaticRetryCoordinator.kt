package com.espitman.sdm.download

import com.espitman.sdm.data.DownloadRepository
import com.espitman.sdm.domain.AutomaticRetrySettings
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The deadline and budget survive process death in the download row and preferences. */
class AutomaticRetryCoordinator(
    private val repository: DownloadRepository,
    private val settings: () -> AutomaticRetrySettings,
    private val scheduler: suspend () -> Unit,
    private val allowed: () -> Boolean = { true },
    private val blocked: () -> Boolean = { false },
    private val arm: (Long?) -> Unit = {},
    private val clock: Clock = Clock.SystemClock,
) {
    private val mutex = Mutex()
    suspend fun apply(): Long? = mutex.withLock {
        repository.awaitInitialized()
        val now = clock.currentTimeMillis()
        var queued = false
        for (row in repository.schedulingSnapshot()) {
            val current = repository.get(row.id) ?: continue
            val due = settings().dueAt(current) ?: continue
            if (due <= now && allowed() && !blocked() && current.schedule?.isOpen(now) != false) {
                if (repository.retryFailed(current.id, true, maxOf(now, current.updatedAtEpochMillis)) != null) queued = true
            }
        }
        if (queued) scheduler()
        val next = repository.schedulingSnapshot().mapNotNull { row ->
            settings().dueAt(row)?.let { due ->
                if (due > now) due else if (row.schedule?.isOpen(now) == false)
                    row.schedule.nextBoundary(now) ?: now + 30_000L
                else now + 30_000L // connectivity, migration or a racing update; avoid spinning
            }
        }.minOrNull()
        arm(next)
        next
    }
}
