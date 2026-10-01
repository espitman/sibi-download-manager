package com.espitman.sdm.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.espitman.sdm.domain.*
import com.espitman.sdm.data.settings.SdmSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class LinkArchiveDeviceTest {
    @Test fun androidCodecRoundTripAndAtomicRestoreConflictHandling() = runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>();val name="archive-${UUID.randomUUID()}.db"
        var repo=SqliteDownloadRepository(context,databaseName=name)
        try {
            repo.awaitInitialized()
            val original=Download(url="https://example.org/original",fileName="original",createdAtEpochMillis=1,speedLimitBytesPerSecond=40_000,state=DownloadState.PAUSED)
            repo.insert(original)
            val text=DownloadBackupCodec.encode(listOf(original),SdmSettings(theme="light"),1)
            val backup=DownloadBackupCodec.decode(text)
            assertEquals(40_000L,backup.downloads.single().speedLimitBytesPerSecond)
            assertEquals("light",backup.settings?.theme)
            assertFalse(text.contains("destinationPath"))
            val fresh=original.copy(id="fresh",url="http://example.org/fresh")
            assertEquals(listOf("fresh"),repo.insertUniqueBatch(listOf(original.copy(id="duplicate"),fresh,fresh.copy(id="another-duplicate"))))
            assertEquals(original,repo.get(original.id));assertEquals(2,repo.downloads.value.size)
            val a=original.copy(id="collision",url="http://example.org/a")
            val b=original.copy(id="collision",url="http://example.org/b")
            try {repo.insertUniqueBatch(listOf(a,b));fail("Must reject duplicate record IDs")} catch(_:android.database.sqlite.SQLiteConstraintException) {}
            assertNull(repo.get("collision"));assertEquals(2,repo.downloads.value.size)
            repo.close();repo=SqliteDownloadRepository(context,databaseName=name);repo.awaitInitialized()
            assertEquals(2,repo.downloads.value.size);assertEquals(40_000L,repo.get("fresh")?.speedLimitBytesPerSecond)
        } finally {repo.close();context.deleteDatabase(name)}
    }
}
