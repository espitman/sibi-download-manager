package com.espitman.sdm.download

object NetworkPermissionPolicy {
    const val ACCESS_NETWORK_STATE = "android.permission.ACCESS_NETWORK_STATE"

    val DISALLOWED_BROAD_PERMISSIONS = setOf(
        "android.permission.CHANGE_NETWORK_STATE",
        "android.permission.CHANGE_WIFI_STATE",
        "android.permission.ACCESS_WIFI_STATE",
        "android.permission.NEARBY_WIFI_DEVICES",
        "android.permission.READ_EXTERNAL_STORAGE",
        "android.permission.WRITE_EXTERNAL_STORAGE",
        "android.permission.MANAGE_EXTERNAL_STORAGE",
        "android.permission.MANAGE_DOCUMENTS",
    )
}
