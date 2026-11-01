package com.espitman.sdm.web

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.espitman.sdm.data.AppRepositories
import com.espitman.sdm.domain.DownloadState
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Verifies and removes exactly the UUID fixture added manually through external Chrome. */
@RunWith(AndroidJUnit4::class)
class WebBrowserHttpFixtureDeviceTest {
    @Test fun verifyCompletedBrowserFixtureAndRemoveOnlyItsOwnedFile() = runBlocking {
        val args=InstrumentationRegistry.getArguments()
        val url=requireNotNull(args.getString("fixtureUrl"))
        val expected=requireNotNull(args.getString("fixtureSha256"))
        val name=Uri.parse(url).lastPathSegment!!
        require(Regex("SDM-web-browser-[0-9a-f-]{36}\\.txt").matches(name))
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val repository=AppRepositories.downloads(context)
        repository.awaitInitialized()
        val row=repository.downloads.value.single { it.url==url && it.fileName==name }
        val originals=repository.downloads.value.filter { it.id!=row.id }
        assertEquals(18,originals.size)
        assertEquals(DownloadState.COMPLETED,row.state)
        val destination=requireNotNull(row.destinationPath)
        val bytes=if(destination.startsWith("content:"))context.contentResolver.openInputStream(Uri.parse(destination))!!.use { it.readBytes() } else File(destination).readBytes()
        assertEquals(278528,bytes.size)
        assertEquals(expected,MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
        WebPanelRuntime.initialize(context)
        val access=WebPanelRuntime.access
        val (pending,ticket)=access.request("SDM browser fixture verification","127.0.0.1")
        check(access.approve(pending.id))
        val secret=checkNotNull(access.collect(pending.id,ticket))
        val browser=access.authorize(secret)
        val bridge=WebAppBridge(context)
        val router=WebRouter(access,{"Fixture"},{null},bridge::handle)
        try {
            val response=router.handle(LanHttpServer.Request("POST","/api/files/delete",mapOf("authorization" to "Bearer $secret","content-type" to "application/json","idempotency-key" to UUID.randomUUID().toString()),JSONObject().put("ids",JSONArray(listOf(row.id))).put("confirmed",true).toString().toByteArray(),"127.0.0.1"))
            assertEquals(200,response.status)
            assertTrue(JSONObject(String(response.body)).getJSONArray("results").getJSONObject(0).getBoolean("deleted"))
            repository.delete(row.id)
            assertEquals(originals,repository.downloads.value)
        } finally { access.revoke(browser.id);bridge.close() }
    }
}
