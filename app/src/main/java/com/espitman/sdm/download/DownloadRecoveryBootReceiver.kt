package com.espitman.sdm.download

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.espitman.sdm.data.SqliteDownloadRepository
import com.espitman.sdm.data.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class DownloadRecoveryBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            var repository: SqliteDownloadRepository? = null
            try {
                val opened = SqliteDownloadRepository(context)
                repository = opened
                BootScheduleRecovery.restore(
                    repository = opened,
                    clock = Clock.SystemClock,
                    autoResume = SettingsRepository.get(context).settings.value.autoResume,
                    armTick = { next -> armScheduleTick(context, next) },
                )
                DailyBulkSchedule.arm(context)
            } finally {
                repository?.close()
                pendingResult.finish()
            }
        }
    }
}
