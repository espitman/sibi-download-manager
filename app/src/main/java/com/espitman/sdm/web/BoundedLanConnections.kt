package com.espitman.sdm.web

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** Caps accepted LAN sockets before Ktor parses any headers; Ktor remains the HTTP server. */
internal class BoundedLanConnections(
    address: InetAddress,
    port: Int,
    private val capacity: Int = 32,
    private val idleMillis: Long = 20_000,
    private val failed: (Throwable) -> Unit = {},
) : AutoCloseable {
    private val listener = ServerSocket().also { socket ->
        try { socket.reuseAddress = true; socket.bind(java.net.InetSocketAddress(address, port), capacity) }
        catch (error: Exception) { socket.close(); throw error }
    }
    val port: Int get() = listener.localPort
    private val closed = AtomicBoolean()
    private val slots = Semaphore(capacity)
    private val peers = ConcurrentHashMap<Int, String>()
    private val connections = ConcurrentHashMap.newKeySet<Connection>()
    private val workers = Executors.newFixedThreadPool(capacity * 2) { task -> Thread(task, "sdm-lan-stream").apply { isDaemon = true } }
    internal val activeConnections: Int get() = connections.size
    private val timer = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "sdm-lan-idle").apply { isDaemon = true } }
    private var accepting: Thread? = null
    fun peer(backendClientPort: Int): String? = peers[backendClientPort]
    fun start(backendPort: Int) {
        check(accepting == null && !closed.get())
        timer.scheduleWithFixedDelay({
            val now = System.nanoTime()
            connections.forEach { if (TimeUnit.NANOSECONDS.toMillis(now - it.activity) >= idleMillis) it.close() }
        }, 100, 100, TimeUnit.MILLISECONDS)
        accepting = thread(isDaemon = true, name = "sdm-lan-accept") {
            try {
                while (!closed.get()) {
                    val client = listener.accept()
                    if (!slots.tryAcquire()) { client.close(); continue }
                    val connection = Connection(client)
                    connections.add(connection)
                    try {
                        workers.execute {
                            try {
                                val backend = Socket()
                                connection.backend = backend
                                backend.connect(java.net.InetSocketAddress("127.0.0.1", backendPort), 3_000)
                                peers[backend.localPort] = client.inetAddress.hostAddress ?: "unknown"
                                if (connection.done.get()) { peers.remove(backend.localPort); backend.close(); return@execute }
                                workers.execute { connection.copy(backend, client) }
                                connection.copy(client, backend)
                            } catch (_: Exception) { connection.close() }
                        }
                    } catch (_: Exception) { connection.close() }
                }
            } catch (error: Exception) { if (!closed.get()) failed(error) }
        }
    }
    private inner class Connection(val client: Socket) : AutoCloseable {
        @Volatile var backend: Socket? = null
        @Volatile var activity = System.nanoTime()
        val done = AtomicBoolean()
        fun copy(source: Socket, destination: Socket) {
            var halfClosed = false
            try {
                val input = source.getInputStream(); val output = destination.getOutputStream()
                val buffer = ByteArray(16_384)
                while (!done.get()) {
                    val count = input.read(buffer)
                    if (count < 0) {
                        if (source === client) { destination.shutdownOutput(); halfClosed = true }
                        break
                    }
                    output.write(buffer, 0, count)
                    activity = System.nanoTime()
                }
            } catch (_: Exception) { } finally { if (!halfClosed) close() }
        }
        override fun close() {
            if (!done.compareAndSet(false, true)) return
            backend?.let { socket -> peers.remove(socket.localPort); runCatching { socket.close() } }
            runCatching { client.close() }
            slots.release()
            connections.remove(this)
        }
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { listener.close() }
        connections.toList().forEach { it.close() }
        timer.shutdownNow(); workers.shutdownNow()
        accepting?.join(1_000)
    }
}
