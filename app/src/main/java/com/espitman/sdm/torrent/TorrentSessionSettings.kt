package com.espitman.sdm.torrent

import com.frostwire.jlibtorrent.SettingsPack
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.NetworkInterface

/** Explicit addresses avoid Android's restricted native route enumeration. */
internal object TorrentSessionSettings {
    fun create(connections: Int): SettingsPack {
        val addresses = runCatching {
            NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp }.flatMap { network ->
                network.inetAddresses.toList().mapNotNull { address ->
                    when {
                        address is Inet4Address -> "${address.hostAddress}:0"
                        address is Inet6Address && !address.isLinkLocalAddress -> "[${address.hostAddress}]:0"
                        else -> null
                    }
                }
            }
        }.getOrDefault(emptyList())
        return SettingsPack().anonymousMode(true).connectionsLimit(connections)
            .listenInterfaces((addresses + "127.0.0.1:0").distinct().joinToString(","))
    }
}
