package com.espitman.sdm.download

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.espitman.sdm.data.SqliteDownloadRepository
import com.espitman.sdm.domain.Download
import com.espitman.sdm.domain.DownloadPauseCause
import com.espitman.sdm.domain.DownloadSchedule
import com.espitman.sdm.domain.DownloadState
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DownloadScheduleCoordinatorTest {
    @Test fun bootRestoreRequeuesInterruptedScheduledTransferAndRearmsWithoutStartingService() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "schedule-boot-${UUID.randomUUID()}.db"
        val repo = SqliteDownloadRepository(context, databaseName = dbName)
        try {
            repo.awaitInitialized()
            val now = System.currentTimeMillis()
            repo.insert(Download(
                id = "boot",
                url = "https://example.com/boot.bin",
                fileName = "boot.bin",
                state = DownloadState.CONNECTING,
                createdAtEpochMillis = now - 20_000L,
                updatedAtEpochMillis = now - 1_000L,
                schedule = DownloadSchedule(
                    DownloadSchedule.Kind.ONCE,
                    startEpochMillis = now - 10_000L,
                    endEpochMillis = now + 60_000L,
                ),
            ))
            val alarms = mutableListOf<Long?>()
            BootScheduleRecovery.restore(
                repository = repo,
                autoResume = true,
                clock = MutableClock(now),
                armTick = alarms::add,
            )
            assertEquals(DownloadState.QUEUED, repo.get("boot")!!.state)
            assertEquals(listOf(now + 5_000L), alarms)
        } finally {
            repo.close()
            context.deleteDatabase(dbName)
        }
    }

    @Test fun futureStartDoesNotOccupySlotAndEndRequestsSchedulePause() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "schedule-${UUID.randomUUID()}.db"
        val repo = SqliteDownloadRepository(context, databaseName = dbName)
        try {
            repo.awaitInitialized()
            val base = System.currentTimeMillis() + 120_000L
            val clock = MutableClock(base)
            val started = mutableListOf<String>()
            val paused = mutableListOf<String>()
            val scheduler = DownloadQueueScheduler(
                repository = repo,
                concurrentLimit = { 1 },
                starter = { started += it.id },
                clock = clock,
            )
            val coordinator = DownloadScheduleCoordinator(context, repo, scheduler, paused::add, clock)
            repo.insert(Download(
                id = "scheduled",
                url = "https://example.com/scheduled.bin",
                fileName = "scheduled.bin",
                createdAtEpochMillis = 1L,
                schedule = DownloadSchedule(
                    DownloadSchedule.Kind.ONCE,
                    startEpochMillis = base + 10_000L,
                    endEpochMillis = base + 20_000L,
                ),
            ))
            repo.insert(Download(
                id = "ordinary",
                url = "https://example.com/ordinary.bin",
                fileName = "ordinary.bin",
                createdAtEpochMillis = 2L,
            ))
            coordinator.apply()
            assertEquals(listOf("ordinary"), started)
            repo.transition("ordinary", DownloadState.CONNECTING, base)
            repo.transition("ordinary", DownloadState.PAUSED, base + 1L)
            clock.now = base + 10_000L
            coordinator.apply()
            assertEquals(listOf("ordinary", "scheduled"), started)
            assertEquals(DownloadState.PAUSED, repo.get("ordinary")!!.state)
            repo.transition("scheduled", DownloadState.CONNECTING, base + 10_000L)
            clock.now = base + 20_000L
            coordinator.apply()
            assertEquals(listOf("scheduled"), paused)
            repo.pauseAtExactOffset("scheduled", 0L, base + 20_000L, DownloadPauseCause.SCHEDULE)
            clock.now = base + 21_000L
            coordinator.apply()
            assertEquals(DownloadState.PAUSED, repo.get("scheduled")!!.state)
            repo.updateSchedule("scheduled", null, base + 22_000L)
            coordinator.apply()
            assertEquals(DownloadState.QUEUED, repo.get("scheduled")!!.state)
            assertEquals(DownloadState.PAUSED, repo.get("ordinary")!!.state)
        } finally {
            repo.close()
            context.deleteDatabase(dbName)
        }
    }

    private class MutableClock(var now: Long) : Clock {
        override fun currentTimeMillis(): Long = now
    }
}
