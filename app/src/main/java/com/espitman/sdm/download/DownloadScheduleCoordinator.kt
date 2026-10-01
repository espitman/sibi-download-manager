package com.espitman.sdm.download

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.espitman.sdm.data.AppRepositories
import com.espitman.sdm.data.DownloadRepository
import com.espitman.sdm.domain.DownloadPauseCause
import com.espitman.sdm.domain.DownloadState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Reconciles persisted time windows with the queue and arms the next wall-clock boundary. */
class DownloadScheduleCoordinator(
    private val context: Context,
    private val repository: DownloadRepository,
    private val scheduler: DownloadQueueScheduler,
    private val pauseActive: (String) -> Unit,
    private val clock: Clock = Clock.SystemClock,
) {
    private val mutex = Mutex()

    suspend fun apply() = mutex.withLock {
        repository.awaitInitialized()
        val now = clock.currentTimeMillis()
        val snapshot = repository.schedulingSnapshot()
        var retryNeeded = false
        for (download in snapshot) {
            val open = download.schedule?.isOpen(now) ?: true
            if (download.state == DownloadState.PAUSED &&
                download.pauseCause == DownloadPauseCause.SCHEDULE && open
            ) {
                try {
                    repository.resumePaused(download.id, maxOf(now, download.updatedAtEpochMillis))
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    retryNeeded = true
                }
            } else if (download.state in DownloadQueuePolicy.OCCUPYING_STATES && !open) {
                retryNeeded = true
                runCatching { pauseActive(download.id) }
            }
        }
        scheduler.schedule()
        val after = repository.schedulingSnapshot()
        val stillEligible = after.any { it.state == DownloadState.QUEUED &&
            it.schedule?.isOpen(now) == true }
        armNextBoundary(after, now, if (retryNeeded || stillEligible) now + 30_000L else null)
    }

    private fun armNextBoundary(
        downloads: List<com.espitman.sdm.domain.Download>,
        now: Long,
        retryAt: Long?,
    ) {
        val next = downloads.asSequence()
            .filter { it.state !in setOf(DownloadState.COMPLETED, DownloadState.CANCELLED, DownloadState.FAILED) &&
                (it.state != DownloadState.PAUSED || it.pauseCause == DownloadPauseCause.SCHEDULE) }
            .mapNotNull { it.schedule?.nextBoundary(now) }
            .minOrNull()
        armScheduleTick(context, listOfNotNull(next, retryAt).minOrNull())
    }

    companion object { const val ACTION_TICK = "com.espitman.sdm.SCHEDULE_TICK" }
}

internal fun armScheduleTick(
    context: Context, next: Long?,
    receiver: Class<out BroadcastReceiver> = DownloadScheduleAlarmReceiver::class.java,
    tickAction: String = DownloadScheduleCoordinator.ACTION_TICK,
    requestCode: Int = 317,
) {
    val alarm = context.getSystemService(AlarmManager::class.java) ?: return
    val normalPending = PendingIntent.getBroadcast(
        context,
        requestCode,
        Intent(context, receiver).apply {
            action = tickAction
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    val idlePending = PendingIntent.getBroadcast(
        context,
        requestCode + 1,
        Intent(context, receiver).apply {
            action = tickAction
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    alarm.cancel(normalPending)
    alarm.cancel(idlePending)
    if (next == null) return
    try {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarm.canScheduleExactAlarms()) {
            // Normal exact alarms avoid the tight quota on allow-while-idle alarms
            // while the device is awake. The idle-capable alarm covers sleep.
            alarm.setExact(AlarmManager.RTC_WAKEUP, next, normalPending)
            alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, idlePending)
        } else {
            // When exact-alarm access is denied, Android may deliver this later.
            alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, normalPending)
        }
    } catch (_: SecurityException) {
        // The grant can be revoked between canScheduleExactAlarms and setExact.
        alarm.cancel(normalPending)
        alarm.cancel(idlePending)
        alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, normalPending)
    }
}

class DownloadScheduleAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(
                DownloadScheduleCoordinator.ACTION_TICK,
                Intent.ACTION_TIME_CHANGED,
                Intent.ACTION_TIMEZONE_CHANGED,
                Intent.ACTION_DATE_CHANGED,
                AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
            )
        ) return
        if (intent.action != DownloadScheduleCoordinator.ACTION_TICK) DailyBulkSchedule.arm(context)
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                AppRepositories.scheduleCoordinator(context).apply()
                AppRepositories.automaticRetry(context).apply()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                armScheduleTick(context, System.currentTimeMillis() + 60_000L)
            } finally {
                pending.finish()
            }
        }
    }
}
