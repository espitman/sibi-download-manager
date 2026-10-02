package com.espitman.sdm.torrent

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import com.frostwire.jlibtorrent.*
import kotlinx.coroutines.*
import java.io.File

/** A separate native peer process, included only in debug builds. Never shipped. */
class TorrentTestSeederService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var session: SessionManager? = null
    private var marker: File? = null
    override fun onBind(intent: Intent): IBinder {
        TorrentNativeRuntime.prepare(this)
        val path = File(intent.getStringExtra("fixture") ?: error("Missing fixture")).canonicalFile
        require(path.path.startsWith(cacheDir.canonicalPath + "/torrent-qa-"))
        val port = intent.getIntExtra("port", 0)
        require(port in 1024..65535)
        marker = File(path, "ready")
        scope.launch {
            val manager = SessionManager()
            session = manager
            manager.addListener(object : AlertListener {
                override fun types() = intArrayOf(com.frostwire.jlibtorrent.alerts.AlertType.PEER_ERROR.swig(), com.frostwire.jlibtorrent.alerts.AlertType.PEER_DISCONNECTED.swig(), com.frostwire.jlibtorrent.alerts.AlertType.PEER_CONNECT.swig())
                override fun alert(alert: com.frostwire.jlibtorrent.alerts.Alert<*>) { android.util.Log.i("TorrentQASeed", alert.message()) }
            })
            manager.start(SessionParams(SettingsPack().listenInterfaces("127.0.0.1:$port").uploadRateLimit(128*1024)))
            manager.stopDht()
            val info = TorrentInfo(File(path, "fixture.torrent").readBytes())
            manager.download(info, File(path, "seed"))
            withTimeout(30_000) {
                while (manager.find(info)?.status()?.isSeeding != true) delay(100)
            }
            marker?.writeText("ready")
        }
        return Binder()
    }
    override fun onDestroy() {
        scope.cancel()
        session?.stop()
        marker?.delete()
        super.onDestroy()
    }
}
