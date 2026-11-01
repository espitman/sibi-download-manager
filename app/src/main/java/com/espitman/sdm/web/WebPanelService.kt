package com.espitman.sdm.web

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.espitman.sdm.MainActivity
import java.net.Inet4Address
import java.net.InetAddress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect

/** Optional foreground LAN controller; completely independent from transfer-service lifetime. */
class WebPanelService : Service() {
    private var destroyed = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var server: LanHttpServer? = null
    private var failureObserver: kotlinx.coroutines.Job? = null
    private var boundAddress: String? = null
    private val bridge by lazy { WebAppBridge(this) }
    private val connectivity by lazy { getSystemService(ConnectivityManager::class.java) }
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { refresh() }
        override fun onLost(network: Network) { refresh() }
        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) { refresh() }
    }
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("sdm_web", "Local-network web panel", NotificationManager.IMPORTANCE_LOW))
        WebPanelRuntime.initialize(this)
        val notification = notification()
        if (Build.VERSION.SDK_INT >= 29) startForeground(4080, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        else startForeground(4080, notification)
        scope.launch {
            WebPanelRuntime.access.requests.collect { manager.notify(4080, notification()) }
        }
        scope.launch {
            WebPanelRuntime.folderAccess.pending.collect { manager.notify(4080, notification()) }
        }
        scope.launch {
            WebPanelRuntime.fileHandoff.pending.collect { manager.notify(4080, notification()) }
        }
        scope.launch {
            while (true) {
                WebPanelRuntime.access.expire()
                bridge.expireDrafts()
                // Network callbacks may precede usable link addresses or a successful bind.
                // Reconcile the enabled listener after the network settles as well.
                refresh()
                delay(1_000)
            }
        }
        connectivity.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET).build(), callback)
    }
    private fun notification(): android.app.Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, WebPanelService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val pending = WebPanelRuntime.access.requests.value.firstOrNull()
        val folder = WebPanelRuntime.folderAccess.pending.value
        val file = WebPanelRuntime.fileHandoff.pending.value
        return NotificationCompat.Builder(this, "sdm_web").setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(when { pending != null -> "Web panel access request"; folder != null -> "Choose download folder"; file != null -> "File request from web panel"; else -> "SDM web panel" })
            .setContentText(pending?.let { "${it.browser} · ${it.address}. Open SDM to approve." }
                ?: folder?.let { "Open SDM to select ${it.category?.label?.lowercase() ?: "the default"} download folder." }
                ?: file?.let { "${it.browserName}: open SDM to ${if(it.action==com.espitman.sdm.storage.CompletedFileAction.Open) "open" else "share"} ${it.identity.fileName}." }
                ?: WebPanelRuntime.status.value.error ?: "Local-network access is enabled")
            .setContentIntent(open).addAction(0, "Stop panel", stop).setOngoing(true).setOnlyAlertOnce(true).build()
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop" || !WebPanelRuntime.enabled(this)) { WebPanelRuntime.stop(this); stopSelf(); return START_NOT_STICKY }
        refresh()
        return START_STICKY
    }
    @Synchronized private fun refresh() {
        if (destroyed || !WebPanelRuntime.enabled(this)) return
        val address = lanAddress()
        val port = WebPanelRuntime.port(this)
        if (address?.hostAddress == boundAddress && server?.port == port) return
        if (address == null) {
            failureObserver?.cancel(); failureObserver = null
            server?.close(); server = null; boundAddress = null
            WebPanelRuntime.mutableStatus.value = WebPanelStatus(port = port, error = "Connect your phone to Wi-Fi to open the panel")
            return
        }
        try {
            val router = WebRouter(WebPanelRuntime.access, { Build.MODEL }, { name ->
                runCatching {
                    val type = when {
                        name.endsWith(".html") -> "text/html; charset=utf-8"
                        name.endsWith(".css") -> "text/css; charset=utf-8"
                        name.endsWith(".js") -> "text/javascript; charset=utf-8"
                        name.endsWith(".svg") -> "image/svg+xml"
                        else -> "font/ttf"
                    }
                    type to assets.open(name).use { it.readBytes() }
                }.getOrNull()
            }, bridge::handle)
            val transport = LanHttpServer(address, port, router::handle).guardRequestsWith(router::preflight)
            transport.start()
            val previous = server
            server = transport; boundAddress = address.hostAddress
            WebPanelRuntime.mutableStatus.value = WebPanelStatus(true, "http://${address.hostAddress}:$port", port)
            previous?.close()
            failureObserver?.cancel()
            failureObserver = scope.launch {
                transport.failure.collect { failure ->
                    if (failure != null && server === transport && !destroyed) {
                        transport.close(); server = null; boundAddress = null
                        WebPanelRuntime.access.cancelPairing()
                        WebPanelRuntime.folderAccess.cancelAll(); WebPanelRuntime.fileHandoff.cancelAll()
                        WebPanelRuntime.mutableStatus.value = WebPanelStatus(port = transport.port,
                            error = "The web panel stopped unexpectedly. Restart it from SDM. Downloads continue on your phone.")
                        getSystemService(NotificationManager::class.java).notify(4080, notification())
                    }
                }
            }
        } catch (_: Throwable) {
            val previous = server
            if (previous != null && boundAddress == address.hostAddress) {
                WebPanelRuntime.restorePort(this, previous.port)
                WebPanelRuntime.mutableStatus.value = WebPanelStatus(true, "http://$boundAddress:${previous.port}", previous.port,
                    "Could not open the new port. The panel is still available at its previous address.")
            } else {
                previous?.close(); server = null; boundAddress = null
                WebPanelRuntime.mutableStatus.value = WebPanelStatus(port = port, error = "Could not open this port. Choose another port and try again.")
            }
        }
    }
    private fun lanAddress(): InetAddress? {
        return connectivity.allNetworks.firstNotNullOfOrNull { network ->
            val capabilities = connectivity.getNetworkCapabilities(network)
            if (capabilities == null || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
                (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) null
            else connectivity.getLinkProperties(network)?.linkAddresses?.map { it.address }?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
        }
    }
    @Synchronized override fun onDestroy() {
        destroyed = true
        scope.cancel()
        bridge.close()
        runCatching { connectivity.unregisterNetworkCallback(callback) }
        server?.close(); server = null; boundAddress = null
        WebPanelRuntime.access.cancelPairing()
        WebPanelRuntime.mutableStatus.value = WebPanelStatus(port = WebPanelRuntime.port(this))
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
