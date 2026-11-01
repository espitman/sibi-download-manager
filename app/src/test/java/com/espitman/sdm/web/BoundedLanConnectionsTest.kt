package com.espitman.sdm.web

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class BoundedLanConnectionsTest {
    @Test(timeout = 5_000) fun idleSocketClosesAndItsCapacityAndPeerMappingAreReleased() {
        ServerSocket(0, 2, InetAddress.getByName("127.0.0.1")).use { backend ->
            BoundedLanConnections(InetAddress.getByName("127.0.0.1"), 0, capacity = 1, idleMillis = 300).use { gateway ->
                gateway.start(backend.localPort)
                Socket("127.0.0.1", gateway.port).use { client ->
                    backend.accept().use { accepted ->
                        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
                        while (gateway.peer(accepted.port) == null && System.nanoTime() < deadline) Thread.sleep(5)
                        assertEquals("127.0.0.1", gateway.peer(accepted.port))
                        client.soTimeout = 2_000
                        assertEquals(-1, client.getInputStream().read())
                        assertNull(gateway.peer(accepted.port))
                    }
                }
                val releaseDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
                while (gateway.activeConnections != 0 && System.nanoTime() < releaseDeadline) Thread.sleep(5)
                assertEquals(0, gateway.activeConnections)
                Socket("127.0.0.1", gateway.port).use { client ->
                    backend.accept().use { accepted ->
                        accepted.getOutputStream().write("ok".toByteArray())
                        client.soTimeout = 2_000
                        assertEquals('o'.code, client.getInputStream().read())
                        assertEquals('k'.code, client.getInputStream().read())
                    }
                }
            }
        }
    }
    @Test(timeout = 5_000) fun activeStreamLivesBeyondIdleDeadlineAndCloseStopsItsSockets() {
        ServerSocket(0, 2, InetAddress.getByName("127.0.0.1")).use { backend ->
            val gateway = BoundedLanConnections(InetAddress.getByName("127.0.0.1"), 0, capacity = 1, idleMillis = 300)
            try {
                gateway.start(backend.localPort)
                Socket("127.0.0.1", gateway.port).use { client ->
                    backend.accept().use { accepted ->
                        client.soTimeout = 2_000
                        repeat(10) { index ->
                            accepted.getOutputStream().write(index)
                            assertEquals(index, client.getInputStream().read())
                            Thread.sleep(60)
                        }
                        gateway.close()
                        assertEquals(-1, client.getInputStream().read())
                    }
                }
            } finally { gateway.close() }
        }
    }
}
