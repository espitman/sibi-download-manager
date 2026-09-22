package com.espitman.sdm.storage

sealed interface TransferSpacePreflightResult {
    data object Allowed : TransferSpacePreflightResult
    data class Insufficient(
        val requiredBytes: Long,
        val availableBytes: Long,
    ) : TransferSpacePreflightResult
}

object TransferSpacePreflight {
    const val INSUFFICIENT_STORAGE_ERROR = "Not enough storage"

    fun evaluate(
        knownFinalSizeBytes: Long?,
        existingValidPartBytes: Long,
        restartingFresh: Boolean,
        localCapacity: StorageCapacity,
        destinationTreeUri: String?,
        treeCapacity: StorageCapacity,
    ): TransferSpacePreflightResult {
        val total = knownFinalSizeBytes?.takeIf { it >= 0L } ?: return TransferSpacePreflightResult.Allowed
        val existing = if (restartingFresh) 0L else existingValidPartBytes.coerceAtLeast(0L)
        val localRequired = StorageBytes.remaining(total, existing)
        val localAvailable = localCapacity.availableBytes
        if (localAvailable != null &&
            !StorageBytes.hasSufficient(localAvailable, localRequired)
        ) {
            return TransferSpacePreflightResult.Insufficient(
                requiredBytes = localRequired,
                availableBytes = localAvailable,
            )
        }
        if (destinationTreeUri.isNullOrBlank()) {
            return TransferSpacePreflightResult.Allowed
        }
        val treeAvailable = treeCapacity.availableBytes
        if (treeAvailable != null &&
            !StorageBytes.hasSufficient(treeAvailable, total)
        ) {
            return TransferSpacePreflightResult.Insufficient(
                requiredBytes = total,
                availableBytes = treeAvailable,
            )
        }
        return TransferSpacePreflightResult.Allowed
    }
}
