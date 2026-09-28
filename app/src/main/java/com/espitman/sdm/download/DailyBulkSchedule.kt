package com.espitman.sdm.download

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.espitman.sdm.data.settings.SettingsRepository
import java.time.Instant
import java.time.ZoneId

/** Two independent wall-clock alarms, so editing the per-download schedule cannot cancel them. */
object DailyBulkSchedule {
    const val ACTION_RESUME = "com.espitman.sdm.DAILY_RESUME_ALL"
    const val ACTION_PAUSE = "com.espitman.sdm.DAILY_PAUSE_ALL"

    fun nextOccurrence(minuteOfDay: Int, afterEpochMillis: Long, zone: ZoneId): Long {
        val localDate = Instant.ofEpochMilli(afterEpochMillis).atZone(zone).toLocalDate()
        val today = localDate.atStartOfDay().plusMinutes(minuteOfDay.toLong())
            .atZone(zone).toInstant().toEpochMilli()
        return if (today > afterEpochMillis) today else localDate.plusDays(1)
            .atStartOfDay().plusMinutes(minuteOfDay.toLong()).atZone(zone).toInstant().toEpochMilli()
    }

    fun arm(context: Context) {
        val alarm = context.getSystemService(AlarmManager::class.java) ?: return
        val settings = SettingsRepository.get(context).settings.value
        val now = System.currentTimeMillis()
        listOf(ACTION_RESUME to settings.dailyResumeMinute, ACTION_PAUSE to settings.dailyPauseMinute)
            .forEachIndexed { index, (action, minute) ->
                val pending = PendingIntent.getBroadcast(
                    context, 400 + index,
                    Intent(context, DailyBulkScheduleReceiver::class.java).setAction(action),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                alarm.cancel(pending)
                if (!settings.dailyBulkScheduleEnabled) return@forEachIndexed
                val next = nextOccurrence(minute, now, ZoneId.systemDefault())
                try {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarm.canScheduleExactAlarms()) {
                        alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pending)
                    } else {
                        alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pending)
                    }
                } catch (_: SecurityException) {
                    alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pending)
                }
            }
    }
}

class DailyBulkScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(DailyBulkSchedule.ACTION_RESUME, DailyBulkSchedule.ACTION_PAUSE)) return
        // A disabled or edited schedule may leave an already dispatched alarm in flight.
        val settings = SettingsRepository.get(context).settings.value
        if (settings.dailyBulkScheduleEnabled) {
            when (intent.action) {
                DailyBulkSchedule.ACTION_RESUME -> DownloadTransferService.resumeAll(context)
                DailyBulkSchedule.ACTION_PAUSE -> DownloadTransferService.pauseAll(context)
            }
        }
        DailyBulkSchedule.arm(context)
    }
}
