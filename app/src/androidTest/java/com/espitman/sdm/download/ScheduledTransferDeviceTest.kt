package com.espitman.sdm.download

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.espitman.sdm.data.AppRepositories
import com.espitman.sdm.data.settings.SettingsRepository
import com.espitman.sdm.domain.DownloadSchedule
import com.espitman.sdm.domain.DownloadPauseCause
import com.espitman.sdm.domain.DownloadState
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScheduledTransferDeviceTest {
    @Test fun scheduledDownloadStaysQueuedUntilAlarmThenCompletes() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settings = SettingsRepository.get(context)
        val before = settings.settings.value
        val server = TinyHttpServer()
        val repository = AppRepositories.downloads(context)
        var id: String? = null
        try {
            settings.update { it.copy(wifiOnly = false) }
            val start = System.currentTimeMillis() + 8_000L
            val result = AppRepositories.submissionCoordinator(context).submit(
                url = "http://127.0.0.1:${server.port}/scheduled.bin",
                startNow = true,
                schedule = DownloadSchedule(DownloadSchedule.Kind.ONCE, startEpochMillis = start),
            )
            assertTrue(result is SubmissionResult.Success)
            id = (result as SubmissionResult.Success).download.id
            delay(2_000L)
            assertEquals(DownloadState.QUEUED, repository.get(id)!!.state)
            val deadline = System.currentTimeMillis() + 25_000L
            while (System.currentTimeMillis() < deadline && repository.get(id)!!.state != DownloadState.COMPLETED) {
                delay(300L)
            }
            assertEquals(DownloadState.COMPLETED, repository.get(id)!!.state)
            assertEquals(1_024L, repository.get(id)!!.downloadedBytes)
        } finally {
            id?.let { downloadId ->
                val record = repository.get(downloadId)
                record?.destinationPath?.let { java.io.File(it).delete() }
                repository.delete(downloadId)
            }
            settings.update { before }
            server.close()
        }
    }

    @Test fun endOfWindowPausesActiveTransferAtItsPartialOffset() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settings = SettingsRepository.get(context)
        val before = settings.settings.value
        val server = TinyHttpServer(bodySize = 102_400, delayPerChunkMs = 150L)
        val repository = AppRepositories.downloads(context)
        var id: String? = null
        try {
            settings.update { it.copy(wifiOnly = false) }
            val now = System.currentTimeMillis()
            val result = AppRepositories.submissionCoordinator(context).submit(
                url = "http://127.0.0.1:${server.port}/slow.bin",
                startNow = true,
                schedule = DownloadSchedule(
                    DownloadSchedule.Kind.ONCE,
                    startEpochMillis = now + 6_000L,
                    endEpochMillis = now + 12_000L,
                ),
            )
            assertTrue(result is SubmissionResult.Success)
            id = (result as SubmissionResult.Success).download.id
            val observedStates = mutableSetOf<DownloadState>()
            val deadline = System.currentTimeMillis() + 25_000L
            while (System.currentTimeMillis() < deadline && repository.get(id)!!.pauseCause != DownloadPauseCause.SCHEDULE) {
                observedStates += repository.get(id)!!.state
                delay(200L)
            }
            val paused = repository.get(id)!!
            assertEquals("Observed $observedStates, error=${paused.error}", DownloadState.PAUSED, paused.state)
            assertEquals(DownloadPauseCause.SCHEDULE, paused.pauseCause)
            assertTrue(paused.downloadedBytes in 1 until 102_400L)
        } finally {
            id?.let { downloadId ->
                val record = repository.get(downloadId)
                record?.destinationPath?.let { destination ->
                    java.io.File(destination).delete()
                    DownloadPartFile.forDestination(java.io.File(destination)).delete()
                }
                repository.delete(downloadId)
            }
            settings.update { before }
            server.close()
        }
    }

    private class TinyHttpServer(
        private val bodySize: Int = 1_024,
        private val delayPerChunkMs: Long = 0L,
    ) : AutoCloseable {
        private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val port: Int = socket.localPort
        private val worker = Thread {
            while (!socket.isClosed) {
                try {
                    socket.accept().use { client ->
                        client.soTimeout = 4_000
                        val reader = BufferedReader(InputStreamReader(client.getInputStream()))
                        val request = reader.readLine() ?: return@use
                        while (reader.readLine()?.isNotEmpty() == true) Unit
                        val headers = (
                            "HTTP/1.1 200 OK\r\n" +
                                "Content-Type: application/octet-stream\r\n" +
                                "Content-Disposition: attachment; filename=scheduled.bin\r\n" +
                                "Content-Length: $bodySize\r\n" +
                                "Connection: close\r\n\r\n"
                            ).toByteArray()
                        client.getOutputStream().write(headers)
                        if (!request.startsWith("HEAD")) {
                            var sent = 0
                            while (sent < bodySize) {
                                val chunk = ByteArray(minOf(1_024, bodySize - sent)) { 42 }
                                client.getOutputStream().write(chunk)
                                client.getOutputStream().flush()
                                sent += chunk.size
                                if (delayPerChunkMs > 0) Thread.sleep(delayPerChunkMs)
                            }
                        }
                    }
                } catch (_: SocketTimeoutException) {
                } catch (_: Exception) {
                    if (socket.isClosed) break
                }
            }
        }.apply { isDaemon = true; start() }

        override fun close() {
            socket.close()
            worker.join(2_000L)
        }
    }
}
