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
import com.espitman.sdm.notification.TransferNotificationCoordinator
import com.espitman.sdm.ui.SdmApp
import com.espitman.sdm.ui.SdmLaunchLayer
import com.espitman.sdm.ui.theme.SdmTheme

class MainActivity : ComponentActivity() {
    private val pendingRequest = mutableStateOf<TransferNotificationRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            splashScreen.setOnExitAnimationListener { splashView -> splashView.remove() }
        }
        enableEdgeToEdge()
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        pendingRequest.value = parseTransferNotificationIntent(intent)
        setContent {
            val request by pendingRequest
            SdmTheme {
                SdmLaunchLayer {
                    SdmApp(
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
