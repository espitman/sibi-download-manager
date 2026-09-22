package com.espitman.sdm.storage

class SaveLocationCoordinator(
    private val store: SaveLocationStore,
    private val grants: TreeUriGrantStore,
    private val trees: UserTreeAccess,
) {
    fun current(): PersistedSaveLocation = store.read()

    fun validatePersisted(): SaveLocationRecovery? = resolve(persistRecovery = true).recovery

    fun resolveForNewDownload(): ResolvedSaveLocation = resolve(persistRecovery = true)

    fun applyPickerResult(
        resultCode: Int,
        uriString: String?,
        returnedGrantFlags: Int = 0,
    ): SaveLocationPickerResult {
        return when (
            val outcome = StorageAccessPolicy.interpretPickerResult(resultCode, uriString, returnedGrantFlags)
        ) {
            OpenDocumentTreeOutcome.Canceled -> SaveLocationPickerResult.Canceled
            is OpenDocumentTreeOutcome.InvalidTree ->
                SaveLocationPickerResult.Failed("Could not use that folder")
            is OpenDocumentTreeOutcome.Accepted -> acceptTree(outcome.uriString, outcome.takeFlags)
        }
    }

    private fun acceptTree(treeUri: String, takeFlags: Int): SaveLocationPickerResult {
        when (val taken = grants.takeReadWrite(treeUri, takeFlags)) {
            is PersistableGrantResult.InvalidTree ->
                return SaveLocationPickerResult.Failed("Could not use that folder")
            is PersistableGrantResult.Failure ->
                return SaveLocationPickerResult.Failed("Could not keep access to that folder")
            is PersistableGrantResult.Success -> Unit
        }
        val inspection = trees.inspect(treeUri)
        if (inspection.state != UserTreeState.Writable) {
            grants.releaseReadWrite(treeUri)
            return SaveLocationPickerResult.Failed(messageFor(inspection.state))
        }
        val previous = store.read().treeUri
        if (!previous.isNullOrBlank() && previous != treeUri) {
            grants.releaseReadWrite(previous)
        }
        val label = SaveLocationLabels.fromTree(treeUri, inspection.displayName)
        store.persistUserTree(treeUri, label)
        return SaveLocationPickerResult.Accepted(label)
    }

    private fun resolve(persistRecovery: Boolean): ResolvedSaveLocation {
        val persisted = store.read()
        if (persisted.treeUri.isNullOrBlank()) {
            return ResolvedSaveLocation.appSpecific(persisted.displayLabel)
        }
        return when (val classified = StorageAccessPolicy.classifyConfiguredLocation(persisted.treeUri)) {
            StorageLocation.AppSpecificDownloads ->
                ResolvedSaveLocation.appSpecific(persisted.displayLabel)
            is StorageLocation.InvalidConfiguredUri ->
                recover(SaveLocationRecovery.InvalidUri, persistRecovery)
            is StorageLocation.UserSelectedTree -> resolveUserTree(
                classified.uriString,
                persisted.displayLabel,
                persistRecovery,
            )
        }
    }

    private fun resolveUserTree(
        treeUri: String,
        storedLabel: String,
        persistRecovery: Boolean,
    ): ResolvedSaveLocation {
        if (!grants.hasReadWrite(treeUri)) {
            return recover(SaveLocationRecovery.PermissionRevoked, persistRecovery)
        }
        val inspection = trees.inspect(treeUri)
        val recovery = when (inspection.state) {
            UserTreeState.Writable -> null
            UserTreeState.Missing -> SaveLocationRecovery.TreeDeleted
            UserTreeState.ProviderUnavailable -> SaveLocationRecovery.ProviderUnavailable
            UserTreeState.PermissionRevoked -> SaveLocationRecovery.PermissionRevoked
            UserTreeState.NotWritable -> SaveLocationRecovery.NotWritable
        }
        if (recovery != null) {
            return recover(recovery, persistRecovery)
        }
        val label = storedLabel.trim().ifEmpty {
            SaveLocationLabels.fromTree(treeUri, inspection.displayName)
        }
        return ResolvedSaveLocation.userTree(treeUri, label)
    }

    private fun recover(
        reason: SaveLocationRecovery,
        persistRecovery: Boolean,
    ): ResolvedSaveLocation {
        if (persistRecovery) {
            val previous = store.read().treeUri
            if (!previous.isNullOrBlank()) {
                grants.releaseReadWrite(previous)
            }
            store.persistAppSpecific()
        }
        return ResolvedSaveLocation.appSpecific(recovery = reason)
    }

    private fun messageFor(state: UserTreeState): String = when (state) {
        UserTreeState.Missing -> SaveLocationRecovery.TreeDeleted.message
        UserTreeState.ProviderUnavailable -> SaveLocationRecovery.ProviderUnavailable.message
        UserTreeState.PermissionRevoked -> SaveLocationRecovery.PermissionRevoked.message
        UserTreeState.NotWritable -> SaveLocationRecovery.NotWritable.message
        UserTreeState.Writable -> SaveLocationRecovery.NotWritable.message
    }
}
