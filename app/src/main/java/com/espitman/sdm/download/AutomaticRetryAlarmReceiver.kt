package com.espitman.sdm.download

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.espitman.sdm.data.AppRepositories
import kotlinx.coroutines.*

class AutomaticRetryAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try { AppRepositories.automaticRetry(context).apply() }
            catch (cancellation: CancellationException) { throw cancellation }
            catch (_: Exception) { armRetryTick(context, System.currentTimeMillis() + 30_000L) }
            finally { pending.finish() }
        }
    }
    companion object { const val ACTION = "com.espitman.sdm.AUTOMATIC_RETRY" }
}

internal fun armRetryTick(context: Context, next: Long?) = armScheduleTick(
    context, next, AutomaticRetryAlarmReceiver::class.java, AutomaticRetryAlarmReceiver.ACTION, 321,
)
