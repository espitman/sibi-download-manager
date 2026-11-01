package com.espitman.sdm.web

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.espitman.sdm.MainActivity
import com.espitman.sdm.data.AppRepositories
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.HttpURLConnection
import java.net.URL

@RunWith(AndroidJUnit4::class)
class WebPanelPortConflictDeviceTest {
    @Test fun occupiedReplacementPortKeepsOriginalListenerSettingsSessionsAndRecords() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assumeTrue("Panel must already be enabled", WebPanelRuntime.enabled(context))
        val originalPort = androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("restorePort")?.toInt()
            ?: WebPanelRuntime.port(context)
        val repository = AppRepositories.downloads(context)
        repository.awaitInitialized()
        val originalRecords = repository.downloads.value
        val originalIds = originalRecords.map { it.id }.toSet()
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        WebPanelRuntime.start(context, originalPort)
        withTimeout(10_000) { while (!WebPanelRuntime.status.value.running) delay(50) }
        val original = WebPanelRuntime.status.value
        val approved = WebPanelRuntime.access.browsers.value.map { it.id }.toSet()
        val host = java.net.URI(requireNotNull(original.address)).host
        ServerSocket().use { blocker ->
            blocker.bind(InetSocketAddress(InetAddress.getByName(host), 0))
            try {
                WebPanelRuntime.setPort(context, blocker.localPort)
                withTimeout(10_000) {
                    while (WebPanelRuntime.status.value.error == null || WebPanelRuntime.port(context) != originalPort) delay(50)
                }
                delay(2_500) // Observe multiple periodic reconciliation passes after rollback.
                val current = WebPanelRuntime.status.value
                assertTrue(current.running)
                assertEquals(original.address, current.address)
                assertEquals(originalPort, current.port)
                assertTrue(WebPanelRuntime.enabled(context))
                val connection = URL("${original.address}/api/info").openConnection() as HttpURLConnection
                connection.connectTimeout = 2_000; connection.readTimeout = 2_000
                try { assertEquals(200, connection.responseCode); connection.inputStream.use { assertTrue(it.readBytes().isNotEmpty()) } }
                finally { connection.disconnect() }
                assertEquals(approved, WebPanelRuntime.access.browsers.value.map { it.id }.toSet())
                assertEquals(originalIds, repository.downloads.value.map { it.id }.toSet())
                assertEquals(originalRecords, repository.downloads.value)
            } finally {
                if (WebPanelRuntime.port(context) == blocker.localPort) WebPanelRuntime.setPort(context, originalPort)
            }
        }
    }
}
