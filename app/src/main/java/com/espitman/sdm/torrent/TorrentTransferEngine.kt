package com.espitman.sdm.torrent

import android.content.Context
import android.util.AtomicFile
import com.espitman.sdm.data.AppRepositories
import com.espitman.sdm.data.DownloadRepository
import com.espitman.sdm.data.settings.SettingsRepository
import com.espitman.sdm.domain.DownloadPauseCause
import com.espitman.sdm.domain.DownloadState
import com.espitman.sdm.domain.ErrorReportSanitizer
import com.frostwire.jlibtorrent.*
import com.frostwire.jlibtorrent.alerts.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max

/** Native state is owned by the process; saved metadata/pieces survive process death. */
data class TorrentTelemetry(val peers: Int = 0, val seeds: Int = 0, val downloadRate: Int = 0,
    val uploadRate: Int = 0, val uploaded: Long = 0, val ratio: Double = 0.0, val seeding: Boolean = false,
    val checking: Boolean = false)

object TorrentRuntime {
    private var session: SessionManager? = null
    private var users = 0
    private val state = MutableStateFlow<Map<String, TorrentTelemetry>>(emptyMap())
    val telemetry: StateFlow<Map<String, TorrentTelemetry>> = state
    @Synchronized internal fun acquire(): SessionManager {
        val manager = session ?: SessionManager().also {
            it.start(SessionParams(TorrentSessionSettings.create(200))); session = it
        }
        users++
        return manager
    }
    @Synchronized internal fun release(id: String) {
        state.value = state.value - id
        if (--users == 0) { session?.stop(); session = null }
    }
    @Synchronized internal fun update(id: String, telemetry: TorrentTelemetry) { state.value = state.value + (id to telemetry) }
}

