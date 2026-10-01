package com.espitman.sdm.download

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.espitman.sdm.data.SqliteDownloadRepository
import com.espitman.sdm.data.settings.SettingsRepository
import com.espitman.sdm.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit staging/verification/cleanup for the real visible countdown and Pause control. */
@RunWith(AndroidJUnit4::class)
class AutomaticRetryUiDeviceTest {
    @Test fun waitingCardAndManualPause()=runBlocking<Unit> {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val phase=InstrumentationRegistry.getArguments().getString("phase") ?: return@runBlocking
        val prefs=context.getSharedPreferences("retry-ui-qa",0)
        val settings=SettingsRepository.get(context)
        SqliteDownloadRepository(context).use {repo ->
            repo.awaitInitialized()
            when(phase) {
                "stage" -> {
                    check(!prefs.contains("count"))
                    prefs.edit().putInt("count",settings.settings.value.retryCount).putInt("delay",settings.settings.value.retryDelaySeconds).commit()
                    settings.update {it.copy(retryCount=3,retryDelaySeconds=300)}
                    context.getSharedPreferences("sdm_settings",0).edit().commit()
                    val dir=java.io.File(context.cacheDir,"retry-ui-qa").apply {mkdirs()}
                    val target=java.io.File(dir,"Automatic retry QA.bin")
                    DownloadPartFile.forDestination(target).writeBytes(ByteArray(128))
                    val now=System.currentTimeMillis()
                    repo.insert(Download(id="retry-ui-qa",url="http://127.0.0.1:1/retry-qa",fileName="Automatic retry QA.bin",destinationPath=target.path,createdAtEpochMillis=now,
                        state=DownloadState.FAILED,error="HTTP 503",failedAtEpochMillis=now,downloadedBytes=128,totalBytes=1024,sortOrder=Long.MIN_VALUE))
                }
                "verify" -> {
                    val row=repo.get("retry-ui-qa")!!
                    assertEquals(DownloadState.PAUSED,row.state);assertEquals(128L,row.downloadedBytes)
                    assertEquals(0,row.automaticRetryCount)
                }
                "cleanup" -> {
                    repo.delete("retry-ui-qa")
                    settings.update {it.copy(retryCount=prefs.getInt("count",2),retryDelaySeconds=prefs.getInt("delay",2))}
                    context.getSharedPreferences("sdm_settings",0).edit().commit()
                    java.io.File(context.cacheDir,"retry-ui-qa").deleteRecursively()
                    prefs.edit().clear().commit()
                }
                else -> error("Unknown phase")
            }
        }
    }
}
