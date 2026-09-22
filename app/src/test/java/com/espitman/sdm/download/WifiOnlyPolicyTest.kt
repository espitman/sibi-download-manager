package com.espitman.sdm.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiOnlyPolicyTest {
    @Test
    fun wifiOnlyAllowsValidatedWifiAndEthernetAndBlocksCellularOfflineAndOther() {
        assertTrue(allowed(wifiOnly = true, ValidatedTransport.WIFI))
        assertTrue(allowed(wifiOnly = true, ValidatedTransport.ETHERNET))
        assertFalse(allowed(wifiOnly = true, ValidatedTransport.CELLULAR))
        assertFalse(allowed(wifiOnly = true, ValidatedTransport.OTHER))
        assertFalse(allowed(wifiOnly = true, ValidatedTransport.NONE))
    }

    @Test
    fun wifiOnlyOffAllowsAnyValidatedNetworkIncludingCellular() {
        assertTrue(allowed(wifiOnly = false, ValidatedTransport.WIFI))
        assertTrue(allowed(wifiOnly = false, ValidatedTransport.ETHERNET))
        assertTrue(allowed(wifiOnly = false, ValidatedTransport.CELLULAR))
        assertTrue(allowed(wifiOnly = false, ValidatedTransport.OTHER))
        assertFalse(allowed(wifiOnly = false, ValidatedTransport.NONE))
    }

    @Test
    fun classifierRequiresValidatedInternetBeforeAssigningTransport() {
        assertEquals(
            ValidatedConnectivity.Offline,
            ValidatedConnectivityClassifier.classify(
                NetworkCapabilityFlags(
                    hasInternet = true,
                    validated = false,
                    hasWifi = true,
                    hasEthernet = false,
                    hasCellular = false,
                ),
            ),
        )
        assertEquals(
            ValidatedConnectivity(ValidatedTransport.WIFI),
            ValidatedConnectivityClassifier.classify(
                NetworkCapabilityFlags(
                    hasInternet = true,
                    validated = true,
                    hasWifi = true,
                    hasEthernet = false,
                    hasCellular = true,
                ),
            ),
        )
        assertEquals(
            ValidatedConnectivity(ValidatedTransport.ETHERNET),
            ValidatedConnectivityClassifier.classify(
                NetworkCapabilityFlags(
                    hasInternet = true,
                    validated = true,
                    hasWifi = false,
                    hasEthernet = true,
                    hasCellular = false,
                ),
            ),
        )
        assertEquals(
            ValidatedConnectivity(ValidatedTransport.CELLULAR),
            ValidatedConnectivityClassifier.classify(
                NetworkCapabilityFlags(
                    hasInternet = true,
                    validated = true,
                    hasWifi = false,
                    hasEthernet = false,
                    hasCellular = true,
                ),
            ),
        )
        assertEquals(
            ValidatedConnectivity(ValidatedTransport.OTHER),
            ValidatedConnectivityClassifier.classify(
                NetworkCapabilityFlags(
                    hasInternet = true,
                    validated = true,
                    hasWifi = false,
                    hasEthernet = false,
                    hasCellular = false,
                    hasOtherTransport = true,
                ),
            ),
        )
        assertEquals(
            ValidatedConnectivity.Offline,
            ValidatedConnectivityClassifier.classify(
                NetworkCapabilityFlags(
                    hasInternet = false,
                    validated = true,
                    hasWifi = true,
                    hasEthernet = false,
                    hasCellular = false,
                ),
            ),
        )
    }

    private fun allowed(wifiOnly: Boolean, transport: ValidatedTransport): Boolean =
        WifiOnlyPolicy.allowsTransfers(wifiOnly, ValidatedConnectivity(transport))
}
