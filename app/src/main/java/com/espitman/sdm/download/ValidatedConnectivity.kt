package com.espitman.sdm.download

enum class ValidatedTransport {
    NONE,
    WIFI,
    ETHERNET,
    CELLULAR,
    OTHER,
}

data class ValidatedConnectivity(
    val transport: ValidatedTransport,
) {
    val isValidated: Boolean get() = transport != ValidatedTransport.NONE

    companion object {
        val Offline = ValidatedConnectivity(ValidatedTransport.NONE)
    }
}

data class NetworkCapabilityFlags(
    val hasInternet: Boolean,
    val validated: Boolean,
    val hasWifi: Boolean,
    val hasEthernet: Boolean,
    val hasCellular: Boolean,
    val hasOtherTransport: Boolean = false,
)

object ValidatedConnectivityClassifier {
    fun classify(flags: NetworkCapabilityFlags): ValidatedConnectivity {
        if (!flags.hasInternet || !flags.validated) return ValidatedConnectivity.Offline
        val transport = when {
            flags.hasWifi -> ValidatedTransport.WIFI
            flags.hasEthernet -> ValidatedTransport.ETHERNET
            flags.hasCellular -> ValidatedTransport.CELLULAR
            flags.hasOtherTransport -> ValidatedTransport.OTHER
            else -> return ValidatedConnectivity.Offline
        }
        return ValidatedConnectivity(transport)
    }
}

object WifiOnlyPolicy {
    fun allowsTransfers(wifiOnly: Boolean, connectivity: ValidatedConnectivity): Boolean {
        if (!connectivity.isValidated) return false
        if (!wifiOnly) return true
        return connectivity.transport == ValidatedTransport.WIFI ||
            connectivity.transport == ValidatedTransport.ETHERNET
    }
}
