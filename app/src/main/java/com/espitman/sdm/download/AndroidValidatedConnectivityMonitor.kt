package com.espitman.sdm.download

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

fun networkCapabilityFlagsOf(capabilities: NetworkCapabilities?): NetworkCapabilityFlags {
    if (capabilities == null) {
        return NetworkCapabilityFlags(
            hasInternet = false,
            validated = false,
            hasWifi = false,
            hasEthernet = false,
            hasCellular = false,
            hasOtherTransport = false,
        )
    }
    val hasWifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    val hasEthernet = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    val hasCellular = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
    return NetworkCapabilityFlags(
        hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
        validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
        hasWifi = hasWifi,
        hasEthernet = hasEthernet,
        hasCellular = hasCellular,
        hasOtherTransport = !hasWifi && !hasEthernet && !hasCellular && hasAnyTransport(capabilities),
    )
}

private fun hasAnyTransport(capabilities: NetworkCapabilities): Boolean {
    val known = intArrayOf(
        NetworkCapabilities.TRANSPORT_WIFI,
        NetworkCapabilities.TRANSPORT_CELLULAR,
        NetworkCapabilities.TRANSPORT_ETHERNET,
        NetworkCapabilities.TRANSPORT_BLUETOOTH,
        NetworkCapabilities.TRANSPORT_VPN,
        NetworkCapabilities.TRANSPORT_WIFI_AWARE,
        NetworkCapabilities.TRANSPORT_LOWPAN,
    )
    if (known.any { capabilities.hasTransport(it) }) return true
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_USB)
    ) {
        return true
    }
    return false
}

class AndroidValidatedConnectivityMonitor(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val mutableConnectivity = MutableStateFlow(readCurrent())
    val connectivity: StateFlow<ValidatedConnectivity> = mutableConnectivity.asStateFlow()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            publish(read(network))
        }

        override fun onLost(network: Network) {
            publish(readCurrent())
        }

        override fun onUnavailable() {
            publish(ValidatedConnectivity.Offline)
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            publish(ValidatedConnectivityClassifier.classify(networkCapabilityFlagsOf(networkCapabilities)))
        }
    }

    init {
        connectivityManager.registerDefaultNetworkCallback(callback)
    }

    fun current(): ValidatedConnectivity = mutableConnectivity.value

    private fun publish(next: ValidatedConnectivity) {
        mutableConnectivity.value = next
    }

    private fun readCurrent(): ValidatedConnectivity = read(connectivityManager.activeNetwork)

    private fun read(network: Network?): ValidatedConnectivity {
        val capabilities = network?.let { connectivityManager.getNetworkCapabilities(it) }
        return ValidatedConnectivityClassifier.classify(networkCapabilityFlagsOf(capabilities))
    }
}
