package com.espitman.sdm.data

import com.espitman.sdm.domain.*
import com.espitman.sdm.data.settings.SdmSettings
import com.espitman.sdm.storage.*
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class DownloadBackupCodecTest {
    private val record=Download(url="https://example.org/file?token=abc",fileName="file.mkv",mimeType="video/mp4",destinationPath="/private/never-export",
        downloadedBytes=400,totalBytes=1000,state=DownloadState.PAUSED,createdAtEpochMillis=1,speedLimitBytesPerSecond=500_000,
        schedule=DownloadSchedule(DownloadSchedule.Kind.DAILY,startMinuteOfDay=90,endMinuteOfDay=400,zoneId="Asia/Tehran"),sortOrder=7,priority=1)
    @Test fun roundTripIncludesPortableFieldsAndExcludesFilePathsProgressAndSecrets() {
        val settings=SdmSettings(theme="light",connections=32,retryCount=7,retryDelaySeconds=50)
        val rules=CategoryFolderSettings(true,mapOf(FileCategory.VIDEO to CategoryFolderRule("content://com.android.externalstorage.documents/tree/primary%3AMovies","Movies")))
        val text=DownloadBackupCodec.encode(listOf(record),settings,1,rules)
        assertFalse(text.contains("private/never-export"));assertFalse(text.contains("downloadedBytes"));assertFalse(text.contains("headers"));assertFalse(text.contains("cookies"))
        val backup=DownloadBackupCodec.decode(text)
        val restored=backup.downloads.single();assertEquals(record.url,restored.url);assertEquals(record.schedule,restored.schedule)
        assertEquals(record.speedLimitBytesPerSecond,restored.speedLimitBytesPerSecond);assertEquals(0L,restored.downloadedBytes);assertNull(restored.destinationPath)
        assertEquals(settings,backup.settings);assertEquals(rules,backup.categoryFolders)
    }
    @Test fun oldBackupsDefaultRetrySettingsAndInvalidPoliciesAreRejected() {
        val root=JSONObject(DownloadBackupCodec.encode(null,SdmSettings(),1))
        root.getJSONObject("settings").remove("retryCount");root.getJSONObject("settings").remove("retryDelaySeconds")
        assertEquals(SdmSettings(),DownloadBackupCodec.decode(root.toString()).settings)
        root.getJSONObject("settings").put("retryCount",11)
        assertThrows(Exception::class.java) {DownloadBackupCodec.decode(root.toString())}
    }
    @Test fun settingsOnlyAndListOnlyBackupsStayCompatible() {
        assertEquals(emptyList<Download>(),DownloadBackupCodec.decode(DownloadBackupCodec.encode(null,SdmSettings(),1)).downloads)
        assertNull(DownloadBackupCodec.decode(DownloadBackupCodec.encode(listOf(record),null,1)).settings)
        assertEquals(emptyList<Download>(),DownloadBackupCodec.decode(DownloadBackupCodec.encode(emptyList(),null,1)).downloads)
    }
    @Test fun rejectsMalformedUnsupportedBadFieldsAndOversizedBackups() {
        fun invalid(text:String) {assertThrows(Exception::class.java) {DownloadBackupCodec.decode(text)}}
        invalid("not json");invalid("{}");invalid(DownloadBackupCodec.encode(listOf(record),null,1)+" garbage")
        val text=DownloadBackupCodec.encode(listOf(record),SdmSettings(),1)
        invalid(JSONObject(text).put("version",2).toString())
        invalid(JSONObject(text).put("version",1.5).toString())
        invalid(JSONObject(text).apply {getJSONArray("downloads").getJSONObject(0).put("url","file:///private/file")}.toString())
        invalid(JSONObject(text).apply {getJSONArray("downloads").getJSONObject(0).put("fileName","../private")}.toString())
        invalid(JSONObject(text).apply {getJSONArray("downloads").getJSONObject(0).put("speedLimitBytesPerSecond",-1)}.toString())
        invalid(JSONObject(text).apply {getJSONObject("settings").put("connections",4294967312L)}.toString())
        invalid("[".repeat(20)+"]".repeat(20));invalid("x".repeat(LinkArchive.MAX_FILE_BYTES+1))
    }
}
