package com.espitman.sdm.storage

import java.io.File

interface StorageCapacityProbe {
    fun queryLocalPath(path: File): StorageCapacity
    fun queryTree(treeUri: String): StorageCapacity

    companion object {
        val Unknown: StorageCapacityProbe = object : StorageCapacityProbe {
            override fun queryLocalPath(path: File): StorageCapacity = StorageCapacity.Unknown
            override fun queryTree(treeUri: String): StorageCapacity = StorageCapacity.Unknown
        }
    }
}
