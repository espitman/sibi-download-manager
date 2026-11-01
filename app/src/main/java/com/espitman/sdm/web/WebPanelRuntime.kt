package com.espitman.sdm.web

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class WebPanelStatus(val running: Boolean = false, val address: String? = null, val port: Int = 8080, val error: String? = null)

object WebPanelRuntime {
    val access = WebAccess()
    internal val folderAccess = WebFolderAccess()
    internal val fileHandoff = WebFileHandoff()
    internal val commands = WebCommandCache()
    internal val mutableStatus = MutableStateFlow(WebPanelStatus())
    val status = mutableStatus.asStateFlow()
    fun initialize(context: Context) { access.initialize(AndroidWebSessionStore(context.applicationContext)) }
    fun port(context: Context): Int = preferences(context).getInt("port", 8080)
    fun enabled(context: Context): Boolean = preferences(context).getBoolean("enabled", false)
    internal fun restorePort(context: Context, port: Int) {
        preferences(context).edit().putInt("port", port).commit().also { check(it) }
    }
    fun setPort(context: Context, port: Int) {
        require(port in 1024..65535) { "Choose a port from 1024 to 65535" }
        if (enabled(context)) start(context, port)
        else {
            preferences(context).edit().putInt("port", port).apply()
            mutableStatus.value = mutableStatus.value.copy(port = port)
        }
    }
    fun start(context: Context, port: Int = port(context)) {
        require(port in 1024..65535) { "Choose a port from 1024 to 65535" }
        val oldPort = port(context)
        val wasEnabled = enabled(context)
        preferences(context).edit().putInt("port", port).putBoolean("enabled", true).apply()
        try { ContextCompat.startForegroundService(context.applicationContext, Intent(context, WebPanelService::class.java)) }
        catch (error: RuntimeException) {
            preferences(context).edit().putInt("port", oldPort).putBoolean("enabled", wasEnabled).apply()
            throw error
        }
    }
    fun stop(context: Context) {
        preferences(context).edit().putBoolean("enabled", false).apply()
        context.stopService(Intent(context, WebPanelService::class.java))
        access.cancelPairing()
        folderAccess.cancelAll(); fileHandoff.cancelAll()
        mutableStatus.value = WebPanelStatus(port = port(context))
    }
    private fun preferences(context: Context) = context.applicationContext.getSharedPreferences("sdm_web_panel", Context.MODE_PRIVATE)
}
