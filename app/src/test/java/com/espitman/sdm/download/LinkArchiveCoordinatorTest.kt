package com.espitman.sdm.download

import com.espitman.sdm.data.*
import com.espitman.sdm.domain.*
import com.espitman.sdm.network.*
import com.espitman.sdm.storage.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class LinkArchiveCoordinatorTest {
    private fun repository(initial:List<Download> = emptyList()):DownloadRepository {
        val delegate=DownloadTransferEngineTest.FakeDownloadRepository(initial)
        return object:DownloadRepository by delegate {
            override suspend fun insertUniqueBatch(downloads:List<Download>):List<String> {
                val seen=delegate.downloads.value.map {it.url}.toMutableSet();val added=downloads.filter {seen.add(it.url)}
                added.forEach {delegate.insert(it)};return added.map {it.id}
            }
        }
    }
    @Test fun partialImportDoesNotRetrySuccessfulLinksAndQueueDoesNotStartTransfers() = runBlocking {
        val directory=Files.createTempDirectory("archive-import").toFile()
        val existing=Download(url="http://example.org/old",fileName="old",createdAtEpochMillis=1)
        val repo=repository(listOf(existing));var started=0
        val scheduler=DownloadQueueScheduler(repo,ConcurrentDownloadLimit {3},QueuedTransferStarter {started++})
        val metadata=object:DownloadMetadataRetriever {
            override suspend fun retrieve(url:String):DownloadMetadataResult=if(url.endsWith("bad")) DownloadMetadataResult.Failure.NetworkError(url,"Offline") else DownloadMetadataResult.Success(DownloadMetadata(url,10,"video/mp4"))
        }
        try {
            val coordinator=LinkArchiveCoordinator(repo,AppPrivateDestinationAllocator({directory}),metadata,scheduler)
            val result=coordinator.importLinks(listOf("http://example.org/old","http://example.org/new","http://example.org/bad"),false) {_,_->}
            assertEquals(1,result.added);assertEquals(1,result.skipped);assertEquals(listOf("http://example.org/bad"),result.failed)
            assertEquals(0,started);assertEquals(DownloadState.PAUSED,repo.downloads.value.last().state)
            val second=coordinator.importLinks(listOf("http://example.org/new"),false) {_,_->}
            assertEquals(0,second.added);assertEquals(1,second.skipped)
        } finally {directory.deleteRecursively()}
    }
    @Test fun restorePreservesExistingRecordsAllocatesFreshDestinationsAndStartsPaused() = runBlocking {
        val directory=Files.createTempDirectory("archive-restore").toFile()
        val old=Download(url="http://example.org/old",fileName="old",destinationPath="/old/path",downloadedBytes=9,createdAtEpochMillis=1)
        val repo=repository(listOf(old));val scheduler=DownloadQueueScheduler(repo,ConcurrentDownloadLimit {3},QueuedTransferStarter {fail("Restore must not start downloads")})
        val metadata=object:DownloadMetadataRetriever {override suspend fun retrieve(url:String):DownloadMetadataResult=error("Restore is offline")}
        try {
            val restored=old.copy(url="http://example.org/new",fileName="fresh.bin",sortOrder=4,speedLimitBytesPerSecond=500_000)
            val coordinator=LinkArchiveCoordinator(repo,AppPrivateDestinationAllocator({directory}),metadata,scheduler)
            assertEquals(1,coordinator.restore(listOf(old,restored,restored)))
            assertEquals(old,repo.get(old.id))
            val new=repo.downloads.value.last();assertEquals(0L,new.downloadedBytes);assertEquals(DownloadState.PAUSED,new.state)
            assertEquals(500_000L,new.speedLimitBytesPerSecond);assertTrue(new.destinationPath!!.startsWith(directory.path))
            assertEquals(1,directory.listFiles()!!.size)
        } finally {directory.deleteRecursively()}
    }
    @Test fun failedRestoreRollsBackReservedPartFiles() = runBlocking {
        val directory=Files.createTempDirectory("archive-rollback").toFile();val repo=repository()
        val scheduler=DownloadQueueScheduler(repo,ConcurrentDownloadLimit {3},QueuedTransferStarter {})
        val metadata=object:DownloadMetadataRetriever {override suspend fun retrieve(url:String):DownloadMetadataResult=error("unused")}
        var count=0
        val allocator=DestinationAllocator {name->if(++count==2) error("Folder unavailable") else AppPrivateDestinationAllocator({directory}).allocate(name)}
        try {
            try {LinkArchiveCoordinator(repo,allocator,metadata,scheduler).restore(listOf(Download(url="http://example.org/1",fileName="1",createdAtEpochMillis=1),Download(url="http://example.org/2",fileName="2",createdAtEpochMillis=1)));fail("Expected failure")} catch(_:IllegalStateException) {}
            assertTrue(repo.downloads.value.isEmpty());assertEquals(0,directory.listFiles()!!.size)
        } finally {directory.deleteRecursively()}
    }
}
