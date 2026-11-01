package com.espitman.sdm.web

import java.io.InputStream
import java.net.InetAddress
import java.util.Locale
import io.ktor.server.engine.embeddedServer
import io.ktor.server.application.call
import io.ktor.server.request.*
import io.ktor.server.response.*

/** Ktor CIO embedded transport with LAN host/origin checks and bounded request bodies. */
class LanHttpServer(
    private val address: InetAddress,
    private val requestedPort: Int,
    private val handler: (Request) -> Response,
) : AutoCloseable {
    data class Request(val method: String, val target: String, val headers: Map<String, String>, val body: ByteArray, val peer: String) {
        val path: String get() = target.substringBefore('?')
    }
    data class Response(
        val status: Int = 200,
        val contentType: String = "application/json; charset=utf-8",
        val body: ByteArray = ByteArray(0),
        val headers: Map<String, String> = emptyMap(),
        val stream: InputStream? = null,
        val length: Long? = null,
        val afterSent: (() -> Unit)? = null,
    )
    private val mutableFailure = kotlinx.coroutines.flow.MutableStateFlow<Throwable?>(null)
    internal val failure: kotlinx.coroutines.flow.StateFlow<Throwable?> = mutableFailure
    private var headerGuard: ((Request) -> Response?)? = null
    @Synchronized fun guardRequestsWith(guard: (Request) -> Response?): LanHttpServer {
        check(engine == null) { "Configure request guards before starting the server" }
        headerGuard = guard
        return this
    }
    private var engine: io.ktor.server.engine.ApplicationEngine? = null
    private var engineParent: kotlinx.coroutines.Job? = null
    private var lanConnections: BoundedLanConnections? = null
    private var actualPort = requestedPort
    private val inFlight = kotlinx.coroutines.sync.Semaphore(MAX_IN_FLIGHT_REQUESTS)
    val port: Int get() = actualPort
    @Synchronized fun start() {
        check(engine == null) { "Server is already running" }
        mutableFailure.value = null
        val gateway = try { BoundedLanConnections(address, requestedPort, failed = { mutableFailure.value = it }) }
        catch (error: Throwable) { mutableFailure.value = error; throw error }
        actualPort = gateway.port
        val parent = kotlinx.coroutines.SupervisorJob()
        val server = kotlinx.coroutines.CoroutineScope(parent).embeddedServer(io.ktor.server.cio.CIO,
            host = "127.0.0.1", port = 0,
            parentCoroutineContext = kotlinx.coroutines.CoroutineExceptionHandler { _, error ->
                if (error !is kotlinx.coroutines.CancellationException) mutableFailure.value = error
            }, configure = { connectionIdleTimeoutSeconds = 20 }) {
            io.ktor.server.application.hooks.ResponseSent.install(this) { call ->
                call.attributes.getOrNull(afterSentKey)?.let { action -> runCatching(action) }
            }
            intercept(io.ktor.server.application.ApplicationCallPipeline.Call) {
                call.response.headers.append("Connection", "close")
                if (!inFlight.tryAcquire()) {
                    sendResponse(call, Response(status = 503,
                        body = "{\"error\":\"Server busy; try again shortly\"}".toByteArray(),
                        headers = mapOf("Retry-After" to "1")), call.request.httpMethod.value == "HEAD")
                    return@intercept
                }
                try {
                    val request = readRequest(call)
                    val response = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { handler(request) }
                    sendResponse(call, response, request.method == "HEAD")
                } catch (early: EarlyResponse) {
                    sendResponse(call, early.response, call.request.httpMethod.value == "HEAD")
                } catch (error: HttpError) {
                    call.respondBytes("{\"error\":\"${error.message}\"}".toByteArray(),
                        io.ktor.http.ContentType.Application.Json, io.ktor.http.HttpStatusCode.fromValue(error.status))
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Throwable) {
                    call.respondBytes("{\"error\":\"Request failed\"}".toByteArray(),
                        io.ktor.http.ContentType.Application.Json, io.ktor.http.HttpStatusCode.InternalServerError)
                } finally { inFlight.release() }
            }
        }
        try {
            server.start(wait = false)
            val backendPort = kotlinx.coroutines.runBlocking { kotlinx.coroutines.withTimeout(3_000) { server.resolvedConnectors().first().port } }
            lanConnections = gateway
            gateway.start(backendPort)
            engine = server
            engineParent = parent
        } catch (error: Throwable) { mutableFailure.value = error; gateway.close(); lanConnections = null; server.stop(0, 1000); parent.cancel(); throw error }
    }
    private suspend fun readRequest(call: io.ktor.server.application.ApplicationCall): Request {
        val headers = linkedMapOf<String, String>()
        var headerSize = 0
        call.request.headers.entries().forEach { (name, values) ->
            if (values.size != 1) throw HttpError(400, "Duplicate header")
            headerSize += name.length + values.single().length
            if (headerSize > 16_384) throw HttpError(431, "Headers too large")
            headers[name.lowercase(Locale.US)] = values.single()
        }
        val authority = "${address.hostAddress}:$port"
        if (headers["host"] != authority) throw HttpError(403, "Host not allowed")
        val origin = headers["origin"]
        if (origin != null && origin != "http://$authority") throw HttpError(403, "Origin not allowed")
        val method = call.request.httpMethod.value
        if (method !in setOf("GET", "HEAD", "POST", "PUT", "DELETE")) throw HttpError(405, "Method not supported")
        val target = call.request.uri
        if (target.length > 4096) throw HttpError(414, "Request target too long")
        if (!target.startsWith('/') || target.startsWith("//") || '#' in target || '\\' in target) throw HttpError(400, "Invalid target")
        if (headers.containsKey("transfer-encoding")) throw HttpError(400, "Transfer encoding not supported")
        if (headers.containsKey("expect")) throw HttpError(417, "Expectation not supported")
        val lengthText = headers["content-length"]
        if (lengthText != null && !lengthText.matches(Regex("[0-9]+"))) throw HttpError(400, "Invalid body length")
        val length = lengthText?.toLongOrNull() ?: if (lengthText == null) 0L else throw HttpError(413, "Request body too large")
        if (length > MAX_BODY_BYTES) {
            call.receiveChannel().cancel(IllegalArgumentException("Request body too large"))
            throw HttpError(413, "Request body too large")
        }
        if (method in setOf("GET", "HEAD") && length != 0L) throw HttpError(400, "Unexpected body")
        val requestHeaders = Request(method, target, headers, ByteArray(0), (lanConnections?.peer(call.request.local.remotePort) ?: throw HttpError(403, "Connection not allowed")))
        headerGuard?.invoke(requestHeaders)?.let { response -> throw EarlyResponse(response) }
        val body = ByteArray(length.toInt())
        if (body.isNotEmpty()) kotlinx.coroutines.withTimeout(20_000) { call.receiveChannel().readFully(body, 0, body.size) }
        return Request(method, target, headers, body, (lanConnections?.peer(call.request.local.remotePort) ?: throw HttpError(403, "Connection not allowed")))
    }
    private suspend fun sendResponse(call: io.ktor.server.application.ApplicationCall, response: Response, head: Boolean) {
        response.afterSent?.let { call.attributes.put(afterSentKey, it) }
        val contentType = io.ktor.http.ContentType.parse(response.contentType)
        val headers = linkedMapOf(
            "Cache-Control" to "no-store", "X-Content-Type-Options" to "nosniff", "Referrer-Policy" to "no-referrer",
            "Content-Security-Policy" to "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; font-src 'self'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'",
        )
        headers.putAll(response.headers)
        headers.forEach { (name, value) -> call.response.headers.append(name, value) }
        val status = io.ktor.http.HttpStatusCode.fromValue(response.status)
        if (head) {
            response.stream?.close()
            call.respond(object : io.ktor.http.content.OutgoingContent.NoContent() {
                override val contentType = contentType
                override val status = status
                override val contentLength = response.length ?: response.body.size.toLong()
            })
        } else if (response.stream != null) {
            call.respondOutputStream(contentType, status, response.length) {
                response.stream.use { it.copyTo(this, 64 * 1024) }
            }
        } else call.respondBytes(response.body, contentType, status)
    }
    @Synchronized override fun close() {
        lanConnections?.close(); lanConnections = null; engine?.stop(100, 1000); engine = null; engineParent?.cancel(); engineParent = null }
    private class EarlyResponse(val response: Response) : RuntimeException()
    private class HttpError(val status: Int, override val message: String) : RuntimeException(message)
    companion object {
        private val afterSentKey = io.ktor.util.AttributeKey<() -> Unit>("SDM after response sent")
        const val MAX_BODY_BYTES = 8 * 1024 * 1024
        const val MAX_IN_FLIGHT_REQUESTS = 16
        private fun reason(status: Int): String = when (status) {
            200 -> "OK"; 201 -> "Created"; 202 -> "Accepted"; 204 -> "No Content"; 400 -> "Bad Request"
            401 -> "Unauthorized"; 403 -> "Forbidden"; 404 -> "Not Found"; 405 -> "Method Not Allowed"
            409 -> "Conflict"; 413 -> "Content Too Large"; 429 -> "Too Many Requests"
            431 -> "Request Header Fields Too Large"; else -> "Internal Server Error"
        }
    }
}
