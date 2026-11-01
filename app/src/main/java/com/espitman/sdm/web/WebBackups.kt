package com.espitman.sdm.web

import android.content.Context
import com.espitman.sdm.data.AppRepositories
import com.espitman.sdm.data.DownloadBackupCodec
import com.espitman.sdm.data.settings.SettingsRepository
import com.espitman.sdm.domain.LinkArchive
import com.espitman.sdm.storage.CategoryFolderStore
import com.espitman.sdm.storage.PersistableTreeUriGrants
import com.espitman.sdm.torrent.TorrentStore
import kotlinx.coroutines.sync.Mutex
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal class WebBackups(
    private val context: Context,
    private val now: () -> Long = System::currentTimeMillis,
    private val archives: (Context) -> com.espitman.sdm.data.LinkArchiveCoordinator = AppRepositories::archiveCoordinator,
) {
    private data class Export(val owner: String, val bytes: ByteArray, val expires: Long)
    private data class Review(val owner: String, val backup: DownloadBackupCodec.Backup, val expires: Long, var applying: Boolean = false)
    private val exports = mutableMapOf<String, Export>()
    private val reviews = mutableMapOf<String, Review>()
    private val work = Mutex()
    @Synchronized fun expire(approved: Set<String>) {
        val time = now()
        exports.entries.removeAll { it.value.owner !in approved || it.value.expires <= time }
        reviews.entries.removeAll { !it.value.applying && (it.value.owner !in approved || it.value.expires <= time) }
    }
    suspend fun export(owner: String, downloads: Boolean, settings: Boolean): JSONObject {
        require(downloads || settings) { "Choose download list or app settings" }
        require(work.tryLock()) { "A backup operation is already running" }
        try {
            synchronized(this) {
                expire(WebPanelRuntime.access.browsers.value.map { it.id }.toSet())
                require(exports.size < 4 && exports.values.count { it.owner == owner } < 2) { "Too many backup exports. Wait for an export to expire" }
            }
            val records = if (downloads) AppRepositories.downloads(context).schedulingSnapshot() else null
            val torrentRows = records.orEmpty().filter { it.isTorrent }
            val torrents = if (torrentRows.isEmpty()) emptyMap() else {
                val store = TorrentStore(context)
                torrentRows.associate { row -> val selection = store.selection(row.id)
                    row.url to DownloadBackupCodec.TorrentSnapshot(store.metadata(row.id), selection.selected, selection.seed) }
            }
            val bytes = DownloadBackupCodec.encode(records, if (settings) SettingsRepository.get(context).settings.value else null,
                now(), if (settings) CategoryFolderStore.get(context).settings.value else null, torrents).toByteArray()
            if (WebPanelRuntime.access.browsers.value.none { it.id == owner }) throw AccessDenied()
            val token = UUID.randomUUID().toString()
            synchronized(this) { exports[token] = Export(owner, bytes, now() + 600_000) }
            return JSONObject().put("download", "/api/backup/export/$token").put("size", bytes.size)
        } finally { work.unlock() }
    }
    @Synchronized fun download(owner: String, token: String): LanHttpServer.Response {
        val export = exports[token]?.takeIf { it.owner == owner && it.expires > now() }
            ?: throw IllegalArgumentException("Backup export expired. Create a new backup")
        val input = export.bytes.inputStream()
        val guarded = object : java.io.FilterInputStream(input) {
            private fun approved() { if (WebPanelRuntime.access.browsers.value.none { it.id == owner }) { close(); throw AccessDenied() } }
            override fun read(): Int { approved(); return super.read() }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int { approved(); return super.read(buffer, offset, length) }
        }
        return LanHttpServer.Response(contentType = "application/json", headers = mapOf(
            "Content-Disposition" to "attachment; filename=\"sdm-backup-${java.time.LocalDate.now()}.json\"",
            "Cache-Control" to "no-store", "X-Content-Type-Options" to "nosniff"), stream = guarded, length = export.bytes.size.toLong())
    }
    suspend fun review(owner: String, bytes: ByteArray): JSONObject {
        require(bytes.size in 1..LinkArchive.MAX_FILE_BYTES) { "Choose an SDM backup up to 5 MB" }
        require(work.tryLock()) { "A backup operation is already running" }
        try {
            synchronized(this) {
                expire(WebPanelRuntime.access.browsers.value.map { it.id }.toSet())
                require(reviews.size < 4 && reviews.values.count { it.owner == owner } < 2) { "Too many restore reviews. Close a review first" }
            }
            val backup = try {
                val text = Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
                DownloadBackupCodec.decode(text)
            } catch (_: Exception) { throw IllegalArgumentException("Invalid, unsupported or unreadable SDM backup") }
            val seen = AppRepositories.downloads(context).schedulingSnapshot().map { it.url }.toMutableSet()
            val duplicates = backup.downloads.count { !seen.add(it.url) }
            if (WebPanelRuntime.access.browsers.value.none { it.id == owner }) throw AccessDenied()
            val token = UUID.randomUUID().toString()
            synchronized(this) { reviews[token] = Review(owner, backup, now() + 120_000) }
            return JSONObject().put("review", token).put("downloads", backup.downloads.size).put("duplicates", duplicates)
                .put("settings", backup.settings != null).put("torrents", backup.downloads.count { it.isTorrent })
                .put("names", JSONArray(backup.downloads.take(50).map { it.fileName })).put("truncated", backup.downloads.size > 50)
        } finally { work.unlock() }
    }
    @Synchronized fun cancel(owner: String, token: String) {
        val review = reviews[token] ?: return
        require(review.owner == owner && !review.applying) { "Restore review is unavailable" }
        reviews.remove(token)
    }
    suspend fun restore(owner: String, token: String, includeSettings: Boolean, confirmed: Boolean): JSONObject {
        require(confirmed) { "Confirm restore after reviewing the backup" }
        require(work.tryLock()) { "A backup operation is already running" }
        var restored: Int? = null
        try {
            val review = synchronized(this) { requireNotNull(reviews[token]?.takeIf { it.owner == owner && it.expires > now() && !it.applying }) {
                "Restore review expired. Choose the backup again" }.also { it.applying = true } }
            if (WebPanelRuntime.access.browsers.value.none { it.id == owner }) throw AccessDenied()
            fun validateCommit() {
                if (WebPanelRuntime.access.browsers.value.none { it.id == owner }) throw AccessDenied()
                synchronized(this) {
                    require(reviews[token] === review && review.expires > now()) { "Restore review expired. Choose the backup again" }
                }
            }
            restored = archives(context).restore(review.backup.downloads, review.backup.torrents, ::validateCommit)
            if (includeSettings && review.backup.settings != null) {
                validateCommit()
                com.espitman.sdm.data.settings.BackupSettingsRestore.apply(review.backup,
                    SettingsRepository.get(context), CategoryFolderStore.get(context),
                    PersistableTreeUriGrants(context.contentResolver)::hasReadWrite)
                com.espitman.sdm.download.DailyBulkSchedule.arm(context)
                AppRepositories.networkRestriction(context).apply()
                AppRepositories.scheduleCoordinator(context).apply()
            }
            synchronized(this) { reviews.remove(token) }
            return JSONObject().put("restored", restored).put("skipped", review.backup.downloads.size - restored)
        } catch (failure: kotlinx.coroutines.CancellationException) { throw failure }
        catch (failure: Exception) {
            if (restored != null) {
                synchronized(this) { reviews.remove(token) }
                return JSONObject().put("restored", restored).put("warning", "$restored downloads restored; some settings could not be restored")
            }
            throw failure
        } finally { synchronized(this) { reviews[token]?.applying = false }; work.unlock() }
    }
}
