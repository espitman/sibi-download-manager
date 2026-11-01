package com.espitman.sdm.web

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.espitman.sdm.data.AppRepositories
import com.espitman.sdm.domain.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class WebFileHandoffDeviceTest {
    @Test fun fileRequestsUseStoredIdentityRejectMissingFilesAndPreserveOriginals() = runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        WebPanelRuntime.initialize(context)
        val access=WebPanelRuntime.access
        val (pending,ticket)=access.request("SDM file handoff test","127.0.0.1")
        check(access.approve(pending.id))
        val secret=checkNotNull(access.collect(pending.id,ticket));val browser=access.authorize(secret)
        val bridge=WebAppBridge(context);val router=WebRouter(access,{"Fixture"},{null},bridge::handle)
        val repository=AppRepositories.downloads(context);repository.awaitInitialized()
        val original=repository.downloads.value
        val id=UUID.randomUUID().toString()
        val file=File(context.filesDir,"handoff-$id.txt").apply { writeText("owned fixture") }
        val record=Download(id=id,url="https://example.invalid/$id",fileName=file.name,mimeType="text/plain",destinationPath=file.path,totalBytes=file.length(),downloadedBytes=file.length(),state=DownloadState.COMPLETED,createdAtEpochMillis=1,completedAtEpochMillis=2)
        fun request(action:String,key:String=UUID.randomUUID().toString(),auth:Boolean=true,index:String="main")=router.handle(LanHttpServer.Request("POST","/api/files/phone",buildMap {
            if(auth)put("authorization","Bearer $secret")
            put("content-type","application/json");put("idempotency-key",key)
        },JSONObject().put("id",id).put("index",index).put("action",action).toString().toByteArray(),"127.0.0.1"))
        try {
            repository.insert(record)
            assertEquals(401,request("open",auth=false).status)
            assertEquals(400,request("unknown").status)
            assertEquals(400,request("open",index="0").status)
            val key=UUID.randomUUID().toString();val first=request("open",key)
            assertEquals(200,first.status)
            assertEquals(first.body.toList(),request("open",key).body.toList())
            val current=checkNotNull(WebPanelRuntime.fileHandoff.pending.value)
            assertEquals(file.path,current.identity.destinationPath)
            assertEquals(browser.id,current.owner)
            assertEquals(record,repository.get(id))
            if(InstrumentationRegistry.getArguments().getString("inspectPhoneSheet")=="true") {
                context.startActivity(android.content.Intent(context,com.espitman.sdm.MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                kotlinx.coroutines.delay(15_000)
            }
            WebPanelRuntime.fileHandoff.dismiss(current.id)
            assertEquals(200,request("share").status)
            access.revoke(browser.id);WebPanelRuntime.fileHandoff.expire()
            assertNull(WebPanelRuntime.fileHandoff.pending.value)
            assertEquals(original,repository.downloads.value.filter { it.id!=id })
        } finally {
            WebPanelRuntime.fileHandoff.pending.value?.takeIf { it.owner==browser.id }?.let { WebPanelRuntime.fileHandoff.dismiss(it.id) }
            repository.delete(id);file.delete();access.revoke(browser.id);bridge.close()
        }
    }
}