class TorrentTransferEngine(private val context: Context, private val capacity: com.espitman.sdm.storage.StorageCapacityProbe = com.espitman.sdm.storage.AndroidStorageCapacityProbe(context)) {
    suspend fun execute(id: String, repository: DownloadRepository, pauseRequested: () -> Boolean,
        pauseCause: () -> DownloadPauseCause?) = withContext(Dispatchers.IO) {
        val initial = repository.get(id) ?: return@withContext
        val store = TorrentStore(context)
        var manager: SessionManager? = null
        var handle: TorrentHandle? = null
        var listener: AlertListener? = null
        val resumeSignal = AtomicReference(CountDownLatch(0))
        val resumeSaved = java.util.concurrent.atomic.AtomicBoolean(true)
        fun checkpoint(flush: Boolean): Boolean {
            // Never let an older alert acknowledge a newer flush request.
            if (!resumeSignal.get().await(5, TimeUnit.SECONDS)) return false
            val signal = CountDownLatch(1)
            resumeSignal.set(signal)
            resumeSaved.set(false)
            if (flush) handle!!.saveResumeData(TorrentHandle.FLUSH_DISK_CACHE) else handle!!.saveResumeData()
            return signal.await(5, TimeUnit.SECONDS) && resumeSaved.get()
        }
        var verified = initial.downloadedBytes
        fun now() = max(System.currentTimeMillis(), initial.updatedAtEpochMillis)
        try {
            repository.transition(id, DownloadState.CONNECTING, now())
            val contents = TorrentStore.inspect(store.metadata(id))
            val selection = store.selection(id)
            val selectedFiles = contents.files.filter { it.index in selection.selected }
            require(selectedFiles.isNotEmpty() && selectedFiles.size == selection.selected.size) { "Torrent selection is unavailable" }
            val directory = File(initial.destinationPath ?: error("Missing torrent folder"))
            require(directory.isDirectory && directory.canWrite()) { "Torrent storage is unavailable" }
            selectedFiles.forEach { TorrentPaths.resolve(directory, it.path) }
            store.updateWorkingDirectory(id, directory)
            val required = (selectedFiles.sumOf { it.size } - initial.downloadedBytes).coerceAtLeast(0L)
            capacity.queryLocalPath(directory).availableBytes?.let { check(it >= required) { "Not enough storage for selected torrent files" } }
            initial.destinationTreeUri?.let { tree -> capacity.queryTree(tree).availableBytes?.let { check(it >= selectedFiles.sumOf { file -> file.size }) { "Not enough storage in the selected folder" } } }
            val info = TorrentInfo(contents.metadata)
            val priorities = Array(info.numFiles()) { if (it in selection.selected) Priority.NORMAL else Priority.IGNORE }
            val session = TorrentRuntime.acquire().also { manager = it }
            val resumeFile = File(store.directory(id), "resume.data")
            session.download(info, directory, resumeFile.takeIf { it.exists() }, priorities, null, TorrentFlags.PAUSED)
            withTimeout(15_000) {
                while (handle == null) { handle = session.find(info)?.takeIf { it.isValid }; if (handle == null) delay(100) }
            }
            val torrent = handle!!
            val hash = torrent.infoHash().toHex()
            listener = object : AlertListener {
                override fun types() = intArrayOf(AlertType.SAVE_RESUME_DATA.swig(), AlertType.SAVE_RESUME_DATA_FAILED.swig())
                override fun alert(alert: Alert<*>) {
                    if (alert !is TorrentAlert<*> || alert.handle().infoHash().toHex() != hash) return
                    try {
                        if (alert is SaveResumeDataAlert) {
                            val atomic = AtomicFile(resumeFile)
                            val output = atomic.startWrite()
                            try { output.write(AddTorrentParams.writeResumeData(alert.params()).bencode()); atomic.finishWrite(output); resumeSaved.set(true) }
                            catch (failure: Exception) { atomic.failWrite(output) }
                        }
                    } finally { resumeSignal.get().countDown() }
                }
            }
            session.addListener(listener)
            torrent.unsetFlags(TorrentFlags.AUTO_MANAGED)
            torrent.prioritizeFiles(priorities)
            torrent.resume()
            repository.transition(id, DownloadState.DOWNLOADING, now())
            var checkpointAt = System.currentTimeMillis()
            while (true) {
                currentCoroutineContext().ensureActive()
                if (pauseRequested()) throw CancellationException("Torrent paused")
                val record = repository.get(id) ?: return@withContext
                if (record.state !in setOf(DownloadState.CONNECTING, DownloadState.DOWNLOADING)) return@withContext
                val status = torrent.status(true)
                val error = status.errorCode()
                check(error.value() == 0) { "Torrent error: ${error.message()}" }
                val checking = status.state() in setOf(TorrentStatus.State.CHECKING_FILES, TorrentStatus.State.CHECKING_RESUME_DATA)
                val complete = status.isFinished || status.isSeeding
                if (!checking) {
                    val progress = torrent.fileProgress(TorrentHandle.PIECE_GRANULARITY)
                    verified = selectedFiles.sumOf { progress.getOrElse(it.index) { 0L }.coerceIn(0L, it.size) }
                    if (verified < record.downloadedBytes) repository.alignDownloadedBytes(id, verified, now())
                    if (verified != record.downloadedBytes) repository.updateProgress(id, verified, now())
                }
                val seed = store.selection(id).seed
                TorrentRuntime.update(id, TorrentTelemetry(status.numPeers(), status.numSeeds(), status.downloadRate(),
                    status.uploadRate(), status.totalPayloadUpload(), if (verified > 0) status.totalPayloadUpload().toDouble() / verified else 0.0,
                    seeding = complete && seed, checking = checking))
                torrent.setDownloadLimit(record.speedLimitBytesPerSecond?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt() ?: 0)
                session.applySettings(SettingsPack().downloadRateLimit(AppRepositories.torrentRateLimit(context)))
                if (complete && !checking && verified == record.totalBytes && !seed) {
                    torrent.pause()
                    check(checkpoint(true)) { "Unable to flush torrent files; resume to retry" }
                    val published = TorrentPublisher.publish(context, record, contents, store)
                    repository.updateDestination(id, published, record.destinationTreeUri, record.destinationDisplayLabel, record.fileName, now())
                    repository.transition(id, DownloadState.COMPLETED, now())
                    return@withContext
                }
                if (System.currentTimeMillis() - checkpointAt >= 5_000) {
                    checkpoint(false); checkpointAt = System.currentTimeMillis()
                }
                delay(500)
            }
        } catch (timeout: TimeoutCancellationException) {
            repository.transition(id, DownloadState.FAILED, now(), "Torrent engine did not start; try again")
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                val record = repository.get(id)
                if (record?.state in setOf(DownloadState.CONNECTING, DownloadState.DOWNLOADING))
                    repository.pauseAtExactOffset(id, verified, now(), pauseCause())
            }
            throw cancelled
        } catch (failure: Exception) {
            val record = repository.get(id)
            if (record?.state in setOf(DownloadState.CONNECTING, DownloadState.DOWNLOADING))
                repository.transition(id, DownloadState.FAILED, now(), ErrorReportSanitizer.sanitize(failure.message ?: "Torrent download failed"))
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                handle?.let { torrent ->
                    runCatching {
                        torrent.pause()
                        checkpoint(true)
                    }
                    runCatching { manager?.remove(torrent) }
                }
                listener?.let { runCatching { manager?.removeListener(it) } }
                if (manager != null) TorrentRuntime.release(id)
                val final = repository.get(id)
                if (final?.state == DownloadState.COMPLETED && com.espitman.sdm.storage.DownloadDestinationRef.isContentUri(final.destinationPath.orEmpty()))
                    runCatching { store.cleanupWorkingFiles(id) }
            }
        }
    }
}
