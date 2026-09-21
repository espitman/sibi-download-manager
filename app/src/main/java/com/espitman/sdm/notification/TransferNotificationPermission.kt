package com.espitman.sdm.notification

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

fun interface TransferNotificationPermissionPreparer {
    fun prepareForForegroundTransfer(onReady: () -> Unit)
}

@Composable
internal fun rememberTransferNotificationPermissionPreparer(
    onRuntimePermissionResolved: () -> Unit,
): TransferNotificationPermissionPreparer {
    val context = LocalContext.current
    val store = remember(context.applicationContext) {
        NotificationPermissionStore(context.applicationContext)
    }
    val resolvedCallback = rememberUpdatedState(onRuntimePermissionResolved)
    val promptAttempt = remember { NotificationPermissionPromptAttempt() }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { _ ->
        store.markRuntimePromptIssued()
        if (promptAttempt.onSystemResult()) {
            resolvedCallback.value()
        }
    }

    return TransferNotificationPermissionPreparer { onReady ->
        val granted = Build.VERSION.SDK_INT < NotificationPermissionPolicy.RUNTIME_PERMISSION_MIN_SDK ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        val decision = NotificationPermissionPolicy.decide(
            sdkInt = Build.VERSION.SDK_INT,
            permissionGranted = granted,
            runtimePromptAlreadyIssued = store.hasIssuedRuntimePrompt() || promptAttempt.awaiting,
        )
        if (!decision.shouldRequestRuntimePermission) {
            onReady()
            return@TransferNotificationPermissionPreparer
        }
        store.markRuntimePromptIssued()
        promptAttempt.beginLaunch()
        try {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } catch (_: Exception) {
            if (promptAttempt.onLaunchFailed()) {
                onReady()
            }
        }
    }
}
