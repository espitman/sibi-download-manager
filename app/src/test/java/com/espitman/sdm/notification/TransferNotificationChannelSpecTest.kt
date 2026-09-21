package com.espitman.sdm.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class TransferNotificationChannelSpecTest {
    @Test
    fun createUsesStableSilentLowImportanceChannel() {
        val spec = TransferNotificationChannelSpec.create(
            name = "File transfers",
            description = "Ongoing file transfers while SDM downloads in the background",
        )
        assertEquals(TransferNotificationChannelSpec.ID, spec.id)
        assertEquals("sdm.transfer", spec.id)
        assertEquals(TransferNotificationChannelSpec.IMPORTANCE_LOW, spec.importance)
        assertEquals(2, spec.importance)
        assertEquals(1001, TransferNotificationChannelSpec.ONGOING_NOTIFICATION_ID)
        assertFalse(spec.enableSound)
        assertFalse(spec.enableVibration)
        assertFalse(spec.showBadge)
    }

    @Test
    fun createTrimsUserFacingCopyAndRejectsBlankValues() {
        val spec = TransferNotificationChannelSpec.create(
            name = "  File transfers  ",
            description = "  Ongoing file transfers while SDM downloads in the background  ",
        )
        assertEquals("File transfers", spec.name)
        assertEquals(
            "Ongoing file transfers while SDM downloads in the background",
            spec.description,
        )

        assertThrows(IllegalArgumentException::class.java) {
            TransferNotificationChannelSpec.create(name = "   ", description = "Ongoing transfers")
        }
        assertThrows(IllegalArgumentException::class.java) {
            TransferNotificationChannelSpec.create(name = "File transfers", description = "")
        }
    }
}
