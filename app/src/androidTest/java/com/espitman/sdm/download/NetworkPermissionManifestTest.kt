package com.espitman.sdm.download

import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NetworkPermissionManifestTest {
    @Test
    fun declaresOnlyAccessNetworkStateForWifiOnlyEnforcement() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val info = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS,
        )
        val requested = info.requestedPermissions?.toSet().orEmpty()
        assertTrue(requested.contains(NetworkPermissionPolicy.ACCESS_NETWORK_STATE))
        NetworkPermissionPolicy.DISALLOWED_BROAD_PERMISSIONS.forEach { permission ->
            assertFalse("$permission must not be requested", requested.contains(permission))
        }
    }
}
