package com.espitman.sdm.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkPermissionPolicyTest {
    @Test
    fun wifiOnlyEnforcementAddsOnlyAccessNetworkState() {
        assertEquals("android.permission.ACCESS_NETWORK_STATE", NetworkPermissionPolicy.ACCESS_NETWORK_STATE)
        assertTrue(
            NetworkPermissionPolicy.DISALLOWED_BROAD_PERMISSIONS.containsAll(
                listOf(
                    "android.permission.CHANGE_NETWORK_STATE",
                    "android.permission.CHANGE_WIFI_STATE",
                    "android.permission.ACCESS_WIFI_STATE",
                    "android.permission.READ_EXTERNAL_STORAGE",
                    "android.permission.WRITE_EXTERNAL_STORAGE",
                    "android.permission.MANAGE_EXTERNAL_STORAGE",
                ),
            ),
        )
        assertFalse(
            NetworkPermissionPolicy.DISALLOWED_BROAD_PERMISSIONS.contains(
                NetworkPermissionPolicy.ACCESS_NETWORK_STATE,
            ),
        )
        assertTrue(
            NetworkPermissionPolicy.ALLOWED_MANIFEST_PERMISSIONS.containsAll(
                listOf(
                    "android.permission.INTERNET",
                    NetworkPermissionPolicy.ACCESS_NETWORK_STATE,
                    "android.permission.WAKE_LOCK",
                    "android.permission.POST_NOTIFICATIONS",
                    "android.permission.FOREGROUND_SERVICE",
                    "android.permission.FOREGROUND_SERVICE_DATA_SYNC",
                    "android.permission.RECEIVE_BOOT_COMPLETED",
                ),
            ),
        )
        assertTrue(
            NetworkPermissionPolicy.ALLOWED_MANIFEST_PERMISSIONS
                .intersect(NetworkPermissionPolicy.DISALLOWED_BROAD_PERMISSIONS)
                .isEmpty(),
        )
    }
}
