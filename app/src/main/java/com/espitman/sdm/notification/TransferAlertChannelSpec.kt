package com.espitman.sdm.notification

data class TransferAlertChannelSpec(
    val id: String,
    val name: String,
    val description: String,
    val importance: Int,
) {
    companion object {
        const val ID = "sdm.transfer.alerts"
        const val IMPORTANCE_DEFAULT = 3

        fun create(name: String, description: String): TransferAlertChannelSpec {
            require(name.isNotBlank()) { "Channel name must be non-blank" }
            require(description.isNotBlank()) { "Channel description must be non-blank" }
            return TransferAlertChannelSpec(
                id = ID,
                name = name.trim(),
                description = description.trim(),
                importance = IMPORTANCE_DEFAULT,
            )
        }
    }
}
