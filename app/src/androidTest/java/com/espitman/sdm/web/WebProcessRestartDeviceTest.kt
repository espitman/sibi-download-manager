package com.espitman.sdm.web

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.espitman.sdm.data.AppRepositories
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/** Run capture and verify in distinct processes, with an actual host force-stop between them. */
@RunWith(AndroidJUnit4::class)
class WebProcessRestartDeviceTest {
    @Test fun preserveExistingIdleRecordsApprovalsAndSettingsAcrossProcessDeath() = runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        WebPanelRuntime.initialize(context)
        val repo=AppRepositories.downloads(context); repo.awaitInitialized()
        val records=repo.downloads.value
        assertTrue("Only run while original downloads are idle",records.none { it.state.name in setOf("DOWNLOADING","QUEUED","SEEDING") })
        fun digest(value:String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
        val snapshot=JSONObject()
            .put("records",digest(records.sortedBy { it.id }.toString()))
            .put("count",records.size)
            .put("browsers",digest(WebPanelRuntime.access.browsers.value.sortedBy { it.id }.toString()))
            .put("settings",digest(context.getSharedPreferences("sdm_settings",Context.MODE_PRIVATE).all.toSortedMap().toString()))
            .put("port",WebPanelRuntime.port(context))
            .put("enabled",WebPanelRuntime.enabled(context))
        val file=File(context.noBackupFilesDir,"web-process-restart-qa.json")
        when(InstrumentationRegistry.getArguments().getString("restartPhase")) {
            "capture" -> { assertTrue(WebPanelRuntime.enabled(context)); assertTrue(WebPanelRuntime.access.browsers.value.isNotEmpty()); file.writeText(snapshot.toString()) }
            "verify" -> try {
                assertTrue("Capture must precede verify",file.isFile)
                val before=JSONObject(file.readText())
                for(key in listOf("records","count","browsers","settings","port","enabled"))assertEquals(key,before.get(key),snapshot.get(key))
            } finally { file.delete() }
            else -> fail("restartPhase must be capture or verify")
        }
    }
}
