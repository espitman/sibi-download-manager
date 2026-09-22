package com.espitman.sdm.download

import android.net.ConnectivityManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ValidatedConnectivityMonitorTest {
    @Test
    fun snapshotMatchesActiveNetworkAndClassifierBeforeCallbacks() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val monitor = AndroidValidatedConnectivityMonitor(context)
        val manager = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val flags = networkCapabilityFlagsOf(manager.getNetworkCapabilities(manager.activeNetwork))
        val expected = ValidatedConnectivityClassifier.classify(flags)
        assertEquals(expected, monitor.current())
        assertEquals(expected, monitor.connectivity.value)
        if (flags.hasWifi && flags.validated && flags.hasInternet) {
            assertEquals(ValidatedTransport.WIFI, expected.transport)
            assertTrue(WifiOnlyPolicy.allowsTransfers(wifiOnly = true, expected))
        }
        if (flags.hasEthernet && flags.validated && flags.hasInternet && !flags.hasWifi) {
            assertEquals(ValidatedTransport.ETHERNET, expected.transport)
            assertTrue(WifiOnlyPolicy.allowsTransfers(wifiOnly = true, expected))
        }
        if (flags.hasCellular && flags.validated && flags.hasInternet && !flags.hasWifi && !flags.hasEthernet) {
            assertEquals(ValidatedTransport.CELLULAR, expected.transport)
            assertFalse(WifiOnlyPolicy.allowsTransfers(wifiOnly = true, expected))
            assertTrue(WifiOnlyPolicy.allowsTransfers(wifiOnly = false, expected))
        }
        if (!flags.validated || !flags.hasInternet) {
            assertEquals(ValidatedConnectivity.Offline, expected)
            assertFalse(WifiOnlyPolicy.allowsTransfers(wifiOnly = false, expected))
        }
    }
}
