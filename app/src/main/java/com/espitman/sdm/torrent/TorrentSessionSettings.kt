package com.espitman.sdm.torrent

import com.frostwire.jlibtorrent.SettingsPack
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.NetworkInterface

/** Explicit addresses avoid Android's restricted native route enumeration. */
internal object TorrentSessionSettings {
    /** libtorrent excludes LAN peers from its global class by default. SDM limits apply to every peer. */
    fun applyRateClassToAllPeers(session: com.frostwire.jlibtorrent.SessionManager) {
        val filter = com.frostwire.jlibtorrent.swig.ip_filter()
        // libtorrent global_peer_class_id is 0: the class mask is 1 << 0.
        filter.add_rule(com.frostwire.jlibtorrent.Address("0.0.0.0").swig(), com.frostwire.jlibtorrent.Address("255.255.255.255").swig(), 1L)
        filter.add_rule(com.frostwire.jlibtorrent.Address("::").swig(), com.frostwire.jlibtorrent.Address("ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff").swig(), 1L)
        session.swig().set_peer_class_filter(filter)
    }
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
