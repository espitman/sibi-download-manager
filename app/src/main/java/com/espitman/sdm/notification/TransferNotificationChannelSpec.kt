package com.espitman.sdm.notification

/**
 * Stable transfer-notification channel configuration.
 * Importance 2 matches [android.app.NotificationManager.IMPORTANCE_LOW].
 */
data class TransferNotificationChannelSpec(
    val id: String,
    val name: String,
    val description: String,
    val importance: Int,
    val enableSound: Boolean,
    val enableVibration: Boolean,
    val showBadge: Boolean,
) {
    companion object {
        const val ID = "sdm.transfer"
        const val ONGOING_NOTIFICATION_ID = 1001
        const val IMPORTANCE_LOW = 2

        fun create(name: String, description: String): TransferNotificationChannelSpec {
            require(name.isNotBlank()) { "Channel name must be user-facing and non-blank" }
            require(description.isNotBlank()) { "Channel description must be user-facing and non-blank" }
            return TransferNotificationChannelSpec(
                id = ID,
                name = name.trim(),
                description = description.trim(),
                importance = IMPORTANCE_LOW,
                enableSound = false,
                enableVibration = false,
                showBadge = false,
            )
        }
    }
}
