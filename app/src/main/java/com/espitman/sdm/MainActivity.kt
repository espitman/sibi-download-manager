package com.espitman.sdm

import android.content.Intent
import android.os.Bundle
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.core.view.WindowCompat
import com.espitman.sdm.data.AppRepositories
import com.espitman.sdm.download.DownloadInterruptionTrigger
import com.espitman.sdm.notification.TransferNotificationCoordinator
import com.espitman.sdm.ui.SdmApp
import com.espitman.sdm.ui.SdmLaunchLayer
import com.espitman.sdm.ui.SplashLaunchPolicy
import com.espitman.sdm.ui.theme.SdmTheme
import kotlinx.coroutines.runBlocking

class MainActivity : ComponentActivity() {
    private val pendingTorrent = mutableStateOf<String?>(null)
    private val pendingRequest = mutableStateOf<TransferNotificationRequest?>(null)

    companion object {
        private val splashLaunchPolicy = SplashLaunchPolicy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val showSplash = splashLaunchPolicy.shouldShow(restoredActivity = savedInstanceState != null)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            splashScreen.setOnExitAnimationListener { splashView -> splashView.remove() }
        }
        enableEdgeToEdge()
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        pendingTorrent.value = parseTorrentIntent(intent)
        pendingRequest.value = if (savedInstanceState == null) parseTransferNotificationIntent(intent) else null
        runBlocking {
            AppRepositories.recoverInterruptedDownloads(
                context = this@MainActivity,
                trigger = DownloadInterruptionTrigger.PROCESS_RESTART,
            )
            val repository = AppRepositories.downloads(this@MainActivity)
            repository.awaitInitialized()
            TransferNotificationCoordinator(this@MainActivity).clearOrphanSummary(repository.downloads.value)
        }
        setContent {
            val request by pendingRequest
            SdmTheme {
                SdmLaunchLayer(showSplash = showSplash) {
                    SdmApp(
                        torrentRequest = pendingTorrent.value,
                        onTorrentConsumed = { pendingTorrent.value = null },
                        openDownloads = request?.openDownloads == true,
                        openDownloadId = request?.openDownloadId,
                        onConsumed = { pendingRequest.value = null },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingRequest.value = parseTransferNotificationIntent(intent)
        pendingTorrent.value = parseTorrentIntent(intent)
    }
}

private data class TransferNotificationRequest(
    val openDownloads: Boolean,
    val openDownloadId: String?,
)

private fun parseTransferNotificationIntent(intent: Intent?): TransferNotificationRequest? {
    return when (intent?.action) {
        TransferNotificationCoordinator.ACTION_OPEN_DOWNLOADS -> TransferNotificationRequest(
            openDownloads = true,
            openDownloadId = null,
        )
        TransferNotificationCoordinator.ACTION_OPEN_DOWNLOAD -> {
            val downloadId = intent.getStringExtra(TransferNotificationCoordinator.EXTRA_DOWNLOAD_ID)
                ?.takeIf { it.isNotBlank() }
                ?: return null
            TransferNotificationRequest(
                openDownloads = false,
                openDownloadId = downloadId,
            )
        }
        else -> null
    }
}

private fun parseTorrentIntent(intent: Intent?): String? {
    if (intent?.action != Intent.ACTION_VIEW) return null
    val uri = intent.data ?: return null
    return when {
        uri.scheme.equals("magnet", true) -> uri.toString()
        uri.scheme in setOf("http", "https") && (intent.type == "application/x-bittorrent" || uri.lastPathSegment?.endsWith(".torrent", true) == true) -> uri.toString()
        uri.scheme == "content" && (intent.type == "application/x-bittorrent" || uri.lastPathSegment?.endsWith(".torrent", true) == true) -> uri.toString()
        else -> null
    }
}
