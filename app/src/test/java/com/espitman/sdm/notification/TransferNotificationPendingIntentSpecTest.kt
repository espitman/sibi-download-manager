package com.espitman.sdm.notification

import com.espitman.sdm.download.DownloadTransferCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferNotificationPendingIntentSpecTest {
    @Test
    fun flagsAreImmutableAndUpdateCurrent() {
        assertEquals(0x04000000, TransferNotificationPendingIntentSpec.FLAG_IMMUTABLE)
        assertEquals(0x08000000, TransferNotificationPendingIntentSpec.FLAG_UPDATE_CURRENT)
        assertEquals(
            TransferNotificationPendingIntentSpec.FLAG_IMMUTABLE or
                TransferNotificationPendingIntentSpec.FLAG_UPDATE_CURRENT,
            TransferNotificationPendingIntentSpec.flags(),
        )
        assertEquals(0, TransferNotificationPendingIntentSpec.flags() and 0x02000000)
    }

    @Test
    fun usesForegroundServicePendingIntentOnSupportedSdks() {
        assertTrue(TransferNotificationPendingIntentSpec.usesForegroundServicePendingIntent(26))
        assertTrue(TransferNotificationPendingIntentSpec.usesForegroundServicePendingIntent(34))
        assertTrue(TransferNotificationPendingIntentSpec.usesForegroundServicePendingIntent(35))
    }

    @Test
    fun identityIsStablePerDownloadAndAction() {
        val first = TransferNotificationPendingIntentSpec.identity(
            "download-a",
            DownloadTransferCommand.ACTION_PAUSE_TRANSFER,
        )!!
        val second = TransferNotificationPendingIntentSpec.identity(
            "  download-a  ",
            DownloadTransferCommand.ACTION_PAUSE_TRANSFER,
        )!!
        assertEquals(first, second)
        assertEquals("download-a", first.downloadId)
        assertEquals(DownloadTransferCommand.ACTION_PAUSE_TRANSFER, first.serviceAction)
        assertEquals(
            TransferNotificationPendingIntentSpec.PENDING_INTENT_KIND_FOREGROUND_SERVICE,
            first.pendingIntentKind,
        )
        assertEquals(TransferNotificationPendingIntentSpec.flags(), first.flags)
        assertTrue(first.data.contains("download-a"))
        assertTrue(first.data.startsWith("sdm://transfer-command/"))
    }

    @Test
    fun requestCodesDifferAcrossActionsAndDownloadsIncludingJavaHashCollisions() {
        val pauseA = TransferNotificationPendingIntentSpec.identity(
            "Aa",
            DownloadTransferCommand.ACTION_PAUSE_TRANSFER,
        )!!
        val pauseB = TransferNotificationPendingIntentSpec.identity(
            "BB",
            DownloadTransferCommand.ACTION_PAUSE_TRANSFER,
        )!!
        val resumeA = TransferNotificationPendingIntentSpec.identity(
            "Aa",
            DownloadTransferCommand.ACTION_RESUME_TRANSFER,
        )!!
        val cancelA = TransferNotificationPendingIntentSpec.identity(
            "Aa",
            DownloadTransferCommand.ACTION_CANCEL_TRANSFER,
        )!!

        assertEquals(2112, "Aa".hashCode())
        assertEquals(2112, "BB".hashCode())
        assertNotEquals(pauseA.requestCode, pauseB.requestCode)
        assertNotEquals(pauseA.data, pauseB.data)
        assertNotEquals(pauseA.requestCode, resumeA.requestCode)
        assertNotEquals(pauseA.requestCode, cancelA.requestCode)
        assertNotEquals(resumeA.requestCode, cancelA.requestCode)
        assertNotEquals(pauseA.data, resumeA.data)
        assertTrue(pauseA.requestCode >= 0x20000000)
        assertTrue(pauseA.requestCode != TransferNotificationChannelSpec.ONGOING_NOTIFICATION_ID)
        assertTrue(pauseA.requestCode != TransferNotificationCoordinator.CHILD_NOTIFICATION_ID)
    }

    @Test
    fun requestCodesStayUniqueAcrossAGridOfDownloadsAndActions() {
        val ids = listOf("Aa", "BB", "download-a", "download-b", "id-1", "id-2") +
            (0 until 40).map { "grid-$it" }
        val actions = listOf(
            DownloadTransferCommand.ACTION_PAUSE_TRANSFER,
            DownloadTransferCommand.ACTION_RESUME_TRANSFER,
            DownloadTransferCommand.ACTION_CANCEL_TRANSFER,
        )
        val seenCodes = mutableSetOf<Int>()
        val seenData = mutableSetOf<String>()
        ids.forEach { id ->
            actions.forEach { action ->
                val identity = TransferNotificationPendingIntentSpec.identity(id, action)!!
                assertTrue("requestCode collision for $id $action", seenCodes.add(identity.requestCode))
                assertTrue("data collision for $id $action", seenData.add(identity.data))
            }
        }
    }

    @Test
    fun invalidIdsAndActionsHaveNoIdentity() {
        assertNull(
            TransferNotificationPendingIntentSpec.identity(
                "",
                DownloadTransferCommand.ACTION_PAUSE_TRANSFER,
            ),
        )
        assertNull(
            TransferNotificationPendingIntentSpec.identity(
                "   ",
                DownloadTransferCommand.ACTION_RESUME_TRANSFER,
            ),
        )
        assertNull(
            TransferNotificationPendingIntentSpec.identity(null, DownloadTransferCommand.ACTION_CANCEL_TRANSFER),
        )
        assertNull(TransferNotificationPendingIntentSpec.identity("download-a", null))
        assertNull(TransferNotificationPendingIntentSpec.identity("download-a", "   "))
        assertNull(
            TransferNotificationPendingIntentSpec.identity(
                "download-a",
                DownloadTransferCommand.ACTION_START_TRANSFER,
            ),
        )
        assertNull(
            TransferNotificationPendingIntentSpec.identity(
                "download-a",
                TransferNotificationCoordinator.ACTION_OPEN_DOWNLOAD,
            ),
        )
    }
}
