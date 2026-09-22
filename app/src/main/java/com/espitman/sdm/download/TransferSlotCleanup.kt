package com.espitman.sdm.download

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

object TransferSlotCleanup {
    suspend fun afterTransferFinished(
        acceptingWork: Boolean,
        releaseClaim: suspend () -> Unit,
        reschedule: suspend () -> Unit,
    ) {
        withContext(NonCancellable) {
            releaseClaim()
            if (TransferServiceLifecyclePolicy.shouldRescheduleAfterSlotRelease(acceptingWork)) {
                reschedule()
            }
        }
    }

    suspend fun afterRejectedStart(releaseClaim: suspend () -> Unit) {
        withContext(NonCancellable) {
            releaseClaim()
        }
    }
}
