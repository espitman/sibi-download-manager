package com.espitman.sdm.torrent

import android.content.Context
import java.util.concurrent.atomic.AtomicBoolean

internal object TorrentNativeRuntime {
    private val prepared = AtomicBoolean()
    fun prepare(context: Context) {
        if (!prepared.compareAndSet(false, true)) return
        // The upstream JNI loader extracts a new cache file per process. Android does
        // not run JVM exit hooks; reclaim old copies before loading this process's JNI.
        val extracted = Regex("jlibtorrent-[0-9]+\\.so")
        context.cacheDir.listFiles().orEmpty().filter { it.isFile && extracted.matches(it.name) }.forEach { it.delete() }
    }
}
