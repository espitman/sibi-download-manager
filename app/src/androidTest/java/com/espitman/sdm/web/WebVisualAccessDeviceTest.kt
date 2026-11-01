package com.espitman.sdm.web

import android.content.Intent
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.espitman.sdm.data.AppRepositories
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicInteger

/** Installed release assets over real phone Ktor; an empty read-only fixture view and isolated access. */
@RunWith(AndroidJUnit4::class)
class WebVisualAccessDeviceTest {
    @Test fun externalChromeEmptyViewAndRevocationClearAuthenticatedUi() = runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        WebPanelRuntime.initialize(context)
        if(WebPanelRuntime.enabled(context))WebPanelRuntime.start(context)
        val repo=AppRepositories.downloads(context);repo.awaitInitialized()
        val original=repo.downloads.value
        val originalBrowsers=WebPanelRuntime.access.browsers.value
        val originalSettings=context.getSharedPreferences("sdm_settings",0).all
        val access=WebAccess()
        val bridge=WebAppBridge(context)
        val reads=AtomicInteger()
        val denials=AtomicInteger()
        val router=WebRouter(access,{Build.MODEL},{name ->
            val type=when { name.endsWith(".html")->"text/html; charset=utf-8";name.endsWith(".css")->"text/css; charset=utf-8";name.endsWith(".js")->"text/javascript; charset=utf-8";name.endsWith(".svg")->"image/svg+xml";else->"font/ttf" }
            type to context.assets.open(name).use { it.readBytes() }
        },{request,browser ->
            if(request.method=="GET"&&request.path=="/api/state") {
                val snapshot=bridge.handle(request,browser)
                val value=JSONObject(String(snapshot.body)).put("downloads",JSONArray())
                reads.incrementAndGet()
                snapshot.copy(body=value.toString().toByteArray())
            } else LanHttpServer.Response(status=405,body="{\"error\":\"Read-only visual fixture\"}".toByteArray())
        })
        val server=LanHttpServer(InetAddress.getByName("127.0.0.1"),18081,router::handle).guardRequestsWith { request ->
            router.preflight(request).also { if(it?.status==401&&request.path=="/api/state")denials.incrementAndGet() }
        }
        try {
            server.start()
            context.startActivity(Intent(context,com.espitman.sdm.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            println("CHROME_VISUAL_ACCESS_READY")
            val pending=withTimeout(180_000) { while(access.requests.value.isEmpty())delay(100);access.requests.value.single() }
            assertTrue(access.approve(pending.id))
            withTimeout(30_000) { while(reads.get()==0)delay(100) }
            println("CHROME_VISUAL_EMPTY_AUTHENTICATED")
            delay(90_000)
            access.revoke(access.browsers.value.single().id)
            println("CHROME_VISUAL_ACCESS_REVOKED")
            withTimeout(30_000) { while(denials.get()==0)delay(100) }
            delay(40_000)
            assertEquals(original,repo.downloads.value)
            assertEquals(originalBrowsers,WebPanelRuntime.access.browsers.value)
            assertEquals(originalSettings,context.getSharedPreferences("sdm_settings",0).all)
            assertTrue(access.browsers.value.isEmpty())
        } finally { server.close();bridge.close() }
    }
}
