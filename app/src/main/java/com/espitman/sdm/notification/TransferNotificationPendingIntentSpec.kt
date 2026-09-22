package com.espitman.sdm.notification

import com.espitman.sdm.download.DownloadTransferCommand
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

data class TransferNotificationActionIdentity(
    val downloadId: String,
    val serviceAction: String,
    val requestCode: Int,
    val data: String,
    val flags: Int,
    val pendingIntentKind: String,
)

object TransferNotificationPendingIntentSpec {
    /** Matches [android.app.PendingIntent.FLAG_UPDATE_CURRENT]. */
    const val FLAG_UPDATE_CURRENT = 0x08000000

    /** Matches [android.app.PendingIntent.FLAG_IMMUTABLE]. */
    const val FLAG_IMMUTABLE = 0x04000000

    const val PENDING_INTENT_KIND_FOREGROUND_SERVICE = "foregroundService"

    private val CONTROL_ACTIONS = setOf(
        DownloadTransferCommand.ACTION_PAUSE_TRANSFER,
        DownloadTransferCommand.ACTION_RESUME_TRANSFER,
        DownloadTransferCommand.ACTION_CANCEL_TRANSFER,
    )

    fun flags(): Int = FLAG_IMMUTABLE or FLAG_UPDATE_CURRENT

    fun usesForegroundServicePendingIntent(sdkInt: Int): Boolean = sdkInt >= 26

    fun identity(downloadId: String?, serviceAction: String?): TransferNotificationActionIdentity? {
        val id = downloadId?.trim().orEmpty()
        val action = serviceAction?.trim().orEmpty()
        if (id.isEmpty() || action !in CONTROL_ACTIONS) return null
        return TransferNotificationActionIdentity(
            downloadId = id,
            serviceAction = action,
            requestCode = requestCode(id, action),
            data = intentData(id, action),
            flags = flags(),
            pendingIntentKind = PENDING_INTENT_KIND_FOREGROUND_SERVICE,
        )
    }

    fun requestCode(downloadId: String, serviceAction: String): Int {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$downloadId\u0001$serviceAction".toByteArray(StandardCharsets.UTF_8))
        val packed = ((digest[0].toInt() and 0xFF) shl 24) or
            ((digest[1].toInt() and 0xFF) shl 16) or
            ((digest[2].toInt() and 0xFF) shl 8) or
            (digest[3].toInt() and 0xFF)
        return (packed and 0x0FFFFFFF) or 0x20000000
    }

    fun intentData(downloadId: String, serviceAction: String): String {
        return "sdm://transfer-command/${percentEncode(downloadId)}/${percentEncode(serviceAction)}"
    }

    private fun percentEncode(value: String): String {
        val allowed = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._~"
        val encoded = StringBuilder(value.length)
        value.codePoints().forEach { codePoint ->
            if (codePoint < 128 && codePoint.toChar() in allowed) {
                encoded.append(codePoint.toChar())
            } else {
                val bytes = String(intArrayOf(codePoint), 0, 1).toByteArray(StandardCharsets.UTF_8)
                for (byte in bytes) {
                    encoded.append('%')
                    encoded.append(((byte.toInt() ushr 4) and 0xF).toString(16).uppercase())
                    encoded.append((byte.toInt() and 0xF).toString(16).uppercase())
                }
            }
        }
        return encoded.toString()
    }
}
