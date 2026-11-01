package com.espitman.sdm.web

import com.espitman.sdm.network.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class WebHttpReviewTest {
    @Test fun preservesInputOrderAndContainsFailuresWithBoundedParallelism() = runBlocking {
        val active=AtomicInteger();val maximum=AtomicInteger()
        val retriever=object:DownloadMetadataRetriever {
            override suspend fun retrieve(url:String):DownloadMetadataResult {
                val count=active.incrementAndGet();maximum.updateAndGet { maxOf(it,count) }
                try {
                    delay(if(url.endsWith("0"))30 else 5)
                    if(url.endsWith("3"))throw IllegalStateException("owned failure")
                    return DownloadMetadataResult.Success(DownloadMetadata(url=url,contentLength=128,suggestedFilename=url.substringAfterLast('/')))
                } finally {active.decrementAndGet()}
            }
        }
        val rows=WebHttpReview.inspect((0..9).map { "https://example.invalid/$it" },retriever).getJSONArray("results")
        assertEquals(10,rows.length());assertTrue(maximum.get()<=4)
        for(i in 0..9) {
            assertEquals(i+1,rows.getJSONObject(i).getInt("line"))
            assertEquals(i!=3,rows.getJSONObject(i).getBoolean("ready"))
            if(i!=3)assertEquals(i.toString(),rows.getJSONObject(i).getString("name"))
        }
        assertEquals(0,active.get())
    }
    @Test fun realHttpMetadataReviewUsesHeadAndOneByteFallbackWithoutPayloadDownload() = runBlocking {
        val origin=okhttp3.mockwebserver.MockWebServer()
        val requests=java.util.Collections.synchronizedList(mutableListOf<Pair<String?,String?>>())
        origin.dispatcher=object:okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request:okhttp3.mockwebserver.RecordedRequest):okhttp3.mockwebserver.MockResponse {
                requests.add(request.method to request.getHeader("Range"))
                return when(request.path) {
                    "/ready.bin" -> okhttp3.mockwebserver.MockResponse().setResponseCode(200)
                        .setHeader("Content-Length","1048576").setHeader("Content-Disposition","attachment; filename=\"ready.bin\"")
                    "/fallback.bin" -> if(request.method=="HEAD") okhttp3.mockwebserver.MockResponse().setResponseCode(405)
                        else okhttp3.mockwebserver.MockResponse().setResponseCode(206).setHeader("Content-Range","bytes 0-0/2097152").setBody("x")
                    else -> okhttp3.mockwebserver.MockResponse().setResponseCode(404)
                }
            }
        }
        origin.start()
        try {
            val result=WebHttpReview.inspect(listOf(origin.url("/ready.bin").toString(),
                "invalid-url",origin.url("/fallback.bin").toString(),origin.url("/missing.bin").toString()),HttpDownloadMetadataRetriever()).getJSONArray("results")
            assertEquals(4,result.length())
            assertTrue(result.getJSONObject(0).getBoolean("ready"));assertEquals(1048576L,result.getJSONObject(0).getLong("size"))
            assertFalse(result.getJSONObject(1).getBoolean("ready"))
            assertTrue(result.getJSONObject(2).getBoolean("ready"));assertEquals(2097152L,result.getJSONObject(2).getLong("size"))
            assertFalse(result.getJSONObject(3).getBoolean("ready"));assertTrue(result.getJSONObject(3).getString("error").contains("404"))
            assertEquals(4,requests.size)
            assertEquals(3,requests.count { it.first=="HEAD" })
            assertEquals(listOf("GET" to "bytes=0-0"),requests.filter { it.first=="GET" })
        } finally {origin.shutdown()}
    }

}
