package com.espitman.sdm.network

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import java.util.concurrent.ConcurrentHashMap

data class ScopedRequestContext(
    val originUrl: String,
    val cookie: String? = null,
    val userAgent: String? = null,
    val referer: String? = null,
    val isPrivate: Boolean = false,
    val retainSession: Boolean = true,
    val requiresSignIn: Boolean = false,
) {
    override fun toString(): String = "ScopedRequestContext(scope=$scopeKey, private=$isPrivate, requiresSignIn=$requiresSignIn)"

    private val origin: HttpUrl? = originUrl.toHttpUrlOrNull()

    fun headersFor(targetUrl: String): Map<String, String> {
        if (requiresSignIn) return emptyMap()
        val target = targetUrl.toHttpUrlOrNull() ?: return emptyMap()
        return buildMap {
            userAgent?.trim()?.takeIf(String::isNotEmpty)?.let { put("User-Agent", it) }
            if (origin != null && origin.sameOrigin(target)) {
                cookie?.trim()?.takeIf(String::isNotEmpty)?.let { put("Cookie", it) }
                referer?.trim()?.takeIf(String::isNotEmpty)?.let { put("Referer", it) }
            }
        }
    }

    internal val scopeKey: String
        get() = origin?.let { "${it.scheme}://${it.host}:${it.port}" } ?: "invalid"
}

private fun HttpUrl.sameOrigin(other: HttpUrl): Boolean =
    scheme == other.scheme && host == other.host && port == other.port

object BrowserRequestContextRegistry {
    private val byDownloadId = ConcurrentHashMap<String, ScopedRequestContext>()

    @Volatile private var store: BrowserSessionStore? = null

    fun initialize(context: android.content.Context) {
        if (store == null) synchronized(this) {
            if (store == null) store = BrowserSessionStore(context.applicationContext)
        }
    }

    @Synchronized fun put(downloadId: String, context: ScopedRequestContext?) {
        if (context == null) { remove(downloadId); return }
        store?.save(downloadId, context)
        byDownloadId[downloadId] = context
    }

    @Synchronized fun get(downloadId: String): ScopedRequestContext? = byDownloadId[downloadId] ?: store?.read(downloadId)?.also { byDownloadId[downloadId] = it }
    @Synchronized fun remove(downloadId: String) { store?.remove(downloadId); byDownloadId.remove(downloadId) }
}

class ScopedRequestContextInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val context = request.tag(ScopedRequestContext::class.java) ?: return chain.proceed(request)
        val builder = request.newBuilder()
            .removeHeader("Cookie")
            .removeHeader("Authorization")
            .removeHeader("Proxy-Authorization")
            .removeHeader("Referer")
        context.headersFor(request.url.toString()).forEach { (name, value) -> builder.header(name, value) }
        return chain.proceed(builder.build())
    }
}
