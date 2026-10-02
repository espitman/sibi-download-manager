package com.espitman.sdm.torrent

import android.content.Context
import android.net.Uri
import com.frostwire.jlibtorrent.SessionManager
import com.frostwire.jlibtorrent.SettingsPack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.util.UUID

object TorrentResolver {
    suspend fun link(context: Context, raw: String, requestContext: com.espitman.sdm.network.ScopedRequestContext? = null): TorrentContents {
        if (raw.trim().startsWith("magnet:", true)) return magnet(context, raw)
        val validated = com.espitman.sdm.domain.DownloadUrl.validate(raw)
        require(validated is com.espitman.sdm.domain.DownloadUrlResult.Valid) { "Enter a magnet link or a torrent URL" }
        val client = okhttp3.OkHttpClient.Builder().callTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .addNetworkInterceptor(com.espitman.sdm.network.ScopedRequestContextInterceptor()).build()
        val request = okhttp3.Request.Builder().url(validated.url).tag(com.espitman.sdm.network.ScopedRequestContext::class.java, requestContext).build()
        val call = client.newCall(request)
        val response = kotlinx.coroutines.suspendCancellableCoroutine<okhttp3.Response> { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, failure: java.io.IOException) { if (continuation.isActive) continuation.resumeWith(Result.failure(failure)) }
                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) { continuation.resume(response) { response.close() } }
            })
        }
        try { return withContext(Dispatchers.IO) {
            response.use {
                check(it.isSuccessful) { "Torrent metadata request failed (HTTP ${it.code})" }
                val body = it.body ?: error("Torrent metadata is empty")
                require(body.contentLength() <= TorrentStore.MAX_METADATA_BYTES) { "Torrent metadata exceeds 8 MB" }
                body.byteStream().use { input -> TorrentStore.inspect(readBounded(input)) }
            }
        } } finally { response.close() }
    }
    private suspend fun readBounded(input: java.io.InputStream): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            require(out.size() + count <= TorrentStore.MAX_METADATA_BYTES) { "Torrent metadata exceeds 8 MB" }
            out.write(buffer, 0, count)
        }
        return out.toByteArray()
    }
    suspend fun magnet(context: Context, uri: String): TorrentContents = withContext(Dispatchers.IO) {
        require(TorrentMagnet.isValid(uri)) { "Enter a valid magnet link" }
        TorrentNativeRuntime.prepare(context)
        val directory = File(context.cacheDir, "torrent-resolve-${UUID.randomUUID()}").apply { mkdirs() }
        val session = SessionManager()
        try {
            session.start(com.frostwire.jlibtorrent.SessionParams(TorrentSessionSettings.create(60)))
            // Metadata-only: the engine cannot request payload before file selection.
            session.download(uri.trim(), directory, com.frostwire.jlibtorrent.TorrentFlags.UPLOAD_MODE.or_(com.frostwire.jlibtorrent.TorrentFlags.STOP_WHEN_READY))
            kotlinx.coroutines.withTimeout(90_000) {
                var configured = false
                while (true) {
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    val handle = session.torrentHandles.firstOrNull()
                    if (handle != null && !configured) {
                        handle.unsetFlags(com.frostwire.jlibtorrent.TorrentFlags.AUTO_MANAGED)
                        handle.resume()
                        configured = true
                    }
                    val info = handle?.torrentFile()
                    if (info?.isValid == true) return@withTimeout TorrentStore.inspect(info.bencode())
                    kotlinx.coroutines.delay(100)
                }
                @Suppress("UNREACHABLE_CODE") error("Metadata unavailable")
            }
        } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
            error("Metadata unavailable. Check your connection or try a .torrent file.")
        } finally {
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { session.stop(); directory.deleteRecursively() }
        }
    }
    suspend fun file(context: Context, uri: Uri): TorrentContents = withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            readBounded(input)
        } ?: error("Unable to read torrent file")
        TorrentStore.inspect(bytes)
    }
}
