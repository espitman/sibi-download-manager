package com.espitman.sdm.web

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WebHeaderGuardDeviceTest {
    private fun exchange(server: LanHttpServer, text: String, close: Boolean = true): String = Socket("127.0.0.1", server.port).use { socket ->
        socket.soTimeout = 2_000
        socket.getOutputStream().write((if (close) text.replaceFirst("\r\n", "\r\nConnection: close\r\n") else text).toByteArray())
        val input = socket.getInputStream()
        val headers = StringBuilder()
        while (!headers.endsWith("\r\n\r\n")) {
            val next = input.read()
            if (next < 0) break
            headers.append(next.toChar())
            check(headers.length <= 32_768)
        }
        val length = headers.lineSequence().firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
            ?.substringAfter(':')?.trim()?.toInt() ?: 0
        val body = if (text.startsWith("HEAD ")) ByteArray(0) else ByteArray(length)
        var offset = 0
        while (offset < body.size) {
            val count = input.read(body, offset, body.size - offset)
            if (count < 0) break
            offset += count
        }
        headers.toString() + String(body, 0, offset)
    }
    @Test fun authenticationAndRouteLimitsRejectHeadersWithoutWaitingForBody() {
        val access = WebAccess()
        val called = AtomicInteger()
        val router = WebRouter(access, { "Fixture" }, { null }) { _, _ ->
            called.incrementAndGet(); LanHttpServer.Response()
        }
        LanHttpServer(InetAddress.getByName("127.0.0.1"), 0, router::handle)
            .guardRequestsWith(router::preflight).use { server ->
                server.start()
                val host = "Host: 127.0.0.1:${server.port}\r\n"
                assertTrue(exchange(server, "POST /api/torrent/upload HTTP/1.1\r\n${host}Content-Length: 8388608\r\n\r\n")
                    .startsWith("HTTP/1.1 401"))
                assertTrue(exchange(server, "POST /api/request HTTP/1.1\r\n${host}Content-Length: 4097\r\n\r\n")
                    .startsWith("HTTP/1.1 413"))
                val token = access.pair(access.regenerateCode().value, "Fixture", "127.0.0.1")
                val auth = "Authorization: Bearer $token\r\n"
                assertTrue(exchange(server, "POST /api/downloads/add HTTP/1.1\r\n$host${auth}Content-Length: 262145\r\n\r\n")
                    .startsWith("HTTP/1.1 413"))
                assertTrue(exchange(server, "POST /api/backup/review HTTP/1.1\r\n$host${auth}Content-Length: 5242881\r\n\r\n")
                    .startsWith("HTTP/1.1 413"))
                assertEquals(0, called.get())
                assertTrue(exchange(server, "GET /api/state HTTP/1.1\r\n$host${auth}\r\n").startsWith("HTTP/1.1 200"))
                assertEquals(1, called.get())
                access.reset()
                assertTrue(exchange(server, "POST /api/torrent/upload HTTP/1.1\r\n$host${auth}Content-Length: 8388608\r\n\r\n")
                    .startsWith("HTTP/1.1 401"))
                assertEquals(1, called.get())
            }
    }

}
