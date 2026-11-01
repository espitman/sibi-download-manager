package com.espitman.sdm.web

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.espitman.sdm.data.AppRepositories
import com.espitman.sdm.domain.Download
import com.espitman.sdm.domain.DownloadState
import com.espitman.sdm.storage.AppSpecificDownloadsDirectory
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class WebExternalPhoneHandoffDeviceTest {
    @Test fun externalChromeOpenAndShareDeliverExactOwnedBytesToSeparateApp() = runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        WebPanelRuntime.initialize(context)
        check(WebPanelRuntime.enabled(context))
        WebPanelRuntime.start(context)
        val repo=AppRepositories.downloads(context);repo.awaitInitialized()
        val original=repo.downloads.value
        val nonce=UUID.randomUUID().toString()
        val expected="SDM phone handoff fixture $nonce"
        val file=File(AppSpecificDownloadsDirectory.from(context),"sdm-web-phone-$nonce.sdmqa").apply { writeText(expected) }
        val row=Download(url="https://example.invalid/$nonce",fileName=file.name,mimeType="application/x-sdm-owned-fixture",
            destinationPath=file.path,totalBytes=file.length(),downloadedBytes=file.length(),state=DownloadState.COMPLETED,
            createdAtEpochMillis=System.currentTimeMillis(),completedAtEpochMillis=System.currentTimeMillis())
        val results=Channel<Intent>(2)
        val receiver=object:BroadcastReceiver() {
            override fun onReceive(context:Context,intent:Intent) { results.trySend(intent) }
        }
        val action="com.espitman.sdm.test.HANDOFF_$nonce"
        context.registerReceiver(receiver,IntentFilter(action),Context.RECEIVER_EXPORTED)
        try {
            repo.insert(row)
            context.startActivity(Intent(context,com.espitman.sdm.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            println("CHROME_PHONE_HANDOFF_READY="+row.fileName)
            for(expectedAction in listOf(Intent.ACTION_VIEW,Intent.ACTION_SEND)) {
                val result=withTimeout(180_000) { results.receive() }
                assertEquals(expected,result.getStringExtra("payload"))
                assertEquals(expectedAction,result.getStringExtra("action"))
                assertTrue(result.getBooleanExtra("readGrant",false))
                assertTrue(result.getBooleanExtra("clip",false))
                assertTrue(checkNotNull(result.getStringExtra("uri")).startsWith("content://com.espitman.sdm.files/"))
                assertEquals(row,repo.get(row.id))
                assertEquals(original,repo.downloads.value.filter { it.id!=row.id })
                println("CHROME_PHONE_HANDOFF_VERIFIED=$expectedAction")
            }
            assertEquals(expected,file.readText())
            delay(5_000)
        } finally {
            context.unregisterReceiver(receiver);results.close()
            val handoff=WebPanelRuntime::class.java.methods.single { it.name.startsWith("getFileHandoff\$")&&it.parameterCount==0 }.invoke(WebPanelRuntime) as WebFileHandoff
            handoff.pending.value?.takeIf { it.identity.downloadId==row.id }?.let { handoff.dismiss(it.id) }
            repo.delete(row.id);check(file.delete())
        }
        assertEquals(original,repo.downloads.value)
    }
}
