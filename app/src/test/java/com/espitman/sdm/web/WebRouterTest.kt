package com.espitman.sdm.web

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WebRouterTest {
    @Test fun pairingSetsScopedHttpOnlyCookieAndRevocationInvalidatesFileAccess() {
        val access = WebAccess()
        var commands = 0
        val router = WebRouter(access, { "Phone" }, { null }) { _, _ -> commands++; WebRouter.json(value = JSONObject().put("ok", true)) }
        val code = access.regenerateCode().value
        val pair = router.handle(LanHttpServer.Request("POST", "/api/pair", mapOf("content-type" to "application/json"),
            JSONObject().put("code", code).put("browser", "Chrome").toString().toByteArray(), "192.168.1.2"))
        assertEquals(200, pair.status)
        val cookie = pair.headers.getValue("Set-Cookie")
        assertTrue(cookie.contains("Path=/api/; HttpOnly; SameSite=Strict"))
        fun get(headers: Map<String, String>) = router.handle(LanHttpServer.Request("GET", "/api/files/id/main/download", headers, ByteArray(0), "192.168.1.2"))
        assertEquals(200, get(mapOf("cookie" to cookie.substringBefore(';'))).status)
        assertEquals(401, get(mapOf("cookie" to cookie.substringBefore(';'), "authorization" to "Basic invalid")).status)
        assertEquals(401, get(mapOf("cookie" to "${cookie.substringBefore(';')}; ${cookie.substringBefore(';')}" )).status)
        access.reset()
        assertEquals(401, get(mapOf("cookie" to cookie.substringBefore(';'))).status)
        assertEquals(1, commands)
    }
}
