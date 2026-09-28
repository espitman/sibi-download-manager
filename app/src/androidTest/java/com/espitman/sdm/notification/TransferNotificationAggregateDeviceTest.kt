package com.espitman.sdm.notification

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.espitman.sdm.data.settings.SdmSettings
import com.espitman.sdm.domain.Download
import com.espitman.sdm.domain.DownloadState
import com.espitman.sdm.download.DownloadTransferCommand
import com.espitman.sdm.download.DownloadTransferService
import com.espitman.sdm.ui.RecentTransferSpeedTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TransferNotificationAggregateDeviceTest {
    @Test
    fun replacesLegacyChildrenWithExactlyOneAggregateAndClearsItAfterWorkEnds() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = context.getSystemService(NotificationManager::class.java)
        val coordinator = TransferNotificationCoordinator(context)
        coordinator.ensureChannel()
        try {
            val oldChild = NotificationCompat.Builder(context, TransferNotificationChannelSpec.ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("Old per-download notification")
                .build()
            manager.notify("first", TransferNotificationCoordinator.CHILD_NOTIFICATION_ID, oldChild)
            manager.notify("second", TransferNotificationCoordinator.CHILD_NOTIFICATION_ID, oldChild)
            awaitLegacyChildren(manager, context.packageName, setOf("first", "second"))

            coordinator.updateActiveTransfers(
                downloads = listOf(
                    record("first", DownloadState.DOWNLOADING, 25L),
                    record("second", DownloadState.DOWNLOADING, 50L),
                ),
                settings = SdmSettings(),
                nowElapsedMs = 1_000L,
            )

            val posted = awaitPosted(
                manager,
                context.packageName,
                NotificationExpectation(
                    compactContains = "2 active",
                    progress = 37,
                    actionTitles = listOf("Pause All"),
                ),
            )
            val notification = posted.single().notification
            assertTrue(notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains("2 active"))
            assertEquals(37, notification.extras.getInt(Notification.EXTRA_PROGRESS))

            coordinator.onServiceTeardown(emptyList(), SdmSettings(downloadComplete = false))
            awaitPosted(manager, context.packageName, NotificationExpectation(empty = true))
        } finally {
            manager.cancelAll()
        }
    }

    @Test
    fun pausedWorkRemainsOneDismissibleSummaryAfterServiceStops() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = context.getSystemService(NotificationManager::class.java)
        val coordinator = TransferNotificationCoordinator(context)
        coordinator.ensureChannel()
        try {
            val paused = record("paused", DownloadState.PAUSED, 30L)
            coordinator.updateActiveTransfers(listOf(paused), SdmSettings(), 1_000L)
            coordinator.onServiceTeardown(listOf(paused), SdmSettings())

            val notifications = awaitPosted(
                manager,
                context.packageName,
                NotificationExpectation(
                    compactContains = "1 paused",
                    actionTitles = listOf("Resume All"),
                    ongoing = false,
                ),
            )
            assertEquals(0, notifications.single().notification.flags and Notification.FLAG_ONGOING_EVENT)
        } finally {
            manager.cancelAll()
        }
    }

    @Test
    fun completionReusesTheSameNotificationIdInsteadOfPostingPerFileAlerts() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = context.getSystemService(NotificationManager::class.java)
        val coordinator = TransferNotificationCoordinator(context)
        coordinator.ensureChannel()
        try {
            coordinator.updateActiveTransfers(
                listOf(record("one", DownloadState.DOWNLOADING, 50L)), SdmSettings(), 1_000L,
            )
            awaitPosted(
                manager,
                context.packageName,
                NotificationExpectation(compactContains = "1 active", actionTitles = listOf("Pause All")),
            )
            val completed = record("one", DownloadState.COMPLETED, 100L)
            coordinator.updateActiveTransfers(listOf(completed), SdmSettings(), 2_000L)
            coordinator.onServiceTeardown(listOf(completed), SdmSettings())

            awaitPosted(
                manager,
                context.packageName,
                NotificationExpectation(compactContains = "1 download completed", actionTitles = emptyList()),
            )
        } finally {
            manager.cancelAll()
        }
    }

    @Test
    fun liveSpeedDecaysOnTheSameIdAndBulkActionsUseImmutableForegroundServiceIntents() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = context.getSystemService(NotificationManager::class.java)
        val coordinator = TransferNotificationCoordinator(context)
        coordinator.ensureChannel()
        try {
            val first = record("live", DownloadState.DOWNLOADING, 0L, totalBytes = 2_000_000L, updatedAt = 1_000L)
            coordinator.updateActiveTransfers(listOf(first), SdmSettings(), 1_000L)
            awaitPosted(
                manager,
                context.packageName,
                NotificationExpectation(
                    compactStartsWith = "0 MB/s",
                    compactContains = "1 active",
                    actionTitles = listOf("Pause All"),
                ),
            )

            val progressing = first.copy(downloadedBytes = 1_000_000L, updatedAtEpochMillis = 2_000L)
            coordinator.updateActiveTransfers(listOf(progressing), SdmSettings(), 2_000L)
            val liveNotification = awaitPosted(
                manager,
                context.packageName,
                NotificationExpectation(
                    compactStartsWith = "1 MB/s",
                    compactContains = "1 active",
                    contentEquals = "1 MB/s | 976.56 KB / 1.91 MB | 1 active",
                    actionTitles = listOf("Pause All"),
                ),
            ).single().notification
            assertBulkAction(context, liveNotification, TransferNotificationActionKind.PAUSE_ALL)

            coordinator.updateActiveTransfers(
                listOf(progressing),
                SdmSettings(),
                2_000L + RecentTransferSpeedTracker.DEFAULT_STALE_WINDOW_MILLIS,
            )
            awaitPosted(
                manager,
                context.packageName,
                NotificationExpectation(
                    compactStartsWith = "1 MB/s",
                    compactContains = "1 active",
                    actionTitles = listOf("Pause All"),
                ),
            )

            coordinator.updateActiveTransfers(
                listOf(progressing),
                SdmSettings(),
                2_000L + RecentTransferSpeedTracker.DEFAULT_STALE_WINDOW_MILLIS + 1L,
            )
            awaitPosted(
                manager,
                context.packageName,
                NotificationExpectation(
                    compactStartsWith = "0 MB/s",
                    contentEquals = "0 MB/s | 976.56 KB / 1.91 MB | 1 active",
                    actionTitles = listOf("Pause All"),
                ),
            )

            val mixed = listOf(
                progressing,
                record("paused", DownloadState.PAUSED, 30L),
            )
            coordinator.updateActiveTransfers(mixed, SdmSettings(), 8_000L)
            val mixedNotification = awaitPosted(
                manager,
                context.packageName,
                NotificationExpectation(
                    compactContains = "1 active",
                    extraContains = "1 paused",
                    actionTitles = listOf("Pause All", "Resume All"),
                ),
            ).single().notification
            assertBulkAction(context, mixedNotification, TransferNotificationActionKind.PAUSE_ALL)
            assertBulkAction(context, mixedNotification, TransferNotificationActionKind.RESUME_ALL)
            holdMixedNotificationIfRequested()
        } finally {
            manager.cancelAll()
        }
    }

    private data class NotificationExpectation(
        val count: Int = 1,
        val id: Int = TransferNotificationChannelSpec.ONGOING_NOTIFICATION_ID,
        val compactContains: String? = null,
        val extraContains: String? = null,
        val compactStartsWith: String? = null,
        val contentEquals: String? = null,
        val actionTitles: List<String>? = null,
        val progress: Int? = null,
        val ongoing: Boolean? = null,
        val empty: Boolean = false,
    )

    private fun awaitLegacyChildren(
        manager: NotificationManager,
        packageName: String,
        tags: Set<String>,
    ) {
        val deadline = SystemClock.elapsedRealtime() + WAIT_TIMEOUT_MS
        while (true) {
            val current = manager.activeNotifications.filter { it.packageName == packageName }
            val children = current.filter { it.id == TransferNotificationCoordinator.CHILD_NOTIFICATION_ID }
            if (children.size == tags.size && children.map { it.tag }.toSet() == tags) return
            check(SystemClock.elapsedRealtime() < deadline) {
                "legacy children not posted: ${current.map { it.id to it.tag }}"
            }
            SystemClock.sleep(POLL_MS)
        }
    }

    private fun awaitPosted(
        manager: NotificationManager,
        packageName: String,
        expectation: NotificationExpectation,
    ): List<StatusBarNotification> {
        val deadline = SystemClock.elapsedRealtime() + WAIT_TIMEOUT_MS
        while (true) {
            val current = manager.activeNotifications.filter { it.packageName == packageName }
            if (matches(current, expectation)) return current
            check(SystemClock.elapsedRealtime() < deadline) {
                "notification not ready: ${describe(current)} expected $expectation"
            }
            SystemClock.sleep(POLL_MS)
        }
    }

    private fun matches(
        current: List<StatusBarNotification>,
        expectation: NotificationExpectation,
    ): Boolean {
        if (expectation.empty) return current.isEmpty()
        if (current.size != expectation.count) return false
        if (current.any { it.id != expectation.id }) return false
        val notification = current.single().notification
        val compact = notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val expanded = notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
        val subText = notification.extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
        if (expectation.compactStartsWith != null && !compact.startsWith(expectation.compactStartsWith)) return false
        if (expectation.compactContains != null && !compact.contains(expectation.compactContains)) return false
        if (expectation.extraContains != null && !compact.contains(expectation.extraContains)) return false
        if (expectation.contentEquals != null && compact != expectation.contentEquals) return false
        if (compact.contains('\n')) return false
        if (!expanded.isNullOrEmpty()) return false
        if (!subText.isNullOrEmpty()) return false
        if (expectation.actionTitles != null && actionTitles(notification) != expectation.actionTitles) return false
        if (expectation.progress != null && notification.extras.getInt(Notification.EXTRA_PROGRESS) != expectation.progress) {
            return false
        }
        if (expectation.ongoing == false && notification.flags and Notification.FLAG_ONGOING_EVENT != 0) return false
        return true
    }

    private fun describe(current: List<StatusBarNotification>): String = current.joinToString { item ->
        val text = item.notification.extras.getCharSequence(Notification.EXTRA_TEXT)
        "${item.id}/${item.tag}/$text/${actionTitles(item.notification)}"
    }

    private fun actionTitles(notification: Notification): List<String> =
        notification.actions.orEmpty().map { it.title.toString() }

    private fun assertBulkAction(
        context: Context,
        notification: Notification,
        kind: TransferNotificationActionKind,
    ) {
        val posted = notification.actions.orEmpty().single { it.title.toString() == TransferNotificationActions.label(kind) }
        val expected = expectedBulkPendingIntent(context, TransferNotificationActions.serviceAction(kind))
        assertEquals(expected, posted.actionIntent)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            assertTrue(posted.actionIntent.isImmutable)
        }
    }

    private fun expectedBulkPendingIntent(context: Context, action: String): PendingIntent {
        val identity = TransferNotificationPendingIntentSpec.identity(
            DownloadTransferCommand.BULK_TARGET_ID,
            action,
        )!!
        val intent = DownloadTransferService.controlIntent(context, identity.downloadId, action)!!
        return PendingIntent.getForegroundService(
            context,
            identity.requestCode,
            intent,
            TransferNotificationPendingIntentSpec.flags(),
        )
    }

    private fun holdMixedNotificationIfRequested() {
        val arguments = InstrumentationRegistry.getArguments()
        val requested = arguments.getString(HOLD_MIXED_ARGUMENT)
            ?.trim()
            ?.lowercase()
        val holdMs = when (requested) {
            "1", "true", "yes" -> DEFAULT_HOLD_MS
            else -> requested?.toLongOrNull()?.takeIf { it > 0L } ?: 0L
        }
        if (holdMs > 0L) SystemClock.sleep(holdMs)
    }

    private fun record(
        id: String,
        state: DownloadState,
        downloaded: Long,
        totalBytes: Long = 100L,
        updatedAt: Long = 1L,
    ) = Download(
        id = id,
        url = "https://example.com/$id",
        fileName = "$id.bin",
        destinationPath = "/downloads/$id.bin",
        totalBytes = totalBytes,
        downloadedBytes = downloaded,
        state = state,
        error = null,
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = updatedAt,
        startedAtEpochMillis = 1L,
        completedAtEpochMillis = if (state == DownloadState.COMPLETED) 2L else null,
    )

    companion object {
        private const val POLL_MS = 50L
        private const val WAIT_TIMEOUT_MS = 5_000L
        private const val DEFAULT_HOLD_MS = 5_000L
        const val HOLD_MIXED_ARGUMENT = "holdMixedNotification"
    }
}
