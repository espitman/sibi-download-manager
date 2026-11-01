package com.espitman.sdm.web

import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class LanHttpServerTest {
    @Test(timeout = 6_000) fun clientHalfCloseStillReceivesCompleteResponse() {
        LanHttpServer(InetAddress.getByName("127.0.0.1"), 0) { LanHttpServer.Response(body = "complete-body".toByteArray()) }.use { server ->
            server.start()
            Socket("127.0.0.1", server.port).use { client ->
                client.soTimeout = 2_000
                client.getOutputStream().write("GET / HTTP/1.1\r\nHost: 127.0.0.1:${server.port}\r\n\r\n".toByteArray())
                client.shutdownOutput()
                assertTrue(client.getInputStream().bufferedReader().readText().contains("complete-body"))
            }
        }
    }
    @Test(timeout = 15_000) fun connectionsWithoutHeadersAreBoundedAndRecoverAfterClose() {
        val calls = AtomicInteger()
        LanHttpServer(InetAddress.getByName("127.0.0.1"), 0) { request ->
            assertEquals("127.0.0.1", request.peer)
            calls.incrementAndGet()
            LanHttpServer.Response(body = "recovered".toByteArray())
        }.use { server ->
            server.start()
            val held = (1..32).map { Socket("127.0.0.1", server.port).apply { soTimeout = 2_000 } }
            try {
                Socket("127.0.0.1", server.port).use { rejected ->
                    rejected.soTimeout = 2_000
                    assertEquals(-1, rejected.getInputStream().read())
                }
                assertEquals(0, calls.get())
            } finally { held.forEach { it.close() } }
            val deadline = System.nanoTime() + 3_000_000_000L
            var response = ""
            while (System.nanoTime() < deadline && !response.contains("recovered")) {
                response = runCatching { exchange(server, "GET / HTTP/1.1\r\nHost: 127.0.0.1:${server.port}\r\n\r\n") }.getOrDefault("")
                if (!response.contains("recovered")) Thread.sleep(20)
            }
            assertTrue(response.contains("recovered"))
            assertEquals(1, calls.get())
        }
    }
    @Test(timeout = 15_000) fun concurrentRequestsAreRejectedBeforeBodyAllocationAndCapacityRecovers() {
        val entered = java.util.concurrent.CountDownLatch(LanHttpServer.MAX_IN_FLIGHT_REQUESTS)
        val release = java.util.concurrent.CountDownLatch(1)
        val calls = AtomicInteger()
        val clients = java.util.concurrent.Executors.newFixedThreadPool(LanHttpServer.MAX_IN_FLIGHT_REQUESTS)
        LanHttpServer(InetAddress.getByName("127.0.0.1"), 0) {
            calls.incrementAndGet()
            entered.countDown()
            check(release.await(8, java.util.concurrent.TimeUnit.SECONDS))
            LanHttpServer.Response(body = "ok".toByteArray())
        }.use { server ->
            server.start()
            try {
                val pending = (1..LanHttpServer.MAX_IN_FLIGHT_REQUESTS).map {
                    clients.submit<String> {
                        exchange(server, "GET / HTTP/1.1\r\nHost: 127.0.0.1:${server.port}\r\n\r\n")
                    }
                }
                assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS))
                // Send no body: rejection must precede receive/allocation, not wait for 8 MiB.
                val rejected = exchange(server, "POST /api/upload HTTP/1.1\r\nHost: 127.0.0.1:${server.port}\r\nContent-Length: ${LanHttpServer.MAX_BODY_BYTES}\r\n\r\n")
                assertTrue(rejected.startsWith("HTTP/1.1 503"))
                assertTrue(rejected.contains("Retry-After: 1", ignoreCase = true))
                assertEquals(LanHttpServer.MAX_IN_FLIGHT_REQUESTS, calls.get())
                release.countDown()
                pending.forEach { assertTrue(it.get(3, java.util.concurrent.TimeUnit.SECONDS).endsWith("ok")) }
                assertTrue(exchange(server, "GET / HTTP/1.1\r\nHost: 127.0.0.1:${server.port}\r\n\r\n").endsWith("ok"))
            } finally { release.countDown(); clients.shutdownNow() }
        }
    }
    @Test(timeout = 6_000) fun occupiedPortFailsStartWithoutCrashingOrHanging() {
        java.net.ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { blocker ->
            LanHttpServer(InetAddress.getByName("127.0.0.1"), blocker.localPort) { LanHttpServer.Response() }.use { server ->
                try { server.start(); fail("Occupied port must fail startup") } catch (failure: Exception) {
                    assertFalse(failure is org.junit.internal.AssumptionViolatedException)
                    kotlinx.coroutines.runBlocking {
                        kotlinx.coroutines.withTimeout(2_000) {
                            while (server.failure.value == null) kotlinx.coroutines.delay(10)
                        }
                    }
                }
            }
            assertTrue(blocker.isBound)
            assertFalse(blocker.isClosed)
        }
    }
    @Test fun normalTransportCloseDoesNotReportRuntimeFailure() {
        val server=LanHttpServer(InetAddress.getByName("127.0.0.1"),0) { LanHttpServer.Response() }
        server.start()
        assertNull(server.failure.value)
        server.close()
        assertNull(server.failure.value)
    }
    @Test fun responseSentHookRunsAfterSuccessfulResponseAndDoesNotRunForRejectedRequests() {
        val sent = java.util.concurrent.CountDownLatch(1)
        val calls = AtomicInteger()
        LanHttpServer(InetAddress.getByName("127.0.0.1"), 0) {
            LanHttpServer.Response(body = "acknowledged".toByteArray(), afterSent = { calls.incrementAndGet(); sent.countDown() })
        }.use { server ->
            server.start()
            assertTrue(exchange(server, "GET / HTTP/1.1\r\nHost: hostile.example\r\n\r\n").startsWith("HTTP/1.1 403"))
            assertEquals(0, calls.get())
            val response = exchange(server, "GET / HTTP/1.1\r\nHost: 127.0.0.1:${server.port}\r\n\r\n")
            assertTrue(response.endsWith("acknowledged"))
            assertTrue(sent.await(2, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(1, calls.get())
        }
    }
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
    @Test fun browserStylePostReceivesResponseWithoutRequestConnectionClose() {
        LanHttpServer(InetAddress.getByName("127.0.0.1"), 0) { request ->
            LanHttpServer.Response(contentType = "text/plain", body = request.body)
        }.use { server ->
            server.start()
            val body = "{\"browser\":\"Chrome\"}"
            val response = exchange(server, "POST /api/request HTTP/1.1\r\nHost: 127.0.0.1:${server.port}\r\nContent-Type: application/json\r\nContent-Length: ${body.length}\r\n\r\n$body", close = false)
            assertTrue(response.startsWith("HTTP/1.1 200"))
            assertTrue(response.endsWith(body))
        }
    }
    @Test fun validRequestAndHeadStreamHaveCorrectHeadersAndCloseResources() {
        var closed = false
        LanHttpServer(InetAddress.getByName("127.0.0.1"), 0) {
            LanHttpServer.Response(contentType = "text/plain", stream = object : java.io.ByteArrayInputStream("hello".toByteArray()) {
                override fun close() { closed = true; super.close() }
            }, length = 5)
        }.use { server ->
            server.start()
            val response = exchange(server, "HEAD / HTTP/1.1\r\nHost: 127.0.0.1:${server.port}\r\n\r\n")
            assertTrue(response.startsWith("HTTP/1.1 200"))
            assertTrue(response.contains("Content-Length: 5"))
            assertTrue(response.contains("frame-ancestors 'none'"))
            assertFalse(response.endsWith("hello"))
            assertTrue(closed)
        }
    }
    @Test fun hostileOriginHostAndAmbiguousLengthsNeverReachHandler() {
        val called = AtomicInteger()
        LanHttpServer(InetAddress.getByName("127.0.0.1"), 0) { called.incrementAndGet(); LanHttpServer.Response() }.use { server ->
            server.start()
            val host = "Host: 127.0.0.1:${server.port}\r\n"
            val requests = listOf(
                "GET / HTTP/1.1\r\nHost: attacker.test\r\n\r\n" to 403,
                "GET / HTTP/1.1\r\n${host}Origin: https://attacker.test\r\n\r\n" to 403,
                "POST / HTTP/1.1\r\n${host}Content-Length: 0\r\nContent-Length: 1\r\n\r\n" to 400,
                "POST / HTTP/1.1\r\n${host}Transfer-Encoding: chunked\r\n\r\n0\r\n\r\n" to 400,
                "POST / HTTP/1.1\r\n${host}Content-Length: 999999999\r\n\r\n" to 413,
                "GET //attacker.test HTTP/1.1\r\n${host}\r\n" to 400,
                "GET / HTTP/1.1\r\n${host}X-Test: ${"x".repeat(17000)}\r\n\r\n" to 431,
            )
            requests.forEach { (raw, code) ->
                val response = try { exchange(server, raw) } catch (error: java.net.SocketTimeoutException) { throw AssertionError("Timed out for ${raw.take(120)}", error) }
                assertTrue("Expected $code for ${raw.take(120)}; received ${response.take(200)}", response.lineSequence().first().matches(Regex("HTTP/1\\.[01] ${if (code == 431) "(?:400|431)" else code}(?: .*)?")))
            }
            assertEquals(0, called.get())
        }
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

    @Test fun routerDoesNotExposeCommandsOrAssetPathsWithoutAccess() {
        val access = WebAccess()
        val called = AtomicInteger()
        val router = WebRouter(access, { "Test phone" }, { "text/plain" to "asset".toByteArray() }) { _, _ ->
            called.incrementAndGet(); WebRouter.json(value = org.json.JSONObject().put("ok", true))
        }
        LanHttpServer(InetAddress.getByName("127.0.0.1"), 0, router::handle).use { server ->
            server.start()
            val host = "Host: 127.0.0.1:${server.port}\r\n"
            assertTrue(exchange(server, "GET /api/state HTTP/1.1\r\n${host}\r\n").startsWith("HTTP/1.1 401"))
            assertTrue(exchange(server, "GET /../secret HTTP/1.1\r\n${host}\r\n").startsWith("HTTP/1.1 404"))
            val token = access.pair(access.regenerateCode().value, "Chrome", "host")
            assertTrue(exchange(server, "GET /api/state HTTP/1.1\r\n${host}Authorization: Bearer $token\r\n\r\n").startsWith("HTTP/1.1 200"))
            access.reset()
            assertTrue(exchange(server, "GET /api/state HTTP/1.1\r\n${host}Authorization: Bearer $token\r\n\r\n").startsWith("HTTP/1.1 401"))
            assertEquals(1, called.get())
        }
    }
}
