package com.espitman.sdm.storage

import com.espitman.sdm.domain.DownloadFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferSpacePreflightTest {
    @Test
    fun unknownSizeOrUnknownCapacityDoesNotBlock() {
        assertEquals(
            TransferSpacePreflightResult.Allowed,
            TransferSpacePreflight.evaluate(
                knownFinalSizeBytes = null,
                existingValidPartBytes = 0L,
                restartingFresh = false,
                localCapacity = StorageCapacity.from(1L, 0L),
                destinationTreeUri = null,
                treeCapacity = StorageCapacity.Unknown,
            ),
        )
        assertEquals(
            TransferSpacePreflightResult.Allowed,
            TransferSpacePreflight.evaluate(
                knownFinalSizeBytes = 64L,
                existingValidPartBytes = 0L,
                restartingFresh = false,
                localCapacity = StorageCapacity.Unknown,
                destinationTreeUri = "content://docs/tree/root",
                treeCapacity = StorageCapacity.Unknown,
            ),
        )
    }

    @Test
    fun exactAvailableEqualsRequiredPassesForFreshLocal() {
        val allowed = TransferSpacePreflight.evaluate(
            knownFinalSizeBytes = 64L,
            existingValidPartBytes = 0L,
            restartingFresh = false,
            localCapacity = StorageCapacity.from(totalBytes = 100L, availableBytes = 64L),
            destinationTreeUri = null,
            treeCapacity = StorageCapacity.Unknown,
        )
        assertEquals(TransferSpacePreflightResult.Allowed, allowed)
    }

    @Test
    fun insufficientFreshRequiresTheFullKnownSize() {
        val result = TransferSpacePreflight.evaluate(
            knownFinalSizeBytes = 64L,
            existingValidPartBytes = 0L,
            restartingFresh = false,
            localCapacity = StorageCapacity.from(totalBytes = 100L, availableBytes = 63L),
            destinationTreeUri = null,
            treeCapacity = StorageCapacity.Unknown,
        )
        assertEquals(TransferSpacePreflightResult.Insufficient(64L, 63L), result)
    }

    @Test
    fun resumeRequiresOnlyRemainingBytes() {
        val remainingFits = TransferSpacePreflight.evaluate(
            knownFinalSizeBytes = 64L,
            existingValidPartBytes = 40L,
            restartingFresh = false,
            localCapacity = StorageCapacity.from(totalBytes = 100L, availableBytes = 24L),
            destinationTreeUri = null,
            treeCapacity = StorageCapacity.Unknown,
        )
        assertEquals(TransferSpacePreflightResult.Allowed, remainingFits)

        val remainingShort = TransferSpacePreflight.evaluate(
            knownFinalSizeBytes = 64L,
            existingValidPartBytes = 40L,
            restartingFresh = false,
            localCapacity = StorageCapacity.from(totalBytes = 100L, availableBytes = 23L),
            destinationTreeUri = null,
            treeCapacity = StorageCapacity.Unknown,
        )
        assertEquals(TransferSpacePreflightResult.Insufficient(24L, 23L), remainingShort)
    }

    @Test
    fun safChecksStagingRemainingAndFullFinalObjectWhenProviderExposesCapacity() {
        val treeUri = "content://com.android.externalstorage.documents/tree/primary%3ADownload"
        val stagingOkFinalShort = TransferSpacePreflight.evaluate(
            knownFinalSizeBytes = 64L,
            existingValidPartBytes = 40L,
            restartingFresh = false,
            localCapacity = StorageCapacity.from(totalBytes = 1_000L, availableBytes = 24L),
            destinationTreeUri = treeUri,
            treeCapacity = StorageCapacity.from(totalBytes = 200L, availableBytes = 63L),
        )
        assertEquals(TransferSpacePreflightResult.Insufficient(64L, 63L), stagingOkFinalShort)

        val stagingShortFinalOk = TransferSpacePreflight.evaluate(
            knownFinalSizeBytes = 64L,
            existingValidPartBytes = 40L,
            restartingFresh = false,
            localCapacity = StorageCapacity.from(totalBytes = 1_000L, availableBytes = 23L),
            destinationTreeUri = treeUri,
            treeCapacity = StorageCapacity.from(totalBytes = 200L, availableBytes = 64L),
        )
        assertEquals(TransferSpacePreflightResult.Insufficient(24L, 23L), stagingShortFinalOk)

        val bothExact = TransferSpacePreflight.evaluate(
            knownFinalSizeBytes = 64L,
            existingValidPartBytes = 40L,
            restartingFresh = false,
            localCapacity = StorageCapacity.from(totalBytes = 1_000L, availableBytes = 24L),
            destinationTreeUri = treeUri,
            treeCapacity = StorageCapacity.from(totalBytes = 200L, availableBytes = 64L),
        )
        assertEquals(TransferSpacePreflightResult.Allowed, bothExact)

        val unknownTreeDoesNotBlock = TransferSpacePreflight.evaluate(
            knownFinalSizeBytes = 64L,
            existingValidPartBytes = 40L,
            restartingFresh = false,
            localCapacity = StorageCapacity.from(totalBytes = 1_000L, availableBytes = 24L),
            destinationTreeUri = treeUri,
            treeCapacity = StorageCapacity.from(totalBytes = null, availableBytes = null),
        )
        assertEquals(TransferSpacePreflightResult.Allowed, unknownTreeDoesNotBlock)
    }

    @Test
    fun freshRestartRequiresTheNewFullBodySize() {
        val result = TransferSpacePreflight.evaluate(
            knownFinalSizeBytes = 80L,
            existingValidPartBytes = 40L,
            restartingFresh = true,
            localCapacity = StorageCapacity.from(totalBytes = 100L, availableBytes = 79L),
            destinationTreeUri = null,
            treeCapacity = StorageCapacity.Unknown,
        )
        assertEquals(TransferSpacePreflightResult.Insufficient(80L, 79L), result)
        assertEquals(
            DownloadFailure.INSUFFICIENT_STORAGE,
            DownloadFailure.classify(TransferSpacePreflight.INSUFFICIENT_STORAGE_ERROR),
        )
        assertEquals(
            DownloadFailure.INSUFFICIENT_STORAGE,
            DownloadFailure.classify("java.io.IOException: No space left on device"),
        )
        assertTrue(DownloadFailure.classify("ENOSPC") == DownloadFailure.INSUFFICIENT_STORAGE)
    }
}
