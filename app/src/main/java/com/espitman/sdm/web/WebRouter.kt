package com.espitman.sdm.web

import org.json.JSONArray
import org.json.JSONObject

/** Stable API boundary; transport and browser credentials are independent from Android UI. */
class WebRouter(
    private val access: WebAccess,
    private val deviceName: () -> String,
    private val asset: (String) -> Pair<String, ByteArray>?,
    private val commands: (LanHttpServer.Request, WebAccess.Browser) -> LanHttpServer.Response,
) {
    fun handle(request: LanHttpServer.Request): LanHttpServer.Response = try {
        route(request)
    } catch (_: AccessDenied) { json(401, JSONObject().put("error", "Access required")) }
    catch (_: PairingRateLimited) { json(429, JSONObject().put("error", "Wait one minute before trying again")) }
    catch (_: org.json.JSONException) { json(400, JSONObject().put("error", "Invalid request data")) }
    catch (e: IllegalArgumentException) { json(400, JSONObject().put("error", e.message ?: "Invalid request")) }
    catch (_: IllegalStateException) { json(409, JSONObject().put("error", "Operation is unavailable in the current state")) }

    /** Authenticate and apply route limits before transport allocates or reads the body. */
    fun preflight(request: LanHttpServer.Request): LanHttpServer.Response? {
        val path = request.path
        val public = request.method == "GET" && path == "/api/info" ||
            request.method == "POST" && path in setOf("/api/pair", "/api/request", "/api/approval")
        if (path.startsWith("/api/") && !public) {
            try { access.authorize(credential(request)) }
            catch (_: AccessDenied) { return json(401, JSONObject().put("error", "Access required")) }
        }
        if (!path.startsWith("/api/") && request.method !in setOf("GET", "HEAD"))
            return json(405, JSONObject().put("error", "Method not supported"))
        val limit = when {
            request.method in setOf("GET", "HEAD") -> 0
            public -> 4096
            path == "/api/torrent/upload" -> LanHttpServer.MAX_BODY_BYTES
            path == "/api/backup/review" -> 5 * 1024 * 1024
            else -> 256 * 1024
        }
        if ((request.headers["content-length"]?.toLongOrNull() ?: 0) > limit)
            return json(413, JSONObject().put("error", "Request body too large"))
        return null
    }

    private fun route(request: LanHttpServer.Request): LanHttpServer.Response {
        val path = request.path
        if (!path.startsWith("/api/")) {
            if (request.method !in setOf("GET", "HEAD")) return json(405, JSONObject().put("error", "Method not supported"))
            val allowed = when (path) {
                "/", "/index.html" -> "web/index.html"
                "/panel.css" -> "web/panel.css"
                "/panel.js" -> "web/panel.js"
                "/icons.svg" -> "web/icons.svg"
                "/font-regular.ttf" -> "web/font-regular.ttf"
                "/font-semibold.ttf" -> "web/font-semibold.ttf"
                else -> return json(404, JSONObject().put("error", "Not found"))
            }
            val resource = asset(allowed) ?: return json(404, JSONObject().put("error", "Not found"))
            return LanHttpServer.Response(contentType = resource.first, body = resource.second)
        }
        if (request.method == "GET" && path == "/api/info") {
            return json(200, JSONObject().put("version", 1).put("device", deviceName()).put("authenticated", runCatching {
                access.authorize(credential(request))
            }.isSuccess))
        }
        if (request.method == "POST" && path in setOf("/api/pair", "/api/request", "/api/approval")) {
            val body = body(request)
            return when (path) {
                "/api/pair" -> grant(access.pair(body.getString("code"), body.getString("browser"), request.peer))
                "/api/request" -> {
                    val (pending, ticket) = access.request(body.getString("browser"), request.peer)
                    json(202, JSONObject().put("id", pending.id).put("ticket", ticket).put("expiresAt", pending.expiresAt))
                }
                else -> {
                    val session = access.collect(body.getString("id"), body.getString("ticket"))
                    if (session == null) json(202, JSONObject().put("pending", true)) else grant(session)
                }
            }
        }
        val browser = access.authorize(credential(request))
        if (request.method == "GET" && path == "/api/browsers") {
            return json(200, JSONObject().put("current", browser.id).put("browsers", JSONArray(access.browsers.value.map {
                JSONObject().put("id", it.id).put("browser", it.browser).put("address", it.address).put("approvedAt", it.approvedAt)
            })))
        }
        if (request.method == "POST" && path == "/api/revoke") {
            val id = body(request).getString("id")
            if (id == "all") access.reset() else access.revoke(id)
            return json(200, JSONObject().put("ok", true))
        }
        return commands(request, browser)
    }
    private fun grant(session: String): LanHttpServer.Response = json(200, JSONObject().put("session", session)).copy(
        // HttpOnly cookie permits native browser file streaming without putting credentials in URLs.
        headers = mapOf("Set-Cookie" to "sdm_session=$session; Path=/api/; HttpOnly; SameSite=Strict; Max-Age=315360000"),
    )
    private fun credential(request: LanHttpServer.Request): String? {
        request.headers["authorization"]?.let { value ->
            if (!value.startsWith("Bearer ")) throw AccessDenied()
            return value.removePrefix("Bearer ")
        }
        val matches = request.headers["cookie"].orEmpty().split(';').map { it.trim() }
            .filter { it.startsWith("sdm_session=") }
        if (matches.size > 1) throw AccessDenied()
        return matches.singleOrNull()?.substringAfter('=')
    }
    companion object {
        fun body(request: LanHttpServer.Request): JSONObject {
            require(request.headers["content-type"]?.substringBefore(';')?.trim() == "application/json") { "JSON content type required" }
            require(request.body.size <= 256 * 1024) { "JSON body too large" }
            return JSONObject(request.body.toString(Charsets.UTF_8))
        }
        fun json(status: Int = 200, value: JSONObject): LanHttpServer.Response =
            LanHttpServer.Response(status, body = value.toString().toByteArray(Charsets.UTF_8))
    }
}
