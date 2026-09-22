package com.espitman.sdm.storage

data class PersistedSaveLocation(
    val treeUri: String?,
    val displayLabel: String,
) {
    companion object {
        val DEFAULT = PersistedSaveLocation(
            treeUri = null,
            displayLabel = SaveLocationLabels.DEFAULT,
        )
    }
}

sealed interface ActiveSaveLocation {
    val displayLabel: String

    data class AppSpecific(
        override val displayLabel: String = SaveLocationLabels.DEFAULT,
    ) : ActiveSaveLocation

    data class UserTree(
        val treeUri: String,
        override val displayLabel: String,
    ) : ActiveSaveLocation
}

enum class SaveLocationRecovery(val message: String) {
    InvalidUri("Save location is invalid. Using the app Downloads folder."),
    PermissionRevoked("Folder access was revoked. Using the app Downloads folder."),
    TreeDeleted("Selected folder was deleted. Using the app Downloads folder."),
    ProviderUnavailable("Selected folder is unavailable. Using the app Downloads folder."),
    NotWritable("Could not write to the selected folder. Using the app Downloads folder."),
}

data class ResolvedSaveLocation(
    val location: ActiveSaveLocation,
    val recovery: SaveLocationRecovery? = null,
) {
    val displayLabel: String get() = location.displayLabel
    val treeUri: String? get() = (location as? ActiveSaveLocation.UserTree)?.treeUri

    companion object {
        fun appSpecific(
            displayLabel: String = SaveLocationLabels.DEFAULT,
            recovery: SaveLocationRecovery? = null,
        ) = ResolvedSaveLocation(ActiveSaveLocation.AppSpecific(displayLabel), recovery)

        fun userTree(treeUri: String, displayLabel: String) =
            ResolvedSaveLocation(ActiveSaveLocation.UserTree(treeUri, displayLabel))
    }
}

sealed interface SaveLocationPickerResult {
    data class Accepted(val displayLabel: String) : SaveLocationPickerResult
    data object Canceled : SaveLocationPickerResult
    data class Failed(val message: String) : SaveLocationPickerResult
}
