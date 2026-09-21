package com.espitman.sdm.download

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.espitman.sdm.data.AppRepositories
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class DownloadRecoveryBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                AppRepositories.recoverInterruptedDownloads(
                    context = context,
                    trigger = DownloadInterruptionTrigger.DEVICE_BOOT,
                )
            } finally {
                pendingResult.finish()
            }
        }
    }
}
