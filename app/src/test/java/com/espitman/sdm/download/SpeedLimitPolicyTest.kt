package com.espitman.sdm.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpeedLimitPolicyTest {
    @Test
    fun unlimitedImposesZeroThrottlingOnEveryTransport() {
        for (wifiOnly in listOf(false, true)) {
            for (transport in ValidatedTransport.entries) {
                assertNull(
                    SpeedLimitPolicy.effectiveBytesPerSecond(
                        unlimitedSpeed = true,
                        speedLimitMbps = 5f,
                        speedLimitWifiOnly = wifiOnly,
                        transport = transport,
                    ),
                )
            }
        }
    }

    @Test
    fun allNetworkCapAppliesOnEveryTransportIncludingNone() {
        val expected = SpeedLimitUnits.bytesPerSecond(12f)
        for (transport in ValidatedTransport.entries) {
            assertEquals(
                expected,
                SpeedLimitPolicy.effectiveBytesPerSecond(
                    unlimitedSpeed = false,
                    speedLimitMbps = 12f,
                    speedLimitWifiOnly = false,
                    transport = transport,
                ),
            )
        }
    }

    @Test
    fun wifiOnlyCapAppliesOnlyWhileValidatedTransportIsWifi() {
        val expected = SpeedLimitUnits.bytesPerSecond(8f)
        assertEquals(
            expected,
            SpeedLimitPolicy.effectiveBytesPerSecond(
                unlimitedSpeed = false,
                speedLimitMbps = 8f,
                speedLimitWifiOnly = true,
                transport = ValidatedTransport.WIFI,
            ),
        )
        for (transport in listOf(
            ValidatedTransport.CELLULAR,
            ValidatedTransport.ETHERNET,
            ValidatedTransport.OTHER,
            ValidatedTransport.NONE,
        )) {
            assertNull(
                SpeedLimitPolicy.effectiveBytesPerSecond(
                    unlimitedSpeed = false,
                    speedLimitMbps = 8f,
                    speedLimitWifiOnly = true,
                    transport = transport,
                ),
            )
        }
    }
}
