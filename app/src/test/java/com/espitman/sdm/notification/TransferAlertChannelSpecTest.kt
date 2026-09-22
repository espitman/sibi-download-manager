package com.espitman.sdm.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TransferAlertChannelSpecTest {
    @Test
    fun alertChannelIsStableAudibleAndDistinctFromForegroundChannel() {
        val spec = TransferAlertChannelSpec.create(" Download alerts ", " Transfer alerts ")
        assertEquals("sdm.transfer.alerts", spec.id)
        assertNotEquals(TransferNotificationChannelSpec.ID, spec.id)
        assertEquals(3, spec.importance)
        assertEquals("Download alerts", spec.name)
        assertEquals("Transfer alerts", spec.description)
        assertNotEquals(TransferNotificationCoordinator.CHILD_NOTIFICATION_ID, TransferNotificationCoordinator.COMPLETION_NOTIFICATION_ID)
        assertNotEquals(TransferNotificationCoordinator.CHILD_NOTIFICATION_ID, TransferNotificationCoordinator.STALL_NOTIFICATION_ID)
        assertNotEquals(TransferNotificationCoordinator.COMPLETION_NOTIFICATION_ID, TransferNotificationCoordinator.STALL_NOTIFICATION_ID)
    }

    @Test
    fun blankCopyIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            TransferAlertChannelSpec.create("", "description")
        }
        assertThrows(IllegalArgumentException::class.java) {
            TransferAlertChannelSpec.create("name", "   ")
        }
    }
}
