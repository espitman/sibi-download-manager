package com.espitman.sdm.download

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.espitman.sdm.data.*
import com.espitman.sdm.data.settings.SettingsRepository
import com.espitman.sdm.data.settings.automaticRetry
import com.espitman.sdm.domain.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

@RunWith(AndroidJUnit4::class)
class AutomaticRetryDeviceTest {
    private val context=ApplicationProvider.getApplicationContext<Context>()
    private fun row(id:String="item",error:String="HTTP 503",count:Int=0)=Download(id=id,url="https://example.com/f",fileName="$id.bin",
        createdAtEpochMillis=1000,updatedAtEpochMillis=2000,failedAtEpochMillis=2000,state=DownloadState.FAILED,error=error,
        downloadedBytes=23,totalBytes=512,automaticRetryCount=count)
    private suspend fun withRepo(block:suspend (SqliteDownloadRepository,String)->Unit) {
        val name="retry-qa-${UUID.randomUUID()}.db";val repo=SqliteDownloadRepository(context,databaseName=name)
        try {repo.awaitInitialized();block(repo,name)} finally {repo.close();context.deleteDatabase(name)}
    }
    @Test fun exactBudgetExhaustionAndPermanentErrors()=runBlocking {withRepo {repo,_ ->
        var now=3000L;var scheduled=0
        repo.insert(row());repo.insert(row("permanent","HTTP 404"));repo.insert(row("expired","HTTP 403"))
        val coordinator=AutomaticRetryCoordinator(repo,{AutomaticRetrySettings(2,1)},{scheduled++},clock=object:Clock {override fun currentTimeMillis()=now})
        repeat(2) {
            coordinator.apply();assertEquals(DownloadState.QUEUED,repo.get("item")!!.state)
            assertEquals(it+1,repo.get("item")!!.automaticRetryCount)
            repo.transition("item",DownloadState.CONNECTING,now)
            repo.transition("item",DownloadState.FAILED,now,"HTTP 503")
            now+=1000
        }
        assertNull(coordinator.apply());assertEquals(2,scheduled)
        assertEquals(DownloadState.FAILED,repo.get("item")!!.state)
        assertEquals(0,repo.get("permanent")!!.automaticRetryCount);assertEquals(0,repo.get("expired")!!.automaticRetryCount)
    }}
    @Test fun offlineMigrationSettingsChangesPauseAndCancel()=runBlocking {withRepo {repo,_ ->
        var now=3000L;var allowed=false;var blocked=false;var policy=AutomaticRetrySettings(3,1);var scheduled=0
        repo.insert(row())
        val coordinator=AutomaticRetryCoordinator(repo,{policy},{scheduled++},{allowed},{blocked},clock=object:Clock {override fun currentTimeMillis()=now})
        assertEquals(33000L,coordinator.apply());assertEquals(0,repo.get("item")!!.automaticRetryCount)
        allowed=true;blocked=true;coordinator.apply();assertEquals(DownloadState.FAILED,repo.get("item")!!.state)
        blocked=false;policy=AutomaticRetrySettings(3,30);assertEquals(32000L,coordinator.apply())
        policy=AutomaticRetrySettings(0,1);assertNull(coordinator.apply())
        policy=AutomaticRetrySettings(3,1)
        repo.pauseAtExactOffset("item",999,now);coordinator.apply()
        assertEquals(DownloadState.PAUSED,repo.get("item")!!.state);assertEquals(23L,repo.get("item")!!.downloadedBytes)
        repo.insert(row("cancel"));repo.cancelAtExactOffset("cancel",23,now);coordinator.apply()
        assertEquals(DownloadState.CANCELLED,repo.get("cancel")!!.state);assertEquals(0,scheduled)
    }}
    @Test fun persistedDeadlineAndSettingsSurviveRecreation()=runBlocking {withRepo {repo,name ->
        repo.insert(row())
        repo.togglePriority("item",9000)
        SqliteDownloadRepository(context,databaseName=name).use {reopened ->
            reopened.awaitInitialized();assertEquals(2000L,reopened.get("item")!!.failedAtEpochMillis)
            assertEquals(7000L,AutomaticRetrySettings(3,5).dueAt(reopened.get("item")!!))
            var started=0
            AutomaticRetryCoordinator(reopened,{AutomaticRetrySettings(3,5)},{started++},clock=object:Clock {override fun currentTimeMillis()=10000L}).apply()
            assertEquals(1,started);assertEquals(1,reopened.get("item")!!.automaticRetryCount)
        }
        val prefs=context.getSharedPreferences("retry-settings-qa",0)
        try {
            val settings=SettingsRepository(prefs);settings.update {it.copy(retryCount=7,retryDelaySeconds=50)}
            prefs.edit().commit()
            assertEquals(AutomaticRetrySettings(7,50),SettingsRepository(prefs).settings.value.automaticRetry())
            settings.reset();assertEquals(AutomaticRetrySettings(),settings.settings.value.automaticRetry())
        } finally {prefs.edit().clear().commit()}
    }}
    @Test fun versionElevenMigrationPreservesFailureAndOffset()=runBlocking {
        val name="retry-migration-${UUID.randomUUID()}.db"
        try {
            SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name),null).use {db ->
                DownloadDatabase.createVersionOne(db);DownloadDatabase.migrateOneToTwo(db);DownloadDatabase.migrateTwoToThree(db)
                DownloadDatabase.migrateThreeToFour(db);DownloadDatabase.migrateFourToFive(db);DownloadDatabase.migrateFiveToSix(db)
                DownloadDatabase.migrateSixToSeven(db);DownloadDatabase.migrateSevenToEight(db);DownloadDatabase.migrateEightToNine(db)
                DownloadDatabase.migrateNineToTen(db);DownloadDatabase.migrateTenToEleven(db)
                db.execSQL("INSERT INTO downloads(id,url,file_name,state,error,created_at,updated_at,downloaded_bytes) VALUES('old','https://example.com/f','f','FAILED','HTTP 503',1000,2000,23)")
                db.version=11
            }
            SqliteDownloadRepository(context,databaseName=name).use {repo ->
                repo.awaitInitialized();val d=repo.get("old")!!;assertEquals(23L,d.downloadedBytes);assertEquals(2000L,d.failedAtEpochMillis)
                assertEquals(4000L,AutomaticRetrySettings().dueAt(d))
            }
        } finally {context.deleteDatabase(name)}
    }
    @Test fun realTemporaryServerErrorsRetryThenCompleteByteIdentically()=runBlocking {withRepo {repo,_ ->
        val server=ServerSocket(0);val attempts=AtomicInteger();val payload=ByteArray(4096) {it.toByte()}
        val serving=thread(isDaemon=true) {
            try {while(!server.isClosed) server.accept().use {socket ->
                val reader=socket.getInputStream().bufferedReader();while(reader.readLine()?.isNotEmpty()==true) {}
                val n=attempts.incrementAndGet();val output=socket.getOutputStream()
                if(n<=2) output.write("HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                else {output.write("HTTP/1.1 200 OK\r\nContent-Length: ${payload.size}\r\nConnection: close\r\n\r\n".toByteArray());output.write(payload)}
                output.flush()
            }} catch(_:java.io.IOException) {}
        }
        val root=File(context.cacheDir,"retry-http-qa-${UUID.randomUUID()}").apply {mkdirs()};val dest=File(root,"payload.bin")
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        lateinit var scheduler:DownloadQueueScheduler
        scheduler=DownloadQueueScheduler(repo,{1},{d ->scope.launch {
            try {DownloadTransferEngine(segmentCount={1}).executeTransfer(d.id,d.url,DownloadPartFile.forDestination(dest),repo)}
            finally {scheduler.releaseSlot(d.id)}
        }})
        val coordinator=AutomaticRetryCoordinator(repo,{AutomaticRetrySettings(3,1)},{scheduler.schedule()})
        try {
            val now=System.currentTimeMillis()
            repo.insert(Download(id="real",url="http://127.0.0.1:${server.localPort}/f",fileName="payload.bin",destinationPath=dest.path,totalBytes=4096,createdAtEpochMillis=now))
            scheduler.schedule()
            withTimeout(15000) {while(repo.get("real")!!.state!=DownloadState.COMPLETED) {coordinator.apply();delay(50)}}
            assertEquals(3,attempts.get());assertEquals(2,repo.get("real")!!.automaticRetryCount)
            assertArrayEquals(payload,dest.readBytes());assertNull(coordinator.apply())
        } finally {scope.cancel();server.close();serving.join(1000);root.deleteRecursively()}
    }}
}
